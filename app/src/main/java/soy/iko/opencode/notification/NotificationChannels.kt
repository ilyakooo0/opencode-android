package soy.iko.opencode.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import soy.iko.opencode.R

/**
 * The app's notification channels. Created once at startup (idempotent — recreating an
 * existing channel is a no-op). Two channels keep user control granular:
 *
 *  - [STATUS]: low-importance, no sound. Used by the foreground service's persistent
 *    "agent running" indicator.
 *  - [COMPLETED]: default importance. Used when a background session finishes a run.
 *  - [PERMISSION]: high importance (heads-up + sound). Used when a paused tool run needs
 *    the user's approval — the whole point is to reach the user who has walked away, so it
 *    interrupts rather than sitting silently in the shade.
 *  - [ERROR]: default importance. Used when a background run fails.
 */
object NotificationChannels {
    const val STATUS = "run_status"
    const val COMPLETED = "session_completed"
    const val PERMISSION = "permission_request"
    const val ERROR = "session_error"

    fun create(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.createNotificationChannel(
            NotificationChannel(STATUS, context.getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_status_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(COMPLETED, context.getString(R.string.notif_channel_completed), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_completed_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(PERMISSION, context.getString(R.string.notif_channel_permission), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_permission_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(ERROR, context.getString(R.string.notif_channel_error), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_error_desc)
            },
        )
    }
}
