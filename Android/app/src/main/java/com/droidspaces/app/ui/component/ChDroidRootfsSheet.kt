// SPDX-License-Identifier: GPL-3.0-or-later
package com.droidspaces.app.ui.component

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidspaces.app.R
import com.droidspaces.app.service.AssetDownloadState
import com.droidspaces.app.ui.util.ClearFocusOnClickOutside
import com.droidspaces.app.ui.viewmodel.ChDroidRootfsViewModel
import com.droidspaces.app.util.RootfsAsset

/**
 * ChDroid's rootfs source sheet. Built-in Ubuntu-Chroot CLI release, a
 * user-supplied download link, and file import. No Droidspaces repo browsing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChDroidRootfsSheet(
    onDismiss: () -> Unit,
    onInstall: (Uri) -> Unit,
    onImport: () -> Unit,
) {
    StandardMotion {
        ChDroidRootfsSheetContent(onDismiss, onInstall, onImport)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChDroidRootfsSheetContent(
    onDismiss: () -> Unit,
    onInstall: (Uri) -> Unit,
    onImport: () -> Unit,
) {
    val vm: ChDroidRootfsViewModel = viewModel()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val appSettings = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    val requestStorage = rememberPermissionRequest(
        permission = Manifest.permission.WRITE_EXTERNAL_STORAGE,
        title = context.getString(R.string.repo_storage_permission_title),
        rationale = context.getString(R.string.repo_storage_permission_rationale),
        settingsIntent = appSettings
    )
    val requestNotifications = rememberPermissionRequest(
        permission = Manifest.permission.POST_NOTIFICATIONS,
        title = context.getString(R.string.notification_permission_title),
        rationale = context.getString(R.string.repo_notification_permission_rationale),
        settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )
    var askedNotifications by remember { mutableStateOf(false) }

    val startDownload: (RootfsAsset) -> Unit = { asset ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotifications) {
            askedNotifications = true
            requestNotifications {}
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            requestStorage { granted ->
                if (granted) vm.startDownload(asset)
                else vm.failDownload(asset, context.getString(R.string.repo_dl_error_storage_permission))
            }
        } else {
            vm.startDownload(asset)
        }
    }

    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        ClearFocusOnClickOutside(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = context.getString(R.string.chdroid_rootfs_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(16.dp))
                vm.assets.forEach { asset ->
                    RootfsSourceCard(
                        asset = asset,
                        state = vm.downloadStates[asset.downloadUrl]
                            ?: downloadedState(vm, asset),
                        onDownload = { startDownload(asset) },
                        onCancel = { vm.cancelDownload(asset) },
                        onInstall = onInstall,
                        onRetry = { vm.resetAsset(asset.downloadUrl) }
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (vm.customAssets.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    vm.customAssets.forEach { asset ->
                        RootfsSourceCard(
                            asset = asset,
                            state = vm.downloadStates[asset.downloadUrl]
                                ?: downloadedState(vm, asset),
                            onDownload = { startDownload(asset) },
                            onCancel = { vm.cancelDownload(asset) },
                            onInstall = onInstall,
                            onRetry = { vm.resetAsset(asset.downloadUrl) }
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                Spacer(Modifier.height(16.dp))

                Text(
                    text = context.getString(R.string.chdroid_rootfs_from_link),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; linkError = null },
                    label = { Text(context.getString(R.string.chdroid_rootfs_url_hint)) },
                    singleLine = true,
                    isError = linkError != null,
                    supportingText = linkError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val trimmed = link.trim()
                        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
                            linkError = context.getString(R.string.chdroid_rootfs_invalid_url)
                            return@Button
                        }
                        val asset = vm.addLink(trimmed)
                        link = ""
                        startDownload(asset)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.chdroid_rootfs_add_link))
                }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.chdroid_rootfs_import))
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = context.getString(R.string.chdroid_rootfs_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

/** Re-derive a finished download from disk so a deleted file is not shown as Install. */
private fun downloadedState(vm: ChDroidRootfsViewModel, asset: RootfsAsset): AssetDownloadState {
    val uri = vm.downloadedUri(asset) ?: return AssetDownloadState.Idle
    return AssetDownloadState.Done(uri)
}

@Composable
private fun RootfsSourceCard(
    asset: RootfsAsset,
    state: AssetDownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: (Uri) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            when (state) {
                is AssetDownloadState.Done -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                is AssetDownloadState.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            }
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = asset.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = asset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (state) {
                is AssetDownloadState.Idle -> {
                    val size = if (asset.sizeBytes > 0)
                        context.getString(R.string.chdroid_rootfs_size_mb, asset.sizeBytes / 1_000_000)
                    else ""
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (size.isEmpty()) context.getString(R.string.chdroid_rootfs_download)
                            else context.getString(R.string.chdroid_rootfs_download_size, size)
                        )
                    }
                }
                is AssetDownloadState.Downloading -> {
                    Text(
                        context.getString(R.string.chdroid_rootfs_downloading, state.percent),
                        style = MaterialTheme.typography.labelMedium
                    )
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = onCancel) {
                        Text(context.getString(R.string.chdroid_rootfs_cancel))
                    }
                }
                is AssetDownloadState.Done -> {
                    Button(
                        onClick = { onInstall(state.uri) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(context.getString(R.string.chdroid_rootfs_install))
                    }
                }
                is AssetDownloadState.Failed -> {
                    Text(
                        state.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = { onRetry(); onDownload() }, modifier = Modifier.fillMaxWidth()) {
                        Text(context.getString(R.string.chdroid_rootfs_retry))
                    }
                }
            }
        }
    }
}
