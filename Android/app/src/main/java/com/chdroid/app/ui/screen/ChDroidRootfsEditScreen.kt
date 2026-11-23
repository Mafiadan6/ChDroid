package com.chdroid.app.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chdroid.app.R
import com.chdroid.app.ui.component.ContainerConfigHost
import com.chdroid.app.ui.component.ChDroidTextFieldDefaults
import com.chdroid.app.ui.component.GroupField
import com.chdroid.app.ui.component.GroupHeader
import com.chdroid.app.ui.component.SaveActionBottomBar
import com.chdroid.app.ui.component.SettingsGroup
import com.chdroid.app.ui.component.screenGutter
import com.chdroid.app.ui.util.FullScreenLoading
import com.chdroid.app.ui.util.LoadingSize
import com.chdroid.app.ui.util.rememberClearFocus
import com.chdroid.app.ui.viewmodel.ContainerViewModel
import com.chdroid.app.util.ChDroidRootfsManager
import com.chdroid.app.util.ContainerInfo
import com.chdroid.app.util.ResourceLimits
import com.chdroid.app.util.ValidationUtils
import com.chdroid.app.util.ViewModelLogger
import com.chdroid.app.util.toConfigState
import com.chdroid.app.util.withConfig
import kotlinx.coroutines.launch

/**
 * Edit screen for the CLI rootfs. It is the same form the install wizard uses
 * (ContainerConfigHost plus its Network/Env/Privileged/Mounts pages), so every
 * field shown during setup is editable here. The container name and
 * Android-root flag live in the leading block; the ~34 config fields and the
 * sparse-image settings come from the persisted container.config.
 */
@Composable
fun ChDroidRootfsEditScreen(
    containerViewModel: ContainerViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clearFocus = rememberClearFocus()

    var loaded by remember { mutableStateOf<ContainerInfo?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { ChDroidRootfsManager.loadEditableConfig() }
            .onSuccess { loaded = it }
            .onFailure { loadError = it.message ?: context.getString(R.string.uc_failed) }
    }

    if (loadError != null) {
        Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
        return
    }
    val original = loaded ?: run {
        FullScreenLoading(size = LoadingSize.Medium)
        return
    }

    var name by remember(original) { mutableStateOf(original.name) }
    var savedName by remember(original) { mutableStateOf(original.name) }
    var state by remember(original) { mutableStateOf(original.toConfigState()) }
    var savedState by remember(original) { mutableStateOf(original.toConfigState()) }
    var sparseSize by remember(original) { mutableStateOf(original.sparseImageSizeGB ?: 8) }
    var savedSparseSize by remember(original) { mutableStateOf(original.sparseImageSizeGB ?: 8) }
    var useSparse by remember(original) { mutableStateOf(original.useSparseImage) }
    var savedUseSparse by remember(original) { mutableStateOf(original.useSparseImage) }
    var mountAndroidRoot by remember(original) { mutableStateOf(original.mountAndroidRoot) }
    var savedMountAndroidRoot by remember(original) { mutableStateOf(original.mountAndroidRoot) }

    val nameError by remember(name) {
        derivedStateOf {
            if (name.isBlank()) context.getString(R.string.chdroid_edit_name) else null
        }
    }

    val macvlanErrors = ValidationUtils.validateMacvlanConfig(
        selfName = name,
        netMode = state.netMode,
        parent = state.macvlanParent,
        mode = state.macvlanMode,
        installed = containerViewModel.containerList,
        context = context
    )
    val gatewayErrors = ValidationUtils.validateGatewayConfig(
        selfName = name,
        gatewayContainer = state.gatewayContainer,
        net = state.gatewayNet,
        iface = state.gatewayIface,
        bridge = state.gatewayBridge,
        installed = containerViewModel.containerList,
        context = context
    )
    val collisionContainer = remember(state.netMode, state.staticNatIp, name, containerViewModel.containerList) {
        if (state.netMode != "nat" || state.staticNatIp.isEmpty()) null
        else containerViewModel.containerList.find { it.name != name && it.staticNatIp == state.staticNatIp }
    }

    val hasChanges by remember {
        derivedStateOf {
            name != savedName || state != savedState ||
                sparseSize != savedSparseSize || useSparse != savedUseSparse ||
                mountAndroidRoot != savedMountAndroidRoot
        }
    }
    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun saveChanges() {
        scope.launch {
            isSaving = true
            isSaved = false
            errorMessage = null
            val finalName = name.trim()
            val finalHost = ValidationUtils.sanitizeHostname(finalName)
            val updated = original.withConfig(state).copy(
                name = finalName,
                hostname = finalHost,
                rootfsPath = original.rootfsPath,
                useSparseImage = useSparse,
                sparseImageSizeGB = if (useSparse) sparseSize else null,
                mountAndroidRoot = mountAndroidRoot,
                selinuxPermissive = false,
            )
            val result = ChDroidRootfsManager.saveEditableConfig(
                context = context,
                info = updated,
                logger = ViewModelLogger { _, _ -> }
            )
            result.fold(
                onSuccess = {
                    name = finalName; savedName = finalName
                    savedState = state
                    savedSparseSize = sparseSize; savedUseSparse = useSparse
                    savedMountAndroidRoot = mountAndroidRoot
                    isSaved = true
                },
                onFailure = { errorMessage = it.message ?: context.getString(R.string.uc_failed) }
            )
            isSaving = false
        }
    }

    val canSave = !isSaving && !isSaved && hasChanges && nameError == null &&
        (state.netMode != "gateway" || gatewayErrors.isValid) &&
        macvlanErrors.isValid && collisionContainer == null &&
        ResourceLimits.isValidPidsLimit(state.pidsLimit)

    ContainerConfigHost(
        title = context.getString(R.string.chdroid_edit_title),
        onBack = onBack,
        bottomBar = {
            SaveActionBottomBar(
                isSaved = isSaved,
                isSaving = isSaving,
                canSave = canSave,
                onSave = { clearFocus(); saveChanges() },
                saveLabel = context.getString(R.string.save_changes),
                savingLabel = context.getString(R.string.saving),
                savedLabel = context.getString(R.string.saved)
            )
        },
        state = state,
        onStateChange = { state = it },
        installedContainers = containerViewModel.containerList,
        selfName = name,
        gatewayErrors = gatewayErrors,
        macvlanErrors = macvlanErrors,
        collisionContainer = collisionContainer,
        leadingContent = {
            val gutter = Modifier.padding(start = screenGutter(), end = screenGutter(), top = 8.dp)
            errorMessage?.let { com.chdroid.app.ui.component.InlineWarning(it, gutter) }
            GroupHeader(context.getString(R.string.config_general))
            SettingsGroup {
                GroupField {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(context.getString(R.string.chdroid_edit_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = ChDroidTextFieldDefaults.colors(),
                        isError = nameError != null
                    )
                }
                GroupField {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            context.getString(R.string.chdroid_mount_android_root),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = mountAndroidRoot, onCheckedChange = { mountAndroidRoot = it })
                    }
                }
                GroupField {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(context.getString(R.string.use_sparse_image), modifier = Modifier.weight(1f))
                        Switch(checked = useSparse, onCheckedChange = { useSparse = it })
                    }
                }
                if (useSparse) {
                    GroupField {
                        OutlinedTextField(
                            value = sparseSize.toString(),
                            onValueChange = { value -> sparseSize = value.toIntOrNull()?.coerceIn(4, 512) ?: sparseSize },
                            label = { Text(context.getString(R.string.size_gb)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = ChDroidTextFieldDefaults.colors()
                        )
                    }
                }
            }
        }
    )
}
