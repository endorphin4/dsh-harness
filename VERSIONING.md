# 版本与发布约定

本项目自 **1.0.0** 起进入正式版维护，以下是必须遵守的规则。

## 版本号

`version.txt` 是唯一真源（`build-apk.sh`、`release.sh`、`scripts/release-engine.sh` 都读它）：

```
CODE=1000
NAME=1.0.0
```

### versionName

遵循语义化版本 `MAJOR.MINOR.PATCH`：

| 变更类型 | 递增位 | 例子 |
|---|---|---|
| 不兼容的架构/数据格式变化、需要用户重装 | MAJOR | `2.0.0` |
| 新功能，向后兼容 | MINOR | `1.1.0` |
| 修 bug、打补丁 | PATCH | `1.0.1` |

禁止使用 `-rc` / `-beta` 后缀发布正式版；预发布用 GitHub Release 的 `prerelease` 标记。

### versionCode

**必须严格单调递增**，否则 Android 拒绝覆盖安装。官方换算：

```
versionCode = MAJOR × 1000 + MINOR × 100 + PATCH
```

| versionName | versionCode |
|---|---|
| 1.0.0 | 1000 |
| 1.0.1 | 1001 |
| 1.1.0 | 1100 |
| 2.0.0 | 2000 |

> 历史迁移：`1.3.82` 用的是 `186`。新方案从 `1000` 起，天然大于历史值，无需特殊处理。
> 但 **`1.0.0 < 1.3.82` 在版本名上倒挂**，所以从 1.3.x 升级到 1.0.0 只能手动覆盖安装一次（签名一致、versionCode 更大，数据保留）。此后恢复正常自动更新。

## 发版流程

```bash
# 1. 改 version.txt（按上面换算）
# 2. 构建
bash build-apk.sh
# 3. 发版（正式版必须 PRE=false）
PRE=false bash release.sh "1.0.1"
```

`release.sh` 内有版本守卫：新 versionCode 必须大于已发布最新版，否则拒绝发布。

## 构建前置（不在仓库里）

见 `REBUILD.md` 的「构建前需要准备」：`dist/dshb-rootfs.tar.gz`、`android.jar`（API 31+）、
`aapt2`/`d8`/`apksigner`、签名密钥 `~/.config/dsh/shellb.keystore`。

**签名密钥是 App 身份**，丢失会导致所有已安装设备无法升级（只能卸载重装）。保管要求：

- 至少两份离线备份，不要放在 HTTP 服务目录、不要进 git
- 权限 `600`
- 轮换密钥 = 全部用户重装，除非确有泄露，否则不要轮换

## 发版纪律（2026-11-23 起）

> 背景：1.0.0 → 1.0.7 在一天内发了 8 个版本，属于"发现一个补一个"的排障节奏，不可持续。

### 冻结基线

**1.0.7 (1007) 为当前稳定版**，功能冻结；除非出现**阻断性缺陷**，否则不再发新版本。

### 发版节奏

| 类型 | 触发条件 | 频率 |
|---|---|---|
| 常规版本 | 收集到的一批修复/改进 | **每周最多一次**（建议每两周） |
| 热修复 | 装不上、进不去、闪退等阻断性问题 | 随时，但必须 bump patch 且只含该修复 |
| 引擎包 | 上游引擎新版本 | 跟随引擎发版，单独 `engine-v*` 标签 |

### 硬性规则

1. **同一 versionCode 只发一次**。发错了宁可 bump 一个 patch，也不要覆盖同名 Release
   （1.0.1 曾重发两次，导致已安装设备无法自更新）
2. **不删除已发布的 Release**：更新检查依赖 `latest`，删旧版会让回退路径断掉
3. **草稿要及时清理**：发布失败留下的 draft 必须删除，否则会污染 `latest` 判定
4. **发布前必须本机装一次**：确认版本号、通知、页面都正常
5. 发布失败（网络/API 超时）**不要连续重试超过 10 次**，隔几分钟再来，避免触发速率限制

## 发布检查清单

1. `version.txt` 的 `CODE`/`NAME` 符合上述换算
2. `dist/dshb-rootfs.tar.gz` 在位且大小正确
3. `bash build-apk.sh` 出包，签名指纹与上一版一致
4. `PRE=false bash release.sh` 发版
5. Release 资产命名必须是 `dsh-harness-<version>-arm64.apk` 与同名 `.sha256`
6. `shell/latest.json` 已更新（`release.sh`/`write-manifest.py` 会自动写）
7. 在本机装一次，确认版本号与环境完好
