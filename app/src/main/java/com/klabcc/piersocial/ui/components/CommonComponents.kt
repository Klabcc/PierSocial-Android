package com.klabcc.piersocial.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klabcc.piersocial.data.local.ProfileEntity
import com.klabcc.piersocial.data.storage.ChunkTransferProgress
import com.klabcc.piersocial.mesh.DiscoveredBlePeer
import com.klabcc.piersocial.mesh.GattConnectionInfo
import com.klabcc.piersocial.mesh.MeshLogEntry
import com.klabcc.piersocial.mesh.MeshProtocol
import com.klabcc.piersocial.mesh.MeshRadioState
import com.klabcc.piersocial.ui.theme.BorderSubtle
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.ObsidianBg
import com.klabcc.piersocial.ui.theme.SignalAmber
import com.klabcc.piersocial.ui.theme.SignalGreen
import com.klabcc.piersocial.ui.theme.SignalRed
import com.klabcc.piersocial.ui.theme.SurfaceCard
import com.klabcc.piersocial.ui.theme.SurfaceElevated
import com.klabcc.piersocial.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun MeshBitmapImage(
    mediaId: String?,
    isComplete: Boolean = true,
    transferProgress: ChunkTransferProgress? = null,
    developerMode: Boolean = false,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, mediaId, isComplete) {
        if (mediaId == null || !isComplete) {
            value = null
        } else {
            value = withContext(Dispatchers.IO) {
                val file = File(context.filesDir, "media_blobs/$mediaId.webp")
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            }
        }
    }

    Box(modifier = modifier.background(SurfaceCard), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                    contentDescription = null,
                    tint = MeshCyan,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (developerMode) {
                        if (transferProgress != null) {
                            "Reassembling BLE chunks ${transferProgress.completedChunks}/${transferProgress.totalChunks}"
                        } else {
                            "Awaiting BLE media chunks..."
                        }
                    } else {
                        "Loading photo..."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                if (transferProgress != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { transferProgress.progress },
                        modifier = Modifier.fillMaxWidth(0.7f),
                        color = MeshCyan
                    )
                }
            }
        }

        if (transferProgress != null && !transferProgress.verifiedSha256 && bitmap != null) {
            LinearProgressIndicator(
                progress = { transferProgress.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                color = MeshCyan
            )
        }
    }
}

@Composable
fun PeerAvatar(
    profile: ProfileEntity?,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mediaId = profile?.avatarMediaId
    val avatarBitmap by produceState<Bitmap?>(initialValue = null, mediaId) {
        value = if (mediaId == null) null else withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "media_blobs/$mediaId.webp")
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }
    }

    val seed = abs((profile?.peerId ?: "pier").hashCode())
    val gradientColors = listOf(
        Color(0xFF00E5FF) to Color(0xFF7C4DFF),
        Color(0xFFFF4081) to Color(0xFFFFB300),
        Color(0xFF00E676) to Color(0xFF00B0FF),
        Color(0xFFAA00FF) to Color(0xFF00E5FF)
    )[seed % 4]

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(gradientColors.first, gradientColors.second))),
        contentAlignment = Alignment.Center
    ) {
        if (avatarBitmap != null) {
            Image(
                bitmap = avatarBitmap!!.asImageBitmap(),
                contentDescription = profile?.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val initials = (profile?.displayName ?: "PN")
                .split(" ")
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")
                .ifEmpty { "PN" }
            Text(
                text = initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp
            )
        }
    }
}

