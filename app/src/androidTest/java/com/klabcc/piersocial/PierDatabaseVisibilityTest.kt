package com.klabcc.piersocial

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.klabcc.piersocial.data.local.MediaBlobEntity
import com.klabcc.piersocial.data.local.PierDatabase
import com.klabcc.piersocial.data.local.PostEntity
import com.klabcc.piersocial.data.local.ProfileEntity
import com.klabcc.piersocial.data.local.StoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PierDatabaseVisibilityTest {

    private lateinit var db: PierDatabase

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, PierDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun postsAndStories_remainHiddenUntilAllRequiredMediaAndAudioBlobsComplete() = runBlocking {
        val now = System.currentTimeMillis()
        db.profileDao().upsert(
            ProfileEntity(
                peerId = "peer_remote_1",
                ed25519PubKey = "edpub",
                x25519PubKey = "xpub",
                handle = "@pier-remote1",
                fingerprint = "FP",
                displayName = "Remote Peer",
                isLocalUser = false
            )
        )

        // 1. Post without audio: incomplete image -> hidden
        db.postDao().insertIfAbsent(
            PostEntity(
                postId = "post_no_aud",
                authorPeerId = "peer_remote_1",
                caption = "No audio post",
                mediaId = "med_1",
                mediaByteSize = 1024L,
                createdAt = now,
                signature = "sig1"
            )
        )
        db.mediaBlobDao().upsert(incompleteBlob("med_1", "peer_remote_1", now))
        assertTrue(db.postDao().observeFeedPosts().first().isEmpty())
        assertTrue(db.postDao().observePostsByAuthor("peer_remote_1").first().isEmpty())

        // Complete image -> visible
        db.mediaBlobDao().upsert(completeBlob("med_1", "peer_remote_1", now))
        assertEquals(listOf("post_no_aud"), db.postDao().observeFeedPosts().first().map { it.post.postId })

        // 2. Post with audio: image complete but audio incomplete -> still hidden
        db.postDao().insertIfAbsent(
            PostEntity(
                postId = "post_with_aud",
                authorPeerId = "peer_remote_1",
                caption = "Audio post",
                mediaId = "med_2",
                mediaByteSize = 2048L,
                audioMediaId = "paud_2",
                audioDurationMs = 4500,
                audioByteSize = 16000L,
                createdAt = now + 100L,
                signature = "sig2"
            )
        )
        db.mediaBlobDao().upsert(completeBlob("med_2", "peer_remote_1", now))
        db.mediaBlobDao().upsert(incompleteBlob("paud_2", "peer_remote_1", now))
        assertEquals(listOf("post_no_aud"), db.postDao().observeFeedPosts().first().map { it.post.postId })

        // Complete audio -> now both posts visible
        db.mediaBlobDao().upsert(completeBlob("paud_2", "peer_remote_1", now))
        assertEquals(
            listOf("post_with_aud", "post_no_aud"),
            db.postDao().observeFeedPosts().first().map { it.post.postId }
        )

        // 3. Story with audio: hidden until both smed_1 and saud_1 are complete
        db.storyDao().insertIfAbsent(
            StoryEntity(
                storyId = "story_with_aud",
                authorPeerId = "peer_remote_1",
                mediaId = "smed_1",
                mediaByteSize = 4096L,
                audioMediaId = "saud_1",
                audioDurationMs = 5000,
                audioByteSize = 18000L,
                createdAt = now,
                expiresAt = now + 86_400_000L,
                signature = "sig3"
            )
        )
        db.mediaBlobDao().upsert(incompleteBlob("smed_1", "peer_remote_1", now))
        db.mediaBlobDao().upsert(incompleteBlob("saud_1", "peer_remote_1", now))
        assertTrue(db.storyDao().observeActiveStories(now).first().isEmpty())

        db.mediaBlobDao().upsert(completeBlob("smed_1", "peer_remote_1", now))
        assertTrue(db.storyDao().observeActiveStories(now).first().isEmpty())

        db.mediaBlobDao().upsert(completeBlob("saud_1", "peer_remote_1", now))
        assertEquals(
            listOf("story_with_aud"),
            db.storyDao().observeActiveStories(now).first().map { it.story.storyId }
        )
    }

    private fun incompleteBlob(mediaId: String, owner: String, now: Long) = MediaBlobEntity(
        mediaId = mediaId,
        ownerPeerId = owner,
        totalBytes = 1024L,
        chunkSize = 384,
        totalChunks = 3,
        receivedChunksCount = 1,
        receivedBitsetHex = "01",
        sha256Hex = "sha",
        localFilePath = "/tmp/$mediaId",
        isComplete = false,
        createdAt = now
    )

    private fun completeBlob(mediaId: String, owner: String, now: Long) = MediaBlobEntity(
        mediaId = mediaId,
        ownerPeerId = owner,
        totalBytes = 1024L,
        chunkSize = 384,
        totalChunks = 3,
        receivedChunksCount = 3,
        receivedBitsetHex = "07",
        sha256Hex = "sha",
        localFilePath = "/tmp/$mediaId",
        isComplete = true,
        createdAt = now
    )
}
