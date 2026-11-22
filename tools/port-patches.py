#!/usr/bin/env python3
"""把一份引擎补丁集三方合并到新的 DSH 版本。

用法:
    python3 tools/port-patches.py 0.2.2-rc.1 0.2.2-rc.5

原理与 scripts/engine/merge-patches.sh 相同，但只取补丁涉及的几个 npm 包
（npm pack），不装整棵依赖树：

    diff3 -m <补丁文件> <旧版本原文件> <新版本原文件>

产物写进 scripts/engine/patches/<to>/，并生成 scripts/engine/locks/<to>.json
（`npm install --package-lock-only` 得到，根包名固定为 engine-build）。

已知特例：@deepseek-ai/dsh-client-ui-sidebar-browser/lib/client.js 从 rc.5 起被
上游压缩成两行，整文件合并必然冲突 —— 这个文件的改动（地址栏搜索回退、
iframe allow-downloads、占位文案）已改由 App 运行时锚点注入
（EnginePatch.ensureBrowser），所以这里直接跳过它。
"""
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.environ.get("NPM_REGISTRY", "https://registry.npmmirror.com")
SKIP = {"@deepseek-ai/dsh-client-ui-sidebar-browser/lib/client.js"}


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def pack(pkg, ver, dest):
    r = run(["npm", "pack", "--registry=" + REGISTRY, "--pack-destination", dest,
             "%s@%s" % (pkg, ver)])
    if r.returncode != 0:
        raise SystemExit("npm pack %s@%s 失败:\n%s" % (pkg, ver, r.stderr[-800:]))
    return os.path.join(dest, r.stdout.strip().splitlines()[-1])


def tarball_name(pkg):
    return ("deepseek-ai-" + pkg.split("/", 1)[1]) if pkg.startswith("@") else pkg


def extract(tar, inner, out):
    with tarfile.open(tar) as t:
        for m in t.getmembers():
            if m.name == "package/" + inner and m.isfile():
                os.makedirs(os.path.dirname(out), exist_ok=True)
                with open(out, "wb") as f:
                    f.write(t.extractfile(m).read())
                return True
    return False


def lock_versions(lock_path, wanted):
    data = json.load(open(lock_path, encoding="utf-8"))["packages"]
    out = {}
    for pkg in wanted:
        e = data.get("node_modules/" + pkg)
        if e:
            out[pkg] = e["version"]
    return out


def make_lock(to_ver, lock_path):
    tmp = tempfile.mkdtemp(prefix="port-lock-")
    try:
        with open(os.path.join(tmp, "package.json"), "w") as f:
            json.dump({"name": "engine-build", "version": "0.0.0", "private": True,
                       "dependencies": {"@deepseek-ai/dsh": to_ver}}, f)
        r = run(["npm", "install", "--package-lock-only", "--registry=" + REGISTRY,
                 "@deepseek-ai/dsh@" + to_ver, "--no-audit", "--no-fund"], cwd=tmp)
        if r.returncode != 0:
            raise SystemExit("生成锁文件失败:\n" + r.stderr[-800:])
        lock = json.load(open(os.path.join(tmp, "package-lock.json"), encoding="utf-8"))
        lock["packages"][""] = {"name": "engine-build", "version": "0.0.0",
                                "dependencies": {"@deepseek-ai/dsh": to_ver}}
        lock["name"] = "engine-build"
        lock["version"] = "0.0.0"
        json.dump(lock, open(lock_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    src_ver, to_ver = sys.argv[1], sys.argv[2]
    patch_dir = os.path.join(ROOT, "scripts/engine/patches", src_ver)
    out_dir = os.path.join(ROOT, "scripts/engine/patches", to_ver)
    lock_from = os.path.join(ROOT, "scripts/engine/locks", src_ver + ".json")
    lock_to = os.path.join(ROOT, "scripts/engine/locks", to_ver + ".json")
    if not os.path.isdir(patch_dir):
        raise SystemExit("没有源补丁集: " + patch_dir)
    if not os.path.isfile(lock_to):
        print("== 生成 %s 锁文件" % to_ver)
        make_lock(to_ver, lock_to)

    files = []
    for base, _, names in os.walk(patch_dir):
        for n in names:
            full = os.path.join(base, n)
            files.append(os.path.relpath(full, patch_dir))
    pkgs = sorted({f.split("/lib/")[0] for f in files})
    old = lock_versions(lock_from, pkgs)
    new = lock_versions(lock_to, pkgs)
    print("== 补丁涉及包:", ", ".join("%s %s→%s" % (p, old.get(p), new.get(p)) for p in pkgs))

    work = tempfile.mkdtemp(prefix="port-patches-")
    conflicts, total = 0, 0
    try:
        tmp_out = out_dir + ".tmp"
        if os.path.isdir(tmp_out):
            shutil.rmtree(tmp_out)
        os.makedirs(tmp_out)
        for rel in files:
            if rel in SKIP:
                print("  跳过(运行时注入) " + rel)
                continue
            pkg = rel.split("/lib/")[0]
            inner = "lib/" + rel.split("/lib/", 1)[1]
            o, n = old.get(pkg), new.get(pkg)
            if not o or not n:
                print("  缺失包版本 %s，跳过 %s" % (pkg, rel))
                conflicts += 1
                continue
            bases = []
            for ver in (o, n):
                tar = pack(pkg, ver, work)
                dst = os.path.join(work, os.path.basename(tar)[:-4])
                if not extract(tar, inner, os.path.join(dst, "content")):
                    raise SystemExit("包里没有 %s（%s@%s）" % (inner, pkg, ver))
                bases.append(os.path.join(dst, "content"))
            dst = os.path.join(tmp_out, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            r = subprocess.run(["diff3", "-m", os.path.join(patch_dir, rel), bases[0], bases[1]],
                               capture_output=True)
            with open(dst, "wb") as f:
                f.write(r.stdout)
            blocks = r.stdout.count(b"<<<<<<<")
            total += 1
            if blocks:
                conflicts += 1
                print("  冲突 %s（%d 块）" % (rel, blocks))
            else:
                print("  ok   %s" % rel)
        if os.path.isdir(tmp_out):
            if os.path.isdir(out_dir):
                shutil.rmtree(out_dir)
            os.rename(tmp_out, out_dir)
    finally:
        shutil.rmtree(work, ignore_errors=True)

    print("\n== %d 个文件，%d 个冲突" % (total, conflicts))
    if conflicts:
        print("→ 人工处理冲突后再 bash build-apk.sh；或把改动改成运行时锚点注入")
        return 2
    print("→ 已生成 patches/%s 与 locks/%s.json" % (to_ver, to_ver))
    return 0


if __name__ == "__main__":
    sys.exit(main())
