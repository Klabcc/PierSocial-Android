package com.klabcc.piersocial

import android.app.Application
import com.klabcc.piersocial.crypto.CryptoManager
import com.klabcc.piersocial.data.local.PierDatabase
import com.klabcc.piersocial.data.preferences.AppPreferencesManager
import com.klabcc.piersocial.data.repository.PierRepository
import com.klabcc.piersocial.data.retention.RetentionManager
import com.klabcc.piersocial.data.storage.MediaStorageManager
import com.klabcc.piersocial.mesh.MeshController
import com.klabcc.piersocial.notifications.DmNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PierSocialApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: PierDatabase
        private set
    lateinit var appPreferences: AppPreferencesManager
        private set
    lateinit var cryptoManager: CryptoManager
        private set
    lateinit var mediaStorageManager: MediaStorageManager
        private set
    lateinit var retentionManager: RetentionManager
        private set
    lateinit var repository: PierRepository
        private set
    lateinit var meshController: MeshController
        private set
    lateinit var dmNotificationHelper: DmNotificationHelper
        private set

    override fun onCreate() {
        super.onCreate()
        database = PierDatabase.getInstance(this)
        appPreferences = AppPreferencesManager(this)
        cryptoManager = CryptoManager(this)
        mediaStorageManager = MediaStorageManager(this, database.mediaBlobDao())
        retentionManager = RetentionManager(
            context = this,
            profileDao = database.profileDao(),
            postDao = database.postDao(),
            storyDao = database.storyDao(),
            meshPacketDao = database.meshPacketDao(),
            mediaBlobDao = database.mediaBlobDao(),
            mediaStorageManager = mediaStorageManager,
            noteDao = database.noteDao(),
            directMessageDao = database.directMessageDao()
        )
        repository = PierRepository(
            context = this,
            database = database,
            cryptoManager = cryptoManager,
            mediaStorageManager = mediaStorageManager,
            retentionManager = retentionManager
        )
        meshController = MeshController(this, repository)
        dmNotificationHelper = DmNotificationHelper(this).apply {
            ensureChannel()
        }

        appScope.launch {
            repository.ensureLocalProfileInitialized()
            retentionManager.enforceRetentionPolicy()
        }

        appScope.launch {
            repository.incomingDmEvents.collect { event ->
                val enabled = runCatching { appPreferences.dmNotificationsEnabledFlow.first() }
                    .getOrDefault(true)
                if (enabled && meshController.shouldShowDmNotification(event.senderPeerId)) {
                    val localName = runCatching {
                        repository.localProfileFlow.first()?.displayName
                    }.getOrNull() ?: "You"
                    dmNotificationHelper.showIncomingDmNotification(event, localName)
                }
            }
        }
    }
}
