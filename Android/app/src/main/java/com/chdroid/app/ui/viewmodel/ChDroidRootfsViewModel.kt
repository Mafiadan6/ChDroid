// SPDX-License-Identifier: GPL-3.0-or-later
package com.chdroid.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chdroid.app.service.AssetDownloadState
import com.chdroid.app.service.RootfsDownloadService
import com.chdroid.app.util.ChDroidRootfs
import com.chdroid.app.util.RootfsAsset
import kotlinx.coroutines.launch

/** Download plumbing for ChDroid's own rootfs sources. Fetches our repo manifest. */
class ChDroidRootfsViewModel(application: Application) : AndroidViewModel(application) {

    var downloadStates by mutableStateOf<Map<String, AssetDownloadState>>(emptyMap())
        private set

    /** Assets from the ChDroid-rootfs manifest. Falls back to both built-in releases. */
    var assets by mutableStateOf<List<RootfsAsset>>(ChDroidRootfs.BUILTINS)
        private set

    /** Links added in this session, newest first. */
    var customAssets by mutableStateOf<List<RootfsAsset>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            RootfsDownloadService.states.collect { downloadStates = it }
        }
        viewModelScope.launch {
            assets = ChDroidRootfs.fetchAssets()
        }
    }

    fun addLink(url: String): RootfsAsset {
        val asset = ChDroidRootfs.fromUrl(url)
        if (customAssets.none { it.downloadUrl == asset.downloadUrl }) {
            customAssets = listOf(asset) + customAssets
        }
        return asset
    }

    fun downloadedUri(asset: RootfsAsset): Uri? =
        RootfsDownloadService.findDownloaded(getApplication(), asset.uniqueFilename)

    fun startDownload(asset: RootfsAsset) {
        if (downloadStates[asset.downloadUrl] is AssetDownloadState.Downloading) return
        RootfsDownloadService.start(getApplication(), asset)
    }

    fun cancelDownload(asset: RootfsAsset) =
        RootfsDownloadService.cancel(getApplication(), asset.downloadUrl)

    fun failDownload(asset: RootfsAsset, reason: String) =
        RootfsDownloadService.set(asset.downloadUrl, AssetDownloadState.Failed(reason))

    fun resetAsset(url: String) = RootfsDownloadService.set(url, null)
}
