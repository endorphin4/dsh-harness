#!/usr/bin/env python3
"""从 Termux 仓库重建载荷里 75 个基础包的 dpkg .list（真文件清单）。

为什么要这个：载荷里的 info/*.list 是空的，于是
  - 每次 apt 装包 dpkg 刷几十条 "files list file ... missing"
  - dpkg -L <pkg> 什么都查不到
  - 我们自己的"孤儿二进制"判定只能靠猜
本脚本按 status 里的 Package/Version 对到仓库 Packages 索引，下载对应 .deb，
从 data.tar.* 里读出成员路径，写成 dpkg 期望的绝对路径形式。
"""
import gzip, io, os, re, subprocess, sys, tarfile, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK = os.path.join(os.path.dirname(ROOT), ".dpkg-lists")
PAYLOAD = os.path.join(ROOT, "dist", "dshb-rootfs.tar.gz")
BASE = "https://packages.termux.dev/apt/termux-main"
PREFIX = "/data/data/com.termux/files/usr"

def run(cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)

def read_status(full):
    with open(os.path.join(full, "usr/var/lib/dpkg/status"), encoding="utf-8", errors="replace") as f:
        text = f.read()
    out = []
    for block in text.split("\n\n"):
        pkg = re.search(r"^Package: (.+)$", block, re.M)
        ver = re.search(r"^Version: (.+)$", block, re.M)
        if pkg and ver:
            out.append((pkg.group(1).strip(), ver.group(1).strip()))
    return out

def fetch_packages():
    gz = urllib.request.urlopen(BASE + "/dists/stable/main/binary-aarch64/Packages.gz", timeout=60).read()
    text = gzip.decompress(gz).decode("utf-8", "replace")
    index = {}
    for block in text.split("\n\n"):
        p = re.search(r"^Package: (.+)$", block, re.M)
        v = re.search(r"^Version: (.+)$", block, re.M)
        f = re.search(r"^Filename: (.+)$", block, re.M)
        if p and v and f:
            index.setdefault(p.group(1).strip(), {})[v.group(1).strip()] = f.group(1).strip()
    return index

def ar_members(path):
    """极简 ar 解析：返回 [(name, bytes)]，用于取 data.tar.*"""
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"!<arch>\n":
        raise ValueError("not ar: " + path)
    off, out = 8, []
    while off + 60 <= len(data):
        hdr = data[off:off+60]
        name = hdr[0:16].decode("ascii", "replace").strip().rstrip("/")
        size = int(hdr[48:58].decode("ascii").strip() or 0)
        body = data[off+60:off+60+size]
        out.append((name, body))
        off += 60 + size + (size & 1)
    return out

def deb_members(deb):
    for name, body in ar_members(deb):
        if name.startswith("data.tar"):
            if name.endswith(".xz"):   stream = io.BytesIO(body); mode = "r:xz"
            elif name.endswith(".gz"): stream = io.BytesIO(body); mode = "r:gz"
            elif name.endswith(".zst"):
                import subprocess as sp
                dec = sp.run(["zstd", "-dc"], input=body, stdout=sp.PIPE, check=True).stdout
                stream = io.BytesIO(dec); mode = "r:"
            else:                      stream = io.BytesIO(body); mode = "r:"
            names = []
            with tarfile.open(fileobj=stream, mode=mode) as tf:
                for m in tf.getmembers():
                    n = m.name
                    if n in (".", "./"): continue
                    if n.startswith("./"): n = n[2:]
                    # data.tar 里是 data/data/com.termux/files/usr/...
                    i = n.find("data/data/com.termux/files/")
                    if i >= 0:
                        rel = n[i + len("data/data/com.termux/files/"):]
                        names.append(PREFIX + "/" + rel if rel else PREFIX)
                    else:
                        names.append("/" + n)
            return names
    raise ValueError("no data.tar in " + deb)

def main():
    os.makedirs(os.path.join(WORK, "debs"), exist_ok=True)
    os.makedirs(os.path.join(WORK, "lists"), exist_ok=True)
    full = os.path.join(WORK, "full")
    if not os.path.isdir(os.path.join(full, "usr")):
        print("[1/4] 解包载荷 →", full, flush=True)
        os.makedirs(full, exist_ok=True)
        run(["tar", "-xzf", PAYLOAD, "-C", full])
    print("[2/4] 读 status / 拉 Packages 索引", flush=True)
    pkgs = read_status(full)
    index = fetch_packages()
    print("    status 里 %d 个包，索引里 %d 个包名" % (len(pkgs), len(index)), flush=True)
    ok = miss = fail = 0
    for name, ver in pkgs:
        out = os.path.join(WORK, "lists", name + ".list")
        if os.path.isfile(out) and os.path.getsize(out) > 0:
            ok += 1; continue
        # status 里的版本可能带 epoch（1:2026.09.25），索引里通常没有
        plain = ver.split(":", 1)[1] if ":" in ver else ver
        fn = index.get(name, {}).get(ver) or index.get(name, {}).get(plain)
        approx = False
        if not fn and name in index and index[name]:
            # 仓库已经滚到更新版本：退而用仓库里该包的最新一份清单（文件集合几乎一致）
            fn = list(index[name].values())[0]
            approx = True
        if not fn:
            miss += 1
            print("   跳过（索引里没有 %s %s）" % (name, ver), flush=True)
            continue
        url = BASE + "/" + fn
        deb = os.path.join(WORK, "debs", name + ".deb")
        try:
            if not os.path.isfile(deb):
                urllib.request.urlretrieve(url, deb)
            names = deb_members(deb)
            with open(out, "w", encoding="utf-8") as f:
                f.write("\n".join(names) + "\n")
            ok += 1
            print("   %-28s %s%s → %d 条" % (name, ver, "（版本近似）" if approx else "", len(names)), flush=True)
        except Exception as e:
            fail += 1
            print("   失败 %s: %s" % (name, e), flush=True)
    print("[3/4] 写入 info/*.list（ok=%d 跳过=%d 失败=%d）" % (ok, miss, fail), flush=True)
    info = os.path.join(full, "usr/var/lib/dpkg/info")
    os.makedirs(info, exist_ok=True)
    copied = 0
    for fn in os.listdir(os.path.join(WORK, "lists")):
        if not fn.endswith(".list"): continue
        src = os.path.join(WORK, "lists", fn)
        if os.path.getsize(src) == 0: continue
        run(["cp", "-f", src, os.path.join(info, fn)])
        copied += 1
    print("   已写入 %d 个 .list" % copied, flush=True)
    print("[4/4] 重打载荷", flush=True)
    run(["tar", "-czf", PAYLOAD, "-C", full, "."])
    print("完成：%s %.1f MB" % (PAYLOAD, os.path.getsize(PAYLOAD)/1048576), flush=True)

if __name__ == "__main__":
    main()
