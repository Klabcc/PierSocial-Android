package com.klabcc.piersocial.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE isLocalUser = 1 LIMIT 1")
    fun observeLocalProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE isLocalUser = 1 LIMIT 1")
    suspend fun getLocalProfile(): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE peerId = :peerId LIMIT 1")
    fun observeProfile(peerId: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE peerId = :peerId LIMIT 1")
    suspend fun getProfile(peerId: String): ProfileEntity?

    @Query("SELECT * FROM profiles ORDER BY isLocalUser DESC, isPinned DESC, updatedAt DESC")
    fun observeAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles")
    suspend fun getAllProfiles(): List<ProfileEntity>

    @Query("SELECT peerId FROM profiles")
    suspend fun getAllProfileIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    @Query("UPDATE profiles SET isFollowing = :following WHERE peerId = :peerId")
    suspend fun setFollowing(peerId: String, following: Boolean)

    @Query("UPDATE profiles SET isMuted = :muted WHERE peerId = :peerId")
    suspend fun setMuted(peerId: String, muted: Boolean)

    @Query("UPDATE profiles SET isPinned = :pinned WHERE peerId = :peerId")
    suspend fun setPinned(peerId: String, pinned: Boolean)

    @Query("UPDATE profiles SET dmDisappearAfterMs = :durationMs WHERE peerId = :peerId")
    suspend fun setDmDisappearAfter(peerId: String, durationMs: Long)

    @Query("UPDATE profiles SET lastAccessedAt = :timestamp WHERE peerId = :peerId")
    suspend fun touch(peerId: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM profiles WHERE isPinned = 1")
    suspend fun getPinnedBytes(): Long
}

@Dao
interface PostDao {
    @Transaction
    @Query(
        """
        SELECT posts.* FROM posts
        LEFT JOIN profiles ON posts.authorPeerId = profiles.peerId
        INNER JOIN media_blobs AS img_blob ON posts.mediaId = img_blob.mediaId
        LEFT JOIN media_blobs AS aud_blob ON posts.audioMediaId = aud_blob.mediaId
        WHERE COALESCE(profiles.isMuted, 0) = 0
          AND img_blob.isComplete = 1
          AND (posts.audioMediaId IS NULL OR posts.audioMediaId = '' OR aud_blob.isComplete = 1)
        ORDER BY posts.createdAt DESC
        """
    )
    fun observeFeedPosts(): Flow<List<PostWithAuthor>>

    @Transaction
    @Query(
        """
        SELECT posts.* FROM posts
        INNER JOIN media_blobs AS img_blob ON posts.mediaId = img_blob.mediaId
        LEFT JOIN media_blobs AS aud_blob ON posts.audioMediaId = aud_blob.mediaId
        WHERE posts.authorPeerId = :peerId
          AND img_blob.isComplete = 1
          AND (posts.audioMediaId IS NULL OR posts.audioMediaId = '' OR aud_blob.isComplete = 1)
        ORDER BY posts.createdAt DESC
        """
    )
    fun observePostsByAuthor(peerId: String): Flow<List<PostWithAuthor>>

    @Transaction
    @Query(
        """
        SELECT posts.* FROM posts
        INNER JOIN media_blobs AS img_blob ON posts.mediaId = img_blob.mediaId
        LEFT JOIN media_blobs AS aud_blob ON posts.audioMediaId = aud_blob.mediaId
        WHERE posts.isPinned = 1
          AND img_blob.isComplete = 1
          AND (posts.audioMediaId IS NULL OR posts.audioMediaId = '' OR aud_blob.isComplete = 1)
        ORDER BY posts.createdAt DESC
        """
    )
    fun observePinnedPosts(): Flow<List<PostWithAuthor>>

    @Transaction
    @Query(
        """
        SELECT posts.* FROM posts
        INNER JOIN media_blobs AS img_blob ON posts.mediaId = img_blob.mediaId
        LEFT JOIN media_blobs AS aud_blob ON posts.audioMediaId = aud_blob.mediaId
        WHERE posts.isSaved = 1
          AND img_blob.isComplete = 1
          AND (posts.audioMediaId IS NULL OR posts.audioMediaId = '' OR aud_blob.isComplete = 1)
        ORDER BY posts.createdAt DESC
        """
    )
    fun observeSavedPosts(): Flow<List<PostWithAuthor>>

    @Query("SELECT * FROM posts WHERE postId = :postId LIMIT 1")
    suspend fun getPost(postId: String): PostEntity?

    @Query("SELECT postId FROM posts")
    suspend fun getAllPostIds(): List<String>

    @Query("SELECT * FROM posts ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentPosts(limit: Int = 50): List<PostEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(post: PostEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(post: PostEntity)

    @Query("UPDATE posts SET isPinned = :pinned WHERE postId = :postId")
    suspend fun setPinned(postId: String, pinned: Boolean)

    @Query("UPDATE posts SET isSaved = :saved WHERE postId = :postId")
    suspend fun setSaved(postId: String, saved: Boolean)

    @Query("UPDATE posts SET isLikedByMe = :liked, likeCount = likeCount + :delta WHERE postId = :postId")
    suspend fun updateLike(postId: String, liked: Boolean, delta: Int)

    @Query("UPDATE posts SET isLikedByMe = :liked, likeCount = :count WHERE postId = :postId")
    suspend fun setLikeState(postId: String, liked: Boolean, count: Int)

    @Query("UPDATE posts SET lastAccessedAt = :timestamp WHERE postId = :postId")
    suspend fun touch(postId: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM posts WHERE isPinned = 0 AND originatedLocally = 0 ORDER BY lastAccessedAt ASC")
    suspend fun getEvictablePostsLru(): List<PostEntity>

    @Query("DELETE FROM posts WHERE postId = :postId")
    suspend fun deleteById(postId: String)

    @Query("DELETE FROM posts WHERE (mediaId = :mediaId OR audioMediaId = :mediaId) AND isPinned = 0")
    suspend fun deleteByMediaId(mediaId: String)
}

@Dao
interface StoryDao {
    @Transaction
    @Query(
        """
        SELECT stories.* FROM stories
        LEFT JOIN profiles ON stories.authorPeerId = profiles.peerId
        INNER JOIN media_blobs AS img_blob ON stories.mediaId = img_blob.mediaId
        LEFT JOIN media_blobs AS aud_blob ON stories.audioMediaId = aud_blob.mediaId
        WHERE (stories.expiresAt > :now OR stories.isPinned = 1)
          AND COALESCE(profiles.isMuted, 0) = 0
          AND img_blob.isComplete = 1
          AND (stories.audioMediaId IS NULL OR stories.audioMediaId = '' OR aud_blob.isComplete = 1)
        ORDER BY stories.createdAt ASC
        """
    )
    fun observeActiveStories(now: Long): Flow<List<StoryWithAuthor>>

    @Query("SELECT * FROM stories WHERE storyId = :storyId LIMIT 1")
    suspend fun getStory(storyId: String): StoryEntity?

    @Query("SELECT storyId FROM stories WHERE expiresAt > :now OR isPinned = 1")
    suspend fun getActiveStoryIds(now: Long): List<String>

    @Query("SELECT * FROM stories WHERE expiresAt > :now OR isPinned = 1")
    suspend fun getActiveStories(now: Long): List<StoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(story: StoryEntity): Long

    @Query("UPDATE stories SET isViewed = 1, lastAccessedAt = :now WHERE storyId = :storyId")
    suspend fun markViewed(storyId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE stories SET isPinned = :pinned WHERE storyId = :storyId")
    suspend fun setPinned(storyId: String, pinned: Boolean)

    @Query("SELECT * FROM stories WHERE isPinned = 0 AND expiresAt <= :now")
    suspend fun getExpiredUnpinnedStories(now: Long): List<StoryEntity>

    @Query("SELECT * FROM stories WHERE isPinned = 0 AND originatedLocally = 0 ORDER BY lastAccessedAt ASC")
    suspend fun getEvictableStoriesLru(): List<StoryEntity>

    @Query("DELETE FROM stories WHERE storyId = :storyId")
    suspend fun deleteById(storyId: String)
}

@Dao
interface DirectMessageDao {
    @Query("SELECT * FROM direct_messages WHERE conversationPeerId = :peerId ORDER BY timestamp ASC")
    fun observeConversation(peerId: String): Flow<List<DirectMessageEntity>>

    @Query("SELECT * FROM direct_messages ORDER BY timestamp DESC")
    fun observeAllMessages(): Flow<List<DirectMessageEntity>>

    @Query("SELECT * FROM direct_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessage(messageId: String): DirectMessageEntity?

    @Query("SELECT messageId FROM direct_messages")
    suspend fun getAllMessageIds(): List<String>

    @Query("SELECT * FROM direct_messages WHERE deliveryState IN ('QUEUED', 'RELAYED') ORDER BY timestamp ASC")
    suspend fun getPendingOutboundMessages(): List<DirectMessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(message: DirectMessageEntity): Long

    @Query("UPDATE direct_messages SET deliveryState = :state, hopCount = :hopCount WHERE messageId = :messageId")
    suspend fun updateDeliveryState(messageId: String, state: DeliveryState, hopCount: Int)

    @Query("UPDATE direct_messages SET reactionsJson = :reactionsJson WHERE messageId = :messageId")
    suspend fun updateReactionsJson(messageId: String, reactionsJson: String)

    @Query("DELETE FROM direct_messages WHERE expiresAt > 0 AND expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int
}

@Dao
interface NoteDao {
    @Transaction
    @Query(
        """
        SELECT notes.* FROM notes
        LEFT JOIN profiles ON notes.authorPeerId = profiles.peerId
        WHERE notes.expiresAt > :now
          AND COALESCE(profiles.isMuted, 0) = 0
        ORDER BY notes.createdAt DESC
        """
    )
    fun observeActiveNotes(now: Long): Flow<List<NoteWithAuthor>>

    @Query("SELECT noteId FROM notes WHERE expiresAt > :now")
    suspend fun getActiveNoteIds(now: Long): List<String>

    @Query("SELECT * FROM notes WHERE noteId = :noteId LIMIT 1")
    suspend fun getNote(noteId: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE authorPeerId = :authorPeerId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getAuthorNote(authorPeerId: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM notes WHERE authorPeerId = :authorPeerId")
    suspend fun deleteByAuthor(authorPeerId: String)

    @Query("DELETE FROM notes WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int
}

@Dao
interface PostReactionDao {
    @Query("SELECT * FROM post_reactions WHERE emoji != ''")
    fun observeAllReactions(): Flow<List<PostReactionEntity>>

    @Query("SELECT * FROM post_reactions WHERE postId = :postId AND reactorPeerId = :reactorPeerId LIMIT 1")
    suspend fun getReaction(postId: String, reactorPeerId: String): PostReactionEntity?

    @Query("SELECT * FROM post_reactions")
    suspend fun getAllReactions(): List<PostReactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: PostReactionEntity)
}

@Dao
interface PostLikeDao {
    @Query("SELECT * FROM post_likes WHERE isLiked = 1 ORDER BY likedAt DESC")
    fun observeAllActiveLikes(): Flow<List<PostLikeEntity>>

    @Query("SELECT * FROM post_likes WHERE postId = :postId AND likerPeerId = :likerPeerId LIMIT 1")
    suspend fun getLike(postId: String, likerPeerId: String): PostLikeEntity?

    @Query("SELECT COUNT(*) FROM post_likes WHERE postId = :postId AND isLiked = 1")
    suspend fun getActiveLikeCount(postId: String): Int

    @Query("SELECT * FROM post_likes")
    suspend fun getAllLikes(): List<PostLikeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(like: PostLikeEntity)
}

@Dao
interface StoryLikeDao {
    @Query("SELECT * FROM story_likes WHERE isLiked = 1 ORDER BY likedAt DESC")
    fun observeAllActiveLikes(): Flow<List<StoryLikeEntity>>

    @Query("SELECT * FROM story_likes WHERE storyId = :storyId AND likerPeerId = :likerPeerId LIMIT 1")
    suspend fun getLike(storyId: String, likerPeerId: String): StoryLikeEntity?

    @Query("SELECT * FROM story_likes")
    suspend fun getAllLikes(): List<StoryLikeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(like: StoryLikeEntity)
}

@Dao
interface PollVoteDao {
    @Query("SELECT * FROM poll_votes")
    fun observeAllVotes(): Flow<List<PollVoteEntity>>

    @Query("SELECT * FROM poll_votes WHERE postId = :postId AND voterPeerId = :voterPeerId LIMIT 1")
    suspend fun getVote(postId: String, voterPeerId: String): PollVoteEntity?

    @Query("SELECT * FROM poll_votes")
    suspend fun getAllVotes(): List<PollVoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vote: PollVoteEntity)
}

@Dao
interface MeshPacketDao {
    @Query("SELECT * FROM mesh_packets WHERE ttl > 0 AND expiresAt > :now ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRelayablePackets(now: Long, limit: Int = 100): List<MeshPacketEntity>

    @Query("SELECT packetId FROM mesh_packets")
    suspend fun getAllPacketIds(): List<String>

    @Query("SELECT * FROM mesh_packets WHERE packetId = :packetId LIMIT 1")
    suspend fun getPacket(packetId: String): MeshPacketEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(packet: MeshPacketEntity): Long

    @Query("UPDATE mesh_packets SET lastRelayedAt = :now WHERE packetId = :packetId")
    suspend fun markRelayed(packetId: String, now: Long = System.currentTimeMillis())

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM mesh_packets")
    fun observeTotalPacketBytes(): Flow<Long>

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM mesh_packets")
    suspend fun getTotalPacketBytes(): Long

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM mesh_packets WHERE isPinned = 1")
    suspend fun getPinnedPacketBytes(): Long

    @Query("SELECT * FROM mesh_packets WHERE isPinned = 0 ORDER BY lastAccessedAt ASC")
    suspend fun getEvictablePacketsLru(): List<MeshPacketEntity>

    @Query("DELETE FROM mesh_packets WHERE expiresAt <= :now AND isPinned = 0")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM mesh_packets WHERE packetId = :packetId")
    suspend fun deleteById(packetId: String)
}

@Dao
interface MediaBlobDao {
    @Query("SELECT * FROM media_blobs")
    fun observeAllBlobs(): Flow<List<MediaBlobEntity>>

    @Query("SELECT * FROM media_blobs WHERE mediaId = :mediaId LIMIT 1")
    fun observeBlob(mediaId: String): Flow<MediaBlobEntity?>

    @Query("SELECT * FROM media_blobs WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getBlob(mediaId: String): MediaBlobEntity?

    @Query("SELECT mediaId FROM media_blobs WHERE isComplete = 1")
    suspend fun getCompleteMediaIds(): List<String>

    @Query("SELECT * FROM media_blobs WHERE isComplete = 0")
    suspend fun getIncompleteBlobs(): List<MediaBlobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(blob: MediaBlobEntity)

    @Query("UPDATE media_blobs SET isPinned = :pinned WHERE mediaId = :mediaId")
    suspend fun setPinned(mediaId: String, pinned: Boolean)

    @Query("UPDATE media_blobs SET lastAccessedAt = :timestamp WHERE mediaId = :mediaId")
    suspend fun touch(mediaId: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COALESCE(SUM(totalBytes), 0) FROM media_blobs")
    fun observeTotalMediaBytes(): Flow<Long>

    @Query("SELECT COALESCE(SUM(totalBytes), 0) FROM media_blobs")
    suspend fun getTotalMediaBytes(): Long

    @Query("SELECT COALESCE(SUM(totalBytes), 0) FROM media_blobs WHERE isPinned = 1")
    suspend fun getPinnedMediaBytes(): Long

    @Query("SELECT * FROM media_blobs WHERE isPinned = 0 AND isLocalAsset = 0 ORDER BY lastAccessedAt ASC")
    suspend fun getEvictableBlobsLru(): List<MediaBlobEntity>

    @Query("DELETE FROM media_blobs WHERE mediaId = :mediaId")
    suspend fun deleteById(mediaId: String)
}
