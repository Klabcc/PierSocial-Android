package com.klabcc.piersocial

import com.klabcc.piersocial.crypto.CryptoManager
import com.klabcc.piersocial.crypto.KeyWrappingProvider
import com.klabcc.piersocial.data.storage.AudioPcmProcessor
import com.klabcc.piersocial.data.storage.MediaStorageManager
import com.klabcc.piersocial.data.storage.MonoMp3Encoder
import com.klabcc.piersocial.data.storage.PureAudioContainerDecoder
import com.klabcc.piersocial.data.storage.StoryAudioRecorder
import com.klabcc.piersocial.mesh.DmAckBinaryCodec
import com.klabcc.piersocial.mesh.DmAckPayload
import com.klabcc.piersocial.mesh.DmBinaryCodec
import com.klabcc.piersocial.mesh.DmRichCodec
import com.klabcc.piersocial.mesh.DmWirePayload
import com.klabcc.piersocial.mesh.LinkProbeAckPayload
import com.klabcc.piersocial.mesh.LinkProbePayload
import com.klabcc.piersocial.mesh.MediaChunkBinary
import com.klabcc.piersocial.mesh.MediaChunkBinaryCodec
import com.klabcc.piersocial.mesh.MeshProtocol
import com.klabcc.piersocial.mesh.NoteWirePayload
import com.klabcc.piersocial.mesh.OptimalPeerSelector
import com.klabcc.piersocial.mesh.PacketDeduplicator
import com.klabcc.piersocial.mesh.PeerLinkMetrics
import com.klabcc.piersocial.mesh.PollVoteWirePayload
import com.klabcc.piersocial.mesh.PostLikeWirePayload
import com.klabcc.piersocial.mesh.PostReactionWirePayload
import com.klabcc.piersocial.mesh.PostWirePayload
import com.klabcc.piersocial.mesh.StoryLikeWirePayload
import com.klabcc.piersocial.mesh.StoryWirePayload
import com.klabcc.piersocial.mesh.SyncRequestPayload
import com.klabcc.piersocial.mesh.SyncVectorPayload
import com.klabcc.piersocial.mesh.WifiDirectOfferPayload
import com.klabcc.piersocial.notifications.DmNotificationHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import org.gagravarr.ogg.OggFile
import org.gagravarr.opus.OpusAudioData
import org.gagravarr.opus.OpusInfo
import org.gagravarr.opus.OpusTags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class PierMeshAndCryptoTest {

    private class InMemoryKeyWrapper : KeyWrappingProvider {
        override fun wrap(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
            val iv = ByteArray(12) { 0x42 }
            val cipher = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            return iv to cipher
        }

        override fun unwrap(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            return ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    @Test
    fun ed25519_signAndVerify_succeedsAndRejectsTampering() {
        val tempFile = File.createTempFile("pier_id", ".json").apply { delete() }
        val crypto = CryptoManager(tempFile, InMemoryKeyWrapper())
        val alice = crypto.getOrCreateIdentity()
        val message = "post_123|${alice.peerId}|med_abc|sha|1700000000|Hello mesh"

        val sig = crypto.signString(message, alice.ed25519PrivateKey)
        val edPubB64 = CryptoManager.encodeBase64(alice.ed25519PublicKey)

        assertTrue(crypto.verifyString(message, sig, edPubB64))
        assertFalse(crypto.verifyString("$message!", sig, edPubB64))

        // Reloading from wrapped identity file yields identical keys
        val reloaded = CryptoManager(tempFile, InMemoryKeyWrapper()).getOrCreateIdentity()
        assertEquals(alice.peerId, reloaded.peerId)
        assertArrayEquals(alice.ed25519PublicKey, reloaded.ed25519PublicKey)
        tempFile.delete()
    }

    @Test
    fun x25519_e2eeDirectMessage_encryptsDecryptsAndDetectsWrongRecipient() {
        val crypto = CryptoManager(null, InMemoryKeyWrapper())
        val alice = crypto.generateNewIdentityKeys()
        val bob = crypto.generateNewIdentityKeys()
        val eve = crypto.generateNewIdentityKeys()

        val secretText = "Meet at the pier at 19:30 • 3 hops max"
        val encrypted = crypto.encryptDirectMessage(secretText, bob.x25519PublicKey, alice)

        val decryptedByBob = crypto.decryptDirectMessage(encrypted, alice.ed25519PublicKey, bob)
        assertEquals(secretText, decryptedByBob)

        val decryptedByEve = crypto.decryptDirectMessage(encrypted, alice.ed25519PublicKey, eve)
        assertNull(decryptedByEve)
    }

    @Test
    fun meshFrame_encodeDecodeAndGattSlicing_roundTripsCleanly() {
        val payload = ByteArray(1200) { (it % 251).toByte() }
        val frame = MeshProtocol.MeshFrame(
            type = MeshProtocol.PacketType.POST_RECORD,
            packetId = "pkt_abc123",
            originPeerId = "peer_alpha",
            targetPeerId = "",
            ttl = 5,
            hopCount = 1,
            timestamp = 1764000000L,
            payload = payload
        )

        val encoded = frame.encode()
        val slices = MeshProtocol.sliceForGattTransport(encoded, mtu = 185, sequenceId = 7)
        assertTrue(slices.size > 1)

        // Reassemble slices (strip 5-byte slice header)
        val reassembled = slices.flatMap { it.drop(5) }.toByteArray()
        val decoded = MeshProtocol.MeshFrame.decode(reassembled)
        assertNotNull(decoded)
        assertEquals(frame.packetId, decoded!!.packetId)
        assertEquals(frame.originPeerId, decoded.originPeerId)
        assertEquals(5, decoded.ttl)
        assertEquals(1, decoded.hopCount)
        assertArrayEquals(payload, decoded.payload)

        val nextHop = decoded.nextHop()
        assertEquals(4, nextHop.ttl)
        assertEquals(2, nextHop.hopCount)
    }

    @Test
    fun packetDeduplicator_dropsDuplicatesAndTracksBloomFilter() {
        val dedup = PacketDeduplicator(maxExactEntries = 64, bloomBits = 1024)
        assertTrue(dedup.markSeenIfNew("pkt_1"))
        assertFalse(dedup.markSeenIfNew("pkt_1"))
        assertTrue(dedup.mightContain("pkt_1"))
        assertTrue(dedup.markSeenIfNew("pkt_2"))
    }

    @Test
    fun mtuChunkPayloadSize_respectsBounds() {
        assertEquals(120, MediaStorageManager.computeMtuChunkPayloadSize(23))
        assertEquals(460, MediaStorageManager.computeMtuChunkPayloadSize(512))
        assertEquals(200, MediaStorageManager.computeMtuChunkPayloadSize(248))
    }

    @Test
    fun storyWirePayload_withAudio_signsAndVerifiesCleanly() {
        val crypto = CryptoManager(null, InMemoryKeyWrapper())
        val alice = crypto.generateNewIdentityKeys()
        val edPubB64 = CryptoManager.encodeBase64(alice.ed25519PublicKey)

        val wire = StoryWirePayload(
            storyId = "story_audio_1",
            authorPeerId = alice.peerId,
            authorEd25519PubKey = edPubB64,
            mediaId = "smed_123",
            mediaByteSize = 18000L,
            mediaChunkSize = 384,
            mediaTotalChunks = 47,
            mediaSha256Hex = "abcd1234",
            caption = "Audio story",
            createdAt = 1764000000L,
            expiresAt = 1764086400L,
            signature = "",
            audioMediaId = "saud_123",
            audioByteSize = 16000L,
            audioChunkSize = 384,
            audioTotalChunks = 42,
            audioSha256Hex = "ef567890",
            audioDurationMs = 4900
        )
        val sig = crypto.signString(wire.canonicalSigningString(), alice.ed25519PrivateKey)
        assertTrue(crypto.verifyString(wire.canonicalSigningString(), sig, edPubB64))
        assertFalse(
            crypto.verifyString(
                wire.copy(audioSha256Hex = "tampered").canonicalSigningString(),
                sig,
                edPubB64
            )
        )
    }

    @Test
    fun postWirePayload_withoutAndWithAudio_signsAndVerifiesCleanly() {
        val crypto = CryptoManager(null, InMemoryKeyWrapper())
        val alice = crypto.generateNewIdentityKeys()
        val edPubB64 = CryptoManager.encodeBase64(alice.ed25519PublicKey)

        val photoOnly = PostWirePayload(
            postId = "post_1",
            authorPeerId = alice.peerId,
            authorEd25519PubKey = edPubB64,
            caption = "Sunset",
            mediaId = "med_1",
            mediaByteSize = 14000L,
            mediaChunkSize = 384,
            mediaTotalChunks = 37,
            mediaSha256Hex = "sha_img_1",
            width = 1080,
            height = 1080,
            createdAt = 1764000000L,
            signature = ""
        )
        val photoSig = crypto.signString(photoOnly.canonicalSigningString(), alice.ed25519PrivateKey)
        assertTrue(crypto.verifyString(photoOnly.canonicalSigningString(), photoSig, edPubB64))

        val withAudio = photoOnly.copy(
            postId = "post_2",
            audioMediaId = "paud_2",
            audioByteSize = 18500L,
            audioChunkSize = 384,
            audioTotalChunks = 49,
            audioSha256Hex = "sha_aud_2",
            audioDurationMs = 4850
        )
        val audioSig = crypto.signString(withAudio.canonicalSigningString(), alice.ed25519PrivateKey)
        assertTrue(crypto.verifyString(withAudio.canonicalSigningString(), audioSig, edPubB64))
        assertFalse(
            crypto.verifyString(
                withAudio.copy(audioSha256Hex = "tampered_audio").canonicalSigningString(),
                audioSig,
                edPubB64
            )
        )
        assertTrue(MediaStorageManager.isAudioMediaId("paud_2"))
        assertTrue(MediaStorageManager.isAudioMediaId("saud_2"))
        assertFalse(MediaStorageManager.isAudioMediaId("med_1"))
    }

    @Test
    fun audioTrimWindow_clampsAndSelectsPortionCleanly() {
        val shortTrack = StoryAudioRecorder.computeTrimWindow(totalDurationMs = 3200, requestedStartMs = 1000)
        assertEquals(0, shortTrack.startMs)
        assertEquals(3200, shortTrack.durationMs)
        assertEquals(3200, shortTrack.endMs)

        val exactTrack = StoryAudioRecorder.computeTrimWindow(totalDurationMs = 5000, requestedStartMs = 500)
        assertEquals(0, exactTrack.startMs)
        assertEquals(5000, exactTrack.durationMs)
        assertEquals(5000, exactTrack.endMs)

        val midTrack = StoryAudioRecorder.computeTrimWindow(totalDurationMs = 120_000, requestedStartMs = 45_000)
        assertEquals(45_000, midTrack.startMs)
        assertEquals(5000, midTrack.durationMs)
        assertEquals(50_000, midTrack.endMs)

        val clampedEnd = StoryAudioRecorder.computeTrimWindow(totalDurationMs = 120_000, requestedStartMs = 118_000)
        assertEquals(115_000, clampedEnd.startMs)
        assertEquals(5000, clampedEnd.durationMs)
        assertEquals(120_000, clampedEnd.endMs)

        val clampedNeg = StoryAudioRecorder.computeTrimWindow(totalDurationMs = 20_000, requestedStartMs = -500)
        assertEquals(0, clampedNeg.startMs)
        assertEquals(5000, clampedNeg.durationMs)
        assertEquals(5000, clampedNeg.endMs)
    }

    @Test
    fun sampleRateAndMonoMp3Encoder_downmixesAndProducesMonoMpegFrames() {
        assertEquals(48000, AudioPcmProcessor.selectValidMp3SampleRate(96000))
        assertEquals(48000, AudioPcmProcessor.selectValidMp3SampleRate(192000))
        assertEquals(44100, AudioPcmProcessor.selectValidMp3SampleRate(88200))
        assertEquals(44100, AudioPcmProcessor.selectValidMp3SampleRate(44100))
        assertEquals(22050, AudioPcmProcessor.selectValidMp3SampleRate(22050))
        assertEquals(8000, AudioPcmProcessor.selectValidMp3SampleRate(6000))

        // Stereo 96kHz PCM -> mono resampled 48kHz -> mono MP3
        val frames96k = 96000 * 3
        val interleaved = FloatArray(frames96k * 2) { idx ->
            val frame = idx / 2
            val freq = if (idx % 2 == 0) 440.0 else 660.0
            (sin(2.0 * PI * freq * frame / 96000.0) * 0.5).toFloat()
        }
        val mono96k = AudioPcmProcessor.mixInterleavedFloatsToMono(interleaved, frames96k, 2)
        val outMp3 = File.createTempFile("unit_mono_", ".mp3").apply { deleteOnExit() }
        val encoded = MonoMp3Encoder.encodeMonoPcmToMp3File(mono96k, 96000, outMp3)
        assertNotNull(encoded)
        assertEquals(48000, encoded!!.sampleRate)
        assertMonoMp3Header(outMp3)
        assertTrue(MediaStorageManager.isAudioMediaId("paud_abc"))
    }

    @Test
    fun pureDecoders_flacWavAiffAndOpus_allCompressToMonoMp3() {
        // 1. Stereo FLAC -> inspect & decode [2.0s..7.0s] -> mono MP3
        val flacFile = File.createTempFile("unit_track_", ".flac").apply { deleteOnExit() }
        writeTestStereoFlac(flacFile, durationSeconds = 8, sampleRate = 44100)
        val flacMeta = PureAudioContainerDecoder.inspectTrackMetadata(flacFile)
        assertNotNull(flacMeta)
        assertEquals("FLAC", flacMeta!!.codecLabel)
        assertTrue("Unexpected FLAC duration ${flacMeta.durationMs}", flacMeta.durationMs in 7500..8500)
        val flacPcm = PureAudioContainerDecoder.decodeSegment(flacFile, startMs = 2000, durationMs = 5000)
        assertNotNull(flacPcm)
        assertEquals(44100, flacPcm!!.sampleRate)
        assertTrue(flacPcm.durationMs in 4500..5100)
        val flacMp3 = File.createTempFile("unit_flac_out_", ".mp3").apply { deleteOnExit() }
        assertNotNull(MonoMp3Encoder.encodeMonoPcmToMp3File(flacPcm.samples, flacPcm.sampleRate, flacMp3))
        assertMonoMp3Header(flacMp3)

        // 2. Stereo 24-bit WAV -> decode -> mono MP3
        val wavFile = File.createTempFile("unit_track_", ".wav").apply { deleteOnExit() }
        writeTestStereo24BitWav(wavFile, durationSeconds = 7, sampleRate = 48000)
        val wavMeta = PureAudioContainerDecoder.inspectTrackMetadata(wavFile)
        assertNotNull(wavMeta)
        assertEquals("WAV", wavMeta!!.codecLabel)
        assertTrue(wavMeta.durationMs in 6800..7200)
        val wavPcm = PureAudioContainerDecoder.decodeSegment(wavFile, startMs = 1500, durationMs = 5000)
        assertNotNull(wavPcm)
        val wavMp3 = File.createTempFile("unit_wav_out_", ".mp3").apply { deleteOnExit() }
        assertNotNull(MonoMp3Encoder.encodeMonoPcmToMp3File(wavPcm!!.samples, wavPcm.sampleRate, wavMp3))
        assertMonoMp3Header(wavMp3)

        // 3. Stereo 16-bit AIFF -> decode -> mono MP3
        val aiffFile = File.createTempFile("unit_track_", ".aiff").apply { deleteOnExit() }
        writeTestStereoAiff(aiffFile, durationSeconds = 7, sampleRate = 44100)
        val aiffMeta = PureAudioContainerDecoder.inspectTrackMetadata(aiffFile)
        assertNotNull(aiffMeta)
        assertEquals("AIFF", aiffMeta!!.codecLabel)
        val aiffPcm = PureAudioContainerDecoder.decodeSegment(aiffFile, startMs = 1000, durationMs = 5000)
        assertNotNull(aiffPcm)
        val aiffMp3 = File.createTempFile("unit_aiff_out_", ".mp3").apply { deleteOnExit() }
        assertNotNull(MonoMp3Encoder.encodeMonoPcmToMp3File(aiffPcm!!.samples, aiffPcm.sampleRate, aiffMp3))
        assertMonoMp3Header(aiffMp3)

        // 4. Stereo Ogg Opus -> decode -> mono MP3
        val opusFile = File.createTempFile("unit_track_", ".opus").apply { deleteOnExit() }
        writeTestStereoOpus(opusFile, durationSeconds = 8)
        val opusMeta = PureAudioContainerDecoder.inspectTrackMetadata(opusFile)
        assertNotNull(opusMeta)
        assertEquals("Opus", opusMeta!!.codecLabel)
        assertTrue("Unexpected Opus duration ${opusMeta.durationMs}", opusMeta.durationMs in 7500..8500)
        val opusPcm = PureAudioContainerDecoder.decodeSegment(opusFile, startMs = 2000, durationMs = 5000)
        assertNotNull(opusPcm)
        assertEquals(48000, opusPcm!!.sampleRate)
        val opusMp3 = File.createTempFile("unit_opus_out_", ".mp3").apply { deleteOnExit() }
        assertNotNull(MonoMp3Encoder.encodeMonoPcmToMp3File(opusPcm.samples, opusPcm.sampleRate, opusMp3))
        assertMonoMp3Header(opusMp3)
    }

    @Test
    fun optimalPeerSelector_ranksPeersByMtuPhyRttRssiAndLoad_andStripesInParallel() {
        val selector = OptimalPeerSelector(stallTimeoutMs = 3_500L, maxParallelPeersPerMedia = 3)
        val now = 1_000_000L

        // Node A: MTU=512, 2M PHY, fast RTT, strong RSSI, idle -> optimal primary source
        selector.updateLinkMetrics("AA:AA:AA:AA:AA:01") {
            PeerLinkMetrics(
                peerAddress = "AA:AA:AA:AA:AA:01",
                peerId = "peer_a",
                negotiatedMtu = 512,
                rssiDbm = -52,
                rttMs = 20L,
                supports2MPhy = true,
                remoteActiveUploads = 0,
                remoteQueuedSlices = 0
            )
        }
        // Node B: MTU=512, 1M PHY, higher RTT, busy (activeUploads=2, queued=80) -> lower weight
        selector.updateLinkMetrics("AA:AA:AA:AA:AA:02") {
            PeerLinkMetrics(
                peerAddress = "AA:AA:AA:AA:AA:02",
                peerId = "peer_b",
                negotiatedMtu = 512,
                rssiDbm = -64,
                rttMs = 55L,
                supports2MPhy = false,
                remoteActiveUploads = 2,
                remoteQueuedSlices = 80
            )
        }
        // Node C: MTU=360, 2M PHY, moderate RSSI, idle -> included in parallel pool
        selector.updateLinkMetrics("AA:AA:AA:AA:AA:03") {
            PeerLinkMetrics(
                peerAddress = "AA:AA:AA:AA:AA:03",
                peerId = "peer_c",
                negotiatedMtu = 360,
                rssiDbm = -68,
                rttMs = 40L,
                supports2MPhy = true,
                remoteActiveUploads = 0,
                remoteQueuedSlices = 5
            )
        }
        // Node D: MTU=23, weak RSSI, high RTT -> straggler excluded (< 30% of best score)
        selector.updateLinkMetrics("AA:AA:AA:AA:AA:04") {
            PeerLinkMetrics(
                peerAddress = "AA:AA:AA:AA:AA:04",
                peerId = "peer_d",
                negotiatedMtu = 23,
                rssiDbm = -94,
                rttMs = 320L,
                supports2MPhy = false
            )
        }

        val mediaId = "med_parallel_test"
        listOf(
            "AA:AA:AA:AA:AA:01",
            "AA:AA:AA:AA:AA:02",
            "AA:AA:AA:AA:AA:03",
            "AA:AA:AA:AA:AA:04"
        ).forEach { addr -> selector.updatePeerMediaInventory(addr, listOf(mediaId)) }

        val missing = (0 until 24).toList()
        val plan = selector.planParallelDownloads(
            missingChunksByMedia = mapOf(mediaId to missing),
            nowMs = now
        )

        val scoreA = plan.scoresByAddress["AA:AA:AA:AA:AA:01"]!!
        val scoreB = plan.scoresByAddress["AA:AA:AA:AA:AA:02"]!!
        val scoreC = plan.scoresByAddress["AA:AA:AA:AA:AA:03"]!!
        val scoreD = plan.scoresByAddress["AA:AA:AA:AA:AA:04"]!!
        assertTrue("Expected idle 2M PHY Node A ($scoreA) > busy Node B ($scoreB)", scoreA > scoreB)
        assertTrue("Expected Node C ($scoreC) > straggler Node D ($scoreD)", scoreC > scoreD)
        assertTrue("Expected Node A ($scoreA) >> straggler Node D ($scoreD)", scoreA > scoreD * 5.0)
        assertTrue("AA:AA:AA:AA:AA:01" in plan.optimalAddresses)

        // Verify disjoint chunk striping across fast nodes while excluding straggler Node D
        assertFalse(plan.requestsByPeer.containsKey("AA:AA:AA:AA:AA:04"))
        val assignedA = plan.requestsByPeer["AA:AA:AA:AA:AA:01"]?.get(mediaId).orEmpty()
        val assignedB = plan.requestsByPeer["AA:AA:AA:AA:AA:02"]?.get(mediaId).orEmpty()
        val assignedC = plan.requestsByPeer["AA:AA:AA:AA:AA:03"]?.get(mediaId).orEmpty()
        assertTrue(assignedA.size > assignedB.size)
        assertTrue(assignedC.isNotEmpty())
        val combined = assignedA + assignedB + assignedC
        assertEquals(24, combined.size)
        assertEquals(24, combined.toSet().size)
    }

    @Test
    fun optimalPeerSelector_deduplicatesDualLinksAndStealsStalledChunks() {
        val selector = OptimalPeerSelector(stallTimeoutMs = 2_000L, maxParallelPeersPerMedia = 2)
        val t0 = 5_000_000L

        // Two addresses belonging to the SAME remote peerId ("peer_same")
        selector.updateLinkMetrics("BB:00:00:00:00:01") {
            PeerLinkMetrics("BB:00:00:00:00:01", peerId = "peer_same", role = "Central", negotiatedMtu = 512, rttMs = 20L, supports2MPhy = true)
        }
        selector.updateLinkMetrics("BB:00:00:00:00:02") {
            PeerLinkMetrics("BB:00:00:00:00:02", peerId = "peer_same", role = "Peripheral", negotiatedMtu = 185, rttMs = 60L, supports2MPhy = false)
        }
        selector.updateLinkMetrics("CC:00:00:00:00:03") {
            PeerLinkMetrics("CC:00:00:00:00:03", peerId = "peer_other", role = "Central", negotiatedMtu = 512, rttMs = 35L, supports2MPhy = true)
        }

        val mediaId = "paud_swarm_1"
        selector.updatePeerMediaInventory("BB:00:00:00:00:01", listOf(mediaId))
        selector.updatePeerMediaInventory("BB:00:00:00:00:02", listOf(mediaId))
        selector.updatePeerMediaInventory("CC:00:00:00:00:03", listOf(mediaId))

        val plan1 = selector.planParallelDownloads(mapOf(mediaId to (0 until 10).toList()), nowMs = t0)
        // Must pick higher-scoring link for peer_same and NOT duplicate-request BB:..:02
        assertTrue(plan1.requestsByPeer.containsKey("BB:00:00:00:00:01"))
        assertFalse(plan1.requestsByPeer.containsKey("BB:00:00:00:00:02"))
        assertTrue(plan1.requestsByPeer.containsKey("CC:00:00:00:00:03"))

        // While within stall window, re-planning returns empty (nothing duplicate-requested)
        val planImmediate = selector.planParallelDownloads(mapOf(mediaId to (0 until 10).toList()), nowMs = t0 + 500L)
        assertTrue(planImmediate.requestsByPeer.isEmpty())

        // After disconnecting BB:..:01, its in-flight chunks are immediately stolen by CC:..:03
        val chunksStolenByOther = plan1.requestsByPeer["BB:00:00:00:00:01"]!![mediaId]!!
        selector.onPeerDisconnected("BB:00:00:00:00:01")
        selector.onPeerDisconnected("BB:00:00:00:00:02")
        val planAfterDrop = selector.planParallelDownloads(
            missingChunksByMedia = mapOf(mediaId to chunksStolenByOther),
            nowMs = t0 + 600L
        )
        assertEquals(chunksStolenByOther, planAfterDrop.requestsByPeer["CC:00:00:00:00:03"]?.get(mediaId))
    }

    @Test
    fun mediaChunkBinaryCodec_roundTripsAndFitsSingle512MtuSlice() {
        val raw = ByteArray(MediaStorageManager.DEFAULT_CHUNK_SIZE) { (it * 7 + 3).toByte() }
        val bin = MediaChunkBinary(
            mediaId = "med_1234567890abcdef",
            ownerPeerId = "peer_alpha_001122",
            chunkIndex = 12,
            totalChunks = 48,
            chunkSize = raw.size,
            totalBytes = 18_432L,
            sha256Hex = "a".repeat(64),
            data = raw
        )
        val encodedPayload = MediaChunkBinaryCodec.encode(bin)
        val frame = MeshProtocol.MeshFrame(
            type = MeshProtocol.PacketType.MEDIA_CHUNK_BIN,
            packetId = "chk_${bin.mediaId}_12",
            originPeerId = bin.ownerPeerId,
            targetPeerId = "peer_beta",
            payload = encodedPayload
        )
        val slices = MeshProtocol.sliceForGattTransport(frame.encode(), mtu = 517, sequenceId = 9)
        // Binary encoding + raw 32B SHA-256 avoids Base64/JSON overhead so a full chunk fits inside a single 495B ATT write!
        assertEquals(1, slices.size)

        val decodedFrame = MeshProtocol.MeshFrame.decode(slices[0].drop(5).toByteArray())
        assertNotNull(decodedFrame)
        assertEquals(MeshProtocol.PacketType.MEDIA_CHUNK_BIN, decodedFrame!!.type)
        assertTrue(decodedFrame.type.isBulkData)
        val decodedChunk = MediaChunkBinaryCodec.decode(decodedFrame.payload)
        assertNotNull(decodedChunk)
        assertEquals(bin.mediaId, decodedChunk!!.mediaId)
        assertEquals(12, decodedChunk.chunkIndex)
        assertEquals(48, decodedChunk.totalChunks)
        assertEquals(bin.sha256Hex, decodedChunk.sha256Hex)
        assertArrayEquals(raw, decodedChunk.data)
    }

    @Test
    fun linkProbeAndParallelSyncPayloads_serializeAndDeserializeCleanly() {
        val json = Json { ignoreUnknownKeys = true }
        val probe = LinkProbePayload("prb_1", "peer_a", 123456L, activeUploads = 1, queuedSlices = 14, connectedPeers = 3, negotiatedMtu = 512, supports2MPhy = true, measuredThroughputBps = 48_000L)
        val ack = LinkProbeAckPayload("prb_1", "peer_b", 123456L, activeUploads = 0, queuedSlices = 2, connectedPeers = 2, negotiatedMtu = 512, supports2MPhy = true, measuredThroughputBps = 55_000L)
        val vec = SyncVectorPayload(
            senderPeerId = "peer_b",
            profileIds = listOf("peer_b"),
            postIds = listOf("post_1"),
            storyIds = emptyList(),
            completeMediaIds = listOf("med_1", "paud_1"),
            pendingDmPacketIds = emptyList(),
            activeOutboundJobs = 0,
            queuedOutboundSlices = 2,
            connectedPeerCount = 3,
            negotiatedMtu = 512,
            measuredGoodputBps = 55_000L,
            supports2MPhy = true
        )
        val req = SyncRequestPayload(
            requesterPeerId = "peer_d",
            wantedMediaChunkIndices = mapOf("med_1" to listOf(0, 1, 2, 3), "paud_1" to listOf(4, 5))
        )

        assertEquals(probe, json.decodeFromString<LinkProbePayload>(json.encodeToString(probe)))
        assertEquals(ack, json.decodeFromString<LinkProbeAckPayload>(json.encodeToString(ack)))
        assertEquals(vec, json.decodeFromString<SyncVectorPayload>(json.encodeToString(vec)))
        assertEquals(req, json.decodeFromString<SyncRequestPayload>(json.encodeToString(req)))
    }

    @Test
    fun dmBinaryCodec_andAckCodec_roundTripAndFitSingle512MtuSliceWithExpressPriority() {
        val alice = CryptoManager(File.createTempFile("alice_dm", ".json").apply { deleteOnExit() }, InMemoryKeyWrapper()).getOrCreateIdentity()
        val bob = CryptoManager(File.createTempFile("bob_dm", ".json").apply { deleteOnExit() }, InMemoryKeyWrapper()).getOrCreateIdentity()
        val crypto = CryptoManager(File.createTempFile("tmp_dm", ".json").apply { deleteOnExit() }, InMemoryKeyWrapper())

        val plaintext = "Hey! Meet at Pier 39 in 5 mins? Testing ultra-fast single-slice E2EE BLE DM!"
        val enc = crypto.encryptDirectMessage(plaintext, bob.x25519PublicKey, alice)
        val wire = DmWirePayload(
            messageId = "dm_0123456789abcdef",
            senderPeerId = alice.peerId,
            senderEd25519PubKey = CryptoManager.encodeBase64(alice.ed25519PublicKey),
            recipientPeerId = bob.peerId,
            encryptedPayloadJson = Json.encodeToString(enc),
            timestamp = 1_750_000_000_000L
        )
        val bin = DmBinaryCodec.encode(wire)
        assertNotNull(bin)
        val dmFrame = MeshProtocol.MeshFrame(
            type = MeshProtocol.PacketType.DM_PACKET_BIN,
            packetId = "pkt_dm_${wire.messageId}",
            originPeerId = wire.senderPeerId,
            targetPeerId = wire.recipientPeerId,
            payload = bin!!
        )
        assertEquals(MeshProtocol.SendPriority.EXPRESS, dmFrame.type.sendPriority)
        assertTrue(dmFrame.type.isExpressDm)
        assertTrue(dmFrame.type.isFastNoResponse)
        assertEquals(MeshProtocol.SendPriority.CONTROL, MeshProtocol.PacketType.SYNC_VECTOR.sendPriority)
        assertEquals(MeshProtocol.SendPriority.BULK, MeshProtocol.PacketType.MEDIA_CHUNK_BIN.sendPriority)

        val dmSlices = MeshProtocol.sliceForGattTransport(dmFrame.encode(), mtu = 517, sequenceId = 3)
        assertEquals("Encrypted DM up to ~190 bytes must fit in a single 512-MTU GATT slice", 1, dmSlices.size)

        val decodedFrame = MeshProtocol.MeshFrame.decode(dmSlices[0].drop(5).toByteArray())!!
        val decodedWire = DmBinaryCodec.decode(decodedFrame.payload)!!
        assertEquals(wire.messageId, decodedWire.messageId)
        assertEquals(wire.senderPeerId, decodedWire.senderPeerId)
        assertEquals(wire.recipientPeerId, decodedWire.recipientPeerId)

        val ack = DmAckPayload(wire.messageId, alice.peerId, bob.peerId, hopCount = 0, deliveredAt = 1_750_000_000_120L)
        val ackFrame = MeshProtocol.MeshFrame(
            type = MeshProtocol.PacketType.DM_ACK_BIN,
            packetId = "ack_${wire.messageId}",
            originPeerId = bob.peerId,
            targetPeerId = alice.peerId,
            payload = DmAckBinaryCodec.encode(ack)
        )
        assertEquals(MeshProtocol.SendPriority.EXPRESS, ackFrame.type.sendPriority)
        val ackSlices = MeshProtocol.sliceForGattTransport(ackFrame.encode(), mtu = 517, sequenceId = 4)
        assertEquals(1, ackSlices.size)
        val decodedAck = DmAckBinaryCodec.decode(MeshProtocol.MeshFrame.decode(ackSlices[0].drop(5).toByteArray())!!.payload)
        assertEquals(ack, decodedAck)
    }

    @Test
    fun wifiDirectOfferPayload_roundTripsOverControlMeshFrame() {
        val offer = WifiDirectOfferPayload(
            senderPeerId = "peer_go_1234",
            networkName = "DIRECT-XY-PierSocial",
            passphrase = "secret-passphrase-99",
            hostIp = "192.168.49.1",
            tcpPort = 43210,
            deviceAddress = "02:11:22:33:44:55",
            timestamp = 1_750_000_005_000L
        )
        val frame = MeshProtocol.MeshFrame(
            type = MeshProtocol.PacketType.WIFI_DIRECT_OFFER,
            packetId = "wdo_peer_go_1234",
            originPeerId = offer.senderPeerId,
            targetPeerId = "peer_cli_5678",
            ttl = 1,
            payload = Json.encodeToString(offer).toByteArray(Charsets.UTF_8)
        )
        assertEquals(MeshProtocol.SendPriority.CONTROL, frame.type.sendPriority)
        assertFalse(frame.type.isBulkData)
        assertFalse(frame.type.isExpressDm)

        val decodedFrame = MeshProtocol.MeshFrame.decode(frame.encode())
        assertNotNull(decodedFrame)
        assertEquals(MeshProtocol.PacketType.WIFI_DIRECT_OFFER, decodedFrame!!.type)
        assertEquals(1, decodedFrame.ttl)
        val decodedOffer = Json.decodeFromString<WifiDirectOfferPayload>(String(decodedFrame.payload, Charsets.UTF_8))
        assertEquals(offer, decodedOffer)
    }

    @Test
    fun optimalPeerSelector_prefersWifiDirectAndFallsBackSeamlesslyToBle() {
        val selector = OptimalPeerSelector()

        // Same remote peerId reachable over both BLE (2M PHY, MTU 517) and Wi-Fi Direct TCP
        selector.updateLinkMetrics("AA:BB:CC:00:00:01") {
            it.copy(
                peerId = "peer_alpha",
                role = "Central",
                negotiatedMtu = 517,
                rssiDbm = -46,
                rttMs = 28L,
                supports2MPhy = true,
                transportType = MeshProtocol.TransportProtocol.BLE
            )
        }
        selector.updateLinkMetrics("wifi:1_192.168.49.5") {
            it.copy(
                peerId = "peer_alpha",
                role = "Wi-Fi Direct Client",
                negotiatedMtu = 32_768,
                rssiDbm = -42,
                rttMs = 4L,
                supports2MPhy = true,
                transportType = MeshProtocol.TransportProtocol.WIFI_DIRECT
            )
        }
        selector.updatePeerMediaInventory("AA:BB:CC:00:00:01", listOf("media_hi_res"))
        selector.updatePeerMediaInventory("wifi:1_192.168.49.5", listOf("media_hi_res"))

        val bleScore = selector.computeOptimalityScore(selector.getLinkMetrics("AA:BB:CC:00:00:01")!!)
        val wifiScore = selector.computeOptimalityScore(selector.getLinkMetrics("wifi:1_192.168.49.5")!!)
        assertTrue("Wi-Fi Direct score ($wifiScore) must dramatically exceed BLE ($bleScore)", wifiScore > bleScore * 5.0)

        val missing = mapOf("media_hi_res" to (0 until 60).toList())
        val wifiPlan = selector.planParallelDownloads(
            missingChunksByMedia = missing,
            maxChunksPerMediaPerRound = OptimalPeerSelector.WIFI_DIRECT_BATCH_CHUNKS_PER_MEDIA
        )
        assertEquals(setOf("wifi:1_192.168.49.5"), wifiPlan.optimalAddresses)
        assertEquals((0 until 60).toList(), wifiPlan.requestsByPeer["wifi:1_192.168.49.5"]?.get("media_hi_res"))
        assertNull(wifiPlan.requestsByPeer["AA:BB:CC:00:00:01"])

        // Simulate Wi-Fi Direct link dropping -> immediate fallback to BLE without losing transfer
        selector.onPeerDisconnected("wifi:1_192.168.49.5")
        val bleFallbackPlan = selector.planParallelDownloads(missing)
        assertEquals(setOf("AA:BB:CC:00:00:01"), bleFallbackPlan.optimalAddresses)
        val assignedBle = bleFallbackPlan.requestsByPeer["AA:BB:CC:00:00:01"]?.get("media_hi_res")
        assertNotNull(assignedBle)
        assertEquals(OptimalPeerSelector.DEFAULT_BATCH_CHUNKS_PER_MEDIA, assignedBle!!.size)
    }

    @Test
    fun optimalPeerSelector_stripesAcrossMultipleWifiDirectLinks() {
        val selector = OptimalPeerSelector()
        selector.updateLinkMetrics("wifi:1_192.168.49.2") {
            it.copy(peerId = "p1", negotiatedMtu = 32_768, rttMs = 5L, transportType = MeshProtocol.TransportProtocol.WIFI_DIRECT)
        }
        selector.updateLinkMetrics("wifi:2_192.168.49.3") {
            it.copy(peerId = "p2", negotiatedMtu = 32_768, rttMs = 8L, transportType = MeshProtocol.TransportProtocol.WIFI_DIRECT)
        }
        selector.updateLinkMetrics("AA:BB:CC:00:00:03") {
            it.copy(peerId = "p3", negotiatedMtu = 517, rttMs = 35L, supports2MPhy = true, transportType = MeshProtocol.TransportProtocol.BLE)
        }
        listOf("wifi:1_192.168.49.2", "wifi:2_192.168.49.3", "AA:BB:CC:00:00:03").forEach { addr ->
            selector.updatePeerMediaInventory(addr, listOf("big_audio"))
        }

        val plan = selector.planParallelDownloads(
            missingChunksByMedia = mapOf("big_audio" to (0 until 100).toList()),
            maxChunksPerMediaPerRound = OptimalPeerSelector.WIFI_DIRECT_BATCH_CHUNKS_PER_MEDIA
        )
        val c1 = plan.requestsByPeer["wifi:1_192.168.49.2"]?.get("big_audio").orEmpty()
        val c2 = plan.requestsByPeer["wifi:2_192.168.49.3"]?.get("big_audio").orEmpty()
        val c3 = plan.requestsByPeer["AA:BB:CC:00:00:03"]?.get("big_audio").orEmpty()
        assertTrue("Expected both Wi-Fi Direct links to stripe chunks (c1=${c1.size}, c2=${c2.size})", c1.size > 20 && c2.size > 20)
        assertEquals("Disjoint union should cover all 100 chunks in one Wi-Fi Direct round", 100, (c1 + c2).toSet().size)
        assertEquals("BLE straggler excluded while multi-Mbps Wi-Fi Direct links active", 0, c3.size)
    }

    @Test
    fun noteReactionAndPollWirePayloads_signAndVerifyCleanly() {
        val crypto = CryptoManager(null, InMemoryKeyWrapper())
        val alice = crypto.generateNewIdentityKeys()
        val edPubB64 = CryptoManager.encodeBase64(alice.ed25519PublicKey)

        val note = NoteWirePayload(
            noteId = "note_1",
            authorPeerId = alice.peerId,
            authorEd25519PubKey = edPubB64,
            text = "Grabbing coffee by the pier",
            moodEmoji = "☕",
            createdAt = 1_764_000_000L,
            expiresAt = 1_764_086_400L,
            signature = ""
        )
        val noteSig = crypto.signString(note.canonicalSigningString(), alice.ed25519PrivateKey)
        assertTrue(crypto.verifyString(note.canonicalSigningString(), noteSig, edPubB64))
        assertFalse(crypto.verifyString(note.copy(text = "Tampered").canonicalSigningString(), noteSig, edPubB64))

        val reaction = PostReactionWirePayload(
            postId = "post_1",
            reactorPeerId = alice.peerId,
            reactorEd25519PubKey = edPubB64,
            emoji = "🔥",
            reactedAt = 1_764_000_500L,
            signature = ""
        )
        val reactSig = crypto.signString(reaction.canonicalSigningString(), alice.ed25519PrivateKey)
        assertTrue(crypto.verifyString(reaction.canonicalSigningString(), reactSig, edPubB64))
        assertFalse(crypto.verifyString(reaction.copy(emoji = "😂").canonicalSigningString(), reactSig, edPubB64))

        val vote = PollVoteWirePayload(
            postId = "post_1",
            voterPeerId = alice.peerId,
            voterEd25519PubKey = edPubB64,
            optionIndex = 2,
            votedAt = 1_764_000_900L,
            signature = ""
        )
        val voteSig = crypto.signString(vote.canonicalSigningString(), alice.ed25519PrivateKey)
        assertTrue(crypto.verifyString(vote.canonicalSigningString(), voteSig, edPubB64))
        assertFalse(crypto.verifyString(vote.copy(optionIndex = 0).canonicalSigningString(), voteSig, edPubB64))

        val pollPost = PostWirePayload(
            postId = "post_poll_1",
            authorPeerId = alice.peerId,
            authorEd25519PubKey = edPubB64,
            caption = "Weekend plans #hangout",
            mediaId = "med_p1",
            mediaByteSize = 12_000L,
            mediaChunkSize = 384,
            mediaTotalChunks = 32,
            mediaSha256Hex = "sha_p1",
            width = 1080,
            height = 1080,
            createdAt = 1_764_000_000L,
            signature = "",
            pollQuestion = "Where should we meet?",
            pollOptionsJson = """["Pier 39","Ferry Bldg","Dolores Park"]"""
        )
        val pollSig = crypto.signString(pollPost.canonicalSigningString(), alice.ed25519PrivateKey)
        assertTrue(crypto.verifyString(pollPost.canonicalSigningString(), pollSig, edPubB64))
        assertFalse(crypto.verifyString(pollPost.copy(pollQuestion = "Changed?").canonicalSigningString(), pollSig, edPubB64))
    }

    @Test
    fun dmRichCodec_preservesPlaintextBackCompatAndRoundTripsMetadata() {
        val plain = DmRichCodec.encode("Simple fast hello")
        assertEquals("Simple fast hello", plain)
        val decodedPlain = DmRichCodec.decode(plain)
        assertEquals("Simple fast hello", decodedPlain.text)
        assertNull(decodedPlain.replyToMessageId)
        assertNull(decodedPlain.reactionToMessageId)
        assertEquals(0L, decodedPlain.disappearAfterMs)

        val encodedReply = DmRichCodec.encode(
            text = "See you there!",
            replyToMessageId = "dm_orig_1",
            replyToPreview = "Meet at 5pm?",
            disappearAfterMs = 3_600_000L
        )
        assertTrue(encodedReply.startsWith("\u0001DM_META:"))
        val decodedReply = DmRichCodec.decode(encodedReply)
        assertEquals("See you there!", decodedReply.text)
        assertEquals("dm_orig_1", decodedReply.replyToMessageId)
        assertEquals("Meet at 5pm?", decodedReply.replyToPreview)
        assertEquals(3_600_000L, decodedReply.disappearAfterMs)

        val encodedReaction = DmRichCodec.encode(
            text = "",
            reactionToMessageId = "dm_orig_1",
            reactionEmoji = "❤️"
        )
        val decodedReaction = DmRichCodec.decode(encodedReaction)
        assertEquals("dm_orig_1", decodedReaction.reactionToMessageId)
        assertEquals("❤️", decodedReaction.reactionEmoji)
    }

    private fun assertMonoMp3Header(mp3File: File) {
        val bytes = mp3File.readBytes()
        assertTrue("MP3 file too small: ${bytes.size}", bytes.size > 1024)
        var syncOffset = -1
        for (i in 0 until bytes.size - 4) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xE0) == 0xE0) {
                syncOffset = i
                break
            }
        }
        assertTrue("Missing MPEG frame sync header", syncOffset >= 0)
        val channelMode = (bytes[syncOffset + 3].toInt() ushr 6) and 0x03
        assertEquals("Expected MONO MPEG channel mode (3)", 3, channelMode)
    }

    private fun writeTestStereo24BitWav(file: File, durationSeconds: Int, sampleRate: Int) {
        val totalFrames = durationSeconds * sampleRate
        val channels = 2
        val blockAlign = channels * 3
        val dataBytes = totalFrames * blockAlign
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(24)
            put("data".toByteArray())
            putInt(dataBytes)
        }.array()
        FileOutputStream(file).use { out ->
            out.write(header)
            val buf = ByteArray(sampleRate * blockAlign)
            for (sec in 0 until durationSeconds) {
                var pos = 0
                for (i in 0 until sampleRate) {
                    val v = (sin(2.0 * PI * 440.0 * i / sampleRate) * 3_000_000.0).toInt()
                    for (c in 0 until channels) {
                        buf[pos++] = (v and 0xFF).toByte()
                        buf[pos++] = ((v ushr 8) and 0xFF).toByte()
                        buf[pos++] = ((v ushr 16) and 0xFF).toByte()
                    }
                }
                out.write(buf)
            }
        }
    }

    private fun writeTestStereoAiff(file: File, durationSeconds: Int, sampleRate: Int) {
        val totalFrames = durationSeconds * sampleRate
        val channels = 2
        val ssndBytes = 8 + totalFrames * channels * 2
        val formSize = 4 + (8 + 18) + (8 + ssndBytes)
        FileOutputStream(file).use { out ->
            val hdr = ByteBuffer.allocate(54).order(ByteOrder.BIG_ENDIAN).apply {
                put("FORM".toByteArray())
                putInt(formSize)
                put("AIFF".toByteArray())
                put("COMM".toByteArray())
                putInt(18)
                putShort(channels.toShort())
                putInt(totalFrames)
                putShort(16)
                // 80-bit IEEE 754 extended for 44100.0 Hz: exp=0x400E, mantissa=0xAC44000000000000
                putShort(0x400E.toShort())
                putLong(0xAC44000000000000uL.toLong())
                put("SSND".toByteArray())
                putInt(ssndBytes)
                putInt(0)
                putInt(0)
            }.array()
            out.write(hdr)
            val chunk = ByteBuffer.allocate(sampleRate * channels * 2).order(ByteOrder.BIG_ENDIAN)
            for (sec in 0 until durationSeconds) {
                chunk.clear()
                for (i in 0 until sampleRate) {
                    val s = (sin(2.0 * PI * 520.0 * i / sampleRate) * 12000.0).toInt().toShort()
                    chunk.putShort(s)
                    chunk.putShort(s)
                }
                out.write(chunk.array())
            }
        }
    }

    private fun writeTestStereoFlac(file: File, durationSeconds: Int, sampleRate: Int) {
        val blockSize = 4096
        val channels = 2
        val bps = 16
        val rawTotal = durationSeconds * sampleRate
        val frameCount = (rawTotal + blockSize - 1) / blockSize
        val totalSamples = frameCount.toLong() * blockSize

        FileOutputStream(file).use { out ->
            out.write("fLaC".toByteArray())
            // Last metadata block (STREAMINFO, length = 34)
            out.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
            val info = ByteArray(34)
            info[0] = ((blockSize ushr 8) and 0xFF).toByte()
            info[1] = (blockSize and 0xFF).toByte()
            info[2] = ((blockSize ushr 8) and 0xFF).toByte()
            info[3] = (blockSize and 0xFF).toByte()
            val packed = ((sampleRate.toLong() and 0xFFFFFL) shl 44) or
                (((channels - 1).toLong() and 0x7L) shl 41) or
                (((bps - 1).toLong() and 0x1FL) shl 36) or
                (totalSamples and 0xFFFFFFFFFL)
            for (b in 0 until 8) {
                info[10 + b] = ((packed ushr ((7 - b) * 8)) and 0xFFL).toByte()
            }
            out.write(info)

            val frameOut = ByteArrayOutputStream(blockSize * channels * 2 + 16)
            for (fIdx in 0 until frameCount) {
                frameOut.reset()
                frameOut.write(0xFF)
                frameOut.write(0xF8)
                frameOut.write(0xC9) // blockSize=4096 (0xC), sampleRate=44100 (0x9)
                frameOut.write(0x18) // 2 channels independent (0x1), 16-bit (0x4), reserved 0
                writeFlacUtf8Number(frameOut, fIdx.toLong())
                val hdrBytes = frameOut.toByteArray()
                frameOut.write(crc8(hdrBytes))

                val freq = if (fIdx * blockSize < 4 * sampleRate) 440.0 else 880.0
                for (ch in 0 until channels) {
                    frameOut.write(0x02) // SUBFRAME_VERBATIM
                    for (s in 0 until blockSize) {
                        val v = (sin(2.0 * PI * freq * (fIdx * blockSize + s) / sampleRate) * 10000.0)
                            .toInt().toShort()
                        frameOut.write((v.toInt() ushr 8) and 0xFF)
                        frameOut.write(v.toInt() and 0xFF)
                    }
                }
                val frameBody = frameOut.toByteArray()
                val c16 = crc16(frameBody)
                out.write(frameBody)
                out.write((c16 ushr 8) and 0xFF)
                out.write(c16 and 0xFF)
            }
        }
    }

    private fun writeFlacUtf8Number(out: ByteArrayOutputStream, value: Long) {
        if (value < 0x80L) {
            out.write(value.toInt())
        } else if (value < 0x800L) {
            out.write((0xC0 or (value ushr 6).toInt()) and 0xFF)
            out.write((0x80 or (value and 0x3FL).toInt()) and 0xFF)
        } else {
            out.write((0xE0 or (value ushr 12).toInt()) and 0xFF)
            out.write((0x80 or ((value ushr 6) and 0x3FL).toInt()) and 0xFF)
            out.write((0x80 or (value and 0x3FL).toInt()) and 0xFF)
        }
    }

    private fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) else (crc shl 1)
                crc = crc and 0xFF
            }
        }
        return crc
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x8005) else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    private fun writeTestStereoOpus(file: File, durationSeconds: Int) {
        val sampleRate = 48000
        val channels = 2
        val frameSize = 960 // 20ms
        val totalFrames = (durationSeconds * 1000) / 20
        val encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_AUDIO).apply {
            bitrate = 64000
        }
        val info = OpusInfo().apply {
            setSampleRate(sampleRate.toLong())
            numChannels = channels
            preSkip = 312
        }
        val outBuf = ByteArray(1275)
        val pcm = ShortArray(frameSize * channels)
        OggFile(FileOutputStream(file)).use { ogg ->
            val writer = ogg.packetWriter
            writer.bufferPacket(info.write(), true)
            writer.bufferPacket(OpusTags().write(), true)
            var granule = 0L
            for (f in 0 until totalFrames) {
                val freq = if (f * 20 < 4000) 440.0 else 880.0
                for (i in 0 until frameSize) {
                    val t = (f * frameSize + i).toDouble() / sampleRate
                    val s = (sin(2.0 * PI * freq * t) * 12000.0).toInt().toShort()
                    pcm[i * 2] = s
                    pcm[i * 2 + 1] = s
                }
                val len = encoder.encode(pcm, 0, frameSize, outBuf, 0, outBuf.size)
                granule += frameSize
                writer.bufferPacket(OpusAudioData(outBuf.copyOf(len)).write(), granule)
                if ((f + 1) % 50 == 0 && f + 1 < totalFrames) {
                    writer.flush()
                }
            }
            writer.close()
        }
    }

    @Test
    fun dmNotificationId_isDeterministicAndNonColliding() {
        val idA1 = DmNotificationHelper.notificationIdForPeer("peer_alice_123456")
        val idA2 = DmNotificationHelper.notificationIdForPeer("peer_alice_123456")
        val idB = DmNotificationHelper.notificationIdForPeer("peer_bob_987654")
        assertEquals(idA1, idA2)
        assertTrue("Notification ID $idA1 must stay in 2000..3023", idA1 in 2000..3023)
        assertTrue("Notification ID $idB must stay in 2000..3023", idB in 2000..3023)
        assertFalse(idA1 == DmNotificationHelper.SUMMARY_NOTIFICATION_ID)
        assertFalse(idB == DmNotificationHelper.SUMMARY_NOTIFICATION_ID)
        assertFalse(idA1 == 4041)
    }

    @Test
    fun postLikeAndStoryLikeWirePayloads_signVerifyAndParticipateInSyncPayloads() {
        val crypto = CryptoManager(null, InMemoryKeyWrapper())
        val alice = crypto.generateNewIdentityKeys()
        val edPubB64 = CryptoManager.encodeBase64(alice.ed25519PublicKey)
        val json = Json { ignoreUnknownKeys = true }

        val postLike = PostLikeWirePayload(
            postId = "post_sync_1",
            likerPeerId = alice.peerId,
            likerEd25519PubKey = edPubB64,
            isLiked = true,
            likedAt = 1_765_000_000L,
            signature = ""
        )
        val postLikeSig = crypto.signString(postLike.canonicalSigningString(), alice.ed25519PrivateKey)
        val signedPostLike = postLike.copy(signature = postLikeSig)
        assertTrue(crypto.verifyString(signedPostLike.canonicalSigningString(), signedPostLike.signature, edPubB64))
        assertFalse(
            crypto.verifyString(
                signedPostLike.copy(isLiked = false).canonicalSigningString(),
                signedPostLike.signature,
                edPubB64
            )
        )

        val storyLike = StoryLikeWirePayload(
            storyId = "story_sync_1",
            likerPeerId = alice.peerId,
            likerEd25519PubKey = edPubB64,
            isLiked = true,
            likedAt = 1_765_000_500L,
            signature = ""
        )
        val storyLikeSig = crypto.signString(storyLike.canonicalSigningString(), alice.ed25519PrivateKey)
        val signedStoryLike = storyLike.copy(signature = storyLikeSig)
        assertTrue(crypto.verifyString(signedStoryLike.canonicalSigningString(), signedStoryLike.signature, edPubB64))
        assertFalse(
            crypto.verifyString(
                signedStoryLike.copy(storyId = "story_other").canonicalSigningString(),
                signedStoryLike.signature,
                edPubB64
            )
        )

        val postLikeFrame = MeshProtocol.MeshFrame(
            type = MeshProtocol.PacketType.POST_LIKE,
            packetId = "plike_${signedPostLike.postId}_${signedPostLike.likerPeerId}",
            originPeerId = signedPostLike.likerPeerId,
            payload = json.encodeToString(signedPostLike).toByteArray(Charsets.UTF_8)
        )
        val decodedPostLikeFrame = MeshProtocol.MeshFrame.decode(postLikeFrame.encode())
        assertNotNull(decodedPostLikeFrame)
        assertEquals(MeshProtocol.PacketType.POST_LIKE, decodedPostLikeFrame!!.type)
        assertEquals(
            signedPostLike,
            json.decodeFromString<PostLikeWirePayload>(String(decodedPostLikeFrame.payload, Charsets.UTF_8))
        )

        val storyLikeFrame = MeshProtocol.MeshFrame(
            type = MeshProtocol.PacketType.STORY_LIKE,
            packetId = "slike_${signedStoryLike.storyId}_${signedStoryLike.likerPeerId}",
            originPeerId = signedStoryLike.likerPeerId,
            payload = json.encodeToString(signedStoryLike).toByteArray(Charsets.UTF_8)
        )
        val decodedStoryLikeFrame = MeshProtocol.MeshFrame.decode(storyLikeFrame.encode())
        assertNotNull(decodedStoryLikeFrame)
        assertEquals(MeshProtocol.PacketType.STORY_LIKE, decodedStoryLikeFrame!!.type)
        assertEquals(
            signedStoryLike,
            json.decodeFromString<StoryLikeWirePayload>(String(decodedStoryLikeFrame.payload, Charsets.UTF_8))
        )

        val vec = SyncVectorPayload(
            senderPeerId = alice.peerId,
            profileIds = listOf(alice.peerId),
            postIds = listOf("post_sync_1"),
            storyIds = listOf("story_sync_1"),
            completeMediaIds = emptyList(),
            pendingDmPacketIds = emptyList(),
            postLikeKeys = listOf("post_sync_1|${alice.peerId}|1765000000"),
            storyLikeKeys = listOf("story_sync_1|${alice.peerId}|1765000500")
        )
        val req = SyncRequestPayload(
            requesterPeerId = "peer_bob",
            wantedPostLikeKeys = listOf("post_sync_1|${alice.peerId}"),
            wantedStoryLikeKeys = listOf("story_sync_1|${alice.peerId}")
        )
        assertEquals(vec, json.decodeFromString<SyncVectorPayload>(json.encodeToString(vec)))
        assertEquals(req, json.decodeFromString<SyncRequestPayload>(json.encodeToString(req)))
    }
}
