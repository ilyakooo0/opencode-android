package soy.iko.opencode.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import soy.iko.opencode.MainActivity
import soy.iko.opencode.R
import soy.iko.opencode.data.model.Permission
import soy.iko.opencode.data.model.PermissionResponse
import java.security.MessageDigest

/**
 * Builds and posts the app's session notifications:
 *
 *  - [postCompleted]: a "session ready" notification when a background run finishes, with an
 *    inline [RemoteInput] Reply action so a follow-up can be sent without opening the app.
 *  - [postPermission]: a heads-up notification when a paused tool needs approval, with
 *    Allow once / Always / Reject action buttons handled by [NotificationActionReceiver].
 *  - [postError]: a notification when a background run fails.
 *
 * Tapping a notification body opens the session via [MainActivity] with an
 * [MainActivity.EXTRA_SESSION_ID] extra. Notification posting is guarded for Android 13+
 * runtime permission: if the user hasn't granted POST_NOTIFICATIONS, the post is silently
 * skipped rather than throwing.
 */
object SessionNotifications {

    private const val NOTIF_ID_PREFIX = 4000
    private const val TAG = "SessionNotifications"

    // Distinct namespaces so a session can have a completion, a permission, and an error
    // notification outstanding at once without their ids colliding.
    private const val NS_COMPLETED = "done"
    private const val NS_PERMISSION = "perm"
    private const val NS_ERROR = "err"

    private val notifIdRegex = Regex("[^A-Za-z0-9_-]")

    /** Derive a stable, collision-resistant notification id from a namespace + session id.
     *  String.hashCode() can collide for different inputs; a SHA-256 truncated to 31
     *  bits makes accidental collisions astronomically unlikely. The namespace keeps the
     *  three notification kinds for one session in separate id spaces. */
    private fun notifId(namespace: String, sessionId: String): Int {
        val digest = runCatching { MessageDigest.getInstance("SHA-256") }
            .getOrNull()?.digest("$namespace:$sessionId".toByteArray()) ?: return NOTIF_ID_PREFIX
        // Take the first 4 bytes, mask to 31 bits (always positive) to fit an Int id.
        // Kotlin's shl/or/and are equal-precedence infix functions evaluated strictly
        // left-to-right, so the shifts MUST be parenthesized individually — otherwise
        // `a shl 24 or b shl 16` parses as `((a shl 24) or b) shl 16`, shifting the first
        // byte out of the Int entirely and collapsing the id's entropy.
        val hash = (((digest[0].toInt() and 0xFF) shl 24) or
            ((digest[1].toInt() and 0xFF) shl 16) or
            ((digest[2].toInt() and 0xFF) shl 8) or
            (digest[3].toInt() and 0xFF)).and(0x7FFFFFFF)
        // Offset by NOTIF_ID_PREFIX, staying within the positive Int range.
        // Using modulo ensures the prefix is preserved without Int overflow.
        return NOTIF_ID_PREFIX + (hash % (0x7FFFFFFF - NOTIF_ID_PREFIX))
    }

    /** Respect the POST_NOTIFICATIONS runtime permission (Android 13+): if the user denied
     *  it, callers should skip posting instead of throwing. Foreground-service notifications
     *  are exempt, so the running indicator still shows during a run. */
    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Sanitize a session id before embedding it in the deep-link URI so characters like
     *  /, ?, # can't inject path segments or query parameters. */
    private fun safeId(sessionId: String): String = notifIdRegex.replace(sessionId, "")