@Composable
fun MeshStatusPill(
    radioState: MeshRadioState,
    peerCount: Int,
    connectionCount: Int,
    onClick: () -> Unit
) {
    val dotColor by animateColorAsState(
        targetValue = when {
            connectionCount > 0 -> SignalGreen
            radioState.isServiceRunning && radioState.bluetoothEnabled -> MeshCyan
            radioState.hasPermissions -> SignalAmber
            else -> SignalRed
        },
        label = "meshDot"
    )
    val label = when {
        connectionCount > 0 -> "$connectionCount linked • $peerCount near"
        radioState.isServiceRunning && radioState.bluetoothEnabled -> "$peerCount peers • Mesh ON"
        !radioState.hasPermissions -> "Grant BLE"
        !radioState.bluetoothEnabled -> "BT Off"
        else -> "Mesh Idle"
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = SurfaceCard,
        modifier = Modifier
            .border(1.dp, BorderSubtle, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshInspectorSheet(
    radioState: MeshRadioState,
    discoveredPeers: List<DiscoveredBlePeer>,
    activeConnections: List<GattConnectionInfo>,
    activeTransfers: Map<String, ChunkTransferProgress>,
    meshLogs: List<MeshLogEntry>,
    onForceSync: () -> Unit,
    onToggleService: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianBg,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Hybrid Mesh Inspector",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "BLE + Wi-Fi Direct • TTL=5 • Relayed: ${radioState.packetsRelayed}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.BluetoothConnected,
                        contentDescription = null,
                        tint = MeshCyan,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusChip("GATT", radioState.isAdvertising)
                    StatusChip("Scan", radioState.isScanning)
                    StatusChip("BT", radioState.bluetoothEnabled)
                    StatusChip(
                        label = if (radioState.isWifiDirectGroupOwner) "Wi-Fi GO" else "Wi-Fi P2P",
                        active = radioState.isWifiDirectEnabled
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onForceSync,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MeshCyan, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Force Sync", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = onToggleService) {
                        Icon(
                            imageVector = if (radioState.isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (activeTransfers.isNotEmpty()) {
                item {
                    SectionTitle("Active Chunk Transfers (${activeTransfers.size})")
                }
                items(activeTransfers.values.toList(), key = { "tx_${it.mediaId}" }) { tx ->
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${tx.direction.name} • ${tx.mediaId.take(12)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MeshCyan
                                )
                                Text(
                                    text = "${tx.completedChunks}/${tx.totalChunks} chunks",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { tx.progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = if (tx.verifiedSha256) SignalGreen else MeshCyan
                            )
                        }
                    }
                }
            }

            item {
                SectionTitle("Active GATT Connections (${activeConnections.size})")
            }
            if (activeConnections.isEmpty()) {
                item {
                    Text(
                        text = "No active GATT links. Keep another PierSocial device nearby with Bluetooth enabled to replicate posts and DMs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            } else {
                items(activeConnections, key = { "conn_${it.role}_${it.address}" }) { conn ->
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = conn.peerId?.let { "@pier-${it.take(8)} (${conn.address})" } ?: conn.address,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (conn.isOptimalSource) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = SignalGreen.copy(alpha = 0.18f)
                                        ) {
                                            Text(
                                                text = "OPTIMAL",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = SignalGreen,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                val transportLabel = if (conn.transportType == MeshProtocol.TransportProtocol.WIFI_DIRECT) {
                                    "Wi-Fi Direct • High-Speed TCP"
                                } else {
                                    if (conn.supports2MPhy) "BLE 2M PHY" else "BLE 1M PHY"
                                }
                                Text(
                                    text = "${conn.role} • ${conn.state} • $transportLabel",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SignalGreen
                                )
                                val rttPart = if (conn.rttMs > 0L) "RTT ${conn.rttMs}ms" else "RTT --"
                                val bwPart = if (conn.measuredGoodputBps > 0L) {
                                    String.format(Locale.US, " • %.1f KB/s", conn.measuredGoodputBps / 1024.0)
                                } else {
                                    ""
                                }
                                Text(
                                    text = "$rttPart • ${conn.rssiDbm} dBm • Load ${conn.remoteActiveUploads}/${conn.remoteQueuedSlices}$bwPart",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (conn.transportType == MeshProtocol.TransportProtocol.WIFI_DIRECT) {
                                        SignalGreen.copy(alpha = 0.16f)
                                    } else {
                                        SurfaceElevated
                                    }
                                ) {
                                    Text(
                                        text = if (conn.transportType == MeshProtocol.TransportProtocol.WIFI_DIRECT) {
                                            "TCP 32K"
                                        } else {
                                            "MTU ${conn.negotiatedMtu}"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (conn.transportType == MeshProtocol.TransportProtocol.WIFI_DIRECT) SignalGreen else MeshCyan,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                if (conn.optimalityScore > 0.0) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "Score ${conn.optimalityScore.toInt()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (conn.isOptimalSource) SignalGreen else TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionTitle("Nearby Discovered BLE Peers (${discoveredPeers.size})")
            }
            if (discoveredPeers.isEmpty()) {
                item {
                    Text(
                        text = "Scanning for PierSocial BLE UUID D8A47B10...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            } else {
                items(discoveredPeers, key = { "peer_${it.address}" }) { peer ->
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = MeshCyan
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = peer.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = peer.address,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SignalCellularAlt,
                                    contentDescription = null,
                                    tint = if (peer.rssi > -65) SignalGreen else SignalAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "${peer.rssi} dBm",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (peer.rssi > -65) SignalGreen else SignalAmber
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionTitle("Live Packet & Transfer Logs")
            }
            items(meshLogs.take(30), key = { "log_${it.id}" }) { log ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = formatClockTime(log.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "[${log.tag}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (log.isError) SignalRed else MeshCyan,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = log.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = BorderSubtle.copy(alpha = 0.4f)
                    )
                }
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun StatusChip(label: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) SignalGreen.copy(alpha = 0.14f) else SurfaceCard,
        modifier = Modifier.border(
            1.dp,
            if (active) SignalGreen.copy(alpha = 0.5f) else BorderSubtle,
            RoundedCornerShape(8.dp)
        )
    ) {
        Text(
            text = "$label: ${if (active) "ON" else "OFF"}",
            style = MaterialTheme.typography.labelSmall,
            color = if (active) SignalGreen else TextSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MeshCyan,
        modifier = Modifier.padding(top = 4.dp)
    )
}

fun formatByteSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatRelativeTime(timestamp: Long): String {
    val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val mins = diff / 60_000L
    if (mins < 1) return "just now"
    if (mins < 60) return "${mins}m"
    val hrs = mins / 60
    if (hrs < 24) return "${hrs}h"
    return "${hrs / 24}d"
}

private fun formatClockTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
    return sdf.format(Date(timestamp))
}
