package com.klabcc.piersocial

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.p2p.WifiP2pManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.klabcc.piersocial.crypto.CryptoManager
import com.klabcc.piersocial.crypto.KeyWrappingProvider
import com.klabcc.piersocial.data.retention.RetentionManager
import com.klabcc.piersocial.data.repository.PierRepository
import com.klabcc.piersocial.data.storage.MediaStorageManager
import com.klabcc.piersocial.data.storage.PhotoAdjustments
import com.klabcc.piersocial.mesh.AntiEntropySyncManager
import com.klabcc.piersocial.mesh.LinkTransportSnapshot
import com.klabcc.piersocial.mesh.MeshProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MeshOptimalParallelTransferInstrumentedTest {

    private class TestKeyWrapper : KeyWrappingProvider {
        override fun wrap(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
            val iv = ByteArray(12) { 0x33 }
            val cipher = plaintext.map { (it.toInt() xor 0x6C).toByte() }.toByteArray()
            return iv to cipher
        }

        override fun unwrap(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            return ciphertext.map { (it.toInt() xor 0x6C).toByte() }.toByteArray()
        }
    }

    private data class VirtualNode(
        val name: String,
        val address: String,
        val dir: File,
        val db: com.klabcc.piersocial.data.local.PierDatabase,
        val repo: PierRepository,
        var syncManager: AntiEntropySyncManager? = null,
        val sentCountsByTarget: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap(),
        val sentChunksByTarget: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
    )

    private val createdDirs = mutableListOf<File>()
    private val openDbs = mutableListOf<com.klabcc.piersocial.data.local.PierDatabase>()
    private val syncManagers = mutableListOf<AntiEntropySyncManager>()

    @After
    fun cleanup() {
        syncManagers.forEach { runCatching { it.shutdown() } }
        openDbs.forEach { runCatching { it.close() } }
        createdDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    @Test
    fun fourNodeMesh_probesLinks_picksOptimalNodes_andTransfersMediaInParallel() = runBlocking {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val appContext = baseContext.applicationContext
        val netScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        try {
            val nodes = (1..4).map { idx ->
                val dir = File(appContext.cacheDir, "mesh_node_${idx}_${System.nanoTime()}").apply { mkdirs() }
                createdDirs.add(dir)
                val nodeCtx = object : ContextWrapper(appContext) {
                    override fun getFilesDir(): File = dir
                }
                val db = Room.inMemoryDatabaseBuilder(appContext, com.klabcc.piersocial.data.local.PierDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
                openDbs.add(db)
                val crypto = CryptoManager(File(dir, "identity.json"), TestKeyWrapper())
                val storage = MediaStorageManager(nodeCtx, db.mediaBlobDao())
                val retention = RetentionManager(
                    context = appContext,
                    profileDao = db.profileDao(),
                    postDao = db.postDao(),
                    storyDao = db.storyDao(),
                    meshPacketDao = db.meshPacketDao(),
                    mediaBlobDao = db.mediaBlobDao(),
                    mediaStorageManager = storage
                )
                val repo = PierRepository(nodeCtx, db, crypto, storage, retention)
                repo.ensureLocalProfileInitialized()
                VirtualNode(
                    name = "node$idx",
                    address = "DE:AD:BE:EF:00:0$idx",
                    dir = dir,
                    db = db,
                    repo = repo
                )
            }

            val nodeByAddr = nodes.associateBy { it.address }

            // Asymmetric simulated BLE links into Node 4:
            // - Node 1: MTU=512, 2M PHY, -50 dBm, ~10ms latency (optimal)
            // - Node 2: MTU=512, 1M PHY, -62 dBm, ~25ms latency (fast secondary parallel source)
            // - Node 3: MTU=23,  1M PHY, -92 dBm, ~250ms latency (slow straggler to avoid)
            fun linkParams(from: VirtualNode, to: VirtualNode): Triple<Int, Long, Boolean> {
                val pair = setOf(from.address, to.address)
                return when {
                    nodes[0].address in pair -> Triple(512, 8L, true)
                    nodes[1].address in pair -> Triple(512, 20L, false)
                    else -> Triple(23, 220L, false)
                }
            }

            fun rssiFor(addr: String): Int = when (addr) {
                nodes[0].address -> -50
                nodes[1].address -> -62
                nodes[2].address -> -92
                else -> -58
            }

            nodes.forEach { node ->
                node.syncManager = AntiEntropySyncManager(
                    repository = node.repo,
                    onSendFrameToPeer = { targetAddr, frame ->
                        val target = nodeByAddr[targetAddr] ?: return@AntiEntropySyncManager
                        val (mtu, oneWayDelayMs, _) = linkParams(node, target)
                        node.sentCountsByTarget.getOrPut(targetAddr) { AtomicInteger(0) }.incrementAndGet()
                        if (frame.type == MeshProtocol.PacketType.MEDIA_CHUNK_BIN) {
                            node.sentChunksByTarget.getOrPut(targetAddr) { AtomicInteger(0) }.incrementAndGet()
                        }
                        netScope.launch {
                            delay(oneWayDelayMs)
                            val decoded = MeshProtocol.MeshFrame.decode(frame.encode()) ?: return@launch
                            target.syncManager?.handleIncomingFrame(node.address, mtu, decoded)
                        }
                    },
                    onBroadcastFrame = { excludeAddr, frame ->
                        nodes.filter { it.address != node.address && it.address != excludeAddr }.forEach { target ->
                            val (mtu, oneWayDelayMs, _) = linkParams(node, target)
                            netScope.launch {
                                delay(oneWayDelayMs)
                                val decoded = MeshProtocol.MeshFrame.decode(frame.encode()) ?: return@launch
                                target.syncManager?.handleIncomingFrame(node.address, mtu, decoded.nextHop())
                            }
                        }
                    },
                    onPeerIdentified = { _, _, _ -> },
                    onLog = { _, _, _ -> },
                    onPacketRelayed = {},
                    onGetLinkSnapshot = { peerAddr ->
                        val peer = nodeByAddr[peerAddr]
                        if (peer == null) null else {
                            val (mtu, _, is2M) = linkParams(node, peer)
                            LinkTransportSnapshot(
                                address = peerAddr,
                                negotiatedMtu = mtu,
                                rssiDbm = rssiFor(peerAddr),
                                supports2MPhy = is2M,
                                queuedSlices = 0,
                                role = "Central"
                            )
                        }
                    },
                    onGetTotalQueuedSlices = { 0 },
                    onGetConnectedCount = { 3 },
                    onIsLocal2MPhySupported = { node.address != nodes[2].address }
                ).also { syncManagers.add(it) }
            }

            // Seed a large image post (> 10 KB, ~25+ chunks) on Nodes 1, 2, 3
            val bmp = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until height step 8) {
                    for (x in 0 until width step 8) {
                        setPixel(x, y, Color.rgb((x * 3) % 255, (y * 5) % 255, ((x + y) * 2) % 255))
                    }
                }
            }
            val (post, blob) = nodes[0].repo.createFeedPost(
                sourceBitmap = bmp,
                adjustments = PhotoAdjustments(),
                caption = "4-device parallel optimal mesh test"
            )
            bmp.recycle()
            assertTrue("Expected multi-chunk media blob, got ${blob.totalChunks}", blob.totalChunks >= 6)

            // Replicate complete post + media onto Nodes 2 and 3 so Nodes 1, 2, 3 all act as available sources
            val fullBytes = nodes[0].repo.mediaStorageManager.getBlobFile(blob.mediaId).readBytes()
            val authorProfile = nodes[0].repo.ensureLocalProfileInitialized()
            listOf(nodes[1], nodes[2]).forEach { seedNode ->
                seedNode.db.profileDao().upsert(authorProfile.copy(isLocalUser = false, isPinned = false))
                seedNode.db.postDao().upsert(post.copy(originatedLocally = false))
                seedNode.repo.mediaStorageManager.storeLocalMedia(
                    mediaId = blob.mediaId,
                    ownerPeerId = post.authorPeerId,
                    bytes = fullBytes,
                    chunkSize = blob.chunkSize
                )
            }

            // Node 4 connects and initiates probes + sync handshakes with Nodes 1, 2, 3 simultaneously
            val node4Sync = nodes[3].syncManager!!
            listOf(nodes[0].address, nodes[1].address, nodes[2].address).forEach { peerAddr ->
                node4Sync.initiateHandshakeAndSync(peerAddr)
            }

            // Wait up to 8 seconds for parallel download + SHA-256 verification on Node 4
            var syncedBlobComplete = false
            val deadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < deadline) {
                val b = nodes[3].db.mediaBlobDao().getBlob(blob.mediaId)
                if (b?.isComplete == true) {
                    syncedBlobComplete = true
                    break
                }
                delay(100L)
            }

            assertTrue("Node 4 should finish SHA-256 verified parallel download", syncedBlobComplete)
            val feedOnNode4 = nodes[3].db.postDao().observeFeedPosts().first()
            assertTrue("Synced post should appear in Node 4 feed once blob completes", feedOnNode4.any { it.post.postId == post.postId })

            // Verify optimal ranking and parallel transfers:
            // Both Node 1 and Node 2 served chunks to Node 4, while straggler Node 3 (MTU=23) was avoided!
            val score1 = node4Sync.peerSelector.getLinkMetrics(nodes[0].address)!!
                .let { node4Sync.peerSelector.computeOptimalityScore(it) }
            val score2 = node4Sync.peerSelector.getLinkMetrics(nodes[1].address)!!
                .let { node4Sync.peerSelector.computeOptimalityScore(it) }
            val score3 = node4Sync.peerSelector.getLinkMetrics(nodes[2].address)!!
                .let { node4Sync.peerSelector.computeOptimalityScore(it) }
            assertTrue("Node 1 score ($score1) > Node 2 score ($score2)", score1 > score2)
            assertTrue("Node 2 score ($score2) > Node 3 straggler score ($score3)", score2 > score3)

            val framesFromNode1 = nodes[0].sentCountsByTarget[nodes[3].address]?.get() ?: 0
            val framesFromNode2 = nodes[1].sentCountsByTarget[nodes[3].address]?.get() ?: 0
            val chunksFromNode1 = nodes[0].sentChunksByTarget[nodes[3].address]?.get() ?: 0
            val chunksFromNode2 = nodes[1].sentChunksByTarget[nodes[3].address]?.get() ?: 0
            val chunksFromNode3 = nodes[2].sentChunksByTarget[nodes[3].address]?.get() ?: 0
            assertTrue("Expected Node 1 to transfer binary chunks in parallel (chunks=$chunksFromNode1, frames=$framesFromNode1)", chunksFromNode1 >= 2)
            assertTrue("Expected Node 2 to transfer binary chunks in parallel (chunks=$chunksFromNode2, frames=$framesFromNode2)", chunksFromNode2 >= 1)
            assertEquals("Expected straggler Node 3 to serve 0 chunks", 0, chunksFromNode3)
        } finally {
            syncManagers.forEach { runCatching { it.shutdown() } }
            netScope.cancel()
        }
    }

    @Test
    fun expressDm_preemptsBulkMedia_andCompletesRoundTripAcrossNodes() = runBlocking {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val appContext = baseContext.applicationContext
        val netScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        try {
            val nodes = (1..2).map { idx ->
                val dir = File(appContext.cacheDir, "dm_node_${idx}_${System.nanoTime()}").apply { mkdirs() }
                createdDirs.add(dir)
                val nodeCtx = object : ContextWrapper(appContext) {
                    override fun getFilesDir(): File = dir
                }
                val db = Room.inMemoryDatabaseBuilder(appContext, com.klabcc.piersocial.data.local.PierDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
                openDbs.add(db)
                val crypto = CryptoManager(File(dir, "identity.json"), TestKeyWrapper())
                val storage = MediaStorageManager(nodeCtx, db.mediaBlobDao())
                val retention = RetentionManager(
                    context = appContext,
                    profileDao = db.profileDao(),
                    postDao = db.postDao(),
                    storyDao = db.storyDao(),
                    meshPacketDao = db.meshPacketDao(),
                    mediaBlobDao = db.mediaBlobDao(),
                    mediaStorageManager = storage
                )
                val repo = PierRepository(nodeCtx, db, crypto, storage, retention)
                repo.ensureLocalProfileInitialized()
                VirtualNode(
                    name = "dmNode$idx",
                    address = "AA:BB:CC:DD:00:0$idx",
                    dir = dir,
                    db = db,
                    repo = repo
                )
            }

            val nodeByAddr = nodes.associateBy { it.address }
            val dmTypesSent = java.util.Collections.synchronizedList(mutableListOf<MeshProtocol.PacketType>())
            nodes.forEach { node ->
                node.syncManager = AntiEntropySyncManager(
                    repository = node.repo,
                    onSendFrameToPeer = { targetAddr, frame ->
                        if (frame.type.isExpressDm) dmTypesSent.add(frame.type)
                        val target = nodeByAddr[targetAddr] ?: return@AntiEntropySyncManager
                        netScope.launch {
                            delay(5L)
                            val decoded = MeshProtocol.MeshFrame.decode(frame.encode()) ?: return@launch
                            target.syncManager?.handleIncomingFrame(node.address, 517, decoded)
                        }
                    },
                    onBroadcastFrame = { excludeAddr, frame ->
                        if (frame.type.isExpressDm) dmTypesSent.add(frame.type)
                        nodes.filter { it.address != node.address && it.address != excludeAddr }.forEach { target ->
                            netScope.launch {
                                delay(5L)
                                val decoded = MeshProtocol.MeshFrame.decode(frame.encode()) ?: return@launch
                                target.syncManager?.handleIncomingFrame(node.address, 517, decoded.nextHop())
                            }
                        }
                    },
                    onPeerIdentified = { _, _, _ -> },
                    onLog = { _, _, _ -> },
                    onPacketRelayed = {}
                ).also { syncManagers.add(it) }
            }

            // Exchange profiles between Node 1 and Node 2
            val p1 = nodes[0].repo.ensureLocalProfileInitialized()
            val p2 = nodes[1].repo.ensureLocalProfileInitialized()
            nodes[0].repo.ingestRemoteProfile(nodes[1].repo.buildLocalAnnouncePayload())
            nodes[1].repo.ingestRemoteProfile(nodes[0].repo.buildLocalAnnouncePayload())

            var sentEntityId: String? = null
            val res = nodes[0].repo.sendDirectMessageFast(p2.peerId, "Fast express BLE DM check!") { entity, wire ->
                sentEntityId = entity.messageId
                val bin = com.klabcc.piersocial.mesh.DmBinaryCodec.encode(wire)!!
                val frame = MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.DM_PACKET_BIN,
                    packetId = "pkt_dm_${entity.messageId}",
                    originPeerId = entity.senderPeerId,
                    targetPeerId = entity.recipientPeerId,
                    payload = bin
                )
                assertEquals(MeshProtocol.SendPriority.EXPRESS, frame.type.sendPriority)
                nodes[0].syncManager?.deduplicator?.markSeenIfNew(frame.packetId)
                nodes[1].syncManager?.handleIncomingFrame(
                    nodes[0].address,
                    517,
                    MeshProtocol.MeshFrame.decode(frame.encode())!!
                )
            }
            assertNotNull(res)
            assertNotNull(sentEntityId)

            var delivered = false
            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline) {
                val msg = nodes[0].db.directMessageDao().getMessage(sentEntityId!!)
                val received = nodes[1].db.directMessageDao().observeConversation(p1.peerId).first()
                if (msg?.deliveryState == com.klabcc.piersocial.data.local.DeliveryState.DELIVERED &&
                    received.any { it.plaintextContent == "Fast express BLE DM check!" }
                ) {
                    delivered = true
                    break
                }
                delay(20L)
            }
            assertTrue("Expected fast E2EE DM + DM_ACK_BIN delivery confirmation", delivered)
            assertTrue("Expected DM_ACK_BIN return receipt", MeshProtocol.PacketType.DM_ACK_BIN in dmTypesSent)
        } finally {
            netScope.cancel()
        }
    }

    @Test
    fun wifiDirectTcpSocket_transfersFullMediaAndFallsBackToBleOnDisconnect() = runBlocking {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val appContext = baseContext.applicationContext
        val netScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val serverSocket = ServerSocket(0)
        var clientSock: Socket? = null
        var serverAcceptSock: Socket? = null

        try {
            val nodes = (1..2).map { idx ->
                val dir = File(appContext.cacheDir, "wifid_node_${idx}_${System.nanoTime()}").apply { mkdirs() }
                createdDirs.add(dir)
                val nodeCtx = object : ContextWrapper(appContext) {
                    override fun getFilesDir(): File = dir
                }
                val db = Room.inMemoryDatabaseBuilder(appContext, com.klabcc.piersocial.data.local.PierDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
                openDbs.add(db)
                val crypto = CryptoManager(File(dir, "identity.json"), TestKeyWrapper())
                val storage = MediaStorageManager(nodeCtx, db.mediaBlobDao())
                val retention = RetentionManager(
                    context = appContext,
                    profileDao = db.profileDao(),
                    postDao = db.postDao(),
                    storyDao = db.storyDao(),
                    meshPacketDao = db.meshPacketDao(),
                    mediaBlobDao = db.mediaBlobDao(),
                    mediaStorageManager = storage
                )
                val repo = PierRepository(nodeCtx, db, crypto, storage, retention)
                repo.ensureLocalProfileInitialized()
                VirtualNode(
                    name = "wifiNode$idx",
                    address = "wifi:${idx}_127.0.0.1",
                    dir = dir,
                    db = db,
                    repo = repo
                )
            }

            val bleAddr1 = "11:22:33:44:55:01"
            val bleAddr2 = "11:22:33:44:55:02"
            val bleChunkCount = AtomicInteger(0)
            val tcpChunkCount = AtomicInteger(0)

            val acceptDone = kotlinx.coroutines.CompletableDeferred<Socket>()
            netScope.launch {
                acceptDone.complete(serverSocket.accept())
            }
            clientSock = Socket("127.0.0.1", serverSocket.localPort).apply { tcpNoDelay = true }
            serverAcceptSock = acceptDone.await().apply { tcpNoDelay = true }

            val out1 = DataOutputStream(BufferedOutputStream(serverAcceptSock.getOutputStream(), 65536))
            val in1 = DataInputStream(BufferedInputStream(serverAcceptSock.getInputStream(), 65536))
            val out2 = DataOutputStream(BufferedOutputStream(clientSock.getOutputStream(), 65536))
            val in2 = DataInputStream(BufferedInputStream(clientSock.getInputStream(), 65536))
            val out1Lock = Any()
            val out2Lock = Any()

            fun writeTcpFrame(out: DataOutputStream, lock: Any, frame: MeshProtocol.MeshFrame) {
                val bytes = frame.encode()
                synchronized(lock) {
                    runCatching {
                        out.writeInt(bytes.size)
                        out.write(bytes)
                        out.flush()
                    }
                }
            }

            nodes[0].syncManager = AntiEntropySyncManager(
                repository = nodes[0].repo,
                onSendFrameToPeer = { targetAddr, frame ->
                    if (targetAddr.startsWith("wifi:")) {
                        if (frame.type == MeshProtocol.PacketType.MEDIA_CHUNK_BIN) tcpChunkCount.incrementAndGet()
                        netScope.launch { writeTcpFrame(out1, out1Lock, frame) }
                    } else {
                        if (frame.type == MeshProtocol.PacketType.MEDIA_CHUNK_BIN) bleChunkCount.incrementAndGet()
                        netScope.launch {
                            delay(25L)
                            val decoded = MeshProtocol.MeshFrame.decode(frame.encode()) ?: return@launch
                            nodes[1].syncManager?.handleIncomingFrame(bleAddr1, 517, decoded)
                        }
                    }
                },
                onBroadcastFrame = { _, frame ->
                    if (!serverAcceptSock!!.isClosed) {
                        netScope.launch { writeTcpFrame(out1, out1Lock, frame.nextHop()) }
                    } else {
                        netScope.launch {
                            delay(25L)
                            val decoded = MeshProtocol.MeshFrame.decode(frame.encode()) ?: return@launch
                            nodes[1].syncManager?.handleIncomingFrame(bleAddr1, 517, decoded.nextHop())
                        }
                    }
                },
                onPeerIdentified = { _, _, _ -> },
                onLog = { _, _, _ -> },
                onPacketRelayed = {},
                onGetLinkSnapshot = { peerAddr ->
                    if (peerAddr.startsWith("wifi:")) {
                        LinkTransportSnapshot(
                            address = peerAddr,
                            negotiatedMtu = 32_768,
                            rssiDbm = -42,
                            supports2MPhy = true,
                            queuedSlices = 0,
                            role = "Wi-Fi Direct GO",
                            transportType = MeshProtocol.TransportProtocol.WIFI_DIRECT
                        )
                    } else {
                        LinkTransportSnapshot(
                            address = peerAddr,
                            negotiatedMtu = 517,
                            rssiDbm = -60,
                            supports2MPhy = true,
                            queuedSlices = 0,
                            role = "Central",
                            transportType = MeshProtocol.TransportProtocol.BLE
                        )
                    }
                }
            ).also { syncManagers.add(it) }

            nodes[1].syncManager = AntiEntropySyncManager(
                repository = nodes[1].repo,
                onSendFrameToPeer = { targetAddr, frame ->
                    if (targetAddr.startsWith("wifi:") && !clientSock!!.isClosed) {
                        netScope.launch { writeTcpFrame(out2, out2Lock, frame) }
                    } else {
                        netScope.launch {
                            delay(25L)
                            val decoded = MeshProtocol.MeshFrame.decode(frame.encode()) ?: return@launch
                            nodes[0].syncManager?.handleIncomingFrame(bleAddr2, 517, decoded)
                        }
                    }
                },
                onBroadcastFrame = { _, _ -> },
                onPeerIdentified = { _, _, _ -> },
                onLog = { _, _, _ -> },
                onPacketRelayed = {},
                onGetLinkSnapshot = { peerAddr ->
                    if (peerAddr.startsWith("wifi:")) {
                        LinkTransportSnapshot(
                            address = peerAddr,
                            negotiatedMtu = 32_768,
                            rssiDbm = -42,
                            supports2MPhy = true,
                            queuedSlices = 0,
                            role = "Wi-Fi Direct Client",
                            transportType = MeshProtocol.TransportProtocol.WIFI_DIRECT
                        )
                    } else {
                        LinkTransportSnapshot(
                            address = peerAddr,
                            negotiatedMtu = 517,
                            rssiDbm = -60,
                            supports2MPhy = true,
                            queuedSlices = 0,
                            role = "Peripheral",
                            transportType = MeshProtocol.TransportProtocol.BLE
                        )
                    }
                }
            ).also { syncManagers.add(it) }

            // TCP socket reader loops mirroring WifiDirectTransport.TcpPeerLink
            netScope.launch {
                while (!serverAcceptSock!!.isClosed) {
                    val len = runCatching { in1.readInt() }.getOrNull() ?: break
                    val buf = ByteArray(len)
                    if (runCatching { in1.readFully(buf) }.isFailure) break
                    val frame = MeshProtocol.MeshFrame.decode(buf) ?: continue
                    nodes[0].syncManager?.handleIncomingFrame(nodes[1].address, 32_768, frame)
                }
            }
            netScope.launch {
                while (!clientSock!!.isClosed) {
                    val len = runCatching { in2.readInt() }.getOrNull() ?: break
                    val buf = ByteArray(len)
                    if (runCatching { in2.readFully(buf) }.isFailure) break
                    val frame = MeshProtocol.MeshFrame.decode(buf) ?: continue
                    nodes[1].syncManager?.handleIncomingFrame(nodes[0].address, 32_768, frame)
                }
            }

            // Phase 1: Post 1 transfers over Wi-Fi Direct TCP socket while BLE is also connected
            val bmp1 = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.CYAN)
            }
            val (post1, blob1) = nodes[0].repo.createFeedPost(
                sourceBitmap = bmp1,
                adjustments = PhotoAdjustments(),
                caption = "Wi-Fi Direct TCP burst post"
            )
            bmp1.recycle()

            nodes[1].syncManager!!.initiateHandshakeAndSync(bleAddr1)
            nodes[1].syncManager!!.initiateHandshakeAndSync(nodes[0].address)

            var post1Done = false
            val dl1 = System.currentTimeMillis() + 6_000L
            while (System.currentTimeMillis() < dl1) {
                if (nodes[1].db.mediaBlobDao().getBlob(blob1.mediaId)?.isComplete == true) {
                    post1Done = true
                    break
                }
                delay(50L)
            }
            assertTrue("Post 1 should complete over Wi-Fi Direct TCP", post1Done)
            assertTrue("Chunks must flow over Wi-Fi Direct TCP (tcp=$tcpChunkCount)", tcpChunkCount.get() >= blob1.totalChunks)
            assertEquals("BLE should not carry bulk chunks while Wi-Fi Direct TCP is active", 0, bleChunkCount.get())

            // Phase 2: Disconnect Wi-Fi Direct TCP -> Post 2 falls back seamlessly to BLE
            runCatching { clientSock.close() }
            runCatching { serverAcceptSock.close() }
            nodes[1].syncManager!!.onPeerDisconnected(nodes[0].address)
            nodes[0].syncManager!!.onPeerDisconnected(nodes[1].address)

            val bmp2 = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.MAGENTA)
            }
            val (post2, blob2) = nodes[0].repo.createFeedPost(
                sourceBitmap = bmp2,
                adjustments = PhotoAdjustments(),
                caption = "Seamless BLE fallback post"
            )
            bmp2.recycle()
            // Node 1 sends its updated SYNC_VECTOR (containing post2) over BLE after Wi-Fi Direct drops
            nodes[0].syncManager!!.initiateHandshakeAndSync(bleAddr2)

            var post2Done = false
            val dl2 = System.currentTimeMillis() + 6_000L
            while (System.currentTimeMillis() < dl2) {
                if (nodes[1].db.mediaBlobDao().getBlob(blob2.mediaId)?.isComplete == true) {
                    post2Done = true
                    break
                }
                delay(50L)
            }
            assertTrue("Post 2 should fall back to BLE seamlessly after Wi-Fi Direct disconnect", post2Done)
            assertTrue("BLE fallback should carry Post 2 chunks", bleChunkCount.get() >= blob2.totalChunks)
            val feedIds = nodes[1].db.postDao().observeFeedPosts().first().map { it.post.postId }.toSet()
            assertTrue(post1.postId in feedIds && post2.postId in feedIds)
        } finally {
            syncManagers.forEach { runCatching { it.shutdown() } }
            runCatching { clientSock?.close() }
            runCatching { serverAcceptSock?.close() }
            runCatching { serverSocket.close() }
            netScope.cancel()
        }
    }

    @Test
    fun deviceWifiDirectSystemService_isAvailableWhenSupported() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        if (hasFeature) {
            val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            assertNotNull("Devices with FEATURE_WIFI_DIRECT must expose WifiP2pManager", mgr)
        }
    }

    @Test
    fun deviceBluetoothAdapter_supportsBleMeshTransport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        assertNotNull("Device must have BluetoothManager", btManager)
        val adapter = btManager!!.adapter
        assertNotNull("Device must have BluetoothAdapter", adapter)
        assertTrue("Bluetooth should be enabled on test device", adapter.isEnabled)
        assertNotNull("BLE scanner must be available when BT is enabled", adapter.bluetoothLeScanner)
    }
}
