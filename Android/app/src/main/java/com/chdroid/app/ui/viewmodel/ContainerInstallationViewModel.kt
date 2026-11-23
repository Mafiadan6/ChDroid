package com.chdroid.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chdroid.app.R
import com.chdroid.app.util.ContainerInfo
import com.chdroid.app.util.ContainerManager
import com.chdroid.app.util.ContainerStatus
import com.chdroid.app.util.Constants

import com.chdroid.app.util.ContainerConfigState
import com.chdroid.app.util.ValidationUtils
import com.chdroid.app.util.withConfig
import com.chdroid.app.util.HostCapabilities
import com.chdroid.app.util.RootfsConfig
import com.chdroid.app.util.toConfigState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContainerInstallationViewModel : ViewModel() {
    var tarballUri: Uri? by mutableStateOf(null)
        private set

    /** True until the archive has been checked for a container.config to prefill from. */
    var readingRecommendation by mutableStateOf(true)
        private set
    var recommendationNotice: String? by mutableStateOf(null)
        private set
    /** The archive carried a container.config and configState now holds all of it. */
    var recommendationLoaded by mutableStateOf(false)
        private set

    var containerName: String by mutableStateOf("")
        private set

    var hostname: String by mutableStateOf("")
        private set

    var useSparseImage: Boolean by mutableStateOf(true)
        private set

    var sparseImageSizeGB: Int by mutableStateOf(8)
        private set

    /**
     * Destination volume for the rootfs, or null for the default location under
     * CONTAINERS_BASE_PATH. Only the bulk data moves; config and .env stay internal.
     */
    var storageDir: String? by mutableStateOf(null)
        private set

    /** All editable networking/security/advanced config, hoisted as one value. */
    var configState: ContainerConfigState by mutableStateOf(ContainerConfigState())
        private set

    /**
     * ChDroid rootfs ZIP installs only. Lives here so it survives every step of
     * the shared wizard and reaches ContainerInstaller via [buildConfig].
     */
    var mountAndroidRoot: Boolean by mutableStateOf(false)
        private set

    fun setTarball(context: Context, uri: Uri) {
        // Returning to the first wizard page must not reapply defaults over user edits.
        if (tarballUri == uri) return
        tarballUri = uri
        val appContext = context.applicationContext
        viewModelScope.launch {
            try {
                val recommended = withContext(Dispatchers.IO) { RootfsConfig.read(appContext, uri) }
                if (recommended != null) {
                    containerName = ValidationUtils.normalizeContainerName(recommended.name)
                    hostname = recommended.hostname
                    useSparseImage = recommended.useSparseImage
                    sparseImageSizeGB = recommended.sparseImageSizeGB ?: 8
                    // Applied whole, hardware access and privileged mode included. The
                    // RecommendedConfig page sends the user through the form to review it.
                    configState = recommended.toConfigState().let { HostCapabilities.state.value?.coerce(it) ?: it }
                    recommendationLoaded = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recommendationNotice = appContext.getString(R.string.rootfs_config_invalid, e.message.orEmpty())
            } finally {
                readingRecommendation = false
            }
        }
    }

    fun setName(name: String, hostname: String) {
        this.containerName = name
        this.hostname = hostname
    }

    fun setSparseImageConfig(useSparseImage: Boolean, sizeGB: Int, storageDir: String? = null) {
        this.useSparseImage = useSparseImage
        this.sparseImageSizeGB = sizeGB
        this.storageDir = storageDir
    }

    fun setConfig(config: ContainerConfigState) {
        this.configState = config
    }

    fun updateMountAndroidRoot(value: Boolean) {
        this.mountAndroidRoot = value
    }

    fun buildConfig(): ContainerInfo? {
        if (tarballUri == null) return null
        if (containerName.isEmpty()) return null

        return ContainerInfo(
            name = containerName,
            hostname = hostname.ifEmpty { ValidationUtils.sanitizeHostname(containerName) },
            rootfsPath = if (useSparseImage) {
                ContainerManager.getSparseImagePath(containerName, storageDir)
            } else {
                ContainerManager.getRootfsPath(containerName, storageDir)
            },
            status = ContainerStatus.STOPPED, // Default status for new container
            useSparseImage = useSparseImage,
            sparseImageSizeGB = if (useSparseImage) sparseImageSizeGB else null,
            mountAndroidRoot = mountAndroidRoot,
        ).withConfig(HostCapabilities.state.value?.coerce(configState) ?: configState)
    }

    fun reset() {
        tarballUri = null
        readingRecommendation = true
        recommendationNotice = null
        recommendationLoaded = false
        containerName = ""
        hostname = ""
        useSparseImage = true
        sparseImageSizeGB = 8
        storageDir = null
        configState = ContainerConfigState()
        mountAndroidRoot = false
    }
}

