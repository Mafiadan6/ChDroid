// SPDX-License-Identifier: GPL-3.0-or-later
package com.droidspaces.app.ui.screen

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droidspaces.app.R
import com.droidspaces.app.util.FilePickerUtils
import com.droidspaces.app.util.ViewModelLogger
import com.droidspaces.app.util.ChDroidRootfsManager

private enum class ChDroidInstallPhase { INSTALLING, DONE, ERROR }

/**
 * Friendly distro label from the release filename, used only for display. The
 * install itself is distro-agnostic — the ZIP's manifest and rootfs decide what
 * actually lands in /data/local/ubuntu-chroot.
 */
private fun rootfsLabel(fileName: String): String {
    val clean = fileName.substringBeforeLast('.').ifBlank { "rootfs" }
    val known = listOf("Artix", "Arch", "Ubuntu", "Alpine", "Debian", "Kali", "OpenWrt", "Azure", "Fedora")
    return known.firstOrNull { clean.contains(it, ignoreCase = true) }
        ?: clean.replace('-', ' ').replace('_', ' ')
}

/** Direct rootfs ZIP install. No Droidspaces container wizard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChDroidInstallScreen(
    zipUri: Uri,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // A content URI's lastPathSegment is often a provider document id (e.g.
    // "msf:1000003692"), not a filename. Resolve the real path for the label.
    val label = remember(zipUri) { mutableStateOf("rootfs") }
    val logs = remember { mutableStateListOf<String>() }
    val phase = remember { mutableStateOf(ChDroidInstallPhase.INSTALLING) }
    val error = remember { mutableStateOf("") }

    val logger = remember {
        ViewModelLogger { _, msg -> logs.add(msg) }
    }

    LaunchedEffect(zipUri) {
        if (phase.value != ChDroidInstallPhase.INSTALLING || logs.isNotEmpty()) return@LaunchedEffect
        val path = FilePickerUtils.realPath(context, zipUri)
        val fileName = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: zipUri.lastPathSegment.orEmpty()
        label.value = rootfsLabel(fileName)
        logger.i("Installing ${label.value} from $fileName")
        val result = ChDroidRootfsManager.install(context, zipUri, logger)
        if (result.isSuccess) {
            logger.i("${label.value} ready")
            phase.value = ChDroidInstallPhase.DONE
        } else {
            error.value = result.exceptionOrNull()?.message.orEmpty()
            logger.e("Install failed: ${error.value}")
            phase.value = ChDroidInstallPhase.ERROR
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(context.getString(R.string.chdroid_install_title, label.value)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when (phase.value) {
                ChDroidInstallPhase.INSTALLING -> Text(
                    text = context.getString(R.string.chdroid_install_running),
                    style = MaterialTheme.typography.titleMedium
                )
                ChDroidInstallPhase.DONE -> Text(
                    text = context.getString(R.string.chdroid_install_done, label.value),
                    style = MaterialTheme.typography.titleMedium
                )
                ChDroidInstallPhase.ERROR -> {
                    Text(
                        text = context.getString(R.string.chdroid_install_failed),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = error.value,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            Text(
                text = context.getString(R.string.chdroid_install_logs),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp)
            ) {
                logs.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text(context.getString(R.string.chdroid_install_back))
            }
        }
    }
}
