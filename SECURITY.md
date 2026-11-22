# 安全策略

## 签名密钥（最重要）

APK 的签名密钥决定「能否覆盖安装」。**一旦更换，所有用户必须卸载重装并丢失数据**，
因此这把密钥是项目里最不能丢、也最不能泄露的东西。

| 项 | 值 |
|---|---|
| 位置 | `~/.config/dsh/shellb.keystore`（KEEP 区，不在仓库内） |
| 密码 | `~/.config/dsh/ks-pass`（600 权限），或环境变量 `KS_PASS` |
| 别名 | `dshb` |
| 类型 | PKCS12（库密码与密钥密码相同） |
| 证书 SHA-256 | `59:E2:D0:88:F7:D8:99:48:96:50:77:3C:8F:E0:51:7F:C0:9C:3C:0A:51:04:93:E8:2A:41:28:43:15:16:04:98` |

### 规则

1. **仓库里永远不能出现 keystore**，`.gitignore` 已覆盖 `*.keystore` / `*.jks`。
2. 构建机之外不要保留明文副本；备份请加密（例如 `age`/`gpg`），并离线存放。
3. 不要更换密钥。确需更换时必须走 v3 签名轮换（新旧密钥同时签），不能直接换。
4. 密码只通过 `KS_PASS` 或 `~/.config/dsh/ks-pass` 传递，不要写进脚本或命令历史。
5. 每次发版后核对：
   ```bash
   apksigner verify --print-certs dist/dshb-latest.apk | grep SHA-256
   ```
   指纹必须与上表一致。

## GitHub Token

| 项 | 值 |
|---|---|
| 位置 | `~/.config/dsh/github.token`（600，KEEP 区） |
| 权限 | `Contents: Read/Write`（发布用）；`Pull requests: Write`（建 PR） |
| 有效期 | 建议 ≤90 天，到期轮换 |

- 不要提交进仓库，也不要贴到聊天/日志里。
- 只授予必要仓库（`endorphin4/dsh-harness`）。

## 更新链路的信任边界

- 引擎包与 APK 都从 GitHub Releases 分发，下载后**必须校验 sha256**。
- `engine/latest.json` 是版本清单，App 检查优先走 GitHub API，jsDelivr 仅兜底。
- 计划中：清单增加 `minApkVersion`，阻止低于门槛的旧壳升级到新引擎（0.10.27 白屏事故的结构性修复）。

## 漏洞与密钥泄露

- 发现密钥泄露：立即在构建机生成新密钥**并接受用户需重装**的代价，或评估 v3 轮换；
  同时检查历史 Release 是否被篡改。
- 一般安全问题：在仓库开 issue，标题以 `security:` 开头，不要公开利用细节。
