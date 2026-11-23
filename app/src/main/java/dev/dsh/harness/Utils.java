package dev.dsh.harness;

/**
 * 纯函数工具箱：不碰 Activity/WebView 状态，只做字符串与网络地址的搬运。
 * 从 MainActivity 里搬出来，一是让它瘦下来，二是这些逻辑能被单元测试直接调。
 */
public final class Utils {

    private Utils() {}

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static boolean looksLikeApk(String u) {
        if (u == null) return false;
        String path = u.split("[?#]")[0].toLowerCase();
        return path.endsWith(".apk");
    }

    public static boolean isLocal(String u) {
        return u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")
                || u.startsWith("file:") || u.startsWith("about:") || u.startsWith("data:")
                || u.startsWith("blob:");
    }

    public static String jsStr(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    public static String[] downloadAttempts(android.content.Context ctx, String url) {
        java.util.LinkedHashSet<String> list = new java.util.LinkedHashSet<>();
        String direct = resolveAssetUrl(ctx, url);
        if (direct != null) list.add(direct);
        list.add(url);
        if (url.startsWith("https://github.com/")) {
            list.add("https://gh-proxy.com/" + url);
            list.add("https://ghproxy.net/" + url);
        }
        return list.toArray(new String[0]);
    }

    public static String guessName(String url, String cd) {
        String name = null;
        if (cd != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("filename\\*?=(?:UTF-8''|\")?([^\";]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(cd);
            if (m.find()) name = m.group(1);
        }
        if (name == null || name.trim().isEmpty()) {
            String path = url == null ? "" : url.split("[?#]")[0];
            int slash = path.lastIndexOf('/');
            name = slash >= 0 ? path.substring(slash + 1) : path;
        }
        try {
            name = java.net.URLDecoder.decode(name, "UTF-8");
        } catch (Throwable ignored) {
            // 解不开就原样用
        }
        if (name == null || name.trim().isEmpty()) name = "download.bin";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public static String prettyNote(String msg) {
        if (msg == null) return "";
        String m = msg;
        int cut = m.indexOf('（');
        if (cut < 0) cut = m.indexOf('(');
        if (cut > 0) m = m.substring(0, cut);
        m = m.trim();
        if (m.isEmpty()) return "正在启动";
        if (m.contains("下载")) return "正在下载运行环境";
        if (m.contains("解包") || m.contains("解压") || m.contains("安装环境")) return "正在准备运行环境";
        if (m.contains("拉起") || m.contains("唤醒") || m.contains("启动引擎") || m.contains("启动 node")) return "正在启动引擎";
        if (m.contains("等待") || m.contains("就绪") || m.contains("回包")) return "正在等待就绪";
        if (m.contains("加载界面") || m.contains("加载页面")) return "正在打开界面";
        if (m.contains("页面没起来") || m.contains("重试")) return "马上就好";
        if (m.contains("修复") || m.contains("恢复")) return "正在自动恢复";
        return m;
    }

    public static String resolveAssetUrl(android.content.Context ctx, String url) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^https://github\\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/([^/?]+)")
                    .matcher(url);
            if (!m.find()) return url;
            String owner = m.group(1), repo = m.group(2), tag = m.group(3), asset = m.group(4);
            String api = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/tags/" + tag;
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(api).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "DSHShell");
            String body = "";
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                body = bos.toString("UTF-8");
            }
            if (c.getResponseCode() != 200) return url;
            String quoted = java.util.regex.Pattern.quote(asset);
            java.util.regex.Matcher am = java.util.regex.Pattern
                    .compile("\"id\\s*:\s*(\\d+)[^}]*?\"name\"\\s*:\\s*\"(" + quoted + ")\"")
                    .matcher(body);
            // 上面那条正则依赖字段顺序，不稳；换个更笨但可靠的办法：先按 name 定位，再往前找 id
            java.util.regex.Matcher nameMatcher = java.util.regex.Pattern
                    .compile("\\{\\s*\"url\"\\s*:\\s*\"([^\"]+/releases/assets/(\\d+))\"[^}]*?\"name\"\\s*:\\s*\"(" + quoted + ")\"")
                    .matcher(body);
            String assetApi = null;
            while (nameMatcher.find()) {
                assetApi = nameMatcher.group(1);
            }
            if (assetApi == null) return url;
            java.net.HttpURLConnection a = (java.net.HttpURLConnection) new java.net.URL(assetApi).openConnection();
            a.setInstanceFollowRedirects(false);
            a.setConnectTimeout(12000);
            a.setReadTimeout(20000);
            a.setRequestProperty("Accept", "application/octet-stream");
            a.setRequestProperty("User-Agent", "DSHShell");
            int code = a.getResponseCode();
            String loc = a.getHeaderField("Location");
            a.disconnect();
            if ((code == 302 || code == 301 || code == 307) && loc != null && loc.startsWith("https://")) {
                Life.note(ctx, "引擎包直链已解析：" + loc.substring(0, Math.min(80, loc.length())) + "…");
                return loc;
            }
        } catch (Throwable e) {
            Life.note(ctx, "解析直链失败（继续用原地址）：" + e);
        }
        return url;
    }

    /** 取一个 URL 的响应正文；失败返回 null。给「检查更新」这种小请求用。 */
    /**
     * 带缓存击穿的取文本：给 URL 追加一个时间戳参数。
     *
     * <p>为什么需要：jsDelivr 的 {@code @main} 路径有小时级缓存，发版后更新检查会一直读到旧清单
     * （2026-11-22 实测：发布 1.0.1 后 CDN 仍返回 1.0.0）。加一个每次不同的查询参数，
     * 对 CDN 来说就是另一个资源，必然回源。
     */
    public static String fetchFresh(String url, int timeoutMs) {
        if (url == null) return null;
        String u = url + (url.contains("?") ? "&" : "?") + "dshb=" + System.currentTimeMillis();
        return fetchText(u, timeoutMs);
    }

    public static String fetchText(String url, int timeoutMs) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "dsh-harness");
            if (c.getResponseCode() != 200) return null;
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 65536) break;
                }
                return bo.toString("UTF-8");
            }
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 粗粒度版本比较：a>b 返回 1，相等 0，a<b 返回 -1。忽略预发布细节，够我们判断「有没有新版本」。 */
    public static int cmpVersion(String a, String b) {
        if (a == null) return -1;
        if (b == null) return 1;
        String[] x = a.replaceAll("^v", "").split("[.+-]");
        String[] y = b.replaceAll("^v", "").split("[.+-]");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            int c;
            try {
                c = Integer.compare(Integer.parseInt(p), Integer.parseInt(q));
            } catch (NumberFormatException e) {
                c = p.compareTo(q);
            }
            if (c != 0) return c > 0 ? 1 : -1;
        }
        return 0;
    }

}
