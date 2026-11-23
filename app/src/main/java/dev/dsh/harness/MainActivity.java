package dev.dsh.harness;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;

/**
 * 路线 B 的壳 Activity：只负责「显示」与「进度提示」，引擎交给 {@link EngineService} 前台服务持有。
 *
 * <p>启动链路：启服务 → 服务里 {@link Engine#ensure}（下载载荷 → 解包 → linker64 起 node → 拿 token）
 * → 回到 Activity 带 token 导航（303 换 cookie）→ WebView 出真 DSH 界面。
 *
 * <p>保活语义：Activity 死掉<b>不</b>停引擎；回到前台时探活（TCP + HTTP 真回包），
 * 死了就地重新拉起并直接 loadUrl，不会出现「网页无法打开」的死页。
 */
public class MainActivity extends Activity {
    private static final String TAG = "DSH-B";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private WebView web;
    private WebView popup;
    private FrameLayout host;
    /** 侧栏鱼标（窄轨里那个）在页面里的实测位置，CSS px；由页面 JS 量完回调 Bridge.anchor。 */
    private volatile float anchorCx = -1f;
    private volatile float anchorCy = -1f;
    private volatile float anchorSize = 0f;

    private Splash splash;
    private android.view.View reconnect;
    private android.widget.TextView reconnectMsg;
    private volatile boolean needReload;
    private volatile boolean pageVisible;
    private boolean watchStarted;
    private volatile boolean recovering;
    private volatile int epPct = -1;
    private volatile String epStage = "";
    private volatile boolean updating;

    /** 单飞保护：同一时刻只允许一个 APK 下载 / 一个引擎包下载，避免双击叠加出两份下载。 */
    private volatile String downloadingUrl = null;
    /** 已下载、等用户确认安装的外壳包（不自动拉起安装器，避免打断正在跑的任务）。 */
    private volatile java.io.File pendingApk = null;
    private static final String PREF_PENDING_APK = "pending_apk";
    private volatile String downloadingEngine = null;
    /** 交接动画用的独立图层：启动时就建好、常驻，交接时只改变换属性 —— 不增删视图、不触发重排。 */
    private FrameLayout overlay;
    private android.widget.ImageView flyer;
    private android.view.View glow;
    /** 鱼的起始尺寸（dp），overlay 里预先按这个尺寸布局。 */
    private static final float FISH_DP = 88f;
    private volatile boolean loaded;
    private boolean repairTried;
    /** 页面自愈：失败页/黑屏时后台盯着引擎，一旦健康就自动重载。 */
    private volatile boolean healing;
    private int healRounds;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Life.note(this, "MainActivity.onCreate saved=" + (saved != null));
        epLoadEvents();
        writeAppVersion();
        // 电池豁免推迟到页面起来后再申请（见 loadUrl 之后）

        // 引擎被看门狗重新拉起（升级完 / 重启脚本踢了它）之后，WebView 还抱着已经断掉的旧连接 ——
        // 这里注册一次性回调，由 EngineService 在拉起成功后调用，直接重新加载首页（cookie 还在）。
        Engine.onRestarted = () -> ui.post(() -> {
            WebView w = web;
            if (w == null) return;
            Life.note(this, "引擎重启完成，重新加载界面");
            epLine("看门狗重启引擎，已恢复 · 版本 " + (engineVersion() == null ? "未知" : engineVersion()));
            String t = Engine.token();
            if (t == null) {
                // 引擎还活着但拿不到它的 token（例如被外部脚本/别的会话重启过）：
                // 裸开 "/" 只会拿到 401 那个"什么都没有"的空页，宁可重启一次换新 token。
                Life.note(this, "重启后没有 token，强制重启引擎取新 token");
                new Thread(() -> {
                    try {
                        Engine.stop();
                        String tok = Engine.ensure(this, null);
                        final String fresh = tok;
                        ui.post(() -> {
                            WebView v = web;
                            if (v == null) return;
                            loaded = true;
                  ui.postDelayed(this::askIgnoreBattery, 6000);
                  ui.postDelayed(() -> checkBrokenPlugins(w), 12000);
                            String u = "http://127.0.0.1:" + Engine.port() + "/" + (fresh != null ? "?token=" + fresh : "");
                            v.loadUrl(u);
                            ui.postDelayed(() -> checkPageAlive(v, u, 0), 6000);
                        });
                    } catch (Throwable e) {
                        Life.note(this, "强制重启引擎失败：" + e);
                    }
                }, "heal-token").start();
                return;
            }
            final String url = "http://127.0.0.1:" + Engine.port() + "/" + (t != null ? "?token=" + t : "");
            loaded = true;
            w.loadUrl(url);
            // 重启后 WebView 偶发"已加载但不重绘"（表现为白屏，碰一下屏幕才恢复）：
            // 主动复位一次 surface，并复用 boot() 那套「正文为空就自动重载」的自检。
            w.invalidate();
            w.requestLayout();
            ui.postDelayed(() -> checkPageAlive(w, url, 0), 6000);
        });

        final FrameLayout root = new FrameLayout(this);
        host = root;
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        // 关键：WebSettings 默认 setSupportMultipleWindows(false) —— 这时 window.open() 直接被忽略，
        // WebChromeClient.onCreateWindow 根本不会被调用（登录就是这个流程，表现为点完没反应/黑屏）。
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        web.setBackgroundColor(Color.parseColor("#0f1115"));
        web.addJavascriptInterface(new Bridge(), "DSHBridge");
        web.addJavascriptInterface(new ShellBridge(), "DSHShell");
        wireBrowser(root);
        // 侧栏内置浏览器里的下载：WebView 自己不会保存文件，必须给 DownloadListener，
        // 否则点「下载」什么都不会发生（这就是之前"下载没法验证"的原因）。
        web.setDownloadListener((url, ua, cd, mime, len) -> startDownload(url, cd, mime));
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 开屏期间 WebView 先不可见：它在下面每帧都被合成一次，是开屏动画掉帧的大头。
        // 页面加载完（onPageFinished）再打开，此时开屏还完全不透明，首绘照样发生在幕布下。
        web.setVisibility(android.view.View.INVISIBLE);

