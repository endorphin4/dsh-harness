package dev.dsh.harness;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃取证：Android 17 上 logcat 只回放系统日志（无 AndroidRuntime/FATAL 行），
 * 所以「点图标直接回弹」这类崩溃在外面完全看不见。这里装一个兜底 handler：
 * 把栈写到 {@code files/crash.log}，再尽量 POST 到本机下载服务；
 * 下次启动时 {@link #flushPending()} 会把上次没送出去的崩溃补传。
 */
public class App extends Application {
    private static final String TAG = "DSH-B";

    @Override
    public void onCreate() {
        super.onCreate();
        Life.note(this, "Application.onCreate v" + name());

        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                String body = "version: " + name() + "\nthread: " + thread.getName()
                        + "\n\n" + Log.getStackTraceString(e);
                appendCrash(body);
                Diag.upload("crash-" + stamp() + ".log", body);
                Life.note(this, "CRASH " + e.getClass().getName() + ": " + e.getMessage());
            } catch (Throwable ignored) {
                // 崩溃处理里再崩就认了
            }
            if (prev != null) prev.uncaughtException(thread, e);
        });

        flushPending();
        immediateReport();
        startBeats();
    }

    /** 进程一起来就先报一声：这样即使几秒后就被杀，我们也有迹可循。 */
    private void immediateReport() {
        new Thread(() -> {
            try {
                Diag.upload("boot-" + stamp() + ".log", "app=alive\n" + Engine.statusLine() + "\n");
            } catch (Throwable ignored) {
                // 上报失败不影响启动
            }
        }, "immediate-report").start();
    }

    /**
     * 一条节拍线程管三件事：心跳、engine.log 增量回传、life.log 增量回传。
     *
     * <p>以前是三个线程各睡各的（60s / 45s / 45s），互相错峰唤醒；合并成一条 5 秒一拍的线程后，
     * 同一时刻只有一个 wakeup，日志回传仍然只在「长度变了」时才发。
     */
    private void startBeats() {
        new Thread(() -> {
            long born = System.currentTimeMillis();
            long nextHeart = born + 60000;
            long nextEngine = born + 45000;
            long nextLife = born + 45000;
            long lastEngineLen = -1;
            long lastLifeLen = -1;
            boolean downgradeLogged = false;
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                try {
                    if (now >= nextEngine) {
                        nextEngine = now + 45000;
                        File log = new File(getFilesDir(), "engine.log");
                        long len = log.isFile() ? log.length() : 0;
                        if (len != 0 && len != lastEngineLen) {
                            lastEngineLen = len;
                            Diag.upload("engine-live-" + stamp() + ".log", tail(log, 60));
                        }
                    }
                    if (now >= nextLife) {
                        nextLife = now + 45000;
                        File f = new File(getFilesDir(), "life.log");
                        long len = f.isFile() ? f.length() : 0;
                        if (len != 0 && len != lastLifeLen) {
                            lastLifeLen = len;
                            Diag.upload("life-live-" + stamp() + ".log", tail(f, 80));
                        }
                    }
                    if (now >= nextHeart) {
                        long age = now - born;
                        long gap = age < 600000 ? 60000 : 300000;
                        nextHeart = now + gap;
                        if (gap == 300000 && !downgradeLogged) {
                            downgradeLogged = true;
                            Life.note(this, "心跳间隔已降到 5 分钟（进程稳定运行）");
                        }
                        Diag.upload("alive-" + stamp() + ".log", "app=alive\n" + Engine.statusLine() + "\n");
                    }
                } catch (Throwable ignored) {
                    // 单次上报失败不影响下一拍
                }
            }
        }, "beats").start();
    }

    /** 读文件末尾若干行（够看栈就够了）。 */
    private String tail(File f, int lines) {
        try {
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            String s = new String(all, StandardCharsets.UTF_8);
            String[] arr = s.split("\n", -1);
            int from = Math.max(0, arr.length - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < arr.length; i++) sb.append(arr[i]).append('\n');
            return sb.toString();
        } catch (Throwable e) {
            return "# 读失败: " + e + "\n";
        }
    }

    /** 上次残留的 crash.log（多半是没送出去的那份）补传；life.log 顺手带一份，能看到生命周期。 */
    private void flushPending() {
        new Thread(() -> {
            try {
                File life = new File(getFilesDir(), "life.log");
                if (life.isFile() && life.length() > 0) {
                    String text = new String(java.nio.file.Files.readAllBytes(life.toPath()),
                            StandardCharsets.UTF_8);
                    Diag.upload("life-" + stamp() + ".log", text);
                }
            } catch (Throwable ignored) {
                // 下次再试
            }
        }, "life-flush").start();

        final File f = new File(getFilesDir(), "crash.log");
        if (!f.isFile()) return;
        new Thread(() -> {
            try {
                String text = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8);
                if (Diag.upload("crash-prev-" + stamp() + ".log", text)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } catch (Throwable ignored) {
                // 下次再试
            }
        }, "crash-flush").start();
    }

    private void appendCrash(String body) throws IOException {
        File f = new File(getFilesDir(), "crash.log");
        FileOutputStream fos = new FileOutputStream(f, true);
        try {
            fos.write((body + "\n----\n").getBytes(StandardCharsets.UTF_8));
        } finally {
            fos.close();
        }
    }

    private String stamp() {
        return new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date());
    }

    private String name() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return Build.VERSION_SNAPSHOT + " / " + pi.versionName + "(" + pi.versionCode + ")";
        } catch (Throwable e) {
            return Build.VERSION_SNAPSHOT;
        }
    }
}
