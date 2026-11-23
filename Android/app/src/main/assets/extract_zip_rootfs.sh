#!/system/bin/sh
# Pull the rootfs tarball out of an Ubuntu-Chroot Magisk-module ZIP.
# Prints the path of the extracted .tar.gz on stdout; all chatter goes to stderr.
# No module flashing, no reboot: the ZIP is just a container for a rootfs.
#
# usage: extract_zip_rootfs.sh <module.zip> <busybox> <output-dir>

ZIP="$1"
BB="${2:-/data/local/ChDroid/bin/busybox}"
OUT="$3"

log() { "$BB" echo "$@" >&2; }

if [ -z "$ZIP" ] || [ ! -f "$ZIP" ]; then
    log "ZIP not found: $ZIP"
    exit 1
fi
if [ ! -x "$BB" ]; then
    log "busybox not executable: $BB"
    exit 1
fi
[ -z "$OUT" ] && OUT="${TMPDIR:-/data/local/tmp}"
"$BB" mkdir -p "$OUT" 2>/dev/null

# Ubuntu-Chroot names the member rootfs.tar.gz; accept any .tar.gz so the
# script also works with CLI/GUI repacks that rename it.
member=$("$BB" unzip -l "$ZIP" 2>/dev/null | "$BB" awk '/\.tar\.gz$/ { print $NF; exit }')
if [ -z "$member" ]; then
    log "No .tar.gz member found in $ZIP"
    exit 2
fi
log "Rootfs member: $member"

target="$OUT/ubuntu-chroot-rootfs.tar.gz"
"$BB" rm -f "$target" 2>/dev/null

# Fast path: stream the member straight out. Some BusyBox builds omit -p.
if "$BB" unzip -p "$ZIP" "$member" > "$target" 2>/dev/null && [ -s "$target" ]; then
    :
else
    "$BB" rm -f "$target" 2>/dev/null
    tmpdir="$OUT/.zip-member"
    "$BB" rm -rf "$tmpdir" 2>/dev/null
    "$BB" mkdir -p "$tmpdir" 2>/dev/null || exit 3
    "$BB" unzip -o "$ZIP" "$member" -d "$tmpdir" >/dev/null 2>&1 || {
        log "unzip failed for $member"
        "$BB" rm -rf "$tmpdir" 2>/dev/null
        exit 3
    }
    found=$("$BB" find "$tmpdir" -name '*.tar.gz' 2>/dev/null | "$BB" head -1)
    if [ -z "$found" ] || ! "$BB" mv "$found" "$target" 2>/dev/null; then
        log "Could not stage extracted tarball"
        "$BB" rm -rf "$tmpdir" 2>/dev/null
        exit 3
    fi
    "$BB" rm -rf "$tmpdir" 2>/dev/null
fi

size=$("$BB" stat -c %s "$target" 2>/dev/null || echo 0)
case "$size" in
    ''|*[!0-9]*) size=0 ;;
esac
if [ "$size" -lt 10485760 ]; then
    log "Extracted tarball is too small ($size bytes)"
    "$BB" rm -f "$target" 2>/dev/null
    exit 4
fi

"$BB" echo "$target"
