#!/data/data/dev.dsh.harness/files/usr/bin/bash
# dsh-engine-port.sh — 把一份现成的引擎补丁集，三方合并到 npm 上的新版本。
#
# 用途：App 内「从 npm 构建」时，如果安装包里没有目标版本的补丁集，
#       就用这里自动迁移一份，再交给 dsh-engine-build.sh 构建。
#
# 用法: dsh-engine-port.sh <base版本> <目标版本> <输出目录>
#   base 版本必须已经在 $ROOT/engine-build/<base>/ 里解好（package-lock + patches）。
#   输出目录会写成和 assets/engine-build/<ver>/ 同样的结构。
#
# 原理（与 tools/port-patches.py 一致）：
#   diff3 -m <旧补丁文件> <旧版本原文件> <新版本原文件>
#   只处理补丁涉及的那几个 npm 包，用 npm pack 单独取文件，不装整棵树。
#
# 已知特例：@deepseek-ai/dsh-client-ui-sidebar-browser/lib/client.js 从 rc.5 起被
# 上游压缩成两行，整文件合并必然冲突；它的三处改动由 App 运行时注入
# （EnginePatch.ensureBrowser），所以这里跳过，改完由引擎启动时补上。
set -uo pipefail

ROOT="${DSHB_ROOT:-/data/data/dev.dsh.harness/files}"
PREFIX="$ROOT/usr"
export HOME="${DSHB_HOME:-$ROOT/home}"
export TMPDIR="$HOME/tmp"
export PREFIX
export PATH="$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"
export OPENSSL_CONF="$PREFIX/etc/tls/openssl.cnf"
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export SSL_CERT_DIR="$PREFIX/etc/tls"
export TZ="${TZ:-Asia/Shanghai}"
mkdir -p "$TMPDIR" "$HOME/试玩"

BASE="${1:-}"; TO="${2:-}"; OUT="${3:-}"
[ -n "$BASE" ] && [ -n "$TO" ] && [ -n "$OUT" ] || {
  echo "用法: dsh-engine-port.sh <base版本> <目标版本> <输出目录>" >&2; exit 2; }

REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"
NODE="$PREFIX/bin/node"
NPMCLI="$PREFIX/lib/node_modules/npm/bin/npm-cli.js"
BASE_ASSETS="$ROOT/engine-build/$BASE"
LOG="$HOME/试玩/dsh-engine-port.log"
STAMP="$(date +%Y%m%d-%H%M%S)"
WORK="$HOME/试玩/engine-port-$STAMP"
SKIP_FILE="@deepseek-ai/dsh-client-ui-sidebar-browser/lib/client.js"

log() { printf '%s %s\n' "$(date '+%m-%d %H:%M:%S')" "$*" | tee -a "$LOG"; }
die() { log "✗ $*"; exit "${2:-1}"; }

# 私有目录里的 ELF 多数能直接 exec；个别机型要 linker64，两种都试。
runb() { "$@" 2>/dev/null && return 0; /system/bin/linker64 "$@" 2>/dev/null; }
runnode() {
  "$NODE" "$@" 2>/dev/null && return 0
  /system/bin/linker64 "$NODE" "$@"
}

