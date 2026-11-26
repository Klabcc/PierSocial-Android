package com.klabcc.piersocial.mesh

import com.klabcc.piersocial.crypto.CryptoManager
import com.klabcc.piersocial.crypto.EncryptedDmPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

object MeshProtocol {
    // Dedicated 128-bit UUIDs for PierSocial ("PIERSOCIAL" in ASCII hex) to avoid
    // GATT service collisions when Bitchat or other BLE mesh apps are also installed.
    val MESH_SERVICE_UUID: UUID = UUID.fromString("D8A47B10-5049-4552-534F-4349414C0001")
    val MESH_TX_CHAR_UUID: UUID = UUID.fromString("D8A47B10-5049-4552-534F-4349414C0002")
    val MESH_RX_CHAR_UUID: UUID = UUID.fromString("D8A47B10-5049-4552-534F-4349414C0003")
    val MESH_META_CHAR_UUID: UUID = UUID.fromString("D8A47B10-5049-4552-534F-4349414C0004")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PROTOCOL_VERSION: Byte = 1
    const val DEFAULT_TTL: Int = 5
    const val TARGET_MTU: Int = 512
    const val DEFAULT_MTU: Int = 23

    enum class TransportProtocol {
        BLE,
        WIFI_DIRECT
    }

    enum class SendPriority {
        /** Direct Messages & DM ACKs — preempts all sync and bulk media traffic */
        EXPRESS,
        /** Link probes, anti-entropy vectors, profiles, and record metadata */
        CONTROL,
        /** Image & audio chunk transfers */
        BULK
    }

    enum class PacketType(val code: Byte) {
        ANNOUNCE(1),
        SYNC_VECTOR(2),
        SYNC_REQUEST(3),
        PROFILE_RECORD(4),
        POST_RECORD(5),
        STORY_RECORD(6),
        MEDIA_CHUNK(7),
        DM_PACKET(8),
        DM_ACK(9),
        LINK_PROBE(10),
        LINK_PROBE_ACK(11),
        MEDIA_CHUNK_BIN(12),
        DM_PACKET_BIN(13),
        DM_ACK_BIN(14),
        WIFI_DIRECT_OFFER(15),
        NOTE_RECORD(16),
        POST_REACTION(17),
        POLL_VOTE(18),
        POST_LIKE(19),
        STORY_LIKE(20);

        val isBulkData: Boolean
            get() = this == MEDIA_CHUNK || this == MEDIA_CHUNK_BIN

        val isExpressDm: Boolean
            get() = this == DM_PACKET || this == DM_ACK || this == DM_PACKET_BIN || this == DM_ACK_BIN

        val isFastNoResponse: Boolean
            get() = isBulkData || isExpressDm

        val sendPriority: SendPriority
            get() = when {
                isExpressDm -> SendPriority.EXPRESS
                isBulkData -> SendPriority.BULK
                else -> SendPriority.CONTROL
            }

        companion object {
            fun fromCode(code: Byte): PacketType? = entries.firstOrNull { it.code == code }
        }
    }

