package com.klabcc.piersocial.data.retention

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.klabcc.piersocial.data.local.DirectMessageDao
import com.klabcc.piersocial.data.local.MediaBlobDao
import com.klabcc.piersocial.data.local.MeshPacketDao
import com.klabcc.piersocial.data.local.NoteDao
import com.klabcc.piersocial.data.local.PostDao
import com.klabcc.piersocial.data.local.ProfileDao
import com.klabcc.piersocial.data.local.StoryDao
import com.klabcc.piersocial.data.storage.MediaStorageManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.retentionDataStore by preferencesDataStore(name = "pier_retention_settings")

enum class RetentionCapPreset(val label: String, val bytes: Long) {
    MB_50("50 MB", 50L * 1024L * 1024L),
    MB_250("250 MB", 250L * 1024L * 1024L),
    GB_1("1 GB", 1024L * 1024L * 1024L),
    GB_5("5 GB", 5L * 1024L * 1024L * 1024L),
    INFINITE("Infinite / Unlimited", -1L);

    val isInfinite: Boolean get() = bytes < 0L

    companion object {
        fun fromBytes(bytes: Long): RetentionCapPreset =
            entries.firstOrNull { it.bytes == bytes } ?: GB_1
    }
}

data class StorageStats(
    val totalUsedBytes: Long = 0L,
    val mediaBytes: Long = 0L,
    val packetBytes: Long = 0L,
    val pinnedBytes: Long = 0L,
    val capBytes: Long = RetentionCapPreset.GB_1.bytes,
    val autoEvictEnabled: Boolean = true,
    val lastEvictedCount: Int = 0
) {
    val isInfinite: Boolean get() = capBytes < 0L
    val usageRatio: Float
        get() = if (isInfinite || capBytes <= 0L) 0f
        else (totalUsedBytes.toFloat() / capBytes.toFloat()).coerceIn(0f, 1f)
}

/**
 * Manages user-configurable storage caps (50 MB .. Infinite), live usage metering,
 * 24-hour story expiration (excluding pinned stories), and automatic LRU eviction
 * that always preserves pinned posts, profiles, stories, and local assets.
 */
