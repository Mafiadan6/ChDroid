English | [简体中文](./Documentation/zh-CN/README.md)

<img src="Documentation/resources/readme/hero.png" alt="Real Linux on your rooted phone. ChDroid runs a native distro rootfs on the phone's own kernel." width="100%">

[![Latest release](https://img.shields.io/github/v/release/Mafiadan6/ChDroid?label=Release&style=for-the-badge)](https://github.com/Mafiadan6/ChDroid/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)](./LICENSE)
[![Rootfs releases](https://img.shields.io/badge/Rootfs-v4.4-2EA44F?style=for-the-badge)](https://github.com/Mafiadan6/ChDroid-rootfs/releases/tag/v4.4)
[![Telegram group](https://img.shields.io/badge/Telegram-Group-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/chdroidxx)
[![Telegram channel](https://img.shields.io/badge/Telegram-Channel-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/chdroidx)
[![Developer](https://img.shields.io/badge/Developer-bitcockiii-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/bitcockiii)

# ChDroid

ChDroid is a CLI-first Linux rootfs frontend for rooted Android. It runs a real distro rootfs directly on the phone's own kernel, with no emulation and no virtual machine. The release's own `chroot.sh` owns the namespace, mounts, DNS and login shell. ChDroid supplies the installer, the status console, the rootfs card and the terminal.

This is the CLI-only fork. It uses the `su` already on the device, with no root-method detection, no boot module, no `post-fs-data`, no `service.sh`, no `META-INF`, no `/data/adb/modules` write and no reboot step. SELinux stays enforcing.

Current app version is **3.0**. Application id is `com.chdroid.app`. Developer: 爪卂丂ㄒ乇尺爪工刀.

## At a glance

| Area | How it works |
|------|--------------|
| Runtime | The rootfs release's `chroot.sh` owns namespaces, mounts, DNS and the login shell. |
| Root access | Generic `su` from any root solution that provides it. |
| Boot | Nothing is installed into `/data/adb/modules` and nothing auto-starts. |
| SELinux | Stays enforcing, no permissive toggle. |
| Rootfs source | ChDroid's own [rootfs repo](https://github.com/Mafiadan6/ChDroid-rootfs), release `v4.4`. |
| App scope | Android frontend for nine aarch64 CLI rootfs builds. |
| Native helpers | The APK ships the backend, BusyBox and shell assets used for install, status and terminal plumbing. They do not replace `chroot.sh`. |

## What you get

- **Nine rootfs choices.** Ubuntu, Arch, Debian, Fedora, Alpine, Artix, AzureLinux, Kali and OpenWrt. Every build is aarch64 and ships as a clean ZIP with the rootfs tarball and the chroot tools.
- **Native speed.** Programs run on the phone kernel. There is no VM layer and no instruction translation.
- **A complete install wizard.** Name, recommended or custom config, sparse image choice, summary and progress all follow the same path as a normal container install.
- **Your name becomes the install path.** `Arch Linux` installs to `/data/local/arch-linux-chroot`. The prompt uses that name, so there is no hostname setting.
- **A real terminal.** The Terminal pill opens the rootfs shell. Typing `exit` or pressing X closes the tab and leaves the rootfs running. Only Stop tears it down.
- **Useful status.** The Panel tab shows live CPU, memory with available RAM, PIDs and IP for the running rootfs. Start and stop output goes to the console, not into a terminal tab.
- **Edit and uninstall in place.** The rootfs card action drawer reopens the full wizard. Uninstall asks for the typed name before removing the install.
- **Optional Android root access.** The only extra bind is `Mount Android root`, which exposes the Android filesystem at `/android-root` with read and write access to `/data` and `/sdcard`.

## Requirements

- Android 8 or newer, since the app targets `minSdk 26`.
- A rooted device with a working `su`. Magisk, KernelSU, APatch, a late-loaded root shell and plain `su` are all treated the same.
- An aarch64 device. All published rootfs builds are aarch64.
- Kernel 3.10 or newer. Kernel 4.9 or newer is where most distributions behave best. Arch needs kernel 5.10 or newer.
- Free storage for the ZIP plus the extracted rootfs. ZIP sizes range from about 4 MB to 562 MB.

## Install the app

1. Download the newest APK from [GitHub Releases](https://github.com/Mafiadan6/ChDroid/releases/latest). The current release is `v3.0-chdroid` and the asset is `ChDroid-debug.apk`.
2. Install the APK and grant root when the app asks.
3. On the Home tab, tap **New to ChDroid?** for the short in-app guide.
4. Open **Containers**, tap **+**, then choose a rootfs from the repository or share a downloaded ZIP into ChDroid.

The setup flow copies the native helpers into `/data/local/ChDroid/bin` in the current root session. It never writes a boot module and never asks for a reboot.

## Install a rootfs

The app reads `manifest-v2.json` from the [v4.4 release](https://github.com/Mafiadan6/ChDroid-rootfs/releases/tag/v4.4). All nine entries also exist as an offline fallback in the APK, so the picker still works when GitHub is unreachable.

| Rootfs | ZIP | Size | Package manager | Note |
|--------|-----|------|-----------------|------|
| Ubuntu 24.04 CLI rootfs | `ChDroid-ubuntu-rootfs-20261029.zip` | 562 MB | apt | Full clean Ubuntu base |
| Arch Linux Minimal | `ChDroid-arch-rootfs-minimal-20261029.zip` | 112 MB | pacman | Kernel 5.10 or newer |
| Debian 13 Minimal | `ChDroid-debian-13-rootfs-20261029.zip` | 100 MB | apt | Stable Debian base |
| Fedora 45 Minimal | `ChDroid-fedora-45-rootfs-20261029.zip` | 56 MB | dnf | From the official Fedora minimal base |
| Alpine Minimal | `ChDroid-alpine-rootfs-20261029.zip` | 11 MB | apk | Smallest general-purpose rootfs |
| Artix Minimal | `ChDroid-artix-rootfs-20261029.zip` | 169 MB | pacman | OpenRC init |
| AzureLinux 3.0 Minimal | `ChDroid-azurelinux-rootfs-20261029.zip` | 88 MB | tdnf | AzureLinux base |
| Kali Linux Minimal | `ChDroid-kali-rootfs-20261029.zip` | 117 MB | apt | Minimal Kali base |
| OpenWrt 24.10 Minimal | `ChDroid-openwrt-rootfs-20261029.zip` | 4 MB | opkg | Smallest build in the set |

After picking a ZIP, the wizard runs in this order:

1. **ContainerName.** The typed name becomes the directory and the prompt label.
2. **RecommendedConfig or ContainerConfig.** A recommendation is loaded when the archive carries one. The full config form is always reachable.
3. **SparseImageConfig.** Choose the storage layout and size.
4. **InstallationSummary.** Review the name, archive and config.
5. **InstallationProgress.** Watch extraction and install output.

Installing the same distro under the same name keeps the existing rootfs data. Installing a different distro or a different name stops the old install and replaces it. A pointer at `/data/local/chdroid-active-rootfs` records the active base, and older installs at `/data/local/ubuntu-chroot` keep working.

## Use the app

### Containers tab

The ChDroid rootfs appears as its own card, with no synthetic backend container beside it. The card shows the setup name, distro id and running state.

- **Start** sets up the namespace and mounts through `chroot.sh start --no-shell --skip-post-exec`. Output goes to the console.
- **Stop** is the only teardown action. It kills rootfs processes, drops mounts, clears the holder and returns SELinux to enforcing.
- **Terminal** opens the rootfs shell through `chroot.sh start root`.
- **Edit** reopens the full wizard, including name, config, storage and the Android root bind.
- **Uninstall** asks for the typed name to confirm removal.

### Panel tab

The Panel lists running things only, and the ChDroid rootfs uses the same card motion and spacing as a native container card. Live stats include CPU, RAM used with available RAM, PID count and IP address. The terminal control lives here too, so a running rootfs can be opened without going back to Containers.

### Terminal behavior

Only the Terminal pill opens a shell. The app runs the whole chain under `su`, refreshes DNS from the phone's current network, applies the optional Android root bind, then enters the rootfs through `chroot.sh`.

`exit` and the tab X close the terminal tab. They do not stop the rootfs and they never drop into Android's `/system/bin/sh`. Reopen the terminal from the rootfs card at any time. Use Stop when the rootfs itself should shut down.

### Paths and data

- Active base: the path in `/data/local/chdroid-active-rootfs`, for example `/data/local/arch-linux-chroot`.
- Legacy base: `/data/local/ubuntu-chroot`, used by installs made before the pointer existed.
- CLI tools: `chroot.sh`, `post_exec.sh`, `start-hotspot`, `sparsemgr.sh`, `forward-nat.sh`, `bin/busybox` and the ChDroid identity script live in the active base.
- Native helpers: `/data/local/ChDroid/bin` holds the backend, BusyBox and the Android root mount script.
- Crash log: `/storage/emulated/0/Android/data/com.chdroid.app/files/chdroid-crash.txt`. The crash report dialog is off by default and can be enabled in Settings.

## Root shell and headless use

The rootfs release manager stays usable from any root shell. Read the active base first so the path follows the setup name:

```sh
BASE=$(cat /data/local/chdroid-active-rootfs)
sh "$BASE/chroot.sh" status
sh "$BASE/chroot.sh" start --no-shell --skip-post-exec
sh "$BASE/chroot.sh" start root
sh "$BASE/chroot.sh" stop
```

The bootstrap helper installed by the app is available as `ubuntu-chroot-bootstrap.sh` from the app's root shell environment. It supports `status`, `binaries`, `daemon`, `install`, `start`, `shell`, `stop` and `all`.

After a reboot, grant root again, open the app, then start the rootfs. There is no boot service, so nothing starts on its own.

## Security model

Root inside the rootfs is real root. A hostile root process can reach the host. Daily work belongs in an unprivileged user with `sudo`, the same as on any Linux machine.

The app keeps SELinux enforcing. The optional Android root bind is the only broad host mount in the setup flow, and it appears as a single named toggle rather than a generic bind mount list.

## Screenshots

<table>
  <tr>
    <td width="33%" align="center"><img src="Documentation/resources/gallery/01-home.png" width="100%"><br>Home</td>
    <td width="33%" align="center"><img src="Documentation/resources/gallery/02-containers.png" width="100%"><br>Containers</td>
    <td width="33%" align="center"><img src="Documentation/resources/gallery/09-panel.png" width="100%"><br>Panel</td>
  </tr>
</table>

## Documentation

| Page | What it covers |
|------|----------------|
| [Android app guide](Documentation/Usage-Android-App.md) | Screens, actions and terminal controls. |
| [CLI rootfs flow](Documentation/Ubuntu-Chroot-CLI.md) | The `chroot.sh` path, paths and shell commands. |
| [Kernel configuration](Documentation/Kernel-Configuration.md) | Kernel options for Linux containers. |
| [Community-supported devices](Documentation/community-supported-devices.md) | Devices and kernel sources from the wider project. |
| [Troubleshooting](Documentation/Troubleshooting.md) | Symptoms and fixes from the wider project. |
| [Uninstallation](Documentation/Uninstallation.md) | Removing ChDroid from Android. |
| [Contributing](CONTRIBUTING.md) | Reuse rules, build commands and repository structure. |
| [Design](DESIGN.md) | Visual rules for Android UI work. |

## Build from source

The C backend and the Android app are both in this repository. The APK build runs `make` for the four phone arches and packages the binaries as assets, so a musl toolchain is required.

```sh
# backend, host build
make native

# backend, phone architectures
make aarch64
make x86_64
make armhf
make x86

# app, from Android/
cd Android
./build.sh          # debug
./build.sh release  # signed release
```

Adding a C source means adding it to `SRCS` in the Makefile. Commit messages are signed off. App changes use `app:`, backend changes use subsystem prefixes such as `fix:`, `feat:` or `net:`.

## Community and contributing

Questions and help: the [Telegram group](https://t.me/chdroidxx) and [Telegram channel](https://t.me/chdroidx). Developer contact: [Telegram](https://t.me/bitcockiii). Bugs and feature requests: [GitHub issues](https://github.com/Mafiadan6/ChDroid/issues). Code contributions start with [CONTRIBUTING.md](CONTRIBUTING.md).

## Credits

- [LXC](https://github.com/lxc/lxc), architectural inspiration for the wider ChDroid project.
- [BusyBox](https://busybox.net/), the static binary used for extraction and setup.
- [ReTerminal](https://github.com/RohitKushvaha01/ReTerminal), [Termux](https://github.com/termux/termux-app) and [LXC-Manager](https://github.com/Container-On-Android/LXC-Manager), terminal emulator backend.
- [JetBrains Mono](https://www.jetbrains.com/legalforms/fonts/), the monospace typeface in the app, under the [SIL Open Font License 1.1](https://scripts.sil.org/OFL).

## License

[GNU General Public License v3.0](./LICENSE). Copyright (C) 2026 [ravindu644](https://github.com/ravindu644) and contributors.
