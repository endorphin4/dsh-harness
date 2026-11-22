package dev.dsh.harness;

import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 引擎侧的运行时补丁。
 *
 * <p>Android 上「私有目录里的可执行文件」不能直接 exec（targetSdk≥29 的
 * {@code untrusted_app} 域禁 app_data_file 执行），所以 node、bash 都是
 * {@code /system/bin/linker64 <bin>} 拉起来的。DSH 自带的 ripgrep 是
 * {@code @vscode/ripgrep-android-arm64} 里的静态二进制，spawn 它直接 EACCES，
 * 表现为 grep/glob 工具报「ripgrep launch failed」。
 *
 * <p>载荷里本来就有一份 Termux 构建的动态 {@code usr/bin/rg}（或引擎更新后仍在），
 * 这里把 {@code dsh-tool-fs-search} 的 spawn 参数改成：
 * {@code process.env.DSHB_EXEC_WRAPPER}（= {@code /system/bin/linker64}）+
 * {@code process.env.DSHB_RG}（= 载荷里的 rg）。补丁放在引擎目录里，所以引擎
 * 换包以后要在启动时重新应用一次；以 {@code __dshbRgArgv} 标记幂等。
 */
final class EnginePatch {
    private static final String TAG = "EnginePatch";

    private static final String SRC = "usr/lib/node_modules/@deepseek-ai/dsh/"
            + "node_modules/@deepseek-ai/dsh-tool-fs-search/lib/index.js";

    private static final String MARK = "__dshbRgArgv";

    private static final String HELPER = "async function " + MARK + "() {\n"
            + "\tconst alt = process.env.DSHB_RG;\n"
            + "\tconst w = process.env.DSHB_EXEC_WRAPPER;\n"
            + "\tif (alt !== void 0 && alt.length > 0) return w !== void 0 && w.length > 0 ? [w, alt] : [alt];\n"
            + "\tconst p = await resolveRgPath();\n"
            + "\treturn w !== void 0 && w.length > 0 ? [w, p] : [p];\n"
            + "}\n";

    private static final String CALL_OLD = "\t\t\t\tawait resolveRgPath(),";
    private static final String CALL_NEW = "\t\t\t\t...(await " + MARK + "()),";

    private EnginePatch() {
    }

    /** 子进程服务的 lib 目录（文件名带 hash，按前缀找）。 */
    private static final String SUB_DIR = "usr/lib/node_modules/@deepseek-ai/dsh/"
            + "node_modules/@deepseek-ai/dsh-subprocess-local/lib";

    private static final String SUB_OLD =
            "if (platform === \"linux\") return new LinuxProcessInspector(arch, internals);";
    private static final String SUB_NEW =
            "if (platform === \"linux\" || platform === \"android\") return new LinuxProcessInspector(arch, internals);";

    /** 终端用的 pty 绑定：npm 包里的 prebuilds 是 glibc 的，Android 上 dlopen 会失败。 */
    private static final String PTY = "usr/lib/node_modules/@deepseek-ai/dsh/"
            + "node_modules/node-pty/build/Release/pty.node";

    /**
     * 浏览器侧栏补丁（语义等同 patches/<ver>/@deepseek-ai/dsh-client-ui-sidebar-browser/lib/client.js）。
     *
     * <p>rc.5 起这个文件被上游压缩成两行，整文件三方合并必然冲突，所以不再随补丁集下发，
     * 改成运行时按锚点注入：地址栏非网址输入走百度搜索、iframe 允许下载、占位文案改成
     * “输入网址或搜索词”。三处都带标记，重复执行不会叠加。
     */
    private static final String BROWSER = "usr/lib/node_modules/@deepseek-ai/dsh/"
            + "node_modules/@deepseek-ai/dsh-client-ui-sidebar-browser/lib/client.js";

    private static final String SEARCH_FALLBACK =
            "const __urlish=/^[A-Za-z][A-Za-z\\d+.-]*:(?!\\d+(?:[/?#]|$))/u.test(trimmed)"
            + "||/^[^\\s/?#]+\\.[A-Za-z]{2,}(?:[/?#:]|$)/u.test(trimmed)"
            + "||/^localhost(?:[:/]|$)/.test(trimmed);"
            + "if(!__urlish)return{ok:true,target:{kind:`https`,"
            + "url:`https://www.baidu.com/s?wd=${encodeURIComponent(trimmed)}`,title:trimmed}};"
            + "/*__dshSearchFallback*/";

    private static final String SEARCH_MARK = "__dshSearchFallback";
    private static final String SANDBOX = "allow-popups-to-escape-sandbox";
    private static final String PLACEHOLDER_OLD = "Enter an HTTP(S) address";
    private static final String PLACEHOLDER_NEW = "Enter a URL or search term";

