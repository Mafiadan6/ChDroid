#!/system/bin/sh
# ChDroid: expose the Android filesystem inside the chroot's mount namespace.
# The whole point of this helper is write access to the phone's root from inside
# the guest, so the bind is deliberately read/write. It runs through the release
# busybox nsenter, which puts the mount in the chroot's namespace only.

POINTER=/data/local/chdroid-active-rootfs
BASE=$(cat "$POINTER" 2>/dev/null)
case "$BASE" in /data/local/*-chroot) ;; *) BASE=/data/local/ubuntu-chroot ;; esac
FLAG=$BASE/mount-android-root
[ -f "$FLAG" ] || exit 0

PIDFILE=$BASE/holder.pid
PID=$(cat "$PIDFILE" 2>/dev/null)
[ -n "$PID" ] && kill -0 "$PID" 2>/dev/null || exit 0

BB=/data/local/ChDroid/bin/busybox-release
[ -x "$BB" ] || BB=/data/local/ChDroid/bin/busybox
[ -x "$BB" ] || exit 0

TARGET=$BASE/rootfs/android-root
mkdir -p "$TARGET" 2>/dev/null || exit 0

# Skip when the namespace already has the bind. Reading /proc/mounts with the
# shell avoids depending on grep or mountpoint in the minimal release busybox.
already=0
while read -r dev mp rest; do
  [ "$mp" = "$TARGET" ] && already=1
done < /proc/mounts
[ "$already" -eq 1 ] && exit 0

# Read/write bind. No remount to read-only: the guest needs to touch Android's
# /data and /sdcard, and a ro remount defeated that.
"$BB" nsenter --target "$PID" -m -- mount --bind / "$TARGET" 2>/dev/null
exit 0
