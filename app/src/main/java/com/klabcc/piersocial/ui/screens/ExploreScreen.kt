package com.klabcc.piersocial.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klabcc.piersocial.data.local.PostWithAuthor
import com.klabcc.piersocial.data.local.ProfileEntity
import com.klabcc.piersocial.data.storage.ChunkTransferProgress
import com.klabcc.piersocial.mesh.DiscoveredBlePeer
import com.klabcc.piersocial.ui.components.MeshBitmapImage
import com.klabcc.piersocial.ui.components.PeerAvatar
import com.klabcc.piersocial.ui.theme.BorderSubtle
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.ObsidianBg
import com.klabcc.piersocial.ui.theme.SignalAmber
import com.klabcc.piersocial.ui.theme.SignalGreen
import com.klabcc.piersocial.ui.theme.SignalRed
import com.klabcc.piersocial.ui.theme.SurfaceCard
import com.klabcc.piersocial.ui.theme.TextSecondary

@Composable
fun ExploreScreen(
    contentPadding: PaddingValues,
    profiles: List<ProfileEntity>,
    posts: List<PostWithAuthor>,
    discoveredBlePeers: List<DiscoveredBlePeer>,
    activeTransfers: Map<String, ChunkTransferProgress>,
    developerMode: Boolean = false,
    onOpenProfile: (String) -> Unit,
    onOpenDm: (String) -> Unit,
    onToggleFollow: (String) -> Unit,
    onToggleMute: (String) -> Unit,
    onTogglePinProfile: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val remoteProfiles = remember(profiles, query) {
        profiles.filter { !it.isLocalUser }.filter {
            query.isBlank() ||
                it.displayName.contains(query, ignoreCase = true) ||
                it.handle.contains(query, ignoreCase = true) ||
                it.fingerprint.contains(query, ignoreCase = true)
        }
    }

    val filteredPosts = remember(posts, query) {
        posts.filter {
            query.isBlank() ||
                it.post.caption.contains(query, ignoreCase = true) ||
                (it.author?.displayName?.contains(query, ignoreCase = true) == true)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
            start = 14.dp,
            end = 14.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        if (developerMode) "Search handle, fingerprint, or caption"
                        else "Search people or posts"
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Text(
                text = if (developerMode) "Mesh Peers Directory (${remoteProfiles.size})"
                else "Nearby People (${remoteProfiles.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MeshCyan
            )
        }

        if (remoteProfiles.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard)) {
                    Text(
                        text = if (developerMode) {
                            "No remote peers discovered yet. Keep nearby PierSocial devices in BLE range or open the Mesh Status pill above to inspect scanning."
                        } else {
                            "No one nearby yet. Keep Bluetooth turned on to discover people around you."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(remoteProfiles, key = { it.peerId }) { peer ->
                val bleMatch = discoveredBlePeers.firstOrNull {
                    it.peerId == peer.peerId || it.displayName.contains(peer.handle)
                }
                PeerDirectoryCard(
                    peer = peer,
                    rssi = bleMatch?.rssi,
                    developerMode = developerMode,
                    onClick = { onOpenProfile(peer.peerId) },
                    onDm = { onOpenDm(peer.peerId) },
                    onToggleFollow = { onToggleFollow(peer.peerId) },
                    onToggleMute = { onToggleMute(peer.peerId) },
                    onTogglePin = { onTogglePinProfile(peer.peerId) }
                )
            }
        }

        if (filteredPosts.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (developerMode) "Replicated Mesh Media (${filteredPosts.size})"
                    else "Posts (${filteredPosts.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MeshCyan
                )
            }

            val rows = filteredPosts.chunked(3)
            items(rows) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rowItems.forEach { postItem ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenProfile(postItem.post.authorPeerId) }
                        ) {
                            MeshBitmapImage(
                                mediaId = postItem.post.mediaId,
                                isComplete = postItem.mediaBlob?.isComplete ?: true,
                                transferProgress = activeTransfers[postItem.post.mediaId],
                                developerMode = developerMode,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    repeat(3 - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerDirectoryCard(
    peer: ProfileEntity,
    rssi: Int?,
    developerMode: Boolean,
    onClick: () -> Unit,
    onDm: () -> Unit,
    onToggleFollow: () -> Unit,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PeerAvatar(profile = peer, size = 44.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peer.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            developerMode && rssi != null -> "${peer.handle} • $rssi dBm"
                            developerMode -> peer.handle
                            rssi != null -> "Nearby"
                            else -> "Recently seen"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rssi != null) SignalGreen else TextSecondary
                    )
                }
                IconButton(onClick = onTogglePin) {
                    Icon(
                        imageVector = if (peer.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = "Pin",
                        tint = if (peer.isPinned) SignalAmber else TextSecondary
                    )
                }
                IconButton(onClick = onToggleMute) {
                    Icon(
                        imageVector = if (peer.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (peer.isMuted) "Unmute" else "Mute",
                        tint = if (peer.isMuted) SignalRed else TextSecondary
                    )
                }
            }
            if (peer.bio.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = peer.bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onToggleFollow,
                    colors = if (peer.isFollowing) {
                        ButtonDefaults.outlinedButtonColors(contentColor = MeshCyan)
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (peer.isFollowing) "Following" else "Follow", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onDm,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (developerMode) "E2EE DM" else "Message")
                }
            }
        }
    }
}
