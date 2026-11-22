#!/data/user/0/dev.dsh.harness/files/usr/bin/bash
# release-engine.sh — 发布引擎包并更新 engine/latest.json
#
# 用法:
#   bash scripts/release-engine.sh <version> [--draft]
#
# 前置:
#   * dist/dsh-engine-<version>-android-arm64.tar.gz(.sha256) 已由 build-engine.sh 产出
#   * token: $GH_TOKEN 或 ~/.config/dsh/github.token（Contents: Read/Write）
set -euo pipefail

VERSION="${1:-}"
[ -n "$VERSION" ] || { echo "用法: release-engine.sh <version> [--draft]" >&2; exit 2; }
DRAFT="false"; [ "${2:-}" = "--draft" ] && DRAFT="true"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OWNER="${GH_OWNER:-endorphin4}"
REPO="${GH_REPO:-dsh-harness}"
TOK="${GH_TOKEN:-$(cat "$HOME/.config/dsh/github.token" 2>/dev/null || true)}"
[ -n "$TOK" ] || { echo "✗ 缺少 token（设置 GH_TOKEN 或写 ~/.config/dsh/github.token）" >&2; exit 1; }

ASSET="dsh-engine-$VERSION-android-arm64.tar.gz"
DIST="$ROOT/dist"
[ -f "$DIST/$ASSET" ] || { echo "✗ 缺少 $DIST/$ASSET，先跑 build-engine.sh" >&2; exit 1; }
SHELL_VER="$(sed -n 's/^NAME=//p' "$ROOT/version.txt" 2>/dev/null | head -1)"
SHA="$(cut -d' ' -f1 < "$DIST/$ASSET.sha256")"
SIZE=$(stat -c%s "$DIST/$ASSET")
TAG="engine-v$VERSION"
API="https://api.github.com/repos/$OWNER/$REPO"

api() { curl -sS -H "Authorization: Bearer $TOK" -H "Accept: application/vnd.github+json" "$@"; }

echo "== 发布 $TAG ($((SIZE/1048576)) MB, sha256 ${SHA:0:16}…)"

# 1) 建 release（已存在则复用，保证可重跑）
BODY=$(python3 - "$VERSION" "$SHA" <<'PY'
import json, sys
v, sha = sys.argv[1], sys.argv[2]
print(json.dumps({
    "tag_name": f"engine-v{v}",
    "name": f"DSH Engine v{v} (android-arm64)",
    "prerelease": True,
    "draft": False,
    "body": f"""Android arm64 引擎包（已打移动端补丁：N1 / F3 / F7 / F8）。

安装：App 内「检查更新 → 更新引擎」，或
```
cd ~/试玩 && bash dsh-engine-update.sh {v} <tar地址> <sha地址>
```

### 校验
```
sha256: {sha}
```
"""
}))
PY
)
REL_ID=$(api -X POST "$API/releases" -d "$BODY" | python3 -c "import sys,json;print(json.load(sys.stdin).get('id') or '')")
if [ -z "$REL_ID" ]; then
    REL_ID=$(api "$API/releases/tags/$TAG" | python3 -c "import sys,json;print(json.load(sys.stdin).get('id') or '')")
fi
[ -n "$REL_ID" ] || { echo "✗ 建 release 失败" >&2; exit 1; }
echo "  release id=$REL_ID"

# 2) 上传（同名先删）
for f in "$DIST/$ASSET" "$DIST/$ASSET.sha256"; do
    NAME="$(basename "$f")"
    api "$API/releases/$REL_ID/assets" | python3 -c "
import json,sys
for a in json.load(sys.stdin):
    if a['name']=='$NAME': print(a['id'])
" | while read -r aid; do
        [ -n "$aid" ] && api -X DELETE "$API/releases/assets/$aid" >/dev/null && echo "  删旧资产 $NAME"
    done
    CODE=$(curl -s -m 900 -X POST -H "Authorization: Bearer $TOK" \
        -H "Content-Type: application/octet-stream" --data-binary "@$f" \
        "https://uploads.github.com/repos/$OWNER/$REPO/releases/$REL_ID/assets?name=$NAME" \
        -o /dev/null -w '%{http_code}' || echo 000)
    [ "$CODE" = "201" ] || { echo "✗ 上传失败 $NAME (HTTP $CODE)" >&2; exit 1; }
    echo "  上传 $NAME"
done

# 3) 更新 engine/latest.json
echo "== 更新 engine/latest.json"
CUR=$(api "$API/contents/engine/latest.json")
CUR_SHA=$(printf '%s' "$CUR" | python3 -c "import sys,json;print(json.load(sys.stdin).get('sha') or '')")
NEW_JSON=$(python3 - "$VERSION" "$SHA" "$TAG" "$SHELL_VER" <<'PY'
import json, sys
v, sha, tag = sys.argv[1:4]
shell = sys.argv[4] if len(sys.argv) > 4 else ""
base = f"https://github.com/endorphin4/dsh-harness/releases/download/{tag}"
doc = {
    "version": v,
    "pageUrl": f"https://github.com/endorphin4/dsh-harness/releases/tag/{tag}",
    "publishedAt": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "tar": f"{base}/dsh-engine-{v}-android-arm64.tar.gz",
    "sha256": f"{base}/dsh-engine-{v}-android-arm64.tar.gz.sha256",
    "size": int(__import__("os").stat(__import__("os").environ.get("DIST_ASSET","")).st_size) if __import__("os").environ.get("DIST_ASSET") else 0
}
if shell:
    doc["minShellVersion"] = shell
print(json.dumps(doc, indent=2, ensure_ascii=False))
PY
)
# size 用真实值覆盖
NEW_JSON=$(printf '%s' "$NEW_JSON" | SIZE="$SIZE" python3 -c "import sys,json,os;d=json.load(sys.stdin);d['size']=int(os.environ['SIZE']);print(json.dumps(d,indent=2,ensure_ascii=False))")
CONTENT=$(printf '%s' "$NEW_JSON" | base64 -w0)
api -X PUT "$API/contents/engine/latest.json" \
    -d "{\"message\":\"engine: publish $VERSION\",\"content\":\"$CONTENT\",\"sha\":\"$CUR_SHA\"}" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print('  latest.json 已更新:', d.get('commit',{}).get('sha','?')[:10] if 'commit' in d else d.get('message'))"

echo "✓ https://github.com/$OWNER/$REPO/releases/tag/$TAG"
