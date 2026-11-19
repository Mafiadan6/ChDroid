# Temporary-root sessions with Ubuntu-Chroot v4.4

This fork of Droidspaces-OSS targets one specific setup:

- root comes from **KernelSU loaded through DirtyFrag** (or any other late-load / temporary-root chain);
- `su` works for the current boot;
- **no boot scripts run**: `post-fs-data.sh`, `service.sh` and `sepolicy.rule` are never loaded;
- the rootfs is [Ubuntu-Chroot v4.4](https://github.com/ravindu644/Ubuntu-Chroot/releases/tag/v4.4).

Stock Droidspaces treats a missing `/data/adb/modules/droidspaces` as a fatal backend error, and expects a boot service to start its daemon. Neither assumption holds here. This fork removes both.

## What changed

| Area | Stock | This fork |
|------|-------|-----------|
| App id | `com.droidspaces.app` | `com.droidspaces.temproot` (installs beside stock) |
| Backend status | requires module directory | installed binaries are enough in temp-root mode |
| Installation | writes boot module, suggests reboot | copies binaries only, starts daemon in-session |
| Daemon | started by `service.sh` | started manually, or from the in-app root terminal |
| Rootfs repo | generic tarballs | Ubuntu-Chroot v4.4 CLI + GUI release ZIPs built in |
| Rootfs install | tarball only | extracts `rootfs.tar.gz` from the release ZIP |

Settings gains a **Temporary root session** group:

- **Temporary root session (DirtyFrag / KernelSU)** — skip the boot-module path entirely.
- **Detect KernelSU automatically** — turn temp-root mode on when KernelSU userspace is visible.
- **Start daemon now** — launch `droidspaces daemon` in the current root session.
- **Install root-shell helper** — copy `temproot-bootstrap.sh` into `/data/local/Droidspaces/bin`.
- **Open temp-root terminal** — install the helper, then open the app's own root shell (empty container name = bare `su`), with the helper already on `PATH`.

## Install flow

1. Install the fork APK. Grant root when prompted.
2. Open **Settings → Temporary root session** and switch on **Temporary root session**. If you are on DirtyFrag/KernelSU, also enable **Detect KernelSU automatically**.
3. Go to **Installation** and install the backend. No module is written, no reboot is suggested. The daemon is started in-session.
4. Open **Containers → + → Repository**. Two entries are built in:
   - `Ubuntu-Chroot v4.4 (CLI)` — 537 MB ZIP
   - `Ubuntu-Chroot v4.4 (GUI)` — 880 MB ZIP
5. Download one, install it. The installer pulls `rootfs.tar.gz` out of the ZIP, extracts it, applies Ubuntu-Chroot's post-extraction fixes, and registers a container named `ubuntu-chroot` with NAT networking.
6. Start the container from the Containers tab. Open its terminal from the Panel tab.

Everything lives under `/data/local/Droidspaces`. Nothing is written to `/data/adb/modules`.

## The in-app terminal

The terminal is the control surface for this fork. Its environment already contains `/data/local/Droidspaces/bin` on `PATH`, so once the helper is installed these work directly:

```sh
temproot-bootstrap.sh status
temproot-bootstrap.sh binaries
temproot-bootstrap.sh daemon
temproot-bootstrap.sh rootfs /sdcard/Download/Ubuntu-Chroot-v4.4-20260323-CLI.zip
temproot-bootstrap.sh start
temproot-bootstrap.sh all /sdcard/Download/Ubuntu-Chroot-v4.4-20260323-CLI.zip
```

`status` prints uid, daemon mode, marker presence, rootfs presence and `droidspaces --json show`.

A blank container route (`terminal/`) is a host root shell — `DroidspacesTerminalSession` falls back to plain `su` when no valid container name is given. The Settings row uses exactly that path.

## Headless bootstrap (no app UI)

The same helper is shipped at `scripts/temp-root-bootstrap.sh` in the repo, and as `assets/temp_root_bootstrap.sh` in the APK. From any shell after DirtyFrag has granted root:

```sh
su -c 'sh /sdcard/Download/temp-root-bootstrap.sh binaries'
su -c 'sh /sdcard/Download/temp-root-bootstrap.sh all /sdcard/Download/Ubuntu-Chroot-v4.4-20260323-CLI.zip'
```

It discovers the fork APK through `pm path com.droidspaces.temproot`, streams the correct-arch backend and BusyBox out of `assets/binaries`, writes the temp-root marker, starts the daemon, extracts the rootfs, applies fixes and writes `container.config`.

## Important

Root is per-boot here. After a reboot, DirtyFrag must be applied again; then reopen the app, or run `temproot-bootstrap.sh binaries daemon` from a root shell. Containers do not auto-start because no boot service exists — that is expected, not a failure.

Build with the fork's own app id so both APKs can coexist:

```sh
cd Android
./gradlew :app:assembleDebug
```
