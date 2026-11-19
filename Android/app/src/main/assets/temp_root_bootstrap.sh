#!/system/bin/sh
# Droidspaces TempRoot fork - in-session bootstrap.
#
# No boot scripts, no module flashing, no reboot. Run this after DirtyFrag has
# granted root, from any shell that can call su:
#
#   su -c 'sh /sdcard/Download/temp-root-bootstrap.sh binaries'
#   su -c 'sh /sdcard/Download/temp-root-bootstrap.sh rootfs /sdcard/Download/Ubuntu-Chroot-v4.4-20260323-CLI.zip'
#   su -c 'sh /sdcard/Download/temp-root-bootstrap.sh all /sdcard/Download/Ubuntu-Chroot-v4.4-20260323-CLI.zip'
#
# Commands: binaries | rootfs <zip> | daemon | start [name] | status | all <zip>

DS_DIR=/data/local/Droidspaces
BIN_DIR=$DS_DIR/bin
CONTAINERS_DIR=$DS_DIR/Containers
LOGS_DIR=$DS_DIR/Logs
DS_BIN=$BIN_DIR/droidspaces
BB=$BIN_DIR/busybox
MARKER=$DS_DIR/.temp_root
PKG_TEMP=com.droidspaces.temproot
PKG_STOCK=com.droidspaces.app
CONTAINER_NAME=ubuntu-chroot
ROOTFS_PATH=$CONTAINERS_DIR/$CONTAINER_NAME/rootfs
CFG_PATH=$CONTAINERS_DIR/$CONTAINER_NAME/container.config

log() { echo "[temp-root] $1"; }
die() { echo "[temp-root] ERROR: $1" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "run as root (su -c 'sh $0 ...')"
mkdir -p "$BIN_DIR" "$CONTAINERS_DIR" "$LOGS_DIR"
echo 1 > "$MARKER"

apk_path() {
    for pkg in "$PKG_TEMP" "$PKG_STOCK"; do
        line=$(pm path "$pkg" 2>/dev/null | head -1)
        case "$line" in package:*) echo "${line#package:}"; return 0 ;; esac
    done
    return 1
}

asset() {
    # asset <apk-asset-name> <destination>
    apk=$(apk_path) || die "fork APK not installed"
    "$BB" unzip -p "$apk" "assets/binaries/$1" > "$2" 2>/dev/null
    [ -s "$2" ] || "$BB" unzip -p "$apk" "assets/$1" > "$2" 2>/dev/null
    [ -s "$2" ] || die "asset $1 missing from $apk"
    chmod 755 "$2"
}

cmd_binaries() {
    arch=$(uname -m)
    case "$arch" in
        aarch64|arm64) suffix=aarch64 ;;
        armv7l|armv8l) suffix=armhf ;;
        x86_64) suffix=x86_64 ;;
        i*86) suffix=x86 ;;
        *) die "unsupported arch $arch" ;;
    esac

    # BusyBox is needed before we can stream the other assets out of the APK.
    if [ ! -x "$BB" ]; then
        pm_path=$(apk_path) || die "fork APK not installed"
        if command -v busybox >/dev/null 2>&1; then
            busybox unzip -p "$pm_path" "assets/binaries/busybox-$suffix" > "$BB" 2>/dev/null
        else
            unzip -p "$pm_path" "assets/binaries/busybox-$suffix" > "$BB" 2>/dev/null
        fi
        [ -s "$BB" ] || die "could not extract busybox"
        chmod 755 "$BB"
    fi

    log "installing droidspaces-$suffix"
    asset "droidspaces-$suffix" "$DS_BIN"
    echo 1 > "$DS_DIR/.daemon_mode"
    echo "temp-root session: no boot module, daemon started by this script" > "$DS_DIR/BOOT_MODE"
    log "binaries ready"
}

