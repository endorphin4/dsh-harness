#!/data/data/dev.dsh.harness/files/usr/bin/bash
# 由 dsh-shell-b 载荷提供：在 App 自带的前缀里，从 npm 构建一份**打好 Android 补丁**的引擎。
#
# 产物：<out.tar.gz>（结构同 dist/dsh-engine-*.tar.gz）
# 换装不在这里做：调用方（Engine.buildFromNpm）拿到 tar 后交给 dsh-engine-update.sh 的本地包模式，
# 校验、解包、冒烟、原子切换、重启、回滚都在那边，失败不会动现网引擎。
#
# 用法: dsh-engine-build.sh <版本> <assets目录> <输出tar路径>
set -uo pipefail

ROOT="/data/data/dev.dsh.harness/files"
PREFIX="$ROOT/usr"
export HOME="$ROOT/home"
export TMPDIR="$HOME/tmp"
export PREFIX
export PATH="$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"
export OPENSSL_CONF="$PREFIX/etc/tls/openssl.cnf"
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export SSL_CERT_DIR="$PREFIX/etc/tls"
export TZ="${TZ:-Asia/Shanghai}"
mkdir -p "$TMPDIR" "$HOME/试玩"

VERSION="${1:-}"
ASSETS="${2:-}"
OUT="${3:-}"
[ -n "$VERSION" ] && [ -n "$ASSETS" ] && [ -n "$OUT" ] || {
  echo "用法: dsh-engine-build.sh <版本> <assets目录> <输出tar>" >&2; exit 2; }

REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"
LOG="$HOME/试玩/dsh-engine-build.log"
STAMP="$(date +%Y%m%d-%H%M%S)"
WORK="$HOME/试玩/engine-build-$STAMP"
NPM_DIR="$WORK/npm"
MODS="$NPM_DIR/node_modules"
STAGE="$WORK/stage"
STATE="$HOME/试玩/.dsh-update-state.json"
STARTED="$(date +%s)"
CUR="$(node -p "require('$PREFIX/lib/node_modules/@deepseek-ai/dsh/package.json').version" 2>/dev/null || echo unknown)"

log() {
  local m
  m="$(date '+%m-%d %H:%M:%S') $*"
  printf '%s\n' "$m" >>"$LOG"
  printf '%s\n' "$m" >&2   # 走 stderr：App 的 watcher 合并两路能看到，命令替换又不会混进 stdout
}
stage() {
  printf '{"stage":"%s","label":"%s","pct":%s,"target":"%s","from":"%s","to":"%s","startedAt":%s,"updatedAt":%s,"done":%s,"error":null}\n' \
    "$1" "$2" "$3" "$VERSION" "$CUR" "$VERSION" "$STARTED" "$(date +%s)" "${4:-false}" > "$STATE"
  log "$1 $2 ($3%)"
}
on_exit() {
  local rc=$?
  if [ $rc -ne 0 ]; then
    stage "error" "构建失败（退出码 $rc），现网引擎未动" 100 false
  fi
  [ "${KEEP_WORK:-0}" = "1" ] || rm -rf "$WORK"
  exit $rc
}
trap on_exit EXIT INT TERM

# 私有目录里的 ELF 能不能直接 exec 取决于设备；引擎自己是走 linker64 起的，这里两种都试一次并记住。
NODE_MODE=""
runnode() {
  if [ -z "$NODE_MODE" ]; then
    if "$PREFIX/bin/node" -v >/dev/null 2>&1; then NODE_MODE=direct; else NODE_MODE=linker; fi
    log "node 启动方式：$NODE_MODE"
  fi
  if [ "$NODE_MODE" = direct ]; then "$PREFIX/bin/node" "$@"; else /system/bin/linker64 "$PREFIX/bin/node" "$@"; fi
}