    /**
     * Compact binary mesh envelope inspired by Bitchat's BLE frame layout:
     * [version:1][type:1][ttl:1][hopCount:1][timestamp:8][packetIdLen:1][packetId]
     * [originLen:1][origin][targetLen:1][target][payloadLen:4][payload]
     */
    data class MeshFrame(
        val type: PacketType,
        val packetId: String,
        val originPeerId: String,
        val targetPeerId: String = "",
        val ttl: Int = DEFAULT_TTL,
        val hopCount: Int = 0,
        val timestamp: Long = System.currentTimeMillis(),
        val payload: ByteArray
    ) {
        fun encode(): ByteArray {
            val idBytes = packetId.toByteArray(Charsets.UTF_8)
            val originBytes = originPeerId.toByteArray(Charsets.UTF_8)
            val targetBytes = targetPeerId.toByteArray(Charsets.UTF_8)
            val totalSize = 1 + 1 + 1 + 1 + 8 + 1 + idBytes.size + 1 + originBytes.size + 1 + targetBytes.size + 4 + payload.size
            val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
            buf.put(PROTOCOL_VERSION)
            buf.put(type.code)
            buf.put(ttl.coerceIn(0, 127).toByte())
            buf.put(hopCount.coerceIn(0, 127).toByte())
            buf.putLong(timestamp)
            buf.put(idBytes.size.toByte())
            buf.put(idBytes)
            buf.put(originBytes.size.toByte())
            buf.put(originBytes)
            buf.put(targetBytes.size.toByte())
            buf.put(targetBytes)
            buf.putInt(payload.size)
            buf.put(payload)
            return buf.array()
        }

        fun nextHop(): MeshFrame = copy(
            ttl = (ttl - 1).coerceAtLeast(0),
            hopCount = hopCount + 1
        )

        companion object {
            fun decode(bytes: ByteArray): MeshFrame? = runCatching {
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                val version = buf.get()
                if (version != PROTOCOL_VERSION) return null
                val type = PacketType.fromCode(buf.get()) ?: return null
                val ttl = buf.get().toInt()
                val hopCount = buf.get().toInt()
                val timestamp = buf.getLong()

                val idLen = buf.get().toInt() and 0xFF
                val idBytes = ByteArray(idLen).also { buf.get(it) }
                val originLen = buf.get().toInt() and 0xFF
                val originBytes = ByteArray(originLen).also { buf.get(it) }
                val targetLen = buf.get().toInt() and 0xFF
                val targetBytes = ByteArray(targetLen).also { buf.get(it) }
                val payloadLen = buf.getInt()
                if (payloadLen < 0 || payloadLen > 128 * 1024) return null
                val payload = ByteArray(payloadLen).also { buf.get(it) }

                MeshFrame(
                    type = type,
                    packetId = String(idBytes, Charsets.UTF_8),
                    originPeerId = String(originBytes, Charsets.UTF_8),
                    targetPeerId = String(targetBytes, Charsets.UTF_8),
                    ttl = ttl,
                    hopCount = hopCount,
                    timestamp = timestamp,
                    payload = payload
                )
            }.getOrNull()
        }
    }

    /**
     * Length-prefixed transport slicer for splitting MeshFrames across GATT characteristic
     * writes/notifications according to the negotiated MTU.
     * Header (5 bytes): [seq:1][index:2][total:2]
     */
    fun sliceForGattTransport(
        frameBytes: ByteArray,
        mtu: Int,
        sequenceId: Byte = 1
    ): List<ByteArray> {
        // Android BLE caps single GATT attribute write/notify payloads at 512 bytes even when ATT MTU is 517.
        val maxSliceBytes = minOf(mtu - 3, 495).coerceAtLeast(20)
        val maxPayload = (maxSliceBytes - 5).coerceAtLeast(15)
        if (frameBytes.size <= maxPayload) {
            return listOf(
                byteArrayOf(sequenceId, 0x00, 0x01, 0x00, 0x01) + frameBytes
            )
        }
        val chunks = frameBytes.toList().chunked(maxPayload)
        val total = chunks.size.coerceAtMost(65535)
        return chunks.take(total).mapIndexed { index, slice ->
            val idx1 = index + 1
            byteArrayOf(
                sequenceId,
                ((idx1 ushr 8) and 0xFF).toByte(),
                (idx1 and 0xFF).toByte(),
                ((total ushr 8) and 0xFF).toByte(),
                (total and 0xFF).toByte()
            ) + slice.toByteArray()
        }
    }
}

@Serializable
data class PeerAnnouncePayload(
    val peerId: String,
    val handle: String,
    val displayName: String,
    val ed25519PubKey: String,
    val x25519PubKey: String,
    val fingerprint: String,
    val bio: String = "",
    val avatarMediaId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val signature: String = ""
)

@Serializable
data class SyncVectorPayload(
    val senderPeerId: String,
    val profileIds: List<String>,
    val postIds: List<String>,
    val storyIds: List<String>,
    val completeMediaIds: List<String>,
    val pendingDmPacketIds: List<String>,
    val activeOutboundJobs: Int = 0,
    val queuedOutboundSlices: Int = 0,
    val connectedPeerCount: Int = 0,
    val negotiatedMtu: Int = MeshProtocol.DEFAULT_MTU,
    val measuredGoodputBps: Long = 0L,
    val supports2MPhy: Boolean = false,
    val noteIds: List<String> = emptyList(),
    val reactionKeys: List<String> = emptyList(),
    val pollVoteKeys: List<String> = emptyList(),
    val postLikeKeys: List<String> = emptyList(),
    val storyLikeKeys: List<String> = emptyList()
)

