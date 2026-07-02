package soy.iko.opencode.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import soy.iko.opencode.OpencodeApp
import soy.iko.opencode.data.model.PermissionResponse

/**
 * Handles the action buttons on the app's notifications so the user can respond without
 * opening the app — the core of the "kick it off and walk away" flow:
 *
 *  - [ACTION_PERMISSION] with a [PermissionResponse] → replies to a paused tool's permission
 *    request (Allow once / Always / Reject) via the active connection.
 *  - [ACTION_REPLY] with a [RemoteInput] text payload → sends a follow-up prompt to a session
 *    that just finished a run.
 *
 * The receiver is not exported (see the manifest); the [PendingIntent]s that target it are
 * created by the app itself. Work is done on the process-lived app scope via [OpencodeApp]'s
 * container, and [goAsync] keeps the receiver alive until the (network) call resolves.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? OpencodeApp)?.container ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return
        when (intent.action) {
            ACTION_PERMISSION -> {
                val permissionId = intent.getStringExtra(EXTRA_PERMISSION_ID)?.takeIf { it.isNotBlank() } ?: return
                val response = PermissionResponse.entries.firstOrNull {
                    it.wire == intent.getStringExtra(EXTRA_RESPONSE)
                } ?: return
                // Dismiss the notification immediately for responsive feedback; the reply
                // itself continues in the background.
                SessionNotifications.cancelPermission(context, sessionId)
                val pending = goAsync()
                container.respondToPermissionFromNotification(sessionId, permissionId, response) {
                    pending.finish()
                }
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return
                val pending = goAsync()
                container.sendPromptFromNotification(sessionId, text) {
                    // The follow-up is on its way; clear the "session ready" notification.
                    // A fresh run (and its foreground indicator) now signals progress.
                    SessionNotifications.cancel(context, sessionId)
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_PERMISSION = "soy.iko.opencode.action.PERMISSION"
        const val ACTION_REPLY = "soy.iko.opencode.action.REPLY"
        const val EXTRA_SESSION_ID = "soy.iko.opencode.extra.SESSION_ID"
        const val EXTRA_PERMISSION_ID = "soy.iko.opencode.extra.PERMISSION_ID"
        const val EXTRA_RESPONSE = "soy.iko.opencode.extra.RESPONSE"
        const val KEY_REPLY_TEXT = "soy.iko.opencode.key.REPLY_TEXT"
    }
}
