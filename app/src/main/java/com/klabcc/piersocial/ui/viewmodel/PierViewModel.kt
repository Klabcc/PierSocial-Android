package com.klabcc.piersocial.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.klabcc.piersocial.PierSocialApp
import com.klabcc.piersocial.crypto.IdentityKeys
import com.klabcc.piersocial.data.local.ConversationSummary
import com.klabcc.piersocial.data.local.DirectMessageEntity
import com.klabcc.piersocial.data.local.NoteWithAuthor
import com.klabcc.piersocial.data.local.PollVoteEntity
import com.klabcc.piersocial.data.local.PostLikeEntity
import com.klabcc.piersocial.data.local.PostReactionEntity
import com.klabcc.piersocial.data.local.PostWithAuthor
import com.klabcc.piersocial.data.local.ProfileEntity
import com.klabcc.piersocial.data.local.StoryLikeEntity
import com.klabcc.piersocial.data.local.StoryWithAuthor
import com.klabcc.piersocial.data.retention.RetentionCapPreset
import com.klabcc.piersocial.data.retention.StorageStats
import com.klabcc.piersocial.data.storage.ChunkTransferProgress
import com.klabcc.piersocial.data.storage.PhotoAdjustments
import com.klabcc.piersocial.data.storage.StoryOverlayData
import com.klabcc.piersocial.mesh.DiscoveredBlePeer
import com.klabcc.piersocial.mesh.GattConnectionInfo
import com.klabcc.piersocial.mesh.MeshLogEntry
import com.klabcc.piersocial.mesh.MeshRadioState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class StoryRingGroup(
    val author: ProfileEntity,
    val stories: List<StoryWithAuthor>,
    val hasUnviewed: Boolean
)

class PierViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PierSocialApp
    val repository = app.repository
    val meshController = app.meshController
    private val appPreferences = app.appPreferences

    val localIdentity: IdentityKeys get() = repository.localIdentity

    val profileSetupCompleted: StateFlow<Boolean?> = appPreferences.profileSetupCompletedFlow
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val developerMode: StateFlow<Boolean> = appPreferences.developerModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val dmNotificationsEnabled: StateFlow<Boolean> = appPreferences.dmNotificationsEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val localProfile: StateFlow<ProfileEntity?> = repository.localProfileFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allProfiles: StateFlow<List<ProfileEntity>> = repository.allProfilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val feedPosts: StateFlow<List<PostWithAuthor>> = repository.feedPostsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedPosts: StateFlow<List<PostWithAuthor>> = repository.savedPostsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeNotes: StateFlow<List<NoteWithAuthor>> = repository.observeActiveNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val postReactionsByPostId: StateFlow<Map<String, List<PostReactionEntity>>> =
        repository.postReactionsFlow
            .map { list -> list.groupBy { it.postId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val postLikesByPostId: StateFlow<Map<String, List<PostLikeEntity>>> =
        repository.postLikesFlow
            .map { list -> list.groupBy { it.postId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val storyLikesByStoryId: StateFlow<Map<String, List<StoryLikeEntity>>> =
        repository.storyLikesFlow
            .map { list -> list.groupBy { it.storyId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val pollVotesByPostId: StateFlow<Map<String, List<PollVoteEntity>>> =
        repository.pollVotesFlow
            .map { list -> list.groupBy { it.postId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val storyGroups: StateFlow<List<StoryRingGroup>> = repository.observeActiveStories()
        .map { stories ->
            stories.groupBy { it.story.authorPeerId }
                .mapNotNull { (_, group) ->
                    val author = group.firstOrNull()?.author ?: return@mapNotNull null
                    StoryRingGroup(
                        author = author,
                        stories = group.sortedBy { it.story.createdAt },
                        hasUnviewed = group.any { !it.story.isViewed }
                    )
                }
                .sortedWith(
                    compareByDescending<StoryRingGroup> { it.author.isLocalUser }
                        .thenByDescending { it.hasUnviewed }
                )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversationSummaries: StateFlow<List<ConversationSummary>> =
        repository.observeConversationSummaries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val storageStats: StateFlow<StorageStats> = repository.storageStatsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StorageStats())

    val pinnedPosts: StateFlow<List<PostWithAuthor>> = repository.observePinnedPosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeTransfers: StateFlow<Map<String, ChunkTransferProgress>> = repository.activeTransfersFlow
    val radioState: StateFlow<MeshRadioState> = meshController.radioState
    val discoveredPeers: StateFlow<List<DiscoveredBlePeer>> = meshController.discoveredPeers
    val activeConnections: StateFlow<List<GattConnectionInfo>> = meshController.activeConnections
    val meshLogs: StateFlow<List<MeshLogEntry>> = meshController.meshLogs

    fun observeProfile(peerId: String): Flow<ProfileEntity?> = repository.observeProfile(peerId)
    fun observePostsByAuthor(peerId: String): Flow<List<PostWithAuthor>> = repository.observePostsByAuthor(peerId)
    fun observeConversation(peerId: String): Flow<List<DirectMessageEntity>> = repository.observeConversation(peerId)

    fun startMeshService() {
        meshController.startMeshService()
    }

    fun stopMeshService() {
        meshController.stopMeshService()
    }

    fun refreshPermissions() {
        meshController.refreshPermissionAndBluetoothState()
    }

    fun forceSync() {
        meshController.forceSync()
    }

    fun setDeveloperMode(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setDeveloperModeEnabled(enabled)
        }
    }

    fun setDmNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setDmNotificationsEnabled(enabled)
            if (!enabled) {
                app.dmNotificationHelper.clearAll()
            }
        }
    }

    fun setActiveChatPeerId(peerId: String?) {
        meshController.activeChatPeerId = peerId
        if (!peerId.isNullOrBlank()) {
            app.dmNotificationHelper.cancelForPeer(peerId)
        }
    }

    fun setAppInForeground(inForeground: Boolean) {
        meshController.isAppInForeground = inForeground
        if (inForeground) {
            meshController.activeChatPeerId?.let { activeId ->
                app.dmNotificationHelper.cancelForPeer(activeId)
            }
        }
    }

    fun completeProfileSetup(
        displayName: String,
        bio: String,
        avatarBitmap: Bitmap?,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            repository.updateLocalProfile(displayName, bio, avatarBitmap)
            appPreferences.setProfileSetupCompleted(true)
            meshController.broadcastLocalProfileUpdate()
            onComplete()
        }
    }

    fun toggleLike(postId: String) {
        viewModelScope.launch {
            val result = repository.toggleLike(postId) ?: return@launch
            meshController.broadcastPostLike(result.second)
        }
    }

    fun ensureLiked(postId: String) {
        viewModelScope.launch {
            val result = repository.ensureLiked(postId) ?: return@launch
            meshController.broadcastPostLike(result.second)
        }
    }

    fun toggleStoryLike(storyId: String) {
        viewModelScope.launch {
            val result = repository.toggleStoryLike(storyId) ?: return@launch
            meshController.broadcastStoryLike(result.second)
        }
    }

    fun ensureStoryLiked(storyId: String) {
        viewModelScope.launch {
            val result = repository.ensureStoryLiked(storyId) ?: return@launch
            meshController.broadcastStoryLike(result.second)
        }
    }

    fun toggleSavePost(postId: String) {
        viewModelScope.launch { repository.toggleSavePost(postId) }
    }

    fun reactToPost(postId: String, emoji: String) {
        viewModelScope.launch {
            val result = repository.reactToPost(postId, emoji) ?: return@launch
            meshController.broadcastPostReaction(result.second)
        }
    }

    fun voteOnPoll(postId: String, optionIndex: Int) {
        viewModelScope.launch {
            val result = repository.voteOnPoll(postId, optionIndex) ?: return@launch
            meshController.broadcastPollVote(result.second)
        }
    }

    fun publishNote(text: String, moodEmoji: String = "") {
        viewModelScope.launch {
            val result = repository.publishNote(text, moodEmoji) ?: return@launch
            meshController.broadcastNote(result.second)
        }
    }

    fun clearLocalNote() {
        viewModelScope.launch {
            val wire = repository.clearLocalNote()
            meshController.broadcastNote(wire)
        }
    }

    fun togglePinPost(postId: String) {
        viewModelScope.launch { repository.togglePinPost(postId) }
    }

    fun togglePinStory(storyId: String) {
        viewModelScope.launch { repository.togglePinStory(storyId) }
    }

    fun togglePinProfile(peerId: String) {
        viewModelScope.launch { repository.togglePinProfile(peerId) }
    }

    fun markStoryViewed(storyId: String) {
        viewModelScope.launch { repository.markStoryViewed(storyId) }
    }

    fun toggleFollow(peerId: String) {
        viewModelScope.launch { repository.toggleFollow(peerId) }
    }

    fun toggleMute(peerId: String) {
        viewModelScope.launch { repository.toggleMute(peerId) }
    }

    fun updateProfile(displayName: String, bio: String, avatarBitmap: Bitmap? = null) {
        viewModelScope.launch {
            repository.updateLocalProfile(displayName, bio, avatarBitmap)
            meshController.broadcastLocalProfileUpdate()
        }
    }

    fun publishFeedPost(
        bitmap: Bitmap,
        adjustments: PhotoAdjustments,
        caption: String,
        audioFile: File? = null,
        audioDurationMs: Int = 0,
        pollQuestion: String? = null,
        pollOptions: List<String> = emptyList(),
        onPublished: () -> Unit
    ) {
        viewModelScope.launch {
            val (post, _) = repository.createFeedPost(
                sourceBitmap = bitmap,
                adjustments = adjustments,
                caption = caption,
                audioFile = audioFile,
                audioDurationMs = audioDurationMs,
                pollQuestion = pollQuestion,
                pollOptions = pollOptions
            )
            meshController.broadcastNewPost(post)
            onPublished()
        }
    }

    fun publishStory(
        bitmap: Bitmap?,
        adjustments: PhotoAdjustments,
        overlay: StoryOverlayData,
        caption: String,
        audioFile: File? = null,
        audioDurationMs: Int = 0,
        onPublished: () -> Unit
    ) {
        viewModelScope.launch {
            val (story, _) = repository.createStory(
                sourceBitmap = bitmap,
                adjustments = adjustments,
                overlay = overlay,
                caption = caption,
                audioFile = audioFile,
                audioDurationMs = audioDurationMs
            )
            meshController.broadcastNewStory(story)
            onPublished()
        }
    }

    fun sendDirectMessage(
        recipientPeerId: String,
        text: String,
        replyToMessageId: String? = null,
        replyToPreview: String? = null
    ) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.sendDirectMessageFast(
                recipientPeerId = recipientPeerId,
                plaintext = text,
                replyToMessageId = replyToMessageId,
                replyToPreview = replyToPreview
            ) { entity, wire ->
                meshController.broadcastDirectMessageNow(entity, wire)
            }
        }
    }

    fun sendDmReaction(recipientPeerId: String, targetMessageId: String, emoji: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.sendDmReactionFast(recipientPeerId, targetMessageId, emoji) { entity, wire ->
                meshController.broadcastDirectMessageNow(entity, wire)
            }
        }
    }

    fun setDmDisappearTimer(peerId: String, durationMs: Long) {
        viewModelScope.launch {
            repository.setDmDisappearTimer(peerId, durationMs)
        }
    }

    fun setRetentionCap(preset: RetentionCapPreset) {
        viewModelScope.launch { repository.retentionManager.setRetentionCap(preset) }
    }

    fun setAutoEvict(enabled: Boolean) {
        viewModelScope.launch { repository.retentionManager.setAutoEvictEnabled(enabled) }
    }

    fun runEvictionNow(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = repository.retentionManager.enforceRetentionPolicy(force = true)
            onResult(count)
        }
    }
}
