#!/usr/bin/env python3
"""
把任意目录树里硬编码的 Termux 前缀换成等长的替身路径。

为什么必须等长：ELF 用的是"字符串表 + 偏移"的结构，路径长度一变，
后续所有偏移都会错位，二进制直接损坏。等长替换 = 原地换字符，偏移不变。

用法：
  relocate.py <tree> [--old A] [--new B] [--old-home A2] [--new-home B2]
                [--since <stamp-file>] [--dry] [-q]
"""
import argparse
import os
import sys

OLD_USR = b"/data/data/com.termux/files/usr"
OLD_HOME = b"/data/data/com.termux/files/home"
# 默认替身：等长，且由 App 在 data 目录根上创建同名符号链接指向真实目录
NEW_USR = b"/data/data/dev.dsh.harness/usr0"
NEW_HOME = b"/data/data/dev.dsh.harness/home0"

MAX = 96 * 1024 * 1024          # 单文件上限，超过就不碰
SKIP_DIRS = {".git", "__pycache__", "node_modules"}


def iter_files(tree, stamp):
    for root, dirs, files in os.walk(tree):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            p = os.path.join(root, name)
            try:
                st = os.lstat(p)
            except OSError:
                continue
            if not os.path.isfile(p) or os.path.islink(p):
                continue
            if st.st_size == 0 or st.st_size > MAX:
                continue
            if stamp is not None and st.st_mtime <= stamp:
                continue
            yield p, st


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tree")
    ap.add_argument("--old", default=OLD_USR.decode())
    ap.add_argument("--new", default=NEW_USR.decode())
    ap.add_argument("--old-home", default=OLD_HOME.decode())
    ap.add_argument("--new-home", default=NEW_HOME.decode())
    ap.add_argument("--since", default=None, help="只处理 mtime 晚于该标记文件的文件")
    ap.add_argument("--dry", action="store_true")
    ap.add_argument("-q", "--quiet", action="store_true")
    a = ap.parse_args()

    pairs = []
    for o, n in ((a.old, a.new), (a.old_home, a.new_home)):
        ob, nb = o.encode(), n.encode()
        if len(ob) != len(nb):
            print(f"[relocate] 拒绝：长度不等 {len(ob)} != {len(nb)} ({o})", file=sys.stderr)
            return 2
        pairs.append((ob, nb))

    stamp = None
    if a.since and os.path.exists(a.since):
        stamp = os.path.getmtime(a.since)

    changed = skipped = 0
    for p, st in iter_files(a.tree, stamp):
        try:
            with open(p, "rb") as f:
                data = f.read()
        except OSError:
            continue
        if not any(o in data for o, _ in pairs):
            continue
        new = data
        for o, n in pairs:
            new = new.replace(o, n)
        changed += 1
        if a.dry:
            continue
        try:
            mode = st.st_mode
            tmp = p + ".reloc"
            with open(tmp, "wb") as f:
                f.write(new)
            os.chmod(tmp, mode)
            os.replace(tmp, p)
        except OSError as e:
            skipped += 1
            print(f"[relocate] 跳过 {p}: {e}", file=sys.stderr)

    if not a.quiet:
        print(f"[relocate] 处理 {changed} 个文件" + (f"，失败 {skipped}" if skipped else ""))
    return 0 if skipped == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
