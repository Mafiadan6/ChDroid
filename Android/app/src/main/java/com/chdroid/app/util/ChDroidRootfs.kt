// SPDX-License-Identifier: GPL-3.0-or-later
package com.chdroid.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * ChDroid's own rootfs sources, fetched from the ChDroid-rootfs repo manifest.
 * The stock ChDroid repository is not part of this fork's flow. The
 * manifest is a flat JSON array using the same schema as RootfsRepository.
 */
object ChDroidRootfs {
    const val MANIFEST_URL =
        "https://raw.githubusercontent.com/Mafiadan6/ChDroid-rootfs/main/manifest.json"

    /** Offline fallback: the release this fork targets, matching the manifest. */
    val BUILTIN = RootfsAsset(
        name = "Ubuntu 24.04 CLI rootfs (ChDroid)",
        description = "Clean Ubuntu 24.04 base CLI rootfs for ChDroid. Rebuilt from " +
            "upstream Droidspaces rootfs. Plain rootfs tarball and chroot tools - no " +
            "Magisk, KernelSU or APatch module, no boot scripts, no root-method detection.",
        architecture = "aarch64",
        downloadUrl = "https://github.com/Mafiadan6/ChDroid-rootfs/releases/download/" +
            "v4.4/ChDroid-ubuntu-rootfs-20261029.zip",
        sizeBytes = 561_967_118L,
        buildDate = "20261029",
        author = "ChDroid",
        sourceRepoName = "ChDroid rootfs"
    )

    /** Fetch the manifest; returns the offline fallback if unreachable or empty. */
    suspend fun fetchAssets(): List<RootfsAsset> = withContext(Dispatchers.IO) {
        val json = RootfsRepository.httpGet(MANIFEST_URL) ?: return@withContext listOf(BUILTIN)
        runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        RootfsAsset(
                            name = obj.optString("name", ""),
                            description = obj.optString("description", ""),
                            architecture = obj.optString("architecture", ""),
                            downloadUrl = obj.optString("download_url", ""),
                            sizeBytes = obj.optLong("size_bytes", 0L),
                            buildDate = obj.optString("build_date", ""),
                            author = obj.optString("author", "ChDroid"),
                            sourceRepoName = "ChDroid rootfs"
                        )
                    )
                }
            }.filter { it.downloadUrl.startsWith("https://") }
        }.getOrDefault(listOf(BUILTIN)).ifEmpty { listOf(BUILTIN) }
    }

    /** A user-supplied download link, given a stable identity for the downloader. */
    fun fromUrl(url: String): RootfsAsset {
        val clean = url.trim()
        val file = clean.substringAfterLast('/').substringBefore('?')
        val label = file.substringBeforeLast('.').ifBlank { "Custom rootfs" }
        return RootfsAsset(
            name = label.replace('-', ' ').replace('_', ' '),
            description = clean,
            architecture = "aarch64",
            downloadUrl = clean,
            sizeBytes = 0L,
            buildDate = "custom",
            author = "ChDroid link",
            sourceRepoName = "Custom link"
        )
    }
}
