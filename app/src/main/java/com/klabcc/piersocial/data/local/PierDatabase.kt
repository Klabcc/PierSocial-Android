package com.klabcc.piersocial.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class PierConverters {
    @TypeConverter
    fun fromDeliveryState(state: DeliveryState): String = state.name

    @TypeConverter
    fun toDeliveryState(value: String): DeliveryState =
        runCatching { DeliveryState.valueOf(value) }.getOrDefault(DeliveryState.QUEUED)
}

@Database(
    entities = [
        ProfileEntity::class,
        PostEntity::class,
        StoryEntity::class,
        DirectMessageEntity::class,
        NoteEntity::class,
        PostReactionEntity::class,
        PostLikeEntity::class,
        StoryLikeEntity::class,
        PollVoteEntity::class,
        MeshPacketEntity::class,
        MediaBlobEntity::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(PierConverters::class)
abstract class PierDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun postDao(): PostDao
    abstract fun storyDao(): StoryDao
    abstract fun directMessageDao(): DirectMessageDao
    abstract fun noteDao(): NoteDao
    abstract fun postReactionDao(): PostReactionDao
    abstract fun postLikeDao(): PostLikeDao
    abstract fun storyLikeDao(): StoryLikeDao
    abstract fun pollVoteDao(): PollVoteDao
    abstract fun meshPacketDao(): MeshPacketDao
    abstract fun mediaBlobDao(): MediaBlobDao

    companion object {
        @Volatile
        private var INSTANCE: PierDatabase? = null

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE post_reactions ADD COLUMN reactorEd25519PubKey TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `post_likes` (
                        `postId` TEXT NOT NULL,
                        `likerPeerId` TEXT NOT NULL,
                        `likerEd25519PubKey` TEXT NOT NULL DEFAULT '',
                        `isLiked` INTEGER NOT NULL,
                        `likedAt` INTEGER NOT NULL,
                        `signature` TEXT NOT NULL,
                        PRIMARY KEY(`postId`, `likerPeerId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_post_likes_postId` ON `post_likes` (`postId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_post_likes_likedAt` ON `post_likes` (`likedAt`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `story_likes` (
                        `storyId` TEXT NOT NULL,
                        `likerPeerId` TEXT NOT NULL,
                        `likerEd25519PubKey` TEXT NOT NULL DEFAULT '',
                        `isLiked` INTEGER NOT NULL,
                        `likedAt` INTEGER NOT NULL,
                        `signature` TEXT NOT NULL,
                        PRIMARY KEY(`storyId`, `likerPeerId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_story_likes_storyId` ON `story_likes` (`storyId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_story_likes_likedAt` ON `story_likes` (`likedAt`)")
            }
        }

        fun getInstance(context: Context): PierDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PierDatabase::class.java,
                    "piersocial_mesh.db"
                )
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
