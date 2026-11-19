// SPDX-License-Identifier: GPL-3.0-or-later
package com.droidspaces.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droidspaces.app.service.AssetDownloadState
import com.droidspaces.app.service.RootfsDownloadService
import com.droidspaces.app.util.ChDroidRootfs
import com.droidspaces.app.util.RootfsAsset
import kotlinx.coroutines.launch

/** Only the download plumbing for ChDroid's own rootfs sources. No repo fetch. */
class ChDroidRootfsViewModel(application: Application) : AndroidViewModel(application) {

    var downloadStates by mutableStateOf<Map<String, AssetDownloadState>>(emptyMap())
        private set

    /** Links added in this session, newest first. The built-in entry is always shown separately. */
    var customAssets by mutableStateOf<List<RootfsAsset>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            RootfsDownloadService.states.collect { downloadStates = it }
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
