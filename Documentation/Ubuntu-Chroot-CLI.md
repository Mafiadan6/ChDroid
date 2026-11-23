# CLI rootfs flow on a rooted phone

ChDroid is a frontend for the ChDroid rootfs CLI release. It runs on any rooted device with a working `su`, including Magisk, KernelSU, APatch, a late-loaded root shell and plain root. There is no root-method detection and no branch in the code that behaves differently per root solution.

The release's own `chroot.sh` owns the namespace, mounts, DNS and login shell. ChDroid supplies the installer, the console, the status card and the terminal. Nothing is written to `/data/adb/modules`, and no boot script or reboot is part of the flow. SELinux stays enforcing.

## How this differs from the wider ChDroid runtime

| Area | Wider ChDroid runtime | This CLI fork |
|------|-----------------------|---------------|
| Root access | expects the installed backend environment | generic `su` from the device |
| Installation | writes a boot module and suggests reboot | stages helpers in `/data/local/ChDroid/bin` in the current session |
| Boot files | `post-fs-data.sh`, `service.sh`, `sepolicy.rule` | absent |
| Rootfs source | generic ChDroid tarballs | ChDroid-rootfs `v4.4` manifest |
| Rootfs layout | ChDroid container image and config | clean ZIP with rootfs tarball and chroot tools |
| Login | `chdroid enter` | `chroot.sh start root` |
| Identity | hostname and config driven | setup name baked into the prompt |

The native backend stays in the APK for install, status and terminal plumbing. It is not the runtime for this rootfs.

## Install

1. Install the ChDroid APK and grant root.
2. Open the rootfs repository from **Containers**, or share a downloaded rootfs ZIP into the app.
3. Pick one of the nine `v4.4` rootfs builds. The catalog lives in [`manifest-v2.json`](https://raw.githubusercontent.com/Mafiadan6/ChDroid-rootfs/main/manifest-v2.json) and all entries are built into the APK as an offline fallback.
4. Follow the wizard: name, recommended or custom config, sparse image choice, summary, progress.
5. Start or open the terminal from the rootfs card.

The typed name decides the install path. `Arch Linux` becomes `/data/local/arch-linux-chroot`. `/data/local/chdroid-active-rootfs` points at the active base, and older installs at `/data/local/ubuntu-chroot` keep working.

Installing the same distro under the same name keeps rootfs data. Installing a different distro or name stops the old rootfs and replaces it.

## Start, stop and terminal

The Containers card owns Start and Stop. Start runs:

```sh
sh "$BASE/chroot.sh" start --no-shell --skip-post-exec
```

Output goes to the console. Stop kills rootfs processes, drops mounts, clears the holder and returns SELinux to enforcing. Stop is the only teardown action.

The Terminal pill runs the whole chain under `su`, refreshes DNS from the current network, applies the optional Android root bind, then enters with:

```sh
sh "$BASE/chroot.sh" start root
```

Typing `exit` or pressing the tab X closes the terminal tab. The rootfs keeps running, and no Android `/system/bin/sh` fallback is left behind. Only Stop shuts the rootfs down. The Panel tab can also open the terminal for a running rootfs and shows live CPU, RAM with available RAM, PIDs and IP.

## Optional Android root bind

The config form has one host bind switch, `Mount Android root`. When enabled, the Android filesystem appears inside the rootfs at `/android-root` with read and write access to `/data` and `/sdcard`. There is no generic bind mount list in this fork.

## Root shell commands

Read the active base first, then drive the release manager directly:

```sh
BASE=$(cat /data/local/chdroid-active-rootfs)
sh "$BASE/chroot.sh" status
sh "$BASE/chroot.sh" start --no-shell --skip-post-exec
sh "$BASE/chroot.sh" start root
sh "$BASE/chroot.sh" stop
sh "$BASE/chroot.sh" restart
sh "$BASE/chroot.sh" list-users
```

The bootstrap helper is installed at `/data/local/ChDroid/bin/ubuntu-chroot-bootstrap.sh`. From a root shell it accepts `status`, `binaries`, `daemon`, `install <zip>`, `start`, `shell`, `stop` and `all <zip>`.

After a reboot, grant root again, open the app, then start the rootfs. No boot service exists, so nothing auto-starts.

## Files

| Path | Purpose |
|------|---------|
| `/data/local/chdroid-active-rootfs` | active base pointer |
| `/data/local/<setup-name>-chroot/chroot.sh` | release manager and login entry |
| `/data/local/<setup-name>-chroot/rootfs/` | extracted distro |
| `/data/local/<setup-name>-chroot/bin/busybox` | static busybox bundled with the release |
| `/data/local/ChDroid/bin/` | native helpers, BusyBox and the Android root mount script |
| `/storage/emulated/0/Android/data/com.chdroid.app/files/chdroid-crash.txt` | crash log, dialog off by default |

There is no hostname setting. The prompt uses the setup name, and the rootfs edit drawer reopens the full wizard.
