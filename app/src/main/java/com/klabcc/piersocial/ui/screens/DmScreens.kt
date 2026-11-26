package com.klabcc.piersocial.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klabcc.piersocial.data.local.ConversationSummary
import com.klabcc.piersocial.data.local.DeliveryState
import com.klabcc.piersocial.data.local.DirectMessageEntity
import com.klabcc.piersocial.data.local.NoteWithAuthor
import com.klabcc.piersocial.data.local.ProfileEntity
import com.klabcc.piersocial.data.repository.PierRepository
import com.klabcc.piersocial.ui.components.PeerAvatar
import com.klabcc.piersocial.ui.components.formatRelativeTime
import com.klabcc.piersocial.ui.theme.BorderSubtle
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.MeshViolet
import com.klabcc.piersocial.ui.theme.ObsidianBg
import com.klabcc.piersocial.ui.theme.SignalAmber
import com.klabcc.piersocial.ui.theme.SignalGreen
import com.klabcc.piersocial.ui.theme.SignalRed
import com.klabcc.piersocial.ui.theme.SurfaceCard
import com.klabcc.piersocial.ui.theme.SurfaceDark
import com.klabcc.piersocial.ui.theme.SurfaceElevated
import com.klabcc.piersocial.ui.theme.TextSecondary
import kotlinx.serialization.json.Json

private val NOTE_MOODS = listOf("", "🎧", "☕", "🌊", "⚡", "🔥", "🌙")
private val DM_QUICK_EMOJIS = listOf("❤️", "😂", "😮", "😢", "🔥", "👍")
private val DISAPPEAR_OPTIONS = listOf(
    0L to "Off",
    3_600_000L to "1 Hour",
    86_400_000L to "24 Hours"
)

