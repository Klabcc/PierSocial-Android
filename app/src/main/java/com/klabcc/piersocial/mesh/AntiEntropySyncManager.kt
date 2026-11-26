package com.klabcc.piersocial.mesh

import com.klabcc.piersocial.data.local.MeshPacketEntity
import com.klabcc.piersocial.data.repository.PierRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Implements Bitchat-style gossip store-and-forward + anti-entropy set reconciliation
 * with inter-node link probing, optimal source selection, and parallel multi-peer
 * BLE chunk transfers.
 */
class AntiEntropySyncManager(
    private val repository: PierRepository,
    private val onSendFrameToPeer: (peerAddress: String, frame: MeshProtocol.MeshFrame) -> Unit,
    private val onBroadcastFrame: (excludeAddress: String?, frame: MeshProtocol.MeshFrame) -> Unit,
    private val onPeerIdentified: (peerAddress: String, peerId: String, displayName: String) -> Unit,
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit,
    private val onPacketRelayed: () -> Unit,
    private val onGetLinkSnapshot: (peerAddress: String) -> LinkTransportSnapshot? = { null },
    private val onGetTotalQueuedSlices: () -> Int = { 0 },
    private val onGetConnectedCount: () -> Int = { 0 },
    private val onIsLocal2MPhySupported: () -> Boolean = { false },
    private val onWifiDirectOfferReceived: (sourceAddress: String, offer: WifiDirectOfferPayload) -> Unit = { _, _ -> },
    private val onLinkScoresUpdated: (
        metrics: Map<String, PeerLinkMetrics>,
        scores: Map<String, Double>,
        optimalAddresses: Set<String>
    ) -> Unit = { _, _, _ -> }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    val deduplicator = PacketDeduplicator()
    val peerSelector = OptimalPeerSelector()

    private val activeUploadJobs = AtomicInteger(0)
    private val aggregateThroughputBps = AtomicLong(0L)
    private val lastVectorSentAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var parallelPullJob: Job? = null
    private var vectorBroadcastJob: Job? = null
    private val pullLock = Any()

    fun shutdown() {
        scope.cancel()
    }

    fun onPeerDisconnected(peerAddress: String) {
        lastVectorSentAtMs.remove(peerAddress)
        peerSelector.onPeerDisconnected(peerAddress)
        publishUpdatedScores()
    }

    fun initiateHandshakeAndSync(peerAddress: String) {
        lastVectorSentAtMs[peerAddress] = System.currentTimeMillis()
        scope.launch {
            syncLocalLinkSnapshot(peerAddress)
            val local = repository.localIdentity
            val announce = repository.buildLocalAnnouncePayload()
            val announceFrame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.ANNOUNCE,
                packetId = "ann_${local.peerId}_${UUID.randomUUID().toString().take(8)}",
                originPeerId = local.peerId,
                payload = json.encodeToString(announce).toByteArray(Charsets.UTF_8)
            )
            onSendFrameToPeer(peerAddress, announceFrame)

            sendLinkProbeInternal(peerAddress)

            val vector = buildSyncVector(peerAddress)
            val vectorFrame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.SYNC_VECTOR,
                packetId = "sv_${UUID.randomUUID().toString().take(12)}",
                originPeerId = local.peerId,
                payload = json.encodeToString(vector).toByteArray(Charsets.UTF_8)
            )
            onSendFrameToPeer(peerAddress, vectorFrame)
            onLog("SYNC", "Sent ANNOUNCE + LINK_PROBE + SYNC_VECTOR to $peerAddress", false)
        }
    }

    fun sendLinkProbe(peerAddress: String) {
        scope.launch {
            sendLinkProbeInternal(peerAddress)
        }
    }

    fun triggerParallelPullCheck() {
        scheduleParallelMediaPull(delayMs = 50L)
    }

    private fun syncLocalLinkSnapshot(peerAddress: String) {
        val snap = onGetLinkSnapshot(peerAddress) ?: return
        peerSelector.updateLinkMetrics(peerAddress) { current ->
            current.copy(
                role = snap.role,
                negotiatedMtu = snap.negotiatedMtu,
                rssiDbm = snap.rssiDbm,
                supports2MPhy = snap.supports2MPhy,
                transportType = snap.transportType
            )
        }
    }

    private fun sendLinkProbeInternal(peerAddress: String) {
        syncLocalLinkSnapshot(peerAddress)
        val snap = onGetLinkSnapshot(peerAddress)
        val probe = LinkProbePayload(
            probeId = "prb_${UUID.randomUUID().toString().take(10)}",
            senderPeerId = repository.localIdentity.peerId,
            sentAtMs = System.currentTimeMillis(),
            activeUploads = activeUploadJobs.get(),
            queuedSlices = onGetTotalQueuedSlices(),
            connectedPeers = onGetConnectedCount(),
            negotiatedMtu = snap?.negotiatedMtu ?: MeshProtocol.DEFAULT_MTU,
            supports2MPhy = snap?.supports2MPhy ?: onIsLocal2MPhySupported(),
            measuredThroughputBps = aggregateThroughputBps.get()
        )
        onSendFrameToPeer(
            peerAddress,
            MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.LINK_PROBE,
                packetId = probe.probeId,
                originPeerId = repository.localIdentity.peerId,
                payload = json.encodeToString(probe).toByteArray(Charsets.UTF_8)
            )
        )
    }

    suspend fun buildSyncVector(forPeerAddress: String? = null): SyncVectorPayload {
        val db = repository.database
        val now = System.currentTimeMillis()
        val snap = forPeerAddress?.let { onGetLinkSnapshot(it) }
        val notes = db.noteDao().getActiveNoteIds(now)
        val reactionKeys = db.postReactionDao().getAllReactions()
            .map { "${it.postId}|${it.reactorPeerId}|${it.reactedAt}" }
        val pollVoteKeys = db.pollVoteDao().getAllVotes()
            .map { "${it.postId}|${it.voterPeerId}|${it.votedAt}" }
        val postLikeKeys = db.postLikeDao().getAllLikes()
            .map { "${it.postId}|${it.likerPeerId}|${it.likedAt}" }
        val storyLikeKeys = db.storyLikeDao().getAllLikes()
            .map { "${it.storyId}|${it.likerPeerId}|${it.likedAt}" }
        return SyncVectorPayload(
            senderPeerId = repository.localIdentity.peerId,
            profileIds = db.profileDao().getAllProfileIds(),
            postIds = db.postDao().getAllPostIds(),
            storyIds = db.storyDao().getActiveStoryIds(now),
            completeMediaIds = db.mediaBlobDao().getCompleteMediaIds(),
            pendingDmPacketIds = db.meshPacketDao().getRelayablePackets(now)
                .filter { it.packetType == MeshProtocol.PacketType.DM_PACKET.name }
                .map { it.packetId },
            activeOutboundJobs = activeUploadJobs.get(),
            queuedOutboundSlices = onGetTotalQueuedSlices(),
            connectedPeerCount = onGetConnectedCount(),
            negotiatedMtu = snap?.negotiatedMtu ?: MeshProtocol.TARGET_MTU,
            measuredGoodputBps = aggregateThroughputBps.get(),
            supports2MPhy = snap?.supports2MPhy ?: onIsLocal2MPhySupported(),
            noteIds = notes,
            reactionKeys = reactionKeys,
            pollVoteKeys = pollVoteKeys,
            postLikeKeys = postLikeKeys,
            storyLikeKeys = storyLikeKeys
        )
    }

    fun handleIncomingFrame(
        sourceAddress: String,
        negotiatedMtu: Int,
        frame: MeshProtocol.MeshFrame
    ) {
        val isNewPacket = deduplicator.markSeenIfNew(frame.packetId)
        if (!isNewPacket && !frame.type.isBulkData) {
            return
        }

        scope.launch {
            if (!frame.type.isExpressDm) {
                syncLocalLinkSnapshot(sourceAddress)
            }
            when (frame.type) {
                MeshProtocol.PacketType.ANNOUNCE -> {
                    val announce = runCatching {
                        json.decodeFromString<PeerAnnouncePayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    repository.ingestRemoteProfile(announce)
                    peerSelector.updateLinkMetrics(sourceAddress) {
                        it.copy(peerId = announce.peerId, negotiatedMtu = maxOf(it.negotiatedMtu, negotiatedMtu))
                    }
                    publishUpdatedScores()
                    onPeerIdentified(sourceAddress, announce.peerId, "${announce.handle} (${announce.displayName})")
                    onLog("MESH", "Peer announced: ${announce.handle} (${announce.displayName})", false)
                }

                MeshProtocol.PacketType.LINK_PROBE -> {
                    val probe = runCatching {
                        json.decodeFromString<LinkProbePayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    peerSelector.updateLinkMetrics(sourceAddress) { current ->
                        current.copy(
                            peerId = probe.senderPeerId,
                            negotiatedMtu = minOf(maxOf(current.negotiatedMtu, negotiatedMtu), probe.negotiatedMtu.coerceAtLeast(negotiatedMtu)),
                            supports2MPhy = current.supports2MPhy || probe.supports2MPhy,
                            remoteActiveUploads = probe.activeUploads,
                            remoteQueuedSlices = probe.queuedSlices,
                            remoteConnectedPeers = probe.connectedPeers,
                            advertisedGoodputBps = probe.measuredThroughputBps
                        )
                    }
                    val snap = onGetLinkSnapshot(sourceAddress)
                    val ack = LinkProbeAckPayload(
                        probeId = probe.probeId,
                        responderPeerId = repository.localIdentity.peerId,
                        echoSentAtMs = probe.sentAtMs,
                        activeUploads = activeUploadJobs.get(),
                        queuedSlices = onGetTotalQueuedSlices(),
                        connectedPeers = onGetConnectedCount(),
                        negotiatedMtu = snap?.negotiatedMtu ?: negotiatedMtu,
                        supports2MPhy = snap?.supports2MPhy ?: onIsLocal2MPhySupported(),
                        measuredThroughputBps = aggregateThroughputBps.get()
                    )
                    onSendFrameToPeer(
                        sourceAddress,
                        MeshProtocol.MeshFrame(
                            type = MeshProtocol.PacketType.LINK_PROBE_ACK,
                            packetId = "prba_${probe.probeId}_${repository.localIdentity.peerId.take(6)}",
                            originPeerId = repository.localIdentity.peerId,
                            targetPeerId = probe.senderPeerId,
                            payload = json.encodeToString(ack).toByteArray(Charsets.UTF_8)
                        )
                    )
                    publishUpdatedScores()
                }

                MeshProtocol.PacketType.LINK_PROBE_ACK -> {
                    val ack = runCatching {
                        json.decodeFromString<LinkProbeAckPayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    val rttMs = (System.currentTimeMillis() - ack.echoSentAtMs).coerceIn(1L, 10_000L)
                    peerSelector.updateLinkMetrics(sourceAddress) { current ->
                        val blendedRtt = if (current.rttMs <= 0L) rttMs else ((current.rttMs * 6L + rttMs * 4L) / 10L)
                        current.copy(
                            peerId = ack.responderPeerId,
                            rttMs = blendedRtt,
                            negotiatedMtu = maxOf(current.negotiatedMtu, minOf(negotiatedMtu, ack.negotiatedMtu)),
                            supports2MPhy = current.supports2MPhy || ack.supports2MPhy,
                            remoteActiveUploads = ack.activeUploads,
                            remoteQueuedSlices = ack.queuedSlices,
                            remoteConnectedPeers = ack.connectedPeers,
                            advertisedGoodputBps = ack.measuredThroughputBps,
                            localFailureCount = (current.localFailureCount - 1).coerceAtLeast(0)
                        )
                    }
                    val updated = peerSelector.getLinkMetrics(sourceAddress)
                    val score = updated?.let { peerSelector.computeOptimalityScore(it) } ?: 0.0
                    onLog(
                        "PROBE",
                        "${ack.responderPeerId.take(8)} RTT=${rttMs}ms MTU=${updated?.negotiatedMtu ?: negotiatedMtu} " +
                            "2M=${updated?.supports2MPhy == true} load=${ack.activeUploads}/${ack.queuedSlices} score=${score.toInt()}",
                        false
                    )
                    publishUpdatedScores()
                }

                MeshProtocol.PacketType.SYNC_VECTOR -> {
                    val remote = runCatching {
                        json.decodeFromString<SyncVectorPayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    handleSyncVector(sourceAddress, negotiatedMtu, remote)
                }

                MeshProtocol.PacketType.SYNC_REQUEST -> {
                    val req = runCatching {
                        json.decodeFromString<SyncRequestPayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    handleSyncRequest(sourceAddress, negotiatedMtu, req)
                }

                MeshProtocol.PacketType.PROFILE_RECORD -> {
                    val announce = runCatching {
                        json.decodeFromString<PeerAnnouncePayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    if (repository.ingestRemoteProfile(announce)) {
                        onLog("REPLICATE", "Profile synced: ${announce.handle}", false)
                        relayIfTtlRemains(sourceAddress, frame)
                    }
                }

                MeshProtocol.PacketType.POST_RECORD -> {
                    val post = runCatching {
                        json.decodeFromString<PostWirePayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    val isNew = repository.ingestRemotePost(post, frame.hopCount)
                    if (isNew) {
                        storeGossipPacket(frame)
                        onLog("GOSSIP", "Post ${post.postId.take(8)} (${frame.hopCount} hops)", false)
                        relayIfTtlRemains(sourceAddress, frame)
                        scheduleParallelMediaPull(delayMs = 120L)
                    }
                }

                MeshProtocol.PacketType.STORY_RECORD -> {
                    val story = runCatching {
                        json.decodeFromString<StoryWirePayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    val isNew = repository.ingestRemoteStory(story, frame.hopCount)
                    if (isNew) {
                        storeGossipPacket(frame)
                        onLog("GOSSIP", "Story ${story.storyId.take(8)} (${frame.hopCount} hops)", false)
                        relayIfTtlRemains(sourceAddress, frame)
                        scheduleParallelMediaPull(delayMs = 120L)
                    }
                }

                MeshProtocol.PacketType.MEDIA_CHUNK -> {
                    val chunk = runCatching {
                        json.decodeFromString<MediaChunkWirePayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    val rawBytes = com.klabcc.piersocial.crypto.CryptoManager.decodeBase64(chunk.dataBase64)
                    handleReceivedMediaChunk(
                        sourceAddress = sourceAddress,
                        frame = frame,
                        isNewPacket = isNewPacket,
                        mediaId = chunk.mediaId,
                        ownerPeerId = chunk.ownerPeerId,
                        chunkIndex = chunk.chunkIndex,
                        totalChunks = chunk.totalChunks,
                        chunkSize = chunk.chunkSize,
                        totalBytes = chunk.totalBytes,
                        sha256Hex = chunk.sha256Hex,
                        rawBytes = rawBytes
                    )
                }

                MeshProtocol.PacketType.MEDIA_CHUNK_BIN -> {
                    val chunk = MediaChunkBinaryCodec.decode(frame.payload) ?: return@launch
                    handleReceivedMediaChunk(
                        sourceAddress = sourceAddress,
                        frame = frame,
                        isNewPacket = isNewPacket,
                        mediaId = chunk.mediaId,
                        ownerPeerId = chunk.ownerPeerId,
                        chunkIndex = chunk.chunkIndex,
                        totalChunks = chunk.totalChunks,
                        chunkSize = chunk.chunkSize,
                        totalBytes = chunk.totalBytes,
                        sha256Hex = chunk.sha256Hex,
                        rawBytes = chunk.data
                    )
                }

                MeshProtocol.PacketType.DM_PACKET -> {
                    val dm = runCatching {
                        json.decodeFromString<DmWirePayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    handleDecodedDm(sourceAddress, frame, dm)
                }

                MeshProtocol.PacketType.DM_PACKET_BIN -> {
                    val dm = DmBinaryCodec.decode(frame.payload) ?: return@launch
                    handleDecodedDm(sourceAddress, frame, dm)
                }

                MeshProtocol.PacketType.DM_ACK -> {
                    val ack = runCatching {
                        json.decodeFromString<DmAckPayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    handleDecodedDmAck(sourceAddress, frame, ack)
                }

                MeshProtocol.PacketType.DM_ACK_BIN -> {
                    val ack = DmAckBinaryCodec.decode(frame.payload) ?: return@launch
                    handleDecodedDmAck(sourceAddress, frame, ack)
                }

                MeshProtocol.PacketType.WIFI_DIRECT_OFFER -> {
                    val offer = runCatching {
                        json.decodeFromString<WifiDirectOfferPayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    peerSelector.updateLinkMetrics(sourceAddress) {
                        it.copy(peerId = offer.senderPeerId, negotiatedMtu = maxOf(it.negotiatedMtu, negotiatedMtu))
                    }
                    onWifiDirectOfferReceived(sourceAddress, offer)
                }

                MeshProtocol.PacketType.NOTE_RECORD -> {
                    val note = runCatching {
                        json.decodeFromString<NoteWirePayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    if (repository.ingestRemoteNote(note, frame.hopCount)) {
                        storeGossipPacket(frame)
                        onLog("GOSSIP", "Note ${note.noteId.take(8)} (${frame.hopCount} hops)", false)
                        relayIfTtlRemains(sourceAddress, frame)
                    }
                }

                MeshProtocol.PacketType.POST_REACTION -> {
                    val reaction = runCatching {
                        json.decodeFromString<PostReactionWirePayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    if (repository.ingestRemotePostReaction(reaction)) {
                        storeGossipPacket(frame)
                        relayIfTtlRemains(sourceAddress, frame)
                    }
                }

                MeshProtocol.PacketType.POLL_VOTE -> {
                    val vote = runCatching {
                        json.decodeFromString<PollVoteWirePayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    if (repository.ingestRemotePollVote(vote)) {
                        storeGossipPacket(frame)
                        relayIfTtlRemains(sourceAddress, frame)
                    }
                }

                MeshProtocol.PacketType.POST_LIKE -> {
                    val like = runCatching {
                        json.decodeFromString<PostLikeWirePayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    if (repository.ingestRemotePostLike(like)) {
                        storeGossipPacket(frame)
                        relayIfTtlRemains(sourceAddress, frame)
                    }
                }

                MeshProtocol.PacketType.STORY_LIKE -> {
                    val like = runCatching {
                        json.decodeFromString<StoryLikeWirePayload>(String(frame.payload, Charsets.UTF_8))
                    }.getOrNull() ?: return@launch
                    if (repository.ingestRemoteStoryLike(like)) {
                        storeGossipPacket(frame)
                        relayIfTtlRemains(sourceAddress, frame)
                    }
                }
            }
        }
    }

    private suspend fun handleDecodedDm(
        sourceAddress: String,
        frame: MeshProtocol.MeshFrame,
        dm: DmWirePayload
    ) {
        val localId = repository.localIdentity.peerId
        if (dm.recipientPeerId == localId) {
            val ack = repository.ingestRemoteDm(dm, frame.hopCount)
            if (ack != null) {
                onLog("E2EE DM", "Decrypted DM from ${dm.senderPeerId.take(8)} (${frame.hopCount} hops)", false)
                val ackFrame = MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.DM_ACK_BIN,
                    packetId = "ack_${dm.messageId}",
                    originPeerId = localId,
                    targetPeerId = dm.senderPeerId,
                    payload = DmAckBinaryCodec.encode(ack)
                )
                // Reply directly to source link first for lowest possible ACK latency
                onSendFrameToPeer(sourceAddress, ackFrame)
                if (frame.hopCount > 0) {
                    onBroadcastFrame(sourceAddress, ackFrame)
                }
            }
        } else {
            storeGossipPacket(frame)
            onLog("RELAY", "Relaying encrypted DM ${dm.messageId.take(8)} (TTL=${frame.ttl - 1})", false)
            relayIfTtlRemains(sourceAddress, frame)
        }
    }

    private suspend fun handleDecodedDmAck(
        sourceAddress: String,
        frame: MeshProtocol.MeshFrame,
        ack: DmAckPayload
    ) {
        if (ack.senderPeerId == repository.localIdentity.peerId) {
            repository.handleDmAck(ack)
            onLog("ACK", "DM ${ack.messageId.take(8)} delivered (${ack.hopCount} hops)", false)
        } else {
            relayIfTtlRemains(sourceAddress, frame)
        }
    }

    private suspend fun handleReceivedMediaChunk(
        sourceAddress: String,
        frame: MeshProtocol.MeshFrame,
        isNewPacket: Boolean,
        mediaId: String,
        ownerPeerId: String,
        chunkIndex: Int,
        totalChunks: Int,
        chunkSize: Int,
        totalBytes: Long,
        sha256Hex: String,
        rawBytes: ByteArray
    ) {
        val now = System.currentTimeMillis()
        peerSelector.recordBytesReceived(sourceAddress, rawBytes.size, now)
        peerSelector.onChunkReceived(mediaId, chunkIndex)
        peerSelector.getLinkMetrics(sourceAddress)?.measuredGoodputBps?.takeIf { it > 0L }?.let { bps ->
            aggregateThroughputBps.set(bps)
        }

        val completed = repository.ingestRemoteMediaChunkRaw(
            mediaId = mediaId,
            ownerPeerId = ownerPeerId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            totalBytes = totalBytes,
            sha256Hex = sha256Hex,
            chunkBytes = rawBytes
        )
        if (completed) {
            peerSelector.onMediaCompleted(mediaId)
            onLog("CHUNK", "Media ${mediaId.take(8)} completed ($totalChunks/$totalChunks) [SHA-256 VERIFIED]", false)
            scheduleBroadcastSyncVector()
            scheduleParallelMediaPull(delayMs = 50L, forceReschedule = true)
        } else {
            val isWifiDirect = peerSelector.getLinkMetrics(sourceAddress)?.transportType == MeshProtocol.TransportProtocol.WIFI_DIRECT
            val logStride = if (isWifiDirect) 32 else 8
            if (chunkIndex % logStride == 0 || chunkIndex + 1 == totalChunks) {
                onLog("CHUNK", "Media ${mediaId.take(8)} chunk ${chunkIndex + 1}/$totalChunks via $sourceAddress", false)
            }
            scheduleParallelMediaPull(delayMs = if (isWifiDirect) 25L else 180L, forceReschedule = false)
        }

        if (isNewPacket && frame.targetPeerId.isEmpty()) {
            relayIfTtlRemains(sourceAddress, frame)
        }
        publishUpdatedScores()
    }

    private suspend fun handleSyncVector(
        sourceAddress: String,
        negotiatedMtu: Int,
        remote: SyncVectorPayload
    ) {
        val snap = onGetLinkSnapshot(sourceAddress)
        peerSelector.updateLinkMetrics(sourceAddress) { current ->
            current.copy(
                peerId = remote.senderPeerId,
                negotiatedMtu = maxOf(current.negotiatedMtu, minOf(negotiatedMtu, remote.negotiatedMtu.coerceAtLeast(negotiatedMtu))),
                supports2MPhy = current.supports2MPhy || remote.supports2MPhy,
                remoteActiveUploads = remote.activeOutboundJobs,
                remoteQueuedSlices = remote.queuedOutboundSlices,
                remoteConnectedPeers = remote.connectedPeerCount,
                advertisedGoodputBps = maxOf(current.advertisedGoodputBps, remote.measuredGoodputBps),
                transportType = snap?.transportType ?: current.transportType
            )
        }
        peerSelector.updatePeerMediaInventory(sourceAddress, remote.completeMediaIds)
        publishUpdatedScores()

        val db = repository.database
        val now = System.currentTimeMillis()
        val localProfileIds = db.profileDao().getAllProfileIds().toSet()
        val localPostIds = db.postDao().getAllPostIds().toSet()
        val localStoryIds = db.storyDao().getActiveStoryIds(now).toSet()
        val localPackets = db.meshPacketDao().getAllPacketIds().toSet()
        val localNoteIds = db.noteDao().getActiveNoteIds(now).toSet()
        val localReactionsByPair = db.postReactionDao().getAllReactions()
            .associate { "${it.postId}|${it.reactorPeerId}" to it.reactedAt }
        val localVotesByPair = db.pollVoteDao().getAllVotes()
            .associate { "${it.postId}|${it.voterPeerId}" to it.votedAt }
        val localPostLikesByPair = db.postLikeDao().getAllLikes()
            .associate { "${it.postId}|${it.likerPeerId}" to it.likedAt }
        val localStoryLikesByPair = db.storyLikeDao().getAllLikes()
            .associate { "${it.storyId}|${it.likerPeerId}" to it.likedAt }

        val wantedProfiles = remote.profileIds.filterNot { it in localProfileIds }
        val wantedPosts = remote.postIds.filterNot { it in localPostIds }
        val wantedStories = remote.storyIds.filterNot { it in localStoryIds }
        val wantedDms = remote.pendingDmPacketIds.filterNot { it in localPackets }
        val wantedNotes = remote.noteIds.filterNot { it in localNoteIds }
        val wantedReactions = remote.reactionKeys.filter { key ->
            val parts = key.split('|')
            if (parts.size < 3) return@filter false
            val ts = parts[2].toLongOrNull() ?: return@filter false
            val localTs = localReactionsByPair["${parts[0]}|${parts[1]}"] ?: -1L
            ts > localTs
        }
        val wantedVotes = remote.pollVoteKeys.filter { key ->
            val parts = key.split('|')
            if (parts.size < 3) return@filter false
            val ts = parts[2].toLongOrNull() ?: return@filter false
            val localTs = localVotesByPair["${parts[0]}|${parts[1]}"] ?: -1L
            ts > localTs
        }
        val wantedPostLikes = remote.postLikeKeys.filter { key ->
            val parts = key.split('|')
            if (parts.size < 3) return@filter false
            val ts = parts[2].toLongOrNull() ?: return@filter false
            val localTs = localPostLikesByPair["${parts[0]}|${parts[1]}"] ?: -1L
            ts > localTs
        }
        val wantedStoryLikes = remote.storyLikeKeys.filter { key ->
            val parts = key.split('|')
            if (parts.size < 3) return@filter false
            val ts = parts[2].toLongOrNull() ?: return@filter false
            val localTs = localStoryLikesByPair["${parts[0]}|${parts[1]}"] ?: -1L
            ts > localTs
        }

        if (wantedProfiles.isNotEmpty() || wantedPosts.isNotEmpty() ||
            wantedStories.isNotEmpty() || wantedDms.isNotEmpty() ||
            wantedNotes.isNotEmpty() || wantedReactions.isNotEmpty() ||
            wantedVotes.isNotEmpty() || wantedPostLikes.isNotEmpty() ||
            wantedStoryLikes.isNotEmpty()
        ) {
            val req = SyncRequestPayload(
                requesterPeerId = repository.localIdentity.peerId,
                wantedProfileIds = wantedProfiles.take(20),
                wantedPostIds = wantedPosts.take(20),
                wantedStoryIds = wantedStories.take(20),
                wantedMediaIds = emptyList(),
                wantedDmPacketIds = wantedDms.take(20),
                wantedNoteIds = wantedNotes.take(20),
                wantedReactionKeys = wantedReactions.take(50),
                wantedPollVoteKeys = wantedVotes.take(50),
                wantedPostLikeKeys = wantedPostLikes.take(50),
                wantedStoryLikeKeys = wantedStoryLikes.take(50)
            )
            onSendFrameToPeer(
                sourceAddress,
                MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.SYNC_REQUEST,
                    packetId = "sreq_${UUID.randomUUID().toString().take(12)}",
                    originPeerId = repository.localIdentity.peerId,
                    targetPeerId = remote.senderPeerId,
                    payload = json.encodeToString(req).toByteArray(Charsets.UTF_8)
                )
            )
            onLog(
                "ANTI-ENTROPY",
                "Requested ${wantedProfiles.size} profiles, ${wantedPosts.size} posts, ${wantedStories.size} stories, ${wantedDms.size} DMs from ${remote.senderPeerId.take(8)}",
                false
            )
        }

        // Reciprocate ANNOUNCE + SYNC_VECTOR if we haven't sent our vector to this peer recently
        val lastSent = lastVectorSentAtMs[sourceAddress] ?: 0L
        if (now - lastSent > 3_000L) {
            lastVectorSentAtMs[sourceAddress] = now
            val local = repository.localIdentity
            val announce = repository.buildLocalAnnouncePayload()
            onSendFrameToPeer(
                sourceAddress,
                MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.ANNOUNCE,
                    packetId = "ann_${local.peerId}_${UUID.randomUUID().toString().take(8)}",
                    originPeerId = local.peerId,
                    targetPeerId = remote.senderPeerId,
                    payload = json.encodeToString(announce).toByteArray(Charsets.UTF_8)
                )
            )
            val localVector = buildSyncVector(sourceAddress)
            onSendFrameToPeer(
                sourceAddress,
                MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.SYNC_VECTOR,
                    packetId = "sv_${UUID.randomUUID().toString().take(12)}",
                    originPeerId = local.peerId,
                    targetPeerId = remote.senderPeerId,
                    payload = json.encodeToString(localVector).toByteArray(Charsets.UTF_8)
                )
            )
        }

        // Schedule coordinated optimal + parallel media pull across all peers holding needed blobs
        if (remote.completeMediaIds.isNotEmpty()) {
            scheduleParallelMediaPull(delayMs = 150L)
        }
    }

    private fun scheduleParallelMediaPull(delayMs: Long, forceReschedule: Boolean = true) {
        synchronized(pullLock) {
            if (!forceReschedule && parallelPullJob?.isActive == true) {
                return
            }
            parallelPullJob?.cancel()
            parallelPullJob = scope.launch {
                delay(delayMs)
                executeParallelMediaPull()
            }
        }
    }

    private suspend fun executeParallelMediaPull() {
        val db = repository.database
        val completeIds = db.mediaBlobDao().getCompleteMediaIds().toSet()
        val incompleteBlobs = db.mediaBlobDao().getIncompleteBlobs()

        val missingChunksByMedia = LinkedHashMap<String, List<Int>>()
        for (blob in incompleteBlobs) {
            val missing = repository.mediaStorageManager.getMissingChunkIndices(blob.mediaId)
            if (missing.isNotEmpty()) {
                missingChunksByMedia[blob.mediaId] = missing
            }
        }

        // Also check for any complete media advertised by peers that hasn't initialized a MediaBlobEntity yet
        val knownMediaIds = completeIds + incompleteBlobs.map { it.mediaId }.toSet()
        val uninitializedAvatarOrMedia = LinkedHashSet<String>()
        val allProfiles = db.profileDao().getAllProfiles()
        for (p in allProfiles) {
            val av = p.avatarMediaId
            if (!av.isNullOrEmpty() && av !in knownMediaIds) {
                uninitializedAvatarOrMedia.add(av)
            }
        }

        if (missingChunksByMedia.isEmpty() && uninitializedAvatarOrMedia.isEmpty()) {
            publishUpdatedScores()
            return
        }

        val hasWifiDirectCandidate = missingChunksByMedia.keys.any { mediaId ->
            peerSelector.getAddressesWithMedia(mediaId).any { addr ->
                peerSelector.getLinkMetrics(addr)?.transportType == MeshProtocol.TransportProtocol.WIFI_DIRECT
            }
        }
        val batchSize = if (hasWifiDirectCandidate) {
            OptimalPeerSelector.WIFI_DIRECT_BATCH_CHUNKS_PER_MEDIA
        } else {
            OptimalPeerSelector.DEFAULT_BATCH_CHUNKS_PER_MEDIA
        }
        val plan = peerSelector.planParallelDownloads(
            missingChunksByMedia = missingChunksByMedia,
            maxChunksPerMediaPerRound = batchSize
        )
        onLinkScoresUpdated(peerSelector.getAllLinkMetrics(), plan.scoresByAddress, plan.optimalAddresses)

        // Request any uninitialized media (like profile avatars) from the single highest-scoring peer that holds it
        val extraMediaByPeer = LinkedHashMap<String, MutableList<String>>()
        for (mediaId in uninitializedAvatarOrMedia) {
            val bestHolder = peerSelector.getAddressesWithMedia(mediaId)
                .maxByOrNull { plan.scoresByAddress[it] ?: 0.0 }
            if (bestHolder != null) {
                extraMediaByPeer.getOrPut(bestHolder) { mutableListOf() }.add(mediaId)
            }
        }

        val targetAddresses = (plan.requestsByPeer.keys + extraMediaByPeer.keys).distinct()
        if (targetAddresses.isEmpty()) return

        val requesterId = repository.localIdentity.peerId
        for (peerAddr in targetAddresses) {
            val chunkMap = plan.requestsByPeer[peerAddr].orEmpty()
            val fullIds = extraMediaByPeer[peerAddr].orEmpty()
            if (chunkMap.isEmpty() && fullIds.isEmpty()) continue

            scope.launch {
                val req = SyncRequestPayload(
                    requesterPeerId = requesterId,
                    wantedMediaIds = fullIds.take(3),
                    wantedMediaChunkIndices = chunkMap
                )
                val targetPeerId = peerSelector.getLinkMetrics(peerAddr)?.peerId ?: ""
                onSendFrameToPeer(
                    peerAddr,
                    MeshProtocol.MeshFrame(
                        type = MeshProtocol.PacketType.SYNC_REQUEST,
                        packetId = "sreq_par_${UUID.randomUUID().toString().take(10)}",
                        originPeerId = requesterId,
                        targetPeerId = targetPeerId,
                        payload = json.encodeToString(req).toByteArray(Charsets.UTF_8)
                    )
                )
            }
        }

        val totalAssignedChunks = plan.requestsByPeer.values.sumOf { m -> m.values.sumOf { it.size } }
        if (totalAssignedChunks > 0) {
            val summary = plan.requestsByPeer.entries.joinToString(", ") { (addr, m) ->
                val count = m.values.sumOf { it.size }
                val peerName = peerSelector.getLinkMetrics(addr)?.peerId?.take(6) ?: addr.takeLast(5)
                val score = (plan.scoresByAddress[addr] ?: 0.0).toInt()
                "$peerName[$count chk, sc=$score]"
            }
            onLog(
                "PARALLEL",
                "Downloading $totalAssignedChunks chunk(s) across ${plan.requestsByPeer.size} optimal link(s): $summary",
                false
            )
        }
    }

    private fun scheduleBroadcastSyncVector() {
        synchronized(pullLock) {
            vectorBroadcastJob?.cancel()
            vectorBroadcastJob = scope.launch {
                delay(150L)
                val vector = buildSyncVector()
                val frame = MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.SYNC_VECTOR,
                    packetId = "sv_upd_${UUID.randomUUID().toString().take(10)}",
                    originPeerId = repository.localIdentity.peerId,
                    payload = json.encodeToString(vector).toByteArray(Charsets.UTF_8)
                )
                onBroadcastFrame(null, frame)
            }
        }
    }

    private fun publishUpdatedScores() {
        val metrics = peerSelector.getAllLinkMetrics()
        val scores = metrics.mapValues { (_, m) -> peerSelector.computeOptimalityScore(m) }
        val optimal = peerSelector.selectCanonicalLinks(metrics.values)
            .firstOrNull()?.peerAddress?.let { setOf(it) } ?: emptySet()
        onLinkScoresUpdated(metrics, scores, optimal)
    }

    private suspend fun handleSyncRequest(
        sourceAddress: String,
        negotiatedMtu: Int,
        req: SyncRequestPayload
    ) {
        val db = repository.database
        val myId = repository.localIdentity.peerId

        for (pid in req.wantedProfileIds) {
            val p = db.profileDao().getProfile(pid) ?: continue
            val payload = PeerAnnouncePayload(
                peerId = p.peerId,
                handle = p.handle,
                displayName = p.displayName,
                ed25519PubKey = p.ed25519PubKey,
                x25519PubKey = p.x25519PubKey,
                fingerprint = p.fingerprint,
                bio = p.bio,
                avatarMediaId = p.avatarMediaId,
                updatedAt = p.updatedAt,
                signature = p.signature
            )
            onSendFrameToPeer(
                sourceAddress,
                MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.PROFILE_RECORD,
                    packetId = "prof_${p.peerId}_${p.updatedAt}",
                    originPeerId = myId,
                    payload = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
                )
            )
        }

        for (postId in req.wantedPostIds) {
            val post = db.postDao().getPost(postId) ?: continue
            val author = db.profileDao().getProfile(post.authorPeerId) ?: continue
            val blob = db.mediaBlobDao().getBlob(post.mediaId) ?: continue
            val audioBlob = post.audioMediaId?.let { db.mediaBlobDao().getBlob(it) }
            val wire = PostWirePayload(
                postId = post.postId,
                authorPeerId = post.authorPeerId,
                authorEd25519PubKey = author.ed25519PubKey,
                caption = post.caption,
                mediaId = post.mediaId,
                mediaByteSize = blob.totalBytes,
                mediaChunkSize = blob.chunkSize,
                mediaTotalChunks = blob.totalChunks,
                mediaSha256Hex = blob.sha256Hex,
                width = post.width,
                height = post.height,
                createdAt = post.createdAt,
                signature = post.signature,
                audioMediaId = audioBlob?.mediaId,
                audioByteSize = audioBlob?.totalBytes ?: 0L,
                audioChunkSize = audioBlob?.chunkSize ?: 0,
                audioTotalChunks = audioBlob?.totalChunks ?: 0,
                audioSha256Hex = audioBlob?.sha256Hex ?: "",
                audioDurationMs = post.audioDurationMs,
                pollQuestion = post.pollQuestion,
                pollOptionsJson = post.pollOptionsJson
            )
            onSendFrameToPeer(
                sourceAddress,
                MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.POST_RECORD,
                    packetId = "post_${post.postId}",
                    originPeerId = post.authorPeerId,
                    payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
                )
            )
        }

        for (storyId in req.wantedStoryIds) {
            val story = db.storyDao().getStory(storyId) ?: continue
            val author = db.profileDao().getProfile(story.authorPeerId) ?: continue
            val blob = db.mediaBlobDao().getBlob(story.mediaId) ?: continue
            val audioBlob = story.audioMediaId?.let { db.mediaBlobDao().getBlob(it) }
            val wire = StoryWirePayload(
                storyId = story.storyId,
                authorPeerId = story.authorPeerId,
                authorEd25519PubKey = author.ed25519PubKey,
                mediaId = story.mediaId,
                mediaByteSize = blob.totalBytes,
                mediaChunkSize = blob.chunkSize,
                mediaTotalChunks = blob.totalChunks,
                mediaSha256Hex = blob.sha256Hex,
                caption = story.caption,
                createdAt = story.createdAt,
                expiresAt = story.expiresAt,
                signature = story.signature,
                audioMediaId = audioBlob?.mediaId,
                audioByteSize = audioBlob?.totalBytes ?: 0L,
                audioChunkSize = audioBlob?.chunkSize ?: 0,
                audioTotalChunks = audioBlob?.totalChunks ?: 0,
                audioSha256Hex = audioBlob?.sha256Hex ?: "",
                audioDurationMs = story.audioDurationMs
            )
            onSendFrameToPeer(
                sourceAddress,
                MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.STORY_RECORD,
                    packetId = "story_${story.storyId}",
                    originPeerId = story.authorPeerId,
                    payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
                )
            )
        }

        for (dmPktId in req.wantedDmPacketIds) {
            val pkt = db.meshPacketDao().getPacket(dmPktId) ?: continue
            val wire = runCatching {
                json.decodeFromString<DmWirePayload>(String(pkt.payloadBytes, Charsets.UTF_8))
            }.getOrNull()
            val binPayload = wire?.let { DmBinaryCodec.encode(it) }
            onSendFrameToPeer(
                sourceAddress,
                MeshProtocol.MeshFrame(
                    type = if (binPayload != null) MeshProtocol.PacketType.DM_PACKET_BIN else MeshProtocol.PacketType.DM_PACKET,
                    packetId = pkt.packetId,
                    originPeerId = pkt.originPeerId,
                    targetPeerId = pkt.targetPeerId,
                    ttl = pkt.ttl,
                    hopCount = pkt.hopCount,
                    timestamp = pkt.createdAt,
                    payload = binPayload ?: pkt.payloadBytes
                )
            )
        }

        for (noteId in req.wantedNoteIds) {
            val note = db.noteDao().getNote(noteId) ?: continue
            val author = db.profileDao().getProfile(note.authorPeerId) ?: continue
            val wire = NoteWirePayload(
                noteId = note.noteId,
                authorPeerId = note.authorPeerId,
                authorEd25519PubKey = author.ed25519PubKey,
                text = note.text,
                moodEmoji = note.moodEmoji,
                createdAt = note.createdAt,
                expiresAt = note.expiresAt,
                signature = note.signature
            )
            onSendFrameToPeer(
                sourceAddress,
                MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.NOTE_RECORD,
                    packetId = "note_${note.noteId}",
                    originPeerId = note.authorPeerId,
                    payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
                )
            )
        }

        for (rKey in req.wantedReactionKeys) {
            val parts = rKey.split('|')
            if (parts.size < 2) continue
            val reaction = db.postReactionDao().getReaction(parts[0], parts[1]) ?: continue
            val edPub = reaction.reactorEd25519PubKey.ifBlank {
                db.profileDao().getProfile(reaction.reactorPeerId)?.ed25519PubKey.orEmpty()
            }
            if (edPub.isBlank()) continue
            val wire = PostReactionWirePayload(
                postId = reaction.postId,
                reactorPeerId = reaction.reactorPeerId,
                reactorEd25519PubKey = edPub,
                emoji = reaction.emoji,
                reactedAt = reaction.reactedAt,
                signature = reaction.signature
            )
            onSendFrameToPeer(
                sourceAddress,
                MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.POST_REACTION,
                    packetId = "react_${reaction.postId}_${reaction.reactorPeerId}_${reaction.reactedAt}",
                    originPeerId = reaction.reactorPeerId,
                    payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
                )
            )
        }

        for (vKey in req.wantedPollVoteKeys) {
            val parts = vKey.split('|')
            if (parts.size < 2) continue
            val vote = db.pollVoteDao().getVote(parts[0], parts[1]) ?: continue
            val voter = db.profileDao().getProfile(vote.voterPeerId) ?: continue
            val wire = PollVoteWirePayload(
                postId = vote.postId,
                voterPeerId = vote.voterPeerId,
                voterEd25519PubKey = voter.ed25519PubKey,
                optionIndex = vote.optionIndex,
                votedAt = vote.votedAt,
                signature = vote.signature
            )
            onSendFrameToPeer(
                sourceAddress,
                MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.POLL_VOTE,
                    packetId = "vote_${vote.postId}_${vote.voterPeerId}_${vote.votedAt}",
                    originPeerId = vote.voterPeerId,
                    payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
                )
            )
        }

        for (plKey in req.wantedPostLikeKeys) {
            val parts = plKey.split('|')
            if (parts.size < 2) continue
            val like = db.postLikeDao().getLike(parts[0], parts[1]) ?: continue
            val edPub = like.likerEd25519PubKey.ifBlank {
                db.profileDao().getProfile(like.likerPeerId)?.ed25519PubKey.orEmpty()
            }
            if (edPub.isBlank()) continue
            val wire = PostLikeWirePayload(
                postId = like.postId,
                likerPeerId = like.likerPeerId,
                likerEd25519PubKey = edPub,
                isLiked = like.isLiked,
                likedAt = like.likedAt,
                signature = like.signature
            )
            onSendFrameToPeer(
                sourceAddress,
                MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.POST_LIKE,
                    packetId = "plike_${like.postId}_${like.likerPeerId}_${like.likedAt}",
                    originPeerId = like.likerPeerId,
                    payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
                )
            )
        }

        for (slKey in req.wantedStoryLikeKeys) {
            val parts = slKey.split('|')
            if (parts.size < 2) continue
            val like = db.storyLikeDao().getLike(parts[0], parts[1]) ?: continue
            val edPub = like.likerEd25519PubKey.ifBlank {
                db.profileDao().getProfile(like.likerPeerId)?.ed25519PubKey.orEmpty()
            }
            if (edPub.isBlank()) continue
            val wire = StoryLikeWirePayload(
                storyId = like.storyId,
                likerPeerId = like.likerPeerId,
                likerEd25519PubKey = edPub,
                isLiked = like.isLiked,
                likedAt = like.likedAt,
                signature = like.signature
            )
            onSendFrameToPeer(
                sourceAddress,
                MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.STORY_LIKE,
                    packetId = "slike_${like.storyId}_${like.likerPeerId}_${like.likedAt}",
                    originPeerId = like.likerPeerId,
                    payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
                )
            )
        }

        // Stream requested chunk subsets and full media requests concurrently
        if (req.wantedMediaChunkIndices.isNotEmpty() || req.wantedMediaIds.isNotEmpty()) {
            scope.launch {
                activeUploadJobs.incrementAndGet()
                try {
                    for ((mediaId, chunkIndices) in req.wantedMediaChunkIndices) {
                        streamMediaChunksToPeer(
                            peerAddress = sourceAddress,
                            mediaId = mediaId,
                            negotiatedMtu = negotiatedMtu,
                            chunkIndices = chunkIndices,
                            targetPeerId = req.requesterPeerId
                        )
                    }
                    for (mediaId in req.wantedMediaIds) {
                        if (!req.wantedMediaChunkIndices.containsKey(mediaId)) {
                            streamMediaChunksToPeer(
                                peerAddress = sourceAddress,
                                mediaId = mediaId,
                                negotiatedMtu = negotiatedMtu,
                                chunkIndices = null,
                                targetPeerId = req.requesterPeerId
                            )
                        }
                    }
                } finally {
                    activeUploadJobs.decrementAndGet()
                }
            }
        }
    }

    suspend fun streamMediaChunksToPeer(
        peerAddress: String?,
        mediaId: String,
        negotiatedMtu: Int,
        chunkIndices: List<Int>? = null,
        targetPeerId: String = ""
    ) {
        val blob = repository.database.mediaBlobDao().getBlob(mediaId) ?: return
        if (!blob.isComplete) return

        val indicesToSend = chunkIndices ?: (0 until blob.totalChunks).toList()
        var sentCount = 0
        for (idx in indicesToSend) {
            val slice = repository.mediaStorageManager.readChunk(mediaId, idx) ?: continue
            val binBytes = MediaChunkBinaryCodec.encode(
                MediaChunkBinary(
                    mediaId = mediaId,
                    ownerPeerId = blob.ownerPeerId,
                    chunkIndex = idx,
                    totalChunks = blob.totalChunks,
                    chunkSize = blob.chunkSize,
                    totalBytes = blob.totalBytes,
                    sha256Hex = blob.sha256Hex,
                    data = slice
                )
            )
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.MEDIA_CHUNK_BIN,
                packetId = "chk_${mediaId}_$idx",
                originPeerId = blob.ownerPeerId,
                targetPeerId = targetPeerId,
                payload = binBytes
            )
            if (peerAddress != null) {
                onSendFrameToPeer(peerAddress, frame)
            } else {
                onBroadcastFrame(null, frame)
            }
            sentCount++
            repository.mediaStorageManager.reportUploadProgress(
                mediaId = mediaId,
                completedChunks = if (chunkIndices == null) sentCount else (idx + 1).coerceAtMost(blob.totalChunks),
                totalChunks = blob.totalChunks,
                totalBytes = blob.totalBytes
            )
        }
        if (sentCount > 0) {
            onLog("UPLOAD", "Queued $sentCount binary chunk(s) for ${mediaId.take(10)} (MTU=$negotiatedMtu)", false)
        }
    }

    private suspend fun storeGossipPacket(frame: MeshProtocol.MeshFrame) {
        val now = System.currentTimeMillis()
        repository.database.meshPacketDao().insertIfAbsent(
            MeshPacketEntity(
                packetId = frame.packetId,
                packetType = frame.type.name,
                originPeerId = frame.originPeerId,
                targetPeerId = frame.targetPeerId,
                ttl = frame.ttl,
                hopCount = frame.hopCount,
                payloadBytes = frame.payload,
                createdAt = frame.timestamp,
                expiresAt = now + 48L * 60 * 60 * 1000L,
                byteSize = frame.payload.size.toLong()
            )
        )
    }

    private fun relayIfTtlRemains(sourceAddress: String, frame: MeshProtocol.MeshFrame) {
        if (frame.ttl <= 1) return
        val forwarded = frame.nextHop()
        onBroadcastFrame(sourceAddress, forwarded)
        onPacketRelayed()
    }
}