    /** A PendingIntent that opens [sessionId] in the app when the notification body is tapped.
     *  [profileId] (the originating server) is embedded so a tap after the user has switched
     *  servers routes back to the server that ran the session, not whichever is active —
     *  mirroring the Reply and permission action intents. */
    private fun openSessionIntent(
        context: Context, sessionId: String, requestCode: Int, profileId: String?,
    ): PendingIntent {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("opencode://session/${safeId(sessionId)}")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            profileId?.let { putExtra(NotificationActionReceiver.EXTRA_PROFILE_ID, it) }
        }
        return PendingIntent.getActivity(
            context, requestCode, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // canPost() checks POST_NOTIFICATIONS before every notify(); lint can't follow the
    // check through the helper, so suppress its MissingPermission flag on these posts.
    @SuppressLint("MissingPermission")
    fun postCompleted(context: Context, sessionId: String, title: String, profileId: String?) {
        if (!canPost(context)) return
        val notifId = notifId(NS_COMPLETED, sessionId)

        // Inline reply: a RemoteInput-backed action that broadcasts the typed follow-up to
        // NotificationActionReceiver. The PendingIntent MUST be mutable so the system can
        // fill in the RemoteInput results before delivering it. [profileId] is embedded so the
        // receiver enqueues the reply against the server that ran the session, not whichever
        // server is active when the user taps (mirrors postPermission).
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.notif_reply_hint))
            .build()
        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
            profileId?.let { putExtra(NotificationActionReceiver.EXTRA_PROFILE_ID, it) }
        }
        val replyPending = PendingIntent.getBroadcast(
            context, notifId, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_launcher_foreground,
            context.getString(R.string.notif_action_reply),
            replyPending,
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build()

        val notification = NotificationCompat.Builder(context, NotificationChannels.COMPLETED)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_completed_title))
            // No % escaping here: getString(id, arg) inserts the argument verbatim and
            // never re-scans it for format specifiers, so escaping the title would show
            // literal doubled percent signs (e.g. a "50% done" title as "50%% done").
            .setContentText(context.getString(R.string.notif_completed_text, title))
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(context, sessionId, notifId, profileId))
            .addAction(replyAction)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Group notifications from the same server so multiple completions collapse into
            // a stackable group on Android 7+ instead of filling the shade with individual entries.
            .setGroup(GROUP_COMPLETED)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            // Post a summary notification so the group has a single top-level entry in the shade.
            postCompletedSummary(context)
        }.onFailure { Log.w(TAG, "Failed to post completion notification", it) }
    }

    /** Post (or update) a summary notification for the completed-sessions group so multiple
     *  completions collapse into one stackable entry instead of filling the shade. */
    @SuppressLint("MissingPermission")
    private fun postCompletedSummary(context: Context) {
        val summary = NotificationCompat.Builder(context, NotificationChannels.COMPLETED)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_completed_title))
            .setContentText(context.getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_COMPLETED)
            .setGroupSummary(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(SUMMARY_COMPLETED_ID, summary)
        }.onFailure { Log.w(TAG, "Failed to post completion summary notification", it) }
    }

    /** Post a heads-up notification for a permission request with Allow once / Always /
     *  Reject action buttons. [sessionTitle] labels the notification; [permission]'s title/
     *  pattern/type supplies the detail line. [profileId] is embedded in the action intents
     *  so the receiver routes the response back to the server that posted the request, not
     *  whichever server happens to be active when the user taps. */
    @SuppressLint("MissingPermission")
    fun postPermission(context: Context, permission: Permission, sessionTitle: String, profileId: String?) {
        if (!canPost(context)) return
        val sessionId = permission.sessionID.takeIf { it.isNotBlank() } ?: return
        val notifId = notifId(NS_PERMISSION, sessionId)
        val detail = permission.title?.takeIf { it.isNotBlank() }
            ?: permission.patternText?.takeIf { it.isNotBlank() }
            ?: permission.type?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notif_permission_fallback)

        val builder = NotificationCompat.Builder(context, NotificationChannels.PERMISSION)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_permission_title, sessionTitle))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(context, sessionId, notifId, profileId))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // One action per response. Each PendingIntent needs a distinct request code or
        // FLAG_UPDATE_CURRENT would collapse them into one (the last extras win).
        listOf(
            PermissionResponse.ONCE to R.string.notif_action_allow_once,
            PermissionResponse.ALWAYS to R.string.notif_action_always,
            PermissionResponse.REJECT to R.string.notif_action_reject,
        ).forEach { (response, labelRes) ->
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_PERMISSION
                putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
                putExtra(NotificationActionReceiver.EXTRA_PERMISSION_ID, permission.id)
                putExtra(NotificationActionReceiver.EXTRA_RESPONSE, response.wire)
                profileId?.let { putExtra(NotificationActionReceiver.EXTRA_PROFILE_ID, it) }
            }
            val pending = PendingIntent.getBroadcast(
                context, notifId + response.ordinal + 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_launcher_foreground, context.getString(labelRes), pending)
        }

        runCatching { NotificationManagerCompat.from(context).notify(notifId, builder.build()) }
            .onFailure { Log.w(TAG, "Failed to post permission notification", it) }
    }

    /** Post a notification when a background run fails. */
    @SuppressLint("MissingPermission")
    fun postError(context: Context, sessionId: String, title: String, profileId: String? = null) {
        if (!canPost(context)) return
        val notifId = notifId(NS_ERROR, sessionId)
        val notification = NotificationCompat.Builder(context, NotificationChannels.ERROR)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_error_title))
            .setContentText(context.getString(R.string.notif_error_text, title))
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(context, sessionId, notifId, profileId))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notifId, notification) }
            .onFailure { Log.w(TAG, "Failed to post error notification", it) }
    }

    /** An inline reply couldn't be durably enqueued (no resolvable profile, or the outbox write
     *  threw). Cancel the completion notification so SystemUI's RemoteInput field doesn't stay
     *  stuck in the "sending…" spinner — a stuck field can't be resubmitted — and post an error
     *  notification so the reply isn't silently lost and the user can reopen the session to retry. */
    @SuppressLint("MissingPermission")
    fun postReplyFailed(context: Context, sessionId: String, profileId: String? = null) {
        NotificationManagerCompat.from(context).cancel(notifId(NS_COMPLETED, sessionId))
        if (!canPost(context)) return
        val notifId = notifId(NS_ERROR, sessionId)
        val notification = NotificationCompat.Builder(context, NotificationChannels.ERROR)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_reply_failed_title))
            .setContentText(context.getString(R.string.notif_reply_failed_text))
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(context, sessionId, notifId, profileId))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notifId, notification) }
            .onFailure { Log.w(TAG, "Failed to post reply-failed notification", it) }
    }

    /** FLAG_MUTABLE where required (Android 12+) so RemoteInput results can be injected. */
    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    /** Cancel a session's completion notification (e.g. when the user opens it). */
    fun cancel(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(notifId(NS_COMPLETED, sessionId))
    }

    /** Cancel a session's permission notification (on reply, or when the user opens it). */
    fun cancelPermission(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(notifId(NS_PERMISSION, sessionId))
    }

    /** Cancel a session's error notification (e.g. when the user opens it). Without this a
     *  stale "run failed" notification lingers when the session is opened by any route other
     *  than tapping the notification itself (setAutoCancel only clears it on a direct tap). */
    fun cancelError(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(notifId(NS_ERROR, sessionId))
    }

    private const val GROUP_COMPLETED = "soy.iko.opencode.COMPLETED"
    private const val SUMMARY_COMPLETED_ID = -1
}
