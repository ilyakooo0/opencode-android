package soy.iko.opencode.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import soy.iko.opencode.MainActivity
import soy.iko.opencode.R

/**
 * A foreground service kept alive while an opencode agent is actively running. Holding a
 * foreground priority lets the long-lived SSE `/event` subscription survive backgrounding
 * (Doze/app-standby would otherwise choke the socket and stall streaming mid-run).
 *
 * The service is started when a run begins ([start]) and stopped when it goes idle
 * ([stop]). It shows a low-importance "Agent is working…" notification in [NotificationChannels.STATUS].
 * Started/stopped via Compose from the chat screen based on the `running` state.
 */
class RunForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The session title (when a single run is active) is passed via the intent extra so
        // the notification can identify which session is running, instead of a generic
        // "Agent is working…". Null/blank falls back to the generic title.
        val sessionTitle = intent?.getStringExtra(EXTRA_SESSION_TITLE)?.takeIf { it.isNotBlank() }
        val notification = buildNotification(sessionTitle)
        // startForeground can throw ForegroundServiceStartNotAllowedException on
        // Android 12+ if the app is in the background when the service starts. Wrap
        // it so a backgrounded start (e.g. the user navigates away at the wrong
        // moment) doesn't crash the app — the SSE stream will just continue without
        // foreground priority and may be killed by the system sooner.
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        }.onFailure {
            Log.w(TAG, "startForeground failed; running without foreground priority", it)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(sessionTitle: String?): Notification {
        // Tapping the notification opens the app so the user can see the running session.
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentText = sessionTitle ?: getString(R.string.notif_running_text)
        return NotificationCompat.Builder(this, NotificationChannels.STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            // Show a live elapsed timer so the user can see how long the current run has
            // been going — no per-tick plumbing needed; the system renders the chronometer.
            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    // Android 14+ can time out a foreground service (notably the ~6h/day cumulative cap on
    // dataSync in Android 15) and calls onTimeout expecting a prompt stop; not stopping risks
    // the system force-stopping/ANR-ing the app. Stop cleanly — a still-active run just
    // continues without foreground priority. Both overloads are covered (the 2-arg form is
    // API 35+).
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "Foreground service timed out; stopping")
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type=$fgsType); stopping")
        stopSelf()
    }

    override fun onDestroy() {
        // Safety net: if the process is being killed while a run is in progress,
        // ensure the notification is removed rather than lingering.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "RunForegroundService"
        private const val NOTIF_ID = 1
        const val EXTRA_SESSION_TITLE = "soy.iko.opencode.extra.SESSION_TITLE"

        fun start(context: Context, sessionTitle: String? = null) {
            // startForegroundService can throw ForegroundServiceStartNotAllowedException
            // on Android 12+ if the app is in the background. Wrap it so a backgrounded
            // start (e.g. the user navigates away at the wrong moment) doesn't crash.
            runCatching {
                val intent = Intent(context, RunForegroundService::class.java)
                sessionTitle?.let { intent.putExtra(EXTRA_SESSION_TITLE, it) }
                context.startForegroundService(intent)
            }.onFailure {
                Log.w(TAG, "startForegroundService failed; running without foreground priority", it)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunForegroundService::class.java))
        }
    }
}
