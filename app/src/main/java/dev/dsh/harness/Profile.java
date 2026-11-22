package dev.dsh.harness;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * profile 自检：DSH 的 web 端从 home/.dsh/profiles/web/package.json 的
 * dsh.profile.bundles 收集客户端 bundle。升级/修复过程中这个文件偶尔还是旧的，
 * 于是插件 UI 不渲染、页面 HTML 里也找不到我们的 client.js —— 这里补登记。
 */
final class Profile {

    private Profile() {}

    /**
     * 我们的客户端 bundle 现在是“空壳”（引擎版本标签改由 App 原生注入），而空壳返回的
     * { exports: {} } 不符合 DSH 的插件契约，会在网页上直接报
     * 「Failed to load plugins / invalid plugin, expect function or object with an "apply" method」。
     * 这里把这个 bundle 从插件包里摘掉：删掉 dsh.client、删掉 ./client 导出、删掉 client.js。
     * 幂等：已经是干净状态就直接返回。
     */
    static String disableBrokenClient(Context ctx) {
        File root = Rootfs.dir(ctx);
        File dir = new File(root, "home/.dsh/profiles/web/node_modules/dsh-app-release");
        File pkg = new File(dir, "package.json");
        File client = new File(dir, "lib/client.js");
        if (!pkg.isFile()) return "客户端 bundle：无插件包，跳过";
        boolean changed = false;
        try {
            String txt = new String(Files.readAllBytes(pkg.toPath()), StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(txt);
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            if (dsh != null && dsh.has("client")) {
                dsh.remove("client");
                o.put("dsh", dsh);
                changed = true;
            }
            org.json.JSONObject exports = o.optJSONObject("exports");
            if (exports != null && exports.has("./client")) {
                exports.remove("./client");
                o.put("exports", exports);
                changed = true;
            }
            if (changed) Files.write(pkg.toPath(), o.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            return "客户端 bundle：改写 package.json 失败 " + e;
        }
        if (client.isFile() && client.delete()) changed = true;
        return changed ? "客户端 bundle：已从 dsh-app-release 摘除（避免 Failed to load plugins）"
                : "客户端 bundle：已是干净状态";
    }

    static String ensureAppRelease(Context ctx) {
        File root = Rootfs.dir(ctx);
        File pkg = new File(root, "home/.dsh/profiles/web/package.json");
        File client = new File(root, "home/.dsh/profiles/web/node_modules/dsh-app-release/lib/client.js");
        StringBuilder sb = new StringBuilder();
        sb.append("profile: package.json=").append(pkg.isFile() ? "有" : "无");
        sb.append(" client.js=").append(client.isFile() ? client.length() + "B" : "无");
        try {
            if (!pkg.isFile()) {
                sb.append(" ⇒ 无 package.json，跳过");
                return sb.toString();
            }
            String txt = new String(Files.readAllBytes(pkg.toPath()), StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(txt);
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            org.json.JSONObject profile = dsh == null ? null : dsh.optJSONObject("profile");
            org.json.JSONArray bundles = profile == null ? null : profile.optJSONArray("bundles");
            boolean has = false;
            if (bundles != null) {
                for (int i = 0; i < bundles.length(); i++) {
                    if ("dsh-app-release".equals(bundles.optString(i))) { has = true; break; }
                }
            }
            sb.append(" bundles=").append(bundles == null ? "无" : String.valueOf(bundles.length()));
            sb.append(" 已登记=").append(has ? "是" : "否");
            if (!has) {
                if (profile == null) {
                    dsh = dsh == null ? new org.json.JSONObject() : dsh;
                    profile = new org.json.JSONObject();
                    dsh.put("profile", profile);
                    o.put("dsh", dsh);
                }
                if (bundles == null) {
                    bundles = new org.json.JSONArray();
                    profile.put("bundles", bundles);
                }
                bundles.put("dsh-app-release");
                Files.write(pkg.toPath(), o.toString(2).getBytes(StandardCharsets.UTF_8));
                sb.append(" ⇒ 已补登记并写回");
            }
        } catch (Throwable e) {
            sb.append(" 检查失败：").append(e);
        }
        return sb.toString();
    }

    /** 「插件市场」的索引源：社区维护的开放插件市场（GitHub: polang233/dsh-plugin-index）。 */
    private static final String MARKET_INDEX = "https://dshopen.org/index.json";
    private static final String MARKET_BUNDLE = "@deepseek-ai/dsh-experimental-plugin-market-bundle";

    /**
     * 插件市场：把官方实验 bundle「自定义插件市场」登记进 profile，并在 profile patch 里预置
     * 「开放插件市场」索引源，让插件页的「发现」标签开箱可用（索引本身是第三方内容，装前有确认框）。
     * 幂等；改了 profile 要重启引擎才生效，所以日志里明确写出来。
     */
    static String ensureMarket(Context ctx) {
        File root = Rootfs.dir(ctx);
        File web = new File(root, "home/.dsh/profiles/web");
        File pkg = new File(web, "package.json");
        File patch = new File(web, "cordis.patch.yml");
        if (!pkg.isFile()) return "插件市场：profile 缺失，跳过";
        // 先确认引擎安装里确实带了实验 bundle，避免登记后「cannot resolve profile bundle」把插件页拖垮
        File impl = new File(root,
                "usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-experimental-plugin-market-bundle/package.json");
        if (!impl.isFile()) return "插件市场：引擎未带实验 bundle，跳过";
        StringBuilder sb = new StringBuilder("插件市场：");
        try {
            String txt = new String(Files.readAllBytes(pkg.toPath()), StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(txt);
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            org.json.JSONObject profile = dsh == null ? null : dsh.optJSONObject("profile");
            org.json.JSONArray bundles = profile == null ? null : profile.optJSONArray("bundles");
            boolean has = false;
            if (bundles != null) {
                for (int i = 0; i < bundles.length(); i++) {
                    if (MARKET_BUNDLE.equals(bundles.optString(i))) { has = true; break; }
                }
            }
            boolean pkgChanged = false;
            if (!has) {
                if (profile == null) {
                    dsh = dsh == null ? new org.json.JSONObject() : dsh;
                    profile = new org.json.JSONObject();
                    dsh.put("profile", profile);
                    o.put("dsh", dsh);
                }
                if (bundles == null) {
                    bundles = new org.json.JSONArray();
                    profile.put("bundles", bundles);
                }
                bundles.put(MARKET_BUNDLE);
                Files.write(pkg.toPath(), o.toString(2).getBytes(StandardCharsets.UTF_8));
                pkgChanged = true;
            }
            boolean patchChanged = false;
            String y = patch.isFile() ? new String(Files.readAllBytes(patch.toPath()), StandardCharsets.UTF_8) : "";
            if (!y.contains("dshopen.org/index.json")) {
                if (y.contains("id: plugin-market")) {
                    sb.append("索引行已存在但不含开放插件市场，保持不动");
                } else {
                    if (!y.isEmpty() && !y.endsWith("\n")) y += "\n";
                    y += "\n# 插件市场：「发现」标签的索引源（开放插件市场，社区索引）\n"
                            + "- id: plugin-market\n"
                            + "  name: \"@deepseek-ai/dsh-plugin-market\"\n"
                            + "  config:\n"
                            + "    indexes:\n"
                            + "      - \"" + MARKET_INDEX + "\"\n";
                    Files.write(patch.toPath(), y.getBytes(StandardCharsets.UTF_8));
                    patchChanged = true;
                }
            }
            ensureNpmRegistry(web);
            if (pkgChanged || patchChanged) {
                sb.append("已启用（索引 dshopen.org），重启引擎后插件页出现「发现」");
            } else {
                sb.append("已是最新");
            }
        } catch (Throwable e) {
            sb.append("启用失败：").append(e);
        }
        return sb.toString();
    }

    /** 插件市场装包走 pnpm，其 registry 从 profile 目录的 .npmrc 读；不写就会直连 npmjs（慢/易断）。 */
    private static void ensureNpmRegistry(File web) throws Exception {
        File npmrc = new File(web, ".npmrc");
        String want = "registry=https://registry.npmmirror.com\n";
        if (npmrc.isFile()) {
            String t = new String(Files.readAllBytes(npmrc.toPath()), StandardCharsets.UTF_8);
            if (t.contains("registry=")) return;
            want = t.endsWith("\n") || t.isEmpty() ? t + want : t + "\n" + want;
        }
        Files.write(npmrc.toPath(), want.getBytes(StandardCharsets.UTF_8));
    }
}
