package com.klabcc.piersocial.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.klabcc.piersocial.crypto.CryptoManager
import com.klabcc.piersocial.crypto.EncryptedDmPayload
import com.klabcc.piersocial.crypto.IdentityKeys
import com.klabcc.piersocial.data.local.ConversationSummary
import com.klabcc.piersocial.data.local.DeliveryState
import com.klabcc.piersocial.data.local.DirectMessageEntity
import com.klabcc.piersocial.data.local.MediaBlobEntity
import com.klabcc.piersocial.data.local.MeshPacketEntity
import com.klabcc.piersocial.data.local.NoteEntity
import com.klabcc.piersocial.data.local.NoteWithAuthor
import com.klabcc.piersocial.data.local.PierDatabase
import com.klabcc.piersocial.data.local.PollVoteEntity
import com.klabcc.piersocial.data.local.PostEntity
import com.klabcc.piersocial.data.local.PostLikeEntity
import com.klabcc.piersocial.data.local.PostReactionEntity
import com.klabcc.piersocial.data.local.PostWithAuthor
import com.klabcc.piersocial.data.local.ProfileEntity
import com.klabcc.piersocial.data.local.StoryEntity
import com.klabcc.piersocial.data.local.StoryLikeEntity
import com.klabcc.piersocial.data.local.StoryWithAuthor
import com.klabcc.piersocial.data.retention.RetentionManager
import com.klabcc.piersocial.data.retention.StorageStats
import com.klabcc.piersocial.data.storage.ChunkTransferProgress
import com.klabcc.piersocial.data.storage.ImagePipeline
import com.klabcc.piersocial.data.storage.MediaStorageManager
import com.klabcc.piersocial.data.storage.PhotoAdjustments
import com.klabcc.piersocial.data.storage.StoryOverlayData
import com.klabcc.piersocial.mesh.DmAckPayload
import com.klabcc.piersocial.mesh.DmRichCodec
import com.klabcc.piersocial.mesh.DmWirePayload
import com.klabcc.piersocial.mesh.MediaChunkWirePayload
import com.klabcc.piersocial.mesh.MeshProtocol
import com.klabcc.piersocial.mesh.NoteWirePayload
import com.klabcc.piersocial.mesh.PeerAnnouncePayload
import com.klabcc.piersocial.mesh.PollVoteWirePayload
import com.klabcc.piersocial.mesh.PostLikeWirePayload
import com.klabcc.piersocial.mesh.PostReactionWirePayload
import com.klabcc.piersocial.mesh.PostWirePayload
import com.klabcc.piersocial.mesh.StoryLikeWirePayload
import com.klabcc.piersocial.mesh.StoryWirePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class IncomingDmEvent(
    val senderPeerId: String,
    val senderDisplayName: String,
    val senderHandle: String,
    val messageId: String,
    val previewText: String,
    val timestamp: Long,
    val isReaction: Boolean
)

