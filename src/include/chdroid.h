/*
 * ChDroid v7 - High-performance Container Runtime
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef CHDROID_H
#define CHDROID_H

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <arpa/inet.h>
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <glob.h>
#include <grp.h>
#include <limits.h>
#include <linux/if_link.h>
#include <net/if.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/signalfd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/utsname.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#ifndef RAMFS_MAGIC
#define RAMFS_MAGIC 0x858458f6
#endif
#ifndef TMPFS_MAGIC
#define TMPFS_MAGIC 0x01021994
#endif
#ifndef CGROUP2_SUPER_MAGIC
#define CGROUP2_SUPER_MAGIC 0x63677270
#endif
#ifndef BINDERFS_SUPER_MAGIC
#define BINDERFS_SUPER_MAGIC 0x6c6f6f70
#endif

/* Cgroup Namespace support (Linux 4.6+) */
#ifndef CLONE_NEWCGROUP
#define CLONE_NEWCGROUP 0x02000000
#endif

/* Constants */

#define CHDROID_PROJECT_NAME "ChDroid"
#define CHDROID_VERSION "3.0"
#define CHDROID_MIN_KERNEL_MAJOR 3
#define CHDROID_MIN_KERNEL_MINOR 10

/* The smallest --pids-limit we accept. The limit counts every task in the
 * container's cgroup: the per-boot intermediate and init are always there,
 * and an enter or run session adds three before its shell forks anything.
 * Below this a container may boot, but there is no room to get a shell. */
#define CHDROID_MIN_PICHDROID_LIMIT 16
#define CHDROID_RECOMMENDED_KERNEL_MAJOR 4
#define CHDROID_RECOMMENDED_KERNEL_MINOR 14
#define CHDROID_AUTHOR "ravindu644"
#define CHDROID_REPO "https://github.com/Mafiadan6/ChDroid"
#define CHDROID_UUID_LEN 32
#define CHDROID_STOP_TIMEOUT 15       /* seconds */
#define CHDROID_RETRY_DELAY_US 200000 /* 200ms */
#define CHDROID_REBOOT_EXIT 249       /* exit code: in-container reboot */

/* Workspace paths */
#define CHDROID_WORKSPACE_ANDROID "/data/local/ChDroid"
#define CHDROID_WORKSPACE_LINUX "/var/lib/ChDroid"
#define CHDROID_CONTAINERS_DIR "Containers"
#define CHDROID_PICHDROID_SUBDIR "Pids"
#define CHDROID_IMG_MOUNT_ROOT_UNIVERSAL "/mnt/ChDroid"
#define CHDROID_BIND_INITIAL_CAP 4
#define CHDROID_DEFAULT_INIT "/sbin/init"
#define CHDROID_VOLATILE_SUBDIR "Volatile"
#define CHDROID_LOGS_SUBDIR "Logs"
#define CHDROID_NET_SUBDIR "Net"
#define CHDROID_ANDROID_TMPFS_CONTEXT "u:object_r:tmpfs:s0"
#define CHDROID_ANDROID_VOLD_CONTEXT "u:object_r:vold_data_file:s0"

/* Common Paths & Patterns */
#define CHDROID_PROC_ROOT_FMT "/proc/%d/root"
#define CHDROID_PROC_STATUS_FMT "/proc/%d/status"
#define CHDROID_FW_PATH_FILE "/sys/module/firmware_class/parameters/path"
#define CHDROID_SYSTEMD_CONTAINER_MARKER "/run/systemd/container"

/* Hardening constants */
#define CHDROID_DEFAULT_TTY_GID 5

/* File Extensions */
#define CHDROID_EXT_PID ".pid"
#define CHDROID_EXT_LOCK ".lock"

/* Signals */
#define CHDROID_SIG_STOP (SIGRTMIN + 3)

/* Colors for output */
#define C_RESET "\033[0m"
#define C_RED "\033[1;31m"
#define C_GREEN "\033[1;32m"
#define C_YELLOW "\033[1;33m"
#define C_BLUE "\033[1;34m"
#define C_CYAN "\033[1;36m"
#define C_WHITE "\033[1;37m"
#define C_DIM "\033[2m"
#define C_BOLD "\033[1m"

/* Also mutes this container's log, see ds_log_internal. */
#define CHDROID_LOG_PEEK 2

/* Privileged Mode Flags */
#define CHDROID_PRIV_NOMASK (1 << 0) /* No jail masks (/proc, /sys) */
#define CHDROID_PRIV_NOCAPS (1 << 1) /* No capability drops */
#define CHDROID_PRIV_NOSEC (1 << 2)  /* Minimal seccomp only */
#define CHDROID_PRIV_SHARED (1 << 3) /* MS_SHARED root propagation */
#define CHDROID_PRIV_FULL (0xFF)     /* All above */

/* Types first: the other folder headers take a struct ds_config, and may
 * take net.h's netlink context or handshake. */
#include "config/config.h"
#include "net/net.h"

#include "android/android.h"
#include "boot/boot.h"
#include "cgroup/cgroup.h"
#include "cli/cli.h"
#include "container/container.h"
#include "core/core.h"
#include "daemon/daemon.h"
#include "dev/dev.h"
#include "hw/hw.h"
#include "monitor/monitor.h"
#include "rootfs/rootfs.h"
#include "security/security.h"
#include "status/status.h"
#include "tty/tty.h"
#include "virt/virt.h"

#endif /* CHDROID_H */
