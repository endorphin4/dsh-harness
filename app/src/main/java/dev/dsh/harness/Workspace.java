package dev.dsh.harness;

import android.util.Log;

import java.io.File;
import java.io.IOException;

/** 工作区种子与路径归一：只碰 workspace.json / settings.yaml / cordis.patch.yml 这几份文本。 */
final class Workspace {

    private static final String TAG = "DSH-B";

    private Workspace() {}

    static String ensureWorkspace(File root) {
        File ws = new File(root, "home/workspace");
        File reg = new File(root, "home/.dsh/storages/workspace.json");
        try {
            if (!ws.isDirectory()) ws.mkdirs();
            boolean seeded = false;
            if (!reg.isFile() || reg.length() < 32) { seedWorkspace(root); seeded = true; }
            // 载荷里 3c 步种下的注册表用的是 /data/data/... 形式（构建机的路径观），
            // 而 DSH 的 workspace 校验拿它跟真实目录比 ⇒ 选中就失败（用户报「选 workplace 不行、选 temp 可以」）。
            // 这里把注册表里的前缀统一改写成运行时真实绝对路径。
            String text = new String(java.nio.file.Files.readAllBytes(reg.toPath()), "UTF-8");
            String fixed = text
                    .replace("/data/data/dev.dsh.harness/files", root.getAbsolutePath())
                    .replace("/data/user/0/dev.dsh.harness/files", root.getAbsolutePath());
            if (!fixed.equals(text)) {
                java.nio.file.Files.write(reg.toPath(), fixed.getBytes("UTF-8"));
                seeded = true;
            }
            return "ws: 目录=" + (ws.isDirectory() ? "有" : "无")
                    + " 注册表=" + (reg.isFile() ? reg.length() + "B" : "无")
                    + (seeded ? "（已补种/改写路径）" : "");
        } catch (Throwable e) {
            return "ws 检查失败：" + e;
        }
    }

    static String normalizeSeedPaths(File root) {
        String real = root.getAbsolutePath();
        String[] rels = {
                "home/.dsh/storages/workspace.json",
                "home/.dsh/settings.yaml",
                "home/.dsh/profiles/web/cordis.patch.yml",
                "home/.dsh/profiles/headless/cordis.patch.yml",
                "home/.dsh/profiles/headless-bad/cordis.patch.yml"
        };
        int changed = 0;
        for (String rel : rels) {
            File f = new File(root, rel);
            if (!f.isFile() || f.length() > 4L * 1024 * 1024) continue;
            try {
                String t = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                String fixed = t
                        .replace("/data/data/dev.dsh.harness/files", real)
                        .replace("/data/user/0/dev.dsh.harness/files", real)
                        .replace("/data/data/app.dsh.shellb/files", real)
                        .replace("/data/user/0/app.dsh.shellb/files", real);
                if (!fixed.equals(t)) {
                    java.nio.file.Files.write(f.toPath(), fixed.getBytes("UTF-8"));
                    changed++;
                }
            } catch (Throwable ignore) {
                // 单个文件失败不影响启动
            }
        }
        return "种子路径归一：改写 " + changed + " 个文件";
    }

    static void seedWorkspace(File root) {
        try {
            File ws = new File(root, "home/workspace");
            if (!ws.isDirectory() && !ws.mkdirs()) return;
            File storages = new File(root, "home/.dsh/storages");
            File reg = new File(storages, "workspace.json");
            if (reg.isFile()) return;
            if (!storages.isDirectory() && !storages.mkdirs()) return;
            String uuid = java.util.UUID.randomUUID().toString();
            String now = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                    .format(new java.util.Date());
            String path = ws.getAbsolutePath();
            String json = "{\n"
                    + "  \"unit\": { \"name\": \"workspace\", \"version\": 2 },\n"
                    + "  \"global\": { \"initialized\": true, \"workspaceIds\": [\"" + uuid + "\"],\n"
                    + "    \"archivedSessionIds\": [], \"pinnedSessionIds\": [] },\n"
                    + "  \"tables\": { \"workspaces\": {\n"
                    + "    \"" + uuid + "\": { \"path\": \"" + path + "\", \"title\": \"工作区\",\n"
                    + "      \"sessionIds\": [], \"createdAt\": \"" + now + "\", \"updatedAt\": \"" + now + "\" }\n"
                    + "  } }\n}\n";
            java.nio.file.Files.write(reg.toPath(), json.getBytes("UTF-8"));
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                java.nio.file.Files.setPosixFilePermissions(reg.toPath(),
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
            Log.i(TAG, "已预置默认工作区：" + path);
        } catch (Throwable t) {
            Log.w(TAG, "预置工作区失败（不致命）", t);
        }
    }
}
