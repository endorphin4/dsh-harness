# 变更记录

## 1.0.5 — 2026-11-23

### 修复

- **新设备首装启动失败 `EISDIR`**：载荷解包时若某路径残留了类型相反的条目（旧目录 vs 新文件），
  `FileOutputStream` 会抛 `open failed: EISDIR`。现在解包遇到类型冲突先清掉再写，可自愈
- **WebView 媒体权限**：补 `onPermissionRequest` + `RECORD_AUDIO`/`CAMERA` 权限，语音输入不再"点了没反应"
- **WebView 渲染进程崩溃**：补 `onRenderProcessGone`，自动重建页面而不是留白屏
- **页面 JS 报错无日志**：补 `onConsoleMessage`，ERROR/WARN 写入 `life.log`
- **软键盘可能遮挡输入框**：Activity 增加 `windowSoftInputMode="adjustResize"`

### 文案与界面

- 全部去掉「大肥鱼」：通知栏改为「正在启动引擎… / 引擎运行中 / 引擎已停止，正在重新启动…」
- 启动失败页重做：去掉 emoji，改为正式标题「引擎启动失败」+ 错误信息卡片 + 可折叠「技术详情」+ 重试按钮


## 1.0.4 — 2026-11-23

### 修复

- **聊天框「+」里的「文件」点了没反应**：WebView 的 `WebChromeClient` 没实现 `onShowFileChooser`，
  页面里任何 `<input type="file">` 都拿不到回调。现补上：
  - `onShowFileChooser` → 用 `FileChooserParams.createIntent()` 拉起系统选择器（失败回退 `ACTION_GET_CONTENT`）
  - `onActivityResult` → 把选中结果（含多选）回传给 WebView
  - 日志里会留一行「页面请求选择文件」，方便确认回调是否真的被触发


## 1.0.3 — 2026-11-23

- **引擎清单也改走 GitHub API**：实测 jsDelivr 对分支路径按路径缓存、**忽略 `?dshb=` 查询参数**，
  所以 1.0.2 里靠查询参数做缓存击穿对 CDN 无效。引擎更新检查现在同样是
  「本机镜像 → 本机 8099 → **GitHub Releases API（找最新 engine-v\*）** → CDN → raw」
- 外壳清单同样以 API 为主源（1.0.1 起已是），CDN 仅作回退


## 1.0.2 — 2026-11-23

### 修复

- **更新缝隙**：1.0.1 发过两次（同 versionCode 1001），装过第一次的设备无法自更新；本版用 1002 抹平
- **换装脚本发不下去**：`dsh-engine-update.sh` 过去只存在于 rootfs 载荷里，改了代码也进不了设备。
  现在改为随 APK 资产下发，并在启动时自愈到 `files/home/试玩/`

### 新增

- **引擎预构建包通道**：发布 `engine-v0.2.2-rc.5`（约 157 MB），`engine/latest.json` 指向 GitHub 资产。
  用户点「更新引擎」优先走「下载预构建包 → 校验 → 换装」，不必再在手机里 npm 现场构建
- **换装后自动清理**：`updates/*.tar.gz` 在换装成功后删除，避免每次更新堆积 100~160 MB


## 1.0.1 — 2026-11-22

修复新设备首次安装无法进入的问题。

### 修复

- **插件加载失败（`Failed to load plugins / invalid plugin`）**：随包载荷里 `dsh-app-release`
  的客户端 bundle 是返回 `{ exports: {} }` 的空壳，不符合 DSH 客户端插件契约。
  - 载荷层面：从 rootfs 中删除该 `lib/client.js`，并从 `package.json` 移除 `dsh.client` 与 `./client` 导出
  - 代码层面：新增页面自愈——检测到该报错时自动摘除客户端 bundle、重启引擎并重载
- **首次启动就被电池优化弹窗挡住**：改为页面加载完成后 6 秒再申请，不再一进门就弹系统框
- **更新检查读不到新版本**：CDN `@main` 有小时级缓存（实测发版后仍返回旧版本）。
  清单获取改为「本机镜像 → GitHub Releases API（无缓存）→ CDN/raw（带缓存击穿）」
- **重新发布 v1.0.1**：替换首次发布的资产（首次发布的包不含本次更新链路修复）


## 1.0.0 — 2026-11-22

首个正式版基线。

### 变更

- **版本**：`1.3.82 (186)` → `1.0.0 (1000)`，`prerelease` 置为 `false`
- **仓库迁移**：`p48382961-spec/dsh-harness` → `endorphin4/dsh-harness`，涉及应用内更新检查、发布脚本、清单生成器、文档共 15 处
- **清理**：删除 7 个历史调试快照目录（`.chk/` `.pj/` `.pl/` `.sb/` `.sbc/` `.stub-B/` `.t5/`）与 5 个调试文件，约 500 KB；相关规则写入 `.gitignore`
- **清单**：`shell/latest.json` 不再入库，改由 `tools/write-manifest.py` 在发版时生成，避免仓库里长期存在指向旧版本、旧仓库的过期清单
- **文档**：重写 `REBUILD.md`，补充升级路径与发版检查清单

### 升级说明

`1.0.0` 的版本名小于 `1.3.82`，自动更新不会提示；手动覆盖安装即可（`versionCode` 1000 大于 186），数据保留。

## 1.3.82 — 2026-11-20

发布前最后一版预发布，功能与 1.0.0 一致。
