#!/system/bin/sh
# dpkg 包装器（我们自己的版本，替换快照里那份）：
#   1. PREFIX / APT_CONFIG 指到本 App 的前缀
#   2. --instdir/--admindir 指到本 App 的目录，--force-script-chrootless 让 postinst 能跑
#   3. 安装拆两段：--unpack（只解包）→ 精确重定位（只处理这次装的包）→ --configure（才跑 postinst）
#      不这样做的话：postinst 的 shebang 写死 /data/data/com.termux/files/usr/bin/sh，
#      那个目录既读不到也不能 exec（EACCES），维护者脚本必然失败。
B="${TERMUX__PREFIX:-/data/data/com.termux/files/usr}"
export PREFIX="$B"
export APT_CONFIG="$B/etc/apt/apt.conf"
STAMP="$B/var/lib/dsh-relocate.stamp"

# dpkg 的 --instdir 语义是「把它当成 /」，所以 .deb 里写死的
# ./data/data/com.termux/files/usr/bin/xxx 会被解到 <前缀>/data/data/com.termux/files/usr/bin/xxx。
# 正常情况下我们建的等长别名会让它穿回 <前缀>/bin/xxx；
# 但只要别名没生效（旧版本建错位置、或 dpkg 把符号链接当目录重建），文件就会真的留在嵌套层里。
# 这个函数兜底：把嵌套层里的 usr / home 内容搬回真位置，再把空的嵌套树删掉。
flatten_nested() {
  N="$B/data/data/com.termux/files"
  [ -d "$N" ] || return 0
  [ -L "$B/data" ] && return 0
  if [ -d "$N/usr" ] && [ ! -L "$N/usr" ]; then
    for e in "$N"/usr/* "$N"/usr/.[!.]*; do
      [ -e "$e" ] || continue
      cp -a "$e" "$B"/ 2>/dev/null || true
    done
  fi
  if [ -d "$N/home" ] && [ ! -L "$N/home" ]; then
    for e in "$N"/home/* "$N"/home/.[!.]*; do
      [ -e "$e" ] || continue
      cp -a "$e" "$B/../home"/ 2>/dev/null || true
    done
  fi
  rm -rf "$B/data" 2>/dev/null || true
  echo "flatten: 已将嵌套前缀内容搬回 $B" >> "$B/var/log/dsh-relocate.log" 2>/dev/null || true
}

dpkg_run() {
  # dsh-mobile: filter-noise —— 空 .list 会让 dpkg 每次刷几十条 "files list file ... missing"，纯噪音。
  _E="$(mktemp "${TMPDIR:-$B/var/tmp}/dshd.XXXXXX" 2>/dev/null || echo "$B/var/tmp/dshd.$$")"
  "$B/bin/dpkg.real" --instdir="$B" --admindir="$B/var/lib/dpkg" --force-script-chrootless "$@" 2> "$_E"
  _rc=$?
  if [ -s "$_E" ]; then
    grep -v -e 'files list file for package' -e 'assuming package has no files currently installed' "$_E" >&2 || true
  fi
  rm -f "$_E"
  return $_rc
}

# 之前版本（1.1.3/1.1.4）拆包装到一半的包，postinst 的 shebang 还指着 /data/data/com.termux/...：
# 那个目录属于 Termux 应用，本 App 连穿过去都不行 ⇒ execve 直接 EACCES（"Permission denied"）。
# 所以每次调 dpkg 之前，先把 info/ 里所有维护者脚本重定位一遍（幂等，命中才写）。
pre_relocate_scripts() {
  [ -x "$B/bin/dsh-relocate" ] || return 0
  L="$B/var/lib/dsh-relocate.scripts.list"
  : > "$L"
  for f in "$B"/var/lib/dpkg/info/*.postinst "$B"/var/lib/dpkg/info/*.preinst \
           "$B"/var/lib/dpkg/info/*.prerm    "$B"/var/lib/dpkg/info/*.postrm; do
    [ -f "$f" ] || continue
    grep -qF '/data/data/com.termux' "$f" 2>/dev/null && echo "$f" >> "$L"
  done
  if [ -s "$L" ]; then
    mkdir -p "$B/var/log" 2>/dev/null
    "$B/bin/dsh-relocate" "$B" --list "$L" >> "$B/var/log/dsh-relocate.log" 2>&1 || true
  fi
  chmod 755 "$B"/var/lib/dpkg/info/*.postinst "$B"/var/lib/dpkg/info/*.preinst \
            "$B"/var/lib/dpkg/info/*.prerm    "$B"/var/lib/dpkg/info/*.postrm 2>/dev/null
  fix_script_env
}

# dpkg 会用 --force-script-chrootless，同时给维护者脚本塞一个 DPKG_ROOT=<instdir>。
# update-alternatives 这类工具认 DPKG_ROOT，会把它自己再拼到绝对路径前面 ——
# 实测报错：alternative path /data/data/dev.dsh.harness/files/usr/data/data/dev.dsh.harness/usr0/bin/nano doesn't exist
# 所以脚本开头统一 unset DPKG_ROOT，并把 admindir 指回本 App 的 apt 数据库。
fix_script_env() {
  for s in "$B"/var/lib/dpkg/info/*.postinst "$B"/var/lib/dpkg/info/*.preinst \
           "$B"/var/lib/dpkg/info/*.prerm    "$B"/var/lib/dpkg/info/*.postrm; do
    [ -f "$s" ] || continue
    grep -q 'dsh-mobile: unset DPKG_ROOT' "$s" 2>/dev/null && continue
    T="$s.dshm"
    { head -1 "$s"
      echo '# dsh-mobile: unset DPKG_ROOT'
      echo 'unset DPKG_ROOT'
      echo "export DPKG_ADMINDIR='$B/var/lib/dpkg'"
      tail -n +2 "$s"
    } > "$T" 2>/dev/null && cat "$T" > "$s" && rm -f "$T"
    chmod 755 "$s" 2>/dev/null
  done
}

# .list 里记的是 .deb 内路径（/data/data/com.termux/files/usr/...），装好后改成
# App 真实前缀：`dpkg -L` 才能直接 ls 到，卸载/升级也按真实路径走。
normalize_lists() {
  # dsh-mobile: 这个前缀串故意拆成两段拼接 —— Java 侧 ensureDpkgWrapper 会把整份
  # 脚本里的 /data/data/com.termux/files/usr 统一替换成 App 前缀，若写成连续字面量
  # 就会被一起换掉，导致 grep 找不到刚解包的 .list（真实病灶）。
  LEGACY="/data/data/com.termux""/files/usr"
  for f in "$B"/var/lib/dpkg/info/*.list; do
    [ -f "$f" ] || continue
    grep -qF "$LEGACY" "$f" 2>/dev/null || continue
    sed -i "s#$LEGACY#$B#g" "$f" || true
  done
}

# 只把「这次装的文件」重定位：从 .list 取真实文件，加上 4 个维护者脚本。
relocate_pkgs() {
  [ -x "$B/bin/dsh-relocate" ] || return 0
  LIST="$B/var/lib/dsh-relocate.list"
  : > "$LIST"
  for p in $1; do
    F="$B/var/lib/dpkg/info/$p.list"
    [ -f "$F" ] && grep -F '/data/data/com.termux' "$F" >> "$LIST" 2>/dev/null
    for s in postinst preinst prerm postrm; do
      [ -f "$B/var/lib/dpkg/info/$p.$s" ] && echo "$B/var/lib/dpkg/info/$p.$s" >> "$LIST"
    done
  done
  mkdir -p "$B/var/log" 2>/dev/null
  "$B/bin/dsh-relocate" "$B" --list "$LIST" >> "$B/var/log/dsh-relocate.log" 2>&1 || true
  chmod 755 "$B"/var/lib/dpkg/info/*.postinst "$B"/var/lib/dpkg/info/*.preinst \
            "$B"/var/lib/dpkg/info/*.prerm    "$B"/var/lib/dpkg/info/*.postrm 2>/dev/null
  touch "$STAMP" 2>/dev/null
}

# apt 的流程是「一次 --unpack，紧接着一次 --configure」；如果我们自己在 unpack 里又
# 跑一遍 configure -a，apt 的 status-fd 会看到两段不属于同一次事务的事件，报
# "APT had planned for dpkg to do more than it reported back"。所以只在用户直接
# `dpkg -i` 时才顺手 configure，`--unpack` 原样返回、交给 apt 下一步。
DO_CFG=1
case " $* " in
  *" --unpack "*) DO_CFG=0 ;;
esac
case " $* " in
  *" -i "*|*" --install "*|*" --unpack "*)
    PKGS=""
    for a in "$@"; do
      case "$a" in
        *.deb) b=$(basename "$a"); PKGS="$PKGS ${b%%_*}" ;;
      esac
    done
    ARGS=""
    for a in "$@"; do
      case "$a" in
        -i|--install) ARGS="$ARGS --unpack" ;;
        -a|--configure|--pending) : ;;
        *) ARGS="$ARGS '$a'" ;;
      esac
    done
    eval dpkg_run $ARGS
    rc=$?
    flatten_nested
    relocate_pkgs "$PKGS"
    normalize_lists
    pre_relocate_scripts
    [ "$rc" -ne 0 ] && exit "$rc"
    [ "$DO_CFG" -eq 1 ] || exit 0
    dpkg_run --configure -a
    exit $?
    ;;
esac

flatten_nested
pre_relocate_scripts
dpkg_run "$@"
normalize_lists
rc=$?
relocate_pkgs ""
exit "$rc"
