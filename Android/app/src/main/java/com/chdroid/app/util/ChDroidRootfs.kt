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
        "https://raw.githubusercontent.com/Mafiadan6/ChDroid-rootfs/main/manifest-v2.json"

    /** Offline fallback: every release in the manifest, so nothing vanishes without a network. */
    val BUILTINS = listOf(
        RootfsAsset(
            name = "Ubuntu 24.04 CLI rootfs (ChDroid)",
            description = "Clean Ubuntu 24.04 base CLI rootfs for ChDroid. Rebuilt from " +
                "upstream Droidspaces rootfs. Plain rootfs tarball and chroot tools " +
                "- no Magisk, KernelSU or APatch module, no boot scripts, no " +
                "root-method detection.",
            architecture = "aarch64",
            downloadUrl = "https://github.com/Mafiadan6/ChDroid-rootfs/releases/download/" +
                "v4.4/ChDroid-ubuntu-rootfs-20261029.zip",
            sizeBytes = 561967118L,
            buildDate = "20261029",
            author = "ChDroid",
            sourceRepoName = "ChDroid rootfs"
        ),
        RootfsAsset(
            name = "Arch Linux Minimal (ChDroid)",
            description = "Minimal Arch Linux aarch64 rootfs for ChDroid. Kernel 5.10+ " +
                "required. Clean tarball and chroot tools - no Magisk, KernelSU or " +
                "APatch module, no boot scripts, no root-method detection. Installs " +
                "to /data/local/ubuntu-chroot and runs from ChDroid's terminal.",
            architecture = "aarch64",
            downloadUrl = "https://github.com/Mafiadan6/ChDroid-rootfs/releases/download/" +
                "v4.4/ChDroid-arch-rootfs-minimal-20261029.zip",
            sizeBytes = 112393075L,
            buildDate = "20261029",
            author = "ChDroid",
            sourceRepoName = "ChDroid rootfs"
        ),
        RootfsAsset(
            name = "Debian 13 Minimal (ChDroid)",
            description = "Minimal Debian 13 aarch64 rootfs for ChDroid. Uses apt. Plain " +
                "rootfs tarball and chroot tools - no module, no boot scripts, no " +
                "root-method detection.",
            architecture = "aarch64",
            downloadUrl = "https://github.com/Mafiadan6/ChDroid-rootfs/releases/download/" +
                "v4.4/ChDroid-debian-13-rootfs-20261029.zip",
            sizeBytes = 100020529L,
            buildDate = "20261029",
            author = "ChDroid",
            sourceRepoName = "ChDroid rootfs"
        ),
        RootfsAsset(
            name = "Fedora 45 Minimal (ChDroid)",
            description = "Minimal Fedora 45 aarch64 rootfs for ChDroid. Uses dnf. Extracted " +
                "from the official Fedora container base image. No module, no boot " +
                "scripts, no root-method detection.",
            architecture = "aarch64",
            downloadUrl = "https://github.com/Mafiadan6/ChDroid-rootfs/releases/download/" +
                "v4.4/ChDroid-fedora-45-rootfs-20261029.zip",
            sizeBytes = 56363025L,
            buildDate = "20261029",
            author = "ChDroid",
            sourceRepoName = "ChDroid rootfs"
        ),
        RootfsAsset(
            name = "Alpine Minimal (ChDroid)",
            description = "Minimal Alpine aarch64 rootfs for ChDroid. Uses apk. Plain rootfs " +
                "tarball and chroot tools - no module, no boot scripts, no " +
                "root-method detection.",
            architecture = "aarch64",
            downloadUrl = "https://github.com/Mafiadan6/ChDroid-rootfs/releases/download/" +
                "v4.4/ChDroid-alpine-rootfs-20261029.zip",
            sizeBytes = 11479696L,
            buildDate = "20261029",
            author = "ChDroid",
            sourceRepoName = "ChDroid rootfs"
        ),
        RootfsAsset(
            name = "Artix Minimal (ChDroid)",
            description = "Minimal Artix Linux aarch64 OpenRC rootfs for ChDroid. Uses " +
                "pacman. Plain rootfs tarball and chroot tools - no module, no boot " +
                "scripts, no root-method detection.",
            architecture = "aarch64",
            downloadUrl = "https://github.com/Mafiadan6/ChDroid-rootfs/releases/download/" +
                "v4.4/ChDroid-artix-rootfs-20261029.zip",
            sizeBytes = 169083739L,
            buildDate = "20261029",
            author = "ChDroid",
            sourceRepoName = "ChDroid rootfs"
        ),
        RootfsAsset(
            name = "AzureLinux 3.0 Minimal (ChDroid)",
            description = "Minimal AzureLinux 3.0 aarch64 rootfs for ChDroid. Uses tdnf. " +
                "Plain rootfs tarball and chroot tools - no module, no boot " +
                "scripts, no root-method detection.",
            architecture = "aarch64",
            downloadUrl = "https://github.com/Mafiadan6/ChDroid-rootfs/releases/download/" +
                "v4.4/ChDroid-azurelinux-rootfs-20261029.zip",
            sizeBytes = 88346200L,
            buildDate = "20261029",
            author = "ChDroid",
            sourceRepoName = "ChDroid rootfs"
        ),
        RootfsAsset(
            name = "Kali Linux Minimal (ChDroid)",
            description = "Minimal Kali Linux aarch64 rootfs for ChDroid. Uses apt. Plain " +
                "rootfs tarball and chroot tools - no module, no boot scripts, no " +
                "root-method detection.",
            architecture = "aarch64",
            downloadUrl = "https://github.com/Mafiadan6/ChDroid-rootfs/releases/download/" +
                "v4.4/ChDroid-kali-rootfs-20261029.zip",
            sizeBytes = 117393127L,
            buildDate = "20261029",
            author = "ChDroid",
            sourceRepoName = "ChDroid rootfs"
        ),
        RootfsAsset(
            name = "OpenWrt 24.10 Minimal (ChDroid)",
            description = "Minimal OpenWrt 24.10 aarch64 rootfs for ChDroid. Uses opkg. Plain " +
                "rootfs tarball and chroot tools - no module, no boot scripts, no " +
                "root-method detection.",
            architecture = "aarch64",
            downloadUrl = "https://github.com/Mafiadan6/ChDroid-rootfs/releases/download/" +
                "v4.4/ChDroid-openwrt-rootfs-20261029.zip",
            sizeBytes = 4344463L,
            buildDate = "20261029",
            author = "ChDroid",
            sourceRepoName = "ChDroid rootfs"
        )
    )

    /** Fetch the manifest; returns the offline fallback if unreachable or empty. */
    suspend fun fetchAssets(): List<RootfsAsset> = withContext(Dispatchers.IO) {
        val json = RootfsRepository.httpGet(MANIFEST_URL) ?: return@withContext BUILTINS
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
        }.getOrDefault(BUILTINS).ifEmpty { BUILTINS }
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
