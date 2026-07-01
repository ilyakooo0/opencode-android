package soy.iko.opencode.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import soy.iko.opencode.MainActivity
import soy.iko.opencode.R
import java.security.MessageDigest

/**
 * Builds and posts the "session completed" notification when a background session
 * finishes a run. Tapping it opens the session via [MainActivity] with an
 * [MainActivity.EXTRA_SESSION_ID] extra.
 *
 * Notification posting is guarded for Android 13+ runtime permission: if the user
 * hasn't granted POST_NOTIFICATIONS, the post is silently skipped rather than throwing.
 */
object SessionNotifications {

    private const val NOTIF_ID_PREFIX = 4000
    private const val TAG = "SessionNotifications"

    private val notifIdRegex = Regex("[^A-Za-z0-9_-]")

    /** Derive a stable, collision-resistant notification id from a session id.
     *  String.hashCode() can collide for different inputs; a SHA-256 truncated to 31
     *  bits makes accidental collisions astronomically unlikely. */
    private fun notifIdFor(sessionId: String): Int {
        val digest = runCatching { MessageDigest.getInstance("SHA-256") }
            .getOrNull()?.digest(sessionId.toByteArray()) ?: return NOTIF_ID_PREFIX
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

    fun postCompleted(context: Context, sessionId: String, title: String) {
        // Respect the POST_NOTIFICATIONS runtime permission (Android 13+): if the user
        // denied it, skip the post instead of throwing. Foreground-service notifications
        // are exempt, so the running indicator still shows during a run.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notifId = notifIdFor(sessionId)
        // Sanitize the session id before embedding it in the URI so characters like
        // /, ?, # can't inject path segments or query parameters.
        val safeSessionId = notifIdRegex.replace(sessionId, "")
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("opencode://session/$safeSessionId")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.COMPLETED)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_completed_title))
            // Escape % characters in the title so getString formatting doesn't break
            // when a server-controlled session title contains format-specifier-like text.
            .setContentText(context.getString(R.string.notif_completed_text, title.replace("%", "%%")))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(notifId, notification) }
            .onFailure { Log.w(TAG, "Failed to post completion notification", it) }
    }

    /** Cancel a session's completion notification (e.g. when the user opens it). */
    fun cancel(context: Context, sessionId: String) {
        val notifId = notifIdFor(sessionId)
        NotificationManagerCompat.from(context).cancel(notifId)
    }
}
