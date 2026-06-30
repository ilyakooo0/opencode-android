package soy.iko.opencode.notification

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
        val notification = buildNotification()
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

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NotificationChannels.STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(getString(R.string.notif_running_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

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

        fun start(context: Context) {
            // startForegroundService can throw ForegroundServiceStartNotAllowedException
            // on Android 12+ if the app is in the background. Wrap it so a backgrounded
            // start (e.g. the user navigates away at the wrong moment) doesn't crash.
            runCatching {
                context.startForegroundService(Intent(context, RunForegroundService::class.java))
            }.onFailure {
                Log.w(TAG, "startForegroundService failed; running without foreground priority", it)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunForegroundService::class.java))
        }
    }
}
