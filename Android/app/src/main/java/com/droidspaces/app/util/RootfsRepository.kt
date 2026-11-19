package com.droidspaces.app.util

import android.content.Context
import com.droidspaces.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import android.os.Build
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class RootfsAsset(
    val name: String,
    val description: String,
    val architecture: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val buildDate: String,
    val author: String,
    val sourceRepoName: String
) {
    // Unique filename derived from metadata - avoids conflicts with generic names like rootfs.tar.xz
    // Includes a short hash of the download URL to guarantee uniqueness even across repos
    val uniqueFilename: String get() {
        val ext = when {
            downloadUrl.endsWith(".tar.xz", ignoreCase = true) -> ".tar.xz"
            downloadUrl.endsWith(".tar.gz", ignoreCase = true) -> ".tar.gz"
            downloadUrl.endsWith(".zip", ignoreCase = true)    -> ".zip"
            else -> ".tar.xz"
        }
        val sanitized = "$name $author".trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "-")
            .replace(Regex("-{2,}"), "-")
            .trimEnd('-')
        val urlHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(downloadUrl.toByteArray())
            .take(4)
            .joinToString("") { "%02x".format(it) }
        return "$sanitized-$architecture-$buildDate-$urlHash$ext"
    }
}

sealed class RepoResult {
    data class Success(val assets: List<RootfsAsset>) : RepoResult()
    data class Error(val message: String) : RepoResult()
}

object RootfsRepository {

    private const val OFFICIAL_REPO_URL =
        "https://github.com/Droidspaces/Droidspaces-rootfs-builder/raw/refs/heads/main/rootfs.json"
    private const val OFFICIAL_REPO_NAME = "Droidspaces Official"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT    = 15_000

    /*
     * Ubuntu-Chroot CLI v4.4 is the only release this fork targets. The ZIP is
     * the Magisk module: it carries rootfs.tar.gz plus chroot.sh and busybox.
     * We install those files directly - no module flash, no boot script.
     */
    private const val UC_VERSION = "v4.4"
    private const val UC_TAG = "20260323"
    private const val UC_RELEASE =
        "https://github.com/ravindu644/Ubuntu-Chroot/releases/download/$UC_VERSION"
    private val UBUNTU_CHROOT_ASSET = RootfsAsset(
        name = "Ubuntu Chroot 24.04 (CLI)",
        description = "Ubuntu-Chroot $UC_VERSION CLI: chroot manager + Ubuntu 24.04 rootfs. " +
            "Installed to /data/local/ubuntu-chroot and driven by the app's terminal.",
        architecture = "aarch64",
        downloadUrl = "$UC_RELEASE/Ubuntu-Chroot-$UC_VERSION-$UC_TAG-CLI.zip",
        sizeBytes = 537_517_628L,
        buildDate = UC_TAG,
        author = "ravindu644 · Ubuntu-Chroot",
        sourceRepoName = "Ubuntu-Chroot $UC_VERSION (CLI)"
    )
    private val UBUNTU_CHROOT_ASSETS = listOf(UBUNTU_CHROOT_ASSET)

    suspend fun fetchAllAssets(context: Context): RepoResult = withContext(Dispatchers.IO) {
        val prefs = PreferencesManager.getInstance(context)
        val customRepos = prefs.getCustomRepos()

        // Fetch official + all custom repos concurrently
        val officialDeferred = async { fetchSingleRepo(OFFICIAL_REPO_URL, OFFICIAL_REPO_NAME) }
        val customDeferreds = customRepos.map { (name, url) ->
            async { fetchSingleRepo(url, name) }
        }

        val results = listOf(officialDeferred).plus(customDeferreds).awaitAll()

        val allAssets = mutableListOf<RootfsAsset>()
        val errors = mutableListOf<String>()

        // Always present, even offline: the fork's target rootfs and the reason
        // it is here. The network repos below are additive.
        allAssets.addAll(UBUNTU_CHROOT_ASSETS)

        results.forEach { result ->
            when (result) {
                is RepoResult.Success -> allAssets.addAll(result.assets)
                is RepoResult.Error   -> errors.add(result.message)
            }
        }

        val arch = DeviceArch.suffix(context)
        val filtered = allAssets.filter { it.architecture == arch }

        return@withContext when {
            filtered.isNotEmpty() -> RepoResult.Success(filtered)
            errors.isNotEmpty()    -> RepoResult.Error(errors.joinToString("\n"))
            else                   -> RepoResult.Error(context.getString(R.string.repo_error_no_assets))
        }
    }

    private fun fetchSingleRepo(url: String, repoName: String): RepoResult {
        return runCatching {
            val json = httpGet(url)
                ?: return RepoResult.Error("$repoName: network error")
            val assets = parseRootfsJson(json, repoName)
            if (assets.isEmpty()) RepoResult.Error("$repoName: no assets found")
            else RepoResult.Success(assets)
        }.getOrElse { e ->
            RepoResult.Error("$repoName: ${e.message ?: "unknown error"}")
        }
    }

    internal fun httpGet(url: String): String? {
        // Refuse cleartext: the rootfs supply chain must not be MITM-able (V13).
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout    = READ_TIMEOUT
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return body
    }

    private fun parseRootfsJson(json: String, repoName: String): List<RootfsAsset> {
        val arr = JSONArray(json)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj         = arr.getJSONObject(i)
                val downloadUrl = obj.optString("download_url", "")
                add(
                    RootfsAsset(
                        name           = obj.optString("name", ""),
                        description    = obj.optString("description", ""),
                        architecture   = obj.optString("architecture", ""),
                        downloadUrl    = downloadUrl,
                        sizeBytes      = obj.optLong("size_bytes", 0L),
                        buildDate      = obj.optString("build_date", ""),
                        author         = obj.optString("author", repoName),
                        sourceRepoName = repoName
                    )
                )
            }
        }
    }
}