cd "$HOME/试玩" || exit 1
stage "prepare" "检查补丁集与锁文件（$VERSION）" 2
[ -f "$ASSETS/package.json" ] || { log "缺少 $ASSETS/package.json"; exit 1; }
[ -f "$ASSETS/package-lock.json" ] || { log "缺少 $ASSETS/package-lock.json"; exit 1; }
[ -d "$ASSETS/patches" ] || { log "缺少 $ASSETS/patches（该版本还没有 Android 补丁集，拒绝构建）"; exit 1; }
mkdir -p "$NPM_DIR" "$STAGE"
cp -f "$ASSETS/package.json" "$NPM_DIR/package.json"
cp -f "$ASSETS/package-lock.json" "$NPM_DIR/package-lock.json"

stage "install" "npm ci 安装依赖（约 170 MB，几分钟）" 8
( cd "$NPM_DIR" && runnode "$PREFIX/lib/node_modules/npm/bin/npm-cli.js" ci \
    --registry="$REGISTRY" --omit=optional --ignore-scripts --no-audit --no-fund ) >>"$LOG" 2>&1
[ -d "$MODS/@deepseek-ai/dsh" ] || { log "npm ci 后找不到 @deepseek-ai/dsh"; exit 1; }
stage "install" "依赖安装完成" 40

stage "patch" "覆盖 Android 补丁文件" 45
( cd "$ASSETS/patches" && find . -type f -print0 | while IFS= read -r -d '' f; do
    rel="${f#./}"
    mkdir -p "$MODS/$(dirname "$rel")"
    cp -f "$f" "$MODS/$rel"
  done ) || { log "拷补丁失败"; exit 1; }

stage "verify" "校验补丁标记与版本" 55
grep -q "dsh-mobile N1" "$MODS/node-addon-require-builtin/lib/index.js" || { log "✗ N1 补丁缺失"; exit 1; }
grep -q "dsh-mobile F3" "$MODS/@deepseek-ai/node-addon-system/lib/flock.js" || { log "✗ F3 补丁缺失"; exit 1; }
grep -q "dsh-mobile exclusive publish" "$MODS/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js" || { log "✗ F7 补丁缺失"; exit 1; }
grep -q "dsh-mobile link->rename" "$MODS/@deepseek-ai/dsh-attachment-local/lib/index.js" || { log "✗ attachment 补丁缺失"; exit 1; }
grep -q "dshMobilePublishExclusive" "$MODS/@deepseek-ai/dsh-fs-local/lib/index.js" || { log "✗ F8 补丁缺失"; exit 1; }
BIN="$MODS/@deepseek-ai/dsh/lib/bin.js"
GOT="$(cd "$MODS/@deepseek-ai/dsh" && runnode -p "require('./package.json').version" 2>/dev/null || echo unknown)"
[ "$GOT" = "$VERSION" ] || { log "版本不符（包内 $GOT，期望 $VERSION）"; exit 1; }
stage "verify" "补丁标记齐全（$GOT）" 58

# 终端运行时：Android 的 node 报 process.platform === "android"，而 subprocess-local 只认
# linux/darwin/win32；把 android 当 linux（/proc 探查在 Android 上可用），并把载荷里
# Termux 编的 node-pty 绑定补进 build/Release（npm 包的 prebuilds 是 glibc 的，dlopen 会失败）。
SUB_LIB="$MODS/@deepseek-ai/dsh-subprocess-local/lib"
SUB="$(ls "$SUB_LIB"/runner-launch-*.js 2>/dev/null | head -1)"
[ -n "$SUB" ] || { log "✗ 找不到 dsh-subprocess-local/lib/runner-launch-*.js"; exit 1; }
if grep -q 'platform === "linux" || platform === "android"' "$SUB"; then
  :
else
  sed -i 's/if (platform === "linux") return new LinuxProcessInspector/if (platform === "linux" || platform === "android") return new LinuxProcessInspector/' "$SUB"
