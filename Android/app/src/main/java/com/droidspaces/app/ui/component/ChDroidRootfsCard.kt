package com.droidspaces.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidspaces.app.R

/**
 * Card for the ChDroid CLI rootfs, which lives in a setup-named directory.
 *
 * This is not a Droidspaces container - chroot.sh owns the namespace and shell -
 * so it is listed separately from the backend container cards and its actions
 * drive the ChDroid CLI directly. The action drawer mirrors upstream's
 * ContainerCard (edit + uninstall) so the rootfs is manageable from Containers.
 */
@Composable
fun ChDroidRootfsCard(
    distro: String,
    path: String,
    isRunning: Boolean,
    isBusy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShowLogs: () -> Unit = {},
    onEdit: () -> Unit = {},
    onUninstall: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val accent = if (isRunning) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val statusText = context.getString(
        if (isRunning) R.string.chdroid_rootfs_card_running else R.string.chdroid_rootfs_card_stopped
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Computer,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(40.dp)
                )
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        text = distro.ifBlank { context.getString(R.string.chdroid_rootfs_card_title) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = context.getString(R.string.chdroid_rootfs_card_path, path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Same console button upstream puts on every Droidspaces card:
                // opens the persisted start/stop output in the log viewer.
                IconButton(onClick = onShowLogs) {
                    Icon(
                        Icons.AutoMirrored.Filled.Article,
                        context.getString(R.string.chdroid_rootfs_console),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        Icons.Default.MoreVert,
                        context.getString(R.string.chdroid_rootfs_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = statusText.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isRunning) {
                    OutlinedButton(
                        onClick = onStop,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            context.getString(R.string.chdroid_rootfs_card_stop),
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onStart,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            context.getString(R.string.chdroid_rootfs_card_start),
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }

            // Integrated expandable action drawer, sealed and interactive, the
            // same shape upstream uses on ContainerCard.
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))
                    ChDroidAction(
                        icon = Icons.Default.Edit,
                        label = context.getString(R.string.edit_container_configuration),
                        tint = MaterialTheme.colorScheme.primary
                    ) {
                        expanded = false
                        onEdit()
                    }
                    ChDroidAction(
                        icon = Icons.Default.Delete,
                        label = context.getString(R.string.uninstall_container_menu),
                        tint = MaterialTheme.colorScheme.error,
                        bold = true
                    ) {
                        expanded = false
                        onUninstall()
                    }
                }
            }
        }
    }
}

@Composable
private fun ChDroidAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    bold: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
