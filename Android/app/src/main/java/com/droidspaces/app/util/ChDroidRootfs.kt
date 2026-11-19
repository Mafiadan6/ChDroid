// SPDX-License-Identifier: GPL-3.0-or-later
package com.droidspaces.app.util

/**
 * ChDroid's rootfs sources. These are the only downloads the app offers: the
 * built-in Ubuntu-Chroot v4.4 CLI release and links the user supplies. The
 * stock Droidspaces rootfs repository is not part of this fork's flow.
 */
object ChDroidRootfs {
    const val VERSION = "v4.4"
    const val TAG = "20260323"
    const val RELEASE = "https://github.com/ravindu644/Ubuntu-Chroot/releases/download/$VERSION"
    const val DOWNLOAD_URL = "$RELEASE/Ubuntu-Chroot-$VERSION-$TAG-CLI.zip"

    val BUILTIN = RootfsAsset(
        name = "Ubuntu Chroot 24.04 (CLI)",
        description = "Ubuntu-Chroot $VERSION CLI: chroot manager and Ubuntu 24.04 rootfs. " +
            "Installed to /data/local/ubuntu-chroot and driven by ChDroid's terminal.",
        architecture = "aarch64",
        downloadUrl = DOWNLOAD_URL,
        sizeBytes = 537_517_628L,
        buildDate = TAG,
        author = "ravindu644 · Ubuntu-Chroot",
        sourceRepoName = "Ubuntu-Chroot $VERSION (CLI)"
    )

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
