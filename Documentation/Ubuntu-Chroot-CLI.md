# Ubuntu-Chroot CLI on any rooted phone

ChDroid turns ChDroid-OSS into a front end for the **Ubuntu-Chroot v4.4 CLI** release. It runs on any rooted device — Magisk, KernelSU, APatch, a late-loaded `su`, or a plain root shell. There is no root-method detection, no mode toggle, and no branch in the code that behaves differently per root solution.

Ubuntu-Chroot's own `chroot.sh` owns the namespace, mounts, DNS and login shell. ChDroid is the UI and the terminal in front of it.

Rootfs releases come from ChDroid's own [ChDroid-rootfs](https://github.com/Mafiadan6/ChDroid-rootfs) repo: a clean Ubuntu 24.04 rootfs tarball plus chroot tools. The upstream module packaging (`META-INF`, `module.prop`, `service.sh`, root-method detection) is not part of this build.

## Design

| Area | Stock ChDroid | ChDroid |
|------|-------------------|-----------|
| App id | `com.chdroid.app` | `com.chdroid.app` (installs beside stock) |
| Backend status | requires `/data/adb/modules/chdroid` | both binaries present and executable is enough |
| Installation | writes boot module, suggests reboot | copies binaries, starts the daemon in-session |
| Boot module | `post-fs-data.sh`, `service.sh`, `sepolicy.rule` | not shipped, not installed, not needed |
| Rootfs repo | ChDroid generic tarballs | ChDroid-rootfs manifest, clean v4.4 CLI release only |
| Rootfs install | ChDroid tarball pipeline | `install_ubuntu_chroot_cli.sh` installs the clean release files directly |
| Shell | ChDroid container login | `sh /data/local/ubuntu-chroot/chroot.sh start root` |

Nothing is written to `/data/adb/modules`. Nothing depends on a reboot. Root can come from anywhere.

## Install flow

1. Install the fork APK. Grant root when prompted.
2. Open **Installation** and install the backend. Binaries are copied to `/data/local/ChDroid/bin` and the daemon is started in the current root session. No module is written, no reboot is suggested.
3. Open **Containers → + → Get Ubuntu rootfs**. ChDroid fetches the manifest from ChDroid-rootfs. Two built-in entries exist:
   - `Ubuntu 24.04 CLI rootfs (ChDroid)` — 520 MB ZIP (`ChDroid-rootfs-v4.4-20260323.zip`), build `20260323`
   - `Arch Linux Minimal (ChDroid)` — 108 MB ZIP (`ChDroid-arch-rootfs-minimal-20261029.zip`), kernel 5.10+ required
4. Download it, then install it. `install_ubuntu_chroot_cli.sh` extracts the static busybox, `chroot.sh`, `post_exec.sh`, the helper tools and the rootfs tarball (`.tar.gz` for Ubuntu, `.tar.xz` for Arch) straight from the clean ZIP into `/data/local/ubuntu-chroot`. No module packaging, no `module.prop` — metadata comes from `manifest.json`. Reinstalling the same distro keeps your rootfs data; installing a different one stops the chroot and replaces the old rootfs.
5. Use **Settings → Start Ubuntu chroot** to set up the namespace and mounts, or **Open Ubuntu terminal** to drop straight into the Ubuntu shell.

The app never creates a ChDroid `container.config` for this rootfs and never writes an init. That is deliberate: `chroot.sh` does all of it.

## The in-app terminal

A blank container route (`terminal/`) is the Ubuntu-Chroot shell. `ChDroidTerminalSession` runs:

```sh
sh /data/local/ubuntu-chroot/chroot.sh start root
```

`chroot.sh` requires a TTY for `enter_chroot`, which the app terminal provides. The Settings row installs the helper first so the same commands work from the app's root shell:

```sh
ubuntu-chroot-bootstrap.sh status
ubuntu-chroot-bootstrap.sh binaries
ubuntu-chroot-bootstrap.sh daemon
ubuntu-chroot-bootstrap.sh install /sdcard/Download/ChDroid-rootfs-v4.4-20260323.zip
ubuntu-chroot-bootstrap.sh start
ubuntu-chroot-bootstrap.sh shell
ubuntu-chroot-bootstrap.sh stop
ubuntu-chroot-bootstrap.sh all /sdcard/Download/ChDroid-rootfs-v4.4-20260323.zip
```

`status` prints uid, daemon mode, the marker and `chroot.sh status`.

## Headless bootstrap (no app UI)

The helper ships at `scripts/ubuntu-chroot-bootstrap.sh` in the repo and `assets/ubuntu_chroot_bootstrap.sh` in the APK. From any root shell:

```sh
su -c 'sh /sdcard/Download/ubuntu-chroot-bootstrap.sh binaries'
su -c 'sh /sdcard/Download/ubuntu-chroot-bootstrap.sh all /sdcard/Download/ChDroid-rootfs-v4.4-20260323.zip'
```

It discovers the fork APK through `pm path com.chdroid.app`, streams the correct-arch backend and BusyBox out of `assets/binaries`, writes the session marker, starts the daemon, and installs the Ubuntu-Chroot CLI files.

## Ubuntu-Chroot commands

Once installed, the release manager is available directly:

```sh
sh /data/local/ubuntu-chroot/chroot.sh status
sh /data/local/ubuntu-chroot/chroot.sh start root
sh /data/local/ubuntu-chroot/chroot.sh stop
sh /data/local/ubuntu-chroot/chroot.sh restart
sh /data/local/ubuntu-chroot/chroot.sh list-users
```

`start --no-shell` sets up the environment without consuming a terminal; the Settings **Start** row uses that form. `start root` enters the login shell.

## After a reboot

Root may be gone until it is granted again. Reopen the app (or run `ubuntu-chroot-bootstrap.sh binaries daemon` from a root shell), then start the chroot again. No boot service exists, so nothing auto-starts — expected, not a failure.

## Build

```sh
cd Android
./gradlew :app:assembleDebug
```

The app id is `com.chdroid.app`, so both APKs can coexist.
