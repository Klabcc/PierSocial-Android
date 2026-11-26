package com.klabcc.piersocial.mesh

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.klabcc.piersocial.data.local.DirectMessageEntity
import com.klabcc.piersocial.data.local.PostEntity
import com.klabcc.piersocial.data.local.StoryEntity
import com.klabcc.piersocial.data.repository.PierRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Coordinates the BLE Mesh ForegroundService, AntiEntropySyncManager,
 * live peer/connection/log state for the Mesh Inspector Drawer, and
 * outbound broadcasts when posts, stories, or DMs are created.
 */
class MeshController(
    private val context: Context,
    private val repository: PierRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredBlePeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredBlePeer>> = _discoveredPeers.asStateFlow()

    private val _activeConnections = MutableStateFlow<List<GattConnectionInfo>>(emptyList())
    val activeConnections: StateFlow<List<GattConnectionInfo>> = _activeConnections.asStateFlow()

    private val _meshLogs = MutableStateFlow<List<MeshLogEntry>>(emptyList())
    val meshLogs: StateFlow<List<MeshLogEntry>> = _meshLogs.asStateFlow()

    private val _radioState = MutableStateFlow(
        MeshRadioState(
            bluetoothEnabled = isBluetoothEnabled(),
            hasPermissions = hasRequiredMeshPermissions(context)
        )
    )
    val radioState: StateFlow<MeshRadioState> = _radioState.asStateFlow()

    @Volatile
    var activeChatPeerId: String? = null

    @Volatile
    var isAppInForeground: Boolean = false

    fun shouldShowDmNotification(senderPeerId: String): Boolean {
        return !(isAppInForeground && activeChatPeerId == senderPeerId)
    }

    internal var serviceTransport: BleMeshTransport? = null

    val syncManager: AntiEntropySyncManager by lazy {
        AntiEntropySyncManager(
            repository = repository,
            onSendFrameToPeer = { addr, frame ->
                serviceTransport?.sendFrame(addr, frame)
            },
            onBroadcastFrame = { exclude, frame ->
                serviceTransport?.broadcastFrame(exclude, frame)
            },
            onPeerIdentified = { addr, peerId, displayName ->
                val updatedConns = _activeConnections.value.map {
                    if (it.address == addr) it.copy(peerId = peerId) else it
                }
                _activeConnections.value = updatedConns

                val updatedPeers = _discoveredPeers.value.map {
                    if (it.address == addr) it.copy(peerId = peerId, displayName = displayName) else it
                }
                _discoveredPeers.value = updatedPeers
                serviceTransport?.onPeerIdentityResolved(addr, peerId)
            },
            onWifiDirectOfferReceived = { sourceAddr, offer ->
                serviceTransport?.onWifiDirectOfferReceived(sourceAddr, offer)
            },
            onLog = { tag, msg, isError -> appendLog(tag, msg, isError) },
            onPacketRelayed = {
                _radioState.value = _radioState.value.copy(
                    packetsRelayed = _radioState.value.packetsRelayed + 1
                )
            },
            onGetLinkSnapshot = { addr -> serviceTransport?.getLinkSnapshot(addr) },
            onGetTotalQueuedSlices = { serviceTransport?.getTotalQueuedSlices() ?: 0 },
            onGetConnectedCount = { _activeConnections.value.size },
            onIsLocal2MPhySupported = { serviceTransport?.isLocal2MPhySupported() ?: false },
            onLinkScoresUpdated = { metrics, scores, optimalAddresses ->
                metrics.forEach { (addr, m) ->
                    m.peerId?.takeIf { it.isNotBlank() }?.let { pid ->
                        serviceTransport?.onPeerIdentityResolved(addr, pid)
                    }
                }
                _activeConnections.value = _activeConnections.value.map { conn ->
                    val m = metrics[conn.address]
                    val sc = scores[conn.address] ?: conn.optimalityScore
                    if (m != null) {
                        conn.copy(
                            peerId = m.peerId ?: conn.peerId,
                            negotiatedMtu = maxOf(conn.negotiatedMtu, m.negotiatedMtu),
                            rssiDbm = m.rssiDbm,
                            rttMs = m.rttMs,
                            supports2MPhy = m.supports2MPhy,
                            remoteActiveUploads = m.remoteActiveUploads,
                            remoteQueuedSlices = m.remoteQueuedSlices,
                            measuredGoodputBps = maxOf(m.measuredGoodputBps, m.advertisedGoodputBps),
                            optimalityScore = sc,
                            isOptimalSource = conn.address in optimalAddresses,
                            transportType = m.transportType
                        )
                    } else {
                        conn.copy(
                            optimalityScore = sc,
                            isOptimalSource = conn.address in optimalAddresses
                        )
                    }
                }
            }
        )
    }

    fun refreshPermissionAndBluetoothState() {
        _radioState.value = _radioState.value.copy(
            bluetoothEnabled = isBluetoothEnabled(),
            hasPermissions = hasRequiredMeshPermissions(context)
        )
    }

    fun startMeshService() {
        refreshPermissionAndBluetoothState()
        if (!hasRequiredMeshPermissions(context)) {
            appendLog("PERM", "BLE permissions missing; mesh service waiting for grant", true)
            return
        }
        val intent = Intent(context, BleMeshService::class.java).apply {
            action = BleMeshService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
        appendLog("SERVICE", "Requested BleMeshService start (connectedDevice)", false)
    }

    fun stopMeshService() {
        val intent = Intent(context, BleMeshService::class.java).apply {
            action = BleMeshService.ACTION_STOP
        }
        context.startService(intent)
        appendLog("SERVICE", "Requested BleMeshService stop", false)
    }

    fun forceSync() {
        refreshPermissionAndBluetoothState()
        val now = System.currentTimeMillis()
        _radioState.value = _radioState.value.copy(lastSyncAt = now)
        val transport = serviceTransport
        if (transport == null) {
            startMeshService()
            return
        }
        transport.triggerManualSync()
        if (_activeConnections.value.isNotEmpty()) {
            appendLog("SYNC", "Manual Force Sync triggered across ${_activeConnections.value.size} active GATT link(s)", false)
        } else {
            appendLog("SYNC", "Radio refreshed & scanning/advertising for nearby PierSocial peers", false)
            scope.launch {
                repository.retentionManager.enforceRetentionPolicy()
            }
        }
    }

    fun broadcastNewPost(post: PostEntity) {
        scope.launch {
            val author = repository.ensureLocalProfileInitialized()
            val blob = repository.database.mediaBlobDao().getBlob(post.mediaId) ?: return@launch
            val audioBlob = post.audioMediaId?.let { repository.database.mediaBlobDao().getBlob(it) }
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
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.POST_RECORD,
                packetId = "post_${post.postId}",
                originPeerId = author.peerId,
                payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
            )
            syncManager.deduplicator.markSeenIfNew(frame.packetId)
            serviceTransport?.broadcastFrame(null, frame)
            syncManager.streamMediaChunksToPeer(null, post.mediaId, MeshProtocol.TARGET_MTU)
            if (audioBlob != null) {
                syncManager.streamMediaChunksToPeer(null, audioBlob.mediaId, MeshProtocol.TARGET_MTU)
            }
            appendLog("BROADCAST", "Published post ${post.postId.take(8)} (${blob.totalChunks} chunks) to mesh", false)
        }
    }

    fun broadcastNewStory(story: StoryEntity) {
        scope.launch {
            val author = repository.ensureLocalProfileInitialized()
            val blob = repository.database.mediaBlobDao().getBlob(story.mediaId) ?: return@launch
            val audioBlob = story.audioMediaId?.let { repository.database.mediaBlobDao().getBlob(it) }
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
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.STORY_RECORD,
                packetId = "story_${story.storyId}",
                originPeerId = author.peerId,
                payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
            )
            syncManager.deduplicator.markSeenIfNew(frame.packetId)
            serviceTransport?.broadcastFrame(null, frame)
            syncManager.streamMediaChunksToPeer(null, story.mediaId, MeshProtocol.TARGET_MTU)
            if (audioBlob != null) {
                syncManager.streamMediaChunksToPeer(null, audioBlob.mediaId, MeshProtocol.TARGET_MTU)
            }
            appendLog("BROADCAST", "Published 24h story ${story.storyId.take(8)} to mesh", false)
        }
    }

    fun broadcastLocalProfileUpdate() {
        scope.launch {
            val announce = repository.buildLocalAnnouncePayload()
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.ANNOUNCE,
                packetId = "ann_${announce.peerId}_${announce.updatedAt}",
                originPeerId = announce.peerId,
                payload = json.encodeToString(announce).toByteArray(Charsets.UTF_8)
            )
            syncManager.deduplicator.markSeenIfNew(frame.packetId)
            serviceTransport?.broadcastFrame(null, frame)
        }
    }

    fun broadcastNote(wire: NoteWirePayload) {
        scope.launch {
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.NOTE_RECORD,
                packetId = "note_${wire.noteId}",
                originPeerId = wire.authorPeerId,
                payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
            )
            syncManager.deduplicator.markSeenIfNew(frame.packetId)
            serviceTransport?.broadcastFrame(null, frame)
            appendLog("BROADCAST", "Published 24h note ${wire.noteId.take(8)} to mesh", false)
        }
    }

    fun broadcastPostReaction(wire: PostReactionWirePayload) {
        scope.launch {
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.POST_REACTION,
                packetId = "react_${wire.postId}_${wire.reactorPeerId}_${wire.reactedAt}",
                originPeerId = wire.reactorPeerId,
                payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
            )
            syncManager.deduplicator.markSeenIfNew(frame.packetId)
            serviceTransport?.broadcastFrame(null, frame)
        }
    }

    fun broadcastPostLike(wire: PostLikeWirePayload) {
        scope.launch {
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.POST_LIKE,
                packetId = "plike_${wire.postId}_${wire.likerPeerId}_${wire.likedAt}",
                originPeerId = wire.likerPeerId,
                payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
            )
            syncManager.deduplicator.markSeenIfNew(frame.packetId)
            serviceTransport?.broadcastFrame(null, frame)
        }
    }

    fun broadcastStoryLike(wire: StoryLikeWirePayload) {
        scope.launch {
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.STORY_LIKE,
                packetId = "slike_${wire.storyId}_${wire.likerPeerId}_${wire.likedAt}",
                originPeerId = wire.likerPeerId,
                payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
            )
            syncManager.deduplicator.markSeenIfNew(frame.packetId)
            serviceTransport?.broadcastFrame(null, frame)
        }
    }

    fun broadcastPollVote(wire: PollVoteWirePayload) {
        scope.launch {
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.POLL_VOTE,
                packetId = "vote_${wire.postId}_${wire.voterPeerId}_${wire.votedAt}",
                originPeerId = wire.voterPeerId,
                payload = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
            )
            syncManager.deduplicator.markSeenIfNew(frame.packetId)
            serviceTransport?.broadcastFrame(null, frame)
        }
    }

    fun broadcastDirectMessage(dm: DirectMessageEntity, wire: DmWirePayload) {
        scope.launch {
            broadcastDirectMessageNow(dm, wire)
        }
    }

    suspend fun broadcastDirectMessageNow(dm: DirectMessageEntity, wire: DmWirePayload) {
        val binPayload = DmBinaryCodec.encode(wire)
        val frame = MeshProtocol.MeshFrame(
            type = if (binPayload != null) MeshProtocol.PacketType.DM_PACKET_BIN else MeshProtocol.PacketType.DM_PACKET,
            packetId = "pkt_dm_${dm.messageId}",
            originPeerId = dm.senderPeerId,
            targetPeerId = dm.recipientPeerId,
            payload = binPayload ?: json.encodeToString(wire).toByteArray(Charsets.UTF_8)
        )
        syncManager.deduplicator.markSeenIfNew(frame.packetId)
        val sent = serviceTransport?.broadcastFrame(null, frame) ?: false
        if (sent) {
            repository.markDmRelayed(dm.messageId, 1)
            appendLog("DM", "E2EE DM ${dm.messageId.take(8)} pushed on EXPRESS lane", false)
        } else {
            appendLog("DM", "E2EE DM ${dm.messageId.take(8)} queued (store-and-forward)", false)
        }
    }

    internal fun updateRadioState(transform: (MeshRadioState) -> MeshRadioState) {
        _radioState.value = transform(_radioState.value)
    }

    internal fun upsertDiscoveredPeer(peer: DiscoveredBlePeer) {
        val current = _discoveredPeers.value.toMutableList()
        val idx = current.indexOfFirst { it.address == peer.address }
        if (idx >= 0) {
            val existing = current[idx]
            current[idx] = peer.copy(
                peerId = peer.peerId ?: existing.peerId,
                displayName = if (peer.displayName == "Pier Peer" && existing.displayName != "Pier Peer") {
                    existing.displayName
                } else {
                    peer.displayName
                }
            )
        } else {
            current.add(0, peer)
        }
        _discoveredPeers.value = current.take(40)
    }

    internal fun updateConnections(connections: List<GattConnectionInfo>) {
        val existingByKey = _activeConnections.value.associateBy { "${it.role}_${it.address}" }
        _activeConnections.value = connections.map { fresh ->
            val prev = existingByKey["${fresh.role}_${fresh.address}"]
            if (prev == null) {
                fresh
            } else {
                fresh.copy(
                    peerId = fresh.peerId ?: prev.peerId,
                    rttMs = if (fresh.rttMs > 0L) fresh.rttMs else prev.rttMs,
                    remoteActiveUploads = maxOf(fresh.remoteActiveUploads, prev.remoteActiveUploads),
                    remoteQueuedSlices = maxOf(fresh.remoteQueuedSlices, prev.remoteQueuedSlices),
                    measuredGoodputBps = maxOf(fresh.measuredGoodputBps, prev.measuredGoodputBps),
                    optimalityScore = if (fresh.optimalityScore > 0.0) fresh.optimalityScore else prev.optimalityScore,
                    isOptimalSource = fresh.isOptimalSource || prev.isOptimalSource,
                    transportType = fresh.transportType
                )
            }
        }
    }

    internal fun appendLog(tag: String, message: String, isError: Boolean) {
        if (isError) {
            Log.w("PierBleMesh", "[$tag] $message")
        } else {
            Log.d("PierBleMesh", "[$tag] $message")
        }
        val entry = MeshLogEntry(tag = tag, message = message, isError = isError)
        _meshLogs.value = (listOf(entry) + _meshLogs.value).take(120)
    }

    private fun isBluetoothEnabled(): Boolean {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter?.isEnabled == true
    }

    companion object {
        fun requiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            } else {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
        }

        fun hasRequiredMeshPermissions(context: Context): Boolean {
            val blePerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            return blePerms.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }

        fun hasWifiDirectPermissions(context: Context): Boolean {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
            return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }
}

data class LinkTransportSnapshot(
    val address: String,
    val negotiatedMtu: Int,
    val rssiDbm: Int,
    val supports2MPhy: Boolean,
    val queuedSlices: Int,
    val role: String,
    val transportType: MeshProtocol.TransportProtocol = MeshProtocol.TransportProtocol.BLE
)

interface BleMeshTransport {
    fun sendFrame(peerAddress: String, frame: MeshProtocol.MeshFrame): Boolean
    fun broadcastFrame(excludeAddress: String?, frame: MeshProtocol.MeshFrame): Boolean
    fun triggerManualSync()
    fun getLinkSnapshot(peerAddress: String): LinkTransportSnapshot? = null
    fun getTotalQueuedSlices(): Int = 0
    fun isLocal2MPhySupported(): Boolean = false
    fun onPeerIdentityResolved(peerAddress: String, peerId: String) {}
    fun onWifiDirectOfferReceived(sourceBleAddress: String, offer: WifiDirectOfferPayload) {}
}