        // 启动屏：冷启动期间（解包 / 起 node / 等 HTTP）不再是一块纯黑
        splash = new Splash(this);
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 交接图层必须在开屏**之上**：鱼一边飞、开屏背景一边化掉，鱼不能被盖在自己家开屏下面。
        // 预先建好、常驻；交接时只改变换属性（translation/scale/alpha），
        // 不 addView/removeView、不改 LayoutParams —— 否则动画第一帧会夹一次全量重排。
        overlay = new FrameLayout(this);
        overlay.setClickable(false);
        overlay.setFocusable(false);
        flyer = new android.widget.ImageView(this);
        flyer.setVisibility(android.view.View.INVISIBLE);
        flyer.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        flyer.setImageBitmap(null);
        overlay.addView(flyer, new FrameLayout.LayoutParams(Math.round(dp(FISH_DP)), Math.round(dp(FISH_DP))));
        glow = new android.view.View(this);
        glow.setBackgroundResource(R.drawable.ripple_glow);
        glow.setAlpha(0f);
        glow.setVisibility(android.view.View.INVISIBLE);
        overlay.addView(glow, new FrameLayout.LayoutParams(Math.round(dp(150)), Math.round(dp(150))));
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        applyFullscreen();
        kick();
    }

    /**
     * 全屏：隐藏状态栏 + 导航栏，内容铺满，边缘上滑临时唤出系统栏（IMMERSIVE_STICKY 会自动再收起）。
     * targetSdk 28，用不着 WindowInsetsController 那套新 API。
     */
    private void applyFullscreen() {
        android.view.Window w = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        hideSystemBars();
    }

    private void hideSystemBars() {
        android.view.View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onBackPressed() {
        if (popup != null) {
            closePopup();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 键盘收起/切回来时系统栏可能又冒出来，重新压下去
        if (hasFocus) hideSystemBars();
    }

    /** 保证服务起来，然后（如果当前没页面）走一次导航。 */
    /**
     * 请求「忽略电池优化」。不申请的话，系统会在切后台约 1 分钟后回收整个进程
     * （连带 node 子进程一起），这是我们反复抓到的那个"起来一会儿就没了"的最后一块拼图。
     * 只在还没被豁免时弹一次。
     */
    private void askIgnoreBattery() {
        try {
            android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            android.content.Intent in = new android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            in.setData(android.net.Uri.parse("package:" + getPackageName()));
            in.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(in);
            Life.note(this, "已请求忽略电池优化");
        } catch (Throwable e) {
            Life.note(this, "请求忽略电池优化失败：" + e);
        }
    }

    private void kick() {
        startService(new Intent(this, EngineService.class));
        if (loaded) return;
        new Thread(this::boot, "boot").start();
    }

    private void boot() {
        dropStalePending();
        Mirror.start(this);
        Life.note(this, "启动后诊断：" + Rootfs.diag(getFilesDir()));
        Life.note(this, Rootfs.reclaimOldEnginesNow(getFilesDir()));
        Life.note(this, Rootfs.orphanBinaries(this, getFilesDir()));
        Life.note(this, Rootfs.ensureDpkgWrapper(this));
        Life.note(this, Rootfs.ensureDpkgLists(this));
        final File root = Rootfs.dir(this);
        // 2026-11-19 外部审查发现的四个「没人管」项：dsh 入口/ dpkg 数据库 / updates 旧包 / 怪名条目
        Life.note(this, Rootfs.ensureDshCli(root));
        Life.note(this, Rootfs.ensureDpkgDb(this, root));
        Life.note(this, Rootfs.cleanUpdateTarballs(root));
        Life.note(this, Rootfs.cleanBadNames(root));
        Life.note(this, Rootfs.normalizeDpkgLists(root));
        Backup.pruneHomeBackups(root, 2);
        // apt 索引为空时补一次 update：慢，放后台线程，不许拖住引擎启动
        new Thread(() -> Life.note(this, Rootfs.ensureAptIndex(root)), "apt-index").start();
        Life.note(this, Rootfs.ensurePtyBinding(this));
        Life.note(this, Rootfs.ensureEngineBuildScript(this));
        Life.note(this, Rootfs.ensureEngineUpdateScript(this));
        Life.note(this, Rootfs.ensureEnginePortScript(this));
        // 引擎已在跑时 Engine.ensure() 会直接返回，所以别名这里无条件补一次（幂等）
        Rootfs.ensurePrefixLinks(root);
        Rootfs.ensureLightTheme(root, Build.VERSION_SNAPSHOT);
        Life.note(this, Rootfs.aliasCheck(root));
        // 先恢复 profile（它可能整个目录消失），再清理游离插件：顺序反了 scrub 会读不到
        // 顶层 @deepseek-ai/dsh 之外的版本，且恢复动作会把残渣重新带回来。
        Life.note(this, Rootfs.ensureProfile(this));
        Life.note(this, Rootfs.scrubForeignPlugins(root, Rootfs.assetText(this, "engine-version.txt")));
        restartEngineIfUpgraded();
        Life.note(this, Profile.disableBrokenClient(this));
        Life.note(this, Profile.ensureAppRelease(this));
        Life.note(this, Profile.ensureMarket(this));
        String rec = Rootfs.recoverEngineDir(this);
        if (rec != null) Life.note(this, rec);
        Life.note(this, LoginKeeper.ensure(root, "http://127.0.0.1:" + Engine.port() + "/"));
        Life.note(this, LoginKeeper.state(root));
        Life.note(this, Rootfs.ensureHelperScripts(this));
        String rgPatch = EnginePatch.ensureRg(root);
        Life.note(this, rgPatch != null ? rgPatch : "rg 补丁：已是最新");
        String browserPatch = EnginePatch.ensureBrowser(root);
        Life.note(this, browserPatch != null ? browserPatch : "浏览器补丁：已是最新");
        Life.note(this, Workspace.ensureWorkspace(root));
        Life.note(this, Workspace.normalizeSeedPaths(root));
        try {
            boolean wasUp = Engine.isUp();          // 引擎没在跑 ⇒ 这次 ensure 就是一次启动，记进「最近事件」
            String token = Engine.ensure(this, msg -> note(msg));

            long deadline = System.currentTimeMillis() + 60000;
            while (!Engine.httpReady() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            }
            if (!Engine.httpReady()) {
                fail(root, "引擎起来了但页面没响应（HTTP 不通）");
                return;
            }
            uploadEngineProbe(root);
            logBundlePresence();
            if (!wasUp) epLine("引擎已启动 · 版本 " + (engineVersion() == null ? "未知" : engineVersion()));

            String t = Engine.token() != null ? Engine.token() : token;
            if (t == null) {
                // 引擎是从上一版继承下来的（进程在、令牌文件没了），裸开 / 只会拿到 401 页。
                // 与其等 15 秒自检，不如当场重启一次拿新令牌。
                Life.note(this, "启动时没有现成 token，重启引擎取新令牌");
                Engine.stop();
                t = Engine.ensure(this, msg -> note("正在重新启动引擎…"));
                long d2 = System.currentTimeMillis() + 60000;
                while (!Engine.httpReady() && System.currentTimeMillis() < d2) {
                    try { Thread.sleep(300); } catch (InterruptedException e) { return; }
                }
                if (t == null) t = Engine.token();
                Life.note(this, "重启后 token " + (t != null ? "已取到" : "仍为空"));
            }
            if (probeReleaseApi() == 404) {
                Life.note(this, "更新接口 404：引擎进程没加载当前插件，重启引擎");
                Engine.stop();
                t = Engine.ensure(this, msg -> note("正在重新启动引擎…"));
                long d3 = System.currentTimeMillis() + 60000;
                while (!Engine.httpReady() && System.currentTimeMillis() < d3) {
                    try { Thread.sleep(300); } catch (InterruptedException e) { return; }
                }
                if (t == null) t = Engine.token();
                Life.note(this, "重启后更新接口 " + probeReleaseApi());
            }
            final String tf = t;
            final String url = "http://127.0.0.1:" + Engine.port() + "/"
                    + (tf != null ? "?token=" + tf : "");
            ui.post(() -> {
                WebView w = web;
                if (w == null) {
                    Life.note(this, "boot 完成但 Activity 已销毁，丢弃这次导航");
                    return;
                }
                note("加载界面…");
                w.loadUrl(url);
                loaded = true;
                if (!watchStarted) {
                    watchStarted = true;
                    ui.postDelayed(this::watchTick, 8000);
                }
                // 页面可能"只画出品牌 logo 就停住"（客户端 bundle 报错 / 引擎刚换过连接），
                // 旧版本只能靠用户划掉重开。这里 15 秒后自检一次：正文几乎是空的就自己重载。
                ui.postDelayed(() -> checkPageAlive(w, url, 0), 15000);
            });
        } catch (Throwable e) {
            Log.e(TAG, "boot 失败", e);
            fail(root, "启动失败：" + e);
        }
    }

    /** 页面 <input type=file> 的回调；不接这个，点「文件」就毫无反应。 */
    private android.webkit.ValueCallback<android.net.Uri[]> fileChooserCallback;
    private static final int REQ_FILE_CHOOSER = 0x2001;
    private static final int REQ_MEDIA_PERM = 0x2002;
    private android.webkit.PermissionRequest pendingPermissionRequest;

    /** 每次启动只尝试一次的「插件加载失败」自愈。 */
    private boolean pluginRepairTried;

    /**
     * 页面报「Failed to load plugins / invalid plugin」时自救一次。
     *
     * 成因：载荷里 dsh-app-release 的客户端 bundle 返回 { exports: {} }，不符合 DSH
     * 客户端插件契约，整页插件加载失败（2026-11-22 新设备实测）。
     * 修法：摘掉该客户端 bundle，停引擎让看门狗重启，onRestarted 会自动重载页面。
     */
    private void checkBrokenPlugins(final WebView w) {
        if (pluginRepairTried) return;
        try {
            w.evaluateJavascript(
                    "(()=>{try{return (document.body&&document.body.innerText)||''}catch(e){return ''}})()",
                    v -> {
                        String txt = v == null ? "" : v.toLowerCase();
                        if (!txt.contains("failed to load plugins") && !txt.contains("invalid plugin")) return;
                        pluginRepairTried = true;
                        Life.note(this, "页面报告插件加载失败，开始自愈");
                        String r = Profile.disableBrokenClient(this);
                        Life.note(this, "插件自愈：" + r + "；重启引擎");
                        try { Engine.stop(); } catch (Throwable e) { Life.note(this, "自愈重启失败：" + e); }
                    });
        } catch (Throwable e) {
            Life.note(this, "插件自检失败：" + e);
        }
    }

    private volatile boolean probeSent;

    /** 每次启动只回传一次「插件加载相关」的引擎日志行 —— 用来判断 dsh-app-release 有没有激活。 */
    private void uploadEngineProbe(final File root) {
        if (probeSent) return;
        probeSent = true;
        new Thread(() -> {
            try {
                File f = new File(root, "engine.log");
                if (!f.isFile()) return;
                String all = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                StringBuilder sb = new StringBuilder();
                for (String ln : all.split("\n")) {
                    if (ln.contains("dsh-app-release") || ln.contains("did not activate")
                            || ln.contains("incompatible") || ln.contains("skipping profile")
                            || ln.contains("cannot resolve") || ln.contains("warning:")) {
                        sb.append(ln).append('\n');
                    }
                }
                if (sb.length() == 0) sb.append("(no matching lines; engine.log ").append(all.length()).append(" chars)");
                Diag.upload("engine-probe-" + new java.text.SimpleDateFormat("MMdd-HHmmss",
                        java.util.Locale.US).format(new java.util.Date()) + ".log", sb.toString());
            } catch (Throwable ignored) {
            }
        }, "engine-probe").start();
    }

    /** 失败收口：把引擎 stdout 尾巴 + DSH 自己写的 startup-*.log 回传到本机下载服务。 */
    private void fail(File root, String why) {
        final StringBuilder sb = new StringBuilder(4096);
        sb.append("why: ").append(why).append('\n');
        sb.append("snapshot: ").append(Build.VERSION_SNAPSHOT).append('\n');
        java.util.List<String> tail = Engine.tail();
        sb.append("\n=== engine stdout (last ").append(tail.size()).append(") ===\n");
        sb.append(String.join("\n", tail)).append('\n');
        sb.append("\n=== startup log ===\n").append(Diag.latestStartupLog(root));
        String name = "dshb-" + new java.text.SimpleDateFormat("MMdd-HHmmss",
                java.util.Locale.US).format(new java.util.Date()) + ".log";
        boolean ok = Diag.upload(name, sb.toString());
        note(why + "\n启动日志" + (ok ? "已回传：logs/" + name : "回传失败（下载服务没开？）")
                + "\n最后输出：\n" + String.join("\n", tail));
        hideSplash(0);
        showFailurePage(why, ok ? name : null, tail);
        startHeal();
    }

    /**
     * 引擎没起来时，WebView 里显示一张能重试的错误页。
     * 以前这里什么都不画，用户看到的就是一块白屏或黑屏闪一下 —— 分不清是"卡住"还是"坏了"。
     */
    private void showFailurePage(final String why, final String logName, final java.util.List<String> tail) {
        ui.post(() -> {
            final WebView w = web;
            if (w == null) return;
            String detail = why == null ? "" : why;
            StringBuilder log = new StringBuilder();
            int from = Math.max(0, tail.size() - 8);
            for (int i = from; i < tail.size(); i++) log.append(tail.get(i)).append('\n');
            final String plain = detail.replaceFirst("^启动失败：", "");
            String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                    + "<body style='margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                    + "background:#0F1115;color:#E8EAED;font:15px/1.6 -apple-system,system-ui,'Segoe UI',Roboto,sans-serif'>"
                    + "<div style='max-width:460px;width:100%;padding:28px;box-sizing:border-box'>"
                    + "<div style='font-size:12px;letter-spacing:.14em;color:#6B7280'>DEEPSEEK HARNESS</div>"
                    + "<h1 style='font-size:20px;margin:14px 0 8px;font-weight:600'>引擎启动失败</h1>"
                    + "<p style='margin:0 0 18px;color:#9AA0A6;font-size:14px'>"
                    + "应用需要本地引擎才能工作，本次未能启动。请点击下方“重试”。</p>"
                    + "<div style='border:1px solid #23272E;border-radius:12px;background:#16181D;padding:14px 16px;margin:0 0 16px'>"
                    + "<div style='font-size:12px;color:#6B7280;margin-bottom:8px'>错误信息</div>"
                    + "<div style='font:12.5px/1.6 ui-monospace,Menlo,monospace;color:#C9CED6;"
                    + "white-space:pre-wrap;word-break:break-word'>" + Utils.esc(plain) + "</div>"
                    + (log.length() > 0
                        ? "<details style='margin:12px 0 0'><summary style='cursor:pointer;color:#8B93A1;font-size:12.5px'>技术详情</summary>"
                          + "<pre style='margin:8px 0 0;padding:10px 12px;border-radius:8px;background:#0F1115;color:#8B93A1;"
                          + "font:11px/16px ui-monospace,Menlo,monospace;white-space:pre-wrap;word-break:break-all;"
                          + "max-height:160px;overflow:auto;border:1px solid #23272E'>"
                          + Utils.esc(log.toString()) + "</pre></details>"
                        : "")
                    + "</div>"
                    + (logName != null
                        ? "<p style='margin:0 0 16px;color:#6B7280;font-size:12px'>诊断日志已保存：" + Utils.esc(logName) + "</p>"
                        : "")
                    + "<button onclick='DSHShell.retry()' style='width:100%;height:46px;border:0;border-radius:12px;"
                    + "background:#E8EAED;color:#0F1115;font-size:15px;font-weight:600'>重试</button>"
                    + "<p style='margin:14px 0 0;color:#6B7280;font-size:12.5px;line-height:1.6'>"
                    + "若多次失败：完全退出应用后重新打开；仍无法解决可在系统设置中清除本应用数据（会删除对话记录）。</p>"
                    + "</div></body>";
            w.loadDataWithBaseURL("http://127.0.0.1/", html, "text/html", "utf-8", null);
        });
    }

    /**
     * 页面自检：正常情况下 1 秒内 WebView 就有正文；如果 15 秒后正文几乎为空，
     * 说明客户端脚本没起来（表现就是一块黑底 + 一只鲸鱼）。这里最多重载 3 次。
     */
    /** 引擎重启/重连期间的遮罩：深色渐变 + 毛玻璃卡片 + 转圈，别让用户盯着白屏。 */
    private void showReconnect(String msg) {
        if (reconnect == null) {
            android.widget.FrameLayout root = new android.widget.FrameLayout(this);
            root.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF0D0F12, 0xFF14171C}));

            android.widget.LinearLayout card = new android.widget.LinearLayout(this);
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            android.graphics.drawable.GradientDrawable cb = new android.graphics.drawable.GradientDrawable();
            cb.setColor(0xFF191C22);
            cb.setCornerRadius(dp(22));
            cb.setStroke(Math.round(dp(1)), 0x1FFFFFFF);
            card.setBackground(cb);
            int pad = Math.round(dp(28));
            card.setPadding(pad, pad, pad, pad);

            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(R.drawable.ic_fish);
            card.addView(icon, new android.widget.LinearLayout.LayoutParams(
                    Math.round(dp(56)), Math.round(dp(56))));

            android.widget.TextView title = new android.widget.TextView(this);
            title.setText("正在重启引擎");
            title.setTextColor(0xFFECEEF1);
            title.setTextSize(16f);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            android.widget.LinearLayout.LayoutParams tp =
                    new android.widget.LinearLayout.LayoutParams(-2, -2);
            tp.topMargin = Math.round(dp(16));
            card.addView(title, tp);

            android.widget.ProgressBar bar =
                    new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
            bar.setIndeterminate(true);
            bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFF3B82F6));
            android.widget.LinearLayout.LayoutParams bp =
                    new android.widget.LinearLayout.LayoutParams(Math.round(dp(22)), Math.round(dp(22)));
            bp.topMargin = Math.round(dp(18));
            card.addView(bar, bp);

            reconnectMsg = new android.widget.TextView(this);
            reconnectMsg.setTextColor(0xFF8B929C);
            reconnectMsg.setTextSize(12.5f);
            reconnectMsg.setGravity(android.view.Gravity.CENTER);
            reconnectMsg.setMaxWidth(Math.round(dp(250)));
            android.widget.LinearLayout.LayoutParams mp =
                    new android.widget.LinearLayout.LayoutParams(-2, -2);
            mp.topMargin = Math.round(dp(12));
            card.addView(reconnectMsg, mp);

            android.widget.FrameLayout.LayoutParams cp =
                    new android.widget.FrameLayout.LayoutParams(-2, -2);
            cp.gravity = android.view.Gravity.CENTER;
            root.addView(card, cp);
            root.setOnClickListener(v -> { /* 吃掉点击 */ });
            ((android.view.ViewGroup) web.getParent()).addView(root,
                    new android.widget.FrameLayout.LayoutParams(-1, -1));
            reconnect = root;
        }
        if (reconnectMsg != null) reconnectMsg.setText(msg);
        reconnect.setVisibility(android.view.View.VISIBLE);
        reconnect.bringToFront();
    }

    private void hideReconnect() {
        if (reconnect != null) reconnect.setVisibility(android.view.View.GONE);
    }

    /** 关掉页面里的引擎面板、版本标签与旧对话框遮罩 —— 换页/重启前统一收口。 */
    private void closePanels() {
        WebView w = web;
        if (w == null) return;
        try {
            w.evaluateJavascript(
                "(function(){var a=document.getElementById('dshb-ep');if(a)a.style.display='none';"
                        + "var b=document.getElementById('dshb-engine');if(b)b.remove();"
                        + "var c=document.getElementById('dshb-mask');if(c)c.remove();"
                        + "var d=document.getElementById('dshb-chip');if(d)d.remove();return 1;})()", null);
        } catch (Throwable ignored) { }
    }

    /** 每 8 秒看一眼引擎：挂了就盖遮罩，恢复了就带新 token 自动回首页（治"白屏不自愈"）。 */
    private void watchTick() {
        if (!pageVisible || !loaded) {
            ui.postDelayed(this::watchTick, 8000);
            return;
        }
        if (updating) {
            ui.postDelayed(this::watchTick, 4000);
            return;
        }
        new Thread(() -> {
            final boolean up = Engine.isUp();
            ui.post(() -> {
                if (!up) {
                    needReload = true;
                    showReconnect("引擎正在重启，稍等…\n（好了会自动回来，不用管它）");
                } else if (needReload) {
                    needReload = false;
                    recoverReload();
                }
                ui.postDelayed(this::watchTick, 8000);
            });
        }, "watch-tick").start();
    }

    /** 引擎自己回来之后：只取 token 重新进首页，绝不再 stop（否则会和 EngineService 的看门狗互相打架）。 */
    private void recoverReload() {
        if (recovering) return;
        recovering = true;
        new Thread(() -> {
            String tok = null;
            try {
                tok = Engine.ensure(MainActivity.this, null);
            } catch (Throwable e) {
                Life.note(MainActivity.this, "看门狗：取 token 异常 " + e);
            }
            final String token = tok;
            ui.post(() -> {
                recovering = false;
                WebView v = web;
                if (v == null) return;
                if (token == null) {
                    Life.note(this, "看门狗：引擎还没给出 token，稍后再试");
                    ui.postDelayed(this::watchTick, 3000);
                    return;
                }
                String u = "http://127.0.0.1:" + Engine.port() + "/?token=" + token;
                Life.note(this, "看门狗：引擎已恢复，带着新 token 回首页");
                closePanels();
                hideReconnect();
                loaded = true;
                v.loadUrl(u);
                ui.postDelayed(() -> checkPageAlive(v, u, 1), 12000);
            });
        }, "watch-recover").start();
    }

    /** 停掉（可能没 token 的）旧引擎、重起一个带新 token 的，然后带着 token 重新进首页。 */
    private void reloadWithFreshToken(final WebView w, final String fallbackUrl) {
        new Thread(() -> {
            try {
                Engine.stop();
                String tok = Engine.ensure(this, null);
                ui.post(() -> {
                    WebView v = web;
                    if (v == null) return;
                    if (tok == null) {
                        Life.note(this, "取新 token 失败，保持原页面");
                        return;
                    }
                    String u = "http://127.0.0.1:" + Engine.port() + "/?token=" + tok;
                    Life.note(this, "已取新 token，重新加载首页");
                    closePanels();
                    hideReconnect();
                    loaded = true;
                    v.loadUrl(u);
                    ui.postDelayed(() -> checkPageAlive(v, u, 1), 12000);
                });
            } catch (Throwable e) {
                Life.note(this, "取新 token 失败：" + e);
            }
        }, "heal-token").start();
    }

    private void checkPageAlive(final WebView w, final String url, final int round) {
        if (w == null || round >= 3 || !loaded) return;
        try {
            w.evaluateJavascript(
                "(function(){var b=document.body;if(!b)return '0|0';"
                        + "var t=(b.innerText||'');var n=t.trim().length;"
                        + "var a=/authentication required/i.test(t);var ok=!a&&(n>=200||/登录|Sign in|Sign up|DeepSeek|工作区|新建会话|New chat/.test(t));"
                        + "return (ok?'1|':'0|')+n;})()", value -> {
                final String raw = value == null ? "" : value.replace("\"", "");
                final boolean ok = raw.startsWith("1|");
                int len = 0;
                try {
                    len = Integer.parseInt(raw.replaceAll("[^0-9]", ""));
                } catch (Throwable ignored) { }
                if (ok) {
                    Life.note(this, "页面自检：正常（正文 " + len + " 字）");
                    return;
                }
                if (repairTried) {
                    Life.note(this, "页面自检：仍不正常（正文 " + len + " 字），已自动修复过一次，等用户手动重试");
                    return;
                }
                repairTried = true;
                {
                    Life.note(this, "页面自检：正文只有 " + len + " 字，第 " + (round + 1) + " 次修复");
                    note("页面没起来，正在自动重试…");
                    if (round == 0) {
                        // 最常见的一种"空白"其实是 401 鉴权页：cookie 随旧引擎一起失效了。
                        // 单纯 reload 只会一直 401，必须重启引擎拿新 token 再进。
                        reloadWithFreshToken(w, url);
                        return;
                    }
                    w.loadUrl(url);
                    ui.postDelayed(() -> checkPageAlive(w, url, round + 1), 12000 + round * 4000);
                }
            });
        } catch (Throwable e) {
            Life.note(this, "页面自检异常：" + e);
        }
    }

    /** 失败页上盯着引擎：它要是自己活过来了（看门狗/重启脚本拉起），就把页面换回来。 */
    private void startHeal() {
        if (healing) return;
        healing = true;
        healRounds = 0;
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (!healing || healRounds >= 20) {
                healing = false;
                return;
            }
            healRounds++;
            new Thread(() -> {
                boolean up = Engine.isUp();
                ui.post(() -> {
                    if (!healing) return;
                    if (up) {
                        WebView w = web;
                        String t = Engine.token();
                        if (w != null) {
                            String u = "http://127.0.0.1:" + Engine.port() + "/" + (t != null ? "?token=" + t : "");
                            Life.note(this, "引擎已恢复，自动回到界面");
                            note("好了，正在回到界面…");
                            healing = false;
                            w.loadUrl(u);
                            loaded = true;
                            ui.postDelayed(() -> checkPageAlive(w, u, 0), 15000);
                            return;
                        }
                    }
                    ui.postDelayed(step[0], 8000);
                });
            }, "heal-probe").start();
        };
        ui.postDelayed(step[0], 8000);
    }

    /** 错误页里的文本要转义，否则日志里的尖括号会把页面吃掉。 */
    
    /** 错误页「重试」按钮的桥。 */
    public class ShellBridge {
        /** 面板里「重启引擎」：停掉旧进程重新拉一次（旧进程可能带着上一版载荷的代码/插件）。 */
        @android.webkit.JavascriptInterface
        public void restartEngine() {
            Life.note(MainActivity.this, "JS 请求重启引擎");
            epLine("手动重启引擎");
            ui.post(() -> {
                note("正在重启引擎…");
                new Thread(() -> {
                    Engine.stop();
                    boot();
                }, "bridge-restart").start();
            });
        }

        @android.webkit.JavascriptInterface
        public void retry() {
            Life.note(MainActivity.this, "错误页点了重试");
            ui.post(() -> {
                loaded = false;
                note("正在重新启动引擎…");
                new Thread(MainActivity.this::boot, "boot-retry").start();
            });
        }
    }

    /** 把 WebView 触发的下载落到 App 私有 Download 目录（不需要存储权限）。 */
    /** 主 WebView 的行为：本地页面自己渲染，外部链接交给系统浏览器，新窗口单独接住。 */
    private void wireBrowser(final FrameLayout root) {
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                boolean crashed = detail != null && detail.didCrash();
                Life.note(MainActivity.this, "WebView 渲染进程结束（didCrash=" + crashed + "），准备重建页面");
                try {
                    android.view.ViewGroup parent = (android.view.ViewGroup) view.getParent();
                    if (parent != null) parent.removeView(view);
                    view.destroy();
                } catch (Throwable ignored) { }
                if (web == view) web = null;
                ui.postDelayed(() -> {
                    try { recreate(); } catch (Throwable e) { Life.note(MainActivity.this, "重建页面失败：" + e); }
                }, 250);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest req) {
                String u = req.getUrl().toString();
                Life.note(MainActivity.this, "主页面导航 " + u);
                if (Utils.isLocal(u)) return false;
                if (Utils.looksLikeApk(u)) {
                    Life.note(MainActivity.this, "页面链接是 APK，走 App 内下载");
                    startDownload(u, null, null);
                    return true;
                }
                openExternal(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                Life.note(MainActivity.this, "页面加载完成 " + url);
                // 页面已经画好了：这时才让 WebView 可见。开屏仍完全不透明（760ms 的淡出还没开始），
                // 所以首次合成照旧发生在幕布下；之后再等 520ms 才真正交接。
                if (v.getVisibility() != android.view.View.VISIBLE) {
                    v.setVisibility(android.view.View.VISIBLE);
                }
                hideSplash(520);
                hideReconnect();
                injectProbe(v, url);
                v.postDelayed(() -> selfReport("boot"), 8000);
                final String ev = engineVersion();
                v.postDelayed(() -> {
                    WebView target = web;
                    if (target != null) target.evaluateJavascript(Probe.engineLabelJs(ev), null);
                }, 900);
                v.postDelayed(() -> {
                    if (epAutoChecked) return;
                    epAutoChecked = true;
                    new Thread(() -> { epRefresh(); epPush(); pushShellState(); }, "ep-auto").start();
                }, 6000);
            }

            @Override
            public void onReceivedError(WebView v, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (req == null || !req.isForMainFrame()) return;
                String d = String.valueOf(err.getDescription());
                Life.note(MainActivity.this, "页面加载失败 " + req.getUrl() + " " + d);
                toast("页面加载失败：" + d);
            }
        });

        // DSH 的「登录」是在点击处理里同步 window.open("about:blank") 拿到窗口句柄、
        // 之后再往里导航到 device 授权页的。壳不实现 onCreateWindow 的话这个窗口根本不存在，
        // 点了登录就只剩一个黑屏 —— 这就是 0.7.1 里那个现象。
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                Life.note(MainActivity.this, "页面请求新窗口 isDialog=" + isDialog);
                final WebView w = new WebView(MainActivity.this);
                WebSettings ps = w.getSettings();
                ps.setJavaScriptEnabled(true);
                ps.setDomStorageEnabled(true);
                ps.setDatabaseEnabled(true);
                ps.setSupportZoom(false);
                ps.setSupportMultipleWindows(true);
                ps.setJavaScriptCanOpenWindowsAutomatically(true);
                w.setBackgroundColor(Color.parseColor("#0f1115"));
                w.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest req) {
                        String u = req.getUrl().toString();
                        Life.note(MainActivity.this, "新窗口导航 " + u);
                        if (u.startsWith("about:") || Utils.isLocal(u)) return false;
                        if (isAuthUrl(u)) {
                            // 登录/授权必须留在同一个 WebView，否则 cookie 留不下来
                            Life.note(MainActivity.this, "授权页保留在应用内：" + u);
                            return false;
                        }
                        if (Utils.looksLikeApk(u)) {
                            startDownload(u, null, null);
                            closePopup();
                            return true;
                        }
                        // 授权页放系统浏览器里完成（那里有系统账号，也不用我们在壳里造表单）
                        openExternal(u);
                        closePopup();
                        return true;
                    }

                    @Override
                    public void onPageFinished(WebView v, String url) {
                        Life.note(MainActivity.this, "新窗口加载完成 " + url);

                        if (isAuthUrl(url)) {
                            v.evaluateJavascript(
                                    "(()=>{try{return (document.body&&document.body.innerText)||''}catch(e){return ''}})()",
                                    t -> {
                                        String txt = t == null ? "" : t;
                                        if (txt.contains("授权已完成") || txt.contains("登录成功")) {
                                            Life.note(MainActivity.this, "检测到授权完成页，自动关闭并重载");
                                            ui.postDelayed(() -> closePopup(), 900);
                                        }
                                    });
                        }
                    }

                        @Override
                        public void onReceivedError(WebView v, android.webkit.WebResourceRequest req,
                                                    android.webkit.WebResourceError err) {
                            try {
                                String bad = req == null ? "" : req.getUrl().toString();
                                if (bad.isEmpty() || bad.startsWith("about:")) return;
                                Life.note(MainActivity.this, "授权页加载失败 " + bad);
                                if (isAuthUrl(bad)) {
                                    openExternal(bad);
                                    closePopup();
                                    toast("授权页在应用内打不开，已转交系统浏览器；登录后请回到应用");
                                }
                            } catch (Throwable ignored) { }
                        }
                });
                popup = w;
                root.addView(w, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                // 登录成功后页面会自己 popup.close()；不接 onCloseWindow 的话这个全屏 WebView
                // 会一直盖在最上层 —— 又是一块去不掉的黑屏。
                // （同一个页面在授权页被转交系统浏览器后也会 close，所以两条路都覆盖了。）
                android.webkit.WebView.WebViewTransport t =
                        (android.webkit.WebView.WebViewTransport) resultMsg.obj;
                t.setWebView(w);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                try {
                    if (m != null && (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR
                            || m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING)) {
                        Life.note(MainActivity.this, "页面控制台[" + m.messageLevel() + "] " + m.message()
                                + " @" + m.sourceId() + ":" + m.lineNumber());
                    }
                } catch (Throwable ignored) { }
                return true;
            }

            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                try {
                    boolean needAudio = false, needVideo = false;
                    for (String r : request.getResources()) {
                        if (r == null) continue;
                        if (r.startsWith("android.webkit.resource.AUDIO_CAPTURE")) needAudio = true;
                        if (r.startsWith("android.webkit.resource.VIDEO_CAPTURE")) needVideo = true;
                    }
                    Life.note(MainActivity.this, "页面请求媒体权限 audio=" + needAudio + " video=" + needVideo);
                    java.util.List<String> missing = new java.util.ArrayList<>();
                    if (needAudio && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        missing.add(android.Manifest.permission.RECORD_AUDIO);
                    }
                    if (needVideo && checkSelfPermission(android.Manifest.permission.CAMERA)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        missing.add(android.Manifest.permission.CAMERA);
                    }
                    if (missing.isEmpty()) { request.grant(request.getResources()); return; }
                    if (pendingPermissionRequest != null) {
                        try { pendingPermissionRequest.deny(); } catch (Throwable ignored) { }
                    }
                    pendingPermissionRequest = request;
                    requestPermissions(missing.toArray(new String[0]), REQ_MEDIA_PERM);
                } catch (Throwable e) {
                    Life.note(MainActivity.this, "处理媒体权限失败：" + e);
                    try { request.deny(); } catch (Throwable ignored) { }
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             android.webkit.ValueCallback<android.net.Uri[]> callback,
                                             android.webkit.WebChromeClient.FileChooserParams params) {
                Life.note(MainActivity.this, "页面请求选择文件");
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                    fileChooserCallback = null;
                }
                fileChooserCallback = callback;
                try {
                    android.content.Intent i = params != null ? params.createIntent() : null;
                    if (i == null) throw new IllegalStateException("createIntent 返回空");
                    i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                    startActivityForResult(i, REQ_FILE_CHOOSER);
                    return true;
                } catch (Throwable e) {
                    Life.note(MainActivity.this, "文件选择 intent 失败，回退通用选择器：" + e);
                    try {
                        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        startActivityForResult(i, REQ_FILE_CHOOSER);
                        return true;
                    } catch (Throwable e2) {
                        Life.note(MainActivity.this, "没有可用的文件选择器：" + e2);
                        fileChooserCallback = null;
                        return false;
                    }
                }
            }

            @Override
            public void onCloseWindow(WebView window) {
                Life.note(MainActivity.this, "页面关闭新窗口");
                if (window != popup) return;
                closePopup();
            }
        });
    }

    /** 页面 → 壳 的单向日志桥，只用来查问题（不回传任何用户数据）。 */
    // ── 原生「引擎」面板的状态 ───────────────────────────────────────────
    private volatile boolean epBusy;
    private volatile String epUrl = null, epTar = null, epSha = null, epBundle = null;
    /** 每次进程启动只静默查一次，用来给左下角胶囊上色（绿=最新 / 橙=可更新）。 */
    private volatile boolean epAutoChecked;
    private final java.util.ArrayDeque<String> epLog = new java.util.ArrayDeque<>();
    private volatile String epMsg = "";

    public class Bridge {
        @android.webkit.JavascriptInterface
        public void log(String msg) {
            Life.note(MainActivity.this, "JS " + msg);
        }

        /**
         * 页面里点「下载更新」时直接调这里。
         * 走导航的话会被 shouldOverrideUrlLoading 当成外部链接丢给浏览器（用户看到的就是「点了没反应」）。
         */
        /**
         * 页面量好鱼标位置后回调这里。单位是 CSS px（在 WebView 里 1 CSS px ≈ 1dp）。
         * 交接动画的落点用它，而不是用我拍脑袋估的 (28,30)。
         */
        @android.webkit.JavascriptInterface
        public void anchor(float cx, float cy, float size) {
            anchorCx = cx;
            anchorCy = cy;
            anchorSize = size;
            Life.note(MainActivity.this, "JS anchor=" + Math.round(cx) + "," + Math.round(cy)
                    + " size=" + Math.round(size));
        }

        @android.webkit.JavascriptInterface
        public void download(String url) {
            if (url == null || url.isEmpty()) return;
            Life.note(MainActivity.this, "JS 请求下载 " + url);
            startDownload(url, null, null);
        }

        /** 面板里点「现在更新」：安装之前下载好的外壳包。 */
        @android.webkit.JavascriptInterface
        public void installPending() {
            final java.io.File f = pendingFile();
            if (f == null) {
                showToast("没有已下载的更新包");
                dlProgress("", 0, 0, "");
                return;
            }
            Life.note(MainActivity.this, "JS 确认安装已下载更新 " + f.getName());
            ui.post(() -> installApk(f));
        }

        @android.webkit.JavascriptInterface
        public boolean hasPendingApk() {
            return pendingFile() != null;
        }

        @android.webkit.JavascriptInterface
        public void toast(String msg) {
            if (msg != null) showToast(msg);
        }

        /** 页面点「应用 ▾ → 检查更新」：由 Java 直接查清单，不再依赖引擎里的插件接口。 */
        @android.webkit.JavascriptInterface
        public void check() {
            new Thread(MainActivity.this::pushUpdateState, "check-release").start();
        }

        /** 面板打开：把当前状态推给网页。 */
        @android.webkit.JavascriptInterface
        public void epOpen() {
            Life.note(MainActivity.this, "JS 打开引擎面板");
            new Thread(MainActivity.this::epRefresh, "ep-open").start();
        }

        /** 检查更新：npm latest + 本地/镜像的预构建包清单。 */
        @android.webkit.JavascriptInterface
        public void epCheck() {
            epMsg = "正在查询 npm 与预构建包…";
            epPct = -1;
            epStage = "";
            epPush();
            new Thread(() -> {
                epRefresh();
                epPush();
            }, "ep-check").start();
        }

        /** 下载并更新引擎：走现有的 tar 包换装流程（校验 → 解包 → 冒烟 → 切换 → 重启）。 */
        @android.webkit.JavascriptInterface
        public void epUpdate() {
            final String url = epUrl;
            final String ver = epBundle;
            if (url == null || ver == null) { epMsg = "没有可用的预构建包"; epPush(); return; }
            new Thread(() -> {
                epBusy = true;
                updating = true;
                epPct = 0;
                epStage = "准备";
                epLine("开始更新引擎 → " + ver);
                Rootfs.ensureHelperScripts(MainActivity.this);
                epPush();
                try {
                    java.io.File dir = new java.io.File(getFilesDir(), "updates");
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("建目录失败");
                    java.io.File out = new java.io.File(dir, "engine-" + ver + ".tar.gz");
                    epStage = "下载引擎包";
                    downloadTo(url, out);
                    epPct = 32;
                    epStage = "校验并解包";
                    epPct = 36;
                    epPush();
                    boolean ok = Engine.applyEnginePackage(MainActivity.this, out, ver);
                    if (!ok) {
                        String why = Engine.lastError();
                        epLine("更新失败：换装脚本没跑起来（" + (why == null ? "无更多信息" : why) + "）");
                        uploadEngineUpdateEvidence();
                    } else {
                        epLine("换装脚本返回成功，开始换装…");
                        long deadline = System.currentTimeMillis() + 180000;
                        while (System.currentTimeMillis() < deadline) {
                            epSyncProgress();
                            epPush();
                            if (epStage.startsWith("完成")) break;
                            boolean alive = new java.io.File(Rootfs.dir(MainActivity.this),
                                    "home/试玩/.dsh-updating").isFile();
                            if (!alive && epPct >= 90) break;
                            try { Thread.sleep(700); } catch (InterruptedException ignored) { }
                        }
                        epSyncProgress();
                        epStage = "重启引擎…";
                        epPct = 100;
                        epPush();
                        // 等脚本把引擎停掉再重启：只在这里做一次"受控重连"，期间看门狗不插手
                        long downBy = System.currentTimeMillis() + 30000;
                        while (System.currentTimeMillis() < downBy && Engine.isUp()) {
                            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                        }
                        long upBy = System.currentTimeMillis() + 150000;
                        while (System.currentTimeMillis() < upBy && !Engine.isUp()) {
                            epStage = "引擎启动中…";
                            epPush();
                            try { Thread.sleep(800); } catch (InterruptedException ignored) { }
                        }
                        epStage = "完成，重新连接界面";
                        epMsg = "已更新到 " + ver;
                        epLine("引擎已更新 → " + (engineVersion() == null ? ver : engineVersion()));
                        epPush();
                        ui.post(() -> {
                            closePanels();
                            recoverReload();
                        });
                    }
                    if (!ok) epMsg = "更新失败，原引擎已保留";
                } catch (Throwable e) {
                    epLine("更新失败：" + e);
                    epMsg = "更新失败：" + e;
                } finally {
                    epBusy = false;
                    updating = false;
                    epPct = -1;
                    epStage = "";
                    epPush();
                }
            }, "ep-update").start();
        }

        /** 打补丁：给当前引擎补调度器守卫（幂等）。 */
        @android.webkit.JavascriptInterface
        public void epPatch() {
            new Thread(() -> {
                epBusy = true;
                epPush();
                String r = EngineGuard.apply(Rootfs.dir(MainActivity.this));
                epLine("调度器守卫补丁：" + r);
                epMsg = r;
                epBusy = false;
                epPush();
            }, "ep-patch").start();
        }

        /**
         * 面板里点「从 npm 构建」：在 App 自带前缀里 npm ci + 覆盖补丁 + 冒烟 + 打 tar，
         * 再交给现成的换装脚本。整条链失败都不会动现网引擎。
         */
        @android.webkit.JavascriptInterface
        public void epBuild() {
            final String ver = epNpm;
            if (ver == null || ver.isEmpty()) { epMsg = "还没查到 npm 最新版本"; epPush(); return; }
            new Thread(() -> {
                epBusy = true;
                updating = true;
                epPct = 1;
                epStage = "准备构建";
                epMsg = "";
                epLine("开始从 npm 构建引擎 " + ver);
                try {
                    Rootfs.ensureEngineBuildScript(MainActivity.this);
                    String a = Rootfs.ensureEngineBuildAssets(MainActivity.this, ver);
                    boolean ready = !a.startsWith("引擎构建资源：安装包里没有") && !a.startsWith("引擎构建资源：失败");
                    if (!ready) {
                        ready = Rootfs.hasPortedEngineBuild(Rootfs.dir(MainActivity.this), ver);
                    }
                    if (!ready) {
                        // 安装包里没有目标版本的补丁集：从最近一个旧版本自动迁移
                        String base = Rootfs.latestEngineBuildBase(MainActivity.this, Rootfs.dir(MainActivity.this), ver);
                        if (base == null) {
                            epLine("构建失败：既没有 " + ver + " 的补丁集，也没有可迁移的旧补丁集");
                            epMsg = "没有可用的补丁集";
                            return;
                        }
                        epLine("没有 " + ver + " 的补丁集，从 " + base + " 自动迁移");
                        epStage = "迁移补丁集";
                        epPct = 2;
                        epPush();
                        String b = Rootfs.ensureEngineBuildAssets(MainActivity.this, base);
                        if (b.startsWith("引擎构建资源：失败") || b.startsWith("引擎构建资源：安装包里没有")) {
                            epLine("迁移失败：" + b);
                            epMsg = "拿不到旧补丁集";
                            return;
                        }
                        Rootfs.ensureEnginePortScript(MainActivity.this);
                        java.io.File baseDir = Rootfs.engineBuildDir(Rootfs.dir(MainActivity.this), base);
                        java.io.File outDir = Rootfs.engineBuildDir(Rootfs.dir(MainActivity.this), ver);
                        if (!Engine.portPatches(MainActivity.this, baseDir, ver, outDir)) {
                            String why = Engine.lastError();
                            epLine("补丁迁移失败：" + (why == null ? "（无更多信息）" : why));
                            epMsg = "补丁迁移失败，现网引擎未动";
                            return;
                        }
                        epLine("补丁集已迁移到 " + ver);
                    }
                    File dir = new File(getFilesDir(), "updates");
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("建目录失败");
                    File out = new File(dir, "engine-" + ver + ".tar.gz");
                    epStage = "npm 安装与打包";
                    epPct = 3;
                    epPush();
                    boolean ok = Engine.buildFromNpm(MainActivity.this, ver, out);
                    if (!ok) {
                        String why = Engine.lastError();
                        epLine("构建失败：" + (why == null ? "（无更多信息）" : why));
                        epMsg = "构建失败，现网引擎未动";
                        return;
                    }
                    epLine("引擎包构建完成（" + (out.length() / (1024 * 1024)) + " MB）");
                    epPct = 75;
                    epStage = "换装";
                    epPush();
                    if (!Engine.applyEnginePackage(MainActivity.this, out, ver)) {
                        epLine("更新失败：换装脚本没跑起来");
                        epMsg = "换装未启动";
                        return;
                    }
                    long deadline = System.currentTimeMillis() + 240000;
                    while (System.currentTimeMillis() < deadline) {
                        epSyncProgress();
                        epPush();
                        if (epStage != null && epStage.startsWith("完成")) break;
                        boolean alive = new java.io.File(Rootfs.dir(MainActivity.this),
                                "home/试玩/.dsh-updating").isFile();
                        if (!alive && epPct >= 90) break;
                        try { Thread.sleep(700); } catch (InterruptedException ignored) { }
                    }
                    epSyncProgress();
                    epStage = "重启引擎…";
                    epPct = 100;
                    epPush();
                    long downBy = System.currentTimeMillis() + 30000;
                    while (System.currentTimeMillis() < downBy && Engine.isUp()) {
                        try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                    }
                    long upBy = System.currentTimeMillis() + 180000;
                    while (System.currentTimeMillis() < upBy && !Engine.isUp()) {
                        epStage = "引擎启动中…";
                        epPush();
                        try { Thread.sleep(800); } catch (InterruptedException ignored) { }
                    }
                    epStage = "完成，重新连接界面";
                    epMsg = "已从 npm 构建并更新到 " + ver;
                    epLine("引擎已更新 → " + (engineVersion() == null ? ver : engineVersion()) + "（从 npm 构建）");
                    epPush();
                    ui.post(() -> {
                        closePanels();
                        recoverReload();
                    });
                } catch (Throwable e) {
                    epLine("构建失败：" + e);
                    epMsg = "构建失败：" + e;
                } finally {
                    epBusy = false;
                    updating = false;
                    epPct = -1;
                    epStage = "";
                    epPush();
                }
            }, "ep-build").start();
        }

        /** 面板里点「重启引擎」：停掉当前进程 → 重新拉起 → 带新 token 重载页面。 */
        @android.webkit.JavascriptInterface
        public void restartEngine() {
            Life.note(MainActivity.this, "JS 请求重启引擎");
            new Thread(() -> {
                try {
                    Engine.stop();
                    String t = Engine.ensure(MainActivity.this, msg -> note(msg));
                    long dl = System.currentTimeMillis() + 60000;
                    while (!Engine.httpReady() && System.currentTimeMillis() < dl) {
                        try { Thread.sleep(300); } catch (InterruptedException e) { return; }
                    }
                    if (t == null) t = Engine.token();
                    final String tf = t;
                    if (tf == null) {
                        Life.note(MainActivity.this, "重启引擎后仍没拿到 token");
                        epLine("重启引擎失败：没拿到 token");
                        return;
                    }
                    epLine("引擎已重启 · 版本 " + (engineVersion() == null ? "未知" : engineVersion()));
                    final String u = "http://127.0.0.1:" + Engine.port() + "/?token=" + tf;
                    ui.post(() -> {
                        WebView w = web;
                        if (w != null) { Life.note(MainActivity.this, "重启引擎完成，重新加载界面"); w.loadUrl(u); }
                    });
                } catch (Throwable e) {
                    Life.note(MainActivity.this, "重启引擎失败：" + e);
                }
            }, "restart-engine").start();
        }

        /**
         * 「更新引擎」走这里：Java 先下载引擎包（与 APK 下载同一条网络路），下完再交给脚本换目录。
         * rootfs 里的 node fetch 到 GitHub 资产会超时，所以不让脚本自己下载。
         */
        /** 外壳胶囊点开时查一次：只查外壳清单，推给页面里的外壳面板。 */
        @android.webkit.JavascriptInterface
        public void shellCheck() {
            new Thread(MainActivity.this::pushShellState, "shell-check").start();
        }

        @android.webkit.JavascriptInterface
        public void engineUpdate(String tarUrl, String version) {
            if (tarUrl == null || tarUrl.isEmpty() || version == null || version.isEmpty()) return;
            Life.note(MainActivity.this, "JS 请求更新引擎 " + version + " <- " + tarUrl);
            downloadEngine(tarUrl, version);
        }
    }

    /**
     * 在本地页面里装一个探针：把 window.open 的调用与结果（拿到句柄还是 null）写进日志。
     * 登录流程就靠 window.open 开新窗口，黑屏时得知道它到底有没有被调用。
     */
    /** 当前 versionName；探针用它做"每个版本只报一次"的键。 */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "0";
        }
    }

    /**
     * 用 token 换 cookie，再问一次「更新接口」在不在。返回 HTTP 码，异常 -1。
     * 404 = 这个引擎进程没加载 dsh-app-release 宿主插件（多半是升级前的老进程）。
     */
    /**
     * 查两边的版本并推给页面：外壳看 shell/latest.json，引擎看 engine/latest.json。
     * 三个来源按顺序试：本机 8099 → jsDelivr → raw.githubusercontent（GitHub 主域在我们这儿不可达）。
     */
    /**
     * 取外壳更新清单，按「新鲜度」排序：
     * 本机镜像 8098 → 本机 8099 → GitHub Releases API（无缓存）→ jsDelivr（带缓存击穿）→ raw（带缓存击穿）。
     *
     * <p>API 优先于 CDN 的原因：CDN 的 {@code @main} 有小时级缓存，发版后读到的还是旧版本。
     */
    private String shellManifest(String repo) {
        String j = Utils.fetchText("http://127.0.0.1:8098/shell-latest.json", 2500);
        if (j != null) return j;
        j = Utils.fetchText("http://127.0.0.1:8099/shell-latest.json", 4000);
        if (j != null) return j;
        j = shellFromApi(repo);
        if (j != null) return j;
        j = Utils.fetchFresh("https://gcore.jsdelivr.net/gh/" + repo + "@main/shell/latest.json", 7000);
        if (j != null) return j;
        return Utils.fetchFresh("https://raw.githubusercontent.com/" + repo + "/main/shell/latest.json", 7000);
    }

    /** 从 GitHub Releases API 合成一份与 shell/latest.json 等价的清单。 */
    private String shellFromApi(String repo) {
        String txt = Utils.fetchFresh("https://api.github.com/repos/" + repo + "/releases/latest", 7000);
        if (txt == null) return null;
        try {
            org.json.JSONObject r = new org.json.JSONObject(txt);
            String tag = r.optString("tag_name", "");
            if (tag.startsWith("v")) tag = tag.substring(1);
            if (tag.isEmpty()) return null;
            org.json.JSONArray assets = r.optJSONArray("assets");
            String apkUrl = null, shaUrl = null;
            long size = 0;
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    org.json.JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String n = a.optString("name", "");
                    if (n.endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", null);
                        size = a.optLong("size", 0);
                    } else if (n.endsWith(".apk.sha256")) {
                        shaUrl = a.optString("browser_download_url", null);
                    }
                }
            }
            if (apkUrl == null) return null;
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("version", tag);
            o.put("apkUrl", apkUrl);
            if (shaUrl != null) o.put("sha256Url", shaUrl);
            o.put("size", size);
            o.put("source", "github-api");
            return o.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 从 GitHub Releases API 找最新一个 {@code engine-v*} 发布，合成引擎更新清单。
     *
     * <p>为什么需要：CDN 的 {@code @main} 有小时级缓存且忽略查询参数，发版后引擎清单会一直是旧值。
     */
    private String engineFromApi(String repo) {
        String txt = Utils.fetchFresh("https://api.github.com/repos/" + repo + "/releases?per_page=20", 8000);
        if (txt == null) return null;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(txt);
            String bestVer = null, bestTar = null, bestSha = null;
            long bestSize = 0;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                String tag = r.optString("tag_name", "");
                if (!tag.startsWith("engine-v")) continue;
                String ver = tag.substring("engine-v".length());
                String tar = null, shaUrl = null;
                long size = 0;
                org.json.JSONArray as = r.optJSONArray("assets");
                for (int k = 0; as != null && k < as.length(); k++) {
                    org.json.JSONObject a = as.optJSONObject(k);
                    if (a == null) continue;
                    String n = a.optString("name", "");
                    if (n.endsWith(".tar.gz")) {
                        tar = a.optString("browser_download_url", null);
                        size = a.optLong("size", 0);
                    } else if (n.endsWith(".tar.gz.sha256")) {
                        shaUrl = a.optString("browser_download_url", null);
                    }
                }
                if (tar == null) continue;
                if (bestVer == null || Utils.cmpVersion(ver, bestVer) > 0) {
                    bestVer = ver;
                    bestTar = tar;
                    bestSize = size;
                    bestSha = null;
                    if (shaUrl != null) {
                        String body = Utils.fetchFresh(shaUrl, 6000);
                        if (body != null) {
                            String first = body.trim().split("\\s+")[0];
                            if (first.matches("[0-9a-f]{64}")) bestSha = first;
                        }
                    }
                }
            }
            if (bestVer == null) return null;
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("version", bestVer);
            o.put("tar", bestTar);
            o.put("size", bestSize);
            if (bestSha != null) o.put("sha256", bestSha);
            o.put("source", "github-api");
            return o.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 取引擎更新清单：本机镜像 → 本机 8099 → CDN/raw（都带缓存击穿）。 */
    private String engineManifest(String repo) {
        String j = Utils.fetchText("http://127.0.0.1:8098/engine-latest.json", 2500);
        if (j != null) return j;
        j = Utils.fetchText("http://127.0.0.1:8099/engine-latest.json", 4000);
        if (j != null) return j;
        // CDN 的 @main 有小时级缓存（且忽略查询参数），所以优先问 GitHub API
        j = engineFromApi(repo);
        if (j != null) return j;
        j = Utils.fetchFresh("https://gcore.jsdelivr.net/gh/" + repo + "@main/engine/latest.json", 7000);
        if (j != null) return j;
        return Utils.fetchFresh("https://raw.githubusercontent.com/" + repo + "/main/engine/latest.json", 7000);
    }

    private void pushShellState() {
        final String repo = "endorphin4/dsh-harness";
        String shellJson = shellManifest(repo);
        String cur = versionName();
        String latest = null, apkUrl = null, note = "";
        if (shellJson == null) note = "取不到外壳清单";
        try {
            if (shellJson != null) {
                org.json.JSONObject o = new org.json.JSONObject(shellJson);
                latest = o.optString("version", null);
                apkUrl = mirrorUrlIfPresent(o.optString("apkUrl", null));
            }
        } catch (Throwable e) { note = "外壳清单解析失败"; }
        final android.content.SharedPreferences sp = getSharedPreferences("dshb", MODE_PRIVATE);
        if (latest != null) {
            sp.edit().putString("shell_latest", latest)
                    .putString("shell_url", apkUrl == null ? "" : apkUrl)
                    .putString("shell_time", new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                            .format(new java.util.Date()))
                    .apply();
        } else {
            // 本机 8099 / jsDelivr / raw 都不通时，退回上次成功的清单，别让面板只剩一个破折号。
            String cached = sp.getString("shell_latest", null);
            if (cached != null) {
                latest = cached;
                String u = sp.getString("shell_url", "");
                if (apkUrl == null && u != null && !u.isEmpty()) apkUrl = u;
                note = "更新服务暂时不可达 · 上次检查 " + sp.getString("shell_time", "?");
            } else {
                note = "取不到外壳清单：本机更新服务没响应";
            }
        }
        boolean newer = latest != null && Utils.cmpVersion(latest, cur) > 0;
        try {
            org.json.JSONObject st = new org.json.JSONObject();
            st.put("cur", cur);
            st.put("latest", latest == null ? org.json.JSONObject.NULL : latest);
            st.put("url", apkUrl == null ? org.json.JSONObject.NULL : apkUrl);
            st.put("newer", newer);
            st.put("note", note);
            java.io.File pf = pendingFile();
            st.put("pending", pf != null);
            st.put("pendingName", pf == null ? org.json.JSONObject.NULL : pf.getName());
            final String js = "window.__dshbShellSet&&window.__dshbShellSet(" + st + ")";
            Life.note(this, "外壳面板 当前 " + cur + " → 最新 " + latest + (newer ? "（可更新）" : ""));
            ui.post(() -> {
                WebView w = web;
                if (w != null) w.evaluateJavascript(js, null);
            });
        } catch (Throwable e) {
            Life.note(this, "组装外壳状态失败：" + e);
        }
    }

    private void pushUpdateState() {
        final String repo = "endorphin4/dsh-harness";
        String shellJson = shellManifest(repo);
        String engineJson = engineManifest(repo);

        String appCur = versionName();
        String appLatest = null, apkUrl = null;
        String engineCur = engineVersion();
        String engineLatest = null, tarUrl = null;
        String note = "";
        try {
            if (shellJson != null) {
                org.json.JSONObject o = new org.json.JSONObject(shellJson);
                appLatest = o.optString("version", null);
                apkUrl = o.optString("apkUrl", null);
            }
        } catch (Throwable e) { note = note + "外壳清单解析失败；"; }
        try {
            if (engineJson != null) {
                org.json.JSONObject o = new org.json.JSONObject(engineJson);
                engineLatest = o.optString("version", null);
                tarUrl = o.optString("tar", null);
            }
        } catch (Throwable e) { note = note + "引擎清单解析失败；"; }
        if (shellJson == null) note = note + "取不到外壳清单；";
        if (engineJson == null) note = note + "取不到引擎清单；";

        final String finalEngineCur = engineCur;
        runOnUiThread(() -> {
            WebView target = web;
            if (target != null) target.evaluateJavascript(Probe.engineLabelJs(finalEngineCur), null);
        });

        boolean appUp = appLatest != null && Utils.cmpVersion(appLatest, appCur) > 0;
        boolean engineUp = engineLatest != null && Utils.cmpVersion(engineLatest, engineCur) > 0;

        try {
            org.json.JSONObject st = new org.json.JSONObject();
            st.put("app", appCur);
            st.put("appLatest", appLatest == null ? org.json.JSONObject.NULL : appLatest);
            st.put("engine", engineCur);
            st.put("engineLatest", engineLatest == null ? org.json.JSONObject.NULL : engineLatest);
            st.put("appUpdate", appUp);
            st.put("engineUpdate", engineUp);
            st.put("apkUrl", apkUrl == null ? org.json.JSONObject.NULL : apkUrl);
            st.put("tarUrl", tarUrl == null ? org.json.JSONObject.NULL : tarUrl);
            st.put("note", note);
            final String js = "window.__dshbUpdate&&window.__dshbUpdate(" + st + ")";
            Life.note(this, "检查更新：外壳 " + appCur + "→" + appLatest + "，引擎 " + engineCur + "→" + engineLatest);
            ui.post(() -> {
                WebView w = web;
                if (w != null) w.evaluateJavascript(js, null);
            });
        } catch (Throwable e) {
            Life.note(this, "组装更新状态失败：" + e);
        }
    }

    /** 读当前引擎版本（载荷里 dsh/package.json 的 version）。 */
    private String engineVersion() {
        try {
            java.io.File f = new java.io.File(getFilesDir(),
                    "usr/lib/node_modules/@deepseek-ai/dsh/package.json");
            String txt = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(txt);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) { }
        return "未知";
    }

    /**
     * 记录一下「页面 HTML 里到底有没有我们的客户端 bundle」——
     * 插件 UI 不出现时，先分清是没被收集（HTML 里没这串）还是加载失败（有串但没执行）。
     */
    /**
     * 应用升级后，上一版留下的引擎进程仍在跑 —— 它的插件图/profile 是旧状态，
     * 继续复用它就会出现「profile 明明修好了、页面里还是没有插件」。
     * 这里用 files/.app-version 记录上次运行的版本，一变就主动停掉引擎重新拉起。
     */
    private void restartEngineIfUpgraded() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), ".app-version");
            String now = versionName();
            try {
                now = now + "(" + getPackageManager().getPackageInfo(getPackageName(), 0).versionCode + ")";
            } catch (Throwable ignored) { }
            String prev = f.isFile() ? new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim() : "";
            if (!now.equals(prev)) {
                Life.note(this, "应用版本变化 " + (prev.isEmpty() ? "（首次）" : prev) + " → " + now + "，重启引擎以加载新 profile");
                Engine.stop();
                try { java.nio.file.Files.write(f.toPath(), now.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                catch (Throwable ignored) { }
            }
        } catch (Throwable e) {
            Life.note(this, "版本变化检查失败：" + e);
        }
    }

    /**
     * 自己截个图、把自己页面的 HTML 交上来。
     *
     * 我（Termux 里的 agent）看不到这个 App 的屏幕：没有 root、没有 adb、也读不到它的私有目录。
     * 所以让 App 自己把「看到的」上传到本机 8099 —— 这样排 UI 问题就不用再靠你描述或截图。
     */
    /** profile 目录状态：用来定位「profile 什么时候消失」。 */
    private String profileState() {
        try {
            java.io.File web = new java.io.File(getFilesDir(), "home/.dsh/profiles/web");
            java.io.File pkg = new java.io.File(web, "package.json");
            java.io.File patch = new java.io.File(web, "cordis.patch.yml");
            java.io.File nm = new java.io.File(web, "node_modules");
            String[] list = nm.list();
            return "profile[web=" + (web.isDirectory() ? "有" : "无")
                    + " pkg=" + (pkg.isFile() ? pkg.length() + "B" : "无")
                    + " patch=" + (patch.isFile() ? "有" : "无")
                    + " nm=" + (list == null ? 0 : list.length) + "]";
        } catch (Throwable e) {
            return "profile[err " + e + "]";
        }
    }

    // ── 原生「引擎」面板的辅助逻辑 ───────────────────────────────────────
    /** 记一条正式事件（更新到哪个版本、何时重启、成败），面板「最近事件」与磁盘各留一份。 */
    private void epLine(String line) {
        String ts = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date());
        String ev = ts + "  " + line;
        synchronized (epLog) {
            epLog.addLast(ev);
            while (epLog.size() > 8) epLog.removeFirst();
        }
        Life.note(this, "引擎事件 " + line);
        try {
            File f = new File(Rootfs.dir(this), "ep-events.log");
            java.nio.file.Files.write(f.toPath(), (ev + "\n").getBytes("UTF-8"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            java.util.List<String> all = java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            if (all.size() > 64) {                       // 只留最近 64 条，别让文件长成日志
                StringBuilder sb = new StringBuilder();
                for (int i = all.size() - 64; i < all.size(); i++) sb.append(all.get(i)).append('\n');
                java.nio.file.Files.write(f.toPath(), sb.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 进程重启后把上次留下的事件读回来，这样「上次更新到哪、什么时候重启过」跨重启也看得到。 */
    private void epLoadEvents() {
        try {
            File f = new File(Rootfs.dir(this), "ep-events.log");
            if (!f.isFile()) return;
            java.util.List<String> all = java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            synchronized (epLog) {
                epLog.clear();
                for (int i = Math.max(0, all.size() - 8); i < all.size(); i++) {
                    String l = all.get(i).trim();
                    if (!l.isEmpty()) epLog.addLast(l);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 读换装脚本写的 state 文件（pct / label），用来驱动面板里的进度条。 */
    private void epSyncProgress() {
        try {
            java.io.File st = new java.io.File(Rootfs.dir(this), "home/试玩/.dsh-update-state.json");
            if (!st.isFile()) return;
            String txt = new String(java.nio.file.Files.readAllBytes(st.toPath()), "UTF-8");
            org.json.JSONObject o = new org.json.JSONObject(txt);
            int pct = o.optInt("pct", -1);
            String label = o.optString("label", "");
            boolean done = o.optBoolean("done", false);
            if (pct >= 0) {
                epPct = Math.max(epPct, Math.min(100, pct));
                if (label.length() > 0) epStage = label;
            }
            if (done) epStage = "完成，准备重启引擎";
        } catch (Throwable ignored) {
        }
    }

    private void epPush() {
        final StringBuilder log = new StringBuilder();
        synchronized (epLog) {
            for (String l : epLog) {
                if (log.length() > 0) log.append("\n");
                log.append(Utils.jsStr(l).replace("\"", ""));      // 已转义，下面再用 JSONObject 包一次
            }
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("cur", engineVersion() == null ? "未知" : engineVersion());
            o.put("npm", epNpm == null ? "" : epNpm);
            o.put("bundle", epBundle == null ? "无" : epBundle);
            o.put("url", epUrl == null ? "" : epUrl);
            o.put("busy", epBusy);
            o.put("msg", epMsg == null ? "" : epMsg);
            o.put("pct", epPct);
            o.put("stage", epStage == null ? "" : epStage);
            o.put("canBuild", Rootfs.canBuildEngine(this, Rootfs.dir(this), epNpm));
            o.put("portNeeded", epNpm != null && !Rootfs.hasEngineBuildAssets(this, epNpm));
            org.json.JSONArray arr = new org.json.JSONArray();
            synchronized (epLog) {
                for (String l : epLog) arr.put(l);
            }
            o.put("log", arr);
            final String js = "window.__dshbEP&&window.__dshbEP(" + o.toString() + ")";
            runOnUiThread(() -> {
                if (web != null) web.evaluateJavascript(js, null);
            });
        } catch (Throwable e) {
            Life.note(this, "引擎面板推送失败：" + e);
        }
    }

    /** 刷新：npm latest + 预构建包清单（本地 8099 → jsDelivr → raw）。 */
    private void epRefresh() {
        epBusy = true;
        epPush();
        try {
            String npm = null;
            String txt = Utils.fetchText("https://registry.npmjs.org/@deepseek-ai/dsh/latest", 8000);
            if (txt != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(txt);
                if (m.find()) npm = m.group(1);
            }
            epNpm = npm;
            if (npm == null) epLine("检查更新：npm 版本取不到（网络不可达）");

            String[] urls = {
                    "http://127.0.0.1:8098/engine-latest.json",
                    "http://127.0.0.1:8099/engine-latest.json",
                    "https://gcore.jsdelivr.net/gh/endorphin4/dsh-harness@main/engine/latest.json",
                    "https://fastly.jsdelivr.net/gh/endorphin4/dsh-harness@main/engine/latest.json",
                    "https://raw.githubusercontent.com/endorphin4/dsh-harness/main/engine/latest.json"
            };
            String found = null, via = null;
            for (String u : urls) {
                String body = Utils.fetchText(u, 8000);
                if (body == null || !body.contains("version")) continue;
                found = body;
                via = u;
                break;
            }
            if (found == null) {
                epUrl = null;
                epBundle = null;
                epLine("检查更新：预构建包清单四个来源都拿不到");
                epMsg = "拿不到预构建包";
            } else {
                org.json.JSONObject o = new org.json.JSONObject(found);
                epBundle = o.optString("version", null);
                String tar = o.optString("tar", "");
                epTar = tar;
                epSha = o.optString("sha256", null);
                epUrl = tar.isEmpty() ? null : mirrorUrlIfPresent(tar);
                String cur = engineVersion();
                if (epBundle != null && cur != null && Utils.cmpVersion(epBundle, cur) > 0) {
                    epLine("发现可用的预构建包 " + epBundle);
                }
                epMsg = "已就绪";
            }
        } catch (Throwable e) {
            epLine("检查更新失败：" + e);
            epMsg = "刷新失败：" + e;
        } finally {
            epBusy = false;
            epPush();
        }
    }

    /** 把换装脚本自己的日志与 state 文件回传到 8099，失败时用来看卡在哪一步。 */
    private void uploadEngineUpdateEvidence() {
        try {
            File root = Rootfs.dir(this);
            File log = new File(root, "home/试玩/dsh-engine-update.log");
            File state = new File(root, "home/试玩/.dsh-update-state.json");
            String ts = new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
            if (log.isFile()) {
                byte[] b = java.nio.file.Files.readAllBytes(log.toPath());
                boolean ok = Diag.uploadBytes("engine-update-" + ts + ".log", b);
                epLine("换装日志 " + b.length + "B 上传=" + (ok ? "成功" : "失败"));
            } else {
                epLine("换装脚本没有写日志（可能根本没跑起来）");
            }
            if (state.isFile()) {
                byte[] b = java.nio.file.Files.readAllBytes(state.toPath());
                Diag.uploadBytes("engine-state-" + ts + ".json", b);
            }
        } catch (Throwable e) {
            epLine("回传失败：" + e);
        }
    }

    /** 简单下载（带进度日志），给引擎面板用。 */
    private void downloadTo(String url, java.io.File out) throws Exception {
        java.net.URLConnection c = new java.net.URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        if (c instanceof java.net.HttpURLConnection) ((java.net.HttpURLConnection) c).setInstanceFollowRedirects(true);
        java.io.File part = new java.io.File(out.getPath() + ".part");
        try (java.io.InputStream in = c.getInputStream();
             java.io.FileOutputStream fos = new java.io.FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0, last = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                done += n;
                if (done - last > 20 * 1024 * 1024) {
                    last = done;
                    epLine("已下载 " + (done / (1024 * 1024)) + " MB");
                    epPush();
                }
            }
        }
        if (!part.renameTo(out)) throw new java.io.IOException("改名失败");
    }

    private volatile String epNpm = null;

    private void selfReport(final String tag) {
        final WebView w = web;
        if (w == null || w.getWidth() <= 0) return;
        // DOM：整页 HTML + 正文文本，用来判断侧栏里到底有什么
        w.evaluateJavascript("(function(){try{var h=document.documentElement.outerHTML||'';"
                + "var t=(document.body&&document.body.innerText||'').replace(/\\s+/g,' ');"
                + "return h.length+'\\u0001'+t.slice(0,1200)+'\\u0001'+h.slice(0,200000);}catch(e){return 'ERR '+e;}})()",
                value -> {
                    try {
                        String text = value == null ? "" : value;
                        // evaluateJavascript 会给 JSON 字符串加引号并转义
                        String cleaned = text;
                        if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                            cleaned = cleaned.substring(1, cleaned.length() - 1)
                                    .replace("\\u0001", "\n@@@\n").replace("\\n", "\n").replace("\\\"", "\"");
                        }
                        final byte[] bytes = cleaned.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        final int len = cleaned.length();
                        final boolean hasBrowser = cleaned.contains("浏览器");
                        final String name = "dom-" + tag + "-" + stamp() + ".txt";
                        new Thread(() -> {
                            boolean ok = Diag.uploadBytes(name, bytes);
                            Life.note(MainActivity.this, "自报 DOM " + len + " 字，含「浏览器」字样="
                                    + (hasBrowser ? "是" : "否") + "，上传=" + (ok ? "成功" : "失败"));
                        }, "selfreport-dom").start();
                    } catch (Throwable e) {
                        Life.note(MainActivity.this, "自报 DOM 失败：" + e);
                    }
                });
        // 截图：把整个窗口画进 Bitmap 再传上来
        try {
            android.view.View root = getWindow().getDecorView();
            final int wd = root.getWidth(), ht = root.getHeight();
            if (wd <= 0 || ht <= 0) return;
            final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(wd, ht,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(getWindow(), new android.graphics.Rect(0, 0, wd, ht), bmp,
                    result -> {
                        if (result != android.view.PixelCopy.SUCCESS) {
                            Life.note(MainActivity.this, "自报截图失败：PixelCopy=" + result);
                            return;
                        }
                        try {
                            final int maxW = 900;
                            int sw = wd, sh = ht;
                            if (wd > maxW) { sw = maxW; sh = Math.max(1, ht * maxW / wd); }
                            final android.graphics.Bitmap small = (sw == wd && sh == ht) ? bmp
                                    : android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true);
                            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                            small.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, bos);
                            final byte[] png = bos.toByteArray();
                            final int fw = sw, fh = sh;
                            final String name = "shot-" + tag + "-" + stamp() + ".png";
                            new Thread(() -> {
                                boolean ok = Diag.uploadBytes(name, png);
                                Life.note(MainActivity.this, "自报截图 " + fw + "x" + fh + " " + (png.length / 1024)
                                        + " KB，上传=" + (ok ? "成功" : "失败"));
                            }, "selfreport-shot").start();
                        } catch (Throwable e) {
                            Life.note(MainActivity.this, "自报截图编码失败：" + e);
                        }
                    }, new android.os.Handler(getMainLooper()));
        } catch (Throwable e) {
            Life.note(this, "自报截图异常：" + e);
        }
    }

    private String stamp() {
        return new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
    }

    private void logBundlePresence() {
        try {
            java.net.CookieHandler.setDefault(new java.net.CookieManager());
            String token = Engine.token();
            int port = Engine.port();
            if (token == null) return;
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:" + port + "/?token=" + token).openConnection();
            c.setConnectTimeout(1500); c.setReadTimeout(3000); c.setInstanceFollowRedirects(true);
            c.getResponseCode();
            java.net.HttpURLConnection h = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:" + port + "/").openConnection();
            h.setConnectTimeout(1500); h.setReadTimeout(3000);
            StringBuilder sb = new StringBuilder();
            try (java.io.InputStream in = h.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && sb.length() < 400000) sb.append(new String(buf, 0, n, "UTF-8"));
            }
            String html = sb.toString();
            boolean has = html.contains("dsh-app-release/client.js");
            boolean browser = html.contains("client-ui-sidebar-browser/client.js");
            Life.note(this, "页面 HTML " + html.length() + " 字节，app-release bundle="
                    + (has ? "是" : "否") + "，侧栏浏览器 bundle=" + (browser ? "是" : "否"));
        } catch (Throwable e) {
            Life.note(this, "检查页面 HTML 失败：" + e);
        }
    }

    private int probeReleaseApi() {
        try {
            java.net.CookieHandler.setDefault(new java.net.CookieManager());
            int port = Engine.port();
            String token = Engine.token();
            if (token == null) return -1;
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:" + port + "/?token=" + token).openConnection();
            c.setConnectTimeout(1500); c.setReadTimeout(2500); c.setInstanceFollowRedirects(false);
            c.getResponseCode();
            java.net.HttpURLConnection a = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:" + port + "/api/dsh-app-release?action=status").openConnection();
            a.setConnectTimeout(1500); a.setReadTimeout(2500);
            int code = a.getResponseCode();
            java.io.InputStream in = code >= 400 ? a.getErrorStream() : a.getInputStream();
            if (in != null) { try (java.io.InputStream x = in) { while (x.read() != -1) { } } }
            return code;
        } catch (Throwable e) {
            Life.note(this, "探更新接口失败：" + e);
            return -1;
        }
    }

    private void injectProbe(WebView v, String url) {
        if (!url.startsWith("http://127.0.0.1")) return;
        v.evaluateJavascript(EnginePanel.js(), null);
        v.evaluateJavascript(Probe.js(versionName()), null);
    }

    /**
     * 把外壳版本写到 files/app-version.txt，供载荷里的 dsh-app-release 插件读取
     * （插件查 GitHub Releases 时需要知道「现在装的是哪一版」）。
     */
    private void writeAppVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            int code = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            java.io.File f = new java.io.File(getFilesDir(), "app-version.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, false)) {
                w.write(version + " (" + code + ")\n");
            }
        } catch (Throwable t) {
            Life.note(this, "写 app-version.txt 失败：" + t);
        }
    }

    /**
     * 是否是登录/授权地址。
     *
     * <p>这类页面必须留在 WebView 里完成：cookie 存在 WebView 的 CookieManager 里，
     * 一旦甩到系统浏览器，授权成功后 cookie 落在浏览器那边，App 永远显示未登录
     * （2026-11-23 实测：授权页提示成功，返回 App 仍要求登录）。
     */
    private static boolean isAuthUrl(String u) {
        if (u == null) return false;
        String s = u.toLowerCase();
        if (s.contains("deepseek.com")) return true;
        return s.contains("/oauth") || s.contains("/authorize") || s.contains("/sign_in")
                || s.contains("/signin") || s.contains("/login") || s.contains("/sso");
    }

    /** 下载链接是不是一个 APK（GitHub release 资产就是 .../<name>.apk）。 */
    
    
    private void openExternal(String u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Life.note(this, "转交系统浏览器 " + u);
        } catch (Throwable e) {
            Life.note(this, "打不开外部链接：" + u + " " + e);
            toast("这个链接系统里没有能打开的 App：" + u);
        }
    }

    private void closePopup() {
        WebView w = popup;
        popup = null;
        if (w == null) return;
        if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
        try {
            String cookies = android.webkit.CookieManager.getInstance()
                    .getCookie("http://127.0.0.1:" + Engine.port() + "/");
            Life.note(this, "关闭新窗口，cookie=" + (cookies == null ? "无" : (cookies.split(";").length + " 项")));
        } catch (Throwable ignored) { }
        w.destroy();
        Life.note(this, "关闭新窗口");
        // 授权页关闭 = 登录流程走完，主页面重载一次才会显示已登录
        // 只做 reload：cookie 已经落在同一个 WebView 里，再拼 ?token= 会让前端重新初始化一遍，
        // 反而触发「页面出现了一些异常」（2026-11-23 录屏实测）。
        ui.postDelayed(() -> {
            try {
                if (web != null) {
                    web.reload();
                    Life.note(this, "授权后重载主页面（reload，保留 cookie）");
                }
            } catch (Throwable e) {
                Life.note(this, "授权后重载失败：" + e);
            }
        }, 600);
    }

    private void toast(String msg) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把下载进度推给页面里的 JS（{@code window.__dshDl}），面板据此画进度条。
     * 之前是每 4 MB 弹一个 toast —— 又吵又不精确，0.10.4 换成实时进度条。
     */
    private void dlProgress(String name, long done, long total, String state) {
        final WebView w = web;
        if (w == null) return;
        final String args = Utils.jsStr(name) + "," + done + "," + total + "," + Utils.jsStr(state);
        final String js = "window.__dshbDl&&window.__dshbDl(" + args + ");"
                + "window.__dshDl&&window.__dshDl(" + args + ")";
        ui.post(() -> {
            WebView v = web;
            if (v != null) v.evaluateJavascript(js, null);
        });
    }

    
    /**
     * 把 `github.com/<o>/<r>/releases/download/<tag>/<asset>` 解析成**能真正下到字节**的直链。
     *
     * 背景：这台设备上 `github.com:443` 时通时不通（2026-11-06 多次 20s 连接超时），
     * 但 `api.github.com` 与 `objects.githubusercontent.com` 一直可达。所以绕开 github.com：
     * 先用 API 查 asset id，再请求 /releases/assets/<id> 拿 302 的 Location（签名直链），
     * 那个域名就是 CDN，直接下。
     */
    
    /**
     * 下载引擎包到 files/updates/。走的是与外层 APK 相同的 HttpURLConnection 链路：
     * 设备网络下 node fetch 到 objects.githubusercontent.com 会超时，而这条一直是通的。
     */
    private void downloadEngine(final String url, final String version) {
        final String tag = "引擎包 " + version;
        synchronized (this) {
            if (downloadingEngine != null) {
                Life.note(this, "引擎包已在下载（" + downloadingEngine + "），忽略重复请求 " + version);
                return;
            }
            downloadingEngine = version;
        }
        new Thread(() -> {
            File dir = new File(getFilesDir(), "updates");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File out = new File(dir, "engine-" + version + ".tar.gz");
            File part = new File(dir, "engine-" + version + ".part");
            java.net.HttpURLConnection conn = null;
            try {
                // github.com 时通时不通：先解析成 objects.githubusercontent.com 的签名直链
                Exception last = null;
                java.io.InputStream in = null;
                for (String attempt : Utils.downloadAttempts(this, url)) {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(attempt).openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(120000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DSHShell");
                    try {
                        int code = c.getResponseCode();
                        if (code >= 400) throw new java.io.IOException("HTTP " + code);
                        in = c.getInputStream();
                        conn = c;
                        break;
                    } catch (Exception e) {
                        last = e;
                        Life.note(this, "引擎包下载重试：" + e);
                        c.disconnect();
                    }
                }
                if (in == null) throw last != null ? last : new java.io.IOException("下载失败");
                final long expect = Math.max(0L, conn.getContentLengthLong());
                dlProgress(tag, 0, expect, "start");
                long total = 0;
                try (java.io.InputStream src = in;
                     java.io.OutputStream os = new java.io.BufferedOutputStream(
                             new java.io.FileOutputStream(part), 256 * 1024)) {
                    byte[] buf = new byte[256 * 1024];
                    long lastTick = 0;
                    int n;
                    while ((n = src.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastTick >= 150) {
                            lastTick = now;
                            dlProgress(tag, total, expect, "downloading");
                        }
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                part.renameTo(out);
                Life.note(this, "引擎包下载完成 " + out + " " + out.length() + " B");
                dlProgress(tag, out.length(), out.length(), "done");
                boolean ok = Engine.applyEnginePackage(this, out, version);
                if (!ok) {
                    Life.note(this, "引擎更新脚本启动失败：" + Engine.lastError());
                    dlProgress(tag, 0, 0, "error");
                    ui.post(() -> showToast("引擎包下载好了，但更新脚本起不来：" + Engine.lastError()));
                } else {
                    ui.post(() -> showToast("引擎包已就绪，正在换引擎…"));
                }
            } catch (Throwable e) {
                Life.note(this, "引擎包下载失败 " + e);
                dlProgress(tag, 0, 0, "error");
                ui.post(() -> showToast("引擎包下载失败：" + e));
            } finally {
                if (conn != null) conn.disconnect();
                downloadingEngine = null;
            }
        }, "engine-download").start();
    }

    /**
     * 给 GitHub release 资产准备一串下载地址，按成功率排序：
     * ① api.github.com 换出来的 release-assets.githubusercontent.com 签名直链（最快）
     * ② 原始 github.com 地址（时通时不通）
     * ③ gh-proxy.com 镜像前缀（实测 206，能下但慢）
     * ④ ghproxy.net 镜像前缀
     */
    
    /** 清单里的下载地址，若本机镜像目录已有同名文件，就改走 App 自己的 8098。 */
    private String mirrorUrlIfPresent(String url) {
        if (url == null || url.isEmpty()) return url;
        String base = url.substring(url.lastIndexOf('/') + 1);
        if (base.isEmpty() || base.contains("?")) return url;
        java.io.File f = new java.io.File(Mirror.dir(this), base);
        return f.isFile() ? "http://127.0.0.1:8098/" + base : url;
    }

    /** 当前可用的待安装包：优先内存，其次上次会话落在 prefs 里的路径。 */
    private java.io.File pendingFile() {
        java.io.File f = pendingApk;
        if (f == null) {
            String p = getSharedPreferences("dshb", MODE_PRIVATE).getString(PREF_PENDING_APK, null);
            if (p != null) f = new java.io.File(p);
        }
        return (f != null && f.isFile() && f.length() > 0) ? f : null;
    }

    /** 装机成功后版本号会变：此时上一版留下的待安装包已无意义，清掉预记录。 */
    private void dropStalePending() {
        android.content.SharedPreferences sp = getSharedPreferences("dshb", MODE_PRIVATE);
        String base = sp.getString("pending_base", null);
        String cur = versionName();
        if (base != null && !base.equals(cur)) {
            sp.edit().remove(PREF_PENDING_APK).remove("pending_base").apply();
            pendingApk = null;
            Life.note(this, "待安装包已过期（" + base + " → " + cur + "），已清除");
        }
    }

    private void startDownload(final String url, final String contentDisposition, final String mime) {
        final String name = Utils.guessName(url, contentDisposition);
        Life.note(this, "下载开始 " + name + " mime=" + mime + " <- " + url);
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            showToast("这个地址没法下载：" + url);
            return;
        }
        synchronized (this) {
            if (downloadingUrl != null) {
                Life.note(this, "已有下载在进行（" + downloadingUrl + "），忽略重复请求：" + url);
                return;
            }
            downloadingUrl = url;
        }
        new Thread(() -> {
            final boolean isApk = name.toLowerCase().endsWith(".apk");
            File dir;
            if (isApk) {
                // 安装器要读这个文件：存到缓存目录，由 ApkProvider 以 content:// 提供，
                // 避免 Android 7+ 的 FileUriExposedException。
                dir = new java.io.File(getCacheDir(), "updates");
            } else {
                File base = getExternalFilesDir(null);
                dir = new File(base != null ? base : getFilesDir(), "Download");
            }
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File out = new File(dir, name);
            File part = new File(dir, name + ".part");
            java.net.HttpURLConnection conn = null;
            try {
                // github.com 时通时不通：先尝试换成 release-assets.githubusercontent.com 的签名直链，
                // 直链失败再退回原地址（两次都试），别让一次连接超时就判死刑。
                java.io.InputStream in0 = null;
                Throwable lastErr = null;
                for (String attempt : Utils.downloadAttempts(this, url)) {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(attempt).openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(120000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DSHShell");
                    try {
                        int code = c.getResponseCode();
                        if (code >= 400) throw new java.io.IOException("HTTP " + code);
                        conn = c;
                        in0 = c.getInputStream();
                        break;
                    } catch (Throwable e) {
                        lastErr = e;
                        Life.note(this, "下载重试（" + name + "）：" + e);
                        c.disconnect();
                    }
                }
                if (in0 == null) throw lastErr != null ? lastErr : new java.io.IOException("下载失败");
                final long expect = Math.max(0L, conn.getContentLengthLong());
                dlProgress(name, 0, expect, "start");
                long total = 0;
                try (java.io.InputStream in = in0;
                     java.io.OutputStream os = new java.io.BufferedOutputStream(
                             new java.io.FileOutputStream(part), 256 * 1024)) {
                    byte[] buf = new byte[256 * 1024];
                    long lastTick = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                        // 进度实时推给页面（浮动卡片里的进度条），节流到 ~120ms，别把主线程刷爆
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastTick >= 120) {
                            lastTick = now;
                            dlProgress(name, total, expect, "downloading");
                        }
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                part.renameTo(out);
                Life.note(this, "下载完成 " + out + " " + out.length() + " B");
                if (isApk) {
                    pendingApk = out;
                    getSharedPreferences("dshb", MODE_PRIVATE).edit()
                            .putString(PREF_PENDING_APK, out.getAbsolutePath())
                            .putString("pending_base", versionName()).apply();
                    dlProgress(name, out.length(), out.length(), "ready");
                    Life.note(this, "外壳包已下载，等用户确认安装 " + out.getName());
                    ui.post(() -> showToast("资源下载完成，是否现在更新？点左下角胶囊确认"));
                } else {
                    dlProgress(name, out.length(), out.length(), "done");
                    ui.post(() -> showToast("下载完成：" + out.getAbsolutePath()));
                }
            } catch (Throwable e) {
                Life.note(this, "下载失败 " + e);
                dlProgress(name, 0, 0, "error");
                ui.post(() -> showToast("下载失败：" + e));
            } finally {
                if (conn != null) conn.disconnect();
                downloadingUrl = null;
            }
        }, "download").start();
    }

    private void installApk(File apk) {
        Life.note(this, "准备安装 " + apk.getName() + " " + apk.length() + " B");
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                showToast("请允许「安装未知应用」后再装");
                try {
                    android.content.Intent settings = new android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:" + getPackageName()));
                    settings.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(settings);
                } catch (Throwable ignored) {
                    // 有的 ROM 没有这个页面，只能靠用户自己去设置里开
                }
                return;
            }
            android.net.Uri uri = android.net.Uri.parse(
                    "content://" + getPackageName() + ".apk/" + apk.getName());
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            showToast("已交给系统安装器");
        } catch (Throwable e) {
            Life.note(this, "安装失败 " + e);
            showToast("安装失败：" + e);
        }
    }

    private java.io.InputStream open(String url) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DSHShell");
        return c.getInputStream();
    }

    /** 从 Content-Disposition 或 URL 猜文件名（中文名做一次 URL 解码）。 */
    
    private void showToast(String msg) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 界面没了就只留日志
        }
    }

    private void note(String msg) {
        Log.i(TAG, msg);
        final String pretty = Utils.prettyNote(msg);
        ui.post(() -> {
            Splash s = splash;
            if (s != null) s.setStatus(pretty);
        });
    }

    /**
     * 开屏文案统一成一句短的话：不带括号、不带实现细节。
     *
     * <p>以前会把 "正在拉起引擎（/data/.../usr/bin/node --port 3082）" 这种内部信息直接显示给用户，
     * 既长又像调试输出。
     */
    
    /**
     * 页面画出来了（或要显示错误页）就交接给界面。
     *
     * <p>不是简单淡出：开屏里的鱼标会"接力"飞到它在应用侧栏里的落点（手机永远是 56px 窄轨，
     * 鱼标中心约 (28dp, 30dp)），同时开屏背景先化掉、页面内容从下方 10dp 淡入上浮。
     * 全程约 520ms；超过 600ms 就会觉得在等。
     */
    private void hideSplash(long delay) {
        final Splash s = splash;
        if (s == null) return;
        ui.postDelayed(() -> {
            WebView w = web;
            if (w != null) {
                // 再等两帧：让页面把"首次绘制 + 合成"做完再开交接。
                // 动画第一帧如果和 WebView 首绘撞在一起，就是肉眼可见的一顿。
                w.postOnAnimation(() -> s.postOnAnimation(() -> playHandoff(s)));
            } else {
                playHandoff(s);
            }
        }, delay);
    }

    private void playHandoff(final Splash s) {
        if (splash != s) return;
        splash = null; // 防重入
        final FrameLayout h = host;
        final android.widget.ImageView fishView = flyer;
        final android.view.View glowView = glow;

        // 页面在交接前就已经在开屏下面画好了（WebView 一直是 VISIBLE），
        // 所以这里**不碰 WebView**：不改 alpha、不改 translation、不改可见性。
        // 任何一帧对 WebView 的重合成都会掉帧，这里是上一版"卡一下"的根源。

        boolean flying = false;
        if (h != null && fishView != null && overlay != null) {
            float[] geo = s.fishCenter(h); // 必须在 beginHandoff 之前取（之后开屏里的鱼会藏起来）
            float size = geo[2] > 0 ? geo[2] : dp(FISH_DP);
            float startCx = geo[0];
            float startCy = geo[1];
            // 落点：优先用页面实测的鱼标位置（CSS px ≈ dp，乘密度换成 px）；
            // 量不到才退回老的估算值 —— 这就是上一版"飞到偏了"的地方。
            final float density = getResources().getDisplayMetrics().density;
            final float targetCx = anchorCx > 0f ? anchorCx * density : dp(28);
            final float targetCy = anchorCy > 0f ? anchorCy * density : dp(30);
            final float aimSize = anchorSize > 0f ? anchorSize * density : dp(24);
            float endScale = aimSize / size;

            // 视图是启动时按 FISH_DP 布局在 overlay 左上角的，这里只算"从布局位置出发要偏移多少"。
            float laidCx = dp(FISH_DP) / 2f;
            float laidCy = dp(FISH_DP) / 2f;
            float baseX = startCx - laidCx;
            float baseY = startCy - laidCy;

            fishView.setImageBitmap(s.fishBitmap());
            fishView.setPivotX(dp(FISH_DP) / 2f);
            fishView.setPivotY(dp(FISH_DP) / 2f);
            fishView.setTranslationX(baseX);
            fishView.setTranslationY(baseY);
            fishView.setScaleX(1f);
            fishView.setScaleY(1f);
            fishView.setRotation(0f);
            fishView.setAlpha(1f);
            fishView.setVisibility(android.view.View.VISIBLE);
            fishView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);

            // 辉光也预先布局在 overlay 里，只用 translation 定位
            if (glowView != null) {
                float g = dp(150) / 2f;
                glowView.setTranslationX(startCx - g);
                glowView.setTranslationY(startCy - g);
                glowView.setScaleX(0.45f);
                glowView.setScaleY(0.45f);
                glowView.setAlpha(0.8f);
                glowView.setVisibility(android.view.View.VISIBLE);
                glowView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
                glowView.animate()
                        .alpha(0f).scaleX(1.7f).scaleY(1.7f)
                        .setStartDelay(30).setDuration(620)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
                        .withEndAction(() -> {
                            glowView.setVisibility(android.view.View.INVISIBLE);
                            glowView.setLayerType(android.view.View.LAYER_TYPE_NONE, null);
                        })
                        .start();
            }

            // 二次贝塞尔：控制点沿位移法线鼓出 0.18×距离，直线看起来像"被吸走"
            final float dx = targetCx - startCx;
            final float dy = targetCy - startCy;
            float dist = (float) Math.hypot(dx, dy);
            if (dist < 1f) dist = 1f;
            final float bend = dist * 0.18f;
            final float cxp = startCx + dx / 2f + (-dy / dist) * bend;
            final float cyp = startCy + dy / 2f + (dx / dist) * bend;

            // 位移与缩放绑在同一个进度上：位置走多远，鱼就缩多小。
            // 曲线缓入缓出（不是 emphasized-decelerate 那种前端极快），820ms 全程可见地在缩。
            final float fStartCx = startCx;
            final float fStartCy = startCy;
            android.animation.ValueAnimator fly = android.animation.ValueAnimator.ofFloat(0f, 1f);
            fly.setStartDelay(30);
            fly.setDuration(820);
            fly.setInterpolator(new android.view.animation.PathInterpolator(0.42f, 0f, 0.18f, 1f));
            fly.addUpdateListener(a -> {
                float t = (Float) a.getAnimatedValue();
                float u = 1f - t;
                float x = u * u * fStartCx + 2f * u * t * cxp + t * t * targetCx;
                float y = u * u * fStartCy + 2f * u * t * cyp + t * t * targetCy;
                fishView.setTranslationX(x - laidCx);
                fishView.setTranslationY(y - laidCy);
                float sc = 1f + (endScale - 1f) * t;
                fishView.setScaleX(sc);
                fishView.setScaleY(sc);
                fishView.setRotation(-8f * (1f - t));
            });
            fly.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    // 落点：先轻轻一涨，再缩一点被"吸进"侧栏，最后淡掉
                    fishView.animate().scaleX(endScale * 1.08f).scaleY(endScale * 1.08f)
                            .setDuration(90)
                            .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                            .withEndAction(() -> fishView.animate()
                                    .scaleX(endScale * 0.9f).scaleY(endScale * 0.9f).alpha(0f)
                                    .setDuration(180)
                                    .setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 1f, 1f))
                                    .withEndAction(() -> {
                                        fishView.setVisibility(android.view.View.INVISIBLE);
                                        fishView.setLayerType(android.view.View.LAYER_TYPE_NONE, null);
                                    })
                                    .start())
                            .start();
                }
            });
            fly.start();
            flying = true;
        }

        // 开屏分层退场：进度条 → 状态 → 标题 → 背景（细节见 Splash.beginHandoff）
        s.beginHandoff();

        // 开屏 860ms 后撤；鱼在 overlay 里自己飞完自己藏起来
        final boolean hasFlyer = flying;
        ui.postDelayed(() -> {
            if (h != null) h.removeView(s);
            s.stop();
            if (hasFlyer) {
                // 兜底：万一动画被系统掐了，别留一只鱼挂在那儿
                ui.postDelayed(() -> {
                    if (fishView != null && fishView.getVisibility() == android.view.View.VISIBLE) {
                        fishView.setVisibility(android.view.View.INVISIBLE);
                        fishView.setLayerType(android.view.View.LAYER_TYPE_NONE, null);
                    }
                }, 900);
            }
        }, 860);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 运行时权限结果：把媒体权限的结论回传给页面。 */
    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] granted) {
        if (req == REQ_MEDIA_PERM) {
            android.webkit.PermissionRequest pr = pendingPermissionRequest;
            pendingPermissionRequest = null;
            if (pr == null) return;
            boolean all = granted != null && granted.length > 0;
            for (int g : granted) if (g != android.content.pm.PackageManager.PERMISSION_GRANTED) all = false;
            try {
                if (all) pr.grant(pr.getResources()); else pr.deny();
            } catch (Throwable e) { Life.note(this, "回传媒体权限失败：" + e); }
            Life.note(this, "媒体权限结果 granted=" + all);
            return;
        }
        super.onRequestPermissionsResult(req, perms, granted);
    }

    /** 文件选择结果回传给 WebView（多选回传数组）。 */
    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        if (req == REQ_FILE_CHOOSER) {
            android.webkit.ValueCallback<android.net.Uri[]> cb = fileChooserCallback;
            fileChooserCallback = null;
            if (cb == null) { super.onActivityResult(req, res, data); return; }
            android.net.Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                android.content.ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    out = new android.net.Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) out[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    out = new android.net.Uri[]{data.getData()};
                }
            }
            Life.note(this, "文件选择返回 res=" + res + " 选中 " + (out == null ? 0 : out.length) + " 个");
            cb.onReceiveValue(out);
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    protected void onResume() {
        pageVisible = true;
        super.onResume();
        Life.note(this, profileState() + " onResume");
        // 从后台回来：引擎可能已被系统收掉。探一次，死了就地拉起（不再出现死页面）。
        // 注意：探活是 TCP/HTTP，**绝不能**在主线程做（NetworkOnMainThreadException 会直接崩溃回弹）。
        Life.note(this, "onResume loaded=" + loaded);
        if (!loaded) return;
        new Thread(() -> {
            boolean up = Engine.isUp();
            Life.note(this, "onResume 探活 engineUp=" + up);
            if (up) return;
            ui.post(() -> {
                loaded = false;
                note("引擎没在跑，正在重新拉起…");
                startService(new Intent(this, EngineService.class));
                new Thread(this::boot, "reboot").start();
            });
        }, "probe").start();
    }

    @Override
    protected void onPause() {
        pageVisible = false;
        try {
            File r = Rootfs.dir(this);
            LoginKeeper.syncBackup(r);
            Life.note(this, LoginKeeper.state(r));
        } catch (Throwable ignored) { }
        super.onPause();
        Life.note(this, profileState() + " onPause");
        Life.note(this, "MainActivity.onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Life.note(this, "MainActivity.onStop");
    }

    @Override
    protected void onDestroy() {
        Life.note(this, "MainActivity.onDestroy");
        // 注意：**不**停引擎 —— 进程归前台服务，Activity 只是观众。
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
