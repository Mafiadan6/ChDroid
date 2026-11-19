package com.droidspaces.app.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Session-root support for KernelSU builds loaded through DirtyFrag (or any
 * other late-load / temporary-root chain).
 *
 * On those devices `su` grants real root for the current boot, but nothing in
 * /data/adb/modules is ever mounted at boot: post-fs-data.sh and service.sh do
 * not run, sepolicy.rule is never loaded, and a reboot drops root entirely.
 * The stock app treats the missing module directory as a fatal backend error,
 * so this object is the single place that knows how to run without it.
 */
object TempRootManager {
    const val TEMP_ROOT_MARKER = "/data/local/Droidspaces/.temp_root"
    private const val KSU_BIN = "/data/adb/ksu/bin/ksud"

    /** True when the app should use the in-session bootstrap path. */
    fun isTempRootMode(context: Context): Boolean {
        val prefs = PreferencesManager.getInstance(context)
        return prefs.tempRootMode || (prefs.autoDetectTempRoot && detectKernelSu())
    }

    /**
     * KernelSU's userspace binary is the reliable tell for a late-loaded KSU
     * build. This is a hint, never a gate: the user can force the mode on or
     * off from Settings, and [isTempRootMode] is the only reader that matters.
     */
    private fun detectKernelSu(): Boolean {
        val result = Shell.cmd(
            "if command -v ksud >/dev/null 2>&1 || [ -x '$KSU_BIN' ]; then echo ksu; fi; " +
                "if [ -d /data/adb/ksu ]; then echo dir; fi"
        ).exec()
        return result.out.any { it.contains("ksu", ignoreCase = true) }
    }

    /** Leaves a marker the headless bootstrap script can read without the app. */
    suspend fun writeMarker(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd(
            "mkdir -p '/data/local/Droidspaces' && echo 1 > '$TEMP_ROOT_MARKER' && chmod 600 '$TEMP_ROOT_MARKER'"
        ).exec().isSuccess
    }

    suspend fun clearMarker(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("rm -f '$TEMP_ROOT_MARKER'").exec().isSuccess
    }

    /**
     * Start the daemon by hand. With no boot script there is nothing else that
     * can do this, and the app's libsu shell has the same seccomp restrictions
     * as any other su shell - the daemon exists precisely to escape them.
     */
    suspend fun ensureDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val mode = Shell.cmd("${Constants.DROIDSPACES_BINARY_PATH} mode 2>/dev/null").exec()
                .out.firstOrNull()?.trim().orEmpty()
            if (mode == "daemon") return@runCatching

            val start = Shell.cmd(
                "${Constants.DROIDSPACES_BINARY_PATH} daemon > '${Constants.TEMP_ROOT_DAEMON_LOG}' 2>&1 & " +
                    "sleep 1; ${Constants.DROIDSPACES_BINARY_PATH} mode 2>/dev/null"
            ).exec()
            val after = start.out.lastOrNull()?.trim().orEmpty()
            check(after == "daemon") {
                "daemon did not start (mode=$after, log=${Constants.TEMP_ROOT_DAEMON_LOG})"
            }
        }
    }

    /**
     * Put the headless bootstrap helper into the same bin directory the app's
     * terminal already has on PATH. From the in-app root shell it is then just
     * `temproot-bootstrap.sh status` (or `... all <zip>`) - no sideloaded copy
     * of the script and no second shell needed.
     */
    suspend fun installBootstrap(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val staged = File(context.filesDir, "temproot-bootstrap.sh")
            context.assets.open("temp_root_bootstrap.sh").use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            val result = Shell.cmd(
                "mkdir -p '${Constants.INSTALL_PATH}' && " +
                    "cp '${staged.absolutePath}' '${Constants.TEMP_ROOT_BOOTSTRAP_SCRIPT}' && " +
                    "chmod 755 '${Constants.TEMP_ROOT_BOOTSTRAP_SCRIPT}' && echo ok"
            ).exec()
            check(result.isSuccess && result.out.any { it.trim() == "ok" }) {
                "could not install the bootstrap helper into ${Constants.INSTALL_PATH}"
            }
            Constants.TEMP_ROOT_BOOTSTRAP_SCRIPT
        }
    }

    /** Command to show next to the root-terminal row in Settings. */
    fun terminalHint(): String = "sh ${Constants.TEMP_ROOT_BOOTSTRAP_SCRIPT} status"

    /** Short status line for the Settings card. */
    suspend fun describe(): String = withContext(Dispatchers.IO) {
        val root = Shell.cmd("id -u").exec().out.firstOrNull()?.trim()
        val daemon = Shell.cmd("${Constants.DROIDSPACES_BINARY_PATH} mode 2>/dev/null").exec()
            .out.firstOrNull()?.trim()
        "uid=${root ?: "?"} · backend=${daemon ?: "?"}"
    }
}
