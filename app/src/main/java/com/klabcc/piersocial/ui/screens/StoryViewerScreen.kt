package com.klabcc.piersocial.ui.screens

import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klabcc.piersocial.data.local.ProfileEntity
import com.klabcc.piersocial.data.local.StoryLikeEntity
import com.klabcc.piersocial.data.storage.ChunkTransferProgress
import com.klabcc.piersocial.data.storage.MediaStorageManager
import com.klabcc.piersocial.ui.components.MeshBitmapImage
import com.klabcc.piersocial.ui.components.PeerAvatar
import com.klabcc.piersocial.ui.components.formatRelativeTime
import com.klabcc.piersocial.ui.theme.BorderSubtle
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.SignalAmber
import com.klabcc.piersocial.ui.theme.SignalGreen
import com.klabcc.piersocial.ui.theme.SignalRed
import com.klabcc.piersocial.ui.theme.SurfaceCard
import com.klabcc.piersocial.ui.theme.TextSecondary
import com.klabcc.piersocial.ui.viewmodel.StoryRingGroup
import kotlinx.coroutines.delay
import java.util.Locale

private val QUICK_STORY_EMOJIS = listOf("❤️", "🔥", "😂", "😮", "😍", "👏")

@Composable
fun StoryViewerScreen(
    storyGroup: StoryRingGroup?,
    localPeerId: String = "",
    storyLikesByStoryId: Map<String, List<StoryLikeEntity>> = emptyMap(),
    profilesByPeerId: Map<String, ProfileEntity> = emptyMap(),
    activeTransfers: Map<String, ChunkTransferProgress>,
    developerMode: Boolean = false,
    onMarkViewed: (storyId: String) -> Unit,
    onTogglePinStory: (storyId: String) -> Unit,
    onToggleLikeStory: (storyId: String) -> Unit = {},
    onEnsureLikeStory: (storyId: String) -> Unit = {},
    onReplyToStory: ((authorPeerId: String, replyText: String) -> Unit)? = null,
    onClose: () -> Unit
) {
    if (storyGroup == null || storyGroup.stories.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val stories = storyGroup.stories
    var currentIndex by remember(storyGroup.author.peerId) { mutableIntStateOf(0) }
    val safeIndex = currentIndex.coerceIn(0, stories.lastIndex)
    val current = stories[safeIndex]
    val currentLikes = storyLikesByStoryId[current.story.storyId].orEmpty()
    val isLikedByMe = currentLikes.any { it.likerPeerId == localPeerId }

    var isHoldPaused by remember { mutableStateOf(false) }
    var isTypingReply by remember { mutableStateOf(false) }
    var showLikesDialog by remember { mutableStateOf(false) }
    var replyDraft by remember { mutableStateOf("") }
    var feedbackPill by remember { mutableStateOf<String?>(null) }
    var isMuted by remember { mutableStateOf(false) }
    var segmentProgress by remember(safeIndex) { mutableFloatStateOf(0f) }

    val isPaused = isHoldPaused || isTypingReply || showLikesDialog

    LaunchedEffect(feedbackPill) {
        if (feedbackPill != null) {
            delay(1400L)
            feedbackPill = null
        }
    }

    val audioMediaId = current.story.audioMediaId
    val audioTx = audioMediaId?.let { activeTransfers[it] }
    val audioFile = remember(audioMediaId) {
        audioMediaId?.let { MediaStorageManager.getAudioFileForPlayback(context, it) }
    }
    val isAudioReady = audioMediaId != null &&
        (current.audioBlob?.isComplete != false) &&
        (audioFile?.exists() == true)

    val mediaPlayerState = remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(current.story.storyId, isAudioReady) {
        var mp: MediaPlayer? = null
        if (isAudioReady && audioFile != null) {
            runCatching {
                mp = MediaPlayer().apply {
                    setDataSource(audioFile.absolutePath)
                    isLooping = false
                    prepare()
                    val vol = if (isMuted) 0f else 1f
                    setVolume(vol, vol)
                    if (!isPaused) start()
                }
                mediaPlayerState.value = mp
            }
        }
        onDispose {
            runCatching { mp?.stop() }
            runCatching { mp?.release() }
            mediaPlayerState.value = null
        }
    }

    LaunchedEffect(isPaused, isMuted, current.story.storyId) {
        val mp = mediaPlayerState.value ?: return@LaunchedEffect
        val vol = if (isMuted) 0f else 1f
        runCatching { mp.setVolume(vol, vol) }
        runCatching {
            if (isPaused && mp.isPlaying) {
                mp.pause()
            } else if (!isPaused && !mp.isPlaying) {
                mp.start()
            }
        }
    }

    LaunchedEffect(current.story.storyId) {
        onMarkViewed(current.story.storyId)
    }

    LaunchedEffect(safeIndex, isPaused) {
        val stepMs = 50L
        val durationMs = maxOf(5000L, current.story.audioDurationMs.toLong()).coerceAtMost(5500L)
        while (segmentProgress < 1f) {
            delay(stepMs)
            if (!isPaused) {
                segmentProgress = (segmentProgress + stepMs.toFloat() / durationMs).coerceAtMost(1f)
            }
        }
        if (safeIndex < stories.lastIndex) {
            currentIndex = safeIndex + 1
        } else {
            onClose()
        }
    }

    val sendStoryReply: (String) -> Unit = { text ->
        val clean = text.trim()
        if (clean.isNotEmpty()) {
            onReplyToStory?.invoke(storyGroup.author.peerId, clean)
            replyDraft = ""
            isTypingReply = false
            focusManager.clearFocus()
            feedbackPill = "Sent to ${storyGroup.author.displayName}"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(safeIndex, stories.size) {
                detectTapGestures(
                    onPress = {
                        isHoldPaused = true
                        tryAwaitRelease()
                        isHoldPaused = false
                    },
                    onTap = { offset ->
                        if (isTypingReply) {
                            focusManager.clearFocus()
                            isTypingReply = false
                            return@detectTapGestures
                        }
                        if (offset.x < size.width * 0.35f) {
                            if (safeIndex > 0) {
                                currentIndex = safeIndex - 1
                            } else {
                                segmentProgress = 0f
                            }
                        } else {
                            if (safeIndex < stories.lastIndex) {
                                currentIndex = safeIndex + 1
                            } else {
                                onClose()
                            }
                        }
                    }
                )
            }
    ) {
        MeshBitmapImage(
            mediaId = current.story.mediaId,
            isComplete = current.mediaBlob?.isComplete ?: true,
            transferProgress = activeTransfers[current.story.mediaId],
            developerMode = developerMode,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        // Top scrim + segmented progress + header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                    )
                )
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                stories.forEachIndexed { idx, _ ->
                    val p = when {
                        idx < safeIndex -> 1f
                        idx == safeIndex -> segmentProgress
                        else -> 0f
                    }
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PeerAvatar(profile = storyGroup.author, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = storyGroup.author.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    val remainingHrs = ((current.story.expiresAt - System.currentTimeMillis()) / 3_600_000L)
                        .coerceAtLeast(0L)
                    Text(
                        text = when {
                            current.story.isPinned && developerMode -> "${formatRelativeTime(current.story.createdAt)} • Pinned (No expiry)"
                            current.story.isPinned -> "${formatRelativeTime(current.story.createdAt)} • Pinned"
                            else -> "${formatRelativeTime(current.story.createdAt)} • ${remainingHrs}h left"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }

                if (audioMediaId != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.55f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                tint = MeshCyan,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isAudioReady) {
                                    String.format(Locale.US, "%.1fs", current.story.audioDurationMs.coerceAtLeast(1000) / 1000f)
                                } else if (developerMode && audioTx != null) {
                                    "Audio ${audioTx.completedChunks}/${audioTx.totalChunks}"
                                } else {
                                    "Syncing..."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                    IconButton(onClick = { isMuted = !isMuted }) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            tint = Color.White
                        )
                    }
                }

                if (developerMode) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.5f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Hub, null, tint = MeshCyan, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${current.story.hopCount}h",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }

                IconButton(onClick = { onTogglePinStory(current.story.storyId) }) {
                    Icon(
                        imageVector = if (current.story.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = "Pin Story",
                        tint = if (current.story.isPinned) SignalAmber else Color.White
                    )
                }

                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }

        // Confirmation pill for story reactions / replies
        AnimatedVisibility(
            visible = feedbackPill != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.78f),
                modifier = Modifier.border(1.dp, SignalGreen.copy(alpha = 0.7f), RoundedCornerShape(50))
            ) {
                Text(
                    text = feedbackPill.orEmpty(),
                    color = SignalGreen,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Bottom caption + quick emoji reactions + DM reply bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                    )
                )
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (current.story.caption.isNotBlank()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = current.story.caption,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            if (storyGroup.author.isLocalUser) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.62f),
                    modifier = Modifier
                        .align(Alignment.Start)
                        .border(
                            1.dp,
                            if (currentLikes.isNotEmpty()) SignalRed.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.25f),
                            RoundedCornerShape(50)
                        )
                        .clip(RoundedCornerShape(50))
                        .clickable { showLikesDialog = true }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (currentLikes.isNotEmpty()) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Story Likes",
                            tint = if (currentLikes.isNotEmpty()) SignalRed else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                        if (currentLikes.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                currentLikes.take(3).forEach { like ->
                                    PeerAvatar(
                                        profile = profilesByPeerId[like.likerPeerId],
                                        size = 22.dp
                                    )
                                }
                            }
                            val firstName = profilesByPeerId[currentLikes.first().likerPeerId]?.displayName
                                ?: currentLikes.first().likerPeerId.take(8)
                            val label = if (currentLikes.size == 1) {
                                "1 like • $firstName"
                            } else {
                                "${currentLikes.size} likes • $firstName +${currentLikes.size - 1}"
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "0 likes",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            } else if (onReplyToStory != null) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(QUICK_STORY_EMOJIS) { emoji ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.14f),
                            modifier = Modifier
                                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                                .clickable {
                                    if (emoji == "❤️") {
                                        onEnsureLikeStory(current.story.storyId)
                                    }
                                    onReplyToStory(storyGroup.author.peerId, "Reacted $emoji to your story")
                                    feedbackPill = "Sent $emoji"
                                }
                        ) {
                            Text(
                                text = emoji,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = replyDraft,
                        onValueChange = { replyDraft = it },
                        placeholder = {
                            Text(
                                text = "Reply to ${storyGroup.author.displayName}...",
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { sendStoryReply(replyDraft) }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MeshCyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                            focusedContainerColor = Color.Black.copy(alpha = 0.45f),
                            unfocusedContainerColor = Color.Black.copy(alpha = 0.35f)
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focusState ->
                                isTypingReply = focusState.isFocused
                            }
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            onToggleLikeStory(current.story.storyId)
                            feedbackPill = if (!isLikedByMe) "Liked story ❤️" else "Removed like"
                        }
                    ) {
                        Icon(
                            imageVector = if (isLikedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isLikedByMe) "Unlike Story" else "Like Story",
                            tint = if (isLikedByMe) SignalRed else Color.White
                        )
                    }
                    if (replyDraft.isNotBlank()) {
                        IconButton(onClick = { sendStoryReply(replyDraft) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send Reply",
                                tint = MeshCyan
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLikesDialog) {
        AlertDialog(
            onDismissRequest = { showLikesDialog = false },
            containerColor = SurfaceCard,
            title = {
                Text(
                    text = "Story Likes (${currentLikes.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                if (currentLikes.isEmpty()) {
                    Text(
                        text = "No one has liked this story yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(currentLikes, key = { it.likerPeerId }) { like ->
                            val likerProfile = profilesByPeerId[like.likerPeerId]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                PeerAvatar(profile = likerProfile, size = 38.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = likerProfile?.displayName ?: like.likerPeerId.take(8),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (developerMode) {
                                            "${likerProfile?.handle ?: "@pier-${like.likerPeerId.take(6)}"} • ${formatRelativeTime(like.likedAt)}"
                                        } else {
                                            formatRelativeTime(like.likedAt)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = SignalRed,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            HorizontalDivider(
                                color = BorderSubtle.copy(alpha = 0.4f),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLikesDialog = false }) {
                    Text("Close", color = MeshCyan)
                }
            }
        )
    }
}
