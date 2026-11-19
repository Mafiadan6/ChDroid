package com.droidspaces.app.util

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Drives the Ubuntu-Chroot v4.4 CLI release directly.
 *
 * The release ZIP is a Magisk module, but its useful parts are plain files:
 * chroot.sh, a static busybox, post_exec.sh and rootfs.tar.gz. We install those
 * to /data/local/ubuntu-chroot with no module flash and no boot script. The
 * chroot's namespace, mounts, DNS and login shell all come from chroot.sh -
 * Droidspaces is only the UI and the terminal in this mode.
 */
object UbuntuChrootManager {

    /** True when the CLI manager and a usable rootfs are on the device. */
    suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd(
            "test -x '${Constants.UBUNTU_CHROOT_SCRIPT}' && " +
                "test -x '${Constants.UBUNTU_CHROOT_ROOTFS}/bin/sh'"
        ).exec().isSuccess
    }

    suspend fun status(): String = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext "not installed"
        Shell.cmd("sh '${Constants.UBUNTU_CHROOT_SCRIPT}' status 2>&1").exec()
            .out.joinToString(" ").trim().ifEmpty { "unknown" }
    }

    /** Start the namespace and mounts without consuming a terminal. */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(isInstalled()) { "rootfs is not installed" }
            val result = Shell.cmd(
                "sh '${Constants.UBUNTU_CHROOT_SCRIPT}' start --no-shell 2>&1"
            ).exec()
            val output = result.out.joinToString("\n").trim()
            if (!result.isSuccess) throw Exception(output.ifEmpty { "chroot start failed" })
        }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val result = Shell.cmd("sh '${Constants.UBUNTU_CHROOT_SCRIPT}' stop 2>&1").exec()
            if (!result.isSuccess) {
                throw Exception(result.out.joinToString("\n").ifEmpty { "chroot stop failed" })
            }
        }
    }

    /** Install the CLI release ZIP into /data/local/ubuntu-chroot. */
    suspend fun install(
        context: Context,
        zipUri: Uri,
        logger: ViewModelLogger,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val zip = FilePickerUtils.realPath(context, zipUri) ?: run {
                logger.i("Copying rootfs ZIP to temporary location...")
                val copy = File("${context.cacheDir}/chdroid-rootfs.zip")
                context.contentResolver.openInputStream(zipUri)?.use { input ->
                    FileOutputStream(copy).use { input.copyTo(it) }
                } ?: throw Exception("Failed to open rootfs ZIP")
                copy.absolutePath
            }
            logger.i("rootfs ZIP: $zip")

            val installer = ContainerInstaller.deployAsset(context, "install_ubuntu_chroot_cli.sh")
            try {
                val cacheDir = File(context.cacheDir, "ubuntu-chroot-install").also { it.mkdirs() }
                val result = Shell.cmd(
                    "BUSYBOX_PATH=${Constants.BUSYBOX_BINARY_PATH} " +
                        "${ContainerCommandBuilder.quote(installer.absolutePath)} " +
                        "${ContainerCommandBuilder.quote(zip)} " +
                        "${Constants.BUSYBOX_BINARY_PATH} " +
                        "${ContainerCommandBuilder.quote(cacheDir.absolutePath)} 2>&1"
                ).exec()
                result.out.map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                    if (line.startsWith("[UC-INSTALL]")) logger.i(line) else logger.d(line)
                }
                if (!result.isSuccess) {
                    throw Exception(result.err.joinToString("\n").ifEmpty { "CLI install failed" })
                }
                check(isInstalled()) { "installer finished but rootfs is not usable" }
                logger.i("rootfs ready at ${Constants.UBUNTU_CHROOT_SCRIPT}")
            } finally {
                installer.delete()
            }
        }
    }

    /** Printed in the terminal-facing Settings row. */
    fun terminalCommand(): String = "sh '${Constants.UBUNTU_CHROOT_SCRIPT}' start root"
}
