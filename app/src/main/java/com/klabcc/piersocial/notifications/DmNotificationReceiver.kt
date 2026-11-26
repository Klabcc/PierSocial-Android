package com.klabcc.piersocial.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.klabcc.piersocial.PierSocialApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DmNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val peerId = intent.getStringExtra(DmNotificationHelper.EXTRA_DM_PEER_ID) ?: return
        val app = context.applicationContext as? PierSocialApp ?: return

        when (intent.action) {
            DmNotificationHelper.ACTION_MARK_READ -> {
                app.dmNotificationHelper.cancelForPeer(peerId)
            }

            DmNotificationHelper.ACTION_REPLY -> {
                val replyText = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(DmNotificationHelper.KEY_TEXT_REPLY)
                    ?.toString()
                    ?.trim()
                if (replyText.isNullOrEmpty()) {
                    app.dmNotificationHelper.cancelForPeer(peerId)
                    return
                }
                val peerName = intent.getStringExtra(DmNotificationHelper.EXTRA_DM_PEER_NAME)
                    ?: peerId.take(8).uppercase()

                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val localName = runCatching {
                            app.repository.localProfileFlow.first()?.displayName
                        }.getOrNull() ?: "You"

                        val sent = app.repository.sendDirectMessageFast(
                            recipientPeerId = peerId,
                            plaintext = replyText
                        ) { entity, wire ->
                            app.meshController.broadcastDirectMessageNow(entity, wire)
                        }
                        if (sent != null) {
                            app.dmNotificationHelper.appendLocalReplyAndUpdate(
                                peerId = peerId,
                                peerDisplayName = peerName,
                                replyText = replyText,
                                localDisplayName = localName
                            )
                        } else {
                            app.dmNotificationHelper.cancelForPeer(peerId)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
