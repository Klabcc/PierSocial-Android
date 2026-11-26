package com.klabcc.piersocial.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.klabcc.piersocial.MainActivity
import com.klabcc.piersocial.R
import com.klabcc.piersocial.data.repository.IncomingDmEvent
import java.util.concurrent.ConcurrentHashMap

class DmNotificationHelper(private val context: Context) {

    data class UnreadMessageLine(
        val messageId: String,
        val senderName: String,
        val text: String,
        val timestamp: Long,
        val isLocalUser: Boolean
    )

    private val appContext = context.applicationContext
    private val unreadByPeer = ConcurrentHashMap<String, MutableList<UnreadMessageLine>>()

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Direct Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Encrypted peer-to-peer direct message push notifications"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                setSound(soundUri, audioAttrs)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(): Boolean {
        val compatEnabled = runCatching {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }.getOrDefault(true)
        if (!compatEnabled) return false

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun showIncomingDmNotification(
        event: IncomingDmEvent,
        localDisplayName: String = "You"
    ): Boolean {
        ensureChannel()
        if (!hasNotificationPermission()) return false

        val lines = unreadByPeer.compute(event.senderPeerId) { _, existing ->
            val list = existing ?: mutableListOf()
            if (list.none { it.messageId == event.messageId }) {
                list.add(
                    UnreadMessageLine(
                        messageId = event.messageId,
                        senderName = event.senderDisplayName,
                        text = event.previewText,
                        timestamp = event.timestamp,
                        isLocalUser = false
                    )
                )
            }
            if (list.size > MAX_UNREAD_LINES) {
                list.subList(list.size - MAX_UNREAD_LINES, list.size).toMutableList()
            } else {
                list
            }
        }?.toList().orEmpty()

        return lines.isNotEmpty() && postPeerNotification(
            peerId = event.senderPeerId,
            peerDisplayName = event.senderDisplayName,
            lines = lines,
            localDisplayName = localDisplayName,
            onlyAlertOnce = false
        )
    }

    @SuppressLint("MissingPermission")
    fun appendLocalReplyAndUpdate(
        peerId: String,
        peerDisplayName: String,
        replyText: String,
        localDisplayName: String = "You"
    ): Boolean {
        ensureChannel()
        if (!hasNotificationPermission()) return false

        val lines = unreadByPeer.compute(peerId) { _, existing ->
            val list = existing ?: mutableListOf()
            list.add(
                UnreadMessageLine(
                    messageId = "local_${System.nanoTime()}",
                    senderName = localDisplayName,
                    text = replyText,
                    timestamp = System.currentTimeMillis(),
                    isLocalUser = true
                )
            )
            if (list.size > MAX_UNREAD_LINES) {
                list.subList(list.size - MAX_UNREAD_LINES, list.size).toMutableList()
            } else {
                list
            }
        }?.toList().orEmpty()

        return lines.isNotEmpty() && postPeerNotification(
            peerId = peerId,
            peerDisplayName = peerDisplayName,
            lines = lines,
            localDisplayName = localDisplayName,
            onlyAlertOnce = true
        )
    }

    fun cancelForPeer(peerId: String) {
        unreadByPeer.remove(peerId)
        val nm = NotificationManagerCompat.from(appContext)
        runCatching {
            nm.cancel(notificationIdForPeer(peerId))
            if (unreadByPeer.isEmpty()) {
                nm.cancel(SUMMARY_NOTIFICATION_ID)
            }
        }
    }

    fun clearAll() {
        val peerIds = unreadByPeer.keys.toList()
        unreadByPeer.clear()
        val nm = NotificationManagerCompat.from(appContext)
        runCatching {
            peerIds.forEach { nm.cancel(notificationIdForPeer(it)) }
            nm.cancel(SUMMARY_NOTIFICATION_ID)
        }
    }

    fun unreadCountForPeer(peerId: String): Int = unreadByPeer[peerId]?.size ?: 0

    @SuppressLint("MissingPermission")
    private fun postPeerNotification(
        peerId: String,
        peerDisplayName: String,
        lines: List<UnreadMessageLine>,
        localDisplayName: String,
        onlyAlertOnce: Boolean
    ): Boolean {
        val notifId = notificationIdForPeer(peerId)
        val localPerson = Person.Builder()
            .setName(localDisplayName.ifBlank { "You" })
            .setKey("local_user")
            .build()
        val remotePerson = Person.Builder()
            .setName(peerDisplayName)
            .setKey(peerId)
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(localPerson)
        if (lines.size > 1) {
            messagingStyle.setConversationTitle("$peerDisplayName (${lines.size})")
        }
        lines.forEach { line ->
            messagingStyle.addMessage(
                line.text,
                line.timestamp,
                if (line.isLocalUser) localPerson else remotePerson
            )
        }

        val openIntent = buildOpenChatIntent(appContext, peerId)
        val openPendingIntent = PendingIntent.getActivity(
            appContext,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply to $peerDisplayName")
            .build()

        val replyIntent = Intent(appContext, DmNotificationReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_DM_PEER_ID, peerId)
            putExtra(EXTRA_DM_PEER_NAME, peerDisplayName)
        }
        val replyMutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            appContext,
            notifId + 10_000,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or replyMutableFlag
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        val markReadIntent = Intent(appContext, DmNotificationReceiver::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_DM_PEER_ID, peerId)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            appContext,
            notifId + 20_000,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val markReadAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Mark read",
            markReadPendingIntent
        ).build()

        val latest = lines.last()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(peerDisplayName)
            .setContentText(latest.text)
            .setStyle(messagingStyle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setGroup(GROUP_KEY)
            .setWhen(latest.timestamp)
            .setShowWhen(true)
            .setContentIntent(openPendingIntent)
            .addAction(replyAction)
            .addAction(markReadAction)
            .build()

        return runCatching {
            val nm = NotificationManagerCompat.from(appContext)
            nm.notify(notifId, notification)
            if (unreadByPeer.size >= 2) {
                val totalUnread = unreadByPeer.values.sumOf { it.size }
                val summary = NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Direct Messages")
                    .setContentText("$totalUnread new messages across ${unreadByPeer.size} chats")
                    .setStyle(
                        NotificationCompat.InboxStyle()
                            .setSummaryText("$totalUnread unread E2EE messages")
                    )
                    .setGroup(GROUP_KEY)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .build()
                nm.notify(SUMMARY_NOTIFICATION_ID, summary)
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        const val CHANNEL_ID = "pier_dm_messages"
        const val GROUP_KEY = "pier_dm_group"
        const val EXTRA_DM_PEER_ID = "extra_dm_peer_id"
        const val EXTRA_DM_PEER_NAME = "extra_dm_peer_name"
        const val ACTION_REPLY = "com.klabcc.piersocial.notifications.ACTION_DM_REPLY"
        const val ACTION_MARK_READ = "com.klabcc.piersocial.notifications.ACTION_DM_MARK_READ"
        const val KEY_TEXT_REPLY = "key_dm_reply_text"
        const val SUMMARY_NOTIFICATION_ID = 3999
        private const val MAX_UNREAD_LINES = 6

        fun notificationIdForPeer(peerId: String): Int = 2000 + (peerId.hashCode() and 0x3FF)

        fun buildOpenChatIntent(context: Context, peerId: String): Intent {
            return Intent(context, MainActivity::class.java).apply {
                action = "com.klabcc.piersocial.OPEN_DM_$peerId"
                putExtra(EXTRA_DM_PEER_ID, peerId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }
}
