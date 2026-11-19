# Ubuntu-Chroot CLI on any rooted phone

This fork turns Droidspaces into a front end for the **Ubuntu-Chroot v4.4 CLI** release. It runs on any rooted device — Magisk, KernelSU, APatch, a late-loaded `su`, or a plain root shell. There is no root-method detection, no mode toggle, and no branch in the code that behaves differently per root solution.

Ubuntu-Chroot's own `chroot.sh` owns the namespace, mounts, DNS and login shell. Droidspaces is the UI and the terminal in front of it.

## Design

| Area | Stock Droidspaces | This fork |
|------|-------------------|-----------|
| App id | `com.droidspaces.app` | `com.droidspaces.ubuntuchroot` (installs beside stock) |
| Backend status | requires `/data/adb/modules/droidspaces` | both binaries present and executable is enough |
| Installation | writes boot module, suggests reboot | copies binaries, starts the daemon in-session |
| Boot module | `post-fs-data.sh`, `service.sh`, `sepolicy.rule` | not shipped, not installed, not needed |
| Rootfs repo | generic tarballs | Ubuntu-Chroot v4.4 **CLI** ZIP only |
| Rootfs install | Droidspaces tarball pipeline | `install_ubuntu_chroot_cli.sh` installs the release files directly |
| Shell | Droidspaces container login | `sh /data/local/ubuntu-chroot/chroot.sh start root` |

Nothing is written to `/data/adb/modules`. Nothing depends on a reboot. Root can come from anywhere.

## Install flow

1. Install the fork APK. Grant root when prompted.
2. Open **Installation** and install the backend. Binaries are copied to `/data/local/Droidspaces/bin` and the daemon is started in the current root session. No module is written, no reboot is suggested.
3. Open **Containers → + → Repository**. One built-in entry exists:
   - `Ubuntu Chroot 24.04 (CLI)` — 537 MB ZIP, build `20260323`
4. Download it, then install it. `install_ubuntu_chroot_cli.sh` mirrors Ubuntu-Chroot's own `util_functions.sh`: it extracts the static busybox, `chroot.sh`, `post_exec.sh`, the helper tools and `rootfs.tar.gz` into `/data/local/ubuntu-chroot`. An existing rootfs is left alone — it is user data.
5. Use **Settings → Start Ubuntu chroot** to set up the namespace and mounts, or **Open Ubuntu terminal** to drop straight into the Ubuntu shell.

The app never creates a Droidspaces `container.config` for this rootfs and never writes an init. That is deliberate: `chroot.sh` does all of it.

## The in-app terminal

A blank container route (`terminal/`) is the Ubuntu-Chroot shell. `DroidspacesTerminalSession` runs:

```sh
sh /data/local/ubuntu-chroot/chroot.sh start root
```

`chroot.sh` requires a TTY for `enter_chroot`, which the app terminal provides. The Settings row installs the helper first so the same commands work from the app's root shell:

```sh
ubuntu-chroot-bootstrap.sh status
ubuntu-chroot-bootstrap.sh binaries
ubuntu-chroot-bootstrap.sh daemon
ubuntu-chroot-bootstrap.sh install /sdcard/Download/Ubuntu-Chroot-v4.4-20260323-CLI.zip
ubuntu-chroot-bootstrap.sh start
ubuntu-chroot-bootstrap.sh shell
ubuntu-chroot-bootstrap.sh stop
ubuntu-chroot-bootstrap.sh all /sdcard/Download/Ubuntu-Chroot-v4.4-20260323-CLI.zip
```

`status` prints uid, daemon mode, the marker and `chroot.sh status`.

## Headless bootstrap (no app UI)

The helper ships at `scripts/ubuntu-chroot-bootstrap.sh` in the repo and `assets/ubuntu_chroot_bootstrap.sh` in the APK. From any root shell:

```sh
su -c 'sh /sdcard/Download/ubuntu-chroot-bootstrap.sh binaries'
su -c 'sh /sdcard/Download/ubuntu-chroot-bootstrap.sh all /sdcard/Download/Ubuntu-Chroot-v4.4-20260323-CLI.zip'
```

It discovers the fork APK through `pm path com.droidspaces.ubuntuchroot`, streams the correct-arch backend and BusyBox out of `assets/binaries`, writes the session marker, starts the daemon, and installs the Ubuntu-Chroot CLI files.

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

The app id is `com.droidspaces.ubuntuchroot`, so both APKs can coexist.
