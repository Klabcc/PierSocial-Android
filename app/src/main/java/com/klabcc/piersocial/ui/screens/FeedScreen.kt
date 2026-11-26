package com.klabcc.piersocial.ui.screens

import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.klabcc.piersocial.data.local.PollVoteEntity
import com.klabcc.piersocial.data.local.PostLikeEntity
import com.klabcc.piersocial.data.local.PostReactionEntity
import com.klabcc.piersocial.data.local.PostWithAuthor
import com.klabcc.piersocial.data.local.ProfileEntity
import com.klabcc.piersocial.data.local.StoryLikeEntity
import com.klabcc.piersocial.data.storage.ChunkTransferProgress
import com.klabcc.piersocial.data.storage.MediaStorageManager
import com.klabcc.piersocial.ui.components.MeshBitmapImage
import com.klabcc.piersocial.ui.components.PeerAvatar
import com.klabcc.piersocial.ui.components.formatByteSize
import com.klabcc.piersocial.ui.components.formatRelativeTime
import com.klabcc.piersocial.ui.theme.BorderSubtle
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.MeshViolet
import com.klabcc.piersocial.ui.theme.ObsidianBg
import com.klabcc.piersocial.ui.theme.SignalAmber
import com.klabcc.piersocial.ui.theme.SignalGreen
import com.klabcc.piersocial.ui.theme.SignalRed
import com.klabcc.piersocial.ui.theme.SurfaceCard
import com.klabcc.piersocial.ui.theme.SurfaceElevated
import com.klabcc.piersocial.ui.theme.TextSecondary
import com.klabcc.piersocial.ui.viewmodel.StoryRingGroup
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.util.Locale

private enum class FeedFilter(val label: String) {
    ALL("All"),
    FOLLOWING("Following"),
    SAVED("Saved")
}

private val POST_QUICK_REACTIONS = listOf("❤️", "🔥", "😂", "😮", "👏")
private val HASHTAG_REGEX = Regex("#[A-Za-z0-9_]+")

