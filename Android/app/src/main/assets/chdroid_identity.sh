#!/system/bin/sh
# Apply the setup screen's identity to an installed ChDroid rootfs. Runs as root
# right after the release installer. Fixes three things the release ZIP hardcodes:
#   - distro-id: a clean ID from the rootfs's own /etc/os-release (arch, ubuntu, ...)
#     instead of the manifest slug (chdroid-arch-linux-minimal).
#   - hostname: written to /etc/hostname AND patched into chroot.sh's C_HOSTNAME,
#     which otherwise rewrites /proc/sys/kernel/hostname to "ubuntu" on every start.
#   - container-name: the name chosen in setup, for the Containers/Panel card.
#
# The release's bundled busybox is a minimal build with no sed/awk/cut, so every
# text operation here is a shell builtin. toybox is only used for mv/chmod/rm.
#
# usage: chdroid_identity.sh <base> <hostname> <container-name>

BASE="$1"
HOST="$2"
CNAME="$3"
ROOTFS="$BASE/rootfs"
TB=/system/bin/toybox
[ -x "$TB" ] || TB=toybox

# toybox ships on every Android; fall back to the ambient PATH on any host that
# does not expose it at the fixed location.
mv_f()   { if "$TB" mv "$@" 2>/dev/null; then return 0; else mv "$@"; fi; }
chmod_f() { if "$TB" chmod "$@" 2>/dev/null; then return 0; else chmod "$@"; fi; }
rm_f()   { if "$TB" rm "$@" 2>/dev/null; then return 0; else rm "$@"; fi; }

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

# chroot.sh rewrites /etc/hosts and /proc/sys/kernel/hostname from C_HOSTNAME on
# every start, so patching only /etc/hostname is not enough: the shell prompt and
# `uname -n` would still read "ubuntu". Rebuild the file with the one line changed.
PATCH="$BASE/chroot.sh"
if [ -f "$PATCH" ]; then
  TMP="$PATCH.id.$$"
  if : > "$TMP" 2>/dev/null; then
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in
        C_HOSTNAME=*) printf 'C_HOSTNAME="%s"\n' "$HOST" ;;
        *) printf '%s\n' "$line" ;;
      esac
    done < "$PATCH" >> "$TMP"
    if mv_f -f "$TMP" "$PATCH"; then
      chmod_f 755 "$PATCH" 2>/dev/null
      say "chroot.sh C_HOSTNAME=$HOST"
    else
      rm_f -f "$TMP" 2>/dev/null
      say "WARNING: could not patch C_HOSTNAME in chroot.sh"
    fi
  else
    say "WARNING: could not patch C_HOSTNAME in chroot.sh"
  fi
fi

# 3. Container name for the UI card.
[ -n "$CNAME" ] || CNAME="$HOST"
printf '%s\n' "$CNAME" > "$BASE/container-name"
say "container-name=$CNAME"

exit 0
