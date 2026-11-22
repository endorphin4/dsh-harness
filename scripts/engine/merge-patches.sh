#!/data/user/0/dev.dsh.harness/files/usr/bin/bash
# merge-patches.sh — 把旧版本的引擎补丁三方合并到新版本
#
# 用法: bash scripts/engine/merge-patches.sh <from-version> <to-version> [--force]
#
# 原理: 以旧版本干净 npm 树为 base、patches/<from>/ 里的补丁文件为 ours、
#       新版本干净树为 theirs，做 diff3 -m。零冲突即得到 patches/<to>/。
set -uo pipefail

FROM="${1:-}"; TO="${2:-}"; FORCE="${3:-}"
[ -n "$FROM" ] && [ -n "$TO" ] || { echo "用法: merge-patches.sh <from> <to> [--force]" >&2; exit 2; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PATCH_FROM="$ROOT/scripts/engine/patches/$FROM"
PATCH_TO="$ROOT/scripts/engine/patches/$TO"
REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"
WORK="${WORK:-$HOME/workspace/.build/engine-merge-$FROM-$TO}"
PREFIX_DIR="/data/user/0/dev.dsh.harness/files/usr"
export PATH="$PREFIX_DIR/bin:$PATH"

[ -d "$PATCH_FROM" ] || { echo "✗ 没有旧补丁目录: $PATCH_FROM" >&2; exit 1; }
if [ -d "$PATCH_TO" ] && [ "$FORCE" != "--force" ]; then
    echo "✗ $PATCH_TO 已存在；确认要覆盖请加 --force" >&2; exit 1
fi

install_tree() { # version dest
    local v="$1" d="$2"
    if [ -d "$d" ]; then echo "== 复用 $d"; return; fi
    echo "== npm 安装 $v（$REGISTRY）"
    mkdir -p "$d"
    ( cd "$d" && npm init -y >/dev/null 2>&1 && \
      npm install --registry="$REGISTRY" "@deepseek-ai/dsh@$v" \
        --omit=optional --ignore-scripts --no-audit --no-fund >/dev/null 2>&1 ) || {
        echo "✗ 安装 $v 失败" >&2; exit 1; }
}
install_tree "$FROM" "$WORK/old/npm"
install_tree "$TO"   "$WORK/new/npm"
OLD="$WORK/old/npm/node_modules"
NEW="$WORK/new/npm/node_modules"

mkdir -p "$PATCH_TO"
conflicts=0; total=0
while IFS= read -r -d '' f; do
    rel="${f#./}"
    src="$PATCH_FROM/$rel"
    a="$OLD/$rel"; b="$NEW/$rel"
    if [ ! -f "$a" ] || [ ! -f "$b" ]; then
        echo "✗ 缺少对照文件: $rel" >&2
        conflicts=$((conflicts+1)); continue
    fi
    mkdir -p "$PATCH_TO/$(dirname "$rel")"
    diff3 -m "$src" "$a" "$b" > "$PATCH_TO/$rel" 2>/dev/null || true
    n=$(grep -c '^<<<<<<<' "$PATCH_TO/$rel" 2>/dev/null || true)
    total=$((total+1))
    if [ "${n:-0}" -gt 0 ]; then
        echo "  ⚠ $rel  冲突块=$n"
        conflicts=$((conflicts+1))
    else
        echo "  ✓ $rel"
    fi
done < <(cd "$PATCH_FROM" && find . -type f -print0)

echo
if [ "$conflicts" -gt 0 ]; then
    echo "有 $conflicts 个文件存在冲突（共 $total）。"
    echo "→ 手工编辑 $PATCH_TO 下带 <<<<<<< 的文件，解决后重跑 build-engine.sh $TO"
    exit 2
fi
echo "全部 $total 个文件零冲突 → 可直接: bash scripts/build-engine.sh $TO"
