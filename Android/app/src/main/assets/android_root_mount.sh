#!/system/bin/sh
# ChDroid: bind the full Android filesystem into the running chroot.
# Runs only when the setup screen's "Mount Android root" option was chosen.
# The mount happens inside the chroot's own mount namespace via the release
# busybox nsenter, so it is visible to the chroot but not to the host.

BASE=/data/local/ubuntu-chroot
FLAG=$BASE/mount-android-root
[ -f "$FLAG" ] || exit 0

PIDFILE=$BASE/holder.pid
PID=$(cat "$PIDFILE" 2>/dev/null)
[ -n "$PID" ] && kill -0 "$PID" 2>/dev/null || exit 0

BB=/data/local/Droidspaces/bin/busybox-release
[ -x "$BB" ] || BB=/data/local/Droidspaces/bin/busybox
[ -x "$BB" ] || exit 0

TARGET=$BASE/rootfs/android-root
mkdir -p "$TARGET" 2>/dev/null || exit 0

# -m enters only the mount namespace; host paths stay addressable.
"$BB" nsenter --target "$PID" -m -- mount --bind / "$TARGET" 2>/dev/null
"$BB" nsenter --target "$PID" -m -- mount -o remount,bind,ro "$TARGET" 2>/dev/null
exit 0