@Composable
fun DmListScreen(
    contentPadding: PaddingValues,
    localProfile: ProfileEntity?,
    notes: List<NoteWithAuthor>,
    conversations: List<ConversationSummary>,
    developerMode: Boolean = false,
    onPublishNote: (text: String, moodEmoji: String) -> Unit,
    onClearNote: () -> Unit,
    onReplyToNote: (peerId: String, noteText: String) -> Unit,
    onOpenChat: (peerId: String) -> Unit
) {
    var showNoteDialog by remember { mutableStateOf(false) }
    var inspectingPeerNote by remember { mutableStateOf<NoteWithAuthor?>(null) }

    val localNote = remember(notes, localProfile?.peerId) {
        notes.firstOrNull { it.note.authorPeerId == localProfile?.peerId || it.author?.isLocalUser == true }
    }
    val peerNotes = remember(notes, localProfile?.peerId) {
        notes.filter { it.note.authorPeerId != localProfile?.peerId && it.author?.isLocalUser != true }
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 24h Ambient Status Notes Bar
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Notes (24h)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    item {
                        NoteBubbleItem(
                            profile = localProfile,
                            noteText = localNote?.note?.text,
                            moodEmoji = localNote?.note?.moodEmoji.orEmpty(),
                            isLocal = true,
                            label = "Your Note",
                            onClick = { showNoteDialog = true }
                        )
                    }
                    items(peerNotes, key = { it.note.noteId }) { item ->
                        NoteBubbleItem(
                            profile = item.author,
                            noteText = item.note.text,
                            moodEmoji = item.note.moodEmoji,
                            isLocal = false,
                            label = item.author?.displayName ?: item.note.authorPeerId.take(6),
                            onClick = { inspectingPeerNote = item }
                        )
                    }
                }
                HorizontalDivider(color = BorderSubtle.copy(alpha = 0.4f))
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
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, null, tint = SignalGreen, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "X25519 + AES-256-GCM E2EE • Multi-hop store-and-forward delivery",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        if (conversations.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (developerMode) "No Mesh Peers for DM Yet" else "No Messages Yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (developerMode) {
                                "Discover nearby peers over BLE to exchange E2EE encrypted messages."
                            } else {
                                "People nearby will appear here when Bluetooth is on."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        } else {
            items(conversations, key = { it.peer.peerId }) { conv ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                        .clickable { onOpenChat(conv.peer.peerId) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PeerAvatar(profile = conv.peer, size = 46.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = conv.peer.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (conv.peer.dmDisappearAfterMs > 0L) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.Default.Timer,
                                            contentDescription = "Disappearing messages active",
                                            tint = MeshCyan,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                val ts = conv.lastMessage?.timestamp
                                if (ts != null) {
                                    Text(
                                        text = formatRelativeTime(ts),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            val last = conv.lastMessage
                            Text(
                                text = last?.plaintextContent ?: if (developerMode) {
                                    "Tap to start an E2EE mesh chat (${conv.peer.handle})"
                                } else {
                                    "Tap to send a message"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNoteDialog) {
        var noteDraft by remember(localNote) { mutableStateOf(localNote?.note?.text.orEmpty()) }
        var moodDraft by remember(localNote) { mutableStateOf(localNote?.note?.moodEmoji.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("Share a 24h Note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Visible to nearby peers for 24 hours (${noteDraft.length}/${PierRepository.NOTE_MAX_CHARS}).",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = {
                            if (it.length <= PierRepository.NOTE_MAX_CHARS) {
                                noteDraft = it
                            }
                        },
                        placeholder = { Text("What are you up to?") },
                        maxLines = 2,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(NOTE_MOODS) { mood ->
                            FilterChip(
                                selected = moodDraft == mood,
                                onClick = { moodDraft = mood },
                                label = { Text(if (mood.isEmpty()) "None" else mood) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = noteDraft.isNotBlank(),
                    onClick = {
                        onPublishNote(noteDraft.trim(), moodDraft)
                        showNoteDialog = false
                    }
                ) {
                    Text("Share", color = MeshCyan)
                }
            },
            dismissButton = {
                Row {
                    if (localNote != null) {
                        TextButton(
                            onClick = {
                                onClearNote()
                                showNoteDialog = false
                            }
                        ) {
                            Text("Clear Note", color = SignalAmber)
                        }
                    }
                    TextButton(onClick = { showNoteDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    val targetNote = inspectingPeerNote
    if (targetNote != null) {
        val peerName = targetNote.author?.displayName ?: targetNote.note.authorPeerId.take(8)
        AlertDialog(
            onDismissRequest = { inspectingPeerNote = null },
            title = { Text("$peerName's Note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = buildString {
                                if (targetNote.note.moodEmoji.isNotBlank()) {
                                    append(targetNote.note.moodEmoji)
                                    append(" ")
                                }
                                append(targetNote.note.text)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                    Text(
                        text = "Posted ${formatRelativeTime(targetNote.note.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        inspectingPeerNote = null
                        onReplyToNote(targetNote.note.authorPeerId, targetNote.note.text)
                    }
                ) {
                    Text("Reply in DM", color = MeshCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { inspectingPeerNote = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun NoteBubbleItem(
    profile: ProfileEntity?,
    noteText: String?,
    moodEmoji: String,
    isLocal: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(86.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Box(modifier = Modifier.padding(top = 24.dp)) {
                PeerAvatar(profile = profile, size = 56.dp)
                if (isLocal && noteText.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MeshCyan)
                            .border(2.dp, ObsidianBg, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Note",
                            tint = Color.Black,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (!noteText.isNullOrBlank()) SurfaceElevated else SurfaceCard,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .border(
                        1.dp,
                        if (!noteText.isNullOrBlank()) MeshCyan.copy(alpha = 0.5f) else BorderSubtle,
                        RoundedCornerShape(14.dp)
                    )
            ) {
                Text(
                    text = when {
                        !noteText.isNullOrBlank() -> {
                            val prefix = if (moodEmoji.isNotBlank()) "$moodEmoji " else ""
                            "$prefix$noteText"
                        }
                        else -> "Note..."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!noteText.isNullOrBlank()) Color.White else TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DmChatScreen(
    peer: ProfileEntity?,
    localPeerId: String,
    messages: List<DirectMessageEntity>,
    sessionFingerprint: String,
    initialReplyPreview: String? = null,
    developerMode: Boolean = false,
    onSendMessage: (text: String, replyToMessageId: String?, replyToPreview: String?) -> Unit,
    onSendReaction: (targetMessageId: String, emoji: String) -> Unit,
    onSetDisappearTimer: (durationMs: Long) -> Unit,
    onBack: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var replyingToId by remember { mutableStateOf<String?>(null) }
    var replyingToPreview by remember(initialReplyPreview) { mutableStateOf(initialReplyPreview) }
    var selectedMessageForAction by remember { mutableStateOf<DirectMessageEntity?>(null) }
    var showTimerMenu by remember { mutableStateOf(false) }
    var hasScrolledInitial by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val submitDraft = {
        val text = draft
        if (text.isNotBlank()) {
            val rId = replyingToId
            val rPrev = replyingToPreview
            draft = ""
            replyingToId = null
            replyingToPreview = null
            onSendMessage(text, rId, rPrev)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (!hasScrolledInitial) {
                listState.scrollToItem(messages.lastIndex)
                hasScrolledInitial = true
            } else {
                listState.animateScrollToItem(messages.lastIndex)
            }
        }
    }

    val activeTimerMs = peer?.dmDisappearAfterMs ?: 0L

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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PeerAvatar(profile = peer, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = peer?.displayName ?: "Direct Message",
                                style = MaterialTheme.typography.titleMedium
                            )
                            val timerLabel = when (activeTimerMs) {
                                3_600_000L -> " • 1h timer"
                                86_400_000L -> " • 24h timer"
                                else -> ""
                            }
                            Text(
                                text = (if (developerMode) "E2EE • $sessionFingerprint" else "End-to-end encrypted") + timerLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (activeTimerMs > 0L) MeshCyan else SignalGreen
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showTimerMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "Disappearing messages timer",
                                tint = if (activeTimerMs > 0L) MeshCyan else Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = showTimerMenu,
                            onDismissRequest = { showTimerMenu = false }
                        ) {
                            DISAPPEAR_OPTIONS.forEach { (durationMs, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (activeTimerMs == durationMs) "✓ $label" else label,
                                            color = if (activeTimerMs == durationMs) MeshCyan else Color.White
                                        )
                                    },
                                    onClick = {
                                        onSetDisappearTimer(durationMs)
                                        showTimerMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = SurfaceDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (!replyingToPreview.isNullOrBlank()) {
                        Surface(
                            color = SurfaceCard,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Reply,
                                    contentDescription = null,
                                    tint = MeshCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = replyingToPreview!!,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        replyingToId = null
                                        replyingToPreview = null
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cancel reply",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            placeholder = {
                                Text(
                                    if (activeTimerMs > 0L) "Disappearing message..." else "Message..."
                                )
                            },
                            singleLine = false,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { submitDraft() }
                            ),
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            enabled = draft.isNotBlank(),
                            onClick = submitDraft
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (draft.isNotBlank()) MeshCyan else TextSecondary
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (developerMode) {
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SurfaceCard,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Lock, null, tint = SignalGreen, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "End-to-end encrypted with X25519 + Ed25519 signatures",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            items(messages, key = { it.messageId }) { msg ->
                val isMe = !msg.isIncoming
                val reactionCounts = remember(msg.reactionsJson, localPeerId) {
                    val map = runCatching {
                        Json.decodeFromString<Map<String, String>>(msg.reactionsJson)
                    }.getOrDefault(emptyMap())
                    map.entries
                        .filter { it.value.isNotBlank() }
                        .groupBy { it.value }
                        .entries
                        .map { (emoji, list) ->
                            Triple(emoji, list.size, list.any { it.key == localPeerId })
                        }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isMe) 16.dp else 4.dp,
                            bottomEnd = if (isMe) 4.dp else 16.dp
                        ),
                        color = if (isMe) MeshViolet.copy(alpha = 0.35f) else SurfaceCard,
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .border(
                                1.dp,
                                if (isMe) MeshViolet.copy(alpha = 0.6f) else BorderSubtle,
                                RoundedCornerShape(16.dp)
                            )
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { selectedMessageForAction = msg }
                            )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (!msg.replyToPreview.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Black.copy(alpha = 0.28f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp)
                                        .border(1.dp, MeshCyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                ) {
                                    Text(
                                        text = msg.replyToPreview,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                            Text(
                                text = msg.plaintextContent,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (msg.expiresAt > 0L) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = "Disappearing message",
                                        tint = MeshCyan,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                                if (developerMode) {
                                    Icon(Icons.Default.Hub, null, tint = MeshCyan, modifier = Modifier.size(11.dp))
                                    Text(
                                        text = "${msg.hopCount}h",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MeshCyan
                                    )
                                }
                                Text(
                                    text = formatRelativeTime(msg.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                if (isMe) {
                                    DeliveryBadge(msg.deliveryState, developerMode)
                                }
                            }
                        }
                    }

                    if (reactionCounts.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 3.dp)
                        ) {
                            reactionCounts.forEach { (emoji, count, mine) ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (mine) MeshCyan.copy(alpha = 0.22f) else SurfaceElevated,
                                    modifier = Modifier
                                        .border(
                                            1.dp,
                                            if (mine) MeshCyan else BorderSubtle,
                                            RoundedCornerShape(50)
                                        )
                                        .clickable { onSendReaction(msg.messageId, emoji) }
                                ) {
                                    Text(
                                        text = if (count > 1) "$emoji $count" else emoji,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Floating Reaction & Reply Action Dialog on long press
    val actionMsg = selectedMessageForAction
    if (actionMsg != null) {
        AlertDialog(
            onDismissRequest = { selectedMessageForAction = null },
            title = { Text("Message Options") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = actionMsg.plaintextContent,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(DM_QUICK_EMOJIS) { emoji ->
                            Surface(
                                shape = CircleShape,
                                color = SurfaceElevated,
                                modifier = Modifier
                                    .border(1.dp, BorderSubtle, CircleShape)
                                    .clickable {
                                        onSendReaction(actionMsg.messageId, emoji)
                                        selectedMessageForAction = null
                                    }
                            ) {
                                Text(
                                    text = emoji,
                                    fontSize = 20.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        replyingToId = actionMsg.messageId
                        replyingToPreview = actionMsg.plaintextContent.take(80)
                        selectedMessageForAction = null
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Reply, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reply", color = MeshCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedMessageForAction = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DeliveryBadge(state: DeliveryState, developerMode: Boolean) {
    val (icon, color, text) = when (state) {
        DeliveryState.QUEUED -> Triple(Icons.Default.Schedule, SignalAmber, if (developerMode) "Queued" else "Sending")
        DeliveryState.RELAYED -> Triple(Icons.Default.Done, MeshCyan, if (developerMode) "Relayed" else "Sent")
        DeliveryState.DELIVERED -> Triple(Icons.Default.DoneAll, SignalGreen, "Delivered")
        DeliveryState.FAILED -> Triple(Icons.Default.ErrorOutline, SignalRed, "Failed")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(2.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}
