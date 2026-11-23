[English](../../README.md) | 简体中文

<img src="../resources/readme/hero-zh.png" alt="真正的 Linux，运行在已 root 的手机上。ChDroid 直接使用手机自身内核运行原生发行版 rootfs。" width="100%">

[![Latest release](https://img.shields.io/github/v/release/Mafiadan6/ChDroid?label=Release&style=for-the-badge)](https://github.com/Mafiadan6/ChDroid/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)](../../LICENSE)
[![Rootfs releases](https://img.shields.io/badge/Rootfs-v4.4-2EA44F?style=for-the-badge)](https://github.com/Mafiadan6/ChDroid-rootfs/releases/tag/v4.4)
[![Telegram channel](https://img.shields.io/badge/Telegram-Channel-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/chdroid)

# ChDroid

ChDroid 是面向已 root Android 设备的 CLI 优先 Linux rootfs 前端。它直接在手机内核上运行真实发行版 rootfs，没有模拟，也没有虚拟机。rootfs 发行包自带的 `chroot.sh` 负责命名空间、挂载、DNS 和登录 shell。ChDroid 提供安装器、状态控制台、rootfs 卡片和终端。

这是仅 CLI 的分支。它使用设备上已有的 `su`，不检测 root 方案，不写 boot 模块，没有 `post-fs-data`、没有 `service.sh`、没有 `META-INF`、不写入 `/data/adb/modules`，也不需要重启。SELinux 保持 enforcing。

当前应用版本为 **3.0**。应用 id 为 `com.chdroid.app`。开发者：爪卂丂ㄒ乇尺爪工刀。

## 概览

| 项目 | 工作方式 |
|------|----------|
| 运行时 | rootfs 发行包中的 `chroot.sh` 负责命名空间、挂载、DNS 和登录 shell。 |
| root 权限 | 使用任意 root 方案提供的通用 `su`。 |
| 开机 | 不安装模块，不自动启动。 |
| SELinux | 保持 enforcing，没有 permissive 开关。 |
| rootfs 来源 | ChDroid 自己的 [rootfs 仓库](https://github.com/Mafiadan6/ChDroid-rootfs)，版本 `v4.4`。 |
| 应用范围 | 九个 aarch64 CLI rootfs 构建的 Android 前端。 |
| 原生辅助程序 | APK 内含后端、BusyBox 和 shell 资源，用于安装、状态和终端支撑，不替代 `chroot.sh`。 |

## 功能

- **九种 rootfs。** Ubuntu、Arch、Debian、Fedora、Alpine、Artix、AzureLinux、Kali 和 OpenWrt。每个构建都是 aarch64 干净 ZIP，包含 rootfs 压缩包和 chroot 工具。
- **原生速度。** 程序直接运行在手机内核上，没有 VM 层，也没有指令翻译。
- **完整安装向导。** 名称、推荐或自定义配置、稀疏镜像、摘要和进度与普通容器安装保持一致。
- **名称就是路径。** 输入 `Arch Linux` 会安装到 `/data/local/arch-linux-chroot`。提示符使用此名称，因此没有 hostname 设置。
- **真实终端。** Terminal 按钮打开 rootfs shell。输入 `exit` 或按 X 只关闭终端标签页，rootfs 继续运行。只有 Stop 会拆除 rootfs。
- **有用状态。** Panel 标签显示运行中 rootfs 的实时 CPU、已用和可用 RAM、PID 数与 IP。Start 和 Stop 输出到控制台，不占用终端标签页。
- **就地编辑和卸载。** rootfs 卡片动作抽屉重新打开完整向导。卸载需要输入名称确认。
- **可选的 Android root 访问。** 唯一的额外绑定是 `Mount Android root`，它把 Android 文件系统暴露到 `/android-root`，并允许读写 `/data` 和 `/sdcard`。

## 要求

- Android 8 或更新，应用目标为 `minSdk 26`
- 已 root，并且 `su` 可用。Magisk、KernelSU、APatch、延迟加载 root shell 和普通 `su` 一视同仁
- aarch64 设备，所有发布 rootfs 都是 aarch64
- 内核 3.10 或更新，4.9 或更新时大多数发行版表现更好，Arch 需要 5.10 或更新
- 足够的存储空间，ZIP 大小约 4 MB 到 562 MB

## 安装应用

1. 从 [GitHub Releases](https://github.com/Mafiadan6/ChDroid/releases/latest) 下载最新 APK。当前版本为 `v3.0-chdroid`，资源名为 `ChDroid-debug.apk`。
2. 安装 APK，并在应用请求时授予 root。
3. 在首页点击 **New to ChDroid?** 打开简短应用内指南。
4. 打开 **Containers**，点击 **+**，从仓库选择 rootfs，或者把已下载的 ZIP 分享给 ChDroid。

设置流程会把原生辅助程序复制到当前 root 会话中的 `/data/local/ChDroid/bin`。它不会写 boot 模块，也不会要求重启。

## 安装 rootfs

应用从 [v4.4 发布页](https://github.com/Mafiadan6/ChDroid-rootfs/releases/tag/v4.4) 读取 `manifest-v2.json`。九个条目也内置在 APK 中作为离线回退，GitHub 不可达时仍可选择。

| Rootfs | ZIP | 大小 | 包管理器 | 说明 |
|--------|-----|------|----------|------|
| Ubuntu 24.04 CLI rootfs | `ChDroid-ubuntu-rootfs-20261029.zip` | 562 MB | apt | 完整干净 Ubuntu 基础 |
| Arch Linux Minimal | `ChDroid-arch-rootfs-minimal-20261029.zip` | 112 MB | pacman | 内核 5.10 或更新 |
| Debian 13 Minimal | `ChDroid-debian-13-rootfs-20261029.zip` | 100 MB | apt | 稳定 Debian 基础 |
| Fedora 45 Minimal | `ChDroid-fedora-45-rootfs-20261029.zip` | 56 MB | dnf | 来自官方 Fedora minimal 基础 |
| Alpine Minimal | `ChDroid-alpine-rootfs-20261029.zip` | 11 MB | apk | 最小的通用 rootfs |
| Artix Minimal | `ChDroid-artix-rootfs-20261029.zip` | 169 MB | pacman | OpenRC init |
| AzureLinux 3.0 Minimal | `ChDroid-azurelinux-rootfs-20261029.zip` | 88 MB | tdnf | AzureLinux 基础 |
| Kali Linux Minimal | `ChDroid-kali-rootfs-20261029.zip` | 117 MB | apt | 最小 Kali 基础 |
| OpenWrt 24.10 Minimal | `ChDroid-openwrt-rootfs-20261029.zip` | 4 MB | opkg | 集合中最小的构建 |

选择 ZIP 后，向导顺序如下：

1. **ContainerName。** 输入名称成为目录和提示符标签。
2. **RecommendedConfig 或 ContainerConfig。** 压缩包带配置时加载推荐配置，完整配置表单始终可用。
3. **SparseImageConfig。** 选择存储布局和大小。
4. **InstallationSummary。** 检查名称、压缩包和配置。
5. **InstallationProgress。** 查看解压和安装输出。

同一名称下重新安装同一发行版会保留 rootfs 数据。安装不同发行版或不同名称会停止并替换旧安装。`/data/local/chdroid-active-rootfs` 记录当前 base，早于该指针的安装继续使用 `/data/local/ubuntu-chroot`。

## 使用应用

### Containers 标签

ChDroid rootfs 以独立卡片出现，旁边没有合成后端容器。卡片显示设置名称、发行版 id 和运行状态。

- **Start** 通过 `chroot.sh start --no-shell --skip-post-exec` 建立命名空间和挂载，输出进入控制台。
- **Stop** 是唯一拆除操作。它杀死 rootfs 进程、卸载挂载、清理 holder，并让 SELinux 回到 enforcing。
- **Terminal** 通过 `chroot.sh start root` 打开 rootfs shell。
- **Edit** 重新打开完整向导，包括名称、配置、存储和 Android root 绑定。
- **Uninstall** 需要输入名称确认删除。

### Panel 标签

Panel 只列出正在运行的对象，ChDroid rootfs 使用与原生容器卡片相同的动画和间距。实时统计包含 CPU、已用与可用 RAM、PID 数和 IP。这里也有终端入口，运行中的 rootfs 可以直接打开，无需返回 Containers。

### 终端行为

只有 Terminal 按钮打开 shell。应用全程在 `su` 下运行链，从手机当前网络刷新 DNS，应用可选的 Android root 绑定，然后通过 `chroot.sh` 进入 rootfs。

`exit` 和标签页 X 只关闭终端标签页。它们不停止 rootfs，也不会落入 Android 的 `/system/bin/sh`。随时可以从 rootfs 卡片重新打开终端。需要关闭 rootfs 时使用 Stop。

### 路径和数据

- 当前 base：`/data/local/chdroid-active-rootfs` 中记录的路径，例如 `/data/local/arch-linux-chroot`
- 旧 base：`/data/local/ubuntu-chroot`，供指针出现之前的安装使用
- CLI 工具：`chroot.sh`、`post_exec.sh`、`start-hotspot`、`sparsemgr.sh`、`forward-nat.sh`、`bin/busybox` 和 ChDroid identity 脚本都位于当前 base
- 原生辅助程序：`/data/local/ChDroid/bin` 存放后端、BusyBox 和 Android root 挂载脚本
- 崩溃日志：`/storage/emulated/0/Android/data/com.chdroid.app/files/chdroid-crash.txt`。崩溃报告对话框默认关闭，可在 Settings 中开启

## Root shell 和无界面使用

rootfs 发行包管理器仍然可以在任意 root shell 中使用。先读取当前 base，让路径跟随设置名称：

```sh
BASE=$(cat /data/local/chdroid-active-rootfs)
sh "$BASE/chroot.sh" status
sh "$BASE/chroot.sh" start --no-shell --skip-post-exec
sh "$BASE/chroot.sh" start root
sh "$BASE/chroot.sh" stop
```

应用安装的 bootstrap 辅助脚本可在应用 root shell 环境中作为 `ubuntu-chroot-bootstrap.sh` 使用。它支持 `status`、`binaries`、`daemon`、`install`、`start`、`shell`、`stop` 和 `all`。

重启后重新授予 root，打开应用，然后启动 rootfs。没有 boot 服务，所以不会自动启动任何东西。

## 安全模型

rootfs 内的 root 是真实 root。恶意 root 进程可以触达宿主机。日常操作应使用普通用户配合 `sudo`，与任何 Linux 机器一样。

应用保持 SELinux enforcing。可选的 Android root 绑定是设置流程中唯一的宽泛宿主机挂载，它以单个具名开关出现，而不是通用绑定挂载列表。

## 截图

<table>
  <tr>
    <td width="33%" align="center"><img src="../resources/gallery/01-home.png" width="100%"><br>首页</td>
    <td width="33%" align="center"><img src="../resources/gallery/02-containers.png" width="100%"><br>容器</td>
    <td width="33%" align="center"><img src="../resources/gallery/09-panel.png" width="100%"><br>面板</td>
  </tr>
</table>

## 文档

| 页面 | 内容 |
|------|------|
| [Android 应用指南](Usage-Android-App.md) | 屏幕、操作和终端控制 |
| [CLI rootfs 流程](Ubuntu-Chroot-CLI.md) | `chroot.sh` 路径、路径和 shell 命令 |
| [内核配置](Kernel-Configuration.md) | Linux 容器所需内核选项 |
| [社区支持的设备](community-supported-devices.md) | 更广泛项目中的设备和内核源码 |
| [故障排查](Troubleshooting.md) | 更广泛项目中的症状和修复 |
| [卸载](Uninstallation.md) | 从 Android 移除 ChDroid |
| [贡献](../../CONTRIBUTING.md) | 复用规则、构建命令和仓库结构 |
| [设计](../../DESIGN.md) | Android UI 视觉规则 |

## 从源码构建

C 后端和 Android 应用都在本仓库中。APK 构建会为四种手机架构运行 `make`，并把二进制打包为资源，因此需要 musl 工具链。

```sh
# 后端，主机构建
make native

# 后端，手机架构
make aarch64
make x86_64
make armhf
make x86

# 应用，在 Android/ 目录中
cd Android
./build.sh          # 调试版
./build.sh release  # 签名发布版
```

新增 C 源文件时必须加入 Makefile 的 `SRCS`。提交需要 sign off。应用改动使用 `app:` 前缀，后端改动使用 `fix:`、`feat:` 或 `net:` 等前缀。

## 社区与贡献

提问和求助：[Telegram 频道](https://t.me/chdroid)。Bug 和功能请求：[GitHub issues](https://github.com/Mafiadan6/ChDroid/issues)。代码贡献从 [CONTRIBUTING.md](../../CONTRIBUTING.md) 开始。

## 致谢

- [LXC](https://github.com/lxc/lxc)，更广泛 ChDroid 项目的架构灵感来源
- [BusyBox](https://busybox.net/)，用于解压和初始化的静态二进制
- [ReTerminal](https://github.com/RohitKushvaha01/ReTerminal)、[Termux](https://github.com/termux/termux-app) 和 [LXC-Manager](https://github.com/Container-On-Android/LXC-Manager)，终端模拟器后端
- [JetBrains Mono](https://www.jetbrains.com/legalforms/fonts/)，应用中的等宽字体，遵循 [SIL Open Font License 1.1](https://scripts.sil.org/OFL)

## 许可证

[GNU General Public License v3.0](../../LICENSE)。版权所有 (C) 2026 [ravindu644](https://github.com/ravindu644) 及贡献者。
