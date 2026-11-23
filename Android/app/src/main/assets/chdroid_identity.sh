#!/system/bin/sh
# Apply setup identity and ChDroid runtime patches to an installed rootfs. Runs as
# root right after the release installer and again on app start, so an APK update
# fixes existing installs without repacking the rootfs ZIP.
#
# Patches:
#   - distro-id: clean ID from the guest's own /etc/os-release.
#   - hostname: /etc/hostname plus chroot.sh's C_HOSTNAME constant. The real
#     kernel hostname is set inside the UTS namespace with busybox's
#     sethostname() path, so `uname -n` and fastfetch show the wizard name too.
#     Writing /proc/sys/kernel/hostname is deliberately avoided: that sysctl
#     belongs to the phone's initial UTS namespace, not the container's.
#   - prompt: restore the release's original coloured `root@host` PS1 in
#     /root/.bashrc. The release's own PS1 is canonical; earlier builds of this
#     script replaced it with a plain one and He wants the original look back.
#   - fast stop: replace lsof scanning, bash-only mount arrays, and the blocking
#     binfmt-support stop with POSIX builtins. Stops stay quick on /system/bin/sh.
#   - fast start: drop the in-chroot /dev/fd and networking helpers (each one was a
#     full nsenter+bash round trip) and stamp elapsed seconds on the phase logs so
#     the console shows exactly where setup time goes.
#
# The release's bundled busybox is minimal, so every text operation here is a
# shell builtin. toybox is only used for mv/chmod/rm.
#
# usage: chdroid_identity.sh <base> <hostname> <container-name>

