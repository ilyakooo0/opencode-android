package soy.iko.opencode.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import soy.iko.opencode.OpencodeApp
import soy.iko.opencode.data.model.PermissionResponse
import soy.iko.opencode.data.network.NetworkConfig
import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * A watchdog finishes the [PendingResult] within [NetworkConfig.notificationReceiverTimeoutMs]
 * so a slow network call (e.g. a permission respond retried with exponential backoff) can't
 * ANR the receiver. The underlying call keeps running on the app scope; only the receiver's
 * lifetime is bounded. [finishOnce] guards against a double finish() (which would throw).
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? OpencodeApp)?.container ?: return
        if (intent.action == ACTION_STOP_RUN) {
            // Stop action from the foreground-service notification. The session id may be
            // absent if multiple runs were active (generic notification); in that case we
            // can't target a specific run — just let it ride.
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return
            val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }
            val pending = goAsync()
            val finished = AtomicBoolean(false)
            var watchdogThread: Thread? = null
            val finishOnce = {
                if (finished.compareAndSet(false, true)) {
                    watchdogThread?.interrupt()
                    pending.finish()
                }
            }
            watchdogThread = watchdog(finishOnce)
            container.abortRunFromNotification(sessionId, profileId) { _ -> finishOnce() }
            return
        }
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return
        when (intent.action) {
            ACTION_MARK_READ -> {
                // "Mark read" from a completion notification — clear the unread badge and
                // dismiss the notification without opening the session.
                container.markSessionRead(sessionId)
                SessionNotifications.cancel(context, sessionId)
                return
            }
            ACTION_PERMISSION -> {
                val permissionId = intent.getStringExtra(EXTRA_PERMISSION_ID)?.takeIf { it.isNotBlank() } ?: return
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }
                val response = PermissionResponse.entries.firstOrNull {
                    it.wire == intent.getStringExtra(EXTRA_RESPONSE)
                } ?: return
                val pending = goAsync()
                val finished = AtomicBoolean(false)
                var watchdogThread: Thread? = null
                val finishOnce = {
                    if (finished.compareAndSet(false, true)) {
                        watchdogThread?.interrupt()
                        pending.finish()
                    }
                }
                watchdogThread = watchdog(finishOnce)
                container.respondToPermissionFromNotification(sessionId, permissionId, response, profileId) { success ->
                    // Only dismiss the notification once the tool was actually answered. If
                    // there was no live connection (respond is a no-op) or the call failed,
                    // leave it up so the user can retry — otherwise it vanishes with the
                    // permission request left unanswered.
                    if (success) SessionNotifications.cancelPermission(context, sessionId)
                    finishOnce()
                }
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (text == null) {
                    // The RemoteInput fired but the reply was empty/whitespace. SystemUI locks the
                    // inline field in a "sending…" state until the notification is updated or
                    // cancelled — cancel it so the field doesn't stay stuck with nothing to send.
                    SessionNotifications.cancel(context, sessionId)
                    return
                }
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }
                val pending = goAsync()
                val finished = AtomicBoolean(false)
                var watchdogThread: Thread? = null
                val finishOnce = {
                    if (finished.compareAndSet(false, true)) {
                        watchdogThread?.interrupt()
                        pending.finish()
                    }
                }
                watchdogThread = watchdog(finishOnce)
                container.sendPromptFromNotification(sessionId, text, profileId) { enqueued ->
                    // The reply is durably queued (and flushed now if online). Show a brief
                    // "Sent" confirmation by updating the completion notification's text, then
                    // let it auto-cancel on tap — instead of vanishing silently. If the enqueue
                    // failed, post a "reply not sent" error: leaving the completion notification
                    // up strands SystemUI's RemoteInput field in a permanent "sending…" spinner
                    // that can't be resubmitted, so "leave it up to retry" doesn't actually let
                    // the user retry.
                    if (enqueued) {
                        SessionNotifications.postReplySent(context, sessionId)
                    } else {
                        SessionNotifications.postReplyFailed(context, sessionId, profileId)
                    }
                    finishOnce()
                }
            }
            ACTION_RETRY_LAST -> {
                // "Retry last" from an error notification: re-send the most recent user prompt
                // for this session without opening the app. Cancels the error notification on
                // success so the user sees the retry took effect.
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }
                val pending = goAsync()
                val finished = AtomicBoolean(false)
                var watchdogThread: Thread? = null
                val finishOnce = {
                    if (finished.compareAndSet(false, true)) {
                        watchdogThread?.interrupt()
                        pending.finish()
                    }
                }
                watchdogThread = watchdog(finishOnce)
                container.retryLastFromNotification(sessionId, profileId) { success ->
                    if (success) SessionNotifications.cancel(context, sessionId)
                    finishOnce()
                }
            }
        }
    }

    /** Schedule a deadline after which the receiver's [PendingResult] is finished even if
     *  the network call hasn't resolved, so the system can't ANR the receiver. The
     *  underlying work continues on the app scope. Returns the watchdog thread so the
     *  caller can [interrupt][Thread.interrupt] it when work completes early, releasing
     *  the sleeping thread immediately instead of leaving it for the full timeout. */
    private fun watchdog(finishOnce: () -> Unit): Thread =
        Thread {
            try {
                Thread.sleep(NetworkConfig.notificationReceiverTimeoutMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            finishOnce()
        }.apply { isDaemon = true; name = "notif-receiver-watchdog"; start() }

    companion object {
        const val ACTION_PERMISSION = "soy.iko.opencode.action.PERMISSION"
        const val ACTION_REPLY = "soy.iko.opencode.action.REPLY"
        const val ACTION_STOP_RUN = "soy.iko.opencode.action.STOP_RUN"
        const val ACTION_MARK_READ = "soy.iko.opencode.action.MARK_READ"
        const val ACTION_RETRY_LAST = "soy.iko.opencode.action.RETRY_LAST"
        const val EXTRA_SESSION_ID = "soy.iko.opencode.extra.SESSION_ID"
        const val EXTRA_PERMISSION_ID = "soy.iko.opencode.extra.PERMISSION_ID"
        const val EXTRA_PROFILE_ID = "soy.iko.opencode.extra.PROFILE_ID"
        const val EXTRA_RESPONSE = "soy.iko.opencode.extra.RESPONSE"
        const val KEY_REPLY_TEXT = "soy.iko.opencode.key.REPLY_TEXT"
    }
}
