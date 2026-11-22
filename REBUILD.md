# 从零重建 dsh-harness（1.0.0）

本仓库是 App 外壳的完整源码。仓库地址：<https://github.com/endorphin4/dsh-harness>

## 1.0.0 做了什么

正式版基线，相比 1.3.82 快照：

| 项 | 变化 |
|---|---|
| 版本 | `version.txt` → `CODE=1000` / `NAME=1.0.0`（正式版，`prerelease=false`） |
| 仓库 | 所有 `p48382961-spec/dsh-harness` 引用改为 `endorphin4/dsh-harness`（共 15 处） |
| 清理 | 删除 7 个历史调试快照目录、5 个调试文件（约 500 KB），并写入 `.gitignore` |
| 清单 | 废弃的 `shell/latest.json` 不再入库，改由 `tools/write-manifest.py` 发版时生成 |

**未剔除的大件**（本来就不在仓库，`.gitignore` 已挡）：

| 项 | 大小 | 说明 |
|---|---|---|
| `.all/` | 114 MB | 上游引擎解包树（曾误提交） |
| `.v4/` | 141 MB | profile `node_modules` 快照（曾误提交） |
| `.r.tgz` | 254 MB | 根文件系统载荷（曾误提交，超 GitHub 单文件上限） |
| `__pycache__/` | — | Python 缓存 |

## 目录结构

- `app/src/main/java/dev/dsh/harness/`：壳（MainActivity、Rootfs、Engine、Env、Mirror、Probe、Splash、EnginePanel、EnginePatch、Profile、Backup、LoginKeeper、Life 等）
- `app/src/main/assets/`：引擎构建脚本与补丁集（`engine-build/`）、dpkg 种子、pty 绑定、`payload-bin.txt`
- `payload/`：根文件系统覆盖层 —— `overrides/usr/bin/dpkg` 包装器、`dsh-doctor`、`dsh-relocate`、`dsh-engine-{build,update,port}.sh`、`dsh-app-release` 插件
- `js/chip.js`、`js/panel.js`：左下角胶囊与面板源码；`tools/gen-web.py` 生成 Java 内嵌的 `Probe.engineLabelJs` / `EnginePanel.js`
- `scripts/engine/`：预构建引擎工具（`build-engine.sh`、`merge-patches.sh`、`patches/<ver>/`、`locks/<ver>.json`）
- `tools/`：`build-dpkg-lists.py`、`gen-web.py`、`probe-js-check.sh`、`write-manifest.py`
- `engine/`、`shell/`：更新清单
- `build-apk.sh`、`release.sh`、`version.txt`

## 构建前需要准备（都不在仓库里）

1. **`dist/dshb-rootfs.tar.gz`** —— 根文件系统载荷，254,476,654 B（Termux aarch64 + node v24.18.0 + DSH 0.2.2-rc.5）。从旧构建机拷，或按「重建载荷」自己出。**不要提交。**
2. `dist/dsh-engine-<ver>-android-arm64.tar.gz`（可选，本地更新服务用）。
3. `android.jar`：默认 `$PROJ/../.sdk/android.jar`，或设 `ANDROID_JAR_DIR`。
4. 工具链：`aapt2`、`javac`（17+）、`d8`、`apksigner`、`python3`、`tar`、`zstd`。
5. 签名密钥：默认 `$HOME/.config/dsh/shellb.keystore`，密码 `$HOME/.config/dsh/ks-pass`；可用 `KS`/`KS_PASS`/`KS_ALIAS` 覆盖。

目标：minSdk 26 / targetSdk 28 / arm64-only，不用 Gradle。

## 构建

```bash
bash build-apk.sh          # → dist/dev.dsh.harness-<version>.apk
```

版本号在 `version.txt`（当前 `CODE=1000`、`NAME=1.0.0`）。`release.sh` 会校验 versionCode 必须大于已发布版本。

## 重建载荷（没有旧 dist 时）

- 引擎树：`bash scripts/build-engine.sh <version>` 在 Termux 里从 npm 构建（npm ci → 覆盖 Android 补丁 → 标记/版本校验 → 空 HOME 冒烟 → 打包），再把产物解包成 `usr/`。
- dpkg 元数据：`python3 tools/build-dpkg-lists.py` 读 `usr/var/lib/dpkg/status`、下载对应 termux deb、生成真实 `.list` 并重打载荷。
- 载荷布局要求：`usr/` 与 `home/` 直接在根；`usr/data/data/com.termux/files/{usr,home}` 是指向它们的绝对软链；node 必须是 Termux 编译（`PT_INTERP=/system/bin/linker64`）。

## 清单与发布

- `tools/write-manifest.py` 生成 `shell/latest.json`（GitHub 地址）与 `dist/shell-latest.json`（回环 8099）；引擎清单是 `engine/latest.json`。
- `release.sh` 一键发版：构建 → 算 sha256 → 建 tag → 传 GitHub Release。正式版用 `PRE=false`。
- 更新检查顺序硬编码在 `MainActivity.pushShellState/pushUpdateState/epRefresh`：本机 8099 → App 镜像 8098 → `gcore.jsdelivr.net` → `raw.githubusercontent.com`。当前 repo 常量已是 `endorphin4/dsh-harness`（`MainActivity.java`、`payload/plugins/dsh-app-release/lib/index.js`）。

## 从 1.3.x 升级到 1.0.0 的注意点

更新检查比的是 **versionName（semver）**，而 `1.0.0 < 1.3.82`，所以：

- 已装 1.3.82 的设备**不会自动收到** 1.0.0 的更新提示
- 但 `versionCode` 1000 > 186，**手动安装可正常覆盖升级**，数据保留
- 全新安装直接是 1.0.0，不受影响
- 若要老设备也能自动升级，需要把比较逻辑改成优先比 `versionCode`（见 `dsh-app-release/lib/index.js` 的 `compareVersions` 调用点）

## 构建期发现的两个坑（1.0.0 已修/已记录）

1. **`themes.xml` 的 `windowBackground` 用了字面色值** `#FF101418`，新版 aapt2 会报
   `expected reference but got (raw string)`。已改为 `@color/window_bg`（新增 `values/colors.xml`）。
2. **`app/src/main/assets/dpkg-lists/` 在仓库里是空的**，构建脚本从 `$PROJ/../.dpkg-lists/` 取：
   - `lists/*.list`（75 份）
   - `full/usr/var/lib/dpkg/{status,available}`
   这三个都能从已安装的 APK 里解出来：
   ```bash
   unzip -o "$APK" 'assets/dpkg-lists/*' 'assets/dpkg-status.txt' 'assets/dpkg-available.txt' -d ./x
   mkdir -p ../.dpkg-lists/lists ../.dpkg-lists/full/usr/var/lib/dpkg
   cp x/assets/dpkg-lists/*.list ../.dpkg-lists/lists/
   cp x/assets/dpkg-status.txt ../.dpkg-lists/full/usr/var/lib/dpkg/status
   cp x/assets/dpkg-available.txt ../.dpkg-lists/full/usr/var/lib/dpkg/available
   ```
3. **`android.jar` 需 API 31+**（代码用了 `Notification.FOREGROUND_SERVICE_IMMEDIATE`）。
   platform-28 会编译失败；用 platform-31 的 `android.jar` 通过。

## 发版检查清单

1. `version.txt` 递增 `CODE`，`NAME` 按需改
2. `dist/dshb-rootfs.tar.gz` 在位且大小正确
3. `bash build-apk.sh` 出包
4. `PRE=false bash release.sh "1.0.0"`
5. 确认 Release 里有 APK 与 `.sha256`，且 `shell/latest.json` 已更新
