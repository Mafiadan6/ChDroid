#!/system/bin/sh
# Ubuntu-Chroot v4.4 CLI installer, without module flashing or boot scripts.
# Mirrors META-INF/com/google/android/util_functions.sh from the release ZIP:
# the tools land in /data/local/ubuntu-chroot and the rootfs in its rootfs/.
#
# usage: install_ubuntu_chroot_cli.sh <release.zip> <busybox> <cache-dir>

ZIP="$1"
BUSYBOX="$2"
CACHE="$3"
BASE=/data/local/ubuntu-chroot
ROOTFS=$BASE/rootfs

say() { echo "[UC-INSTALL] $1"; }
fail() { echo "[UC-INSTALL] ERROR: $1" >&2; exit 1; }

[ -n "$ZIP" ] && [ -f "$ZIP" ] || fail "release ZIP not found"
[ -n "$BUSYBOX" ] && [ -x "$BUSYBOX" ] || fail "busybox not executable"
[ -n "$CACHE" ] || fail "cache directory not given"

mkdir -p "$BASE/bin" "$BASE/ota" "$ROOTFS" "$CACHE" || fail "could not create $BASE"

# The ZIP carries its own static busybox. Use it for every extraction after
# this point so a missing system busybox cannot break the install.
"$BUSYBOX" unzip -oj "$ZIP" 'tools/bin/busybox' -d "$BASE/bin" >/dev/null 2>&1 || \
    fail "could not extract tools/bin/busybox"
chmod 755 "$BASE/bin/busybox" || fail "could not chmod busybox"
BB=$BASE/bin/busybox

say "installing chroot manager"
"$BB" unzip -oj "$ZIP" \
    'tools/chroot.sh' \
    'tools/post_exec.sh' \
    'tools/start-hotspot' \
    'tools/sparsemgr.sh' \
    'tools/forward-nat.sh' \
    'tools/update-status.sh' \
    -d "$BASE" >/dev/null 2>&1 || fail "could not extract chroot tools"

"$BB" unzip -oj "$ZIP" 'tools/updater.sh' 'tools/updates.sh' -d "$BASE/ota" >/dev/null 2>&1 || \
    say "OTA tools not present (CLI install continues)"

chmod 755 "$BASE/chroot.sh" "$BASE/post_exec.sh" "$BASE/start-hotspot" \
    "$BASE/sparsemgr.sh" "$BASE/forward-nat.sh" "$BASE/update-status.sh" \
    "$BASE/ota/updater.sh" "$BASE/ota/updates.sh" 2>/dev/null

# Record the release version the same way the module installer does.
"$BB" unzip -oj "$ZIP" 'module.prop' -d "$CACHE" >/dev/null 2>&1
ver=$("$BB" grep '^versionCode=' "$CACHE/module.prop" 2>/dev/null | "$BB" cut -d= -f2)
[ -n "$ver" ] && echo "$ver" > "$BASE/version"
rm -f "$CACHE/module.prop"

# Skip a second extraction when a rootfs is already in place - that is user
# data, and the upstream installer refuses to overwrite it as well.
if [ -x "$ROOTFS/bin/sh" ] || [ -x "$ROOTFS/bin/busybox" ]; then
    say "rootfs already installed at $ROOTFS - keeping existing data"
else
    member=$("$BB" unzip -l "$ZIP" 2>/dev/null | "$BB" grep -E '\.tar\.gz$' | "$BB" head -1 | "$BB" rev | "$BB" cut -d' ' -f1 | "$BB" rev)
    [ -n "$member" ] || fail "no rootfs.tar.gz member in $ZIP"
    say "found rootfs member: $member"

    "$BB" unzip -oq "$ZIP" "$member" -d "$CACHE" || fail "could not extract $member from ZIP"
    say "extracting rootfs to $ROOTFS"
    "$BB" tar -xpf "$CACHE/$member" -C "$ROOTFS" || fail "rootfs extraction failed"
    rm -f "$CACHE/$member"
fi

# chroot.sh sets up namespaces, mounts and DNS itself. No container.config,
# no Droidspaces init, no boot service: that is the whole point of this path.
echo 1 > "$BASE/.ubuntu-chroot-cli"
say "ready: sh $BASE/chroot.sh start root"
