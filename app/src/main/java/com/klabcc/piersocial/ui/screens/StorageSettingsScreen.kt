package com.klabcc.piersocial.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.klabcc.piersocial.data.local.PostWithAuthor
import com.klabcc.piersocial.data.retention.RetentionCapPreset
import com.klabcc.piersocial.data.retention.StorageStats
import com.klabcc.piersocial.ui.components.formatByteSize
import com.klabcc.piersocial.ui.theme.BorderSubtle
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.ObsidianBg
import com.klabcc.piersocial.ui.theme.SignalAmber
import com.klabcc.piersocial.ui.theme.SignalGreen
import com.klabcc.piersocial.ui.theme.SurfaceCard
import com.klabcc.piersocial.ui.theme.SurfaceDark
import com.klabcc.piersocial.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(
    stats: StorageStats,
    pinnedPosts: List<PostWithAuthor>,
    developerMode: Boolean = false,
    onSelectCap: (RetentionCapPreset) -> Unit,
    onToggleAutoEvict: (Boolean) -> Unit,
    onRunEvictionNow: (onDone: (Int) -> Unit) -> Unit,
    onUnpinPost: (String) -> Unit,
    onBack: () -> Unit
) {
    val activePreset = RetentionCapPreset.fromBytes(stats.capBytes)
    var evictionResultMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = ObsidianBg,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Storage & Retention") }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Live Storage Usage Meter
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, null, tint = MeshCyan)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (developerMode) "Live Mesh Storage Usage" else "Storage Usage",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatByteSize(stats.totalUsedBytes),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MeshCyan
                            )
                            Text(
                                text = if (stats.isInfinite) "Limit: Unlimited" else "Limit: ${activePreset.label}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                        }

                        LinearProgressIndicator(
                            progress = { if (stats.isInfinite) 0.15f else stats.usageRatio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            color = when {
                                stats.isInfinite -> SignalGreen
                                stats.usageRatio > 0.85f -> SignalAmber
                                else -> MeshCyan
                            },
                            trackColor = ObsidianBg
                        )

                        if (developerMode) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Media Blobs: ${formatByteSize(stats.mediaBytes)}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text("Packets: ${formatByteSize(stats.packetBytes)}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text("Pinned: ${formatByteSize(stats.pinnedBytes)}", style = MaterialTheme.typography.labelSmall, color = SignalAmber)
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = if (developerMode) "Retained Peer Data Cap" else "Storage Limit",
                    style = MaterialTheme.typography.titleMedium,
                    color = MeshCyan
                )
            }

            items(RetentionCapPreset.entries) { preset ->
                val selected = preset == activePreset
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MeshCyan.copy(alpha = 0.12f) else SurfaceCard
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (selected) MeshCyan else BorderSubtle,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectCap(preset) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = { onSelectCap(preset) })
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (preset.isInfinite) {
                                    "Keep all shared posts and photos without automatic cleanup"
                                } else if (developerMode) {
                                    "Automatically evict oldest unpinned peer data above ${preset.label}"
                                } else {
                                    "Automatically clean up oldest unpinned posts above ${preset.label}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        if (preset.isInfinite) {
                            Icon(Icons.Default.AllInclusive, null, tint = SignalGreen)
                        }
                    }
                }
            }

            // Automatic LRU Eviction Controls
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (developerMode) "Automatic LRU Eviction" else "Automatic Storage Cleanup",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Removes expired 24h stories and oldest unpinned posts when limit is reached.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = stats.autoEvictEnabled,
                                onCheckedChange = onToggleAutoEvict
                            )
                        }

                        if (developerMode) {
                            Button(
                                onClick = {
                                    onRunEvictionNow { count ->
                                        evictionResultMessage = "LRU sweep complete: $count item(s) evicted."
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MeshCyan, contentColor = Color.Black),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CleaningServices, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Run LRU & Expiry Sweep Now", fontWeight = FontWeight.Bold)
                            }

                            if (evictionResultMessage != null) {
                                Text(evictionResultMessage!!, style = MaterialTheme.typography.labelMedium, color = SignalGreen)
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Pinned Items (${pinnedPosts.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = SignalAmber
                )
            }

            if (pinnedPosts.isEmpty()) {
                item {
                    Text(
                        text = "Pin any post, story, or profile to protect it from automatic cleanup.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            } else {
                items(pinnedPosts, key = { it.post.postId }) { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (developerMode) {
                                        item.author?.handle ?: item.post.authorPeerId.take(8)
                                    } else {
                                        item.author?.displayName ?: "Pinned Post"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MeshCyan
                                )
                                Text(
                                    text = item.post.caption.ifBlank { "Photo • ${formatByteSize(item.post.mediaByteSize)}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                            }
                            IconButton(onClick = { onUnpinPost(item.post.postId) }) {
                                Icon(Icons.Filled.PushPin, contentDescription = "Unpin", tint = SignalAmber)
                            }
                        }
                    }
                }
            }
        }
    }
}
