#!/system/bin/sh
# Ubuntu-Chroot v4.4 rootfs adjustments for Droidspaces.
# Called only for rootfs extracted from an Ubuntu-Chroot release ZIP.
# Every change is guarded by what is actually in the extracted tree.

ROOTFS_PATH="$1"
BUSYBOX_PATH="${BUSYBOX_PATH:-/data/local/Droidspaces/bin/busybox}"
BB="$BUSYBOX_PATH"
log() { "$BB" echo "[UC-FIX] $1"; }

[ -d "$ROOTFS_PATH" ] || { log "rootfs missing: $ROOTFS_PATH"; exit 1; }
[ -x "$BB" ] || { log "busybox missing"; exit 1; }

# Ubuntu-Chroot points /etc/resolv.conf at /run/resolvconf/resolv.conf and
# writes it at chroot start. Nothing writes it in a Droidspaces container, so
# leave a plain file when the link has no target.
if [ -L "$ROOTFS_PATH/etc/resolv.conf" ] && [ ! -e "$ROOTFS_PATH/etc/resolv.conf" ]; then
    "$BB" rm -f "$ROOTFS_PATH/etc/resolv.conf"
    "$BB" printf 'nameserver 8.8.8.8\nnameserver 8.8.4.4\n' > "$ROOTFS_PATH/etc/resolv.conf"
    log "Replaced dangling resolv.conf symlink"
fi

# The chroot package adds these groups to /etc/group at install time; a fresh
# extraction may predate that. post_extract_fixes.sh also does this, keep this
# idempotent for the standalone bootstrap path.
for grp in aid_inet:3003 aid_net_raw:3004 aid_net_admin:3005; do
    name=${grp%%:*}
    gid=${grp##*:}
    "$BB" grep -q "^$name:" "$ROOTFS_PATH/etc/group" 2>/dev/null || \
        "$BB" echo "$name:x:$gid:" >> "$ROOTFS_PATH/etc/group"
done

# Mark the rootfs so a second install can skip the whole fix pass.
"$BB" touch "$ROOTFS_PATH/etc/ubuntu-chroot-droidspaces" 2>/dev/null
log "Ubuntu-Chroot rootfs ready for Droidspaces"
