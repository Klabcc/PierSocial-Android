package com.klabcc.piersocial.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.klabcc.piersocial.data.local.ProfileEntity
import com.klabcc.piersocial.data.retention.RetentionCapPreset
import com.klabcc.piersocial.data.retention.StorageStats
import com.klabcc.piersocial.mesh.MeshRadioState
import com.klabcc.piersocial.ui.components.formatByteSize
import com.klabcc.piersocial.ui.theme.BorderSubtle
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.MeshViolet
import com.klabcc.piersocial.ui.theme.ObsidianBg
import com.klabcc.piersocial.ui.theme.SignalAmber
import com.klabcc.piersocial.ui.theme.SignalGreen
import com.klabcc.piersocial.ui.theme.SurfaceCard
import com.klabcc.piersocial.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    localProfile: ProfileEntity?,
    storageStats: StorageStats,
    radioState: MeshRadioState,
    discoveredPeerCount: Int,
    activeConnectionCount: Int,
    developerMode: Boolean,
    dmNotificationsEnabled: Boolean = true,
    onToggleDeveloperMode: (Boolean) -> Unit,
    onToggleDmNotifications: (Boolean) -> Unit = {},
    onToggleMeshService: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    onOpenMeshInspector: () -> Unit
) {
    val context = LocalContext.current
    val activeCap = RetentionCapPreset.fromBytes(storageStats.capBytes)
    var permVersion by remember { mutableIntStateOf(0) }

    val hasSystemNotifPerm = remember(permVersion) {
        val enabled = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(true)
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        enabled && runtimeGranted
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permVersion++
        onToggleDmNotifications(granted)
        if (!granted) {
            Toast.makeText(context, "Notification permission denied in Android settings", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
            start = 14.dp,
            end = 14.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "General",
                style = MaterialTheme.typography.titleMedium,
                color = MeshCyan
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.BluetoothConnected,
                        contentDescription = null,
                        tint = if (radioState.isServiceRunning) SignalGreen else TextSecondary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Nearby Sharing",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (radioState.isServiceRunning) {
                                "Active • Discover nearby people & sync posts"
                            } else {
                                "Paused • Enable to connect with nearby people"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = radioState.isServiceRunning,
                        onCheckedChange = { onToggleMeshService() }
                    )
                }
            }
        }

        item {
            val effectiveDmEnabled = dmNotificationsEnabled && hasSystemNotifPerm
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (effectiveDmEnabled) {
                            Icons.Default.NotificationsActive
                        } else {
                            Icons.Default.NotificationsOff
                        },
                        contentDescription = null,
                        tint = if (effectiveDmEnabled) MeshCyan else TextSecondary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DM Push Notifications",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = when {
                                !hasSystemNotifPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                    "Toggle on to grant notification permission for incoming DMs"
                                !hasSystemNotifPerm ->
                                    "Notifications blocked in system settings"
                                dmNotificationsEnabled ->
                                    "Instant alerts with inline reply when new DMs arrive"
                                else ->
                                    "Muted • You will not receive DM push alerts"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = effectiveDmEnabled,
                        onCheckedChange = { turnOn ->
                            if (turnOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                onToggleDmNotifications(turnOn)
                            }
                        }
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenStorageSettings)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null, tint = MeshCyan)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Storage & Retention", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${formatByteSize(storageStats.totalUsedBytes)} used • Limit: ${activeCap.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Advanced",
                style = MaterialTheme.typography.titleMedium,
                color = MeshCyan
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (developerMode) MeshViolet.copy(alpha = 0.7f) else BorderSubtle,
                        RoundedCornerShape(14.dp)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = if (developerMode) MeshViolet else TextSecondary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Developer Mode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Show BLE mesh inspector, hop counts, chunk metrics, RSSI, and Ed25519 fingerprints",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = developerMode,
                        onCheckedChange = onToggleDeveloperMode
                    )
                }
            }
        }

        if (developerMode) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Developer Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    color = MeshViolet
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Hub, null, tint = MeshCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("BLE Mesh Telemetry", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = "Linked: $activeConnectionCount • Nearby: $discoveredPeerCount • Relayed: ${radioState.packetsRelayed}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "GATT Server: ${if (radioState.isAdvertising) "ON" else "OFF"} • Scanner: ${if (radioState.isScanning) "ON" else "OFF"} • BT Radio: ${if (radioState.bluetoothEnabled) "ON" else "OFF"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (radioState.bluetoothEnabled) SignalGreen else SignalAmber
                        )
                        Button(
                            onClick = onOpenMeshInspector,
                            colors = ButtonDefaults.buttonColors(containerColor = MeshCyan, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open BLE Mesh Inspector", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                        .clickable {
                            val fp = localProfile?.fingerprint ?: return@clickable
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.primaryClip = ClipData.newPlainText("Public Key Fingerprint", fp)
                            Toast.makeText(context, "Copied Ed25519 fingerprint", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Key, null, tint = MeshCyan)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Node Identity (${localProfile?.handle ?: "@pier"})", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = localProfile?.fingerprint ?: "Initializing...",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MeshCyan
                            )
                        }
                        Icon(Icons.Default.ContentCopy, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Storage Breakdown", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Media Blobs: ${formatByteSize(storageStats.mediaBytes)} • Packets: ${formatByteSize(storageStats.packetBytes)} • Pinned: ${formatByteSize(storageStats.pinnedBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
