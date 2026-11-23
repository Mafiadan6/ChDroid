#!/system/bin/sh
# Ubuntu-Chroot CLI frontend - Ubuntu-Chroot CLI session bootstrap.
#
# No boot scripts, no module flashing, no reboot. Run this after  has
# granted root, from any shell that can call su:
#
#   su -c 'sh /sdcard/Download/ubuntu-chroot-bootstrap.sh all /sdcard/Download/ChDroid-ubuntu-rootfs-20261029.zip'
#   su -c 'sh /sdcard/Download/ubuntu-chroot-bootstrap.sh install /sdcard/Download/ChDroid-ubuntu-rootfs-20261029.zip'
#   su -c 'sh /sdcard/Download/ubuntu-chroot-bootstrap.sh start'
#   su -c 'sh /sdcard/Download/ubuntu-chroot-bootstrap.sh shell'
#
# Commands: binaries | install <zip> | daemon | start | shell | stop | status | all <zip>

CHDROID_DIR=/data/local/ChDroid
BIN_DIR=$CHDROID_DIR/bin
CONTAINERS_DIR=$CHDROID_DIR/Containers
LOGS_DIR=$CHDROID_DIR/Logs
CHDROID_BIN=$BIN_DIR/chdroid
BB=$BIN_DIR/busybox
MARKER=$CHDROID_DIR/.ubuntu-chroot-cli
PKG_FORK=com.chdroid.app
PKG_STOCK=com.chdroid.app
UC_POINTER=/data/local/chdroid-active-rootfs
UC_BASE=$(cat "$UC_POINTER" 2>/dev/null)
case "$UC_BASE" in /data/local/*-chroot) ;; *) UC_BASE=/data/local/ubuntu-chroot ;; esac
UC_SH=$UC_BASE/chroot.sh

log() { echo "[uc-cli] $1"; }
die() { echo "[uc-cli] ERROR: $1" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "run as root (su -c 'sh $0 ...')"
mkdir -p "$BIN_DIR" "$CONTAINERS_DIR" "$LOGS_DIR"
echo 1 > "$MARKER"

apk_path() {
    for pkg in "$PKG_FORK" "$PKG_STOCK"; do
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

    log "installing chdroid-$suffix"
    asset "chdroid-$suffix" "$CHDROID_BIN"
    echo 1 > "$CHDROID_DIR/.daemon_mode"
    echo "ubuntu-chroot CLI session: no boot module, no reboot" > "$CHDROID_DIR/BOOT_MODE"
    log "binaries ready"
}

cmd_daemon() {
    [ -x "$CHDROID_BIN" ] || die "backend missing - run 'binaries' first"
    mode=$("$CHDROID_BIN" mode 2>/dev/null)
    [ "$mode" = "daemon" ] && { log "daemon already running"; return 0; }
    log "starting daemon"
    "$CHDROID_BIN" daemon >> "$LOGS_DIR/ubuntu-chroot-daemon.log" 2>&1 &
    sleep 1
    mode=$("$CHDROID_BIN" mode 2>/dev/null)
    [ "$mode" = "daemon" ] || die "daemon failed - see $LOGS_DIR/ubuntu-chroot-daemon.log"
    log "daemon running"
}

cmd_install() {
    zip=$1
    [ -n "$zip" ] || die "usage: $0 install <ubuntu-chroot-CLI-v4.4.zip>"
    [ -f "$zip" ] || die "ZIP not found: $zip"
    [ -x "$BB" ] || die "busybox missing - run 'binaries' first"

    installer=$BIN_DIR/install_ubuntu_chroot_cli.sh
    asset install_ubuntu_chroot_cli.sh "$installer"

    log "installing Ubuntu-Chroot CLI from $zip"
    "$installer" "$zip" "$BB" "$CHDROID_DIR/.zip-cache" >> "$LOGS_DIR/ubuntu-chroot-install.log" 2>&1 || \
        die "CLI install failed - see $LOGS_DIR/ubuntu-chroot-install.log"
    [ -x "$UC_SH" ] || die "chroot.sh missing after install"
    log "Ubuntu-Chroot CLI ready at $UC_SH"
}

cmd_start() {
    [ -x "$UC_SH" ] || die "Ubuntu-Chroot CLI missing - run 'install' first"
    log "starting Ubuntu chroot (no shell)"
    sh "$UC_SH" start --no-shell
}

cmd_shell() {
    [ -x "$UC_SH" ] || die "Ubuntu-Chroot CLI missing - run 'install' first"
    exec sh "$UC_SH" start root
}

cmd_stop() {
    [ -x "$UC_SH" ] || die "Ubuntu-Chroot CLI missing"
    sh "$UC_SH" stop
}

cmd_status() {
    echo "root: $(id -u)"
    [ -x "$CHDROID_BIN" ] && echo "mode: $($CHDROID_BIN mode 2>/dev/null)" || echo "mode: backend missing"
    echo "marker: $([ -f "$MARKER" ] && echo yes || echo no)"
    if [ -x "$UC_SH" ]; then
        sh "$UC_SH" status
    else
        echo "chroot: not installed"
    fi
}

case "$1" in
    binaries) cmd_binaries ;;
    install) shift; cmd_install "$@" ;;
    daemon) cmd_daemon ;;
    start) cmd_start ;;
    shell) cmd_shell ;;
    stop) cmd_stop ;;
    status) cmd_status ;;
    all)
        shift
        cmd_binaries
        cmd_install "$@"
        cmd_start
        ;;
    *) sed -n '2,16p' "$0" ;;
esac
