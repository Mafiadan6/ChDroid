#!/system/bin/sh
# ChDroid rootfs installer. Installs a clean Ubuntu chroot release: a plain
# rootfs tarball plus chroot tools, directly to /data/local/ubuntu-chroot.
# No module flashing, no META-INF, no module.prop, no boot scripts, no root
# method detection — that logic is not part of this build.
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

# Record the release version from our own manifest. No module.prop anywhere.
"$BB" unzip -oj "$ZIP" 'manifest.json' -d "$CACHE" >/dev/null 2>&1
ver=$("$BB" grep -o '"versionCode"[[:space:]]*:[[:space:]]*[0-9]*' "$CACHE/manifest.json" 2>/dev/null | "$BB" grep -o '[0-9]*$')
[ -n "$ver" ] && echo "$ver" > "$BASE/version"
rm -f "$CACHE/manifest.json"

# Never overwrite an existing rootfs — that is user data.
if [ -x "$ROOTFS/bin/sh" ] || [ -x "$ROOTFS/bin/busybox" ]; then
    say "rootfs already installed at $ROOTFS - keeping existing data"
else
    member=$("$BB" unzip -l "$ZIP" 2>/dev/null | "$BB" grep -E 'rootfs\.tar\.gz$' | "$BB" head -1 | "$BB" rev | "$BB" cut -d' ' -f1 | "$BB" rev)
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
