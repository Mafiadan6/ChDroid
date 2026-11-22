#!/system/bin/sh
# Apply setup identity and ChDroid runtime patches to an installed rootfs. Runs as
# root right after the release installer and again on app start, so an APK update
# fixes existing installs without repacking the rootfs ZIP.
#
# Patches:
#   - distro-id: clean ID from the guest's own /etc/os-release.
#   - hostname: /etc/hostname plus chroot.sh's C_HOSTNAME constant; chroot.sh
#     rewrites /proc/sys/kernel/hostname from that constant on every start.
#   - prompt: restore the release's original coloured `root@host` PS1 in
#     /root/.bashrc. The release's own PS1 is canonical; earlier builds of this
#     script replaced it with a plain one and He wants the original look back.
#   - fast stop: replace lsof scanning, bash-only mount arrays, and the blocking
#     binfmt-support stop with POSIX builtins. Stops stay quick on /system/bin/sh.
#
# The release's bundled busybox is minimal, so every text operation here is a
# shell builtin. toybox is only used for mv/chmod/rm.
#
# usage: chdroid_identity.sh <base> <hostname> <container-name>

BASE="$1"
HOST="$2"
CNAME="$3"
ROOTFS="$BASE/rootfs"
POINTER=/data/local/chdroid-active-rootfs
# Keep the app's active-base pointer in sync when an existing install is
# re-identified (app start or the edit action) without a reinstall.
printf '%s\n' "$BASE" > "$POINTER" 2>/dev/null
TB=/system/bin/toybox
[ -x "$TB" ] || TB=toybox

mv_f()    { if "$TB" mv "$@" 2>/dev/null; then return 0; else mv "$@"; fi; }
chmod_f() { if "$TB" chmod "$@" 2>/dev/null; then return 0; else chmod "$@"; fi; }
rm_f()    { if "$TB" rm "$@" 2>/dev/null; then return 0; else rm "$@"; fi; }

say() { echo "[CHDROID-ID] $1"; }

[ -d "$ROOTFS" ] || { say "ERROR: rootfs missing at $ROOTFS"; exit 1; }

# 1. Clean distro id straight from the guest, not the release manifest slug.
DID=''
while IFS= read -r line; do
  case "$line" in
    ID=*) DID=${line#ID=}; break ;;
  esac
done < "$ROOTFS/etc/os-release" 2>/dev/null
DID=${DID#\"}
DID=${DID%\"}
# Minimal Arch images ship without /etc/os-release. Keep any good id already
# recorded, then fall back to the distro's own marker files.
if [ -z "$DID" ] || [ "$DID" = unknown ]; then
  OLD=$(cat "$BASE/distro-id" 2>/dev/null)
  case "$OLD" in ''|unknown) ;; *) DID=$OLD ;; esac
fi
if [ -z "$DID" ] || [ "$DID" = unknown ]; then
  [ -f "$ROOTFS/etc/arch-release" ] && DID=arch
  [ -f "$ROOTFS/etc/alpine-release" ] && DID=alpine
  [ -f "$ROOTFS/etc/debian_version" ] && DID=debian
fi
if [ -z "$DID" ] || [ "$DID" = unknown ]; then
  case "$CNAME" in
    *[Aa]rch*) DID=arch ;;
    *[Uu]buntu*) DID=ubuntu ;;
    *[Dd]ebian*) DID=debian ;;
    *[Aa]lpine*) DID=alpine ;;
  esac
fi
[ -n "$DID" ] || DID=unknown
printf '%s\n' "$DID" > "$BASE/distro-id"
say "distro-id=$DID"

# 2. Hostname. Fall back to the container name, then the distro id.
[ -n "$HOST" ] || HOST="$CNAME"
[ -n "$HOST" ] || HOST="$DID"
printf '%s\n' "$HOST" > "$ROOTFS/etc/hostname"