# Bump when the patch rules change. The app checks this marker at launch so an
# unchanged APK does not rewrite chroot.sh on every start (that root-shell
# contention was implicated in the launch crash reports).
PATCH_VERSION=8

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
    t0_added=0
    while IFS= read -r line || [ -n "$line" ]; do
      # Start the elapsed clock once, right after the shebang. Phase logs below
      # then print +Ns so a slow start is visible in the console without logcat.
      case "$line" in
        'CHDROID_T0='*) continue ;;
        *'# ChDroid: pacman'*)
          # Upgrade an earlier build's inline mtab symlink to the helper call.
          printf '%s\n' "    run_in_ns sh '$BASE/chdroid-mtab.sh' \"\$CHROOT_PATH\" 2>/dev/null # ChDroid: pacman"
          continue
          ;;
      esac
      if [ "$t0_added" -eq 0 ]; then
        case "$line" in
          '#!'*) printf '%s\n' "$line" 'CHDROID_T0=$(date +%s)' > "$TMP"; t0_added=1; continue ;;
        esac
      fi

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
        *'# ChDroid: hostname'*)
          # Re-patching a v8 chroot.sh must not stack another hostname call.
          printf '%s\n' "    run_in_ns sh '$BASE/chdroid-hostname.sh' '$BASE' \"\$C_HOSTNAME\" 2>/dev/null # ChDroid: hostname"
          continue
          ;;
        C_HOSTNAME=*)
          printf 'C_HOSTNAME="%s"\n' "$HOST"
          ;;
        BASE_CHROOT_DIR=*)
          # The release hardcodes /data/local/ubuntu-chroot. Point the installed
          # script at the setup-named directory instead.
          printf 'BASE_CHROOT_DIR="%s"\n' "$BASE"
          ;;
        *'log "Setting up advanced chroot environment..."'*)
          printf '%s\n' '    log "Setting up advanced chroot environment... +$(( $(date +%s) - CHDROID_T0 ))s"'
          ;;
        *'log "Creating new isolated namespace..."'*)
          printf '%s\n' '        log "Creating new isolated namespace... +$(( $(date +%s) - CHDROID_T0 ))s"'
          ;;
        *'log "Setting up system mounts..."'*)
          printf '%s\n' '    log "Setting up system mounts... +$(( $(date +%s) - CHDROID_T0 ))s"'
          ;;
        *'log "Applying networking fixes..."'*)
          printf '%s\n' '    log "Applying networking fixes... +$(( $(date +%s) - CHDROID_T0 ))s"'
          # Do this at every start, not only at patch time: a sparse/image rootfs
          # is empty while unmounted, so host-side writes made by this script can
          # vanish once the image is loop-mounted.
          printf '%s\n' "    run_in_ns sh '$BASE/chdroid-mtab.sh' \"\$CHROOT_PATH\" 2>/dev/null # ChDroid: pacman"
          # fastfetch's title reads uname(2), not /etc/hostname. Set the name in
          # the UTS namespace so it agrees with the baked prompt.
          printf '%s\n' "    run_in_ns sh '$BASE/chdroid-hostname.sh' '$BASE' \"\$C_HOSTNAME\" 2>/dev/null # ChDroid: hostname"
          ;;
        *'log "Chroot environment setup completed successfully!"'*)
          printf '%s\n' '    log "Chroot environment setup completed successfully! +$(( $(date +%s) - CHDROID_T0 ))s"'
          ;;
        *'run_in_chroot "umount /dev/fd'*)
          # /dev/fd lives on the rootfs filesystem, visible from the host. Do the
          # symlink here instead of paying for nsenter + chroot bash on every start.
          printf '%s\n' '    rm -rf "$CHROOT_PATH/dev/fd" 2>/dev/null; ln -sfn /proc/self/fd "$CHROOT_PATH/dev/fd" 2>/dev/null'
          ;;
        *'if run_in_chroot "${internet_fix_cmd}"; then'*)
          # The release's in-chroot networking block runs find/usermod/sed through
          # nsenter+bash. DNS and hosts are plain files on the host side; write them
          # directly and keep the if/else that follows so the log shape is unchanged.
          printf '%s\n' '    mkdir -p "$CHROOT_PATH/run/resolvconf" "$CHROOT_PATH/run/sshd" 2>/dev/null'
          printf '%s\n' '    printf "%b" "$dns_servers" > "$CHROOT_PATH/run/resolvconf/resolv.conf"'
          printf '%s\n' '    ln -sfn /run/resolvconf/resolv.conf "$CHROOT_PATH/etc/resolv.conf" 2>/dev/null'
          printf '%s\n' '    printf "127.0.0.1\\tlocalhost %s\\n" "$C_HOSTNAME" > "$CHROOT_PATH/etc/hosts"'
          printf '%s\n' '    if :; then'
          ;;
        *'"$2" sleep infinity &'*)
          # The namespace holder is backgrounded by chroot.sh start. Left as-is
          # it inherits exec()'s stdout pipe and libsu waits for EOF forever,
          # which reads as "start takes ages". Null its stdio at the spawn.
          printf '%s\n' '    unshare $unshare_flags sh -c '\''"$2" sleep infinity >/dev/null 2>&1 & echo $! > "$1"'\'' -- "$pid_file" "$BUSYBOX"'
          ;;
        *'Use lsof to find all PIDs'*)
          printf '%s\n' '    # One ls over all proc roots, not a readlink fork per PID and not'
          printf '%s\n' '    # lsof scanning the whole device. Both made stop take many seconds.'
          ;;
        '    local pids')
          printf '%s\n' '    local pids'
          ;;
        *'pids=$(lsof'*)
          printf '%s\n' '    pids=$(ls -l /proc/[0-9]*/root 2>/dev/null | awk -v t="$CHROOT_PATH" '\''$11==t {split($9,a,"/"); print a[3]}'\'')'
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
        *'android_optimizations --enable'*)
          printf '%s\n' '    : # ChDroid: skip Android settings round-trips on start'
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
          # The UTS namespace cannot write /proc/sys/kernel/hostname on Android, so
          # \h resolves to the phone's "localhost". Bake the wizard hostname into
          # the prompt literally instead - the colours and layout stay canonical.
          printf '%s\n' "export PS1=\"\\[\\033[38;5;208m\\]\\u@${HOST}\\[\\033[m\\]:\\[\\033[34m\\]\\w\\[\\033[m\\]# \""
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

# Arch has no /root/.bashrc: its login shell sets PS1 in /etc/bash.bashrc with
# \h, which resolves to the phone's "localhost". Patch that line too so the
# prompt carries the wizard hostname on every distro.
GLOBALRC="$ROOTFS/etc/bash.bashrc"
if [ -f "$GLOBALRC" ]; then
  TMP="$GLOBALRC.chdroid.$$"
  if : > "$TMP" 2>/dev/null; then
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in
        *PS1=*)
          printf '%s\n' "PS1='[\\u@${HOST} \\W]\\\$ '"
          ;;
        *)
          printf '%s\n' "$line"
          ;;
      esac
    done < "$GLOBALRC" >> "$TMP"
    if mv_f -f "$TMP" "$GLOBALRC"; then
      say "arch root prompt restored"
    else
      rm_f -f "$TMP" 2>/dev/null
      say "WARNING: could not patch arch root prompt"
    fi
  else
    say "WARNING: could not patch arch root prompt"
  fi
