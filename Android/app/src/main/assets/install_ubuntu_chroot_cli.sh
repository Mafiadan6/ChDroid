#!/system/bin/sh
# ChDroid rootfs installer. Installs a clean rootfs release to a directory named
# after the setup choice, for example /data/local/arch-chroot.
# No module flashing, no META-INF, no module.prop, no boot scripts, no root
# method detection — that logic is not part of this build.
#
# usage: install_ubuntu_chroot_cli.sh <release.zip> <busybox> <cache-dir> [setup-name]

ZIP="$1"
BUSYBOX="$2"
CACHE="$3"
NAME="$4"
POINTER=/data/local/chdroid-active-rootfs
LEGACY=/data/local/ubuntu-chroot

say() { echo "[UC-INSTALL] $1"; }
fail() { echo "[UC-INSTALL] ERROR: $1" >&2; exit 1; }

[ -n "$ZIP" ] && [ -f "$ZIP" ] || fail "release ZIP not found"
[ -n "$BUSYBOX" ] && [ -x "$BUSYBOX" ] || fail "busybox not executable"
[ -n "$CACHE" ] || fail "cache directory not given"

# Read the manifest with the bootstrap busybox, before the install base exists.
"$BUSYBOX" unzip -oj "$ZIP" 'manifest.json' -d "$CACHE" >/dev/null 2>&1
ver=$("$BUSYBOX" grep -o '"versionCode"[[:space:]]*:[[:space:]]*[0-9]*' "$CACHE/manifest.json" 2>/dev/null | "$BUSYBOX" grep -o '[0-9]*$')
distro=$("$BUSYBOX" grep -o '"id"[[:space:]]*:[[:space:]]*"[^"]*"' "$CACHE/manifest.json" 2>/dev/null | "$BUSYBOX" head -1 | "$BUSYBOX" cut -d'"' -f4)
[ -n "$distro" ] || distro=unknown
family=$distro
case "$distro" in
    *[Uu]buntu*) family=ubuntu ;;
    *[Aa]rch*)   family=arch ;;
    *[Dd]ebian*) family=debian ;;
    *[Aa]lpine*) family=alpine ;;
esac
rm -f "$CACHE/manifest.json"

# The directory name is the name He typed in setup, lowercased and safe for a
# path. The manifest family is only the fallback when he left the field blank.
slug=$(printf '%s\n' "$NAME" | "$BUSYBOX" tr 'A-Z' 'a-z' | "$BUSYBOX" sed 's/[^a-z0-9._-]/-/g; s/^[-._]*//; s/[-._]*$//')
[ -n "$slug" ] || slug=$family
case "$slug" in
    *-chroot) ;;
    *) slug="$slug-chroot" ;;
esac
BASE=/data/local/$slug
ROOTFS=$BASE/rootfs
DISTRO_FILE=$BASE/distro-id

# Stop a rootfs, kill whatever still holds it, drop its mounts, remove it.
# Used both when switching to a different setup name and when replacing a
# mismatched distro in the same directory.
stop_and_clean() {
    b="$1"
    rf="$b/rootfs"
    [ -d "$b" ] || return 0
    if [ -f "$b/chroot.sh" ]; then
        sh "$b/chroot.sh" stop >/dev/null 2>&1 || true
    fi
    for procdir in /proc/[0-9]*; do
        r=$(readlink "$procdir/root" 2>/dev/null) || continue
        [ "$r" = "$rf" ] && kill -9 "${procdir##*/}" 2>/dev/null
    done
    p=$(cat "$b/holder.pid" 2>/dev/null)
    [ -n "$p" ] && kill -9 "$p" 2>/dev/null
    rm -f "$b/holder.pid" "$b/holder.pid.flags"
    while read -r dev mp rest; do
        case "$mp" in
            "$rf"|"$rf"/*) umount -l "$mp" 2>/dev/null ;;
        esac
    done < /proc/mounts
    rm -rf "$b" || fail "could not remove old rootfs at $b"
}

# The pointer records the active base. Installs made before it existed fall
# back to the legacy path so they get cleaned up instead of left behind.
old=$(cat "$POINTER" 2>/dev/null)
case "$old" in
    /data/local/*-chroot) ;;
    *) old=$LEGACY ;;
esac
if [ "$old" != "$BASE" ] && [ -d "$old" ]; then
    say "switching rootfs: $old -> $BASE"
    stop_and_clean "$old"
fi

mkdir -p "$BASE/bin" "$ROOTFS" "$CACHE" || fail "could not create $BASE"

# Same directory, same distro: keep user data. Identity still runs afterwards
# from the app, so a rename applies without a reinstall.
if [ -x "$ROOTFS/bin/sh" ] || [ -x "$ROOTFS/bin/busybox" ]; then
    existing=$(cat "$DISTRO_FILE" 2>/dev/null)
    case "$existing" in
        *[Uu]buntu*) existing=ubuntu ;;
        *[Aa]rch*)   existing=arch ;;
        *[Dd]ebian*) existing=debian ;;
        *[Aa]lpine*) existing=alpine ;;
    esac
    if [ "$existing" = "$family" ]; then
        say "rootfs $family already installed at $BASE - keeping existing data"
        printf '%s\n' "$BASE" > "$POINTER"
        echo 1 > "$BASE/.ubuntu-chroot-cli"
        say "base=$BASE"
        say "ready: sh $BASE/chroot.sh start root"
        exit 0
    fi
    say "replacing mismatched rootfs at $BASE"
    stop_and_clean "$BASE"
    mkdir -p "$BASE/bin" "$ROOTFS" || fail "could not recreate $BASE"
fi

# Use the bundled static busybox for every extraction, so a missing system
# busybox cannot break the install.
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
[ -n "$ver" ] && echo "$ver" > "$BASE/version"

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
printf '%s\n' "$BASE" > "$POINTER"
echo 1 > "$BASE/.ubuntu-chroot-cli"
say "base=$BASE"
say "ready: sh $BASE/chroot.sh start root"
