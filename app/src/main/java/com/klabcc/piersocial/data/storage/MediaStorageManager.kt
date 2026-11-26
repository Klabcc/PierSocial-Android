package com.klabcc.piersocial.data.storage

import android.content.Context
import com.klabcc.piersocial.crypto.CryptoManager
import com.klabcc.piersocial.data.local.MediaBlobDao
import com.klabcc.piersocial.data.local.MediaBlobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.BitSet

data class ChunkTransferProgress(
    val mediaId: String,
    val direction: TransferDirection,
    val completedChunks: Int,
    val totalChunks: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val verifiedSha256: Boolean = false
) {
    val progress: Float
        get() = if (totalChunks <= 0) 0f else (completedChunks.toFloat() / totalChunks).coerceIn(0f, 1f)
}

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD
}

/**
 * Manages internal app storage (`filesDir`) for chunked media blobs.
 * Supports GATT MTU-aware chunk slicing, out-of-order BLE chunk reassembly,
 * SHA-256 verification on completion, and disk cleanup during LRU eviction.
 */
class MediaStorageManager(
    context: Context,
    private val mediaBlobDao: MediaBlobDao
) {
    private val blobsDir = File(context.filesDir, "media_blobs").apply { mkdirs() }
    private val chunksDir = File(context.filesDir, "media_chunks").apply { mkdirs() }
    private val ioMutex = Mutex()

    private val _activeTransfers = MutableStateFlow<Map<String, ChunkTransferProgress>>(emptyMap())
    val activeTransfers: StateFlow<Map<String, ChunkTransferProgress>> = _activeTransfers.asStateFlow()

    fun getBlobFile(mediaId: String): File {
        val ext = if (isAudioMediaId(mediaId)) "mp3" else "webp"
        return File(blobsDir, "$mediaId.$ext")
    }

    private fun getTempPartFile(mediaId: String): File = File(chunksDir, "$mediaId.part")

    suspend fun storeLocalMedia(
        mediaId: String,
        ownerPeerId: String,
        bytes: ByteArray,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        isPinned: Boolean = false
    ): MediaBlobEntity = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val file = getBlobFile(mediaId)
            file.writeBytes(bytes)
            val sha256 = CryptoManager.sha256Hex(bytes)
            val totalChunks = ((bytes.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
            val bitset = BitSet(totalChunks).apply { set(0, totalChunks) }
            val now = System.currentTimeMillis()
            val entity = MediaBlobEntity(
                mediaId = mediaId,
                ownerPeerId = ownerPeerId,
                totalBytes = bytes.size.toLong(),
                chunkSize = chunkSize,
                totalChunks = totalChunks,
                receivedChunksCount = totalChunks,
                receivedBitsetHex = bitsetToHex(bitset),
                sha256Hex = sha256,
                localFilePath = file.absolutePath,
                isComplete = true,
                isPinned = isPinned,
                isLocalAsset = true,
                createdAt = now,
                lastAccessedAt = now
            )
            mediaBlobDao.upsert(entity)
            entity
        }
    }

    suspend fun initIncomingMedia(
        mediaId: String,
        ownerPeerId: String,
        totalBytes: Long,
        chunkSize: Int,
        totalChunks: Int,
        sha256Hex: String
    ): MediaBlobEntity = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val existing = mediaBlobDao.getBlob(mediaId)
            if (existing != null) return@withLock existing

            val now = System.currentTimeMillis()
            val targetFile = getBlobFile(mediaId)
            val partFile = getTempPartFile(mediaId)
            RandomAccessFile(partFile, "rw").use { raf ->
                raf.setLength(totalBytes)
            }

            val entity = MediaBlobEntity(
                mediaId = mediaId,
                ownerPeerId = ownerPeerId,
                totalBytes = totalBytes,
                chunkSize = chunkSize,
                totalChunks = totalChunks,
                receivedChunksCount = 0,
                receivedBitsetHex = "",
                sha256Hex = sha256Hex,
                localFilePath = targetFile.absolutePath,
                isComplete = false,
                isPinned = false,
                isLocalAsset = false,
                createdAt = now,
                lastAccessedAt = now
            )
            mediaBlobDao.upsert(entity)
            updateTransferState(
                ChunkTransferProgress(
                    mediaId = mediaId,
                    direction = TransferDirection.DOWNLOAD,
                    completedChunks = 0,
                    totalChunks = totalChunks,
                    bytesTransferred = 0L,
                    totalBytes = totalBytes
                )
            )
            entity
        }
    }

    /**
     * Writes an incoming BLE chunk into the temporary reassembly file.
     * Returns true if this chunk completed and SHA-256 verified the media blob.
     */
    suspend fun writeIncomingChunk(
        mediaId: String,
        chunkIndex: Int,
        totalChunks: Int,
        chunkBytes: ByteArray,
        expectedChunkSize: Int,
        totalBytes: Long,
        expectedSha256Hex: String,
        ownerPeerId: String
    ): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            var blob = mediaBlobDao.getBlob(mediaId)
            if (blob == null) {
                val now = System.currentTimeMillis()
                val partFile = getTempPartFile(mediaId)
                RandomAccessFile(partFile, "rw").use { raf -> raf.setLength(totalBytes) }
                blob = MediaBlobEntity(
                    mediaId = mediaId,
                    ownerPeerId = ownerPeerId,
                    totalBytes = totalBytes,
                    chunkSize = expectedChunkSize,
                    totalChunks = totalChunks,
                    receivedChunksCount = 0,
                    receivedBitsetHex = "",
                    sha256Hex = expectedSha256Hex,
                    localFilePath = getBlobFile(mediaId).absolutePath,
                    isComplete = false,
                    isPinned = false,
                    isLocalAsset = false,
                    createdAt = now,
                    lastAccessedAt = now
                )
            }
            if (blob.isComplete) return@withLock false
            if (chunkIndex < 0 || chunkIndex >= blob.totalChunks) return@withLock false

            val partFile = getTempPartFile(mediaId)
            if (!partFile.exists()) {
                RandomAccessFile(partFile, "rw").use { raf -> raf.setLength(blob.totalBytes) }
            }
            RandomAccessFile(partFile, "rw").use { raf ->
                val offset = chunkIndex.toLong() * blob.chunkSize.toLong()
                raf.seek(offset)
                raf.write(chunkBytes)
            }

            val bitset = hexToBitset(blob.receivedBitsetHex)
            bitset.set(chunkIndex)
            val receivedCount = bitset.cardinality()
            val allReceived = receivedCount >= blob.totalChunks

            var verified = false
            var complete = false
            if (allReceived) {
                val assembled = partFile.readBytes()
                val actualSha = CryptoManager.sha256Hex(assembled)
                if (blob.sha256Hex.isEmpty() || actualSha.equals(blob.sha256Hex, ignoreCase = true)) {
                    val finalFile = getBlobFile(mediaId)
                    finalFile.writeBytes(assembled)
                    partFile.delete()
                    verified = true
                    complete = true
                } else {
                    // Checksum mismatch: reset bitset to allow retransmission
                    partFile.delete()
                    bitset.clear()
                }
            }

            val updated = blob.copy(
                receivedChunksCount = if (complete) blob.totalChunks else bitset.cardinality(),
                receivedBitsetHex = bitsetToHex(bitset),
                isComplete = complete,
                lastAccessedAt = System.currentTimeMillis()
            )
            mediaBlobDao.upsert(updated)

            updateTransferState(
                ChunkTransferProgress(
                    mediaId = mediaId,
                    direction = TransferDirection.DOWNLOAD,
                    completedChunks = updated.receivedChunksCount,
                    totalChunks = updated.totalChunks,
                    bytesTransferred = (updated.receivedChunksCount.toLong() * updated.chunkSize)
                        .coerceAtMost(updated.totalBytes),
                    totalBytes = updated.totalBytes,
                    verifiedSha256 = verified
                )
            )
            verified
        }
    }

    suspend fun getMissingChunkIndices(mediaId: String): List<Int> =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                val blob = mediaBlobDao.getBlob(mediaId) ?: return@withLock emptyList()
                if (blob.isComplete || blob.totalChunks <= 0) return@withLock emptyList()
                val bitset = hexToBitset(blob.receivedBitsetHex)
                val missing = ArrayList<Int>(blob.totalChunks - bitset.cardinality())
                for (i in 0 until blob.totalChunks) {
                    if (!bitset.get(i)) missing.add(i)
                }
                missing
            }
        }

    suspend fun readChunk(mediaId: String, chunkIndex: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val blob = mediaBlobDao.getBlob(mediaId) ?: return@withContext null
            val file = File(blob.localFilePath)
            if (!file.exists()) return@withContext null

            val offset = chunkIndex.toLong() * blob.chunkSize.toLong()
            if (offset >= file.length()) return@withContext null

            val len = minOf(blob.chunkSize.toLong(), file.length() - offset).toInt()
            val buffer = ByteArray(len)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                raf.readFully(buffer)
            }
            buffer
        }

    fun reportUploadProgress(
        mediaId: String,
        completedChunks: Int,
        totalChunks: Int,
        totalBytes: Long
    ) {
        updateTransferState(
            ChunkTransferProgress(
                mediaId = mediaId,
                direction = TransferDirection.UPLOAD,
                completedChunks = completedChunks,
                totalChunks = totalChunks,
                bytesTransferred = ((completedChunks.toFloat() / totalChunks.coerceAtLeast(1)) * totalBytes).toLong(),
                totalBytes = totalBytes,
                verifiedSha256 = completedChunks >= totalChunks
            )
        )
    }

    private fun updateTransferState(progress: ChunkTransferProgress) {
        val mutable = _activeTransfers.value.toMutableMap()
        mutable[progress.mediaId] = progress
        _activeTransfers.value = mutable
    }

    suspend fun deleteMediaFiles(mediaId: String) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            getBlobFile(mediaId).delete()
            getTempPartFile(mediaId).delete()
            mediaBlobDao.deleteById(mediaId)
            val mutable = _activeTransfers.value.toMutableMap()
            mutable.remove(mediaId)
            _activeTransfers.value = mutable
        }
    }

    fun calculateDiskUsageBytes(): Long {
        val blobBytes = blobsDir.listFiles()?.sumOf { it.length() } ?: 0L
        val partBytes = chunksDir.listFiles()?.sumOf { it.length() } ?: 0L
        return blobBytes + partBytes
    }

    fun clearTransferNotification(mediaId: String) {
        val mutable = _activeTransfers.value.toMutableMap()
        mutable.remove(mediaId)
        _activeTransfers.value = mutable
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 288 // Fits inside a single 495B GATT slice at MTU 512+ with MEDIA_CHUNK_BIN

        fun isAudioMediaId(mediaId: String): Boolean =
            mediaId.startsWith("saud_") || mediaId.startsWith("paud_")

        fun getAudioFileForPlayback(context: Context, mediaId: String): File {
            val dir = File(context.filesDir, "media_blobs")
            val mp3 = File(dir, "$mediaId.mp3")
            if (mp3.exists()) return mp3
            val legacyM4a = File(dir, "$mediaId.m4a")
            return if (legacyM4a.exists()) legacyM4a else mp3
        }

        fun computeMtuChunkPayloadSize(negotiatedMtu: Int): Int {
            // Reserve 3 bytes ATT header + 42 bytes mesh packet + chunk header
            return (negotiatedMtu - 48).coerceIn(120, 460)
        }

        private fun bitsetToHex(bitset: BitSet): String =
            bitset.toByteArray().joinToString("") { "%02x".format(it) }

        private fun hexToBitset(hex: String): BitSet {
            if (hex.isEmpty()) return BitSet()
            val bytes = ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return BitSet.valueOf(bytes)
        }
    }
}