fi
grep -q 'platform === "linux" || platform === "android"' "$SUB" || { log "✗ subprocess android 平台补丁未生效"; exit 1; }
PTY_BIN="$ROOT/share/node-pty.node"      # Rootfs.ensurePtyBinding 写这里
[ -f "$PTY_BIN" ] || PTY_BIN="$PREFIX/share/node-pty.node"   # 老安装可能落在前缀里
[ -f "$PTY_BIN" ] || { log "✗ 终端 pty 绑定缺失（试过 $ROOT/share 与 $PREFIX/share）"; exit 1; }
mkdir -p "$MODS/node-pty/build/Release"
cp -f "$PTY_BIN" "$MODS/node-pty/build/Release/pty.node"
chmod 755 "$MODS/node-pty/build/Release/pty.node"

# dsh 命令行入口：npm 包的 bin.js 是 #!/usr/bin/env node，Android 没有 /usr/bin/env。
# 换成载荷自己的 node 绝对路径，否则终端里 `dsh` 报 bad interpreter。
BINJS="$MODS/@deepseek-ai/dsh/lib/bin.js"
[ -f "$BINJS" ] || { log "✗ 找不到 $BINJS"; exit 1; }
sed -i "1s|^#!.*|#!$PREFIX/bin/node|" "$BINJS"
chmod 755 "$BINJS"
head -1 "$BINJS" | grep -qx "#!$PREFIX/bin/node" || { log "✗ bin.js shebang 修正失败"; exit 1; }

stage "smoke" "空 HOME 冒烟（起 3099 看能不能应答）" 62
SMOKE_HOME="$WORK/smoke-home"
rm -rf "$SMOKE_HOME"; mkdir -p "$SMOKE_HOME"
( DSH_HOME="$SMOKE_HOME" DSHH_NO_UPDATE=1 runnode --expose-internals "$BIN" \
    --profile web --host 127.0.0.1 --port 3099 --no-open > "$WORK/smoke.log" 2>&1 & echo $! > "$WORK/smoke.pid" )
SMOKE_PID="$(cat "$WORK/smoke.pid" 2>/dev/null)"
code=""
for _ in $(seq 1 40); do
  code=$(curl -s -o /dev/null -m 2 -w '%{http_code}' http://127.0.0.1:3099/ 2>/dev/null || true)
  [ "$code" = "401" ] && break
  kill -0 "$SMOKE_PID" 2>/dev/null || break
  sleep 1
done
kill "$SMOKE_PID" 2>/dev/null || true
[ "$code" = "401" ] || { log "冒烟失败（HTTP ${code:-000}）"; tail -20 "$WORK/smoke.log" >>"$LOG"; exit 1; }
stage "smoke" "冒烟通过（HTTP 401）" 68

stage "pack" "打包引擎 tar" 72
mkdir -p "$STAGE/@deepseek-ai/dsh/node_modules/@deepseek-ai"
cp -a "$MODS/@deepseek-ai/dsh/." "$STAGE/@deepseek-ai/dsh/"
for entry in "$MODS"/*; do
  name="$(basename "$entry")"
  [ "$name" = "@deepseek-ai" ] && continue
  cp -a "$entry" "$STAGE/@deepseek-ai/dsh/node_modules/"
done
for entry in "$MODS/@deepseek-ai"/*; do
  name="$(basename "$entry")"
  [ "$name" = "dsh" ] && continue
  cp -a "$entry" "$STAGE/@deepseek-ai/dsh/node_modules/@deepseek-ai/"
done
mkdir -p "$(dirname "$OUT")"
tar -czf "$OUT" -C "$STAGE" .
SIZE=$(stat -c%s "$OUT" 2>/dev/null || echo 0)
[ "$SIZE" -gt 10000000 ] || { log "打出的包太小（$SIZE 字节），放弃"; exit 1; }
stage "built" "构建完成（$((SIZE/1048576)) MB），交给换装" 74
log "产物 $OUT $SIZE 字节"
