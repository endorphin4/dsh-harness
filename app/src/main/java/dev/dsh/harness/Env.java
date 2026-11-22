package dev.dsh.harness;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 引擎与脚本子进程的统一环境。
 *
 * <p>以前这套变量在 {@code Engine.spawn()} 和 {@code Engine.applyEnginePackage()} 里各抄了一份，
 * 改一处漏一处（例如 OPENSSL_CONF 曾经只在其中一条路径上），所以收成一个入口。
 */
final class Env {
    private Env() {
    }

    /** 把 rootfs 所需的变量塞进进程环境。 */
    static void apply(ProcessBuilder pb, File root) {
        File usr = new File(root, "usr");
        File home = new File(root, "home");
        Map<String, String> e = pb.environment();
        e.put("LD_LIBRARY_PATH", new File(usr, "lib").getAbsolutePath());
        e.put("HOME", home.getAbsolutePath());
        e.put("DSH_HOME", new File(home, ".dsh").getAbsolutePath());
        // 临时目录放在 cache/ 下：home/ 里只留用户自己的东西，
        // 工作区选择器扫 home/ 时就不会冒出个 "tmp" 让人误选。
        e.put("TMPDIR", new File(root, "cache/tmp").getAbsolutePath());
        e.put("PATH", new File(usr, "bin").getAbsolutePath() + ":/system/bin:/system/xbin");
        e.put("LANG", "zh_CN.UTF-8");
        // 没有 TERM 时 App 内终端是 dumb：tmux/vim/git 的彩色与清屏全退化。
        e.put("TERM", "xterm-256color");
        e.put("COLORTERM", "truecolor");
        // Termux 版 node 的 OpenSSL 目录是编译期写死的 /data/data/com.termux/...，读不到就启动失败；
        // 用环境变量把 conf 与证书指到载荷自己的副本（详见 PROJECT.md §7 第 18 条）。
        e.put("OPENSSL_CONF", new File(usr, "etc/tls/openssl.cnf").getAbsolutePath());
        e.put("SSL_CERT_FILE", new File(usr, "etc/tls/cert.pem").getAbsolutePath());
        e.put("SSL_CERT_DIR", new File(usr, "etc/tls").getAbsolutePath());
        e.put("TZ", "Asia/Shanghai");
        e.put("PREFIX", usr.getAbsolutePath());
        e.put("SHELL", new File(usr, "bin/bash").getAbsolutePath());
        // grep/glob 用引擎补丁（EnginePatch.ensureRg）换成这一对：
        // 私有目录的 ELF 不能直接 exec，必须经过系统动态链接器拉起。
        e.put("DSHB_RG", new File(usr, "bin/rg").getAbsolutePath());
        e.put("DSHB_EXEC_WRAPPER", "/system/bin/linker64");
        // 沙箱默认值由 dsh-base 的 cordis.patch.yml 从环境变量读；
        // 手机上的私有存储当工作区时 workspace-write 会把常见的 shell 动作全拦住。
        e.put("DSH_PERMISSION_MODE", "danger-full-access");
    }

    /** 以 rootfs 的 home 为工作目录、合并 stderr 的 ProcessBuilder。 */
    static ProcessBuilder builder(File root, List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(new File(root, "home"));
        apply(pb, root);
        return pb;
    }
}
