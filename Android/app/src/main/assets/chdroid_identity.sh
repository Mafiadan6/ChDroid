#!/system/bin/sh
# Apply setup identity and ChDroid runtime patches to an installed rootfs. Runs as
# root right after the release installer and again on app start, so an APK update
# fixes existing installs without repacking the rootfs ZIP.
#
# Patches:
#   - distro-id: clean ID from the guest's own /etc/os-release.
#   - hostname: /etc/hostname plus chroot.sh's C_HOSTNAME constant; chroot.sh
#     rewrites /proc/sys/kernel/hostname from that constant on every start.
#   - prompt: plain [user@host cwd]# in /root/.bashrc; the release's 256-colour
#     PS1 leaves a broken fragment on screen in the app's terminal.
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
        *)
          printf '%s\n' "$line"
          ;;
      esac
    done < "$PATCH" >> "$TMP"
    if mv_f -f "$TMP" "$PATCH"; then
      chmod_f 755 "$PATCH" 2>/dev/null
      say "chroot.sh C_HOSTNAME=$HOST, fast-stop applied"
    else
      rm_f -f "$TMP" 2>/dev/null
      say "WARNING: could not patch chroot.sh"
    fi
  else
    say "WARNING: could not patch chroot.sh"
  fi
fi

# 4. Plain prompt. The release PS1 uses a raw 256-colour escape that the app's
# terminal renders as a broken prompt fragment after each command.
BASHRC="$ROOTFS/root/.bashrc"
if [ -f "$BASHRC" ]; then
  TMP="$BASHRC.chdroid.$$"
  if : > "$TMP" 2>/dev/null; then
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in
        export\ PS1=*)
          printf '%s\n' "export PS1='[\\u@\\h \\w]# '"
          ;;
        *)
          printf '%s\n' "$line"
          ;;
      esac
    done < "$BASHRC" >> "$TMP"
    if mv_f -f "$TMP" "$BASHRC"; then
      say "root prompt simplified"
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

exit 0