    static String ensureBrowser(File root) {
        try {
            File f = new File(root, BROWSER);
            if (!f.isFile()) return null;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String before = s;
            int hits = 0;

            if (!s.contains(SEARCH_MARK)) {
                int p = s.indexOf("parseBrowserAddress(");
                int c = p < 0 ? -1 : s.indexOf("const candidate", p);
                if (c > 0) {
                    s = s.substring(0, c) + SEARCH_FALLBACK + "\n" + s.substring(c);
                    hits++;
                }
            }
            if (!s.contains("sandbox allow-downloads")) {
                int i = s.indexOf(SANDBOX);
                if (i > 0 && s.startsWith("`", i + SANDBOX.length())) {
                    int end = i + SANDBOX.length();
                    s = s.substring(0, end) + " allow-downloads" + s.substring(end);
                    hits++;
                }
            }
            if (s.contains(PLACEHOLDER_OLD)) {
                s = s.replace(PLACEHOLDER_OLD, PLACEHOLDER_NEW);
                hits++;
            }
            if (hits == 0 || s.equals(before)) return null;
            Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
            return "浏览器补丁：应用 " + hits + " 处";
        } catch (Throwable e) {
            Log.w(TAG, "ensureBrowser", e);
            return null;
        }
    }

    /**
     * 终端两处补丁：
     * <ol>
     *   <li>dsh-subprocess-local 的平台判断只认 linux/darwin/win32；Termux 的 node
     *       报 {@code android} ⇒ createProcessInspector 直接抛
     *       「terminal inspection is unsupported on platform android」。这里把 android
     *       当 linux（/proc + LinuxProcessInspector 在 Android 上可用）。</li>
     *   <li>npm ci --ignore-scripts 不会给 node-pty 编 pty.node，包里的 prebuilds/linux-arm64
     *       是 glibc 产物（缺 libutil.so.1）；把载荷里 Termux 编的那份拷进 build/Release。</li>
     * </ol>
     * 幂等；返回给日志看的话（都齐了就返回 null）。
     */
    static String ensureTerminal(File root) {
        StringBuilder sb = new StringBuilder();
        File dir = new File(root, SUB_DIR);
        File[] files = dir.listFiles((d, n) -> n.startsWith("runner-launch-") && n.endsWith(".js"));
        boolean found = files != null && files.length > 0;
        boolean patched = false;
        if (found) {
            for (File f : files) {
                try {
                    String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    if (s.contains(SUB_NEW)) {
                        // 已补过
                    } else if (s.contains(SUB_OLD)) {
                        Files.write(f.toPath(), s.replace(SUB_OLD, SUB_NEW).getBytes(StandardCharsets.UTF_8));
                        patched = true;
                    } else {
                        sb.append("终端补丁：注入点不见了（").append(f.getName()).append("）；");
                    }
                } catch (Throwable e) {
                    sb.append("终端补丁失败：").append(e).append("；");
                }
            }
        }
        if (!found) {
            sb.append("终端补丁：找不到 dsh-subprocess-local（跳过）；");
        } else if (patched) {
            sb.append("终端补丁：android 按 linux 处理；");
        }

        File src = new File(root, "share/node-pty.node");
        File dst = new File(root, PTY);
        if (src.isFile()) {
            try {
                byte[] want = Files.readAllBytes(src.toPath());
                boolean same = dst.isFile() && dst.length() == want.length
                        && java.util.Arrays.equals(Files.readAllBytes(dst.toPath()), want);
                if (!same) {
                    File parent = dst.getParentFile();
                    if (parent != null) parent.mkdirs();
                    Files.write(dst.toPath(), want);
                    //noinspection ResultOfMethodCallIgnored
                    dst.setExecutable(true, false);
                    sb.append("pty 绑定：已写入（").append(want.length).append("B）；");
                }
            } catch (Throwable e) {
                sb.append("pty 绑定失败：").append(e).append("；");
            }
        } else if (!dst.isFile()) {
            sb.append("pty 绑定：缺 share/node-pty.node（终端起不来）；");
        }
        String out = sb.toString();
        return out.isEmpty() ? null : out;
    }

    /** 幂等：打过就直接返回 null；打上了返回一行给日志看的话。 */
    static String ensureRg(File root) {
        File f = new File(root, SRC);
        if (!f.isFile()) return "rg 补丁：找不到 " + SRC + "（跳过）";
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (s.contains(MARK)) return null;
            if (!s.contains(CALL_OLD)) return "rg 补丁：注入点不见了（引擎版本变了？跳过）";
            String helper = HELPER + "async function runRipgrep(";
            int at = s.indexOf("async function runRipgrep(");
            if (at < 0) return "rg 补丁：找不到 runRipgrep（跳过）";
            s = s.substring(0, at) + helper + s.substring(at + "async function runRipgrep(".length());
            s = s.replace(CALL_OLD, CALL_NEW);
            Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "rg 补丁：已应用到 " + f);
            return "rg 补丁：已应用（grep/glob 改走 " + (new File(root, "usr/bin/rg")) + "）";
        } catch (Throwable e) {
            Log.w(TAG, "rg 补丁失败", e);
            return "rg 补丁：失败 " + e;
        }
    }
}