class RetentionManager(
    private val context: Context,
    private val profileDao: ProfileDao,
    private val postDao: PostDao,
    private val storyDao: StoryDao,
    private val meshPacketDao: MeshPacketDao,
    private val mediaBlobDao: MediaBlobDao,
    private val mediaStorageManager: MediaStorageManager,
    private val noteDao: NoteDao? = null,
    private val directMessageDao: DirectMessageDao? = null
) {
    private val capKey = longPreferencesKey("retention_cap_bytes")
    private val autoEvictKey = booleanPreferencesKey("auto_evict_enabled")
    private val lastEvictedKey = longPreferencesKey("last_evicted_count")

    val capBytesFlow: Flow<Long> = context.retentionDataStore.data.map { prefs ->
        prefs[capKey] ?: RetentionCapPreset.GB_1.bytes
    }

    val autoEvictFlow: Flow<Boolean> = context.retentionDataStore.data.map { prefs ->
        prefs[autoEvictKey] ?: true
    }

    val storageStatsFlow: Flow<StorageStats> = combine(
        mediaBlobDao.observeTotalMediaBytes(),
        meshPacketDao.observeTotalPacketBytes(),
        capBytesFlow,
        autoEvictFlow,
        context.retentionDataStore.data.map { (it[lastEvictedKey] ?: 0L).toInt() }
    ) { mediaBytes, packetBytes, capBytes, autoEvict, lastEvicted ->
        val pinnedMedia = mediaBlobDao.getPinnedMediaBytes()
        val pinnedPackets = meshPacketDao.getPinnedPacketBytes()
        val pinnedProfiles = profileDao.getPinnedBytes()
        val diskBytes = mediaStorageManager.calculateDiskUsageBytes()
        val effectiveMedia = maxOf(mediaBytes, diskBytes)
        StorageStats(
            totalUsedBytes = effectiveMedia + packetBytes,
            mediaBytes = effectiveMedia,
            packetBytes = packetBytes,
            pinnedBytes = pinnedMedia + pinnedPackets + pinnedProfiles,
            capBytes = capBytes,
            autoEvictEnabled = autoEvict,
            lastEvictedCount = lastEvicted
        )
    }

    suspend fun setRetentionCap(preset: RetentionCapPreset) {
        context.retentionDataStore.edit { prefs ->
            prefs[capKey] = preset.bytes
        }
        enforceRetentionPolicy()
    }

    suspend fun setAutoEvictEnabled(enabled: Boolean) {
        context.retentionDataStore.edit { prefs ->
            prefs[autoEvictKey] = enabled
        }
        if (enabled) enforceRetentionPolicy()
    }

    /**
     * Purges expired (>24h) unpinned stories and runs LRU eviction whenever
     * retained peer data exceeds the user's configured retention cap.
     */
    suspend fun enforceRetentionPolicy(force: Boolean = false): Int {
        val now = System.currentTimeMillis()
        var evictedCount = 0

        // 1. Expire unpinned stories past 24 hours
        val expiredStories = storyDao.getExpiredUnpinnedStories(now)
        for (story in expiredStories) {
            storyDao.deleteById(story.storyId)
            val blob = mediaBlobDao.getBlob(story.mediaId)
            if (blob != null && !blob.isPinned) {
                mediaStorageManager.deleteMediaFiles(story.mediaId)
            }
            if (!story.audioMediaId.isNullOrEmpty()) {
                val audioBlob = mediaBlobDao.getBlob(story.audioMediaId)
                if (audioBlob != null && !audioBlob.isPinned) {
                    mediaStorageManager.deleteMediaFiles(story.audioMediaId)
                }
            }
            evictedCount++
        }
        meshPacketDao.deleteExpired(now)
        noteDao?.deleteExpired(now)
        directMessageDao?.deleteExpired(now)

        // 2. Check cap
        val cap = capBytesFlow.first()
        val autoEvict = autoEvictFlow.first()
        if (cap < 0L || (!autoEvict && !force)) {
            saveEvictedCount(evictedCount)
            return evictedCount
        }

        var currentTotal = maxOf(
            mediaBlobDao.getTotalMediaBytes(),
            mediaStorageManager.calculateDiskUsageBytes()
        ) + meshPacketDao.getTotalPacketBytes()

        if (currentTotal <= cap) {
            saveEvictedCount(evictedCount)
            return evictedCount
        }

        // 3. Evict oldest unpinned mesh packets first
        val evictablePackets = meshPacketDao.getEvictablePacketsLru()
        for (packet in evictablePackets) {
            if (currentTotal <= cap) break
            meshPacketDao.deleteById(packet.packetId)
            currentTotal -= packet.byteSize
            evictedCount++
        }

        // 4. Evict oldest unpinned peer stories
        if (currentTotal > cap) {
            val lruStories = storyDao.getEvictableStoriesLru()
            for (story in lruStories) {
                if (currentTotal <= cap) break
                storyDao.deleteById(story.storyId)
                val blob = mediaBlobDao.getBlob(story.mediaId)
                if (blob != null && !blob.isPinned && !blob.isLocalAsset) {
                    mediaStorageManager.deleteMediaFiles(story.mediaId)
                    currentTotal -= blob.totalBytes
                }
                if (!story.audioMediaId.isNullOrEmpty()) {
                    val audioBlob = mediaBlobDao.getBlob(story.audioMediaId)
                    if (audioBlob != null && !audioBlob.isPinned && !audioBlob.isLocalAsset) {
                        mediaStorageManager.deleteMediaFiles(story.audioMediaId)
                        currentTotal -= audioBlob.totalBytes
                    }
                }
                evictedCount++
            }
        }

        // 5. Evict oldest unpinned peer posts & their media/audio blobs
        if (currentTotal > cap) {
            val lruPosts = postDao.getEvictablePostsLru()
            for (post in lruPosts) {
                if (currentTotal <= cap) break
                postDao.deleteById(post.postId)
                val blob = mediaBlobDao.getBlob(post.mediaId)
                if (blob != null && !blob.isPinned && !blob.isLocalAsset) {
                    mediaStorageManager.deleteMediaFiles(post.mediaId)
                    currentTotal -= blob.totalBytes
                }
                if (!post.audioMediaId.isNullOrEmpty()) {
                    val audioBlob = mediaBlobDao.getBlob(post.audioMediaId)
                    if (audioBlob != null && !audioBlob.isPinned && !audioBlob.isLocalAsset) {
                        mediaStorageManager.deleteMediaFiles(post.audioMediaId)
                        currentTotal -= audioBlob.totalBytes
                    }
                }
                evictedCount++
            }
        }

        if (currentTotal > cap) {
            val lruBlobs = mediaBlobDao.getEvictableBlobsLru()
            for (blob in lruBlobs) {
                if (currentTotal <= cap) break
                postDao.deleteByMediaId(blob.mediaId)
                mediaStorageManager.deleteMediaFiles(blob.mediaId)
                currentTotal -= blob.totalBytes
                evictedCount++
            }
        }

        saveEvictedCount(evictedCount)
        return evictedCount
    }

    private suspend fun saveEvictedCount(count: Int) {
        context.retentionDataStore.edit { prefs ->
            prefs[lastEvictedKey] = count.toLong()
        }
    }
}