fi

# 5. Container name and hostname for the UI and the terminal label. These plain
#    files are the fallback when container.config was written by an older build.
[ -n "$CNAME" ] || CNAME="$HOST"
printf '%s\n' "$CNAME" > "$BASE/container-name"
printf '%s\n' "$HOST" > "$BASE/hostname"
say "container-name=$CNAME, hostname=$HOST"

# 6. Earlier builds flipped the whole phone permissive. Put it back now and drop
#    the stale state file so a later chroot.sh cannot restore a permissive mode.
SE=/system/bin/setenforce
[ -x "$SE" ] || SE=setenforce
"$SE" 1 2>/dev/null && say "SELinux enforcing" || say "SELinux already enforcing or unsupported"
rm_f -f "$BASE/selinux-original" 2>/dev/null

# 7. pacman resolves the cachedir's mount point through getmntent(/etc/mtab).
#    Arch ships mtab as a symlink to /proc/self/mounts, whose entries carry host
#    paths (/data/local/...), so nothing matches /var/cache/pacman/pkg and every
#    transaction fails. Generate a chroot-relative mtab instead, at patch time
#    and again on every start through the helper injected into chroot.sh.
mkdir -p "$ROOTFS/var/cache/pacman/pkg" 2>/dev/null
MTAB="$ROOTFS/etc/mtab"
rm_f -f "$MTAB" 2>/dev/null
printf '%s\n' '/dev/root / ext4 rw,relatime 0 0' > "$MTAB" 2>/dev/null

HELPER="$BASE/chdroid-mtab.sh"
cat > "$HELPER" <<'CHDROID_MTAB'
#!/system/bin/sh
# Regenerate a chroot-relative /etc/mtab from this namespace's mount table.
ROOT="$1"
[ -n "$ROOT" ] || exit 0
mkdir -p "$ROOT/var/cache/pacman/pkg" 2>/dev/null
MT="$ROOT/etc/mtab"
rm -f "$MT" 2>/dev/null
printf '%s\n' '/dev/root / ext4 rw,relatime 0 0' > "$MT" || exit 0
while read -r dev mp type opts rest; do
  case "$mp" in
    "$ROOT") ;;
    "$ROOT"/*) printf '%s %s %s %s 0 0\n' "$dev" "${mp#$ROOT}" "$type" "$opts" >> "$MT" ;;
  esac
done < /proc/self/mounts
CHDROID_MTAB
chmod_f 755 "$HELPER" 2>/dev/null
say "pacman mtab generated"

# 8. Kernel hostname. /etc/hostname is just a file, fastfetch reads uname(2).
#    busybox's hostname applet reaches sethostname(2), which renames the UTS
#    namespace only. Skip it without UTS so the phone's own name is untouched.
HOST_HELPER="$BASE/chdroid-hostname.sh"
cat > "$HOST_HELPER" <<CHDROID_HOSTNAME
#!/system/bin/sh
BASE=\$1
HOST=\$2
[ -n "\$HOST" ] || exit 0
FLAGS_FILE="\$BASE/holder.pid.flags"
case " \$(cat "\$FLAGS_FILE" 2>/dev/null) " in
  *" --uts "*|*" -u "*) ;;
  *) exit 0 ;;
esac
BB="\$BUSYBOX"
[ -x "\$BB" ] || BB="\$BASE/bin/busybox"
if [ -x "\$BB" ] && "\$BB" hostname "\$HOST" 2>/dev/null; then
  printf 'chdroid-hostname: %s\\n' "\$HOST"
  exit 0
fi
if hostname "\$HOST" 2>/dev/null; then
  printf 'chdroid-hostname: %s\\n' "\$HOST"
  exit 0
fi
printf 'chdroid-hostname: could not set kernel hostname\\n'
exit 1
CHDROID_HOSTNAME
chmod_f 755 "$HOST_HELPER" 2>/dev/null
say "hostname helper generated"

# 9. Patch revision marker. The app reads this at launch and skips the whole
#    re-patch when the installed chroot.sh is already at this revision.
printf '%s\n' "$PATCH_VERSION" > "$BASE/chdroid-patch-version" 2>/dev/null
say "patch version=$PATCH_VERSION"

exit 0