[ -f "$BASE_ASSETS/package-lock.json" ] || die "缺少基础补丁集：$BASE_ASSETS/package-lock.json" 2
[ -d "$BASE_ASSETS/patches" ] || die "缺少基础补丁目录：$BASE_ASSETS/patches" 2
[ -f "$NODE" ] || die "缺少 node：$NODE" 2
[ -f "$NPMCLI" ] || die "缺少 npm：$NPMCLI" 2

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK/tars" "$OUT/patches" || die "建目录失败" 2
cleanup() { [ "${KEEP_WORK:-0}" = "1" ] || rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

log "== 迁移补丁集 $BASE → $TO（$REGISTRY）"

# 1) 目标版本锁文件
printf '{"name":"engine-build","private":true,"version":"0.0.0","dependencies":{"@deepseek-ai/dsh":"%s"}}\n' "$TO" \
  > "$WORK/package.json"
( cd "$WORK" && runnode "$NPMCLI" install --package-lock-only --ignore-scripts \
    --registry="$REGISTRY" --no-audit --no-fund ) >/dev/null 2>>"$LOG" \
  || die "生成 $TO 锁文件失败（见 $LOG）" 2
runnode -e 'const fs=require("fs"),p=process.argv[1],v=process.argv[2];
const l=JSON.parse(fs.readFileSync(p,"utf8"));
l.name="engine-build"; l.version="0.0.0";
l.packages[""]={name:"engine-build",version:"0.0.0",dependencies:{"@deepseek-ai/dsh":v}};
fs.writeFileSync(p,JSON.stringify(l,null,2));' "$WORK/package-lock.json" "$TO" \
  || die "锁文件根条目修正失败" 2
log "   锁文件就绪"

# 2) 逐个补丁文件做三方合并
cd "$BASE_ASSETS/patches" || die "进不去补丁目录" 2
files="$(find . -type f | sed 's#^\./##' | sort)"
[ -n "$files" ] || die "基础补丁集是空的" 2

conflicts=0
total=0
for rel in $files; do
  if [ "$rel" = "$SKIP_FILE" ]; then
    log "   跳过（运行时注入）$rel"
    continue
  fi
  pkg="${rel%%/lib/*}"
  inner="lib/${rel#*/lib/}"
  [ "$pkg" != "$rel" ] || die "看不懂的补丁路径：$rel" 2

  oldv="$(runnode -e 'const l=require(process.argv[1]);const e=l.packages["node_modules/"+process.argv[2]];process.stdout.write(e?e.version:"")' "$BASE_ASSETS/package-lock.json" "$pkg")"
  newv="$(runnode -e 'const l=require(process.argv[1]);const e=l.packages["node_modules/"+process.argv[2]];process.stdout.write(e?e.version:"")' "$WORK/package-lock.json" "$pkg")"
  [ -n "$oldv" ] || die "基础锁文件里没有 $pkg" 2
  [ -n "$newv" ] || die "目标锁文件里没有 $pkg" 2

  old_f="$WORK/old-$total"; new_f="$WORK/new-$total"
  for pair in "$oldv:$old_f" "$newv:$new_f"; do
    ver="${pair%%:*}"; dst="${pair#*:}"
    ( cd "$WORK/tars" && runnode "$NPMCLI" pack --ignore-scripts --registry="$REGISTRY" "$pkg@$ver" ) \
      >/dev/null 2>>"$LOG" || die "npm pack $pkg@$ver 失败" 2
    tgz="$(ls -t "$WORK/tars"/*.tgz | head -1)"
    tar -xOzf "$tgz" "package/$inner" > "$dst" 2>>"$LOG" \
      || die "解不出 $inner（$pkg@$ver）" 2
    rm -f "$tgz"
  done

  mkdir -p "$(dirname "$OUT/patches/$rel")"
  runb diff3 -m "$BASE_ASSETS/patches/$rel" "$old_f" "$new_f" > "$OUT/patches/$rel" 2>>"$LOG"
  n="$(grep -c '^<<<<<<<' "$OUT/patches/$rel" 2>/dev/null || true)"
  total=$((total + 1))
  if [ "${n:-0}" -gt 0 ]; then
    log "   ⚠ 冲突 $rel（$n 块）"
    conflicts=$((conflicts + 1))
  else
    log "   ok   $rel（$oldv → $newv）"
  fi
done

[ "$conflicts" -eq 0 ] || die "有 $conflicts 个文件冲突，已中止（没有改动现网引擎）" 3

# 3) 标记 + 语法校验
for f in $(find "$OUT/patches" -name '*.js'); do
  runnode --check "$f" >/dev/null 2>>"$LOG" || die "语法校验失败：$f（没有改动现网引擎）" 4
done
markers=(
  "dsh-mobile N1|node-addon-require-builtin/lib/index.js"
  "dsh-mobile F3|@deepseek-ai/node-addon-system/lib/flock.js"
  "dsh-mobile exclusive publish|@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js"
  "dsh-mobile link->rename|@deepseek-ai/dsh-attachment-local/lib/index.js"
  "dshMobilePublishExclusive|@deepseek-ai/dsh-fs-local/lib/index.js"
)
for m in "${markers[@]}"; do
  pat="${m%%|*}"; file="$OUT/patches/${m#*|}"
  grep -q -- "$pat" "$file" 2>/dev/null || die "补丁标记丢失：$pat（$file）" 4
done
log "   标记 5/5，语法校验通过"

cp -f "$WORK/package-lock.json" "$OUT/package-lock.json" || die "写入锁文件失败" 2
cp -f "$WORK/package.json" "$OUT/package.json" || die "写入 package.json 失败" 2
log "✓ 补丁集迁移完成：$OUT（$total 个文件，0 冲突）"
