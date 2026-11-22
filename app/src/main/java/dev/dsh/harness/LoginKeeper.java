package dev.dsh.harness;

import android.util.Log;
import android.webkit.CookieManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 登录态守护。
 *
 * <p>DSH 的「登录账号」最终落在两处：网页侧的 cookie（按浏览器 + authority 签发）和
 * {@code home/.dsh/.credentials.yaml}。载荷升级时 home 会被整目录备份再恢复，KEEP 里已经有
 * 这两个文件；但配置文件被引擎重写、cookie 被换浏览器/换端口作废时仍会要求重新登录。
 *
 * <p>这里做两件事：
 * <ol>
 *   <li>把 {@code .credentials.yaml} 另存一份到 {@code files/.login-backup/}，升级后缺失就补回；</li>
 *   <li>把「cookie 数量 + 凭据是否存在」写进日志，方便确认是哪一半丢的。</li>
 * </ol>
 */
final class LoginKeeper {

    private static final String TAG = "DSH-B";

    private LoginKeeper() {}

    /** 会话期间也要备份：登录是在页面里发生的，只在启动时备份等于永远错过。 */
    static void syncBackup(File root) {
        try {
            File cred = new File(root, "home/.dsh/.credentials.yaml");
            if (cred.isFile() && cred.length() > 0) {
                File dir = new File(root, ".login-backup");
                dir.mkdirs();
                Files.copy(cred.toPath(), new File(dir, "credentials.yaml").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ignored) { }
    }

    /** 一份简短的目录盘点：谁被删了、谁还在，一眼能对上。 */
    static String state(File root) {
        StringBuilder sb = new StringBuilder("home 盘点：");
        String[] paths = {"home/.dsh/.credentials.yaml", "home/.dsh/settings.yaml",
                "home/.dsh/profiles/web/package.json", "home/.dsh/storages", "home/.dsh/sessions",
                "home/workspace", "home/试玩/dsh-engine-update.sh", "home/dsh-restart.sh",
                ".login-backup/credentials.yaml"};
        for (String p : paths) {
            File f = new File(root, p);
            sb.append(p.replace("home/", "")).append('=')
              .append(f.isFile() ? f.length() + "B" : (f.isDirectory() ? "dir" : "-")).append(' ');
        }
        return sb.toString();
    }

    static String ensure(File root, String url) {
        File dsh = new File(root, "home/.dsh");
        File cred = new File(dsh, ".credentials.yaml");
        File backupDir = new File(root, ".login-backup");
        File backup = new File(backupDir, "credentials.yaml");
        StringBuilder sb = new StringBuilder("登录态：");
        try {
            if (cred.isFile() && cred.length() > 0) {
                backupDir.mkdirs();
                Files.copy(cred.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                sb.append("凭据=有(").append(cred.length()).append("B，已备份)");
            } else if (backup.isFile()) {
                dsh.mkdirs();
                Files.copy(backup.toPath(), cred.toPath(), StandardCopyOption.REPLACE_EXISTING);
                sb.append("凭据=补回(").append(backup.length()).append("B)");
            } else {
                sb.append("凭据=无(未登录过)");
            }
        } catch (Throwable e) {
            Log.w(TAG, "登录态守护失败：" + e);
            sb.append("凭据=异常(").append(e).append(')');
        }
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            int n = 0;
            StringBuilder names = new StringBuilder();
            if (cookie != null) for (String c : cookie.split(";")) {
                if (c.trim().isEmpty()) continue;
                n++;
                if (names.length() < 60) names.append(c.trim().split("=")[0]).append(' ');
            }
            sb.append(" cookie名=").append(names);
            sb.append(" cookie=").append(n).append(" 条");
        } catch (Throwable e) {
            sb.append(" cookie=读取失败");
        }
        return sb.toString();
    }
}
