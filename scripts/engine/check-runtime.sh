#!/data/user/0/dev.dsh.harness/files/usr/bin/bash
# check-runtime.sh — 引擎更新后的「运行时」自检（Android 专用）
#
# 和 build-engine.sh 的启动冒烟互补：这里检查已经跑起来的实例——
# 会话文件是否真的可解析、附件写入回退是否可用、补丁是否都在。
#
# 用法: bash scripts/engine/check-runtime.sh
set -uo pipefail

P="${PREFIX:-/data/user/0/dev.dsh.harness/files/usr}"
ROOT="${DSH_DATA_ROOT:-/data/data/dev.dsh.harness/files}"
HOME_DIR="$ROOT/home"
fail=0
ok()   { printf '  \033[32m✓\033[0m %-26s %s\n' "$1" "$2"; }
bad()  { printf '  \033[31m✗\033[0m %-26s %s\n' "$1" "$2"; fail=$((fail+1)); }
warn() { printf '  \033[33m!\033[0m %-26s %s\n' "$1" "$2"; }
export PATH="$P/bin:$PATH"

echo "== 进程与端口 =="
PID=$(tr -dc '0-9' < "$ROOT/engine.pid" 2>/dev/null)
if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null && tr '\0' ' ' < "/proc/$PID/cmdline" 2>/dev/null | grep -q "lib/bin.js"; then
    ok "引擎进程" "pid $PID"
else
    bad "引擎进程" "engine.pid 无效或进程不在"
fi
CODE=$(curl -s -o /dev/null -m 5 -w '%{http_code}' http://127.0.0.1:3082 2>/dev/null || echo 000)
case "$CODE" in 401|200) ok "HTTP 3082" "$CODE" ;; *) bad "HTTP 3082" "$CODE" ;; esac

echo "== 版本 =="
SHELL_VER=$(tr -d '\n' < "$ROOT/app-version.txt" 2>/dev/null)
ENGINE_VER=$(node -p "require('$P/lib/node_modules/@deepseek-ai/dsh/package.json').version" 2>/dev/null || echo "?")
ok "外壳 / 引擎" "${SHELL_VER:-?} / $ENGINE_VER"

echo "== 补丁标记 =="
D="$P/lib/node_modules/@deepseek-ai/dsh/node_modules"
declare -A MARK=(
  ["N1 require-builtin"]="$D/node-addon-require-builtin/lib/index.js:dsh-mobile N1"
  ["F3 flock"]="$D/@deepseek-ai/node-addon-system/lib/flock.js:dsh-mobile F3"
  ["F7 会话发布"]="$D/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js:dsh-mobile exclusive publish"
  ["F8 fs-local"]="$D/@deepseek-ai/dsh-fs-local/lib/index.js:dshMobilePublishExclusive"
  ["attachment"]="$D/@deepseek-ai/dsh-attachment-local/lib/index.js:dsh-mobile link->rename"
)
for name in "${!MARK[@]}"; do
    f="${MARK[$name]%%:*}"; needle="${MARK[$name]##*:}"
    if grep -q "$needle" "$f" 2>/dev/null; then ok "$name" "已打补丁"; else bad "$name" "标记缺失"; fi
done

echo "== 会话持久化（F7 实跑结果）=="
SESSION=$(find "$HOME_DIR/.dsh/sessions" -name 'session.v4.jsonl.zstd' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)
if [ -n "$SESSION" ]; then
    AGE=$(( $(date +%s) - $(stat -c %Y "$SESSION") ))
    LINES=$(node -e '
const fs=require("fs"),zlib=require("zlib");
try {
  const buf=zlib.zstdDecompressSync(fs.readFileSync(process.argv[1]));
  const lines=buf.toString("utf8").trim().split("\n").filter(Boolean);
  JSON.parse(lines[0]); JSON.parse(lines[lines.length-1]);
  console.log(lines.length);
} catch (e) { console.log("ERR:"+e.message); }
' "$SESSION" 2>/dev/null)
    case "$LINES" in
        ERR:*) bad "会话文件" "解压/解析失败 ${LINES#ERR:}" ;;
        *)     ok "会话文件" "$LINES 行，${AGE}s 前写入，首尾 JSON 可解析" ;;
    esac
else
    warn "会话文件" "没有找到（还没聊过？）"
fi

echo "== 文件系统能力（F7/F8 的前提）=="
TMPD=$(mktemp -d "$HOME_DIR/tmp/rtcheck.XXXXXX") || { bad "临时目录" "创建失败"; TMPD=""; }
if [ -n "$TMPD" ]; then
    printf 'x' > "$TMPD/a"
    if ln "$TMPD/a" "$TMPD/b" 2>/dev/null; then
        warn "link(2)" "居然成功了（本机通常 EACCES，补丁走 rename 分支）"
    else
        ok "link(2)" "如预期失败 → 走 rename 回退"
    fi
    if node -e '
const fs=require("fs");const p=process.argv[1];
const f=fs.openSync(p,"wx");fs.closeSync(f);
fs.renameSync(process.argv[2],p);
' "$TMPD/claim" "$TMPD/a" 2>/dev/null; then
        ok "O_EXCL + rename" "可用（F7/F8 的发布算法）"
    else
        bad "O_EXCL + rename" "不可用"
    fi
    rm -rf "$TMPD"
fi

echo "== Office =="
KIT="$D/@deepseek-ai/libreoffice-kit"
if [ -f "$KIT/package.json" ]; then
    KV=$(node -p "require('$KIT/package.json').version" 2>/dev/null)
    if [ -d "$D/@deepseek-ai/libreoffice-kit-wasm" ]; then ok "office" "kit $KV + wasm"; else warn "office" "kit $KV，但缺 wasm（转换会失败）"; fi
else
    warn "office" "libreoffice-kit 未安装"
fi

echo
[ "$fail" -eq 0 ] && echo "结论：运行时自检通过 ✅" || echo "结论：$fail 项失败 ❌"
exit "$fail"
