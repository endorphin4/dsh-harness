# 引擎与 APK 构建手册

本仓库的 APK 和 Android 引擎包都可以**在设备本身上构建**，不需要 Android Studio / Gradle。
本文记录实际跑通的工具链、载荷来源、补丁策略和发布流程。

---

## 一、APK 构建

### 依赖（全部来自 Termux 仓库，aarch64 原生）

```bash
dsh-pkg install aapt2            # 资源编译/链接
# d8 / apksigner 只需 jar + 包装脚本（避免拉第二个 JDK）
apt-get download d8 apksigner
dpkg-deb -x d8_*.deb x && dpkg-deb -x apksigner_*.deb x
cp x/data/data/com.termux/files/usr/share/java/{d8,apksigner}.jar $PREFIX/share/java/
printf '#!/system/bin/sh\nexec java -cp %s/share/java/d8.jar com.android.tools.r8.D8 "$@"\n' "$PREFIX" > $PREFIX/bin/d8
printf '#!/system/bin/sh\nexec java -jar %s/share/java/apksigner.jar "$@"\n' "$PREFIX" > $PREFIX/bin/apksigner
chmod 755 $PREFIX/bin/{d8,apksigner}
```

### android.jar

平台 37 的 SDK 包名暂为 404，平台 36 可用：

```bash
mkdir -p ~/workspace/.sdk && cd ~/workspace/.sdk
curl -L -o p36.zip https://dl.google.com/android/repository/platform-36_r02.zip
unzip -j p36.zip 'android-*/android.jar' -d .
```

### 载荷（assets/dshb-rootfs.tar.gz）

仓库不存这个大文件，从**已发布的 APK** 里取（与官方逐字节一致）：

```bash
curl -L -o old.apk <release>/dsh-harness-<ver>-arm64.apk
sha256sum old.apk        # 与 release 的 .sha256 比对
unzip -p old.apk assets/dshb-rootfs.tar.gz > dist/dshb-rootfs.tar.gz
```

> 首次安装必须有载荷；没有它 `Build.ROOTFS_URL` 会指向 `127.0.0.1:8099`（开发期下载服务），
> 在用户设备上必然失败。所以发布包**必须**内嵌载荷。

### 构建与签名

```bash
cd ~/workspace/dsh-harness
export ANDROID_JAR_DIR=$HOME/workspace/.sdk
bash build-apk.sh
```

- 签名密钥：`~/.config/dsh/shellb.keystore`（**不在仓库内**），密码读 `~/.config/dsh/ks-pass`
  或环境变量 `KS_PASS`，别名 `dshb`；详见 [SECURITY.md](../SECURITY.md)
- **必须使用原始密钥**，换钥会导致无法覆盖安装。官方证书指纹：
  `59:E2:D0:88:F7:D8:99:48:96:50:77:3C:8F:E0:51:7F:C0:9C:3C:0A:51:04:93:E8:2A:41:28:43:15:16:04:98`
- 产物：`dist/dshb-latest.apk` + `.sha256`（只保留 latest 省空间）

### 发布

```bash
GH_TOKEN=... bash release.sh "变更说明"
# 默认 prerelease=true；正式版用 PRE=false
```

---

## 二、引擎构建

### 一键

```bash
bash scripts/build-engine.sh 0.2.2-rc.1
# 可选 --from-existing <node_modules> 复用已装好的树
```

流程：

1. 安装依赖：有 `scripts/engine/locks/<ver>.json` 时用 `npm ci`（可复现）；
   没有则 `npm install` 并自动把生成的 lock 落到 `locks/`（记得提交）
2. 覆盖 `scripts/engine/patches/<ver>/` 里的补丁文件
3. 校验 5 个补丁标记（N1 / F3 / F7 / F8 / attachment）
4. 冒烟 1：`--version` 必须等于目标版本
5. 冒烟 2：空 `DSH_HOME` 启动 `--profile web`，3099 端口必须返回 401
6. 打包成 `@deepseek-ai/dsh/{lib,node_modules}` 同构结构 + sha256

### 发布

```bash
bash scripts/release-engine.sh 0.2.2-rc.1
```

建 `engine-v<ver>` release → 上传 tar/sha → 更新 `engine/latest.json`。

> App 检查优先走 GitHub API，CDN（jsDelivr）只是兜底；更新清单后可在
> `https://purge.jsdelivr.net/gh/endorphin4/dsh-harness@main/engine/latest.json` 手动刷新。

---

### 更新清单字段

`engine/latest.json` 除版本/地址外还带：

| 字段 | 含义 |
|---|---|
| `minShellVersion` | 允许安装该引擎的最低外壳版本。外壳低于它时 App 不提示升级（避免「老壳 + 新引擎」白屏），`release-engine.sh` 会自动写入当前 `version.txt` 的版本。 |

## 三、补丁策略

引擎的 Android 适配是**代码级**的，集中在 6 个文件：

| 文件 | 标记 | 作用 |
|---|---|---|
| `node-addon-require-builtin/lib/index.js` | N1 | 无原生绑定时回退 `require()`（否则启动即崩） |
| `@deepseek-ai/node-addon-system/lib/flock.js` | F3 | Android 无 flock 绑定时 stub 成立即成功 |
| `@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js` | F7 | `link(2)` EACCES → O_EXCL 占位 + rename |
| `@deepseek-ai/dsh-fs-local/lib/index.js` | F8 | createIfAbsent 的 link→rename 回退 |
| `@deepseek-ai/dsh-attachment-local/lib/index.js` | — | 附件 link→rename 回退 |
| `@deepseek-ai/dsh-client-ui-sidebar-browser/lib/client.js` | — | 地址栏关键词搜索、下载沙箱、文案 |

### 新版本怎么生成补丁

```bash
bash scripts/engine/merge-patches.sh 0.2.2-rc.1 0.2.3
```

它以旧版本干净 npm 树为 base、旧补丁文件为 ours、新版本干净树为 theirs 做三方合并：

- 零冲突 → 直接得到 `patches/0.2.3/`，可跑 `build-engine.sh`
- 有冲突 → 脚本列出文件和冲突块数量，人工解决后重跑

> 经验：上游重构（如 0.2.2 把 `runGroup` 抽走、`writeText` 改成 `writeTarget`）会让补丁锚点失效。
> 冲突不可怕，但要**逐块确认语义**，尤其 F7/F8 涉及会话与文件写入正确性。

---

## 四、发版检查清单

- [ ] `version.txt` 已 bump（CODE/NAME）
- [ ] `build-apk.sh` 产物 `versionName` 正确
- [ ] `apksigner verify --print-certs` 指纹 = `59:E2:D0:…:04:98`
- [ ] APK 内 `assets/dshb-rootfs.tar.gz` 存在
- [ ] `build-engine.sh` 双重冒烟通过
- [ ] 发布后 `app_check_update` 能正确报出新版本
- [ ] 真机验证：App 内更新 → 引擎重启 → 页面自动恢复（不应白屏）
- [ ] 设备上跑 `bash scripts/engine/check-runtime.sh`（会话文件可解析、link(2) 回退、补丁标记）
