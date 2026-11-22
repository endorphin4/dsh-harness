package dev.dsh.harness;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 更新镜像：App 自己起一个只监听 127.0.0.1:8098 的小服务。
 *
 * <p>以前更新清单靠 Termux 里那个 8099 静态服务，Termux 进程一被系统回收，
 * 清单、APK、引擎包就全都“读不到”。这里把镜像挪进 App 自己的进程：
 *
 * <ul>
 *   <li>Termux 的构建脚本把 dist/shell-latest.json、dshb-latest.apk 推到这个端口的
 *       {@code PUT /put?name=...}，App 落在私有目录 files/mirror/；</li>
 *   <li>更新检查先读 {@code http://127.0.0.1:8098/shell-latest.json}，
 *       读不到才退到 8099 / jsDelivr / raw；</li>
 *   <li>App 被杀进程重建也没关系，文件在私有目录里，重启后服务自动再起。</li>
 * </ul>
 *
 * <p>只绑定回环地址，不对外网/局域网暴露；文件名白名单化，拒绝路径穿越。
 */
public final class Mirror {

    public static final int PORT = 8098;
    private static final long MAX_BYTES = 420L * 1024 * 1024;

    private static volatile boolean started;
    private static volatile String info = "未启动";

    private Mirror() {
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "mirror");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static String info() {
        return info;
    }

    public static synchronized void start(final Context ctx) {
        if (started) return;
        started = true;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
                info = "监听 127.0.0.1:" + PORT + "，目录 " + dir(app);
                Life.note(app, "更新镜像：" + info);
                while (true) {
                    final Socket s = ss.accept();
                    Thread h = new Thread(() -> handle(app, s), "mirror-req");
                    h.setDaemon(true);
                    h.start();
                }
            } catch (Throwable e) {
                info = "启动失败：" + e;
                Life.note(app, "更新镜像：" + info);
                if (ss != null) try {
                    ss.close();
                } catch (Throwable ignored) {
                }
            }
        }, "mirror");
        t.setDaemon(true);
        t.start();
    }

    private static void handle(Context app, Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(180000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());

            String req = readLine(in);
            if (req == null) return;
            String[] parts = req.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase();
            String target = parts[1];

            long len = -1;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        len = Long.parseLong(line.substring(i + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            String path = target;
            String name = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String kv : target.substring(q + 1).split("&")) {
                    if (kv.startsWith("name=")) name = kv.substring(5);
                }
            }
            name = safeName(name);

            if (method.equals("PUT") || method.equals("POST")) {
                if (name.isEmpty() || len <= 0 || len > MAX_BYTES) {
                    reply(out, 400, "text/plain", "bad name/length");
                    return;
                }
                File tmp = new File(dir(app), "." + name + ".part");
                File dst = new File(dir(app), name);
                byte[] buf = new byte[256 * 1024];
                long got = 0;
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 256 * 1024)) {
                    while (got < len) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, len - got));
                        if (n < 0) break;
                        os.write(buf, 0, n);
                        got += n;
                    }
                }
                if (got != len) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    reply(out, 400, "text/plain", "short body " + got + "/" + len);
                    return;
                }
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                if (!tmp.renameTo(dst)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    reply(out, 500, "text/plain", "rename failed");
                    return;
                }
                Life.note(app, "更新镜像：收到 " + name + " " + len + " B");
                reply(out, 200, "text/plain", "ok " + len);
                return;
            }

            if (method.equals("GET")) {
                if (path.equals("/") || path.equals("/list")) {
                    StringBuilder sb = new StringBuilder();
                    File[] fs = dir(app).listFiles();
                    if (fs != null) {
                        for (File f : fs) {
                            if (f.isFile() && !f.getName().startsWith(".")) {
                                sb.append(f.getName()).append('\t').append(f.length()).append('\n');
                            }
                        }
                    }
                    reply(out, 200, "text/plain; charset=utf-8", sb.toString());
                    return;
                }
                if (name.isEmpty()) name = safeName(path.substring(1));
                File f = new File(dir(app), name);
                if (name.isEmpty() || !f.isFile()) {
                    reply(out, 404, "text/plain", "no file");
                    return;
                }
                String head = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "Content-Length: " + f.length() + "\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.UTF_8));
                try (InputStream fin = new BufferedInputStream(new FileInputStream(f), 256 * 1024)) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = fin.read(buf)) > 0) out.write(buf, 0, n);
                }
                out.flush();
                return;
            }
            reply(out, 405, "text/plain", "method");
        } catch (Throwable ignored) {
            // 单个请求失败不影响服务
        }
    }

    private static String safeName(String raw) {
        if (raw == null) return "";
        String n = raw.trim();
        if (n.isEmpty() || n.length() > 96) return "";
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) return "";
        }
        return n.contains("..") ? "" : n;
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        return (c < 0 && sb.length() == 0) ? null : sb.toString();
    }

    private static void reply(OutputStream out, int code, String type, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "ERR") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }
}
