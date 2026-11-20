package com.droidspaces.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidspaces.app.R

/**
 * Card for the ChDroid CLI rootfs installed at /data/local/ubuntu-chroot.
 *
 * This is not a Droidspaces container - chroot.sh owns the namespace and shell -
 * so it is listed separately from the backend container cards and its actions
 * drive the ChDroid CLI directly.
 */
@Composable
fun ChDroidRootfsCard(
    distro: String,
    isRunning: Boolean,
    isBusy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
                        text = context.getString(R.string.chdroid_rootfs_card_path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = statusText.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
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
        }
    }
}