@Serializable
data class SyncRequestPayload(
    val requesterPeerId: String,
    val wantedProfileIds: List<String> = emptyList(),
    val wantedPostIds: List<String> = emptyList(),
    val wantedStoryIds: List<String> = emptyList(),
    val wantedMediaIds: List<String> = emptyList(),
    val wantedDmPacketIds: List<String> = emptyList(),
    val wantedMediaChunkIndices: Map<String, List<Int>> = emptyMap(),
    val wantedNoteIds: List<String> = emptyList(),
    val wantedReactionKeys: List<String> = emptyList(),
    val wantedPollVoteKeys: List<String> = emptyList(),
    val wantedPostLikeKeys: List<String> = emptyList(),
    val wantedStoryLikeKeys: List<String> = emptyList()
)

@Serializable
data class LinkProbePayload(
    val probeId: String,
    val senderPeerId: String,
    val sentAtMs: Long,
    val activeUploads: Int = 0,
    val queuedSlices: Int = 0,
    val connectedPeers: Int = 0,
    val negotiatedMtu: Int = MeshProtocol.DEFAULT_MTU,
    val supports2MPhy: Boolean = false,
    val measuredThroughputBps: Long = 0L
)

@Serializable
data class LinkProbeAckPayload(
    val probeId: String,
    val responderPeerId: String,
    val echoSentAtMs: Long,
    val activeUploads: Int = 0,
    val queuedSlices: Int = 0,
    val connectedPeers: Int = 0,
    val negotiatedMtu: Int = MeshProtocol.DEFAULT_MTU,
    val supports2MPhy: Boolean = false,
    val measuredThroughputBps: Long = 0L
)

