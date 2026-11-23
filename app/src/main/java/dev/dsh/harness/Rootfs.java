package dev.dsh.harness;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;

/**
 * 快照安装器：下载 → 解包 → 标记。
 *
 * 为什么是 tar.gz 而不是原厂的 tar.xz：Android 里没有 xz 解码器，
 * 而 gzip 是 JDK 自带的（GZIPInputStream），tar 格式自己解析 512 字节头就够了。
 * 代价是 242 MB 而不是 149 MB —— 走本机回环，无所谓。
 *
 * 关键点：必须保留 **符号链接**（usr/bin/dsh、libtermux-exec.so 等一串）和
 * **可执行位**（usr/bin 下的所有东西），否则 node 起不来。
 */
final class Rootfs {
    private static final String TAG = "DSH-B";

    /** 安装进行中的哨兵：存在 ⇒ 上次是在「删掉 home 之后」中断的，.keep 才是用户数据的权威副本。 */
    private static final String INSTALLING = ".installing";

    /** 覆盖安装时保留设备上的 dpkg 数据库：apt 装的包不能被载荷的 status/list 重置。 */
    private static volatile boolean keepDpkgDb = false;

    /** 增量覆盖时也要保住的目录：apt 的包索引与已下载缓存。
     *  否则换一次载荷，apt 就「认识 75 个已装包、却 locate 不到任何包」。 */
    private static final String[] KEEP_DIRS = {
            "usr/var/lib/apt/lists/",
            "usr/var/cache/apt/archives/",
    };

    private static boolean isKeepDir(String name) {
        for (String d : KEEP_DIRS) {
            if (name.startsWith(d)) return true;
        }
        return false;
    }
    static final String MARKER = ".rootfs-installed";

    interface Progress {
        void step(String msg);
        void bytes(long done, long total);
    }

    private Rootfs() {}

    /**
     * 快照直接铺在 filesDir 根上（不是 filesDir/rootfs）。
     * 原因：载荷里 cordis.patch.yml / dsh-node 写死的路径是
     * /data/data/<包名>/files/usr/...，安装时我们已经把包名换成自己的，
     * 只有让 usr、home 直接位于 filesDir 下，那些路径才恰好命中。
     */
    static File dir(Context ctx) { return ctx.getFilesDir(); }

