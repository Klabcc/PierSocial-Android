package com.klabcc.piersocial.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.klabcc.piersocial.data.local.PostWithAuthor
import com.klabcc.piersocial.data.local.ProfileEntity
import com.klabcc.piersocial.data.retention.StorageStats
import com.klabcc.piersocial.data.storage.ChunkTransferProgress
import com.klabcc.piersocial.data.storage.ImagePipeline
import com.klabcc.piersocial.data.storage.MediaStorageManager
import com.klabcc.piersocial.ui.components.MeshBitmapImage
import com.klabcc.piersocial.ui.components.PeerAvatar
import com.klabcc.piersocial.ui.components.formatByteSize
import com.klabcc.piersocial.ui.theme.BorderSubtle
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.ObsidianBg
import com.klabcc.piersocial.ui.theme.SignalAmber
import com.klabcc.piersocial.ui.theme.SignalRed
import com.klabcc.piersocial.ui.theme.SurfaceCard
import com.klabcc.piersocial.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun ProfileScreen(
    contentPadding: PaddingValues,
    profile: ProfileEntity?,
    posts: List<PostWithAuthor>,
    allProfiles: List<ProfileEntity>,
    storageStats: StorageStats,
    activeTransfers: Map<String, ChunkTransferProgress>,
    isOwnProfile: Boolean,
    developerMode: Boolean = false,
    onBack: (() -> Unit)? = null,
    onUpdateProfile: (displayName: String, bio: String, avatar: Bitmap?) -> Unit,
    onOpenStorageSettings: () -> Unit,
    onToggleFollow: (String) -> Unit,
    onToggleMute: (String) -> Unit,
    onTogglePinProfile: (String) -> Unit,
    onTogglePinPost: (String) -> Unit,
    onOpenDm: (String) -> Unit
) {
    val context = LocalContext.current
    var showEditDialog by remember { mutableStateOf(false) }
    var selectedPostDetail by remember { mutableStateOf<PostWithAuthor?>(null) }

    val followingCount = remember(allProfiles) { allProfiles.count { it.isFollowing } }
    val mutedCount = remember(allProfiles) { allProfiles.count { it.isMuted } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 20.dp,
            start = 14.dp,
            end = 14.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (onBack != null && !isOwnProfile) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = if (developerMode) (profile?.handle ?: "Peer Profile") else (profile?.displayName ?: "Profile"),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PeerAvatar(profile = profile, size = 78.dp)
                Spacer(Modifier.width(16.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatColumn("${posts.size}", "Posts")
                    if (isOwnProfile) {
                        StatColumn("$followingCount", "Following")
                        StatColumn("$mutedCount", "Muted")
                    } else {
                        StatColumn(if (profile?.isFollowing == true) "Yes" else "No", "Following")
                        if (developerMode) {
                            StatColumn(if (profile?.isPinned == true) "Pinned" else "LRU", "Retention")
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = profile?.displayName ?: "PierSocial User",
                    style = MaterialTheme.typography.headlineSmall
                )
                if (developerMode && profile?.handle != null) {
                    Text(
                        text = profile.handle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MeshCyan
                    )
                }
                if (!profile?.bio.isNullOrBlank()) {
                    Text(
                        text = profile.bio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (developerMode) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                        .clickable {
                            val fp = profile?.fingerprint ?: return@clickable
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.primaryClip = ClipData.newPlainText("Public Key Fingerprint", fp)
                            Toast.makeText(context, "Copied Ed25519 fingerprint", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ed25519 Public Key Fingerprint", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Text(
                                text = profile?.fingerprint ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MeshCyan
                            )
                        }
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Action Buttons
        item {
            if (isOwnProfile) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Edit Profile")
                    }
                    if (developerMode) {
                        Button(
                            onClick = onOpenStorageSettings,
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard, contentColor = MeshCyan),
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(50))
                        ) {
                            Icon(Icons.Default.Storage, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Storage (${formatByteSize(storageStats.totalUsedBytes)})", maxLines = 1)
                        }
                    }
                }
            } else if (profile != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onToggleFollow(profile.peerId) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (profile.isFollowing) SurfaceCard else MeshCyan,
                            contentColor = if (profile.isFollowing) Color.White else Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (profile.isFollowing) "Following" else "Follow", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { onOpenDm(profile.peerId) }) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Message")
                    }
                    IconButton(onClick = { onTogglePinProfile(profile.peerId) }) {
                        Icon(
                            if (profile.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin Profile",
                            tint = if (profile.isPinned) SignalAmber else TextSecondary
                        )
                    }
                    IconButton(onClick = { onToggleMute(profile.peerId) }) {
                        Icon(
                            if (profile.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Mute",
                            tint = if (profile.isMuted) SignalRed else TextSecondary
                        )
                    }
                }
            }
        }

        item {
            HorizontalDivider(color = BorderSubtle.copy(alpha = 0.5f))
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (developerMode) "3-Column Photo Grid" else "Posts",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }

        if (posts.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard)) {
                    Text(
                        text = "No photos shared yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            val gridRows = posts.chunked(3)
            items(gridRows) { rowPosts ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rowPosts.forEach { postItem ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { selectedPostDetail = postItem }
                        ) {
                            MeshBitmapImage(
                                mediaId = postItem.post.mediaId,
                                isComplete = postItem.mediaBlob?.isComplete ?: true,
                                transferProgress = activeTransfers[postItem.post.mediaId],
                                developerMode = developerMode,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (!postItem.post.audioMediaId.isNullOrEmpty()) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Has Audio",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(6.dp)
                                        .size(16.dp)
                                )
                            }
                            if (postItem.post.isPinned) {
                                Icon(
                                    imageVector = Icons.Filled.PushPin,
                                    contentDescription = "Pinned",
                                    tint = SignalAmber,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(16.dp)
                                )
                            }
                        }
                    }
                    repeat(3 - rowPosts.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        var nameInput by remember { mutableStateOf(profile?.displayName ?: "") }
        var bioInput by remember { mutableStateOf(profile?.bio ?: "") }
        var pickedAvatar by remember { mutableStateOf<Bitmap?>(null) }

        val avatarPicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                pickedAvatar = ImagePipeline.decodeUriToBitmap(context, uri, 512)
            }
        }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(if (developerMode) "Edit Local Node Profile" else "Edit Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    ) {
                        Text(if (pickedAvatar != null) "Avatar Selected ✓" else "Choose New Avatar Photo")
                    }
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Display Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = bioInput,
                        onValueChange = { bioInput = it },
                        label = { Text("Bio") },
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateProfile(nameInput, bioInput, pickedAvatar)
                        showEditDialog = false
                    }
                ) {
                    Text(if (developerMode) "Save & Sign" else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
            }
        )
    }

    selectedPostDetail?.let { detail ->
        var isPlayingDetailAudio by remember(detail.post.postId) { mutableStateOf(false) }
        var detailPlayer by remember(detail.post.postId) { mutableStateOf<MediaPlayer?>(null) }
        DisposableEffect(detail.post.postId) {
            onDispose {
                runCatching { detailPlayer?.stop() }
                runCatching { detailPlayer?.release() }
                detailPlayer = null
                isPlayingDetailAudio = false
            }
        }
        AlertDialog(
            onDismissRequest = { selectedPostDetail = null },
            title = {
                Text(
                    if (developerMode) (detail.author?.handle ?: "Post")
                    else (detail.author?.displayName ?: "Post")
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MeshBitmapImage(
                        mediaId = detail.post.mediaId,
                        isComplete = detail.mediaBlob?.isComplete ?: true,
                        developerMode = developerMode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    val audioId = detail.post.audioMediaId
                    if (!audioId.isNullOrEmpty()) {
                        OutlinedButton(
                            onClick = {
                                if (isPlayingDetailAudio) {
                                    runCatching { detailPlayer?.stop() }
                                    runCatching { detailPlayer?.release() }
                                    detailPlayer = null
                                    isPlayingDetailAudio = false
                                } else {
                                    val audioFile = MediaStorageManager.getAudioFileForPlayback(context, audioId)
                                    if (audioFile.exists()) {
                                        runCatching {
                                            detailPlayer = MediaPlayer().apply {
                                                setDataSource(audioFile.absolutePath)
                                                setOnCompletionListener {
                                                    runCatching { release() }
                                                    detailPlayer = null
                                                    isPlayingDetailAudio = false
                                                }
                                                prepare()
                                                start()
                                            }
                                            isPlayingDetailAudio = true
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (isPlayingDetailAudio) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = MeshCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isPlayingDetailAudio) {
                                    "Stop Audio"
                                } else {
                                    String.format(
                                        Locale.US,
                                        "Play %.1fs Audio",
                                        detail.post.audioDurationMs.coerceAtLeast(500) / 1000f
                                    )
                                },
                                color = MeshCyan
                            )
                        }
                    }
                    if (detail.post.caption.isNotBlank()) {
                        Text(detail.post.caption, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (developerMode) {
                        Text(
                            text = "Size: ${formatByteSize(detail.post.mediaByteSize + detail.post.audioByteSize)} • Hops: ${detail.post.hopCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onTogglePinPost(detail.post.postId)
                        selectedPostDetail = null
                    }
                ) {
                    Text(
                        when {
                            detail.post.isPinned -> "Unpin"
                            developerMode -> "Pin (Protect from LRU)"
                            else -> "Pin"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPostDetail = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun StatColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}
