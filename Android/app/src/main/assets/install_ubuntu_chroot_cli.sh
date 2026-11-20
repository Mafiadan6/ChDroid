#!/system/bin/sh
# ChDroid rootfs installer. Installs a clean rootfs release: a plain rootfs
# tarball plus chroot tools, directly to /data/local/ubuntu-chroot.
# No module flashing, no META-INF, no module.prop, no boot scripts, no root
# method detection — that logic is not part of this build.
#
# usage: install_ubuntu_chroot_cli.sh <release.zip> <busybox> <cache-dir>

ZIP="$1"
BUSYBOX="$2"
CACHE="$3"
BASE=/data/local/ubuntu-chroot
ROOTFS=$BASE/rootfs
DISTRO_FILE=$BASE/distro-id

say() { echo "[UC-INSTALL] $1"; }
fail() { echo "[UC-INSTALL] ERROR: $1" >&2; exit 1; }

[ -n "$ZIP" ] && [ -f "$ZIP" ] || fail "release ZIP not found"
[ -n "$BUSYBOX" ] && [ -x "$BUSYBOX" ] || fail "busybox not executable"
[ -n "$CACHE" ] || fail "cache directory not given"

mkdir -p "$BASE/bin" "$ROOTFS" "$CACHE" || fail "could not create $BASE"

# Use the bundled static busybox for every extraction once it is available,
# so a missing system busybox cannot break the install.
"$BUSYBOX" unzip -oj "$ZIP" 'bin/busybox' -d "$BASE/bin" >/dev/null 2>&1 || \
    fail "could not extract bin/busybox"
chmod 755 "$BASE/bin/busybox" || fail "could not chmod busybox"
BB=$BASE/bin/busybox

say "installing chroot tools"
"$BB" unzip -oj "$ZIP" \
    'chroot.sh' \
    'post_exec.sh' \
    'start-hotspot' \
    'sparsemgr.sh' \
    'forward-nat.sh' \
    -d "$BASE" >/dev/null 2>&1 || fail "could not extract chroot tools"
chmod 755 "$BASE/chroot.sh" "$BASE/post_exec.sh" "$BASE/start-hotspot" \
    "$BASE/sparsemgr.sh" "$BASE/forward-nat.sh" 2>/dev/null

# Release metadata comes from our own manifest. No module.prop anywhere.
"$BB" unzip -oj "$ZIP" 'manifest.json' -d "$CACHE" >/dev/null 2>&1
ver=$("$BB" grep -o '"versionCode"[[:space:]]*:[[:space:]]*[0-9]*' "$CACHE/manifest.json" 2>/dev/null | "$BB" grep -o '[0-9]*$')
[ -n "$ver" ] && echo "$ver" > "$BASE/version"
distro=$("$BB" grep -o '"id"[[:space:]]*:[[:space:]]*"[^"]*"' "$CACHE/manifest.json" 2>/dev/null | "$BB" head -1 | "$BB" cut -d'"' -f4)
[ -n "$distro" ] || distro=unknown
# Manifest ids are slugs (chdroid-ubuntu-2404-cli). The identity script records a
# short family (ubuntu) in distro-id, so compare families or every reinstall of
# the same distro looks like a switch.
family=$distro
case "$distro" in
    *[Uu]buntu*) family=ubuntu ;;
    *[Aa]rch*)   family=arch ;;
    *[Dd]ebian*) family=debian ;;
    *[Aa]lpine*) family=alpine ;;
esac
rm -f "$CACHE/manifest.json"

# Decide what to do with an existing rootfs. Same distro = keep user data.
# Different distro = stop the chroot, clear the old rootfs, install the new one.
existing=$(cat "$DISTRO_FILE" 2>/dev/null)
case "$existing" in
    *[Uu]buntu*) existing=ubuntu ;;
    *[Aa]rch*)   existing=arch ;;
    *[Dd]ebian*) existing=debian ;;
    *[Aa]lpine*) existing=alpine ;;
esac
if [ -x "$ROOTFS/bin/sh" ] || [ -x "$ROOTFS/bin/busybox" ]; then
    if [ "$existing" = "$family" ]; then
        say "rootfs $family already installed - keeping existing data"
        echo 1 > "$BASE/.ubuntu-chroot-cli"
        say "ready: sh $BASE/chroot.sh start root"
        exit 0
    fi
    say "switching rootfs: ${existing:-unknown} -> $family"
    if [ -f "$BASE/chroot.sh" ]; then
        sh "$BASE/chroot.sh" stop >/dev/null 2>&1 || true
    fi
    # chroot.sh stop can leave a shell holding the old rootfs open. Kill every
    # process whose root is the old tree, then the namespace holder, then drop
    # any mount still under it. Without this the mount guard below aborted the
    # install and He stayed stuck with the previous distro.
    for procdir in /proc/[0-9]*; do
        r=$(readlink "$procdir/root" 2>/dev/null) || continue
        [ "$r" = "$ROOTFS" ] && kill -9 "${procdir##*/}" 2>/dev/null
    done
    p=$(cat "$BASE/holder.pid" 2>/dev/null)
    [ -n "$p" ] && kill -9 "$p" 2>/dev/null
    rm -f "$BASE/holder.pid" "$BASE/holder.pid.flags"
    while read -r dev mp rest; do
        case "$mp" in
            "$ROOTFS"|"$ROOTFS"/*) umount -l "$mp" 2>/dev/null ;;
        esac
    done < /proc/mounts
    if grep -q "$ROOTFS" /proc/mounts 2>/dev/null; then
        fail "old rootfs is still mounted - run 'sh $BASE/chroot.sh stop' and retry"
    fi
    rm -rf "$ROOTFS" "$BASE/rootfs.img" || fail "could not remove old rootfs"
    mkdir -p "$ROOTFS" || fail "could not recreate $ROOTFS"
fi

# Find the rootfs tarball (.tar.gz or .tar.xz) in the clean ZIP.
member=$("$BB" unzip -l "$ZIP" 2>/dev/null | "$BB" grep -E 'rootfs\.tar\.(gz|xz)$' | "$BB" head -1 | "$BB" rev | "$BB" cut -d' ' -f1 | "$BB" rev)
[ -n "$member" ] || fail "no rootfs.tar.gz or rootfs.tar.xz member in $ZIP"
say "found rootfs member: $member"
"$BB" unzip -oq "$ZIP" "$member" -d "$CACHE" || fail "could not extract $member from ZIP"

say "extracting rootfs to $ROOTFS"
case "$member" in
    *.tar.xz)
        "$BB" tar -xJf "$CACHE/$member" -C "$ROOTFS" || \
        tar -xJf "$CACHE/$member" -C "$ROOTFS" || \
        "$BB" tar -xpf "$CACHE/$member" -C "$ROOTFS" || fail "xz rootfs extraction failed"
        ;;
    *)
        "$BB" tar -xzf "$CACHE/$member" -C "$ROOTFS" || \
        "$BB" tar -xpf "$CACHE/$member" -C "$ROOTFS" || fail "gzip rootfs extraction failed"
        ;;
esac
rm -f "$CACHE/$member"
echo "$family" > "$DISTRO_FILE"

# chroot.sh sets up namespaces, mounts and DNS itself. No container.config,
# no Droidspaces init, no boot service: that is the whole point of this path.
echo 1 > "$BASE/.ubuntu-chroot-cli"
say "ready: sh $BASE/chroot.sh start root"