    static boolean isInstalled(File root) {
        File m = new File(root, MARKER);
        if (!m.isFile()) return false;
        try (InputStream in = new FileInputStream(m)) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            return n > 0 && new String(buf, 0, n, StandardCharsets.UTF_8).trim().equals(Build.VERSION_SNAPSHOT);
        } catch (IOException e) {
            return false;
        }
    }


    /**
     * 建两条**等长替身**符号链接：`<data>/usr0 -> files/usr`、`<data>/home0 -> files/home`。
     *
     * <p>载荷里的 ELF（apt / dpkg / node / bash / lib*.so，700 多个文件）被重定位成
     * `/data/data/dev.dsh.harness/usr0`——这个路径和 Termux 原本的
     * `/data/data/com.termux/files/usr` 一样是 31 字节，所以能原地改字符串、ELF 偏移不变。
     * 但真实目录是 `files/usr`，必须靠这两条链接把替身路径接上；没有它们，
     * bash 一启动就找不到 PATH，apt 也找不到自己的库。
     *
     * <p>链接建在 data 目录根（不是 files/ 里面）：只有这样才能凑出 31/32 字节。
     */
    static void ensurePrefixLinks(final File filesDir) {
        final File dataDir = filesDir.getParentFile();
        if (dataDir == null) return;
        link(dataDir, "usr0", "files/usr");
        link(dataDir, "home0", "files/home");
        ensureTermuxAlias(filesDir);
    }

    /**
     * Termux 的 .deb 里路径是**绝对**的 {@code ./data/data/com.termux/files/usr/...}，
     * 而我们的 dpkg 是用 {@code --instdir=<本 App 前缀>} 跑的 —— dpkg 会把它再拼一次前缀，
     * 结果装到 {@code <前缀>/data/data/com.termux/files/usr/bin/xxx}（双层前缀，用不了）。
     *
     * 修法不是改 dpkg，而是让那个「双层前缀路径」本身就是真路径：在
     * {@code <前缀>/data/data/com.termux/files/} 下建 usr / home 两个指向真目录的**绝对**
     * 软链接。这样 dpkg 写 {@code .../files/usr/bin/xxx} 时，实际落盘正是 {@code <前缀>/bin/xxx}，
     * 连 conffiles、.list 里的绝对路径也一并自洽（dpkg 自己 stat 时会穿过软链）。
     */
    private static void ensureTermuxAlias(final File filesDir) {
        // dpkg 的 --instdir 语义：.deb 里写死的绝对路径 ./data/data/com.termux/files/usr/bin/xxx
        // 会被解到 <前缀>/data/data/com.termux/files/usr/bin/xxx。
        // 也就是说别名必须建在「前缀里面」，不是 files/ 下面 —— 这是 1.1.x 三版都没发现的错位：
        // 以前建成 files/data/... ，dpkg 实际写的是 files/usr/data/... ，两边永远对不上。
        final File prefix = new File(filesDir, "usr");
        final File chain = new File(prefix, "data/data/com.termux/files");
        flattenStaleNested(prefix, filesDir);
        if (!chain.isDirectory() && !chain.mkdirs() && !chain.isDirectory()) {
            Log.w(TAG, "无法创建 dpkg 前缀别名目录：" + chain);
            return;
        }
        absoluteLink(new File(chain, "usr"), prefix.getAbsolutePath());
        absoluteLink(new File(chain, "home"), new File(filesDir, "home").getAbsolutePath());
    }

    /** 把历史上真正落到嵌套层里的文件搬回真位置，然后把空的嵌套树删掉。 */
    private static void flattenStaleNested(final File prefix, final File filesDir) {
        final File nested = new File(prefix, "data/data/com.termux/files");
        if (!nested.isDirectory() || Files.isSymbolicLink(nested.toPath())) return;
        try {
            final File nestedUsr = new File(nested, "usr");
            if (nestedUsr.isDirectory() && !Files.isSymbolicLink(nestedUsr.toPath())) {
                mergeInto(nestedUsr, prefix);
            }
            final File nestedHome = new File(nested, "home");
            if (nestedHome.isDirectory() && !Files.isSymbolicLink(nestedHome.toPath())) {
                mergeInto(nestedHome, new File(filesDir, "home"));
            }
            Backup.deleteTree(new File(prefix, "data"));
            Log.i(TAG, "已把嵌套前缀里的文件搬回真位置");
        } catch (Throwable e) {
            Log.w(TAG, "嵌套前缀清理失败：" + e);
        }
    }

    private static void mergeInto(final File src, final File dst) {
        final File[] kids = src.listFiles();
        if (kids == null) return;
        if (!dst.isDirectory() && !dst.mkdirs()) return;
        for (final File k : kids) {
            final File target = new File(dst, k.getName());
            try {
                Backup.deleteTree(target);
                if (!k.renameTo(target)) Backup.copyTree(k, target);
            } catch (Throwable ignored) { }
        }
    }

    /**
     * 每个载荷版本只强制一次「浅色主题」。
     *
     * <p>DSH 的主题 preference 存在 settings.yaml 里，而 settings.yaml 在 KEEP 名单里 ——
     * 老版本或旧会话把它写成 dark 之后，新载荷的 profile 补丁（ui-theme preference: light）
     * 会被用户设置覆盖，于是更新完还是深色。这里只在版本变化后修一次，之后用户自己切换不再干预。
     */
    static void ensureLightTheme(final File filesDir, final String snapshot) {
        final File dsh = new File(filesDir, "home/.dsh");
        final File settings = new File(dsh, "settings.yaml");
        final File marker = new File(dsh, ".theme-light");
        try {
            if (!dsh.isDirectory() && !dsh.mkdirs()) return;
            String text = settings.isFile()
                    ? new String(java.nio.file.Files.readAllBytes(settings.toPath()), java.nio.charset.StandardCharsets.UTF_8)
                    : "";
            if (text.contains("preference: dark")) {
                text = text.replace("preference: dark", "preference: light");
            } else if (text.contains("preference: system")) {
                text = text.replace("preference: system", "preference: light");
            } else if (!text.contains("ui-theme")) {
                if (!text.isEmpty() && !text.endsWith("\n")) text += "\n";
                text += "ui-theme:\n  preference: light\n";
            }
            java.nio.file.Files.write(settings.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.write(marker.toPath(), (snapshot == null ? "1" : snapshot)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Log.i(TAG, "已把默认主题设为浅色（" + snapshot + "）");
        } catch (Throwable e) {
            Log.w(TAG, "设置浅色主题失败：" + e);
        }
    }

    /** 启动后自检：别名能不能真的解析到前缀，日志里能直接看到。 */
    /**
     * 清掉「引擎之外的游离插件」：历史上 npm 升级引擎时会在 usr/lib/node_modules/@deepseek-ai/
     * 顶层装上 0.2.2-rc.1 之类的包，DSH 启动时通过 node_modules 向上查找会优先命中它们
     * （表现为 `Plugin … is incompatible with dsh …`，账号/存储插件被 disable）。
     * 规则：与引擎版本不一致的 dsh-* 包统一挪进 files-trash/plugins-<ts>/。
     */
    static String scrubForeignPlugins(final File root, final String fallbackEngineVer) {
        final File usr = new File(root, "usr");
        final File dshPkg = new File(usr, "lib/node_modules/@deepseek-ai/dsh/package.json");
        final String fromPkg = readVersion(dshPkg);
        final String engineVer = fromPkg != null ? fromPkg : fallbackEngineVer;
        final File top = new File(usr, "lib/node_modules/@deepseek-ai");
        final File trash = new File(root, "files-trash/plugins-" + stamp());
        int moved = 0;
        // 顶层只允许 dsh 一个包：载荷的 usr/lib/node_modules/@deepseek-ai 里就只有它，
        // 其它任何 dsh-* 都是旧 npm 升级留下的残渣，会盖住 dsh/node_modules 里的正确版本。
        final File[] kids = top.listFiles();
        if (kids != null) for (File k : kids) {
            final String n = k.getName();
            if (!k.isDirectory() || !n.startsWith("dsh-") || "dsh".equals(n)) continue;
            try {
                trash.mkdirs();
                java.nio.file.Files.move(k.toPath(), new File(trash, n).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                moved++;
            } catch (Throwable ignored) {
            }
        }
        // profile 里的副本：只在能读到引擎版本时才比对，版本不一致的挪走。
        if (engineVer != null) {
            final java.util.List<File> homes = new java.util.ArrayList<>();
            final File profiles = new File(root, "home/.dsh/profiles");
            final File[] ps = profiles.listFiles();
            if (ps != null) for (File p : ps) homes.add(new File(p, "node_modules/@deepseek-ai"));
            for (File home : homes) {
                final File[] kk = home.listFiles();
                if (kk == null) continue;
                for (File k : kk) {
                    final String n = k.getName();
                    if (!n.startsWith("dsh-") || !k.isDirectory()) continue;
                    final String v = readVersion(new File(k, "package.json"));
                    if (v == null || v.equals(engineVer)) continue;
                    try {
                        trash.mkdirs();
                        java.nio.file.Files.move(k.toPath(), new File(trash, n).toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        moved++;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return "游离插件清理：引擎版本=" + (engineVer == null ? "读不到" : engineVer)
                + "（来源=" + (fromPkg != null ? "引擎包" : "APK 资产") + "），挪走 " + moved + " 个";
    }

    /** 粗粒度版本比较：0.2.1-rc.5 > 0.2.1-rc.2；非数字段落按字典序。 */
    static int cmpVersion(String a, String b) {
        if (a == null || b == null) return -1;
        String[] x = a.split("[.+-]"), y = b.split("[.+-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            try {
                int c = Integer.compare(Integer.parseInt(p), Integer.parseInt(q));
                if (c != 0) return c;
            } catch (NumberFormatException e) {
                int c = p.compareTo(q);
                if (c != 0) return c;
            }
        }
        return 0;
    }

    /** 读 APK assets 里的小文本（例如构建期写入的 engine-version.txt）。 */
    static String assetText(final android.content.Context ctx, final String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0 && bos.size() < 4096) bos.write(buf, 0, n);
            return bos.toString("UTF-8").trim();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String readVersion(final File pkgJson) {
        try {
            final String t = new String(java.nio.file.Files.readAllBytes(pkgJson.toPath()), "UTF-8");
            final java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(t);
            return m.find() ? m.group(1) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stamp() {
        return new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
    }

    static String aliasCheck(final File filesDir) {
        final File chain = new File(new File(filesDir, "usr"), "data/data/com.termux/files");
        final File usr = new File(chain, "usr");
        final File home = new File(chain, "home");
        try {
            final String ut = Files.isSymbolicLink(usr.toPath())
                    ? Files.readSymbolicLink(usr.toPath()).toString() : "(不是软链)";
            final String ht = Files.isSymbolicLink(home.toPath())
                    ? Files.readSymbolicLink(home.toPath()).toString() : "(不是软链)";
            final File realBin = new File(usr, "bin");
            final int bins = realBin.isDirectory() && realBin.list() != null ? realBin.list().length : -1;
            final File legacy = new File(filesDir, "data");
            return "alias usr=" + ut + " home=" + ht + " chain/bin条目=" + bins
                    + " 解析bin=" + realBin.getCanonicalPath()
                    + " 旧链残留=" + (legacy.exists() ? "有" : "无");
        } catch (Throwable e) {
            return "alias 自检失败：" + e;
        }
    }

    private static void absoluteLink(final File f, final String target) {
        try {
            if (Files.isSymbolicLink(f.toPath())) {
                if (target.equals(Files.readSymbolicLink(f.toPath()).toString())) return;
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } else if (f.isDirectory()) {
                // 早期版本 dpkg 确实把文件装进了这里（双层前缀），挪开留证再换成软链
                final File aside = new File(f.getParentFile(), f.getName() + ".misplaced");
                if (aside.exists()) Backup.deleteTree(aside);
                if (!f.renameTo(aside)) {
                    Log.w(TAG, "旧的双层前缀目录挪不动：" + f);
                    return;
                }
                Log.i(TAG, "已挪走双层前缀目录：" + aside);
            } else if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            Files.createSymbolicLink(f.toPath(), Paths.get(target));
            Log.i(TAG, "dpkg 前缀别名已建：" + f + " -> " + target);
        } catch (Throwable e) {
            Log.w(TAG, "dpkg 前缀别名失败 " + f + ": " + e);
        }
    }

    private static void link(final File dataDir, final String name, final String target) {
        final File f = new File(dataDir, name);
        try {
            if (Files.isSymbolicLink(f.toPath())) {
                String cur = Files.readSymbolicLink(f.toPath()).toString();
                if (target.equals(cur)) return;
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } else if (f.exists()) {
                Log.w(TAG, "前缀链接位置被占用（不是符号链接）：" + f);
                return;
            }
            Files.createSymbolicLink(f.toPath(), Paths.get(target));
            Log.i(TAG, "前缀链接已建：" + name + " -> " + target);
        } catch (Throwable e) {
            Log.w(TAG, "前缀链接失败 " + name + ": " + e);
        }
    }

    /** 幂等：已装过同版本快照就直接返回。 */
    static void ensure(final Context ctx, final Progress p) throws Exception {
        final File root = dir(ctx);
        ensurePrefixLinks(root);
        String rec = recoverEngineDir(ctx);
        if (rec != null) Log.i(TAG, rec);
        final boolean installed = isInstalled(root);
        dev.dsh.harness.Life.note(ctx, "ensure 诊断：" + diag(root) + " expect=" + Build.VERSION_SNAPSHOT);
        if (installed && healthy(root)) {
            p.step("快照已在（跳过下载/解包）");
            return;
        }
        // 标记在、但关键文件缺了（引擎目录被改名/删除、半截更新、外部脚本动过 usr/）
        // ⇒ 用载荷把 usr/ 增量补回来；此时绝不动 home，用户数据不该为一次修复再折腾一遍。
        final boolean repair = installed;
        final File archive = new File(ctx.getCacheDir(), "rootfs.tar.gz");
        // 优先用安装包内置的载荷（APK assets）：不用联网、不用 Termux 当下载源。
        final InputStream bundled = openBundled(ctx);
        if (bundled != null) {
            p.step("使用安装包内置载荷（无需下载）");
        } else if (!archive.isFile() || archive.length() == 0) {
            download(Build.ROOTFS_URL, archive, p);
        } else {
            p.step("用已下载的压缩包 " + archive.length() / 1048576 + " MB");
        }
        if (repair) {
            p.step("快照缺关键文件（" + missing(root) + "），开始修复…");
            keepDpkgDb = true; // 修复时同样保住 dpkg 数据库与 apt 索引/缓存
            reclaimOldEngines(root, p);
            try {
                if (bundled != null) {
                    try {
                        extract(bundled, root, p, "usr/", true);
                    } finally {
                        try { bundled.close(); } catch (IOException ignored) { }
                    }
                } else {
                    extract(archive, root, p, "usr/", true);
                }
            } finally {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
            if (!healthy(root)) throw new IOException("修复后仍缺 " + missing(root) + "，请卸载重装");
            Log.i(TAG, "自检修复完成：usr 仅补缺失项（未覆盖已有文件），home 未动");
            p.step("修复完成（usr 已补齐，home 未动）");
            return;
        }
        p.step("清理旧快照…");
        // 先保住用户数据：会话、工作区里的文件、模型设置、凭据都在 home 下，而 home 是载荷的一部分。
        // 不做这步的话，每次换载荷（VERSION_SNAPSHOT 变了）都会把用户聊过的东西端掉。
        final File home = new File(root, "home");
        // 旧版留下的 .keep（含迁移期残渣）：能救多少救多少，然后清掉。
        Backup.recoverLegacyKeep(root, home, p);
        // 不再把 KEEP 条目「搬」到临时目录：搬的过程中断电/被杀 = 数据只在临时区，而判据一旦错就是生产事故。
        // 改成整个 home 改名备份（同盘 rename，瞬间完成、一个字节都不动），解包后从备份里**拷贝**回来。
        final File homeBak = new File(root, "home.bak-" + System.currentTimeMillis());
        boolean backedUp = false;
        if (home.isDirectory()) {
            p.step("备份现有 home（改名，不复制）…");
            if (home.renameTo(homeBak)) {
                backedUp = true;
            } else {
                Log.w(TAG, "home 改名失败，退回解包前删除");
            }
        }
        // usr 是「载荷程序 + 用户自己装的工具链」共用的一棵树：换载荷时只做增量覆盖，绝不整棵删掉。
        // 反例（2026-11-09）：ensure() 无条件 Backup.deleteTree(usr)，于是 dsh-pkg 装的 78 个包、
        // 重定位过的 1,105 个二进制、Go/JDK/clang 全在一次 App 升级里消失。
        final File usr = new File(root, "usr");
        final boolean freshInstall = !usr.isDirectory();
        if (freshInstall) {
            Backup.deleteTree(usr);
            keepDpkgDb = false;
        } else {
            // 引擎升级/回滚留下的旧目录属于「已被载荷取代的版本」，顺手回收（每个约 600 MB 解包体积）
            reclaimOldEngines(root, p);
            p.step("更新载荷：保留已装工具链，只做增量覆盖");
            keepDpkgDb = true; // 只覆盖写、不重置 apt 数据库
        }
        // 引擎可能已被「引擎更新」升到比载荷自带更新的版本。先把整棵 dsh 目录挪开（同盘 rename，瞬间完成），
        // 解包后再比版本决定还原还是丢弃——否则每次 APK 升级都会把引擎降回载荷里的版本。
        final File engineDir = new File(root, "usr/lib/node_modules/@deepseek-ai/dsh");
        final File enginePreserve = new File(root, ".engine-preserve");
        String preservedEngine = null;
        if (!freshInstall && engineDir.isDirectory()) {
            Backup.deleteTree(enginePreserve);
            if (engineDir.renameTo(enginePreserve)) {
                preservedEngine = readEngineVersion(enginePreserve);
                if (preservedEngine != null) p.step("暂存现有引擎 " + preservedEngine);
            }
        }
        // home 则相反：插件树（.dsh/profiles）必须跟着载荷走，所以整棵重建，只把 KEEP 里的用户件搬回来。
        // 删之前立哨兵：下次启动若看到哨兵，说明上次是在解包阶段中断的，home 不完整，.keep 才是权威副本。
        try (OutputStream flag = new FileOutputStream(new File(root, INSTALLING))) {
            flag.write((Build.VERSION_SNAPSHOT + " " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        }
        if (!backedUp) Backup.deleteTree(home);
        // 0.2.0 时代把快照铺在 filesDir/rootfs，升级后那份 879 MB 就是垃圾了
        Backup.deleteTree(new File(root, "rootfs"));
        // filesDir 本身总是存在的，mkdirs() 会返回 false —— 只有"不存在且建不出来"才算失败
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("建 rootfs 目录失败：" + root);
        p.step("解包中（879 MB，约 1–3 分钟）…");
        try {
            if (bundled != null) {
                try {
                    extract(bundled, root, p);
                } finally {
                    try { bundled.close(); } catch (IOException ignored) { }
                }
            } else {
                extract(archive, root, p);
            }
        } catch (Exception extractionFailure) {
            // 解包失败时先把暂存的引擎放回去，至少让 App 还能启动
            forceRestoreEngine(engineDir, enginePreserve, p);
            throw extractionFailure;
        }
        // 解包成功后：暂存引擎比载荷新就还原，否则丢弃
        restorePreservedEngine(engineDir, enginePreserve, preservedEngine, p);
        if (backedUp) {
            p.step("恢复用户数据（从 home.bak 拷贝）…");
            int copied = Backup.copyKeep(homeBak, home);
            if (copied == 0) Log.w(TAG, "备份里没有可恢复的条目：" + homeBak);
            Backup.pruneHomeBackups(root, 2);
        }
        // 保险：凭据文件必须是属主独占（dsh-credentials-local 会挡 666/644）
        File cred = new File(root, "home/.dsh/.credentials.yaml");
        if (cred.isFile()) applyMode(cred, 0600);

        try (OutputStream out = new FileOutputStream(new File(root, MARKER))) {
            out.write(Build.VERSION_SNAPSHOT.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection ResultOfMethodCallIgnored
        new File(root, INSTALLING).delete();
        //noinspection ResultOfMethodCallIgnored
        archive.delete();
        p.step("快照安装完成");
    }

    /** 读取引擎目录 package.json 里的版本；读不到返回 null。 */
    private static String readEngineVersion(File dir) {
        try {
            File pkg = new File(dir, "package.json");
            if (!pkg.isFile()) return null;
            String json = new String(java.nio.file.Files.readAllBytes(pkg.toPath()), StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** semver 粗比：数字段优先，其次「无预发布 > 有预发布」。返回 >0 表示 a 更新。 */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("-", 2);
        String[] pb = b.split("-", 2);
        String[] na = pa[0].split("\\.");
        String[] nb = pb[0].split("\\.");
        for (int i = 0; i < 3; i++) {
            int x = i < na.length ? parseIntSafe(na[i]) : 0;
            int y = i < nb.length ? parseIntSafe(nb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        boolean apre = pa.length > 1, bpre = pb.length > 1;
        if (!apre && bpre) return 1;
        if (apre && !bpre) return -1;
        if (!apre) return 0;
        return pa[1].compareTo(pb[1]);
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return 0; }
    }

    /**
     * 解包成功后处理暂存引擎：**只有版本与载荷完全一致才还原**，否则一律用载荷版本。
     *
     * <p>为什么不再"新就保留"：插件树（`home/.dsh/profiles/**`）来自载荷，跟着载荷版本走；
     * 若引擎比载荷新，就会出现「引擎 0.2.2-rc.1 + 插件 0.2.2-rc.1 要求的运行时是 0.2.2-rc.1、
     * 实际跑的却是 0.2.1-rc.2」这类错配，DSH 会直接 disable 掉 account/storage-domain 等插件，
     * 界面就只剩一具空壳（2026-11-12 实测：正文 59 字、存储域与账号插件被禁用）。
     */
    /** 引擎目录是否「像样」：至少 bin.js 与几个关键依赖在。
     *  2026-11-13 事故：一次中断的修复留下半截引擎，版本号却是对的 ⇒ 被原样还原 ⇒ 启动即崩。 */
    private static boolean engineIntact(File engineDir) {
        if (engineDir == null || !engineDir.isDirectory()) return false;
        if (!new File(engineDir, "lib/bin.js").isFile()) return false;
        if (!new File(engineDir, "package.json").isFile()) return false;
        File nm = new File(engineDir, "node_modules/@deepseek-ai");
        File boot = new File(nm, "dsh-app-boot");
        if (!new File(boot, "package.json").isFile()) return false;
        if (!new File(boot, "index.js").isFile() && !new File(boot, "lib/index.js").isFile()) return false;
        File tools = new File(nm, "dsh-tools");
        return new File(tools, "lib/index.js").isFile() || new File(tools, "index.js").isFile();
    }

    private static void restorePreservedEngine(File engineDir, File preserve, String preserved, Progress p) {
        if (preserved == null || !preserve.isDirectory()) return;
        String bundled = readEngineVersion(engineDir);
        boolean keep = bundled != null && cmpVersion(preserved, bundled) >= 0;
        if (!keep) {
            Backup.deleteTree(preserve);
            if (bundled != null) p.step("引擎 " + preserved + " 与载荷 " + bundled + " 不一致，已用载荷版本（插件与运行时必须同版）");
            return;
        }
        if (!engineIntact(preserve)) {
            Backup.deleteTree(preserve);
            p.step("暂存引擎 " + preserved + " 不完整，已丢弃，改用载荷版本");
            return;
        }
        if (!engineIntact(engineDir)) {
            p.step("载荷引擎不完整，沿用暂存引擎 " + preserved);
            Backup.deleteTree(engineDir);
            File parent0 = engineDir.getParentFile();
            if (parent0 != null) parent0.mkdirs();
            if (!preserve.renameTo(engineDir)) p.step("暂存引擎还原失败");
            return;
        }
        Backup.deleteTree(engineDir);
        File parent = engineDir.getParentFile();
        if (parent != null) parent.mkdirs();
        if (preserve.renameTo(engineDir)) {
            p.step("保留已更新的引擎 " + preserved + "（载荷自带 " + bundled + "）");
        } else {
            p.step("还原引擎失败，沿用载荷版本");
        }
    }

    /** 解包失败时的兜底：无条件把暂存引擎放回原位。 */
    private static void forceRestoreEngine(File engineDir, File preserve, Progress p) {
        if (!preserve.isDirectory()) return;
        Backup.deleteTree(engineDir);
        File parent = engineDir.getParentFile();
        if (parent != null) parent.mkdirs();
        if (preserve.renameTo(engineDir)) p.step("解包失败，已还原原引擎");
    }

    /** 读 dpkg 的文件清单（info/*.list），换算出「这台设备上 apt 装过的相对路径」。
     *  载荷覆盖时跳过这些条目，就不会把用户装的 nano 9.3 覆盖回载荷自带的 8.3。 */
    private static java.util.Set<String> aptOwned(File root) {
        java.util.Set<String> out = new java.util.HashSet<>();
        File info = new File(root, "usr/var/lib/dpkg/info");
        File[] lists = info.listFiles((d, n) -> n.endsWith(".list"));
        if (lists == null) return out;
        for (File f : lists) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("./")) line = line.substring(2);
                    if (line.startsWith("/data/data/com.termux/files/usr/"))
                        out.add("usr/" + line.substring("/data/data/com.termux/files/usr/".length()));
                    else if (line.startsWith("/data/data/com.termux/files/home/"))
                        out.add("home/" + line.substring("/data/data/com.termux/files/home/".length()));
                    else if (line.startsWith("/data/user/0/dev.dsh.harness/files/usr/"))
                        out.add("usr/" + line.substring("/data/user/0/dev.dsh.harness/files/usr/".length()));
                    else if (line.startsWith("/data/user/0/dev.dsh.harness/files/home/"))
                        out.add("home/" + line.substring("/data/user/0/dev.dsh.harness/files/home/".length()));
                }
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    /** 判断一份 rootfs 是否「能用」：不只看标记文件，还要看关键文件在不在。 */
    private static final String[] REQUIRED = {
            "usr/bin/node",
            "usr/bin/bash",
            "usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
    };

    /**
     * profile 缺失时，从安装包内置载荷里把 home/.dsh/profiles/ 重新铺一遍。
     * 设备上出现过 profile 整个不见（引擎于是自己生成了一份最小 profile，我们的插件自然不在里面），
     * 这里的做法是「缺就补」，不动 home 下其它数据。
     */
    /**
     * 启动自检：profile 必须存在、必须登记 dsh-app-release、且必须带侧栏浏览器的启用开关。
     * 任一不满足就从安装包内置载荷里把 home/.dsh/profiles/ 整棵恢复（不动 home 下其它数据）。
     */
    /**
     * 引擎目录可能被「换装过程中 App 被杀」留在半截状态：dsh 已被改名成 dsh.old-*、staging 还没搬过来。
     * 这时如果走载荷修复，会把引擎静默退回载荷自带的版本（用户看到的就是"版本又变回去了"）。
     * 启动时先看一眼：dsh 目录不完整、但有可用的 dsh.old-* / dsh.failed-*，就把最新的那个认回来。
     */
    static String recoverEngineDir(Context ctx) {
        final File root = dir(ctx);
        final File modules = new File(root, "usr/lib/node_modules/@deepseek-ai");
        final File dsh = new File(modules, "dsh");
        if (new File(dsh, "package.json").isFile() && new File(dsh, "lib/bin.js").isFile()) {
            return null; // 完好，不用管
        }
        File preserve = new File(root, ".engine-preserve");
        if (engineIntact(preserve)) {
            File dead0 = new File(modules, "dsh.broken-" + System.currentTimeMillis());
            try {
                if (dsh.exists() && !dsh.renameTo(dead0)) return null;
                if (preserve.renameTo(dsh)) {
                    Backup.deleteTree(dead0);
                    return "引擎目录不在，已认回暂存引擎 .engine-preserve（" + readEngineVersion(dsh) + "）";
                }
                if (dead0.exists()) dead0.renameTo(dsh);
            } catch (Throwable ignored) { }
        }
        File[] cands = modules.listFiles((dir, name) -> name.startsWith("dsh.old-") || name.startsWith("dsh.failed-"));
        if (cands == null || cands.length == 0) return null;
        java.util.Arrays.sort(cands, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File c : cands) {
            if (!new File(c, "package.json").isFile() || !new File(c, "lib/bin.js").isFile()) continue;
            File dead = new File(modules, "dsh.broken-" + System.currentTimeMillis());
            try {
                if (dsh.exists() && !dsh.renameTo(dead)) continue;
                if (c.renameTo(dsh)) {
                    Backup.deleteTree(dead);
                    String v = "unknown";
                    try {
                        String txt = new String(java.nio.file.Files.readAllBytes(
                                new File(dsh, "package.json").toPath()), "UTF-8");
                        v = new org.json.JSONObject(txt).optString("version", "unknown");
                    } catch (Throwable ignored) { }
                    return "引擎目录不完整，已认回 " + c.getName() + "（" + v + "）";
                }
                if (dead.exists()) dead.renameTo(dsh);
            } catch (Throwable e) {
                return "引擎目录恢复失败：" + e;
            }
        }
        return null;
    }

    /** 更新/换装用的辅助脚本：缺失就从内置载荷只解 home/试玩/ 这一层补上。 */
    static String ensureHelperScripts(Context ctx) {
        final File root = dir(ctx);
        final File dir = new File(root, "home/试玩");
        final String[] names = {"dsh-engine-update.sh", "dsh-engine-rollback.sh", "engine-fetch.mjs", "dsh-relocate"};
        StringBuilder have = new StringBuilder();
        StringBuilder miss = new StringBuilder();
        for (String n : names) {
            if (new File(dir, n).isFile()) have.append(n).append(' ');
            else miss.append(n).append(' ');
        }
        boolean restartMissing = !new File(root, "home/dsh-restart.sh").isFile();
        if (miss.length() == 0 && !restartMissing) {
            return "辅助脚本：齐全（" + have + "）";
        }
        InputStream in = openBundled(ctx);
        if (in == null) return "辅助脚本：缺 " + miss + "，且没有内置载荷可解";
        try {
            if (miss.length() > 0) {
                extract(in, root, new Progress() {
                    @Override public void step(String msg) { }
                    @Override public void bytes(long done, long total) { }
                }, "home/试玩/", true);
            }
            if (restartMissing) {
                InputStream in2 = openBundled(ctx);
                if (in2 != null) {
                    try {
                        extract(in2, root, new Progress() {
                            @Override public void step(String msg) { }
                            @Override public void bytes(long done, long total) { }
                        }, "home/", true);
                    } finally {
                        try { in2.close(); } catch (IOException ignored) { }
                    }
                }
            }
        } catch (Throwable e) {
            return "辅助脚本：补解失败 " + e;
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
        StringBuilder ok = new StringBuilder();
        for (String n : names) {
            File f = new File(dir, n);
            if (f.isFile()) {
                ok.append(n).append(f.canExecute() ? "" : "(不可执行)").append(' ');
                if (!f.canExecute() && n.endsWith(".sh")) f.setExecutable(true, false);
            }
        }
        if (restartMissing && new File(root, "home/dsh-restart.sh").isFile()) ok.append("dsh-restart.sh ");
        return "辅助脚本：已从内置载荷补上（" + ok + "），原来缺 " + miss;
    }

    static String ensureProfile(Context ctx) {
        File root = dir(ctx);
        File web = new File(root, "home/.dsh/profiles/web");
        File pkg = new File(web, "package.json");
        File patch = new File(web, "cordis.patch.yml");
        StringBuilder why = new StringBuilder();
        if (!pkg.isFile()) why.append("package.json 缺失;");
        if (!patch.isFile()) why.append("cordis.patch.yml 缺失;");
        if (pkg.isFile() && !readText(pkg).contains("dsh-app-release")) why.append("未登记 dsh-app-release;");
        if (patch.isFile() && !readText(patch).contains("ui-sidebar-browser")) why.append("缺少侧栏浏览器开关;");
        if (why.length() == 0) {
            return "profile 就绪（node_modules 条目 " + countEntries(new File(web, "node_modules")) + "）";
        }
        InputStream in = openBundled(ctx);
        if (in == null) return "profile 异常（" + why + "），且没有内置载荷可用";
        long t0 = System.currentTimeMillis();
        try {
            extract(in, root, new Progress() {
                @Override public void step(String msg) { }
                @Override public void bytes(long done, long total) { }
            }, "home/.dsh/profiles/", false);
        } catch (Throwable e) {
            return "恢复 profile 失败（" + why + "）：" + e;
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
        return "profile 已恢复（" + why + "，" + (System.currentTimeMillis() - t0) + "ms，node_modules 条目 "
                + countEntries(new File(web, "node_modules")) + "）";
    }

    private static String readText(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    private static int countEntries(File dir) {
        String[] names = dir.list();
        return names == null ? 0 : names.length;
    }



    /** 把 APK 内置的 dpkg 包装器同步到 <前缀>/bin/dpkg（内容不同才写，chmod 755）。 */
    static String ensureDpkgWrapper(Context ctx) {
        File root = dir(ctx);
        File dst = new File(root, "usr/bin/dpkg");
        try {
            java.io.InputStream in = ctx.getAssets().open("dpkg-wrapper.sh");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String script = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("/data/data/com.termux/files/usr", new File(root, "usr").getAbsolutePath());
            byte[] want = script.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] have = dst.isFile() ? java.nio.file.Files.readAllBytes(dst.toPath()) : null;
            if (have != null && java.util.Arrays.equals(have, want)) return "dpkg 包装器：已是最新";
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            java.nio.file.Files.write(dst.toPath(), want);
            //noinspection ResultOfMethodCallIgnored
            dst.setExecutable(true, false);
            return "dpkg 包装器：已更新到自愈版（" + want.length + " B）";
        } catch (Throwable e) {
            return "dpkg 包装器自愈失败：" + e;
        }
    }

    /**
     * 终端绑定：载荷里引擎自带的 node-pty 是 Termux 编的 pty.node（bionic，能 dlopen）；
     * npm 包里的 prebuilds/linux-arm64 是 glibc 的（dlopen 报缺 libutil.so.1）。
     * 这里把载荷里那份随 APK 下发到 files/share/node-pty.node，引擎换包/启动时由
     * {@link EnginePatch#ensureTerminal} 拷进 node_modules/node-pty/build/Release/。
     */
    static String ensurePtyBinding(Context ctx) {
        File dst = new File(dir(ctx), "share/node-pty.node");
        try {
            java.io.InputStream in = ctx.getAssets().open("node-pty.node");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] want = bos.toByteArray();
            byte[] have = dst.isFile() ? java.nio.file.Files.readAllBytes(dst.toPath()) : null;
            if (have != null && java.util.Arrays.equals(have, want)) {
                return "pty 资源：已是最新（" + want.length + " B）";
            }
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            java.nio.file.Files.write(dst.toPath(), want);
            //noinspection ResultOfMethodCallIgnored
            dst.setExecutable(true, false);
            return "pty 资源：已写入（" + want.length + " B）";
        } catch (Throwable e) {
            return "pty 资源：失败 " + e;
        }
    }

    /**
     * 补齐 dpkg 的 files list：载荷里 75 个基础包的 info/*.list 历史上是空的，
     * 于是 apt 每次装包都要刷几十条 "files list file for package ... is missing"，
     * 且 dpkg -L 什么都查不到。清单随 APK 以 assets/dpkg-lists/<pkg>.list 下发，
     * 启动时只补「缺失或为空」的，已有内容绝不覆盖（用户后来装/升的包有自己的清单）。
     */
    static String ensureDpkgLists(Context ctx) {
        File info = new File(dir(ctx), "usr/var/lib/dpkg/info");
        try {
            String[] names = ctx.getAssets().list("dpkg-lists");
            if (names == null || names.length == 0) return "dpkg 清单：安装包里没有清单资源";
            if (!info.isDirectory() && !info.mkdirs()) throw new java.io.IOException("建不了 " + info);
            int wrote = 0, kept = 0;
            byte[] buf = new byte[8192];
            for (String name : names) {
                if (!name.endsWith(".list")) continue;
                File dst = new File(info, name);
                if (dst.isFile() && dst.length() > 0) { kept++; continue; }
                java.io.InputStream in = ctx.getAssets().open("dpkg-lists/" + name);
                try {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    java.nio.file.Files.write(dst.toPath(), out.toByteArray());
                } finally {
                    in.close();
                }
                wrote++;
            }
            return "dpkg 清单：补 " + wrote + " 份，已有 " + kept + " 份（共 " + names.length + " 份资源）";
        } catch (Throwable e) {
            return "dpkg 清单补齐失败：" + e;
        }
    }

    /**
     * 把 dpkg 的 .list 从「包内 Termux 前缀」重写成 App 真实前缀。
     *
     * <p>Termux 的 .deb 里路径写死 /data/data/com.termux/files/usr/...，装载是靠
     * files/usr/data/data/com.termux/files/usr 这条软链穿回前缀的；但 .list 记的是
     * 包内路径，`dpkg -L` 打出来就是一串在 App 沙箱里 ls 不到的路径。这里只改清单
     * 文本，不动物理文件；dpkg 升级/卸载仍按新路径工作。
     */
    static String normalizeDpkgLists(File root) {
        File info = new File(root, "usr/var/lib/dpkg/info");
        File[] lists = info.listFiles((d, n) -> n.endsWith(".list"));
        if (lists == null) return "dpkg 清单路径：没有 info/.list";
        String oldP = "/data/data/com.termux/files/usr";
        String newP = new File(root, "usr").getAbsolutePath();
        int changed = 0;
        try {
            for (File f : lists) {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                if (!s.contains(oldP)) continue;
                java.nio.file.Files.write(f.toPath(), s.replace(oldP, newP).getBytes("UTF-8"));
                changed++;
            }
        } catch (Throwable t) {
            return "dpkg 清单路径重写失败：" + t;
        }
        return "dpkg 清单路径：重写 " + changed + " 份（共 " + lists.length + " 份）";
    }

    /** App 内从 npm 构建引擎用的补丁集/锁文件：安装包里有没有这个版本。 */
    static boolean hasEngineBuildAssets(Context ctx, String ver) {
        if (ver == null || ver.isEmpty()) return false;
        try {
            String[] names = ctx.getAssets().list("engine-build");
            if (names == null) return false;
            for (String n : names) if (n.equals(ver)) return true;
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 磁盘上的构建资源目录（install 包里解出来的，或运行时迁移出来的）。 */
    static File engineBuildDir(File root, String ver) {
        return new File(root, "engine-build/" + ver);
    }

    /** 磁盘上已经有这个版本的 package-lock + patches（运行时迁移写出来的也算）。 */
    static boolean hasPortedEngineBuild(File root, String ver) {
        if (ver == null || ver.isEmpty()) return false;
        File d = engineBuildDir(root, ver);
        return new File(d, "package-lock.json").isFile() && new File(d, "patches").isDirectory();
    }

    /** 面板按钮判断：安装包自带补丁集，或者能从某个旧版本自动迁移。 */
    static boolean canBuildEngine(Context ctx, File root, String ver) {
        if (ver == null || ver.isEmpty()) return false;
        return hasEngineBuildAssets(ctx, ver) || hasPortedEngineBuild(root, ver)
                || latestEngineBuildBase(ctx, root, ver) != null;
    }

    /** 找一个可用的旧补丁集当迁移基底：优先安装包自带，其次磁盘上迁移过的。 */
    static String latestEngineBuildBase(Context ctx, File root, String ver) {
        java.util.TreeSet<String> cands = new java.util.TreeSet<>();
        try {
            String[] names = ctx.getAssets().list("engine-build");
            if (names != null) {
                for (String n : names) {
                    if (!n.equals(ver) && new File(engineBuildDir(root, n), "package-lock.json").isFile()
                            && new File(engineBuildDir(root, n), "patches").isDirectory()) {
                        cands.add(n);   // 已解出来的
                    } else if (!n.equals(ver) && hasEngineBuildAssets(ctx, n)) {
                        cands.add(n);   // 安装包里还有，稍后解
                    }
                }
            }
        } catch (Throwable ignored) { }
        if (cands.isEmpty()) return null;
        // 选「低于目标版本里最高的」当基底，避免把新补丁回迁到旧版；没有更旧的再用最高的。
        String bestLower = null, best = null;
        for (String c : cands) {
            if (best == null || Utils.cmpVersion(c, best) > 0) best = c;
            if (Utils.cmpVersion(c, ver) < 0 && (bestLower == null || Utils.cmpVersion(c, bestLower) > 0)) {
                bestLower = c;
            }
        }
        return bestLower != null ? bestLower : best;
    }

    /** 从安装包自愈补丁迁移脚本（和构建脚本一样，改脚本不用重打载荷）。 */
    static String ensureEnginePortScript(Context ctx) {
        File dst = new File(dir(ctx), "home/试玩/dsh-engine-port.sh");
        try {
            byte[] want;
            try (InputStream in = ctx.getAssets().open("engine-port.sh")) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                want = bos.toByteArray();
            }
            if (dst.isFile() && dst.length() == want.length
                    && java.util.Arrays.equals(Files.readAllBytes(dst.toPath()), want)) {
                return "补丁迁移脚本：已是最新";
            }
            File d = dst.getParentFile();
            if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("建不了 " + d);
            Files.write(dst.toPath(), want);
            try { dst.setExecutable(true, false); } catch (Throwable ignored) { }
            return "补丁迁移脚本：已更新（" + want.length + " B）";
        } catch (Throwable e) {
            return "补丁迁移脚本：写入失败 " + e;
        }
    }

    /** 把 assets/engine-build/<ver>/（package.json + 锁文件 + patches）解到 files/engine-build/<ver>。 */
    static String ensureEngineBuildAssets(Context ctx, String ver) {
        File dst = new File(dir(ctx), "engine-build/" + ver);
        try {
            if (!hasEngineBuildAssets(ctx, ver)) return "引擎构建资源：安装包里没有 " + ver + " 的补丁集";
            if (new File(dst, "package-lock.json").isFile() && new File(dst, "patches").isDirectory()) {
                return "引擎构建资源：已是现成的（" + ver + "）";
            }
            if (dst.exists()) Backup.deleteTree(dst);
            if (!dst.mkdirs()) throw new IOException("建不了 " + dst);
            int n = copyAssetTree(ctx, "engine-build/" + ver, dst);
            return "引擎构建资源：解出 " + n + " 个文件（" + ver + "）";
        } catch (Throwable e) {
            return "引擎构建资源：失败 " + e;
        }
    }

    /** 递归把 assets 下的目录树写到磁盘，返回文件数。 */
    private static int copyAssetTree(Context ctx, String assetPath, File dst) throws IOException {
        String[] kids = ctx.getAssets().list(assetPath);
        if (kids == null || kids.length == 0) {
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("建不了 " + parent);
            try (InputStream in = ctx.getAssets().open(assetPath)) {
                Files.copy(in, dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return 1;
        }
        if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("建不了 " + dst);
        int n = 0;
        for (String k : kids) n += copyAssetTree(ctx, assetPath + "/" + k, new File(dst, k));
        return n;
    }

    /** 从安装包自愈构建脚本（免去打载荷）。 */
    /** 把「换装脚本」自愈到 files/home/试玩/：以前它只存在于载荷里，改了代码也发不下去。 */
    static String ensureEngineUpdateScript(Context ctx) {
        File dst = new File(dir(ctx), "home/试玩/dsh-engine-update.sh");
        try {
            byte[] want;
            try (InputStream in = ctx.getAssets().open("engine-update.sh")) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                want = bos.toByteArray();
            }
            if (dst.isFile() && java.util.Arrays.equals(Files.readAllBytes(dst.toPath()), want)) {
                return "引擎换装脚本：已是最新";
            }
            File d = dst.getParentFile();
            if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("建不了 " + d);
            Files.write(dst.toPath(), want);
            //noinspection ResultOfMethodCallIgnored
            dst.setExecutable(true, false);
            return "引擎换装脚本：已更新（" + want.length + " B）";
        } catch (Throwable e) {
            return "引擎换装脚本自愈失败：" + e;
        }
    }

    static String ensureEngineBuildScript(Context ctx) {
        File dst = new File(dir(ctx), "home/试玩/dsh-engine-build.sh");
        try {
            byte[] want;
            try (InputStream in = ctx.getAssets().open("engine-build.sh")) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                want = bos.toByteArray();
            }
            if (dst.isFile() && dst.length() == want.length
                    && java.util.Arrays.equals(Files.readAllBytes(dst.toPath()), want)) {
                return "引擎构建脚本：已是最新";
            }
            File d = dst.getParentFile();
            if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("建不了 " + d);
            Files.write(dst.toPath(), want);
            try { dst.setExecutable(true, false); } catch (Throwable ignored) { }
            return "引擎构建脚本：已更新（" + want.length + " B）";
        } catch (Throwable e) {
            return "引擎构建脚本：写入失败 " + e;
        }
    }

    /** 回收旧引擎目录（dsh.old-* / dsh.failed-*），保留最新一份做回滚，其余真删。返回人话。 */
    static String reclaimOldEnginesNow(File root) {
        File[] kids = new File(root, "usr/lib/node_modules/@deepseek-ai").listFiles();
        if (kids == null) return "回收旧引擎：目录不存在";
        java.util.List<File> olds = new java.util.ArrayList<>();
        for (File k : kids) {
            String n = k.getName();
            if (!n.startsWith("dsh.old-") && !n.startsWith("dsh.failed-")) continue;
            if (k.isDirectory()) olds.add(k);
        }
        olds.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long freed = 0;
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < olds.size(); i++) {
            File d = olds.get(i);
            if (i == 0) { kept.append(d.getName()); continue; }
            freed += sizeOf(d);
            Backup.deleteTree(d);
        }
        return "回收旧引擎：保留 " + (kept.length() == 0 ? "无" : kept) + "，删除 " + Math.max(0, olds.size() - 1)
                + " 份，合计 " + (freed / 1024) + " KB"
                + "（若为 0 说明它们是空壳/软链，删掉不影响体积）";
    }

    /**
     * dsh 命令行入口（usr/bin/dsh → lib/bin.js）。
     *
     * <p>npm 包里的 bin.js 第一行是 {@code #!/usr/bin/env node}，而 Android 没有 /usr/bin/env；
     * App 内从 npm 构建引擎后这行会被原样带进来，于是终端里 `dsh` 直接报
     * "bad interpreter"。这里改成载荷自己的 node 绝对路径（幂等，启动时与引擎拉起前各跑一次）。
     */
    static String ensureDshCli(File root) {
        File binJs = new File(root, "usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js");
        File node = new File(root, "usr/bin/node");
        if (!binJs.isFile()) return "dsh 入口：没有 bin.js（跳过）";
        String want = "#!" + node.getAbsolutePath();
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(binJs.toPath());
            String s = new String(raw, "UTF-8");
            int nl = s.indexOf('\n');
            String first = (nl < 0 ? s : s.substring(0, nl)).trim();
            if (!first.startsWith("#!")) return "dsh 入口：bin.js 没有 shebang（跳过）";
            if (want.equals(first)) return "dsh 入口：已是最新";
            String rest = nl < 0 ? "" : s.substring(nl);
            java.nio.file.Files.write(binJs.toPath(), (want + rest).getBytes("UTF-8"));
            binJs.setExecutable(true, false);
            return "dsh 入口：shebang 已修正（" + node.getAbsolutePath() + "）";
        } catch (Throwable t) {
            return "dsh 入口修正失败：" + t;
        }
    }

    /** dpkg 数据库体检：status/available 缺失或为空时，用 APK 里预置的种子恢复。 */
    static String ensureDpkgDb(android.content.Context ctx, File root) {
        File dpkg = new File(root, "usr/var/lib/dpkg");
        String s = seedIfMissing(ctx, "dpkg-status.txt", new File(dpkg, "status"), "status");
        String a = seedIfMissing(ctx, "dpkg-available.txt", new File(dpkg, "available"), "available");
        return "dpkg 数据库：" + s + "，" + a
                + "，apt 索引 " + countReal(new File(root, "usr/var/lib/apt/lists")) + " 份";
    }

    private static String seedIfMissing(android.content.Context ctx, String asset, File target, String label) {
        if (target.isFile() && target.length() > 0) return label + " 正常(" + target.length() + "B)";
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return label + " 缺失，目录建不出来";
            java.io.InputStream in = ctx.getAssets().open(asset);
            java.io.OutputStream out = new java.io.FileOutputStream(target);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            return label + " 已重建(" + target.length() + "B，来自种子)";
        } catch (Throwable t) {
            return label + " 缺失，恢复失败：" + t;
        }
    }

    private static int countReal(File dir) {
        File[] l = dir.listFiles();
        if (l == null) return 0;
        int n = 0;
        for (File f : l) {
            String name = f.getName();
            if ("partial".equals(name) || name.startsWith("lock")) continue;
            n++;
        }
        return n;
    }

    /**
     * 清掉早期误操作留下的「名字里带换行/控制字符」的条目。
     * 它们本身只有几十 KB，但会让 rg/glob 这类按目录扫描的工具直接报错退出。
     */
    static String cleanBadNames(File root) {
        int n = 0;
        long freed = 0;
        StringBuilder sample = new StringBuilder();
        for (File dir : new File[]{root, new File(root, "home"), new File(root, "usr")}) {
            File[] l = dir.listFiles();
            if (l == null) continue;
            for (File f : l) {
                if (!hasControl(f.getName())) continue;
                String shown = hexName(f.getName());
                long sz = sizeOf(f);
                // 有些条目是 000 权限（审查里报的 Permission denied），先松开再删
                f.setReadable(true, false);
                f.setWritable(true, false);
                f.setExecutable(true, false);
                Backup.deleteTree(f);
                if (!f.exists()) {
                    n++;
                    freed += sz;
                    if (sample.length() < 120) sample.append(sample.length() == 0 ? "" : "，").append(shown);
                    Log.i(TAG, "清掉异常文件名条目：" + f.getParent() + " / " + shown);
                }
            }
        }
        return "异常文件名：清理 " + n + " 项" + (n > 0 ? "（如 " + sample + "）" : "")
                + "，释放 " + (freed / 1024) + " KB";
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    private static String hexName(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && i < 12; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7f) sb.append(c);
            else sb.append(String.format("%%%02x", (int) c));
        }
        return sb.toString();
    }

    /**
     * updates/ 里的整包 tar 在解包进 usr/ 之后就是纯冗余（回滚用 dsh.old-*）。
     * 实测两份 91 MB + 100 MB，白占 195 MB。
     */
    static String cleanUpdateTarballs(File root) {
        File dir = new File(root, "updates");
        File[] l = dir.listFiles((d, n) -> n.startsWith("engine-")
                && (n.endsWith(".tar.gz") || n.endsWith(".sha256")));
        if (l == null) return "引擎包清理：没有 updates/ 目录";
        int c = 0;
        long freed = 0;
        for (File f : l) {
            long sz = sizeOf(f);
            if (f.delete() || !f.exists()) {
                c++;
                freed += sz;
            }
        }
        return "引擎包清理：删除 " + c + " 个，释放 " + (freed / 1048576) + " MB";
    }

    /** apt 索引为空时补一次 `apt-get update`（失败把最后几行写进日志，不阻塞启动）。 */
    static String ensureAptIndex(File root) {
        File lists = new File(root, "usr/var/lib/apt/lists");
        int have = countReal(lists);
        if (have >= 5) return "apt 索引：已有 " + have + " 份";
        File aptGet = new File(root, "usr/bin/apt-get");
        if (!aptGet.isFile()) return "apt 索引：没有 apt-get（跳过）";
        Process p = null;
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(aptGet.getAbsolutePath());
            cmd.add("update");
            ProcessBuilder pb = Env.builder(root, cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder tail = new StringBuilder();
            final long deadline = System.currentTimeMillis() + 150000;
            String line;
            while (System.currentTimeMillis() < deadline && (line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                tail.append(line).append('\n');
                if (tail.length() > 2000) tail.delete(0, tail.length() - 2000);
            }
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy();
                return "apt 索引更新超时（已放弃）";
            }
            int code = p.exitValue();
            if (code == 0) return "apt 索引：已更新（" + countReal(lists) + " 份）";
            String t = tail.toString().trim();
            return "apt 索引更新失败(code=" + code + ")：" + (t.length() > 300 ? t.substring(t.length() - 300) : t);
        } catch (Throwable t) {
            if (p != null) p.destroy();
            return "apt 索引更新失败：" + t;
        }
    }

    private static long sizeOf(File f) {
        if (f == null) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] k = f.listFiles();
        if (k != null) for (File c : k) n += sizeOf(c);
        return n;
    }

    /** 列出 usr/bin 里「既不在载荷清单、也没被 dpkg 登记」的条目（只报告，不删）。 */
    static String orphanBinaries(Context ctx, File root) {
        java.util.Set<String> known = new java.util.HashSet<>();
        File info = new File(root, "usr/var/lib/dpkg/info");
        File[] lists = info.listFiles((d, n) -> n.endsWith(".list"));
        if (lists != null) for (File l : lists) {
            try {
                for (String line : java.nio.file.Files.readAllLines(l.toPath())) {
                    int i = line.indexOf("/usr/");
                    if (i >= 0) known.add(line.substring(i + 5));
                }
            } catch (Throwable ignored) { }
        }
        int payloadCount = 0;
        try {
            java.io.InputStream in = ctx.getAssets().open("payload-bin.txt");
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                payloadCount++;
                known.add("bin/" + line);
            }
            br.close();
        } catch (Throwable ignored) { }
        File bin = new File(root, "usr/bin");
        File[] items = bin.listFiles();
        if (items == null) return "孤儿二进制：usr/bin 不存在";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (File f : items) {
            if (known.contains("bin/" + f.getName())) continue;
            n++;
            if (sb.length() < 260) sb.append(f.getName()).append(' ');
        }
        return "孤儿二进制：usr/bin " + items.length + " 项，载荷 " + payloadCount
                + " 项，真孤儿 " + n + " 项（前若干：" + sb + "）";
    }

    /** 一行说清磁盘实况：标记、健康、缺项、usr 规模、@deepseek-ai 顶层、暂存引擎、哨兵、home。 */
    static String diag(File root) {
        StringBuilder sb = new StringBuilder();
        try {
            String mk = "";
            File mf = new File(root, MARKER);
            if (mf.isFile()) mk = new String(java.nio.file.Files.readAllBytes(mf.toPath()), "UTF-8").trim();
            sb.append("marker=").append(mk.isEmpty() ? "无" : mk)
              .append(" installed=").append(isInstalled(root))
              .append(" healthy=").append(healthy(root))
              .append(" missing=").append(missing(root).isEmpty() ? "无" : missing(root))
              .append(" usr/bin=").append(countEntries(new File(root, "usr/bin")))
              .append(" @ds=").append(joinNames(new File(root, "usr/lib/node_modules/@deepseek-ai")))
              .append(" 版本=").append(readEngineVersion(new File(root, "usr/lib/node_modules/@deepseek-ai/dsh")))
              .append(" preserve=").append(engineIntact(new File(root, ".engine-preserve")) ? "有" : "无")
              .append(" installing=").append(new File(root, INSTALLING).isFile() ? "有" : "无")
              .append(" home=").append(new File(root, "home").isDirectory() ? "有" : "无")
              .append(" dpkg=").append(dpkgBrief(root))
              .append(" updates=").append(sizeOf(new File(root, "updates")) / 1048576).append("MB")
              .append(" bak=").append(countPrefixed(root, "home.bak-"))
              .append(" badnames=").append(countBadNames(root))
              .append(" TERM=xterm-256color");
        } catch (Throwable t) {
            sb.append("diag 失败：").append(t);
        }
        return sb.toString();
    }

    private static String dpkgBrief(File root) {
        File status = new File(root, "usr/var/lib/dpkg/status");
        File avail = new File(root, "usr/var/lib/dpkg/available");
        return (status.isFile() ? status.length() + "B" : "无")
                + "/" + (avail.isFile() ? avail.length() + "B" : "无");
    }

    private static int countPrefixed(File dir, String prefix) {
        String[] names = dir.list((d, n) -> n.startsWith(prefix));
        return names == null ? 0 : names.length;
    }

    private static int countBadNames(File root) {
        int n = 0;
        for (File dir : new File[]{root, new File(root, "home"), new File(root, "usr")}) {
            File[] l = dir.listFiles();
            if (l == null) continue;
            for (File f : l) if (hasControl(f.getName())) n++;
        }
        return n;
    }

    private static String joinNames(File d) {
        String[] names = d.list();
        if (names == null) return "无";
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) sb.append(',');
            sb.append(n);
            if (sb.length() > 160) { sb.append("…"); break; }
        }
        return sb.length() == 0 ? "空" : sb.toString();
    }

    static boolean healthy(File root) {
        for (String rel : REQUIRED) {
            if (!new File(root, rel).exists()) return false;
        }
        return engineIntact(new File(root, "usr/lib/node_modules/@deepseek-ai/dsh"));
    }

    private static String missing(File root) {
        StringBuilder sb = new StringBuilder();
        for (String rel : REQUIRED) {
            if (new File(root, rel).exists()) continue;
            if (sb.length() > 0) sb.append('、');
            sb.append(rel);
        }
        return sb.toString();
    }

    /** 回收引擎升级/回滚留下的 dsh.old-* / dsh.failed-*（每个解包后约 600 MB）。 */
    private static void reclaimOldEngines(File root, Progress p) {
        File base = new File(root, "usr/lib/node_modules/@deepseek-ai");
        File[] kids = base.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String n = k.getName();
            if (!n.startsWith("dsh.old-") && !n.startsWith("dsh.failed-")) continue;
            p.step("回收旧引擎目录 " + n);
            Backup.deleteTree(k);
        }
    }

    /** 安装包里带了载荷吗？带了就返回它的流（APK assets 里是 store 的，可直接流式解包）。 */
    private static InputStream openBundled(Context ctx) {
        try {
            return ctx.getAssets().open("dshb-rootfs.tar.gz");
        } catch (Throwable notBundled) {
            return null;
        }
    }

    private static void download(String url, File dest, Progress p) throws IOException {
        p.step("下载快照 242 MB…");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.connect();
        long total = c.getContentLengthLong();
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " " + url);
        File tmp = new File(dest.getPath() + ".part");
        try (InputStream in = new BufferedInputStream(c.getInputStream(), 128 * 1024);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[128 * 1024];
            long done = 0;
            int n;
            long last = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (done - last > 4L * 1024 * 1024) {
                    last = done;
                    p.bytes(done, total);
                }
            }
        } finally {
            c.disconnect();
        }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** POSIX tar（ustar + GNU longname）解包：文件入口。 */
    static void extract(File archive, File root, Progress p) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(archive), 256 * 1024)) {
            extract(in, root, p);
        }
    }

    static void extract(File archive, File root, Progress p, String onlyPrefix) throws IOException {
        extract(archive, root, p, onlyPrefix, false);
    }

    /** ${'$'}fillOnly=true${'$'} 时只补缺失条目，已存在的文件一律跳过（修复路径专用，避免把用户装的版本降级）。 */
    static void extract(File archive, File root, Progress p, String onlyPrefix, boolean fillOnly) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(archive), 256 * 1024)) {
            extract(in, root, p, onlyPrefix, fillOnly);
        }
    }

    /** POSIX tar（ustar + GNU longname）解包：流入口（内置载荷直接从这里喂，不落盘）。 */
    static void extract(InputStream raw, File root, Progress p) throws IOException {
        extract(raw, root, p, null);
    }

    /** 只解包 {@code onlyPrefix} 开头的条目（如 "usr/"），用于「只补 usr、不动 home」的修复路径。 */
    static void extract(InputStream raw, File root, Progress p, String onlyPrefix) throws IOException {
        extract(raw, root, p, onlyPrefix, false);
    }

    static void extract(InputStream raw, File root, Progress p, String onlyPrefix, boolean fillOnly) throws IOException {
        final java.util.Set<String> aptOwned = keepDpkgDb ? aptOwned(root) : null;
        Path base = root.toPath().normalize();
        long count = 0;
        long bytes = 0;
        try (GZIPInputStream gz = new GZIPInputStream(raw, 256 * 1024)) {
            byte[] hdr = new byte[512];
            String longName = null;
            String longLink = null;
            String paxPath = null;
            String paxLink = null;
            while (true) {
                if (!readFully(gz, hdr)) break;
                if (isZero(hdr)) continue;

                String name = str(hdr, 0, 100);
                long mode = oct(hdr, 100, 8);
                long size = oct(hdr, 124, 12);
                char type = (char) (hdr[156] & 0xff);
                String link = str(hdr, 157, 100);
                String magic = str(hdr, 257, 6);
                if (magic.startsWith("ustar")) {
                    String prefix = str(hdr, 345, 155);
                    if (!prefix.isEmpty()) name = prefix + "/" + name;
                }
                if (longName != null) { name = longName; longName = null; }
                if (longLink != null) { link = longLink; longLink = null; }

                if (type == 'L') { // GNU 长文件名
                    longName = readBodyString(gz, size);
                    long restL = size - Math.min(size, 8192);
                    if (restL > 0) skipN(gz, restL);
                    skipPad(gz, size);
                    continue;
                }
                if (type == 'K') {
                    longLink = readBodyString(gz, size);
                    long restK = size - Math.min(size, 8192);
                    if (restK > 0) skipN(gz, restK);
                    skipPad(gz, size);
                    continue;
                }
                if (type == 'x' || type == 'X') { // PAX 扩展头：下一跳的名字/链接在这里
                    String body = readBodyString(gz, size);
                    long restX = size - Math.min(size, 8192);
                    if (restX > 0) skipN(gz, restX);
                    skipPad(gz, size);
                    for (String rec : body.split("\n")) {
                        int sp = rec.indexOf(' ');
                        if (sp <= 0) continue;
                        String kv = rec.substring(sp + 1);
                        if (kv.startsWith("path=")) paxPath = kv.substring(5);
                        else if (kv.startsWith("linkpath=")) paxLink = kv.substring(9);
                    }
                    continue;
                }
                if (type == 'g') { // 全局 PAX 头：跳过
                    skipBody(gz, size);
                    continue;
                }
                if (paxPath != null) { name = paxPath; paxPath = null; }
                if (paxLink != null) { link = paxLink; paxLink = null; }
                if (name.startsWith("./")) name = name.substring(2);
                if (name.indexOf('\n') >= 0 || name.indexOf('\u0000') >= 0) {
                    throw new IOException("tar 头错位（条目名不可信）：" + name);
                }
                if (name.isEmpty() || name.equals(".")) { skipBody(gz, size); continue; }
                if (onlyPrefix != null && !name.startsWith(onlyPrefix)) { skipBody(gz, size); continue; }
                if (keepDpkgDb && name.startsWith("usr/var/lib/dpkg/")) { skipBody(gz, size); continue; }
                if (keepDpkgDb && isKeepDir(name)) { skipBody(gz, size); continue; }
                if (aptOwned != null && aptOwned.contains(name)) { skipBody(gz, size); continue; }
                if (fillOnly) {
                    Path probe = base.resolve(name).normalize();
                    if (probe.startsWith(base) && probe.toFile().exists()) { skipBody(gz, size); continue; }
                }

                Path target = base.resolve(name).normalize();
                if (!target.startsWith(base)) {
                    Log.w(TAG, "跳过越界条目: " + name);
                    skipBody(gz, size);
                    continue;
                }
                File f = target.toFile();

                switch (type) {
                    case '5': { // 目录
                        if (f.exists() && !f.isDirectory()) {
                            Log.w(TAG, "路径类型冲突（应为目录），删除同名文件: " + name);
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                        if (!f.isDirectory() && !f.mkdirs()) Log.w(TAG, "建目录失败: " + name);
                        applyMode(f, mode);
                        break;
                    }
                    case '2': { // 符号链接
                        ensureParent(f);
                        try {
                            if (f.exists() || Files.isSymbolicLink(target)) Files.delete(target);
                            Files.createSymbolicLink(target, Paths.get(link));
                        } catch (Exception e) {
                            Log.w(TAG, "建符号链接失败 " + name + " → " + link + ": " + e);
                        }
                        break;
                    }
                    case '1': { // 硬链接
                        ensureParent(f);
                        Path src = base.resolve(link).normalize();
                        try {
                            if (src.startsWith(base) && src.toFile().isFile()) {
                                if (f.exists()) Files.delete(target);
                                Files.createLink(target, src);
                            } else {
                                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "硬链接失败 " + name + ": " + e);
                        }
                        break;
                    }
                    case '0':
                    case '\0':
                    case '7': { // 普通文件
                        ensureParent(f);
                        // 同路径残留的旧目录会让 FileOutputStream 抛 EISDIR（新设备首装撞过），先清掉
                        if (f.isDirectory()) {
                            Log.w(TAG, "路径类型冲突（应为文件），删除同名目录: " + name);
                            Backup.deleteTree(f);
                        }
                        try (OutputStream out = new FileOutputStream(f)) {
                            copyN(gz, out, size);
                        }
                        applyMode(f, mode);
                        bytes += size;
                        break;
                    }
                    default: {
                        Log.w(TAG, "跳过未知 tar 类型 " + type + " (" + name + ")");
                        skipBody(gz, size);
                        continue;
                    }
                }
                skipPad(gz, size);
                if ((++count % 2000) == 0) {
                    p.step("解包 " + count + " 个条目，" + (bytes / 1048576) + " MB");
                }
            }
        }
        p.step("解包完成：" + count + " 个条目");
    }

    /**
     * 还原 tar 头里的权限位。
     *
     * 必须用 POSIX 权限整位设置，不能只调 setReadable/setWritable/setExecutable：
     * 那套 API 表达不了"只给属主"——实测把 600 的 .credentials.yaml 还原成了 666，
     * 于是 dsh-credentials-local 的 assertOwnerOnly() 直接拒绝启动
     * （错误原文："is readable beyond its owner (mode 666)"）。
     */
    private static void applyMode(File f, long mode) {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                    java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class);
            add(perms, mode, 0400, 0200, 0100,
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            add(perms, mode, 0040, 0020, 0010,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                    java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            add(perms, mode, 0004, 0002, 0001,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            java.nio.file.Files.setPosixFilePermissions(f.toPath(), perms);
            return;
        } catch (Throwable ignored) {
            // 某些文件系统不支持 POSIX 权限，退化成粗粒度设置（至少保住可执行位）
        }
        //noinspection ResultOfMethodCallIgnored
        f.setReadable((mode & 0400) != 0, false);
        //noinspection ResultOfMethodCallIgnored
        f.setWritable((mode & 0200) != 0, false);
        //noinspection ResultOfMethodCallIgnored
        f.setExecutable((mode & 0100) != 0, false);
    }

    private static void add(java.util.Set<java.nio.file.attribute.PosixFilePermission> set, long mode,
                            long r, long w, long x,
                            java.nio.file.attribute.PosixFilePermission pr,
                            java.nio.file.attribute.PosixFilePermission pw,
                            java.nio.file.attribute.PosixFilePermission px) {
        if ((mode & r) != 0) set.add(pr);
        if ((mode & w) != 0) set.add(pw);
        if ((mode & x) != 0) set.add(px);
    }

    private static void ensureParent(File f) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("建父目录失败: " + parent);
        }
    }

    private static String readBodyString(InputStream in, long size) throws IOException {
        byte[] buf = new byte[(int) Math.min(size, 8192)];
        readFully(in, buf);
        return new String(buf, StandardCharsets.UTF_8).trim();
    }

    private static void copyN(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[128 * 1024];
        long left = size;
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int n = in.read(buf, 0, want);
            if (n < 0) throw new IOException("解包时数据提前结束（还差 " + left + " 字节）");
            out.write(buf, 0, n);
            left -= n;
        }
    }

    /** 跳过整整一条（正文 + 对齐 padding）。正文还没读过的跳过路径必须用这个。 */
    private static void skipBody(InputStream in, long size) throws IOException {
        skipN(in, size);
        skipPad(in, size);
    }

    /** 只跳过 padding（正文已经读完）。 */
    private static void skipPad(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        if (pad > 0) readFully(in, new byte[(int) pad]);
    }

    private static void skipN(InputStream in, long n) throws IOException {
        byte[] buf = new byte[64 * 1024];
        while (n > 0) {
            int want = (int) Math.min(buf.length, n);
            int got = in.read(buf, 0, want);
            if (got < 0) throw new java.io.EOFException("tar 流提前结束");
            n -= got;
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long oct(byte[] b, int off, int len) {
        String s = str(b, off, len).trim();
        if (s.isEmpty()) return 0;
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 换载荷时要跨版本保留的用户数据（相对 home 的路径）。
     *
     * <p>不保留的东西：{@code .dsh/profiles}（插件树属于程序，必须跟着载荷走，否则新插件装不上）、
     * {@code tmp}（引擎自己会重建）、{@code .dsh/logs}（诊断日志，留着只会让上传变慢）。
     */

    /** 把 KEEP 里存在的条目 move 到暂存目录，结构原样保留。返回是否有过任何一条。 */

    /** 解包之后把暂存目录里的条目放回 home，覆盖载荷里的同名新鲜副本。 */

    /**
     * 把 src 搬到 dst。目标已存在时**先改名挪开再删**——直接 delete 失败会留下
     * 非空同名目录，让 Files.move 抛 DirectoryNotEmpty（1.1.6 真机上就是这个）。
     * rename 失败时退化为递归复制 + 删源。
     */



    /**
     * 从备份 home 里把 KEEP 清单**拷贝**回新 home（不是移动）：备份始终留在原地，
     * 装挂了/装错了都能回滚，也不会出现「搬走之后判据错了」这种事。
     */

    /** 只保留最近 keep 份 home.bak-*，更老的才删。 */

    /**
     * 迁移期兼容：旧版本用 .keep 暂存 KEEP 条目。
     * 这里**只增不删**——home 里缺什么就补什么，绝不拿 .keep 覆盖 home 里已有的东西。
     */

    /** 删除并检查：删不干净时把原因写进日志（旧 deleteTree 静默失败，正是 1.1.6 那个 bug 的土壤）。 */


    /**
     * 预置一个默认工作区：载荷里已经有 workspace.json 的话就跳过（幂等）。
     * 为什么需要：我们删掉上游 @dsh-android/* 之后，没有任何插件替用户建默认文件夹，
     * 首启打开只有「选/建文件夹」，得手动点一次才能聊天。
     */
    /** 启动时保证「默认工作区」可用：目录在、注册表在。
     *  2026-11-13 次生问题：注册表/目录任缺，界面就只剩「选择工作区」，对话输入框打不开。 */

    /** 把种子文件里"构建机视角"的绝对路径改写成运行时真实路径。
     *  1.3.2 修的是 workspace.json；同类隐患还有 settings.yaml 与 profile 补丁里的 shellPath。
     *  旧包名（app.dsh.shellb）的历史残留也一并清掉。 */

}
