package soy.iko.opencode.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import soy.iko.opencode.MainActivity
import soy.iko.opencode.R

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
        val notifId = (NOTIF_ID_PREFIX + sessionId.hashCode()).and(0x7FFFFFFF)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, sessionId.hashCode(), openIntent,
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
    }

    /** Cancel a session's completion notification (e.g. when the user opens it). */
    fun cancel(context: Context, sessionId: String) {
        val notifId = (NOTIF_ID_PREFIX + sessionId.hashCode()).and(0x7FFFFFFF)
        NotificationManagerCompat.from(context).cancel(notifId)
    }
}