@Serializable
data class WifiDirectOfferPayload(
    val senderPeerId: String,
    val networkName: String,
    val passphrase: String,
    val hostIp: String,
    val tcpPort: Int,
    val deviceAddress: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class PostWirePayload(
    val postId: String,
    val authorPeerId: String,
    val authorEd25519PubKey: String,
    val caption: String,
    val mediaId: String,
    val mediaByteSize: Long,
    val mediaChunkSize: Int,
    val mediaTotalChunks: Int,
    val mediaSha256Hex: String,
    val width: Int,
    val height: Int,
    val createdAt: Long,
    val signature: String,
    val audioMediaId: String? = null,
    val audioByteSize: Long = 0L,
    val audioChunkSize: Int = 0,
    val audioTotalChunks: Int = 0,
    val audioSha256Hex: String = "",
    val audioDurationMs: Int = 0,
    val pollQuestion: String? = null,
    val pollOptionsJson: String? = null
) {
    fun canonicalSigningString(): String {
        val base = if (audioMediaId.isNullOrEmpty()) {
            "$postId|$authorPeerId|$mediaId|$mediaSha256Hex|$createdAt|$caption"
        } else {
            "$postId|$authorPeerId|$mediaId|$mediaSha256Hex|$audioMediaId|$audioSha256Hex|$createdAt|$caption"
        }
        return if (pollQuestion.isNullOrEmpty()) {
            base
        } else {
            "$base|$pollQuestion|${pollOptionsJson.orEmpty()}"
        }
    }
}

@Serializable
data class NoteWirePayload(
    val noteId: String,
    val authorPeerId: String,
    val authorEd25519PubKey: String,
    val text: String,
    val moodEmoji: String = "",
    val createdAt: Long,
    val expiresAt: Long,
    val signature: String
) {
    fun canonicalSigningString(): String =
        "$noteId|$authorPeerId|$text|$moodEmoji|$createdAt|$expiresAt"
}

@Serializable
data class PostReactionWirePayload(
    val postId: String,
    val reactorPeerId: String,
    val reactorEd25519PubKey: String,
    val emoji: String,
    val reactedAt: Long,
    val signature: String
) {
    fun canonicalSigningString(): String =
        "$postId|$reactorPeerId|$emoji|$reactedAt"
}

@Serializable
data class PostLikeWirePayload(
    val postId: String,
    val likerPeerId: String,
    val likerEd25519PubKey: String,
    val isLiked: Boolean,
    val likedAt: Long,
    val signature: String
) {
    fun canonicalSigningString(): String =
        "$postId|$likerPeerId|$isLiked|$likedAt"
}

@Serializable
data class StoryLikeWirePayload(
    val storyId: String,
    val likerPeerId: String,
    val likerEd25519PubKey: String,
    val isLiked: Boolean,
    val likedAt: Long,
    val signature: String
) {
    fun canonicalSigningString(): String =
        "$storyId|$likerPeerId|$isLiked|$likedAt"
}

@Serializable
data class PollVoteWirePayload(
    val postId: String,
    val voterPeerId: String,
    val voterEd25519PubKey: String,
    val optionIndex: Int,
    val votedAt: Long,
    val signature: String
) {
    fun canonicalSigningString(): String =
        "$postId|$voterPeerId|$optionIndex|$votedAt"
}

@Serializable
data class DmRichContent(
    val text: String = "",
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val reactionToMessageId: String? = null,
    val reactionEmoji: String? = null,
    val disappearAfterMs: Long = 0L
)

object DmRichCodec {
    private const val PREFIX = "\u0001DM_META:"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(
        text: String,
        replyToMessageId: String? = null,
        replyToPreview: String? = null,
        reactionToMessageId: String? = null,
        reactionEmoji: String? = null,
        disappearAfterMs: Long = 0L
    ): String {
        if (replyToMessageId == null && reactionToMessageId == null && disappearAfterMs <= 0L) {
            return text
        }
        val rich = DmRichContent(
            text = text,
            replyToMessageId = replyToMessageId,
            replyToPreview = replyToPreview?.take(80),
            reactionToMessageId = reactionToMessageId,
            reactionEmoji = reactionEmoji,
            disappearAfterMs = disappearAfterMs
        )
        return PREFIX + json.encodeToString(rich)
    }

    fun decode(rawPlaintext: String): DmRichContent {
        if (!rawPlaintext.startsWith(PREFIX)) {
            return DmRichContent(text = rawPlaintext)
        }
        return runCatching {
            json.decodeFromString<DmRichContent>(rawPlaintext.removePrefix(PREFIX))
        }.getOrElse {
            DmRichContent(text = rawPlaintext)
        }
    }
}

@Serializable
data class StoryWirePayload(
    val storyId: String,
    val authorPeerId: String,
    val authorEd25519PubKey: String,
    val mediaId: String,
    val mediaByteSize: Long,
    val mediaChunkSize: Int,
    val mediaTotalChunks: Int,
    val mediaSha256Hex: String,
    val caption: String,
    val createdAt: Long,
    val expiresAt: Long,
    val signature: String,
    val audioMediaId: String? = null,
    val audioByteSize: Long = 0L,
    val audioChunkSize: Int = 0,
    val audioTotalChunks: Int = 0,
    val audioSha256Hex: String = "",
    val audioDurationMs: Int = 0
) {
    fun canonicalSigningString(): String =
        if (audioMediaId.isNullOrEmpty()) {
            "$storyId|$authorPeerId|$mediaId|$mediaSha256Hex|$createdAt|$expiresAt|$caption"
        } else {
            "$storyId|$authorPeerId|$mediaId|$mediaSha256Hex|$audioMediaId|$audioSha256Hex|$createdAt|$expiresAt|$caption"
        }
}

@Serializable
data class MediaChunkWirePayload(
    val mediaId: String,
    val ownerPeerId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSize: Int,
    val totalBytes: Long,
    val sha256Hex: String,
    val dataBase64: String
)

data class MediaChunkBinary(
    val mediaId: String,
    val ownerPeerId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSize: Int,
    val totalBytes: Long,
    val sha256Hex: String,
    val data: ByteArray
)

object MediaChunkBinaryCodec {
    private const val MAGIC: Byte = 0x4D // 'M' (UTF-8 sha256Hex)
    private const val MAGIC_RAW_SHA: Byte = 0x4E // 'N' (32-byte raw SHA-256, saves 32 bytes)

    fun encode(chunk: MediaChunkBinary): ByteArray {
        val mediaBytes = chunk.mediaId.toByteArray(Charsets.UTF_8)
        val ownerBytes = chunk.ownerPeerId.toByteArray(Charsets.UTF_8)
        val rawSha = hexToBytes32(chunk.sha256Hex)
        val useRawSha = rawSha != null
        val shaBytes = rawSha ?: chunk.sha256Hex.toByteArray(Charsets.UTF_8)
        val totalSize = 1 + 1 + mediaBytes.size + 1 + ownerBytes.size +
            4 + 4 + 4 + 8 + 1 + shaBytes.size + 4 + chunk.data.size
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.put(if (useRawSha) MAGIC_RAW_SHA else MAGIC)
        buf.put(mediaBytes.size.coerceAtMost(255).toByte())
        buf.put(mediaBytes, 0, mediaBytes.size.coerceAtMost(255))
        buf.put(ownerBytes.size.coerceAtMost(255).toByte())
        buf.put(ownerBytes, 0, ownerBytes.size.coerceAtMost(255))
        buf.putInt(chunk.chunkIndex)
        buf.putInt(chunk.totalChunks)
        buf.putInt(chunk.chunkSize)
        buf.putLong(chunk.totalBytes)
        buf.put(shaBytes.size.coerceAtMost(255).toByte())
        buf.put(shaBytes, 0, shaBytes.size.coerceAtMost(255))
        buf.putInt(chunk.data.size)
        buf.put(chunk.data)
        return buf.array()
    }

    fun decode(bytes: ByteArray): MediaChunkBinary? = runCatching {
        if (bytes.size < 28) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.get()
        if (magic != MAGIC && magic != MAGIC_RAW_SHA) return null
        val mediaLen = buf.get().toInt() and 0xFF
        val mediaBytes = ByteArray(mediaLen).also { buf.get(it) }
        val ownerLen = buf.get().toInt() and 0xFF
        val ownerBytes = ByteArray(ownerLen).also { buf.get(it) }
        val chunkIndex = buf.getInt()
        val totalChunks = buf.getInt()
        val chunkSize = buf.getInt()
        val totalBytes = buf.getLong()
        val shaLen = buf.get().toInt() and 0xFF
        val shaBytes = ByteArray(shaLen).also { buf.get(it) }
        val sha256Hex = if (magic == MAGIC_RAW_SHA) {
            shaBytes.joinToString("") { "%02x".format(it) }
        } else {
            String(shaBytes, Charsets.UTF_8)
        }
        val dataLen = buf.getInt()
        if (dataLen < 0 || dataLen > 64 * 1024) return null
        val data = ByteArray(dataLen).also { buf.get(it) }
        MediaChunkBinary(
            mediaId = String(mediaBytes, Charsets.UTF_8),
            ownerPeerId = String(ownerBytes, Charsets.UTF_8),
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            totalBytes = totalBytes,
            sha256Hex = sha256Hex,
            data = data
        )
    }.getOrNull()

    private fun hexToBytes32(hex: String): ByteArray? {
        if (hex.length != 64) return null
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

@Serializable
data class DmWirePayload(
    val messageId: String,
    val senderPeerId: String,
    val senderEd25519PubKey: String,
    val recipientPeerId: String,
    val encryptedPayloadJson: String,
    val timestamp: Long
)

@Serializable
data class DmAckPayload(
    val messageId: String,
    val senderPeerId: String,
    val recipientPeerId: String,
    val hopCount: Int,
    val deliveredAt: Long
)

/**
 * Compact binary codec for E2EE DMs: shrinks ~600B nested JSON+Base64 to ~230B + ciphertext,
 * allowing typical messages (< ~190 UTF-8 bytes) to transmit in a SINGLE 512-MTU GATT packet.
 */
object DmBinaryCodec {
    private const val MAGIC: Byte = 0x44 // 'D'
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(wire: DmWirePayload): ByteArray? = runCatching {
        val enc = json.decodeFromString<EncryptedDmPayload>(wire.encryptedPayloadJson)
        val msgBytes = wire.messageId.toByteArray(Charsets.UTF_8)
        val senderBytes = wire.senderPeerId.toByteArray(Charsets.UTF_8)
        val recipBytes = wire.recipientPeerId.toByteArray(Charsets.UTF_8)
        val edPub = CryptoManager.decodeBase64(wire.senderEd25519PubKey)
        val ephPub = CryptoManager.decodeBase64(enc.ephemeralX25519PubKeyBase64)
        val nonce = CryptoManager.decodeBase64(enc.nonceBase64)
        val sig = CryptoManager.decodeBase64(enc.signatureBase64)
        val cipher = CryptoManager.decodeBase64(enc.ciphertextBase64)
        if (edPub.size != 32 || ephPub.size != 32 || nonce.size != 12 || sig.size != 64) {
            return null
        }
        val total = 1 + 1 + msgBytes.size + 1 + senderBytes.size + 1 + recipBytes.size +
            8 + 32 + 32 + 12 + 64 + 4 + cipher.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(msgBytes.size.coerceAtMost(255).toByte())
        buf.put(msgBytes, 0, msgBytes.size.coerceAtMost(255))
        buf.put(senderBytes.size.coerceAtMost(255).toByte())
        buf.put(senderBytes, 0, senderBytes.size.coerceAtMost(255))
        buf.put(recipBytes.size.coerceAtMost(255).toByte())
        buf.put(recipBytes, 0, recipBytes.size.coerceAtMost(255))
        buf.putLong(wire.timestamp)
        buf.put(edPub)
        buf.put(ephPub)
        buf.put(nonce)
        buf.put(sig)
        buf.putInt(cipher.size)
        buf.put(cipher)
        buf.array()
    }.getOrNull()

    fun decode(bytes: ByteArray): DmWirePayload? = runCatching {
        if (bytes.size < 160) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buf.get() != MAGIC) return null
        val msgLen = buf.get().toInt() and 0xFF
        val msgBytes = ByteArray(msgLen).also { buf.get(it) }
        val senderLen = buf.get().toInt() and 0xFF
        val senderBytes = ByteArray(senderLen).also { buf.get(it) }
        val recipLen = buf.get().toInt() and 0xFF
        val recipBytes = ByteArray(recipLen).also { buf.get(it) }
        val timestamp = buf.getLong()
        val edPub = ByteArray(32).also { buf.get(it) }
        val ephPub = ByteArray(32).also { buf.get(it) }
        val nonce = ByteArray(12).also { buf.get(it) }
        val sig = ByteArray(64).also { buf.get(it) }
        val cipherLen = buf.getInt()
        if (cipherLen < 0 || cipherLen > 64 * 1024) return null
        val cipher = ByteArray(cipherLen).also { buf.get(it) }
        val enc = EncryptedDmPayload(
            ephemeralX25519PubKeyBase64 = CryptoManager.encodeBase64(ephPub),
            nonceBase64 = CryptoManager.encodeBase64(nonce),
            ciphertextBase64 = CryptoManager.encodeBase64(cipher),
            signatureBase64 = CryptoManager.encodeBase64(sig)
        )
        DmWirePayload(
            messageId = String(msgBytes, Charsets.UTF_8),
            senderPeerId = String(senderBytes, Charsets.UTF_8),
            senderEd25519PubKey = CryptoManager.encodeBase64(edPub),
            recipientPeerId = String(recipBytes, Charsets.UTF_8),
            encryptedPayloadJson = json.encodeToString(enc),
            timestamp = timestamp
        )
    }.getOrNull()
}

/**
 * Compact binary codec for DM delivery receipts (~65 bytes total).
 */
object DmAckBinaryCodec {
    private const val MAGIC: Byte = 0x41 // 'A'

    fun encode(ack: DmAckPayload): ByteArray {
        val msgBytes = ack.messageId.toByteArray(Charsets.UTF_8)
        val senderBytes = ack.senderPeerId.toByteArray(Charsets.UTF_8)
        val recipBytes = ack.recipientPeerId.toByteArray(Charsets.UTF_8)
        val total = 1 + 1 + msgBytes.size + 1 + senderBytes.size + 1 + recipBytes.size + 1 + 8
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(msgBytes.size.coerceAtMost(255).toByte())
        buf.put(msgBytes, 0, msgBytes.size.coerceAtMost(255))
        buf.put(senderBytes.size.coerceAtMost(255).toByte())
        buf.put(senderBytes, 0, senderBytes.size.coerceAtMost(255))
        buf.put(recipBytes.size.coerceAtMost(255).toByte())
        buf.put(recipBytes, 0, recipBytes.size.coerceAtMost(255))
        buf.put(ack.hopCount.coerceIn(0, 127).toByte())
        buf.putLong(ack.deliveredAt)
        return buf.array()
    }

    fun decode(bytes: ByteArray): DmAckPayload? = runCatching {
        if (bytes.size < 16) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buf.get() != MAGIC) return null
        val msgLen = buf.get().toInt() and 0xFF
        val msgBytes = ByteArray(msgLen).also { buf.get(it) }
        val senderLen = buf.get().toInt() and 0xFF
        val senderBytes = ByteArray(senderLen).also { buf.get(it) }
        val recipLen = buf.get().toInt() and 0xFF
        val recipBytes = ByteArray(recipLen).also { buf.get(it) }
        val hopCount = buf.get().toInt()
        val deliveredAt = buf.getLong()
        DmAckPayload(
            messageId = String(msgBytes, Charsets.UTF_8),
            senderPeerId = String(senderBytes, Charsets.UTF_8),
            recipientPeerId = String(recipBytes, Charsets.UTF_8),
            hopCount = hopCount,
            deliveredAt = deliveredAt
        )
    }.getOrNull()
}

/**
 * Thread-safe Bloom filter + bounded LRU set for O(1) multi-hop packet deduplication.
 */
class PacketDeduplicator(
    private val maxExactEntries: Int = 4096,
    private val bloomBits: Int = 8192
) {
    private val seenSet = object : LinkedHashMap<String, Boolean>(maxExactEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > maxExactEntries
    }
    private val bloom = BooleanArray(bloomBits)

    @Synchronized
    fun markSeenIfNew(packetId: String): Boolean {
        if (seenSet.containsKey(packetId)) return false
        seenSet[packetId] = true
        val hashes = bloomHashes(packetId)
        for (h in hashes) {
            bloom[h] = true
        }
        return true
    }

    @Synchronized
    fun mightContain(packetId: String): Boolean {
        return seenSet.containsKey(packetId) || bloomHashes(packetId).all { bloom[it] }
    }

    @Synchronized
    fun clear() {
        seenSet.clear()
        bloom.fill(false)
    }

    private fun bloomHashes(key: String): IntArray {
        val bytes = key.toByteArray(Charsets.UTF_8)
        val crc1 = CRC32().apply { update(bytes) }.value
        val crc2 = CRC32().apply {
            update(byteArrayOf(0x5A))
            update(bytes)
        }.value
        return intArrayOf(
            ((crc1) % bloomBits).toInt().let { if (it < 0) it + bloomBits else it },
            ((crc2) % bloomBits).toInt().let { if (it < 0) it + bloomBits else it },
            (((crc1 xor (crc2 shl 5))) % bloomBits).toInt().let { if (it < 0) it + bloomBits else it }
        )
    }
}

data class DiscoveredBlePeer(
    val address: String,
    val peerId: String?,
    val displayName: String,
    val rssi: Int,
    val lastSeenAt: Long
)

data class GattConnectionInfo(
    val address: String,
    val peerId: String?,
    val role: String, // "Central", "Peripheral", "Wi-Fi Direct GO", or "Wi-Fi Direct Client"
    val negotiatedMtu: Int,
    val state: String,
    val connectedAt: Long,
    val rssiDbm: Int = -65,
    val rttMs: Long = 0L,
    val supports2MPhy: Boolean = false,
    val remoteActiveUploads: Int = 0,
    val remoteQueuedSlices: Int = 0,
    val measuredGoodputBps: Long = 0L,
    val optimalityScore: Double = 0.0,
    val isOptimalSource: Boolean = false,
    val transportType: MeshProtocol.TransportProtocol = MeshProtocol.TransportProtocol.BLE
)

data class MeshLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val isError: Boolean = false
)

data class MeshRadioState(
    val isServiceRunning: Boolean = false,
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val hasPermissions: Boolean = false,
    val packetsRelayed: Int = 0,
    val lastSyncAt: Long? = null,
    val isWifiDirectEnabled: Boolean = false,
    val isWifiDirectGroupOwner: Boolean = false,
    val wifiDirectConnectedCount: Int = 0
)
