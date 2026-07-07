package soy.iko.opencode.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
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
    // Fixed notification IDs use NEGATIVE space (mirroring the SUMMARY_*_ID constants below)
    // so they can never collide with the hash-derived per-session IDs returned by notifId(),
    // which are always positive (NOTIF_ID_PREFIX + (hash % (0x7FFFFFFF - NOTIF_ID_PREFIX)),
    // range [4000, 2147483646]). A collision would let a session-specific notification
    // overwrite or be canceled by the fixed one (~1 in 2 billion per session, but a real
    // ID-space flaw). Negative IDs are valid for NotificationManager.cancel/notify.
    private const val UNREAD_BADGE_ID = -10
    private const val CONNECTION_LOST_ID = -11

    // Distinct namespaces so a session can have a completion, a permission, an error, and an
    // outbox-dropped notification outstanding at once without their ids colliding. The outbox
    // drop is semantically distinct from a run error (no run failed — a queued reply couldn't
    // be delivered), so it gets its own namespace rather than overwriting a prior postError.
    private const val NS_COMPLETED = "done"
    private const val NS_PERMISSION = "perm"
    private const val NS_ERROR = "err"
    private const val NS_OUTBOX_DROPPED = "outbox"

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

    /** A PendingIntent that opens [sessionId] in the app when the notification body is tapped.
     *  [profileId] (the originating server) is embedded so a tap after the user has switched
     *  servers routes back to the server that ran the session, not whichever is active —
     *  mirroring the Reply and permission action intents. */
    private fun openSessionIntent(
        context: Context, sessionId: String, requestCode: Int, profileId: String?,
    ): PendingIntent {
        // Sanitize the session id before embedding it in the deep-link URI so characters like
        // /, ?, # can't inject path segments or query parameters.
        val safeId = notifIdRegex.replace(sessionId, "")
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("opencode://session/$safeId")
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
            R.drawable.ic_action_reply,
            context.getString(R.string.notif_action_reply),
            replyPending,
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build()

        // "Mark read" action so the user can triage a completion without opening the session.
        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
            profileId?.let { putExtra(NotificationActionReceiver.EXTRA_PROFILE_ID, it) }
        }
        val markReadPending = PendingIntent.getBroadcast(
            context, notifId + 100, markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_action_check,
            context.getString(R.string.notif_action_mark_read),
            markReadPending,
        ).build()

        val notification = NotificationCompat.Builder(context, NotificationChannels.COMPLETED)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.notif_completed_title))
            // No % escaping here: getString(id, arg) inserts the argument verbatim and
            // never re-scans it for format specifiers, so escaping the title would show
            // literal doubled percent signs (e.g. a "50% done" title as "50%% done").
            .setContentText(context.getString(R.string.notif_completed_text, title))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSessionIntent(context, sessionId, notifId, profileId))
            .addAction(replyAction)
            .addAction(markReadAction)
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
        // The summary needs a content intent so tapping it opens the app (and so
        // setAutoCancel can fire on tap — without a PendingIntent the tap does nothing
        // and the summary can only be dismissed by swiping). A plain open-app intent is
        // right for a group summary: there's no single session to route to.
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = NotificationCompat.Builder(context, NotificationChannels.COMPLETED)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.notif_completed_title))
            .setContentText(context.getString(R.string.app_name))
            .setContentIntent(contentIntent)
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
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.notif_permission_title, sessionTitle))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(context, sessionId, notifId, profileId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_PERMISSION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, NotificationChannels.PERMISSION)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle(context.getString(R.string.notif_permission_public))
                    .build(),
            )

        // One action per response. Each PendingIntent needs a distinct request code or
        // FLAG_UPDATE_CURRENT would collapse them into one (the last extras win).
        listOf(
            Triple(PermissionResponse.ONCE, R.string.notif_action_allow_once, R.drawable.ic_action_check),
            Triple(PermissionResponse.ALWAYS, R.string.notif_action_always, R.drawable.ic_action_check),
            Triple(PermissionResponse.REJECT, R.string.notif_action_reject, R.drawable.ic_action_block),
        ).forEach { (response, labelRes, iconRes) ->
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
            builder.addAction(iconRes, context.getString(labelRes), pending)
        }

        runCatching { NotificationManagerCompat.from(context).notify(notifId, builder.build()) }
            .onFailure { Log.w(TAG, "Failed to post permission notification", it) }
        // Group permission notifications so a multi-session burst collapses into a stackable
        // group instead of filling the shade — mirroring the completed-sessions grouping.
        runCatching { postGroupSummary(context, NotificationChannels.PERMISSION, GROUP_PERMISSION, SUMMARY_PERMISSION_ID) }
            .onFailure { Log.w(TAG, "Failed to post permission summary notification", it) }
    }

    /** Post a notification when a permission prompt auto-rejected on timeout. A user who
     *  walked away from an in-app prompt returns to find the run stopped with no on-screen
     *  record of why; this leaves a persistent trace in the shade explaining the stop and
     *  offering to re-open the session. Uses the permission channel (heads-up) so it's seen. */
    @SuppressLint("MissingPermission")
    fun postPermissionAutoRejected(context: Context, sessionId: String, sessionTitle: String, profileId: String?) {
        if (!canPost(context)) return
        val notifId = notifId(NS_PERMISSION, sessionId) + 1 // distinct from the live prompt
        val detail = context.getString(R.string.notif_permission_auto_rejected_text)
        val builder = NotificationCompat.Builder(context, NotificationChannels.PERMISSION)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.notif_permission_auto_rejected_title, sessionTitle))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSessionIntent(context, sessionId, notifId, profileId))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setGroup(GROUP_PERMISSION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        runCatching { NotificationManagerCompat.from(context).notify(notifId, builder.build()) }
            .onFailure { Log.w(TAG, "Failed to post permission auto-reject notification", it) }
        runCatching { postGroupSummary(context, NotificationChannels.PERMISSION, GROUP_PERMISSION, SUMMARY_PERMISSION_ID) }
            .onFailure { Log.w(TAG, "Failed to post permission summary notification", it) }
    }

    /** Post a notification when a background run fails. */
    @SuppressLint("MissingPermission")
    fun postError(context: Context, sessionId: String, title: String, profileId: String? = null) {
        if (!canPost(context)) return
        val notifId = notifId(NS_ERROR, sessionId)
        val builder = NotificationCompat.Builder(context, NotificationChannels.ERROR)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.notif_error_title))
            .setContentText(context.getString(R.string.notif_error_text, title))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSessionIntent(context, sessionId, notifId, profileId))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Group error notifications so a burst of failures collapses into a stackable group.
            .setGroup(GROUP_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, NotificationChannels.ERROR)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle(context.getString(R.string.notif_error_public))
                    .build(),
            )
        // A "Retry last" action lets the user re-run the failed prompt straight from the shade
        // without opening the app — a failed run is exactly when a one-tap retry is most useful.
        val retryIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_RETRY_LAST
            putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
            profileId?.let { putExtra(NotificationActionReceiver.EXTRA_PROFILE_ID, it) }
        }
        val retryPending = PendingIntent.getBroadcast(
            context, notifId + 10, retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(R.drawable.ic_action_refresh, context.getString(R.string.retry_last), retryPending)
        val notification = builder.build()
        runCatching { NotificationManagerCompat.from(context).notify(notifId, notification) }
            .onFailure { Log.w(TAG, "Failed to post error notification", it) }
        runCatching { postGroupSummary(context, NotificationChannels.ERROR, GROUP_ERROR, SUMMARY_ERROR_ID) }
            .onFailure { Log.w(TAG, "Failed to post error summary notification", it) }
    }

    /** Update the completion notification to show a brief "Sent" confirmation after a
     *  successful inline reply, replacing the completion notification's content. Auto-cancels
     *  on tap (via a [setContentIntent]) so it doesn't linger. */
    @SuppressLint("MissingPermission")
    fun postReplySent(context: Context, sessionId: String, profileId: String? = null) {
        if (!canPost(context)) {
            cancel(context, sessionId)
            return
        }
        val notifId = notifId(NS_COMPLETED, sessionId)
        val notification = NotificationCompat.Builder(context, NotificationChannels.COMPLETED)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.notif_reply_sent))
            // setAutoCancel(true) only fires when the user taps a contentIntent; without one
            // the "Sent" notification lingers indefinitely (the prior behavior). Wire the
            // open-session intent so a tap dismisses the notification and opens the session.
            .setContentIntent(openSessionIntent(context, sessionId, notifId, profileId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GROUP_COMPLETED)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notifId, notification) }
            .onFailure { Log.w(TAG, "Failed to post reply-sent notification", it) }
    }

    /** An inline reply couldn't be durably enqueued (no resolvable profile, or the outbox write
     *  threw). Cancel the completion notification so SystemUI's RemoteInput field doesn't stay
     *  stuck in the "sending…" spinner — a stuck field can't be resubmitted — and post an error
     *  notification so the reply isn't silently lost and the user can reopen the session to retry. */
    @SuppressLint("MissingPermission")
    fun postReplyFailed(context: Context, sessionId: String, profileId: String? = null) {
        NotificationManagerCompat.from(context).cancel(notifId(NS_COMPLETED, sessionId))
        maybeCancelSummary(context, GROUP_COMPLETED, SUMMARY_COMPLETED_ID)
        if (!canPost(context)) return
        val notifId = notifId(NS_ERROR, sessionId)
        val notification = NotificationCompat.Builder(context, NotificationChannels.ERROR)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.notif_reply_failed_title))
            .setContentText(context.getString(R.string.notif_reply_failed_text))
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(context, sessionId, notifId, profileId))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Group with the other error notifications (mirrors postError/postOutboxDropped)
            // so this notification joins the GROUP_ERROR stack and the group summary stays
            // consistent. Without .setGroup, reusing the NS_ERROR id space would overwrite a
            // prior postError for this session without the group association, leaving an
            // orphaned SUMMARY_ERROR_ID group summary with no children lingering in the shade.
            .setGroup(GROUP_ERROR)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notifId, notification) }
            .onFailure { Log.w(TAG, "Failed to post reply-failed notification", it) }
        runCatching { postGroupSummary(context, NotificationChannels.ERROR, GROUP_ERROR, SUMMARY_ERROR_ID) }
            .onFailure { Log.w(TAG, "Failed to post error summary notification", it) }
    }

    /** A queued outbox message was permanently undeliverable (e.g. the target session was
     *  deleted server-side → 404). Distinct from [postError] (which is titled "Run failed"):
     *  no run failed — a queued reply couldn't be delivered. Uses a distinct title/body so the
     *  user understands the failure mode and recovery action (open the session and re-send).
     *  Uses a distinct notification namespace ([NS_OUTBOX_DROPPED]) so it doesn't overwrite a
     *  prior [postError] for the same session — the two failure modes are independent and the
     *  user may need to see both. */
    @SuppressLint("MissingPermission")
    fun postOutboxDropped(context: Context, sessionId: String, sessionTitle: String, profileId: String? = null) {
        if (!canPost(context)) return
        val notifId = notifId(NS_OUTBOX_DROPPED, sessionId)
        val notification = NotificationCompat.Builder(context, NotificationChannels.ERROR)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.message_not_delivered))
            .setContentText(context.getString(R.string.message_not_delivered_body, sessionTitle))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSessionIntent(context, sessionId, notifId, profileId))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setGroup(GROUP_ERROR)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notifId, notification) }
            .onFailure { Log.w(TAG, "Failed to post outbox-dropped notification", it) }
        runCatching { postGroupSummary(context, NotificationChannels.ERROR, GROUP_ERROR, SUMMARY_ERROR_ID) }
            .onFailure { Log.w(TAG, "Failed to post error summary notification", it) }
    }

    /** The SSE stream hit a non-retryable failure (Failed/AuthFailed) while the app was
     *  backgrounded during an active run, so the in-app banner isn't visible and the run is
     *  effectively stranded. Posts a low-priority notification so a backgrounded user is
     *  signaled that their run is stalled and can tap to reconnect. Distinct from [postError]
     *  (a run error) — this is a connection loss, not a run failure. */
    @SuppressLint("MissingPermission")
    fun postConnectionLost(context: Context, profileLabel: String, profileId: String?) {
        if (!canPost(context)) return
        val notifId = CONNECTION_LOST_ID
        // Tap reopens the app at the server list so the user can reconnect / fix credentials.
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            profileId?.let { putExtra(NotificationActionReceiver.EXTRA_PROFILE_ID, it) }
        }
        val openPending = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.ERROR)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.connection_lost, profileLabel))
            .setContentText(context.getString(R.string.connection_lost_tap))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notifId, notification) }
            .onFailure { Log.w(TAG, "Failed to post connection-lost notification", it) }
    }

    /** Cancel a previously-posted [postConnectionLost] notification (e.g. on reconnect). */
    fun cancelConnectionLost(context: Context) {
        NotificationManagerCompat.from(context).cancel(CONNECTION_LOST_ID)
    }

    /** FLAG_MUTABLE where required (Android 12+) so RemoteInput results can be injected. */
    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    /** Cancel a session's completion notification (e.g. when the user opens it). */
    fun cancel(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(notifId(NS_COMPLETED, sessionId))
        maybeCancelSummary(context, GROUP_COMPLETED, SUMMARY_COMPLETED_ID)
    }

    /**
     * Reflect the total number of unread sessions as a launcher-icon badge. Android only badges
     * the launcher icon via a notification on a badge-enabled channel, so this posts a silent,
     * min-importance notification with [.setNumber] on the [NotificationChannels.UNREAD] channel
     * when [totalUnread] > 0, and cancels it when it drops to 0. Skipped entirely without
     * POST_NOTIFICATIONS permission (Android 13+) — the badge just won't show, no error.
     */
    @SuppressLint("MissingPermission")
    fun updateUnreadBadge(context: Context, totalUnread: Int) {
        val nm = NotificationManagerCompat.from(context)
        if (totalUnread <= 0) {
            nm.cancel(UNREAD_BADGE_ID)
            return
        }
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, NotificationChannels.UNREAD)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.resources.getQuantityString(R.plurals.unread_sessions, totalUnread, totalUnread))
            .setNumber(totalUnread.coerceAtMost(999))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        runCatching { nm.notify(UNREAD_BADGE_ID, notification) }
            .onFailure { Log.w(TAG, "Failed to post unread badge notification", it) }
    }

    /** Cancel a session's permission notification (on reply, or when the user opens it). Also
     *  cancels the auto-rejected-permission notification (posted under notifId + 1) so opening
     *  the session by any route other than tapping that notification clears it too — without
     *  this a stale "auto-rejected" heads-up lingers in the shade (setAutoCancel only dismisses
     *  on a direct tap). */
    fun cancelPermission(context: Context, sessionId: String) {
        val nm = NotificationManagerCompat.from(context)
        val baseId = notifId(NS_PERMISSION, sessionId)
        nm.cancel(baseId)
        nm.cancel(baseId + 1) // postPermissionAutoRejected uses baseId + 1
        maybeCancelSummary(context, GROUP_PERMISSION, SUMMARY_PERMISSION_ID)
    }

    /** Cancel a session's error notification (e.g. when the user opens it). Without this a
     *  stale "run failed" notification lingers when the session is opened by any route other
     *  than tapping the notification itself (setAutoCancel only clears it on a direct tap).
     *  Also cancels an outbox-dropped notification ([postOutboxDropped]) since it shares the
     *  error channel/group and should likewise clear on open. */
    fun cancelError(context: Context, sessionId: String) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(notifId(NS_ERROR, sessionId))
        nm.cancel(notifId(NS_OUTBOX_DROPPED, sessionId))
        maybeCancelSummary(context, GROUP_ERROR, SUMMARY_ERROR_ID)
    }

    /** Build the lock-screen (public) version of a notification: only a generic title, no
     *  detail line, so sensitive file paths / commands in the real content aren't revealed
     *  while the device is locked. */
    /** When the last child of a group is dismissed, cancel its lingering summary so it
     *  doesn't sit empty in the shade. NotificationManagerCompat lacks getActiveNotifications,
     *  so reach for the platform NotificationManager. The children/summary are posted without
     *  a tag, so matching by [android.app.Notification.getGroup] (plus excluding the summary
     *  id) is the reliable way to tell if any child remains. */
    private fun maybeCancelSummary(context: Context, group: String, summaryId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val active = try {
            nm.activeNotifications
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query active notifications for summary cleanup", e)
            return
        }
        val hasChild = active.any { it.id != summaryId && it.notification?.group == group }
        if (!hasChild) {
            runCatching { NotificationManagerCompat.from(context).cancel(summaryId) }
                .onFailure { Log.w(TAG, "Failed to cancel empty group summary", it) }
        }
    }

    private const val GROUP_COMPLETED = "soy.iko.opencode.COMPLETED"
    private const val GROUP_PERMISSION = "soy.iko.opencode.PERMISSION"
    private const val GROUP_ERROR = "soy.iko.opencode.ERROR"
    private const val SUMMARY_COMPLETED_ID = -1
    private const val SUMMARY_PERMISSION_ID = -2
    private const val SUMMARY_ERROR_ID = -3

    /** Post a summary notification for a group so multiple entries collapse into one stackable
     *  entry in the shade instead of filling it. */
    @SuppressLint("MissingPermission")
    private fun postGroupSummary(context: Context, channelId: String, group: String, summaryId: Int) {
        if (!canPost(context)) return
        val summary = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.app_name))
            .setAutoCancel(true)
            .setGroup(group)
            .setGroupSummary(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(summaryId, summary)
        }.onFailure { Log.w(TAG, "Failed to post group summary notification", it) }
    }
}
