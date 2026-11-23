package com.chdroid.app.util

/**
 * Centralized constants for the entire application.
 * Single source of truth for all paths, keys, and configuration values.
 */
object Constants {
    // Installation paths
    const val INSTALL_PATH = "/data/local/ChDroid/bin"
    const val CHDROID_BINARY_NAME = "chdroid"
    const val BUSYBOX_BINARY_NAME = "busybox"
    const val CHDROID_BINARY_PATH = "$INSTALL_PATH/$CHDROID_BINARY_NAME"
    const val BUSYBOX_BINARY_PATH = "$INSTALL_PATH/$BUSYBOX_BINARY_NAME"
    const val MAGISK_MODULE_PATH = "/data/adb/modules/chdroid"

    // Container paths
    const val CONTAINERS_BASE_PATH = "/data/local/ChDroid/Containers"
    const val MODULE_SYSTEM_BIN_PATH = "$MAGISK_MODULE_PATH/system/bin"
    const val SYSTEM_BIN_SYMLINK_PATH = "$MODULE_SYSTEM_BIN_PATH/$CHDROID_BINARY_NAME"

    const val DAEMON_MODE_FILE = "/data/local/ChDroid/.daemon_mode"
    const val DAEMON_PID_FILE = "/data/local/ChDroid/chdroidd.pid"

    // Session-root paths. No boot module and no root-method detection: the
    // fork runs on any rooted phone.
    const val SESSION_DAEMON_LOG = "/data/local/ChDroid/Logs/ubuntu-chroot-daemon.log"
    const val SESSION_BOOTSTRAP_SCRIPT = "$INSTALL_PATH/ubuntu-chroot-bootstrap.sh"
    // The active rootfs lives in a distro-named directory, e.g.
    // /data/local/arch-chroot. ChDroidPaths reads the pointer and resolves it.
    const val CHDROID_ROOTFS_POINTER = "/data/local/chdroid-active-rootfs"
    const val CHDROID_ROOTFS_LEGACY_BASE = "/data/local/ubuntu-chroot"
    const val CHDROID_MOUNT_SCRIPT = "$INSTALL_PATH/android-root-mount.sh"
    const val CONTAINER_CONFIG_FILE = "container.config"
    // Default network mode, the single source of truth for both the ContainerInfo
    // model default and the config parser default (they used to disagree).
    const val DEFAULT_NET_MODE = "nat"

    const val KERNEL_CONFIG_DOC_URL = "https://github.com/Mafiadan6/ChDroid/blob/main/Documentation/Kernel-Configuration.md"

    // Minimum storage requirements
    const val MIN_STORAGE_GB = 4

    // NAT Networking constants
    const val NAT_IP_PREFIX = "172.28"
    const val NAT_OCTET_MIN = 1
    const val NAT_OCTET_MAX = 254

    /**
     * Check if chdroid binary is available in system PATH
     * Returns the command to use (either "chdroid" if in PATH, or full path otherwise)
     */
    fun getChDroidCommand(): String {
        val result = com.topjohnwu.superuser.Shell.cmd("command -v chdroid 2>&1").exec()
        return if (result.isSuccess && result.out.isNotEmpty() && result.out[0].isNotBlank()) {
            // chdroid is in PATH, use just the command name
            "chdroid"
        } else {
            // chdroid is not in PATH, use full path
            CHDROID_BINARY_PATH
        }
    }
}
