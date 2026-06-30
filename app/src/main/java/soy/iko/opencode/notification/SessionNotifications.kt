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

    /** Derive a stable, collision-resistant notification id from a session id.
     *  String.hashCode() can collide for different inputs; a SHA-256 truncated to 31
     *  bits makes accidental collisions astronomically unlikely. */
    private fun notifIdFor(sessionId: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray())
        // Take the first 4 bytes, mask to 31 bits (always positive) to fit an Int id.
        val hash = ((digest[0].toInt() and 0xFF) shl 24 or
            (digest[1].toInt() and 0xFF) shl 16 or
            (digest[2].toInt() and 0xFF) shl 8 or
            (digest[3].toInt() and 0xFF)).and(0x7FFFFFFF)
        // Offset by NOTIF_ID_PREFIX, wrapping within the positive Int range so the
        // prefix is preserved (not masked away) and ids don't collide with the
        // foreground service's NOTIF_ID = 1.
        return (NOTIF_ID_PREFIX + hash).and(0x7FFFFFFF)
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
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("opencode://session/$sessionId")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.COMPLETED)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_completed_title))
            .setContentText(context.getString(R.string.notif_completed_text, title))
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
