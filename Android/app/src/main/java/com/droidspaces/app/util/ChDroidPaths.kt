package com.droidspaces.app.util

import java.io.File

/**
 * The CLI rootfs lives in a distro-named directory, so an Arch install shows up
 * as /data/local/arch-chroot instead of the inherited /data/local/ubuntu-chroot.
 * A pointer file records the active base; installs made before it existed keep
 * using the legacy path.
 */
object ChDroidPaths {
    private const val POINTER = Constants.CHDROID_ROOTFS_POINTER
    private const val LEGACY = Constants.CHDROID_ROOTFS_LEGACY_BASE

    @Volatile
    private var current: String = LEGACY

    val base: String
        get() {
            val pointer = runCatching { File(POINTER).readText().trim() }.getOrNull().orEmpty()
            if (isUsable(pointer) && File("$pointer/chroot.sh").exists()) {
                if (pointer != current) current = pointer
            }
            return current
        }

    val script: String get() = "$base/chroot.sh"
    val rootfs: String get() = "$base/rootfs"
    val mountFlag: String get() = "$base/mount-android-root"

    fun refresh() {
        val pointer = runCatching { File(POINTER).readText().trim() }.getOrNull().orEmpty()
        current = if (isUsable(pointer) && File("$pointer/chroot.sh").exists()) pointer else LEGACY
    }

    fun use(basePath: String) {
        if (isUsable(basePath)) current = basePath
    }

    private fun isUsable(path: String): Boolean =
        path.startsWith("/data/local/") && path.length < 128 && !path.contains("..")
}
