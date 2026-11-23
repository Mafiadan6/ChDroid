package com.chdroid.app.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

enum class ChDroidBackendStatus { Checking, Available, UpdateAvailable, NotInstalled, ModuleMissing }

object ChDroidChecker {
    private const val CHDROID_BINARY_PATH = Constants.CHDROID_BINARY_PATH
    private const val BUSYBOX_BINARY_PATH = Constants.BUSYBOX_BINARY_PATH
    /**
     * Check if both chdroid and busybox binaries are available and executable (non-blocking).
     * Returns status without blocking the calling thread.
     *
     * Performance: ~10-50ms (runs in background, never blocks UI)
     */
    suspend fun checkBackendStatus(context: Context): ChDroidBackendStatus = withContext(Dispatchers.IO) {
        // One shell round-trip, with a retry on an empty answer. Several screens
        // check the backend while a long stop command is on the shared root
        // shell; separate execs there returned empty and made the app report the
        // backend missing, which forced a reinstall after every stop.
        val probe =
            "if [ -x '$CHDROID_BINARY_PATH' ] && [ -x '$BUSYBOX_BINARY_PATH' ]; " +
                "then echo OK; else echo MISSING; fi"
        repeat(3) { attempt ->
            val out = Shell.cmd(probe).exec().out.map { it.trim() }
            when {
                out.contains("OK") -> return@withContext ChDroidBackendStatus.Available
                out.contains("MISSING") -> return@withContext ChDroidBackendStatus.ModuleMissing
                attempt == 2 -> return@withContext ChDroidBackendStatus.ModuleMissing
            }
        }
        ChDroidBackendStatus.ModuleMissing
    }

    /**
     * Get chdroid version by executing 'chdroid version' command (non-blocking).
     * Returns version string or null if unavailable.
     *
     * Performance: ~10-50ms (runs in background, never blocks UI)
     */
    suspend fun getChDroidVersion(): String? = withContext(Dispatchers.IO) {
        val result = Shell.cmd("$CHDROID_BINARY_PATH version 2>&1").exec()
        if (result.isSuccess) result.out.firstOrNull()?.trim()?.removePrefix("v") else null
    }

    /**
     * Check if backend update is available by comparing installed binary hash with asset binary hash.
     * Returns true if hashes differ (update available), false otherwise.
     *
     * Performance: ~50-200ms (reads both files and calculates hashes)
     */
    suspend fun checkUpdateAvailable(context: Context): Boolean = withContext(Dispatchers.IO) {
        val installed = Shell.cmd("md5sum $CHDROID_BINARY_PATH 2>&1 | cut -d' ' -f1").exec()
        if (!installed.isSuccess || installed.out.isEmpty()) return@withContext false
        val assetHash = try {
            context.assets.open("binaries/chdroid-${DeviceArch.suffix(context)}").use { calculateMD5(it) }
        } catch (e: Exception) {
            return@withContext false
        }
        installed.out[0].trim() != assetHash
    }

    /**
     * Calculate MD5 hash of an InputStream.
     */
    private fun calculateMD5(inputStream: InputStream): String =
        MessageDigest.getInstance("MD5").digest(inputStream.readBytes()).joinToString("") { "%02x".format(it) }

}

