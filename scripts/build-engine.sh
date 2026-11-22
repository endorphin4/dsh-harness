#!/data/user/0/dev.dsh.harness/files/usr/bin/bash
# build-engine.sh — 从 npm 构建带 Android 补丁的引擎包
#
# 用法:
#   bash scripts/build-engine.sh <version> [--from-existing <node_modules>]
#
# 产物:
#   dist/dsh-engine-<version>-android-arm64.tar.gz
#   dist/dsh-engine-<version>-android-arm64.tar.gz.sha256
#
# 流程: npm 安装 → 覆盖 patches/<version>/ → 校验标记 → --version 冒烟
#       → 空 HOME 启动 3099 冒烟(401) → 同构打包 → sha256
set -euo pipefail

VERSION="${1:-}"
[ -n "$VERSION" ] || { echo "用法: build-engine.sh <version> [--from-existing <node_modules>]" >&2; exit 2; }
shift
FROM_EXISTING=""
if [ "${1:-}" = "--from-existing" ]; then FROM_EXISTING="${2:-}"; fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PATCH_DIR="$ROOT/scripts/engine/patches/$VERSION"
WORK="${WORK:-$HOME/workspace/.build/engine-$VERSION}"
DIST="$ROOT/dist"
REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"

PREFIX_DIR="/data/user/0/dev.dsh.harness/files/usr"
export PATH="$PREFIX_DIR/bin:$PATH"
export GIT_CONFIG_NOSYSTEM=1

[ -d "$PATCH_DIR" ] || { echo "✗ 没有 $VERSION 的补丁目录: $PATCH_DIR" >&2; exit 1; }

echo "== 引擎构建 $VERSION"
mkdir -p "$WORK" "$DIST"

if [ -n "$FROM_EXISTING" ]; then
    echo "== 复用 node_modules: $FROM_EXISTING"
    MODS="$FROM_EXISTING"
else
    echo "== 安装依赖（$REGISTRY）"
    rm -rf "$WORK/npm"; mkdir -p "$WORK/npm"
    printf '{"name":"engine-build","private":true,"version":"0.0.0","dependencies":{"@deepseek-ai/dsh":"%s"}}\n' "$VERSION" > "$WORK/npm/package.json"
    LOCK="$ROOT/scripts/engine/locks/$VERSION.json"
    if [ -f "$LOCK" ]; then
        echo "   使用锁文件 locks/$(basename "$LOCK")（npm ci，可复现）"
        cp "$LOCK" "$WORK/npm/package-lock.json"
        ( cd "$WORK/npm" && npm ci --registry="$REGISTRY" \
            --omit=optional --ignore-scripts --no-audit --no-fund )
    else
        echo "   ⚠ 没有锁文件，退回 npm install（产物不可复现）"
        ( cd "$WORK/npm" && npm install --registry="$REGISTRY" \
            --omit=optional --ignore-scripts --no-audit --no-fund )
        mkdir -p "$ROOT/scripts/engine/locks"
        cp "$WORK/npm/package-lock.json" "$LOCK"
        echo "   已生成锁文件 scripts/engine/locks/$VERSION.json（请提交）"
    fi
    MODS="$WORK/npm/node_modules"
fi

echo "== 覆盖补丁"
( cd "$PATCH_DIR" && find . -type f -print0 | while IFS= read -r -d '' f; do
    rel="${f#./}"
    mkdir -p "$MODS/$(dirname "$rel")"
    cp -f "$f" "$MODS/$rel"
    echo "   $rel"
done )

echo "== 校验补丁标记"
grep -q "dsh-mobile N1" "$MODS/node-addon-require-builtin/lib/index.js" || { echo "✗ N1 缺失" >&2; exit 1; }
grep -q "dsh-mobile F3" "$MODS/@deepseek-ai/node-addon-system/lib/flock.js" || { echo "✗ F3 缺失" >&2; exit 1; }
grep -q "dsh-mobile exclusive publish" "$MODS/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js" || { echo "✗ F7 缺失" >&2; exit 1; }
grep -q "dsh-mobile link->rename" "$MODS/@deepseek-ai/dsh-attachment-local/lib/index.js" || { echo "✗ attachment 缺失" >&2; exit 1; }
grep -q "dshMobilePublishExclusive" "$MODS/@deepseek-ai/dsh-fs-local/lib/index.js" || { echo "✗ F8 缺失" >&2; exit 1; }

BIN="$MODS/@deepseek-ai/dsh/lib/bin.js"
echo "== 冒烟 1/2: --version"
node --expose-internals "$BIN" --version | grep -qx "$VERSION" || { echo "✗ 版本不符" >&2; exit 1; }

echo "== 冒烟 2/2: 空 HOME 启动 3099"
SMOKE_HOME="$WORK/smoke-home"
rm -rf "$SMOKE_HOME"; mkdir -p "$SMOKE_HOME"
LOG="$WORK/smoke.log"
( DSH_HOME="$SMOKE_HOME" timeout 45 node --expose-internals "$BIN" \
    --profile web --host 127.0.0.1 --port 3099 --no-open > "$LOG" 2>&1 || true ) &
code=""
for _ in $(seq 1 30); do
    code=$(curl -s -o /dev/null -m 2 -w '%{http_code}' http://127.0.0.1:3099/ 2>/dev/null || true)
    [ "$code" = "401" ] && break
    sleep 1
done
pkill -f "port 3099" 2>/dev/null || true
[ "$code" = "401" ] || { echo "✗ 启动冒烟失败（HTTP ${code:-000}）" >&2; tail -20 "$LOG" >&2; exit 1; }
echo "   OK（HTTP 401）"

echo "== 打包（同构: @deepseek-ai/dsh + node_modules）"
STAGE="$WORK/stage"
rm -rf "$STAGE"; mkdir -p "$STAGE/@deepseek-ai/dsh/node_modules/@deepseek-ai"
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

ASSET="dsh-engine-$VERSION-android-arm64.tar.gz"
tar -czf "$DIST/$ASSET" -C "$STAGE" .
( cd "$DIST" && sha256sum "$ASSET" | awk '{print $1}' > "$ASSET.sha256" )
SIZE=$(stat -c%s "$DIST/$ASSET")
echo "✓ $DIST/$ASSET  $((SIZE/1048576)) MB"
echo "  sha256 $(cut -c1-32 < "$DIST/$ASSET.sha256")…"