cmd_daemon() {
    [ -x "$DS_BIN" ] || die "backend missing - run 'binaries' first"
    mode=$("$DS_BIN" mode 2>/dev/null)
    [ "$mode" = "daemon" ] && { log "daemon already running"; return 0; }
    log "starting daemon"
    "$DS_BIN" daemon >> "$LOGS_DIR/temp-root-daemon.log" 2>&1 &
    sleep 1
    mode=$("$DS_BIN" mode 2>/dev/null)
    [ "$mode" = "daemon" ] || die "daemon failed - see $LOGS_DIR/temp-root-daemon.log"
    log "daemon running"
}

cmd_rootfs() {
    zip=$1
    [ -n "$zip" ] || die "usage: $0 rootfs <ubuntu-chroot-v4.4.zip>"
    [ -f "$zip" ] || die "ZIP not found: $zip"
    [ -x "$BB" ] || die "busybox missing - run 'binaries' first"

    extractor=$BIN_DIR/extract_zip_rootfs.sh
    fixer=$BIN_DIR/ubuntu_chroot_fixes.sh
    postfix=$BIN_DIR/post_extract_fixes.sh
    asset extract_zip_rootfs.sh "$extractor"
    asset ubuntu_chroot_fixes.sh "$fixer"
    asset post_extract_fixes.sh "$postfix"
    chmod 755 "$extractor" "$fixer" "$postfix"

    log "staging rootfs member from $zip"
    tarball=$("$extractor" "$zip" "$BB" "$DS_DIR/.zip-cache" 2>"$LOGS_DIR/zip-extract.log") || \
        die "ZIP extraction failed - see $LOGS_DIR/zip-extract.log"
    [ -f "$tarball" ] || die "no tarball returned"

    log "extracting rootfs to $ROOTFS_PATH"
    mkdir -p "$ROOTFS_PATH"
    (cd "$ROOTFS_PATH" && "$BB" tar -xzpf "$tarball" 2>"$LOGS_DIR/rootfs-extract.log") || \
        die "tar extraction failed - see $LOGS_DIR/rootfs-extract.log"

    log "applying post-extraction fixes"
    "$postfix" "$ROOTFS_PATH" >> "$LOGS_DIR/rootfs-fixes.log" 2>&1 || \
        log "post_extract_fixes reported an error (continuing)"
    "$fixer" "$ROOTFS_PATH" >> "$LOGS_DIR/rootfs-fixes.log" 2>&1 || \
        log "ubuntu_chroot_fixes reported an error (continuing)"

    log "writing container config"
    mkdir -p "${CFG_PATH%/*}"
    cat > "$CFG_PATH" <<EOF
name=$CONTAINER_NAME
hostname=ubuntu-chroot
rootfs_path=$ROOTFS_PATH
net_mode=nat
disable_ipv6=0
enable_android_storage=0
enable_hw_access=0
enable_gpu_mode=0
enable_x11=0
enable_virgl=0
enable_pulseaudio=0
selinux_permissive=1
allow_sandboxing=0
volatile_mode=0
run_at_boot=0
enable_anland=0
force_cgroupv1=0
EOF
    chmod 644 "$CFG_PATH"
    log "container '$CONTAINER_NAME' registered"
}

cmd_start() {
    name=${1:-$CONTAINER_NAME}
    [ -x "$DS_BIN" ] || die "backend missing"
    log "starting container $name"
    "$DS_BIN" --config="${CFG_PATH}" start 2>&1 | tee "$LOGS_DIR/start-$name.log"
}

cmd_status() {
    [ -x "$DS_BIN" ] || { echo "backend: missing"; return 0; }
    echo "root: $(id -u)"
    echo "mode: $($DS_BIN mode 2>/dev/null)"
    echo "marker: $([ -f "$MARKER" ] && echo yes || echo no)"
    echo "rootfs: $([ -d "$ROOTFS_PATH" ] && echo present || echo missing)"
    "$DS_BIN" --json show 2>/dev/null
}

case "$1" in
    binaries) cmd_binaries ;;
    rootfs) shift; cmd_rootfs "$@" ;;
    daemon) cmd_daemon ;;
    start) shift; cmd_start "$@" ;;
    status) cmd_status ;;
    all)
        shift
        cmd_binaries
        cmd_daemon
        cmd_rootfs "$@"
        cmd_start
        ;;
    *) sed -n '2,14p' "$0" ;;
esac
