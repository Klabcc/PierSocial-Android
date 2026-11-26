package com.klabcc.piersocial

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.klabcc.piersocial.crypto.CryptoManager
import com.klabcc.piersocial.crypto.KeyWrappingProvider
import com.klabcc.piersocial.data.local.PierDatabase
import com.klabcc.piersocial.data.repository.IncomingDmEvent
import com.klabcc.piersocial.data.repository.PierRepository
import com.klabcc.piersocial.data.retention.RetentionManager
import com.klabcc.piersocial.data.storage.MediaStorageManager
import com.klabcc.piersocial.data.storage.PhotoAdjustments
import com.klabcc.piersocial.data.storage.StoryOverlayData
import com.klabcc.piersocial.mesh.AntiEntropySyncManager
import com.klabcc.piersocial.mesh.DmBinaryCodec
import com.klabcc.piersocial.mesh.MeshProtocol
import com.klabcc.piersocial.notifications.DmNotificationHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SocialFeaturesInstrumentedTest {

    private class TestKeyWrapper : KeyWrappingProvider {
        override fun wrap(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
            val iv = ByteArray(12) { 0x11 }
            val cipher = plaintext.map { (it.toInt() xor 0x3A).toByte() }.toByteArray()
            return iv to cipher
        }

        override fun unwrap(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            return ciphertext.map { (it.toInt() xor 0x3A).toByte() }.toByteArray()
        }
    }

    private data class TestPeer(
        val address: String,
        val dir: File,
        val db: PierDatabase,
        val repo: PierRepository,
        var sync: AntiEntropySyncManager? = null
    )

    private val dbs = mutableListOf<PierDatabase>()
    private val dirs = mutableListOf<File>()
    private val syncManagers = mutableListOf<AntiEntropySyncManager>()

    @After
    fun cleanup() {
        syncManagers.forEach { runCatching { it.shutdown() } }
        dbs.forEach { runCatching { it.close() } }
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private suspend fun createTestPeer(name: String, address: String): TestPeer {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val dir = File(appContext.cacheDir, "social_test_${name}_${System.nanoTime()}").apply { mkdirs() }
        dirs.add(dir)
        val nodeCtx = object : ContextWrapper(appContext) {
            override fun getFilesDir(): File = dir
        }
        val db = Room.inMemoryDatabaseBuilder(appContext, PierDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dbs.add(db)
        val crypto = CryptoManager(File(dir, "id.json"), TestKeyWrapper())
        val storage = MediaStorageManager(nodeCtx, db.mediaBlobDao())
        val retention = RetentionManager(
            context = appContext,
            profileDao = db.profileDao(),
            postDao = db.postDao(),
            storyDao = db.storyDao(),
            meshPacketDao = db.meshPacketDao(),
            mediaBlobDao = db.mediaBlobDao(),
            mediaStorageManager = storage,
            noteDao = db.noteDao(),
            directMessageDao = db.directMessageDao()
        )
        val repo = PierRepository(nodeCtx, db, crypto, storage, retention)
        repo.ensureLocalProfileInitialized()
        return TestPeer(address, dir, db, repo)
    }

    @Test
    fun notesPollsReactionsAndRichE2eeDms_syncAndPersistEndToEnd() = runBlocking {
        val alice = createTestPeer("alice", "AA:11:22:33:44:01")
        val bob = createTestPeer("bob", "AA:11:22:33:44:02")
        val peersByAddr = mapOf(alice.address to alice, bob.address to bob)

        fun wireMesh(self: TestPeer, remote: TestPeer): AntiEntropySyncManager {
            return AntiEntropySyncManager(
                repository = self.repo,
                onSendFrameToPeer = { targetAddr, frame ->
                    val dest = peersByAddr[targetAddr] ?: return@AntiEntropySyncManager
                    dest.sync?.handleIncomingFrame(self.address, 517, frame)
                },
                onBroadcastFrame = { excludeAddr, frame ->
                    if (excludeAddr != remote.address) {
                        remote.sync?.handleIncomingFrame(self.address, 517, frame)
                    }
                },
                onPeerIdentified = { _, _, _ -> },
                onLog = { _, _, _ -> },
                onPacketRelayed = {}
            )
        }

        alice.sync = wireMesh(alice, bob).also { syncManagers.add(it) }
        bob.sync = wireMesh(bob, alice).also { syncManagers.add(it) }

        // Exchange ANNOUNCE + SYNC_VECTOR so X25519 keys & profiles exist on both nodes
        alice.sync!!.initiateHandshakeAndSync(bob.address)
        delay(350L)

        // 1. Alice publishes a 24h Note -> syncs to Bob via gossip -> Alice clears it -> clears on Bob
        val (_, noteWire) = alice.repo.publishNote("By the pier right now", "🌊")!!
        bob.sync!!.handleIncomingFrame(
            alice.address,
            517,
            MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.NOTE_RECORD,
                packetId = "note_${noteWire.noteId}",
                originPeerId = noteWire.authorPeerId,
                payload = kotlinx.serialization.json.Json.encodeToString(
                    com.klabcc.piersocial.mesh.NoteWirePayload.serializer(),
                    noteWire
                ).toByteArray(Charsets.UTF_8)
            )
        )
        delay(250L)
        assertTrue(bob.repo.observeActiveNotes().first().any { it.note.text == "By the pier right now" })
        val clearWire = alice.repo.clearLocalNote()
        assertTrue(bob.repo.ingestRemoteNote(clearWire, 1))
        assertTrue(bob.repo.observeActiveNotes().first().none { it.note.authorPeerId == alice.repo.localIdentity.peerId })

        // 2. Alice creates a Post with a Poll & Hashtag -> replicated to Bob -> both vote & react + Bob saves it
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN)
        }
        val (post, _) = alice.repo.createFeedPost(
            sourceBitmap = bmp,
            adjustments = PhotoAdjustments(),
            caption = "Best beach spot? #sunset #beach",
            pollQuestion = "Which spot?",
            pollOptions = listOf("North Pier", "South Cove", "Boardwalk")
        )
        alice.sync!!.initiateHandshakeAndSync(bob.address)
        // Wait for post + chunks to arrive on Bob
        var bobSawPost = false
        for (attempt in 0 until 20) {
            if (bob.repo.feedPostsFlow.first().any { it.post.postId == post.postId }) {
                bobSawPost = true
                break
            }
            delay(150L)
        }
        assertTrue("Expected Bob to sync post with completed media", bobSawPost)

        val bobPost = bob.repo.feedPostsFlow.first().first { it.post.postId == post.postId }.post
        assertEquals("Which spot?", bobPost.pollQuestion)

        // Bob saves the post
        bob.repo.toggleSavePost(post.postId)
        assertTrue(bob.repo.savedPostsFlow.first().any { it.post.postId == post.postId })

        // Alice and Bob vote on the poll + Bob switches vote
        val (_, aliceVoteWire) = alice.repo.voteOnPoll(post.postId, 0)!!
        assertTrue(bob.repo.ingestRemotePollVote(aliceVoteWire))
        val (_, bobVoteWire) = bob.repo.voteOnPoll(post.postId, 2)!!
        assertTrue(alice.repo.ingestRemotePollVote(bobVoteWire))
        assertEquals(2, alice.repo.pollVotesFlow.first().count { it.postId == post.postId })

        // Alice reacts 🔥 to post -> Bob sees 🔥 -> Alice toggles off
        val (_, reactWire) = alice.repo.reactToPost(post.postId, "🔥")!!
        assertTrue(bob.repo.ingestRemotePostReaction(reactWire))
        assertEquals("🔥", bob.repo.postReactionsFlow.first().first { it.postId == post.postId }.emoji)
        delay(5L)
        val (_, unreactWire) = alice.repo.reactToPost(post.postId, "🔥")!!
        assertTrue(bob.repo.ingestRemotePostReaction(unreactWire))
        assertTrue(bob.repo.postReactionsFlow.first().none { it.postId == post.postId && it.emoji == "🔥" })

        // 3. Rich E2EE DM: Disappearing timer + Reply Quote + Reaction via single-slice DmBinaryCodec
        alice.repo.setDmDisappearTimer(bob.repo.localIdentity.peerId, 3_600_000L)
        val sentPair = alice.repo.sendDirectMessageFast(
            recipientPeerId = bob.repo.localIdentity.peerId,
            plaintext = "Meet at North Pier!",
            replyToMessageId = "story_ctx_1",
            replyToPreview = "Story reply"
        ) { entity, wire ->
            val bin = DmBinaryCodec.encode(wire)!!
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.DM_PACKET_BIN,
                packetId = "pkt_dm_${entity.messageId}",
                originPeerId = entity.senderPeerId,
                targetPeerId = entity.recipientPeerId,
                payload = bin
            )
            bob.sync!!.handleIncomingFrame(alice.address, 517, frame)
        }
        assertNotNull(sentPair)
        delay(200L)

        val bobMsgs = bob.repo.observeConversation(alice.repo.localIdentity.peerId).first()
        assertEquals(1, bobMsgs.size)
        val received = bobMsgs[0]
        assertEquals("Meet at North Pier!", received.plaintextContent)
        assertEquals("Story reply", received.replyToPreview)
        assertTrue("Disappearing message must have future expiresAt", received.expiresAt > System.currentTimeMillis())

        // Bob reacts ❤️ to Alice's DM -> updates Alice's DM reactionsJson without inserting extra bubble
        val reacted = bob.repo.sendDmReactionFast(
            recipientPeerId = alice.repo.localIdentity.peerId,
            targetMessageId = received.messageId,
            emoji = "❤️"
        ) { entity, wire ->
            val bin = DmBinaryCodec.encode(wire)!!
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.DM_PACKET_BIN,
                packetId = "pkt_dm_${entity.messageId}",
                originPeerId = entity.senderPeerId,
                targetPeerId = entity.recipientPeerId,
                payload = bin
            )
            alice.sync!!.handleIncomingFrame(bob.address, 517, frame)
        }
        assertTrue(reacted)
        delay(200L)

        val aliceMsgs = alice.repo.observeConversation(bob.repo.localIdentity.peerId).first()
        assertEquals("Reaction must NOT insert duplicate DM message row", 1, aliceMsgs.size)
        assertTrue("Alice must see Bob's ❤️ reaction", aliceMsgs[0].reactionsJson.contains("❤️"))
        assertFalse(aliceMsgs[0].reactionsJson == "{}")
    }

    @Test
    fun incomingDmNotifications_emitDeduplicateSuppressMutedAndBuildIntent() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val alice = createTestPeer("notif_alice", "BB:11:22:33:44:01")
        val bob = createTestPeer("notif_bob", "BB:11:22:33:44:02")
        val peersByAddr = mapOf(alice.address to alice, bob.address to bob)

        fun wireMesh(self: TestPeer, remote: TestPeer): AntiEntropySyncManager {
            return AntiEntropySyncManager(
                repository = self.repo,
                onSendFrameToPeer = { targetAddr, frame ->
                    val dest = peersByAddr[targetAddr] ?: return@AntiEntropySyncManager
                    dest.sync?.handleIncomingFrame(self.address, 517, frame)
                },
                onBroadcastFrame = { excludeAddr, frame ->
                    if (excludeAddr != remote.address) {
                        remote.sync?.handleIncomingFrame(self.address, 517, frame)
                    }
                },
                onPeerIdentified = { _, _, _ -> },
                onLog = { _, _, _ -> },
                onPacketRelayed = {}
            )
        }

        alice.sync = wireMesh(alice, bob).also { syncManagers.add(it) }
        bob.sync = wireMesh(bob, alice).also { syncManagers.add(it) }
        alice.sync!!.initiateHandshakeAndSync(bob.address)
        delay(350L)

        val bobEvents = mutableListOf<IncomingDmEvent>()
        val collectJob = launch {
            bob.repo.incomingDmEvents.collect { bobEvents.add(it) }
        }
        delay(50L)

        var lastFrame: MeshProtocol.MeshFrame? = null
        alice.repo.sendDirectMessageFast(
            recipientPeerId = bob.repo.localIdentity.peerId,
            plaintext = "Ping for push alert!"
        ) { entity, wire ->
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.DM_PACKET_BIN,
                packetId = "pkt_dm_${entity.messageId}",
                originPeerId = entity.senderPeerId,
                targetPeerId = entity.recipientPeerId,
                payload = DmBinaryCodec.encode(wire)!!
            )
            lastFrame = frame
            bob.sync!!.handleIncomingFrame(alice.address, 517, frame)
        }
        delay(250L)
        assertEquals("New DM should emit 1 notification event", 1, bobEvents.size)
        assertEquals("Ping for push alert!", bobEvents[0].previewText)
        assertFalse(bobEvents[0].isReaction)

        // Replaying the exact same DM frame directly into ingestRemoteDm must NOT emit a duplicate notification
        val wireCopy = DmBinaryCodec.decode(lastFrame!!.payload)!!
        bob.repo.ingestRemoteDm(wireCopy, 1)
        delay(100L)
        assertEquals("Duplicate DM replay must not emit second notification", 1, bobEvents.size)

        // Muting Alice suppresses notification events even for brand-new DMs
        bob.repo.toggleMute(alice.repo.localIdentity.peerId)
        alice.repo.sendDirectMessageFast(
            recipientPeerId = bob.repo.localIdentity.peerId,
            plaintext = "Muted sender message"
        ) { entity, wire ->
            val frame = MeshProtocol.MeshFrame(
                type = MeshProtocol.PacketType.DM_PACKET_BIN,
                packetId = "pkt_dm_${entity.messageId}",
                originPeerId = entity.senderPeerId,
                targetPeerId = entity.recipientPeerId,
                payload = DmBinaryCodec.encode(wire)!!
            )
            bob.sync!!.handleIncomingFrame(alice.address, 517, frame)
        }
        delay(250L)
        assertEquals("Muted sender must not emit notification", 1, bobEvents.size)

        // Verify DmNotificationHelper channel creation, posting/canceling (safe across API 32-36), and deep-link Intent
        val helper = DmNotificationHelper(appContext)
        helper.ensureChannel()
        val posted = helper.showIncomingDmNotification(bobEvents[0], "Bob")
        if (helper.hasNotificationPermission()) {
            assertTrue("Expected notification to post when permission is granted", posted)
            assertEquals(1, helper.unreadCountForPeer(alice.repo.localIdentity.peerId))
        }
        helper.cancelForPeer(alice.repo.localIdentity.peerId)
        assertEquals(0, helper.unreadCountForPeer(alice.repo.localIdentity.peerId))

        val openIntent = DmNotificationHelper.buildOpenChatIntent(appContext, alice.repo.localIdentity.peerId)
        assertEquals(alice.repo.localIdentity.peerId, openIntent.getStringExtra(DmNotificationHelper.EXTRA_DM_PEER_ID))

        collectJob.cancel()
    }

    @Test
    fun postLikesReactionsAndStoryLikes_replicateAcrossAllDevicesViaGossipAndAntiEntropy() = runBlocking {
        val alice = createTestPeer("likes_alice", "CC:11:22:33:44:01")
        val bob = createTestPeer("likes_bob", "CC:11:22:33:44:02")
        val carol = createTestPeer("likes_carol", "CC:11:22:33:44:03")

        // Topology: Alice <-> Bob <-> Carol (Bob relays between Alice and Carol)
        val neighbors = mapOf(
            alice.address to listOf(bob),
            bob.address to listOf(alice, carol),
            carol.address to listOf(bob)
        )
        val peersByAddr = mapOf(alice.address to alice, bob.address to bob, carol.address to carol)

        fun wireMesh(self: TestPeer): AntiEntropySyncManager {
            return AntiEntropySyncManager(
                repository = self.repo,
                onSendFrameToPeer = { targetAddr, frame ->
                    peersByAddr[targetAddr]?.sync?.handleIncomingFrame(self.address, 517, frame)
                },
                onBroadcastFrame = { excludeAddr, frame ->
                    neighbors[self.address].orEmpty().forEach { neighbor ->
                        if (neighbor.address != excludeAddr) {
                            neighbor.sync?.handleIncomingFrame(self.address, 517, frame)
                        }
                    }
                },
                onPeerIdentified = { _, _, _ -> },
                onLog = { _, _, _ -> },
                onPacketRelayed = {}
            )
        }

        alice.sync = wireMesh(alice).also { syncManagers.add(it) }
        bob.sync = wireMesh(bob).also { syncManagers.add(it) }
        carol.sync = wireMesh(carol).also { syncManagers.add(it) }

        // Alice creates a Post and a Story
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        val (post, _) = alice.repo.createFeedPost(
            sourceBitmap = bmp,
            adjustments = PhotoAdjustments(),
            caption = "Multi-device likes & reactions check"
        )
        val (story, _) = alice.repo.createStory(
            sourceBitmap = bmp,
            adjustments = PhotoAdjustments(),
            overlay = StoryOverlayData(),
            caption = "My pier story"
        )

        // Sync Alice -> Bob -> Carol so all 3 devices have the post and story
        alice.sync!!.initiateHandshakeAndSync(bob.address)
        delay(400L)
        bob.sync!!.initiateHandshakeAndSync(carol.address)
        var syncedAll = false
        for (attempt in 0 until 25) {
            val bobHasPost = bob.repo.feedPostsFlow.first().any { it.post.postId == post.postId }
            val bobHasStory = bob.repo.observeActiveStories().first().any { it.story.storyId == story.storyId }
            val carolHasPost = carol.repo.feedPostsFlow.first().any { it.post.postId == post.postId }
            val carolHasStory = carol.repo.observeActiveStories().first().any { it.story.storyId == story.storyId }
            if (bobHasPost && bobHasStory && carolHasPost && carolHasStory) {
                syncedAll = true
                break
            }
            delay(150L)
        }
        assertTrue("Expected Bob and Carol to sync Alice's post and story", syncedAll)

        // 1. Bob likes the post, reacts 🔥, and likes the story via live broadcast (relayed through Bob to Alice & Carol)
        val (_, bobPostLikeWire) = bob.repo.toggleLike(post.postId)!!
        val postLikeFrame = MeshProtocol.MeshFrame(
            type = MeshProtocol.PacketType.POST_LIKE,
            packetId = "plike_${bobPostLikeWire.postId}_${bobPostLikeWire.likerPeerId}_${bobPostLikeWire.likedAt}",
            originPeerId = bobPostLikeWire.likerPeerId,
            payload = Json.encodeToString(bobPostLikeWire).toByteArray(Charsets.UTF_8)
        )
        bob.sync!!.deduplicator.markSeenIfNew(postLikeFrame.packetId)
        alice.sync!!.handleIncomingFrame(bob.address, 517, postLikeFrame)
        carol.sync!!.handleIncomingFrame(bob.address, 517, postLikeFrame)

        val (_, bobReactWire) = bob.repo.reactToPost(post.postId, "🔥")!!
        val reactFrame = MeshProtocol.MeshFrame(
            type = MeshProtocol.PacketType.POST_REACTION,
            packetId = "react_${bobReactWire.postId}_${bobReactWire.reactorPeerId}_${bobReactWire.reactedAt}",
            originPeerId = bobReactWire.reactorPeerId,
            payload = Json.encodeToString(bobReactWire).toByteArray(Charsets.UTF_8)
        )
        bob.sync!!.deduplicator.markSeenIfNew(reactFrame.packetId)
        alice.sync!!.handleIncomingFrame(bob.address, 517, reactFrame)
        carol.sync!!.handleIncomingFrame(bob.address, 517, reactFrame)

        val (_, bobStoryLikeWire) = bob.repo.toggleStoryLike(story.storyId)!!
        val storyLikeFrame = MeshProtocol.MeshFrame(
            type = MeshProtocol.PacketType.STORY_LIKE,
            packetId = "slike_${bobStoryLikeWire.storyId}_${bobStoryLikeWire.likerPeerId}_${bobStoryLikeWire.likedAt}",
            originPeerId = bobStoryLikeWire.likerPeerId,
            payload = Json.encodeToString(bobStoryLikeWire).toByteArray(Charsets.UTF_8)
        )
        bob.sync!!.deduplicator.markSeenIfNew(storyLikeFrame.packetId)
        alice.sync!!.handleIncomingFrame(bob.address, 517, storyLikeFrame)
        carol.sync!!.handleIncomingFrame(bob.address, 517, storyLikeFrame)

        delay(250L)

        // Verify Alice (poster) & Carol both received Bob's post like, reaction, and story like
        assertEquals(1, alice.repo.feedPostsFlow.first().first { it.post.postId == post.postId }.post.likeCount)
        assertEquals(1, carol.repo.feedPostsFlow.first().first { it.post.postId == post.postId }.post.likeCount)
        assertEquals(setOf(bob.repo.localIdentity.peerId), alice.repo.postLikesFlow.first().filter { it.postId == post.postId }.map { it.likerPeerId }.toSet())
        assertEquals(setOf(bob.repo.localIdentity.peerId), carol.repo.postLikesFlow.first().filter { it.postId == post.postId }.map { it.likerPeerId }.toSet())
        assertTrue(alice.repo.postReactionsFlow.first().any { it.postId == post.postId && it.emoji == "🔥" })
        assertTrue(carol.repo.postReactionsFlow.first().any { it.postId == post.postId && it.emoji == "🔥" })
        // Poster (Alice) sees Bob's like on her story!
        assertEquals(
            setOf(bob.repo.localIdentity.peerId),
            alice.repo.storyLikesFlow.first().filter { it.storyId == story.storyId }.map { it.likerPeerId }.toSet()
        )

        // 2. Carol likes the post, reacts ❤️, and likes the story locally WITHOUT immediate broadcast (anti-entropy catch-up!)
        carol.repo.ensureLiked(post.postId)
        carol.repo.reactToPost(post.postId, "❤️")
        carol.repo.ensureStoryLiked(story.storyId)

        // Sync Carol <-> Bob, then Bob <-> Alice
        carol.sync!!.initiateHandshakeAndSync(bob.address)
        delay(350L)
        bob.sync!!.initiateHandshakeAndSync(alice.address)
        delay(350L)

        val expectedLikers = setOf(bob.repo.localIdentity.peerId, carol.repo.localIdentity.peerId)
        for (peer in listOf(alice, bob, carol)) {
            val peerPost = peer.repo.feedPostsFlow.first().first { it.post.postId == post.postId }.post
            assertEquals("Expected likeCount == 2 on peer ${peer.address}", 2, peerPost.likeCount)
            assertEquals(
                expectedLikers,
                peer.repo.postLikesFlow.first().filter { it.postId == post.postId }.map { it.likerPeerId }.toSet()
            )
            val emojis = peer.repo.postReactionsFlow.first().filter { it.postId == post.postId }.map { it.emoji }.toSet()
            assertEquals(setOf("🔥", "❤️"), emojis)
            assertEquals(
                expectedLikers,
                peer.repo.storyLikesFlow.first().filter { it.storyId == story.storyId }.map { it.likerPeerId }.toSet()
            )
        }

        // 3. Bob unlikes the post and unlikes the story locally -> anti-entropy converges all 3 devices to count=1 (Carol only)
        bob.repo.toggleLike(post.postId)
        bob.repo.toggleStoryLike(story.storyId)
        bob.sync!!.initiateHandshakeAndSync(alice.address)
        bob.sync!!.initiateHandshakeAndSync(carol.address)
        delay(350L)

        for (peer in listOf(alice, bob, carol)) {
            val peerPost = peer.repo.feedPostsFlow.first().first { it.post.postId == post.postId }.post
            assertEquals("Expected likeCount == 1 after Bob unlike on ${peer.address}", 1, peerPost.likeCount)
            assertEquals(
                setOf(carol.repo.localIdentity.peerId),
                peer.repo.postLikesFlow.first().filter { it.postId == post.postId }.map { it.likerPeerId }.toSet()
            )
            assertEquals(
                setOf(carol.repo.localIdentity.peerId),
                peer.repo.storyLikesFlow.first().filter { it.storyId == story.storyId }.map { it.likerPeerId }.toSet()
            )
        }
    }
}
