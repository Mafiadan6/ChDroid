package com.chdroid.app.util

import android.content.Context
import org.json.JSONObject

data class AppUpdateInfo(
    val version: String,
    val releaseUrl: String
)

object AppUpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Mafiadan6/ChDroid/releases/latest"

    // Blocking, call from Dispatchers.IO. Null when the installed build is
    // already current and on any failure, so the banner just stays hidden: an
    // offline device should see nothing, not a warning.
    fun fetchLatest(context: Context): AppUpdateInfo? = runCatching {
        val body = RootfsRepository.httpGet(LATEST_RELEASE_URL) ?: return null
        val json = JSONObject(body)
        val tag = json.optString("tag_name")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val latest = versionNumber(tag) ?: return null
        val current = versionNumber(installed ?: return null) ?: return null
        if (latest <= current) return null
        AppUpdateInfo(version = tag, releaseUrl = json.optString("html_url"))
    }.getOrNull()

    // Release tags are vX.Y.Z and the installed versionName comes straight from
    // CHDROID_VERSION in chdroid.h, with a -chdroid suffix. Read up to three
    // numbers so both "3.0-chdroid" and "v3.0.1" compare correctly.
    private fun versionNumber(text: String): Int? {
        val parts = Regex("\\d+").findAll(text).map { it.value.toInt() }.toList()
        if (parts.isEmpty()) return null
        val major = parts[0]
        val minor = parts.getOrNull(1) ?: 0
        val patch = parts.getOrNull(2) ?: 0
        return major * 10_000 + minor * 100 + patch
    }
}