# 3. Patch the installed chroot.sh in one pass. C_HOSTNAME keeps the shell
# prompt and `uname -n` in sync, and the stop path drops three slow/non-POSIX
# constructs from the release script.
PATCH="$BASE/chroot.sh"
if [ -f "$PATCH" ]; then
  TMP="$PATCH.id.$$"
  if : > "$TMP" 2>/dev/null; then
    skip_array=0
    while IFS= read -r line || [ -n "$line" ]; do
      # Drop the bash-only `custom_linux_mounts=(...)` declaration. The loop
      # below gets a literal list instead so /system/bin/sh can parse the file.
      case "$line" in
        *'custom_linux_mounts=('*)
          skip_array=1
          continue
          ;;
      esac
      if [ "$skip_array" -eq 1 ]; then
        case "$line" in
          *')'*) skip_array=0 ;;
        esac
        continue
      fi

      case "$line" in
        C_HOSTNAME=*)
          printf 'C_HOSTNAME="%s"\n' "$HOST"
          ;;
        BASE_CHROOT_DIR=*)
          # The release hardcodes /data/local/ubuntu-chroot. Point the installed
          # script at the setup-named directory instead.
          printf 'BASE_CHROOT_DIR="%s"\n' "$BASE"
          ;;
        *'Use lsof to find all PIDs'*)
          printf '%s\n' '    # Walk /proc/PID/root instead of lsof: lsof scans the whole device.'
          ;;
        '    local pids')
          printf '%s\n' '    local pids procdir target'
          ;;
        *'pids=$(lsof'*)
          # /proc/PID/root is the chroot dir for every guest process. Walking it
          # is instant; lsof scans the whole device and can stall shutdown.
          printf '%s\n' '    pids='
          printf '%s\n' '    for procdir in /proc/[0-9]*; do'
          printf '%s\n' '        [ -r "$procdir/root" ] || continue'
          printf '%s\n' '        target=$(readlink "$procdir/root" 2>/dev/null) || continue'
          printf '%s\n' '        [ "$target" = "$CHROOT_PATH" ] || continue'
          printf '%s\n' '        pids="$pids ${procdir##*/}"'
          printf '%s\n' '    done'
          ;;
        *'for mount_point in "${custom_linux_mounts'*)
          printf '%s\n' '    for mount_point in "$CHROOT_PATH/proc/sys/fs/binfmt_misc" "$CHROOT_PATH/tmp/runtime/gvfs" "$CHROOT_PATH/tmp"; do'
          ;;
        *'run_in_chroot "systemctl stop binfmt-support"'*)
          printf '%s\n' '    : # ChDroid: skipped binfmt-support stop; it can hang shutdown'
          ;;
        *'run_fstrim >/dev/null 2>&1'*)
          printf '%s\n' '        : # ChDroid: skip fstrim on stop; it can block for tens of seconds'
          ;;
        '    umount_chroot'*)
          # The release helper enters the namespace once per mount and retries
          # storage three times. One host-side lazy-unmount pass is instant and
          # drops exactly the same mount points.
          printf '%s\n' '    # ChDroid: unmount from the host in one lazy pass.'
          printf '%s\n' '    while read -r dev mp rest; do'
          printf '%s\n' '        case "$mp" in'
          printf '%s\n' '            "$CHROOT_PATH"|"$CHROOT_PATH"/*) umount -l "$mp" 2>/dev/null ;;'
          printf '%s\n' '        esac'
          printf '%s\n' '    done < /proc/mounts'
          printf '%s\n' '    rm -f "$MOUNTED_FILE" 2>/dev/null'
          ;;
        *'android_optimizations --disable'*)
          printf '%s\n' '    : # ChDroid: skip Android settings round-trips on stop'
          ;;
        *'SELINUX_STATE_FILE="'*)
          printf '%s\n' '    : # ChDroid: SELinux mode stays enforcing'
          ;;
        *'read -r orig_mode < /sys/fs/selinux/enforce'*)
          printf '%s\n' '    : # ChDroid: no SELinux mode change'
          ;;
        *'setenforce 0'*)
          printf '%s\n' '    : # ChDroid: no permissive switch'
          ;;
        *)
          printf '%s\n' "$line"
          ;;
      esac
    done < "$PATCH" >> "$TMP"
    if mv_f -f "$TMP" "$PATCH"; then
      chmod_f 755 "$PATCH" 2>/dev/null
      say "chroot.sh base=$BASE, C_HOSTNAME=$HOST, fast-stop applied"
    else
      rm_f -f "$TMP" 2>/dev/null
      say "WARNING: could not patch chroot.sh"
    fi
  else
    say "WARNING: could not patch chroot.sh"
  fi
fi

# 4. Restore the release's original coloured `root@host:~# ` prompt. An earlier
# build of this script replaced it with a plain one; writing the canonical line
# back (with real ESC bytes) puts the original prompt on existing installs too.
BASHRC="$ROOTFS/root/.bashrc"
if [ -f "$BASHRC" ]; then
  TMP="$BASHRC.chdroid.$$"
  if : > "$TMP" 2>/dev/null; then
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in
        export\ PS1=*)
          printf '%b\n' 'export PS1="\\[\033[38;5;208m\\]\\u@\\h\\[\033[m\\]:\\[\033[34m\\]\\w\\[\033[m\\]# "'
          ;;
        *)
          printf '%s\n' "$line"
          ;;
      esac
    done < "$BASHRC" >> "$TMP"
    if mv_f -f "$TMP" "$BASHRC"; then
      say "release root prompt restored"
    else
      rm_f -f "$TMP" 2>/dev/null
      say "WARNING: could not patch root prompt"
    fi
  else
    say "WARNING: could not patch root prompt"
  fi
fi

# 5. Container name for the UI card.
[ -n "$CNAME" ] || CNAME="$HOST"
printf '%s\n' "$CNAME" > "$BASE/container-name"
say "container-name=$CNAME"

# 6. Earlier builds flipped the whole phone permissive. Put it back now and drop
#    the stale state file so a later chroot.sh cannot restore a permissive mode.
SE=/system/bin/setenforce
[ -x "$SE" ] || SE=setenforce
"$SE" 1 2>/dev/null && say "SELinux enforcing" || say "SELinux already enforcing or unsupported"
rm_f -f "$BASE/selinux-original" 2>/dev/null

exit 0
