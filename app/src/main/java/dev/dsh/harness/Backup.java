package dev.dsh.harness;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** home 的备份/恢复与整树删除：1.1.8 起改成「整目录 rename 备份 → 解包 → 拷回 KEEP」。 */
final class Backup {

    private static final String TAG = "DSH-B";

    private Backup() {}

    private static final String[] KEEP = {
            ".dsh/storages",          // 会话索引 + workspace.json + 各种 settings 落库
            ".dsh/sessions",          // session-persistence-jsonl 的正文
            ".dsh/settings.yaml",     // 用户在界面里改过的模型/主题
            ".dsh/.credentials.yaml", // 重新授权过的凭据比载荷里带的新
            "workspace",              // 默认工作区里的用户文件
            ".gitconfig",
            // 用户自己装的语言运行时/工具链的缓存与包目录（`dsh-pkg`、pip、npm、cargo、Go 都会往这里写）。
            // 这些不属于载荷，删了就得重装一遍——2026-11-09 丢的 .npm 缓存、.dsh-pkg 库、.fonts 就是它们。
            ".dsh-pkg",
            ".npm",
            ".cache",
            ".config",
            ".local",
            ".fonts",
            ".cargo",
            ".rustup",
            ".go",
            "go",
            ".ssh",
            ".gnupg",
    };

    static boolean preserveInto(File home, File keep) throws IOException {
        boolean any = false;
        for (String rel : KEEP) {
            File src = new File(home, rel);
            if (!src.exists()) continue;
            File dst = new File(keep, rel);
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("建暂存子目录失败：" + parent);
            }
            if (!moveOrCopy(src, dst)) {
                throw new IOException("暂存用户数据失败：" + rel + "（源 " + src + " 仍在，未删除 home）");
            }
            any = true;
        }
        return any;
    }

    static void restoreFrom(File keep, File home) throws IOException {
        for (String rel : KEEP) {
            File src = new File(keep, rel);
            if (!src.exists()) continue;
            File dst = new File(home, rel);
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("建恢复目录失败：" + parent);
            }
            if (!moveOrCopy(src, dst)) {
                Log.w(TAG, "恢复用户数据失败：" + rel + "（保留在 " + src + "）");
            }
        }
    }

    static boolean moveOrCopy(File src, File dst) {
        try {
            if (dst.exists() || java.nio.file.Files.isSymbolicLink(dst.toPath())) {
                File aside = new File(dst.getParentFile(), dst.getName() + ".stale-" + System.currentTimeMillis());
                java.nio.file.Files.move(dst.toPath(), aside.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                deleteTreeStrict(aside);
            }
            java.nio.file.Files.move(src.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception ignored) {
            // 落到复制路径
        }
        try {
            deleteTreeStrict(dst);
            copyTree(src, dst);
            deleteTreeStrict(src);
            return !src.exists();
        } catch (Exception e) {
            Log.w(TAG, "moveOrCopy 失败 " + src + " → " + dst + ": " + e);
            return false;
        }
    }

    static void copyTree(File src, File dst) throws IOException {
        try {
            if (java.nio.file.Files.isSymbolicLink(src.toPath())) {
                dst.getParentFile().mkdirs();
                if (dst.exists() || java.nio.file.Files.isSymbolicLink(dst.toPath())) dst.delete();
                java.nio.file.Files.createSymbolicLink(dst.toPath(),
                        java.nio.file.Files.readSymbolicLink(src.toPath()));
                return;
            }
        } catch (Exception e) {
            throw new IOException("复制软链失败 " + src, e);
        }
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("建目录失败：" + dst);
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
            return;
        }
        dst.getParentFile().mkdirs();
        java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    static int copyKeep(File fromHome, File toHome) {
        int copied = 0;
        for (String rel : KEEP) {
            File src = new File(fromHome, rel);
            if (!src.exists() && !java.nio.file.Files.isSymbolicLink(src.toPath())) continue;
            File dst = new File(toHome, rel);
            try {
                deleteTreeStrict(dst);
                copyTree(src, dst);
                copied++;
            } catch (Exception e) {
                Log.w(TAG, "恢复 " + rel + " 失败（备份仍在 " + src + "）：" + e);
            }
        }
        return copied;
    }

    static void pruneHomeBackups(File root, int keep) {
        File[] all = root.listFiles((d, n) -> n.startsWith("home.bak-"));
        if (all == null || all.length <= keep) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep; i < all.length; i++) {
            Log.w(TAG, "清理旧 home 备份：" + all[i].getName());
            deleteTreeStrict(all[i]);
        }
    }

    static void recoverLegacyKeep(File root, File home, Rootfs.Progress p) {
        File keep = new File(root, ".keep");
        if (!keep.isDirectory()) return;
        if (home.isDirectory()) {
            p.step("发现旧版暂存目录 .keep，按「缺什么补什么」合并…");
            int fixed = 0;
            for (String rel : KEEP) {
                File src = new File(keep, rel);
                File dst = new File(home, rel);
                if (!src.exists() || dst.exists()) continue;
                try {
                    copyTree(src, dst);
                    fixed++;
                } catch (Exception e) {
                    Log.w(TAG, "从 .keep 补 " + rel + " 失败: " + e);
                }
            }
            Log.w(TAG, "旧 .keep 合并补入 " + fixed + " 项");
        }
        deleteTreeStrict(keep);
    }

    static boolean deleteTreeStrict(File f) {
        deleteTree(f);
        if (!f.exists() && !java.nio.file.Files.isSymbolicLink(f.toPath())) return true;
        File[] kids = f.listFiles();
        Log.w(TAG, "删不掉：" + f + "（剩余 " + (kids == null ? -1 : kids.length) + " 项，可读=" + f.canRead()
                + " 可写=" + f.canWrite() + " 目录=" + f.isDirectory() + "）");
        return false;
    }

    static void deleteTree(File f) {
        if (f == null) return;
        // 软链只删链本身，绝不递归进目标 —— 递归穿链曾经把整个 usr/ 删掉过（2026-11-16）。
        try {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                java.nio.file.Files.deleteIfExists(f.toPath());
                return;
            }
        } catch (Throwable ignored) { }
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