class PierRepository(
    context: Context,
    val database: PierDatabase,
    val cryptoManager: CryptoManager,
    val mediaStorageManager: MediaStorageManager,
    val retentionManager: RetentionManager
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val profileDao = database.profileDao()
    private val postDao = database.postDao()
    private val storyDao = database.storyDao()
    private val dmDao = database.directMessageDao()
    private val noteDao = database.noteDao()
    private val postReactionDao = database.postReactionDao()
    private val postLikeDao = database.postLikeDao()
    private val storyLikeDao = database.storyLikeDao()
    private val pollVoteDao = database.pollVoteDao()
    private val packetDao = database.meshPacketDao()
    private val mediaBlobDao = database.mediaBlobDao()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cachedProfiles = ConcurrentHashMap<String, ProfileEntity>()
    @Volatile
    private var cachedLocalProfile: ProfileEntity? = null

    val localIdentity: IdentityKeys by lazy { cryptoManager.getOrCreateIdentity() }

    val localProfileFlow: Flow<ProfileEntity?> = profileDao.observeLocalProfile()
    val allProfilesFlow: Flow<List<ProfileEntity>> = profileDao.observeAllProfiles()
    val feedPostsFlow: Flow<List<PostWithAuthor>> = postDao.observeFeedPosts()
    val savedPostsFlow: Flow<List<PostWithAuthor>> = postDao.observeSavedPosts()
    val postReactionsFlow: Flow<List<PostReactionEntity>> = postReactionDao.observeAllReactions()
    val postLikesFlow: Flow<List<PostLikeEntity>> = postLikeDao.observeAllActiveLikes()
    val storyLikesFlow: Flow<List<StoryLikeEntity>> = storyLikeDao.observeAllActiveLikes()
    val pollVotesFlow: Flow<List<PollVoteEntity>> = pollVoteDao.observeAllVotes()
    val storageStatsFlow: Flow<StorageStats> = retentionManager.storageStatsFlow
    val activeTransfersFlow: StateFlow<Map<String, ChunkTransferProgress>> =
        mediaStorageManager.activeTransfers

    private val _incomingDmEvents = MutableSharedFlow<IncomingDmEvent>(extraBufferCapacity = 32)
    val incomingDmEvents: SharedFlow<IncomingDmEvent> = _incomingDmEvents.asSharedFlow()

    fun observeActiveStories(): Flow<List<StoryWithAuthor>> =
        storyDao.observeActiveStories(System.currentTimeMillis())

    fun observeActiveNotes(): Flow<List<NoteWithAuthor>> =
        noteDao.observeActiveNotes(System.currentTimeMillis())

    fun observeProfile(peerId: String): Flow<ProfileEntity?> = profileDao.observeProfile(peerId)

    fun observePostsByAuthor(peerId: String): Flow<List<PostWithAuthor>> =
        postDao.observePostsByAuthor(peerId)

    fun observePinnedPosts(): Flow<List<PostWithAuthor>> = postDao.observePinnedPosts()

    fun observeConversation(peerId: String): Flow<List<DirectMessageEntity>> =
        dmDao.observeConversation(peerId).map { list ->
            val now = System.currentTimeMillis()
            list.filter { it.expiresAt == 0L || it.expiresAt > now }
        }

    fun observeConversationSummaries(): Flow<List<ConversationSummary>> =
        combine(
            profileDao.observeAllProfiles(),
            dmDao.observeAllMessages()
        ) { profiles, messages ->
            val now = System.currentTimeMillis()
            val validMessages = messages.filter { it.expiresAt == 0L || it.expiresAt > now }
            val profileMap = profiles.associateBy { it.peerId }
            val byPeer = validMessages.groupBy { it.conversationPeerId }
            val peersWithMessages = byPeer.mapNotNull { (peerId, msgs) ->
                val profile = profileMap[peerId] ?: return@mapNotNull null
                ConversationSummary(peer = profile, lastMessage = msgs.maxByOrNull { it.timestamp })
            }
            val remainingFollows = profiles
                .filter { !it.isLocalUser && !it.isMuted && !byPeer.containsKey(it.peerId) }
                .map { ConversationSummary(peer = it, lastMessage = null) }
            (peersWithMessages + remainingFollows)
                .sortedByDescending { it.lastMessage?.timestamp ?: it.peer.updatedAt }
        }

    suspend fun ensureLocalProfileInitialized(): ProfileEntity {
        cachedLocalProfile?.let { return it }
        return withContext(Dispatchers.IO) {
            cachedLocalProfile?.let { return@withContext it }
            val existing = profileDao.getLocalProfile()
            if (existing != null) {
                cachedLocalProfile = existing
                return@withContext existing
            }

            val keys = localIdentity
            val edPubB64 = CryptoManager.encodeBase64(keys.ed25519PublicKey)
            val xPubB64 = CryptoManager.encodeBase64(keys.x25519PublicKey)
            val sig = cryptoManager.signString("${keys.peerId}|${keys.handle}|Pier Explorer", keys.ed25519PrivateKey)
            val profile = ProfileEntity(
                peerId = keys.peerId,
                ed25519PubKey = edPubB64,
                x25519PubKey = xPubB64,
                handle = keys.handle,
                fingerprint = keys.fingerprint,
                displayName = "Pier Node ${keys.peerId.take(4).uppercase()}",
                bio = "Decentralized BLE mesh node • Keys in Android Keystore",
                avatarMediaId = null,
                isLocalUser = true,
                isFollowing = false,
                isMuted = false,
                isPinned = true,
                updatedAt = System.currentTimeMillis(),
                signature = sig
            )
            profileDao.upsert(profile)
            cachedLocalProfile = profile
            profile
        }
    }

    suspend fun updateLocalProfile(
        displayName: String,
        bio: String,
        avatarBitmap: Bitmap? = null
    ): ProfileEntity = withContext(Dispatchers.IO) {
        val current = ensureLocalProfileInitialized()
        var avatarMediaId = current.avatarMediaId
        if (avatarBitmap != null) {
            val processed = ImagePipeline.processAndExport(
                source = avatarBitmap,
                isStory = false,
                adjustments = PhotoAdjustments(),
                quality = 78
            )
            avatarMediaId = "avatar_${current.peerId}_${System.currentTimeMillis()}"
            mediaStorageManager.storeLocalMedia(
                mediaId = avatarMediaId,
                ownerPeerId = current.peerId,
                bytes = processed.bytes,
                isPinned = true
            )
        }
        val cleanName = displayName.trim().ifBlank { current.displayName }
        val cleanBio = bio.trim()
        val sig = cryptoManager.signString("${current.peerId}|$cleanName|$cleanBio", localIdentity.ed25519PrivateKey)
        val updated = current.copy(
            displayName = cleanName,
            bio = cleanBio,
            avatarMediaId = avatarMediaId,
            updatedAt = System.currentTimeMillis(),
            signature = sig
        )
        profileDao.upsert(updated)
        cachedLocalProfile = updated
        updated
    }

    suspend fun createFeedPost(
        sourceBitmap: Bitmap,
        adjustments: PhotoAdjustments,
        caption: String,
        audioFile: File? = null,
        audioDurationMs: Int = 0,
        pollQuestion: String? = null,
        pollOptions: List<String> = emptyList()
    ): Pair<PostEntity, MediaBlobEntity> = withContext(Dispatchers.IO) {
        val me = ensureLocalProfileInitialized()
        val processed = ImagePipeline.processAndExport(
            source = sourceBitmap,
            isStory = false,
            adjustments = adjustments
        )
        val mediaId = "med_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val blob = mediaStorageManager.storeLocalMedia(
            mediaId = mediaId,
            ownerPeerId = me.peerId,
            bytes = processed.bytes,
            isPinned = false
        )

        var audioBlob: MediaBlobEntity? = null
        if (audioFile != null && audioFile.exists() && audioFile.length() > 0L) {
            val audioBytes = audioFile.readBytes()
            val audioId = "paud_${UUID.randomUUID().toString().replace("-", "").take(16)}"
            audioBlob = mediaStorageManager.storeLocalMedia(
                mediaId = audioId,
                ownerPeerId = me.peerId,
                bytes = audioBytes,
                isPinned = false
            )
            audioFile.delete()
        }

        val cleanQuestion = pollQuestion?.trim()?.takeIf { it.isNotEmpty() }
        val cleanOptions = pollOptions.map { it.trim() }.filter { it.isNotEmpty() }.take(4)
        val hasPoll = cleanQuestion != null && cleanOptions.size >= 2
        val serializedPollOptions = if (hasPoll) json.encodeToString(cleanOptions) else null

        val postId = "post_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val createdAt = System.currentTimeMillis()
        val wire = PostWirePayload(
            postId = postId,
            authorPeerId = me.peerId,
            authorEd25519PubKey = me.ed25519PubKey,
            caption = caption.trim(),
            mediaId = mediaId,
            mediaByteSize = blob.totalBytes,
            mediaChunkSize = blob.chunkSize,
            mediaTotalChunks = blob.totalChunks,
            mediaSha256Hex = blob.sha256Hex,
            width = processed.width,
            height = processed.height,
            createdAt = createdAt,
            signature = "",
            audioMediaId = audioBlob?.mediaId,
            audioByteSize = audioBlob?.totalBytes ?: 0L,
            audioChunkSize = audioBlob?.chunkSize ?: 0,
            audioTotalChunks = audioBlob?.totalChunks ?: 0,
            audioSha256Hex = audioBlob?.sha256Hex ?: "",
            audioDurationMs = if (audioBlob != null) audioDurationMs.coerceIn(0, 5000) else 0,
            pollQuestion = if (hasPoll) cleanQuestion else null,
            pollOptionsJson = serializedPollOptions
        )
        val sig = cryptoManager.signString(wire.canonicalSigningString(), localIdentity.ed25519PrivateKey)
        val signedWire = wire.copy(signature = sig)

        val post = PostEntity(
            postId = postId,
            authorPeerId = me.peerId,
            caption = caption.trim(),
            mediaId = mediaId,
            mediaByteSize = blob.totalBytes,
            mimeType = processed.mimeType,
            width = processed.width,
            height = processed.height,
            audioMediaId = audioBlob?.mediaId,
            audioDurationMs = signedWire.audioDurationMs,
            audioByteSize = audioBlob?.totalBytes ?: 0L,
            pollQuestion = if (hasPoll) cleanQuestion else null,
            pollOptionsJson = serializedPollOptions,
            createdAt = createdAt,
            lastAccessedAt = createdAt,
            isPinned = false,
            hopCount = 0,
            signature = sig,
            originatedLocally = true
        )
        postDao.upsert(post)

        // Queue gossip packet for store-and-forward replication
        val payloadBytes = json.encodeToString(signedWire).toByteArray(Charsets.UTF_8)
        packetDao.insertIfAbsent(
            MeshPacketEntity(
                packetId = "pkt_post_${post.postId}",
                packetType = MeshProtocol.PacketType.POST_RECORD.name,
                originPeerId = me.peerId,
                ttl = MeshProtocol.DEFAULT_TTL,
                hopCount = 0,
                payloadBytes = payloadBytes,
                createdAt = createdAt,
                expiresAt = createdAt + 7L * 24 * 60 * 60 * 1000L,
                byteSize = payloadBytes.size.toLong()
            )
        )
        retentionManager.enforceRetentionPolicy()
        post to blob
    }

    suspend fun createStory(
        sourceBitmap: Bitmap?,
        adjustments: PhotoAdjustments,
        overlay: StoryOverlayData,
        caption: String,
        audioFile: File? = null,
        audioDurationMs: Int = 0
    ): Pair<StoryEntity, MediaBlobEntity> = withContext(Dispatchers.IO) {
        val me = ensureLocalProfileInitialized()
        val processed = ImagePipeline.processAndExport(
            source = sourceBitmap,
            isStory = true,
            adjustments = adjustments,
            storyOverlay = overlay
        )
        val mediaId = "smed_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val blob = mediaStorageManager.storeLocalMedia(
            mediaId = mediaId,
            ownerPeerId = me.peerId,
            bytes = processed.bytes,
            isPinned = false
        )

        var audioBlob: MediaBlobEntity? = null
        if (audioFile != null && audioFile.exists() && audioFile.length() > 0L) {
            val audioBytes = audioFile.readBytes()
            val audioId = "saud_${UUID.randomUUID().toString().replace("-", "").take(16)}"
            audioBlob = mediaStorageManager.storeLocalMedia(
                mediaId = audioId,
                ownerPeerId = me.peerId,
                bytes = audioBytes,
                isPinned = false
            )
            audioFile.delete()
        }

        val storyId = "story_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val createdAt = System.currentTimeMillis()
        val expiresAt = createdAt + STORY_TTL_MS
        val wire = StoryWirePayload(
            storyId = storyId,
            authorPeerId = me.peerId,
            authorEd25519PubKey = me.ed25519PubKey,
            mediaId = mediaId,
            mediaByteSize = blob.totalBytes,
            mediaChunkSize = blob.chunkSize,
            mediaTotalChunks = blob.totalChunks,
            mediaSha256Hex = blob.sha256Hex,
            caption = caption.trim(),
            createdAt = createdAt,
            expiresAt = expiresAt,
            signature = "",
            audioMediaId = audioBlob?.mediaId,
            audioByteSize = audioBlob?.totalBytes ?: 0L,
            audioChunkSize = audioBlob?.chunkSize ?: 0,
            audioTotalChunks = audioBlob?.totalChunks ?: 0,
            audioSha256Hex = audioBlob?.sha256Hex ?: "",
            audioDurationMs = if (audioBlob != null) audioDurationMs.coerceIn(0, 5000) else 0
        )
        val sig = cryptoManager.signString(wire.canonicalSigningString(), localIdentity.ed25519PrivateKey)
        val signedWire = wire.copy(signature = sig)

        val story = StoryEntity(
            storyId = storyId,
            authorPeerId = me.peerId,
            mediaId = mediaId,
            mediaByteSize = blob.totalBytes,
            audioMediaId = audioBlob?.mediaId,
            audioDurationMs = signedWire.audioDurationMs,
            audioByteSize = audioBlob?.totalBytes ?: 0L,
            caption = caption.trim(),
            createdAt = createdAt,
            expiresAt = expiresAt,
            lastAccessedAt = createdAt,
            isPinned = false,
            isViewed = false,
            hopCount = 0,
            signature = sig,
            originatedLocally = true
        )
        storyDao.insertIfAbsent(story)

        val payloadBytes = json.encodeToString(signedWire).toByteArray(Charsets.UTF_8)
        packetDao.insertIfAbsent(
            MeshPacketEntity(
                packetId = "pkt_story_${story.storyId}",
                packetType = MeshProtocol.PacketType.STORY_RECORD.name,
                originPeerId = me.peerId,
                ttl = MeshProtocol.DEFAULT_TTL,
                hopCount = 0,
                payloadBytes = payloadBytes,
                createdAt = createdAt,
                expiresAt = expiresAt,
                byteSize = payloadBytes.size.toLong()
            )
        )
        retentionManager.enforceRetentionPolicy()
        story to blob
    }

    suspend fun publishNote(
        text: String,
        moodEmoji: String = ""
    ): Pair<NoteEntity, NoteWirePayload>? = withContext(Dispatchers.IO) {
        val cleanText = text.trim().take(NOTE_MAX_CHARS)
        if (cleanText.isEmpty()) return@withContext null
        val cleanMood = moodEmoji.trim().take(8)
        val me = ensureLocalProfileInitialized()
        val createdAt = System.currentTimeMillis()
        val expiresAt = createdAt + STORY_TTL_MS
        val noteId = "note_${me.peerId}_${createdAt}"
        val wire = NoteWirePayload(
            noteId = noteId,
            authorPeerId = me.peerId,
            authorEd25519PubKey = me.ed25519PubKey,
            text = cleanText,
            moodEmoji = cleanMood,
            createdAt = createdAt,
            expiresAt = expiresAt,
            signature = ""
        )
        val sig = cryptoManager.signString(wire.canonicalSigningString(), localIdentity.ed25519PrivateKey)
        val signedWire = wire.copy(signature = sig)
        val entity = NoteEntity(
            noteId = noteId,
            authorPeerId = me.peerId,
            text = cleanText,
            moodEmoji = cleanMood,
            createdAt = createdAt,
            expiresAt = expiresAt,
            hopCount = 0,
            signature = sig,
            originatedLocally = true
        )
        noteDao.deleteByAuthor(me.peerId)
        noteDao.upsert(entity)
        val payloadBytes = json.encodeToString(signedWire).toByteArray(Charsets.UTF_8)
        packetDao.insertIfAbsent(
            MeshPacketEntity(
                packetId = "pkt_note_${noteId}",
                packetType = MeshProtocol.PacketType.NOTE_RECORD.name,
                originPeerId = me.peerId,
                ttl = MeshProtocol.DEFAULT_TTL,
                hopCount = 0,
                payloadBytes = payloadBytes,
                createdAt = createdAt,
                expiresAt = expiresAt,
                byteSize = payloadBytes.size.toLong()
            )
        )
        entity to signedWire
    }

    suspend fun clearLocalNote(): NoteWirePayload = withContext(Dispatchers.IO) {
        val me = ensureLocalProfileInitialized()
        noteDao.deleteByAuthor(me.peerId)
        val now = System.currentTimeMillis()
        val wire = NoteWirePayload(
            noteId = "note_clr_${me.peerId}_$now",
            authorPeerId = me.peerId,
            authorEd25519PubKey = me.ed25519PubKey,
            text = "",
            moodEmoji = "",
            createdAt = now,
            expiresAt = now,
            signature = ""
        )
        val sig = cryptoManager.signString(wire.canonicalSigningString(), localIdentity.ed25519PrivateKey)
        wire.copy(signature = sig)
    }

    suspend fun reactToPost(
        postId: String,
        emoji: String
    ): Pair<PostReactionEntity, PostReactionWirePayload>? = withContext(Dispatchers.IO) {
        postDao.getPost(postId) ?: return@withContext null
        val me = ensureLocalProfileInitialized()
        val existing = postReactionDao.getReaction(postId, me.peerId)
        val nextEmoji = if (existing?.emoji == emoji) "" else emoji
        val now = maxOf(System.currentTimeMillis(), (existing?.reactedAt ?: 0L) + 1L)
        val wire = PostReactionWirePayload(
            postId = postId,
            reactorPeerId = me.peerId,
            reactorEd25519PubKey = me.ed25519PubKey,
            emoji = nextEmoji,
            reactedAt = now,
            signature = ""
        )
        val sig = cryptoManager.signString(wire.canonicalSigningString(), localIdentity.ed25519PrivateKey)
        val signedWire = wire.copy(signature = sig)
        val entity = PostReactionEntity(
            postId = postId,
            reactorPeerId = me.peerId,
            emoji = nextEmoji,
            reactedAt = now,
            signature = sig,
            reactorEd25519PubKey = me.ed25519PubKey
        )
        postReactionDao.upsert(entity)
        val payloadBytes = json.encodeToString(signedWire).toByteArray(Charsets.UTF_8)
        packetDao.insertIfAbsent(
            MeshPacketEntity(
                packetId = "pkt_react_${postId}_${me.peerId}_$now",
                packetType = MeshProtocol.PacketType.POST_REACTION.name,
                originPeerId = me.peerId,
                ttl = MeshProtocol.DEFAULT_TTL,
                hopCount = 0,
                payloadBytes = payloadBytes,
                createdAt = now,
                expiresAt = now + 7L * 24 * 60 * 60 * 1000L,
                byteSize = payloadBytes.size.toLong()
            )
        )
        entity to signedWire
    }

    suspend fun voteOnPoll(
        postId: String,
        optionIndex: Int
    ): Pair<PollVoteEntity, PollVoteWirePayload>? = withContext(Dispatchers.IO) {
        val post = postDao.getPost(postId) ?: return@withContext null
        val optionsJson = post.pollOptionsJson ?: return@withContext null
        val options = runCatching { json.decodeFromString<List<String>>(optionsJson) }.getOrNull() ?: return@withContext null
        if (optionIndex !in options.indices) return@withContext null

        val me = ensureLocalProfileInitialized()
        val now = System.currentTimeMillis()
        val wire = PollVoteWirePayload(
            postId = postId,
            voterPeerId = me.peerId,
            voterEd25519PubKey = me.ed25519PubKey,
            optionIndex = optionIndex,
            votedAt = now,
            signature = ""
        )
        val sig = cryptoManager.signString(wire.canonicalSigningString(), localIdentity.ed25519PrivateKey)
        val signedWire = wire.copy(signature = sig)
        val entity = PollVoteEntity(
            postId = postId,
            voterPeerId = me.peerId,
            optionIndex = optionIndex,
            votedAt = now,
            signature = sig
        )
        pollVoteDao.upsert(entity)
        val payloadBytes = json.encodeToString(signedWire).toByteArray(Charsets.UTF_8)
        packetDao.insertIfAbsent(
            MeshPacketEntity(
                packetId = "pkt_vote_${postId}_${me.peerId}_$now",
                packetType = MeshProtocol.PacketType.POLL_VOTE.name,
                originPeerId = me.peerId,
                ttl = MeshProtocol.DEFAULT_TTL,
                hopCount = 0,
                payloadBytes = payloadBytes,
                createdAt = now,
                expiresAt = now + 7L * 24 * 60 * 60 * 1000L,
                byteSize = payloadBytes.size.toLong()
            )
        )
        entity to signedWire
    }

    suspend fun setDmDisappearTimer(peerId: String, durationMs: Long) = withContext(Dispatchers.IO) {
        profileDao.setDmDisappearAfter(peerId, durationMs.coerceAtLeast(0L))
        cachedProfiles.computeIfPresent(peerId) { _, existing ->
            existing.copy(dmDisappearAfterMs = durationMs.coerceAtLeast(0L))
        }
    }

    /**
     * Low-latency DM send path: uses cached keys when available, encrypts, dispatches over
     * BLE express transport immediately via [onReadyForMesh], and persists store-and-forward
     * metadata concurrently without blocking radio transmission.
     */
    suspend fun sendDirectMessageFast(
        recipientPeerId: String,
        plaintext: String,
        replyToMessageId: String? = null,
        replyToPreview: String? = null,
        onReadyForMesh: suspend (DirectMessageEntity, DmWirePayload) -> Unit
    ): Pair<DirectMessageEntity, DmWirePayload>? = withContext(Dispatchers.IO) {
        val recipient = cachedProfiles[recipientPeerId]
            ?: profileDao.getProfile(recipientPeerId)?.also { cachedProfiles[recipientPeerId] = it }
            ?: return@withContext null
        val me = ensureLocalProfileInitialized()
        val cleanText = plaintext.trim()
        val cleanReplyPreview = replyToPreview?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
        val disappearMs = recipient.dmDisappearAfterMs.coerceAtLeast(0L)
        val wirePlaintext = DmRichCodec.encode(
            text = cleanText,
            replyToMessageId = replyToMessageId,
            replyToPreview = cleanReplyPreview,
            disappearAfterMs = disappearMs
        )

        val recipientXPub = CryptoManager.decodeBase64(recipient.x25519PubKey)
        val encrypted = cryptoManager.encryptDirectMessage(
            plaintext = wirePlaintext,
            recipientX25519PubKey = recipientXPub,
            senderIdentity = localIdentity
        )
        val encryptedJson = json.encodeToString(encrypted)
        val messageId = "dm_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val now = System.currentTimeMillis()
        val expiresAt = if (disappearMs > 0L) now + disappearMs else 0L
        val entity = DirectMessageEntity(
            messageId = messageId,
            conversationPeerId = recipientPeerId,
            senderPeerId = me.peerId,
            recipientPeerId = recipientPeerId,
            plaintextContent = cleanText,
            encryptedPayloadJson = encryptedJson,
            timestamp = now,
            hopCount = 0,
            deliveryState = DeliveryState.QUEUED,
            isIncoming = false,
            replyToMessageId = replyToMessageId,
            replyToPreview = cleanReplyPreview,
            expiresAt = expiresAt
        )
        val wire = DmWirePayload(
            messageId = messageId,
            senderPeerId = me.peerId,
            senderEd25519PubKey = me.ed25519PubKey,
            recipientPeerId = recipientPeerId,
            encryptedPayloadJson = encryptedJson,
            timestamp = now
        )

        // Dispatch onto the BLE express lane immediately before waiting on store-and-forward disk writes
        val uiPersistJob = backgroundScope.launch {
            dmDao.insertIfAbsent(entity)
        }
        backgroundScope.launch {
            val wireBytes = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
            packetDao.insertIfAbsent(
                MeshPacketEntity(
                    packetId = "pkt_dm_$messageId",
                    packetType = MeshProtocol.PacketType.DM_PACKET.name,
                    originPeerId = me.peerId,
                    targetPeerId = recipientPeerId,
                    ttl = MeshProtocol.DEFAULT_TTL,
                    hopCount = 0,
                    payloadBytes = wireBytes,
                    createdAt = now,
                    expiresAt = now + 7L * 24 * 60 * 60 * 1000L,
                    byteSize = wireBytes.size.toLong()
                )
            )
        }
        uiPersistJob.join()
        onReadyForMesh(entity, wire)
        entity to wire
    }

    suspend fun sendDmReactionFast(
        recipientPeerId: String,
        targetMessageId: String,
        emoji: String,
        onReadyForMesh: suspend (DirectMessageEntity, DmWirePayload) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val recipient = cachedProfiles[recipientPeerId]
            ?: profileDao.getProfile(recipientPeerId)?.also { cachedProfiles[recipientPeerId] = it }
            ?: return@withContext false
        val targetMsg = dmDao.getMessage(targetMessageId) ?: return@withContext false
        val me = ensureLocalProfileInitialized()

        val currentMap = parseDmReactions(targetMsg.reactionsJson).toMutableMap()
        val nextEmoji = if (currentMap[me.peerId] == emoji) "" else emoji
        if (nextEmoji.isEmpty()) {
            currentMap.remove(me.peerId)
        } else {
            currentMap[me.peerId] = nextEmoji
        }
        dmDao.updateReactionsJson(targetMessageId, json.encodeToString(currentMap))

        val wirePlaintext = DmRichCodec.encode(
            text = "",
            reactionToMessageId = targetMessageId,
            reactionEmoji = nextEmoji
        )
        val encrypted = cryptoManager.encryptDirectMessage(
            plaintext = wirePlaintext,
            recipientX25519PubKey = CryptoManager.decodeBase64(recipient.x25519PubKey),
            senderIdentity = localIdentity
        )
        val encryptedJson = json.encodeToString(encrypted)
        val now = System.currentTimeMillis()
        val reactionPacketId = "dmr_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val dummyEntity = DirectMessageEntity(
            messageId = reactionPacketId,
            conversationPeerId = recipientPeerId,
            senderPeerId = me.peerId,
            recipientPeerId = recipientPeerId,
            plaintextContent = "",
            encryptedPayloadJson = encryptedJson,
            timestamp = now,
            hopCount = 0,
            deliveryState = DeliveryState.QUEUED,
            isIncoming = false
        )
        val wire = DmWirePayload(
            messageId = reactionPacketId,
            senderPeerId = me.peerId,
            senderEd25519PubKey = me.ed25519PubKey,
            recipientPeerId = recipientPeerId,
            encryptedPayloadJson = encryptedJson,
            timestamp = now
        )
        onReadyForMesh(dummyEntity, wire)
        true
    }

    suspend fun sendDirectMessage(
        recipientPeerId: String,
        plaintext: String
    ): Pair<DirectMessageEntity, DmWirePayload>? = withContext(Dispatchers.IO) {
        val recipient = cachedProfiles[recipientPeerId]
            ?: profileDao.getProfile(recipientPeerId)?.also { cachedProfiles[recipientPeerId] = it }
            ?: return@withContext null
        val me = ensureLocalProfileInitialized()
        val cleanText = plaintext.trim()
        val recipientXPub = CryptoManager.decodeBase64(recipient.x25519PubKey)
        val encrypted = cryptoManager.encryptDirectMessage(
            plaintext = cleanText,
            recipientX25519PubKey = recipientXPub,
            senderIdentity = localIdentity
        )
        val encryptedJson = json.encodeToString(encrypted)
        val messageId = "dm_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val now = System.currentTimeMillis()
        val entity = DirectMessageEntity(
            messageId = messageId,
            conversationPeerId = recipientPeerId,
            senderPeerId = me.peerId,
            recipientPeerId = recipientPeerId,
            plaintextContent = cleanText,
            encryptedPayloadJson = encryptedJson,
            timestamp = now,
            hopCount = 0,
            deliveryState = DeliveryState.QUEUED,
            isIncoming = false
        )
        dmDao.insertIfAbsent(entity)

        val wire = DmWirePayload(
            messageId = messageId,
            senderPeerId = me.peerId,
            senderEd25519PubKey = me.ed25519PubKey,
            recipientPeerId = recipientPeerId,
            encryptedPayloadJson = encryptedJson,
            timestamp = now
        )
        val wireBytes = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
        packetDao.insertIfAbsent(
            MeshPacketEntity(
                packetId = "pkt_dm_$messageId",
                packetType = MeshProtocol.PacketType.DM_PACKET.name,
                originPeerId = me.peerId,
                targetPeerId = recipientPeerId,
                ttl = MeshProtocol.DEFAULT_TTL,
                hopCount = 0,
                payloadBytes = wireBytes,
                createdAt = now,
                expiresAt = now + 7L * 24 * 60 * 60 * 1000L,
                byteSize = wireBytes.size.toLong()
            )
        )
        entity to wire
    }

    suspend fun toggleLike(postId: String): Pair<PostLikeEntity, PostLikeWirePayload>? = withContext(Dispatchers.IO) {
        val post = postDao.getPost(postId) ?: return@withContext null
        val me = ensureLocalProfileInitialized()
        val existing = postLikeDao.getLike(postId, me.peerId)
        val currentlyLiked = existing?.isLiked ?: post.isLikedByMe
        setLocalPostLike(postId, !currentlyLiked, existing?.likedAt ?: 0L)
    }

    suspend fun ensureLiked(postId: String): Pair<PostLikeEntity, PostLikeWirePayload>? = withContext(Dispatchers.IO) {
        val post = postDao.getPost(postId) ?: return@withContext null
        val me = ensureLocalProfileInitialized()
        val existing = postLikeDao.getLike(postId, me.peerId)
        val currentlyLiked = existing?.isLiked ?: post.isLikedByMe
        if (currentlyLiked) return@withContext null
        setLocalPostLike(postId, true, existing?.likedAt ?: 0L)
    }

    private suspend fun setLocalPostLike(
        postId: String,
        liked: Boolean,
        previousLikedAt: Long
    ): Pair<PostLikeEntity, PostLikeWirePayload> {
        val me = ensureLocalProfileInitialized()
        val now = maxOf(System.currentTimeMillis(), previousLikedAt + 1L)
        val wire = PostLikeWirePayload(
            postId = postId,
            likerPeerId = me.peerId,
            likerEd25519PubKey = me.ed25519PubKey,
            isLiked = liked,
            likedAt = now,
            signature = ""
        )
        val sig = cryptoManager.signString(wire.canonicalSigningString(), localIdentity.ed25519PrivateKey)
        val signedWire = wire.copy(signature = sig)
        val entity = PostLikeEntity(
            postId = postId,
            likerPeerId = me.peerId,
            likerEd25519PubKey = me.ed25519PubKey,
            isLiked = liked,
            likedAt = now,
            signature = sig
        )
        postLikeDao.upsert(entity)
        syncPostLikeProjection(postId)
        val payloadBytes = json.encodeToString(signedWire).toByteArray(Charsets.UTF_8)
        packetDao.insertIfAbsent(
            MeshPacketEntity(
                packetId = "pkt_plike_${postId}_${me.peerId}_$now",
                packetType = MeshProtocol.PacketType.POST_LIKE.name,
                originPeerId = me.peerId,
                ttl = MeshProtocol.DEFAULT_TTL,
                hopCount = 0,
                payloadBytes = payloadBytes,
                createdAt = now,
                expiresAt = now + 7L * 24 * 60 * 60 * 1000L,
                byteSize = payloadBytes.size.toLong()
            )
        )
        return entity to signedWire
    }

    private suspend fun syncPostLikeProjection(postId: String) {
        val count = postLikeDao.getActiveLikeCount(postId)
        val likedByMe = postLikeDao.getLike(postId, localIdentity.peerId)?.isLiked == true
        postDao.setLikeState(postId, likedByMe, count)
    }

    suspend fun toggleStoryLike(storyId: String): Pair<StoryLikeEntity, StoryLikeWirePayload>? = withContext(Dispatchers.IO) {
        storyDao.getStory(storyId) ?: return@withContext null
        val me = ensureLocalProfileInitialized()
        val existing = storyLikeDao.getLike(storyId, me.peerId)
        val nextLiked = !(existing?.isLiked ?: false)
        setLocalStoryLike(storyId, nextLiked, existing?.likedAt ?: 0L)
    }

    suspend fun ensureStoryLiked(storyId: String): Pair<StoryLikeEntity, StoryLikeWirePayload>? = withContext(Dispatchers.IO) {
        storyDao.getStory(storyId) ?: return@withContext null
        val me = ensureLocalProfileInitialized()
        val existing = storyLikeDao.getLike(storyId, me.peerId)
        if (existing?.isLiked == true) return@withContext null
        setLocalStoryLike(storyId, true, existing?.likedAt ?: 0L)
    }

    private suspend fun setLocalStoryLike(
        storyId: String,
        liked: Boolean,
        previousLikedAt: Long
    ): Pair<StoryLikeEntity, StoryLikeWirePayload> {
        val me = ensureLocalProfileInitialized()
        val now = maxOf(System.currentTimeMillis(), previousLikedAt + 1L)
        val wire = StoryLikeWirePayload(
            storyId = storyId,
            likerPeerId = me.peerId,
            likerEd25519PubKey = me.ed25519PubKey,
            isLiked = liked,
            likedAt = now,
            signature = ""
        )
        val sig = cryptoManager.signString(wire.canonicalSigningString(), localIdentity.ed25519PrivateKey)
        val signedWire = wire.copy(signature = sig)
        val entity = StoryLikeEntity(
            storyId = storyId,
            likerPeerId = me.peerId,
            likerEd25519PubKey = me.ed25519PubKey,
            isLiked = liked,
            likedAt = now,
            signature = sig
        )
        storyLikeDao.upsert(entity)
        val payloadBytes = json.encodeToString(signedWire).toByteArray(Charsets.UTF_8)
        packetDao.insertIfAbsent(
            MeshPacketEntity(
                packetId = "pkt_slike_${storyId}_${me.peerId}_$now",
                packetType = MeshProtocol.PacketType.STORY_LIKE.name,
                originPeerId = me.peerId,
                ttl = MeshProtocol.DEFAULT_TTL,
                hopCount = 0,
                payloadBytes = payloadBytes,
                createdAt = now,
                expiresAt = now + STORY_TTL_MS,
                byteSize = payloadBytes.size.toLong()
            )
        )
        return entity to signedWire
    }

    suspend fun toggleSavePost(postId: String) = withContext(Dispatchers.IO) {
        val post = postDao.getPost(postId) ?: return@withContext
        postDao.setSaved(postId, !post.isSaved)
    }

    suspend fun togglePinPost(postId: String) = withContext(Dispatchers.IO) {
        val post = postDao.getPost(postId) ?: return@withContext
        val newPinned = !post.isPinned
        postDao.setPinned(postId, newPinned)
        mediaBlobDao.setPinned(post.mediaId, newPinned)
        if (!post.audioMediaId.isNullOrEmpty()) {
            mediaBlobDao.setPinned(post.audioMediaId, newPinned)
        }
    }

    suspend fun togglePinStory(storyId: String) = withContext(Dispatchers.IO) {
        val story = storyDao.getStory(storyId) ?: return@withContext
        val newPinned = !story.isPinned
        storyDao.setPinned(storyId, newPinned)
        mediaBlobDao.setPinned(story.mediaId, newPinned)
        if (!story.audioMediaId.isNullOrEmpty()) {
            mediaBlobDao.setPinned(story.audioMediaId, newPinned)
        }
    }

    suspend fun markStoryViewed(storyId: String) = withContext(Dispatchers.IO) {
        storyDao.markViewed(storyId)
    }

    suspend fun toggleFollow(peerId: String) = withContext(Dispatchers.IO) {
        val p = profileDao.getProfile(peerId) ?: return@withContext
        val next = !p.isFollowing
        profileDao.setFollowing(peerId, next)
        cachedProfiles.computeIfPresent(peerId) { _, existing -> existing.copy(isFollowing = next) }
    }

    suspend fun toggleMute(peerId: String) = withContext(Dispatchers.IO) {
        val p = profileDao.getProfile(peerId) ?: return@withContext
        val next = !p.isMuted
        profileDao.setMuted(peerId, next)
        cachedProfiles.computeIfPresent(peerId) { _, existing -> existing.copy(isMuted = next) }
    }

    suspend fun togglePinProfile(peerId: String) = withContext(Dispatchers.IO) {
        val p = profileDao.getProfile(peerId) ?: return@withContext
        val next = !p.isPinned
        profileDao.setPinned(peerId, next)
        cachedProfiles.computeIfPresent(peerId) { _, existing -> existing.copy(isPinned = next) }
    }

    suspend fun buildLocalAnnouncePayload(): PeerAnnouncePayload {
        val me = ensureLocalProfileInitialized()
        return PeerAnnouncePayload(
            peerId = me.peerId,
            handle = me.handle,
            displayName = me.displayName,
            ed25519PubKey = me.ed25519PubKey,
            x25519PubKey = me.x25519PubKey,
            fingerprint = me.fingerprint,
            bio = me.bio,
            avatarMediaId = me.avatarMediaId,
            updatedAt = me.updatedAt,
            signature = me.signature
        )
    }

    suspend fun ingestRemoteProfile(payload: PeerAnnouncePayload): Boolean = withContext(Dispatchers.IO) {
        if (payload.peerId == localIdentity.peerId) return@withContext false
        val existing = profileDao.getProfile(payload.peerId)
        val merged = ProfileEntity(
            peerId = payload.peerId,
            ed25519PubKey = payload.ed25519PubKey,
            x25519PubKey = payload.x25519PubKey,
            handle = payload.handle,
            fingerprint = payload.fingerprint,
            displayName = payload.displayName,
            bio = payload.bio,
            avatarMediaId = payload.avatarMediaId,
            isLocalUser = false,
            isFollowing = existing?.isFollowing ?: false,
            isMuted = existing?.isMuted ?: false,
            isPinned = existing?.isPinned ?: false,
            dmDisappearAfterMs = existing?.dmDisappearAfterMs ?: 0L,
            updatedAt = payload.updatedAt,
            lastAccessedAt = System.currentTimeMillis(),
            signature = payload.signature
        )
        profileDao.upsert(merged)
        cachedProfiles[merged.peerId] = merged
        true
    }

    suspend fun ingestRemotePost(payload: PostWirePayload, hopCount: Int): Boolean = withContext(Dispatchers.IO) {
        val sigOk = cryptoManager.verifyString(
            message = payload.canonicalSigningString(),
            signatureBase64 = payload.signature,
            ed25519PublicKeyBase64 = payload.authorEd25519PubKey
        )
        if (!sigOk) return@withContext false

        val inserted = postDao.insertIfAbsent(
            PostEntity(
                postId = payload.postId,
                authorPeerId = payload.authorPeerId,
                caption = payload.caption,
                mediaId = payload.mediaId,
                mediaByteSize = payload.mediaByteSize,
                width = payload.width,
                height = payload.height,
                audioMediaId = payload.audioMediaId,
                audioDurationMs = payload.audioDurationMs,
                audioByteSize = payload.audioByteSize,
                pollQuestion = payload.pollQuestion,
                pollOptionsJson = payload.pollOptionsJson,
                createdAt = payload.createdAt,
                lastAccessedAt = System.currentTimeMillis(),
                hopCount = hopCount,
                signature = payload.signature,
                originatedLocally = false
            )
        )
        mediaStorageManager.initIncomingMedia(
            mediaId = payload.mediaId,
            ownerPeerId = payload.authorPeerId,
            totalBytes = payload.mediaByteSize,
            chunkSize = payload.mediaChunkSize,
            totalChunks = payload.mediaTotalChunks,
            sha256Hex = payload.mediaSha256Hex
        )
        if (!payload.audioMediaId.isNullOrEmpty() && payload.audioByteSize > 0L) {
            mediaStorageManager.initIncomingMedia(
                mediaId = payload.audioMediaId,
                ownerPeerId = payload.authorPeerId,
                totalBytes = payload.audioByteSize,
                chunkSize = payload.audioChunkSize,
                totalChunks = payload.audioTotalChunks,
                sha256Hex = payload.audioSha256Hex
            )
        }
        if (inserted != -1L) {
            syncPostLikeProjection(payload.postId)
        }
        retentionManager.enforceRetentionPolicy()
        inserted != -1L
    }

    suspend fun ingestRemoteStory(payload: StoryWirePayload, hopCount: Int): Boolean = withContext(Dispatchers.IO) {
        if (payload.expiresAt <= System.currentTimeMillis()) return@withContext false
        val sigOk = cryptoManager.verifyString(
            message = payload.canonicalSigningString(),
            signatureBase64 = payload.signature,
            ed25519PublicKeyBase64 = payload.authorEd25519PubKey
        )
        if (!sigOk) return@withContext false

        val inserted = storyDao.insertIfAbsent(
            StoryEntity(
                storyId = payload.storyId,
                authorPeerId = payload.authorPeerId,
                mediaId = payload.mediaId,
                mediaByteSize = payload.mediaByteSize,
                audioMediaId = payload.audioMediaId,
                audioDurationMs = payload.audioDurationMs,
                audioByteSize = payload.audioByteSize,
                caption = payload.caption,
                createdAt = payload.createdAt,
                expiresAt = payload.expiresAt,
                lastAccessedAt = System.currentTimeMillis(),
                hopCount = hopCount,
                signature = payload.signature,
                originatedLocally = false
            )
        )
        mediaStorageManager.initIncomingMedia(
            mediaId = payload.mediaId,
            ownerPeerId = payload.authorPeerId,
            totalBytes = payload.mediaByteSize,
            chunkSize = payload.mediaChunkSize,
            totalChunks = payload.mediaTotalChunks,
            sha256Hex = payload.mediaSha256Hex
        )
        if (!payload.audioMediaId.isNullOrEmpty() && payload.audioByteSize > 0L) {
            mediaStorageManager.initIncomingMedia(
                mediaId = payload.audioMediaId,
                ownerPeerId = payload.authorPeerId,
                totalBytes = payload.audioByteSize,
                chunkSize = payload.audioChunkSize,
                totalChunks = payload.audioTotalChunks,
                sha256Hex = payload.audioSha256Hex
            )
        }
        retentionManager.enforceRetentionPolicy()
        inserted != -1L
    }

    suspend fun ingestRemoteNote(payload: NoteWirePayload, hopCount: Int): Boolean = withContext(Dispatchers.IO) {
        val sigOk = cryptoManager.verifyString(
            message = payload.canonicalSigningString(),
            signatureBase64 = payload.signature,
            ed25519PublicKeyBase64 = payload.authorEd25519PubKey
        )
        if (!sigOk) return@withContext false

        val now = System.currentTimeMillis()
        val existing = noteDao.getAuthorNote(payload.authorPeerId)
        if (existing != null && existing.createdAt >= payload.createdAt) {
            return@withContext false
        }
        if (payload.text.isBlank() || payload.expiresAt <= now) {
            if (existing != null) {
                noteDao.deleteByAuthor(payload.authorPeerId)
                return@withContext true
            }
            return@withContext false
        }

        noteDao.deleteByAuthor(payload.authorPeerId)
        noteDao.upsert(
            NoteEntity(
                noteId = payload.noteId,
                authorPeerId = payload.authorPeerId,
                text = payload.text.take(NOTE_MAX_CHARS),
                moodEmoji = payload.moodEmoji.take(8),
                createdAt = payload.createdAt,
                expiresAt = payload.expiresAt,
                hopCount = hopCount,
                signature = payload.signature,
                originatedLocally = payload.authorPeerId == localIdentity.peerId
            )
        )
        true
    }

    suspend fun ingestRemotePostReaction(payload: PostReactionWirePayload): Boolean = withContext(Dispatchers.IO) {
        val sigOk = cryptoManager.verifyString(
            message = payload.canonicalSigningString(),
            signatureBase64 = payload.signature,
            ed25519PublicKeyBase64 = payload.reactorEd25519PubKey
        )
        if (!sigOk) return@withContext false

        val existing = postReactionDao.getReaction(payload.postId, payload.reactorPeerId)
        if (existing != null && existing.reactedAt >= payload.reactedAt) {
            return@withContext false
        }
        postReactionDao.upsert(
            PostReactionEntity(
                postId = payload.postId,
                reactorPeerId = payload.reactorPeerId,
                emoji = payload.emoji.take(8),
                reactedAt = payload.reactedAt,
                signature = payload.signature,
                reactorEd25519PubKey = payload.reactorEd25519PubKey
            )
        )
        true
    }

    suspend fun ingestRemotePostLike(payload: PostLikeWirePayload): Boolean = withContext(Dispatchers.IO) {
        val sigOk = cryptoManager.verifyString(
            message = payload.canonicalSigningString(),
            signatureBase64 = payload.signature,
            ed25519PublicKeyBase64 = payload.likerEd25519PubKey
        )
        if (!sigOk) return@withContext false

        val existing = postLikeDao.getLike(payload.postId, payload.likerPeerId)
        if (existing != null && existing.likedAt >= payload.likedAt) {
            return@withContext false
        }
        postLikeDao.upsert(
            PostLikeEntity(
                postId = payload.postId,
                likerPeerId = payload.likerPeerId,
                likerEd25519PubKey = payload.likerEd25519PubKey,
                isLiked = payload.isLiked,
                likedAt = payload.likedAt,
                signature = payload.signature
            )
        )
        syncPostLikeProjection(payload.postId)
        true
    }

    suspend fun ingestRemoteStoryLike(payload: StoryLikeWirePayload): Boolean = withContext(Dispatchers.IO) {
        val sigOk = cryptoManager.verifyString(
            message = payload.canonicalSigningString(),
            signatureBase64 = payload.signature,
            ed25519PublicKeyBase64 = payload.likerEd25519PubKey
        )
        if (!sigOk) return@withContext false

        val existing = storyLikeDao.getLike(payload.storyId, payload.likerPeerId)
        if (existing != null && existing.likedAt >= payload.likedAt) {
            return@withContext false
        }
        storyLikeDao.upsert(
            StoryLikeEntity(
                storyId = payload.storyId,
                likerPeerId = payload.likerPeerId,
                likerEd25519PubKey = payload.likerEd25519PubKey,
                isLiked = payload.isLiked,
                likedAt = payload.likedAt,
                signature = payload.signature
            )
        )
        true
    }

    suspend fun ingestRemotePollVote(payload: PollVoteWirePayload): Boolean = withContext(Dispatchers.IO) {
        val sigOk = cryptoManager.verifyString(
            message = payload.canonicalSigningString(),
            signatureBase64 = payload.signature,
            ed25519PublicKeyBase64 = payload.voterEd25519PubKey
        )
        if (!sigOk) return@withContext false

        val existing = pollVoteDao.getVote(payload.postId, payload.voterPeerId)
        if (existing != null && existing.votedAt >= payload.votedAt) {
            return@withContext false
        }
        pollVoteDao.upsert(
            PollVoteEntity(
                postId = payload.postId,
                voterPeerId = payload.voterPeerId,
                optionIndex = payload.optionIndex.coerceIn(0, 3),
                votedAt = payload.votedAt,
                signature = payload.signature
            )
        )
        true
    }

    suspend fun ingestRemoteMediaChunk(payload: MediaChunkWirePayload): Boolean {
        val bytes = CryptoManager.decodeBase64(payload.dataBase64)
        return ingestRemoteMediaChunkRaw(
            mediaId = payload.mediaId,
            ownerPeerId = payload.ownerPeerId,
            chunkIndex = payload.chunkIndex,
            totalChunks = payload.totalChunks,
            chunkSize = payload.chunkSize,
            totalBytes = payload.totalBytes,
            sha256Hex = payload.sha256Hex,
            chunkBytes = bytes
        )
    }

    suspend fun ingestRemoteMediaChunkRaw(
        mediaId: String,
        ownerPeerId: String,
        chunkIndex: Int,
        totalChunks: Int,
        chunkSize: Int,
        totalBytes: Long,
        sha256Hex: String,
        chunkBytes: ByteArray
    ): Boolean {
        return mediaStorageManager.writeIncomingChunk(
            mediaId = mediaId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            chunkBytes = chunkBytes,
            expectedChunkSize = chunkSize,
            totalBytes = totalBytes,
            expectedSha256Hex = sha256Hex,
            ownerPeerId = ownerPeerId
        )
    }

    suspend fun ingestRemoteDm(payload: DmWirePayload, hopCount: Int): DmAckPayload? = withContext(Dispatchers.IO) {
        if (payload.recipientPeerId != localIdentity.peerId) return@withContext null

        val encrypted = json.decodeFromString<EncryptedDmPayload>(payload.encryptedPayloadJson)
        val senderEdPub = CryptoManager.decodeBase64(payload.senderEd25519PubKey)
        val rawPlaintext = cryptoManager.decryptDirectMessage(
            payload = encrypted,
            senderEd25519PubKey = senderEdPub,
            recipientIdentity = localIdentity
        ) ?: return@withContext null

        val senderProfile = cachedProfiles[payload.senderPeerId]
            ?: profileDao.getProfile(payload.senderPeerId)?.also { cachedProfiles[payload.senderPeerId] = it }
        val isMuted = senderProfile?.isMuted == true
        val senderName = senderProfile?.displayName?.takeIf { it.isNotBlank() }
            ?: payload.senderPeerId.take(8).uppercase()
        val senderHandle = senderProfile?.handle ?: "@${payload.senderPeerId.take(6)}"

        val rich = DmRichCodec.decode(rawPlaintext)
        val targetMsgId = rich.reactionToMessageId
        if (!targetMsgId.isNullOrEmpty()) {
            val targetMsg = dmDao.getMessage(targetMsgId)
            if (targetMsg != null) {
                val map = parseDmReactions(targetMsg.reactionsJson).toMutableMap()
                val emoji = rich.reactionEmoji.orEmpty()
                val previousEmoji = map[payload.senderPeerId]
                if (emoji.isEmpty()) {
                    map.remove(payload.senderPeerId)
                } else {
                    map[payload.senderPeerId] = emoji
                }
                dmDao.updateReactionsJson(targetMsgId, json.encodeToString(map))
                if (!isMuted && emoji.isNotEmpty() && previousEmoji != emoji) {
                    _incomingDmEvents.tryEmit(
                        IncomingDmEvent(
                            senderPeerId = payload.senderPeerId,
                            senderDisplayName = senderName,
                            senderHandle = senderHandle,
                            messageId = payload.messageId,
                            previewText = "Reacted $emoji to your message",
                            timestamp = payload.timestamp,
                            isReaction = true
                        )
                    )
                }
            }
        } else {
            val now = System.currentTimeMillis()
            val expiresAt = if (rich.disappearAfterMs > 0L) now + rich.disappearAfterMs else 0L
            val insertedRowId = dmDao.insertIfAbsent(
                DirectMessageEntity(
                    messageId = payload.messageId,
                    conversationPeerId = payload.senderPeerId,
                    senderPeerId = payload.senderPeerId,
                    recipientPeerId = payload.recipientPeerId,
                    plaintextContent = rich.text,
                    encryptedPayloadJson = payload.encryptedPayloadJson,
                    timestamp = payload.timestamp,
                    hopCount = hopCount,
                    deliveryState = DeliveryState.DELIVERED,
                    isIncoming = true,
                    replyToMessageId = rich.replyToMessageId,
                    replyToPreview = rich.replyToPreview,
                    expiresAt = expiresAt
                )
            )
            if (insertedRowId != -1L && !isMuted && rich.text.isNotBlank()) {
                _incomingDmEvents.tryEmit(
                    IncomingDmEvent(
                        senderPeerId = payload.senderPeerId,
                        senderDisplayName = senderName,
                        senderHandle = senderHandle,
                        messageId = payload.messageId,
                        previewText = rich.text,
                        timestamp = payload.timestamp,
                        isReaction = false
                    )
                )
            }
        }
        DmAckPayload(
            messageId = payload.messageId,
            senderPeerId = payload.senderPeerId,
            recipientPeerId = payload.recipientPeerId,
            hopCount = hopCount,
            deliveredAt = System.currentTimeMillis()
        )
    }

    suspend fun handleDmAck(ack: DmAckPayload) = withContext(Dispatchers.IO) {
        if (ack.senderPeerId == localIdentity.peerId) {
            dmDao.updateDeliveryState(ack.messageId, DeliveryState.DELIVERED, ack.hopCount)
        }
    }

    suspend fun markDmRelayed(messageId: String, hopCount: Int) = withContext(Dispatchers.IO) {
        val existing = dmDao.getMessage(messageId)
        if (existing != null && existing.deliveryState == DeliveryState.QUEUED) {
            dmDao.updateDeliveryState(messageId, DeliveryState.RELAYED, hopCount)
        }
    }

    private fun parseDmReactions(reactionsJson: String): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(reactionsJson) }
            .getOrDefault(emptyMap())

    companion object {
        const val STORY_TTL_MS = 24L * 60L * 60L * 1000L
        const val NOTE_MAX_CHARS = 60
    }
}