@Composable
fun FeedScreen(
    contentPadding: PaddingValues,
    localProfile: ProfileEntity?,
    localPeerId: String,
    storyGroups: List<StoryRingGroup>,
    posts: List<PostWithAuthor>,
    savedPosts: List<PostWithAuthor>,
    reactionsByPostId: Map<String, List<PostReactionEntity>>,
    postLikesByPostId: Map<String, List<PostLikeEntity>> = emptyMap(),
    storyLikesByStoryId: Map<String, List<StoryLikeEntity>> = emptyMap(),
    votesByPostId: Map<String, List<PollVoteEntity>>,
    activeTransfers: Map<String, ChunkTransferProgress>,
    developerMode: Boolean = false,
    onOpenStory: (peerId: String) -> Unit,
    onCreateStory: () -> Unit,
    onCreatePost: () -> Unit,
    onOpenProfile: (peerId: String) -> Unit,
    onOpenDm: (peerId: String) -> Unit,
    onToggleLike: (postId: String) -> Unit,
    onDoubleTapLike: (postId: String) -> Unit,
    onToggleSave: (postId: String) -> Unit,
    onTogglePin: (postId: String) -> Unit,
    onReactPost: (postId: String, emoji: String) -> Unit,
    onVotePoll: (postId: String, optionIndex: Int) -> Unit
) {
    val context = LocalContext.current
    var playingPostId by remember { mutableStateOf<String?>(null) }
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var activeFilter by remember { mutableStateOf(FeedFilter.ALL) }
    var selectedHashtag by remember { mutableStateOf<String?>(null) }

    val displayedPosts = remember(posts, savedPosts, activeFilter, selectedHashtag) {
        val base = when (activeFilter) {
            FeedFilter.ALL -> posts
            FeedFilter.FOLLOWING -> posts.filter {
                it.author?.isFollowing == true || it.author?.isLocalUser == true
            }
            FeedFilter.SAVED -> savedPosts
        }
        val tag = selectedHashtag
        if (tag.isNullOrBlank()) {
            base
        } else {
            base.filter { item ->
                HASHTAG_REGEX.findAll(item.post.caption)
                    .any { it.value.equals(tag, ignoreCase = true) }
            }
        }
    }

    fun stopPostAudio() {
        runCatching { activePlayer?.stop() }
        runCatching { activePlayer?.release() }
        activePlayer = null
        playingPostId = null
    }

    fun togglePostAudio(postId: String, audioMediaId: String) {
        if (playingPostId == postId) {
            stopPostAudio()
            return
        }
        stopPostAudio()
        val file = MediaStorageManager.getAudioFileForPlayback(context, audioMediaId)
        if (!file.exists()) return
        runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stopPostAudio()
                }
                prepare()
                start()
            }
            activePlayer = mp
            playingPostId = postId
        }.onFailure {
            stopPostAudio()
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPostAudio() }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        stopPostAudio()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 16.dp
        )
    ) {
        // Stories Ring Row
        item {
            StoriesRingBar(
                localProfile = localProfile,
                storyGroups = storyGroups,
                storyLikesByStoryId = storyLikesByStoryId,
                onCreateStory = onCreateStory,
                onOpenStory = onOpenStory
            )
            HorizontalDivider(color = BorderSubtle.copy(alpha = 0.5f))
        }

        // Chronological Feed Filter Bar (All / Following / Saved + active #hashtag)
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(FeedFilter.entries) { filter ->
                    val selected = activeFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { activeFilter = filter },
                        label = { Text(filter.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MeshCyan.copy(alpha = 0.18f),
                            selectedLabelColor = MeshCyan
                        )
                    )
                }
                if (selectedHashtag != null) {
                    item {
                        FilterChip(
                            selected = true,
                            onClick = { selectedHashtag = null },
                            label = { Text(selectedHashtag!!) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear topic filter",
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MeshViolet.copy(alpha = 0.24f),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }
        }

        if (displayedPosts.isEmpty()) {
            item {
                EmptyMeshFeedCard(
                    activeFilter = activeFilter,
                    selectedHashtag = selectedHashtag,
                    developerMode = developerMode,
                    onResetFilter = {
                        activeFilter = FeedFilter.ALL
                        selectedHashtag = null
                    },
                    onCreatePost = onCreatePost
                )
            }
        } else {
            items(displayedPosts, key = { it.post.postId }) { item ->
                val audioId = item.post.audioMediaId
                FeedPostCard(
                    item = item,
                    localPeerId = localPeerId,
                    reactions = reactionsByPostId[item.post.postId].orEmpty(),
                    likes = postLikesByPostId[item.post.postId].orEmpty(),
                    votes = votesByPostId[item.post.postId].orEmpty(),
                    transferProgress = activeTransfers[item.post.mediaId],
                    isPlayingAudio = playingPostId == item.post.postId,
                    developerMode = developerMode,
                    onOpenProfile = { onOpenProfile(item.post.authorPeerId) },
                    onOpenDm = { onOpenDm(item.post.authorPeerId) },
                    onToggleLike = { onToggleLike(item.post.postId) },
                    onDoubleTapLike = { onDoubleTapLike(item.post.postId) },
                    onToggleSave = { onToggleSave(item.post.postId) },
                    onTogglePin = { onTogglePin(item.post.postId) },
                    onReact = { emoji -> onReactPost(item.post.postId, emoji) },
                    onVotePoll = { idx -> onVotePoll(item.post.postId, idx) },
                    onHashtagClick = { tag -> selectedHashtag = tag },
                    onToggleAudio = {
                        if (!audioId.isNullOrEmpty()) {
                            togglePostAudio(item.post.postId, audioId)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun StoriesRingBar(
    localProfile: ProfileEntity?,
    storyGroups: List<StoryRingGroup>,
    storyLikesByStoryId: Map<String, List<StoryLikeEntity>>,
    onCreateStory: () -> Unit,
    onOpenStory: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val localHasStory = storyGroups.any { it.author.isLocalUser }
        if (!localHasStory) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(72.dp)
                        .clickable(onClick = onCreateStory)
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        PeerAvatar(profile = localProfile, size = 62.dp)
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MeshCyan)
                                .border(2.dp, ObsidianBg, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Story",
                                tint = Color.Black,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Your Story",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        items(storyGroups, key = { it.author.peerId }) { group ->
            val ringBrush = if (group.hasUnviewed) {
                Brush.linearGradient(listOf(MeshCyan, MeshViolet, Color(0xFFFF4081)))
            } else {
                Brush.linearGradient(listOf(BorderSubtle, BorderSubtle))
            }
            val localLikesCount = if (group.author.isLocalUser) {
                group.stories.sumOf { storyLikesByStoryId[it.story.storyId]?.size ?: 0 }
            } else {
                0
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(72.dp)
                    .clickable { onOpenStory(group.author.peerId) }
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        modifier = Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .border(2.5.dp, ringBrush, CircleShape)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        PeerAvatar(profile = group.author, size = 56.dp)
                    }
                    if (localLikesCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = SignalRed,
                            modifier = Modifier.border(2.dp, ObsidianBg, RoundedCornerShape(50))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = "$localLikesCount story likes",
                                    tint = Color.White,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    text = "$localLikesCount",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = if (group.author.isLocalUser) "Your Story" else group.author.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (group.hasUnviewed) MaterialTheme.colorScheme.onSurface else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FeedPostCard(
    item: PostWithAuthor,
    localPeerId: String,
    reactions: List<PostReactionEntity>,
    likes: List<PostLikeEntity>,
    votes: List<PollVoteEntity>,
    transferProgress: ChunkTransferProgress?,
    isPlayingAudio: Boolean,
    developerMode: Boolean,
    onOpenProfile: () -> Unit,
    onOpenDm: () -> Unit,
    onToggleLike: () -> Unit,
    onDoubleTapLike: () -> Unit,
    onToggleSave: () -> Unit,
    onTogglePin: () -> Unit,
    onReact: (String) -> Unit,
    onVotePoll: (Int) -> Unit,
    onHashtagClick: (String) -> Unit,
    onToggleAudio: () -> Unit
) {
    val post = item.post
    val author = item.author
    val blob = item.mediaBlob
    val hasAudio = !post.audioMediaId.isNullOrEmpty() && (item.audioBlob?.isComplete != false)

    var showHeartBurst by remember { mutableStateOf(false) }
    var showReactionPicker by remember { mutableStateOf(false) }

    LaunchedEffect(showHeartBurst) {
        if (showHeartBurst) {
            delay(650L)
            showHeartBurst = false
        }
    }

    val pollOptions = remember(post.pollOptionsJson) {
        val raw = post.pollOptionsJson ?: return@remember emptyList()
        runCatching { Json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    val groupedReactions = remember(reactions, localPeerId) {
        reactions
            .filter { it.emoji.isNotBlank() }
            .groupBy { it.emoji }
            .entries
            .sortedByDescending { it.value.size }
            .map { (emoji, list) ->
                Triple(emoji, list.size, list.any { it.reactorPeerId == localPeerId })
            }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenProfile)
            ) {
                PeerAvatar(profile = author, size = 38.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = author?.displayName ?: post.authorPeerId.take(8),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (developerMode) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = "Ed25519 Verified",
                                tint = MeshCyan,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Text(
                        text = if (developerMode) {
                            "${author?.handle ?: "@pier-${post.authorPeerId.take(8)}"} • ${formatRelativeTime(post.createdAt)}"
                        } else {
                            formatRelativeTime(post.createdAt)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            if (developerMode) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = SurfaceElevated
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = if (post.hopCount == 0) SignalGreen else MeshCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (post.hopCount == 0) "Local" else "${post.hopCount} hop",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (post.hopCount == 0) SignalGreen else MeshCyan
                        )
                    }
                }
                IconButton(onClick = onTogglePin) {
                    Icon(
                        imageVector = if (post.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = "Pin for retention",
                        tint = if (post.isPinned) SignalAmber else TextSecondary
                    )
                }
            }
        }

        // Photo 1:1 with double-tap heart burst
        val ratio = (post.width.toFloat() / post.height.coerceAtLeast(1)).coerceIn(0.75f, 1.33f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .pointerInput(post.postId) {
                    detectTapGestures(
                        onDoubleTap = {
                            showHeartBurst = true
                            onDoubleTapLike()
                        }
                    )
                }
        ) {
            MeshBitmapImage(
                mediaId = post.mediaId,
                isComplete = blob?.isComplete ?: true,
                transferProgress = transferProgress,
                developerMode = developerMode,
                modifier = Modifier.fillMaxSize()
            )

            androidx.compose.animation.AnimatedVisibility(
                visible = showHeartBurst,
                enter = scaleIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(),
                exit = scaleOut(targetScale = 1.35f) + fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = SignalRed.copy(alpha = 0.92f),
                    modifier = Modifier.size(92.dp)
                )
            }

            if (hasAudio) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .border(
                            1.dp,
                            if (isPlayingAudio) MeshCyan else BorderSubtle,
                            RoundedCornerShape(50)
                        )
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onToggleAudio)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlayingAudio) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isPlayingAudio) "Stop Audio" else "Play Audio",
                            tint = if (isPlayingAudio) MeshCyan else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (isPlayingAudio) "Playing" else String.format(
                                Locale.US,
                                "%.1fs",
                                post.audioDurationMs.coerceAtLeast(500) / 1000f
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPlayingAudio) MeshCyan else Color.White
                        )
                    }
                }
            }
        }

        // Action row
        val isLikedByMe = if (likes.isNotEmpty()) {
            likes.any { it.likerPeerId == localPeerId }
        } else {
            post.isLikedByMe
        }
        val displayLikeCount = if (likes.isNotEmpty()) likes.size else post.likeCount
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleLike) {
                Icon(
                    imageVector = if (isLikedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (isLikedByMe) SignalRed else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "$displayLikeCount",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            IconButton(onClick = { showReactionPicker = !showReactionPicker }) {
                Icon(
                    imageVector = Icons.Default.AddReaction,
                    contentDescription = "React with emoji",
                    tint = if (showReactionPicker) MeshCyan else MaterialTheme.colorScheme.onSurface
                )
            }

            if (author?.isLocalUser != true) {
                IconButton(onClick = onOpenDm) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Message",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (hasAudio) {
                Spacer(Modifier.width(4.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isPlayingAudio) MeshCyan.copy(alpha = 0.16f) else SurfaceCard,
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (isPlayingAudio) MeshCyan else BorderSubtle,
                            RoundedCornerShape(50)
                        )
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onToggleAudio)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlayingAudio) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPlayingAudio) "Stop Post Audio" else "Play Post Audio",
                            tint = MeshCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isPlayingAudio) "Stop" else String.format(
                                Locale.US,
                                "%.1fs Audio",
                                post.audioDurationMs.coerceAtLeast(500) / 1000f
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPlayingAudio) MeshCyan else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (developerMode) {
                val totalPostBytes = post.mediaByteSize + post.audioByteSize
                val totalChunks = (blob?.totalChunks ?: 1) + (item.audioBlob?.totalChunks ?: 0)
                Text(
                    text = "${formatByteSize(totalPostBytes)} • $totalChunks chunks",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            IconButton(onClick = onToggleSave) {
                Icon(
                    imageVector = if (post.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (post.isSaved) "Remove from Saved" else "Save Post",
                    tint = if (post.isSaved) MeshCyan else TextSecondary
                )
            }
        }

        // Expandable Emoji Reaction Picker
        AnimatedVisibility(visible = showReactionPicker) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(POST_QUICK_REACTIONS) { emoji ->
                    val isMine = reactions.any { it.reactorPeerId == localPeerId && it.emoji == emoji }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isMine) MeshCyan.copy(alpha = 0.22f) else SurfaceCard,
                        modifier = Modifier
                            .border(
                                1.dp,
                                if (isMine) MeshCyan else BorderSubtle,
                                RoundedCornerShape(50)
                            )
                            .clickable {
                                onReact(emoji)
                                showReactionPicker = false
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
        }

        // Mesh-synced Emoji Reaction Pills
        if (groupedReactions.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(groupedReactions, key = { it.first }) { (emoji, count, isMine) ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isMine) MeshCyan.copy(alpha = 0.16f) else SurfaceCard,
                        modifier = Modifier
                            .border(
                                1.dp,
                                if (isMine) MeshCyan.copy(alpha = 0.7f) else BorderSubtle,
                                RoundedCornerShape(50)
                            )
                            .clickable { onReact(emoji) }
                    ) {
                        Text(
                            text = "$emoji $count",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isMine) MeshCyan else Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Caption with clickable #topic hashtags
        if (post.caption.isNotBlank()) {
            HashtagCaptionText(
                caption = post.caption,
                onHashtagClick = onHashtagClick,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)
            )
        }

        // Interactive Mesh Poll Card
        if (!post.pollQuestion.isNullOrBlank() && pollOptions.size >= 2) {
            PostPollCard(
                question = post.pollQuestion,
                options = pollOptions,
                votes = votes,
                localPeerId = localPeerId,
                onVote = onVotePoll,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        if (developerMode && post.isPinned) {
            Text(
                text = "Pinned • Protected from LRU eviction",
                style = MaterialTheme.typography.labelSmall,
                color = SignalAmber,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = BorderSubtle.copy(alpha = 0.35f))
    }
}

@Composable
private fun HashtagCaptionText(
    caption: String,
    onHashtagClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val annotated = remember(caption) {
        buildAnnotatedString {
            var lastIdx = 0
            for (match in HASHTAG_REGEX.findAll(caption)) {
                if (match.range.first > lastIdx) {
                    append(caption.substring(lastIdx, match.range.first))
                }
                val tag = match.value
                withLink(
                    LinkAnnotation.Clickable(
                        tag = tag,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = MeshCyan,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    ) {
                        onHashtagClick(tag)
                    }
                ) {
                    append(tag)
                }
                lastIdx = match.range.last + 1
            }
            if (lastIdx < caption.length) {
                append(caption.substring(lastIdx))
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    )
}

@Composable
private fun PostPollCard(
    question: String,
    options: List<String>,
    votes: List<PollVoteEntity>,
    localPeerId: String,
    onVote: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalVotes = votes.size
    val myVoteIdx = votes.firstOrNull { it.voterPeerId == localPeerId }?.optionIndex

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Poll, null, tint = MeshCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = question,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            options.forEachIndexed { idx, label ->
                val count = votes.count { it.optionIndex == idx }
                val frac = if (totalVotes > 0) count.toFloat() / totalVotes else 0f
                val animatedFrac by animateFloatAsState(
                    targetValue = frac,
                    label = "pollBar_$idx"
                )
                val isSelected = myVoteIdx == idx

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ObsidianBg)
                        .border(
                            1.dp,
                            if (isSelected) MeshCyan else BorderSubtle,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onVote(idx) }
                ) {
                    if (totalVotes > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedFrac.coerceIn(0f, 1f))
                                .background(
                                    if (isSelected) MeshCyan.copy(alpha = 0.25f)
                                    else MeshViolet.copy(alpha = 0.18f)
                                )
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Your vote",
                                    tint = MeshCyan,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) MeshCyan else Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (totalVotes > 0) {
                            val pct = ((frac * 100f)).toInt()
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MeshCyan else TextSecondary
                            )
                        }
                    }
                }
            }

            Text(
                text = if (totalVotes == 1) "1 vote • Tap to vote or switch" else "$totalVotes votes • Tap to vote or switch",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun EmptyMeshFeedCard(
    activeFilter: FeedFilter,
    selectedHashtag: String?,
    developerMode: Boolean,
    onResetFilter: () -> Unit,
    onCreatePost: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
            .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Hub,
                contentDescription = null,
                tint = MeshCyan,
                modifier = Modifier.size(44.dp)
            )
            val title = when {
                selectedHashtag != null -> "No Posts for $selectedHashtag"
                activeFilter == FeedFilter.SAVED -> "No Saved Posts Yet"
                activeFilter == FeedFilter.FOLLOWING -> "No Posts from Following"
                developerMode -> "Your Mesh Feed is Quiet"
                else -> "Nothing Here Yet"
            }
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    selectedHashtag != null -> "Tap below to return to all posts."
                    activeFilter == FeedFilter.SAVED -> "Tap the bookmark icon on any post to save it here."
                    activeFilter == FeedFilter.FOLLOWING -> "Follow people nearby in Explore to see their posts here."
                    developerMode -> "Posts and 24h stories replicate automatically when PierSocial peers enter BLE range. Share your first 1080px WebP photo or keep Bluetooth enabled to sync nearby peers."
                    else -> "Share a photo or connect with people nearby over Bluetooth to see their posts here."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            if (activeFilter != FeedFilter.ALL || selectedHashtag != null) {
                OutlinedButton(onClick = onResetFilter) {
                    Text("Show All Posts", color = MeshCyan)
                }
            } else {
                OutlinedButton(onClick = onCreatePost) {
                    Text("Create Photo", color = MeshCyan)
                }
            }
        }
    }
}
