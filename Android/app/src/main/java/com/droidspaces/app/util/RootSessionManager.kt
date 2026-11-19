package com.droidspaces.app.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Session-root support for the Ubuntu-Chroot CLI frontend.
 *
 * This fork does not install a boot module and does not depend on one being
 * mounted. It works on any rooted phone because everything it needs runs in
 * the current root session, whatever supplied su. Reboots are never an
 * install step.
 */
object RootSessionManager {
    const val SESSION_MARKER = "/data/local/Droidspaces/.ubuntu-chroot-cli"

    /** Leaves a marker the headless bootstrap script can read without the app. */
    suspend fun writeMarker(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd(
            "mkdir -p '/data/local/Droidspaces' && echo 1 > '$SESSION_MARKER' && chmod 600 '$SESSION_MARKER'"
        ).exec().isSuccess
    }

    suspend fun clearMarker(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("rm -f '$SESSION_MARKER'").exec().isSuccess
    }

    /**
     * Start the Droidspaces backend by hand. The Ubuntu chroot itself is driven
     * by UbuntuChrootManager; this keeps the app's backend status honest.
     */
    suspend fun ensureDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val mode = Shell.cmd("${Constants.DROIDSPACES_BINARY_PATH} mode 2>/dev/null").exec()
                .out.firstOrNull()?.trim().orEmpty()
            if (mode == "daemon") return@runCatching

            val start = Shell.cmd(
                "${Constants.DROIDSPACES_BINARY_PATH} daemon > '${Constants.SESSION_DAEMON_LOG}' 2>&1 & " +
                    "sleep 1; ${Constants.DROIDSPACES_BINARY_PATH} mode 2>/dev/null"
            ).exec()
            val after = start.out.lastOrNull()?.trim().orEmpty()
            check(after == "daemon") {
                "daemon did not start (mode=$after, log=${Constants.SESSION_DAEMON_LOG})"
            }
        }
    }

    /**
     * Put the headless bootstrap helper into the same bin directory the app's
     * terminal already has on PATH. From the in-app root shell it is then just
     * `ubuntu-chroot-bootstrap.sh status` - no sideloaded copy needed.
     */
    suspend fun installBootstrap(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val staged = File(context.filesDir, "ubuntu-chroot-bootstrap.sh")
            context.assets.open("ubuntu_chroot_bootstrap.sh").use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            val result = Shell.cmd(
                "mkdir -p '${Constants.INSTALL_PATH}' && " +
                    "cp '${staged.absolutePath}' '${Constants.SESSION_BOOTSTRAP_SCRIPT}' && " +
                    "chmod 755 '${Constants.SESSION_BOOTSTRAP_SCRIPT}' && echo ok"
            ).exec()
            check(result.isSuccess && result.out.any { it.trim() == "ok" }) {
                "could not install the bootstrap helper into ${Constants.INSTALL_PATH}"
            }
            Constants.SESSION_BOOTSTRAP_SCRIPT
        }
    }

    /** Short status line for the Settings card. */
    suspend fun describe(): String = withContext(Dispatchers.IO) {
        val root = Shell.cmd("id -u").exec().out.firstOrNull()?.trim()
        val daemon = Shell.cmd("${Constants.DROIDSPACES_BINARY_PATH} mode 2>/dev/null").exec()
            .out.firstOrNull()?.trim()
        "uid=${root ?: "?"} · backend=${daemon ?: "?"}"
    }
}
