package com.klabcc.piersocial.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class DeliveryState {
    QUEUED,
    RELAYED,
    DELIVERED,
    FAILED
}

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val peerId: String,
    val ed25519PubKey: String,
    val x25519PubKey: String,
    val handle: String,
    val fingerprint: String,
    val displayName: String,
    val bio: String = "",
    val avatarMediaId: String? = null,
    val isLocalUser: Boolean = false,
    val isFollowing: Boolean = false,
    val isMuted: Boolean = false,
    val isPinned: Boolean = false,
    val dmDisappearAfterMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val signature: String = "",
    val byteSize: Long = 256L
)

@Entity(
    tableName = "posts",
    indices = [Index("authorPeerId"), Index("createdAt")]
)
data class PostEntity(
    @PrimaryKey val postId: String,
    val authorPeerId: String,
    val caption: String,
    val mediaId: String,
    val mediaByteSize: Long,
    val mimeType: String = "image/webp",
    val width: Int = 1080,
    val height: Int = 1080,
    val audioMediaId: String? = null,
    val audioDurationMs: Int = 0,
    val audioByteSize: Long = 0L,
    val pollQuestion: String? = null,
    val pollOptionsJson: String? = null,
    val createdAt: Long,
    val lastAccessedAt: Long = createdAt,
    val isPinned: Boolean = false,
    val isSaved: Boolean = false,
    val likeCount: Int = 0,
    val isLikedByMe: Boolean = false,
    val hopCount: Int = 0,
    val signature: String,
    val originatedLocally: Boolean = false
)

@Entity(
    tableName = "stories",
    indices = [Index("authorPeerId"), Index("expiresAt")]
)
data class StoryEntity(
    @PrimaryKey val storyId: String,
    val authorPeerId: String,
    val mediaId: String,
    val mediaByteSize: Long,
    val audioMediaId: String? = null,
    val audioDurationMs: Int = 0,
    val audioByteSize: Long = 0L,
    val caption: String = "",
    val createdAt: Long,
    val expiresAt: Long,
    val lastAccessedAt: Long = createdAt,
    val isPinned: Boolean = false,
    val isViewed: Boolean = false,
    val hopCount: Int = 0,
    val signature: String,
    val originatedLocally: Boolean = false
)

@Entity(
    tableName = "direct_messages",
    indices = [Index("conversationPeerId"), Index("timestamp")]
)
data class DirectMessageEntity(
    @PrimaryKey val messageId: String,
    val conversationPeerId: String,
    val senderPeerId: String,
    val recipientPeerId: String,
    val plaintextContent: String,
    val encryptedPayloadJson: String,
    val timestamp: Long,
    val hopCount: Int = 0,
    val deliveryState: DeliveryState = DeliveryState.QUEUED,
    val isIncoming: Boolean = false,
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val reactionsJson: String = "{}",
    val expiresAt: Long = 0L
)

@Entity(
    tableName = "notes",
    indices = [Index("authorPeerId"), Index("expiresAt")]
)
data class NoteEntity(
    @PrimaryKey val noteId: String,
    val authorPeerId: String,
    val text: String,
    val moodEmoji: String = "",
    val createdAt: Long,
    val expiresAt: Long,
    val hopCount: Int = 0,
    val signature: String,
    val originatedLocally: Boolean = false
)

@Entity(
    tableName = "post_reactions",
    primaryKeys = ["postId", "reactorPeerId"],
    indices = [Index("postId"), Index("reactedAt")]
)
data class PostReactionEntity(
    val postId: String,
    val reactorPeerId: String,
    val emoji: String,
    val reactedAt: Long,
    val signature: String,
    val reactorEd25519PubKey: String = ""
)

@Entity(
    tableName = "post_likes",
    primaryKeys = ["postId", "likerPeerId"],
    indices = [Index("postId"), Index("likedAt")]
)
data class PostLikeEntity(
    val postId: String,
    val likerPeerId: String,
    val likerEd25519PubKey: String = "",
    val isLiked: Boolean,
    val likedAt: Long,
    val signature: String
)

@Entity(
    tableName = "story_likes",
    primaryKeys = ["storyId", "likerPeerId"],
    indices = [Index("storyId"), Index("likedAt")]
)
data class StoryLikeEntity(
    val storyId: String,
    val likerPeerId: String,
    val likerEd25519PubKey: String = "",
    val isLiked: Boolean,
    val likedAt: Long,
    val signature: String
)

@Entity(
    tableName = "poll_votes",
    primaryKeys = ["postId", "voterPeerId"],
    indices = [Index("postId"), Index("votedAt")]
)
data class PollVoteEntity(
    val postId: String,
    val voterPeerId: String,
    val optionIndex: Int,
    val votedAt: Long,
    val signature: String
)

@Entity(
    tableName = "mesh_packets",
    indices = [Index("packetType"), Index("lastRelayedAt"), Index("createdAt")]
)
data class MeshPacketEntity(
    @PrimaryKey val packetId: String,
    val packetType: String,
    val originPeerId: String,
    val targetPeerId: String = "",
    val ttl: Int,
    val hopCount: Int,
    val payloadBytes: ByteArray,
    val createdAt: Long,
    val expiresAt: Long,
    val lastRelayedAt: Long = createdAt,
    val lastAccessedAt: Long = createdAt,
    val byteSize: Long,
    val isPinned: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshPacketEntity) return false
        return packetId == other.packetId
    }

    override fun hashCode(): Int = packetId.hashCode()
}

@Entity(
    tableName = "media_blobs",
    indices = [Index("lastAccessedAt"), Index("isComplete")]
)
data class MediaBlobEntity(
    @PrimaryKey val mediaId: String,
    val ownerPeerId: String,
    val totalBytes: Long,
    val chunkSize: Int,
    val totalChunks: Int,
    val receivedChunksCount: Int,
    val receivedBitsetHex: String,
    val sha256Hex: String,
    val localFilePath: String,
    val isComplete: Boolean,
    val isPinned: Boolean = false,
    val isLocalAsset: Boolean = false,
    val createdAt: Long,
    val lastAccessedAt: Long = createdAt
)

data class PostWithAuthor(
    @Embedded val post: PostEntity,
    @Relation(parentColumn = "authorPeerId", entityColumn = "peerId")
    val author: ProfileEntity?,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId")
    val mediaBlob: MediaBlobEntity?,
    @Relation(parentColumn = "audioMediaId", entityColumn = "mediaId")
    val audioBlob: MediaBlobEntity? = null
)

data class StoryWithAuthor(
    @Embedded val story: StoryEntity,
    @Relation(parentColumn = "authorPeerId", entityColumn = "peerId")
    val author: ProfileEntity?,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId")
    val mediaBlob: MediaBlobEntity?,
    @Relation(parentColumn = "audioMediaId", entityColumn = "mediaId")
    val audioBlob: MediaBlobEntity? = null
)

data class NoteWithAuthor(
    @Embedded val note: NoteEntity,
    @Relation(parentColumn = "authorPeerId", entityColumn = "peerId")
    val author: ProfileEntity?
)

data class ConversationSummary(
    val peer: ProfileEntity,
    val lastMessage: DirectMessageEntity?
)
