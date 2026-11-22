#!/usr/bin/env python3
"""写静态更新清单：dist/shell-latest.json（回环地址）与 shell/latest.json（GitHub 地址）。"""
import hashlib, json, os, sys, time

out, root = sys.argv[1], sys.argv[2]
version, code = sys.argv[3], sys.argv[4]
apk = os.path.join(out, "dshb-latest.apk")
size = os.path.getsize(apk)
h = hashlib.sha256()
with open(apk, "rb") as f:
    for chunk in iter(lambda: f.read(1 << 20), b""):
        h.update(chunk)
name = "dsh-harness-%s-arm64.apk" % version
gh = "https://github.com/endorphin4/dsh-harness/releases/download/v%s/%s" % (version, name)
base = {
    "version": version,
    "code": int(code or 0),
    "apkName": name,
    "size": size,
    "sha256": h.hexdigest(),
    "publishedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    "prerelease": False,
    "pageUrl": "https://github.com/endorphin4/dsh-harness/releases/tag/v%s" % version,
    "notes": "DSH Harness %s" % version,
}
local = dict(base, apkUrl="http://127.0.0.1:8099/dshb-latest.apk",
             sha256Url="http://127.0.0.1:8099/dshb-latest.apk.sha256")
remote = dict(base, apkUrl=gh, sha256Url=gh + ".sha256")
with open(os.path.join(out, "shell-latest.json"), "w", encoding="utf-8") as f:
    json.dump(local, f, ensure_ascii=False, indent=1)
os.makedirs(os.path.join(root, "shell"), exist_ok=True)
with open(os.path.join(root, "shell", "latest.json"), "w", encoding="utf-8") as f:
    json.dump(remote, f, ensure_ascii=False, indent=1)
print("  shell-latest.json %s (%s)" % (version, h.hexdigest()[:12]))
