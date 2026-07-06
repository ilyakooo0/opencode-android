package soy.iko.opencode.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.annotation.SuppressLint
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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

    // Wall-clock time the current run started, captured on the first onStartCommand of
    // the run and reused across progress-driven notification rebuilds so the chronometer's
    // base doesn't jump back to 0:00 every time the agent advances a step. Reset to 0 in
    // onDestroy once the run ends (stop() → onDestroy) so the next run starts fresh.
    //
    // Instance field (not companion/static): a static field would survive a stopped-then-
    // recreated service instance and leave the chronometer pinned to a previous run's start
    // if onDestroy hadn't run yet when the next run's first intent arrived. As an instance
    // field it's reset by construction on every new Service instance.
    private var runStartMillis: Long = 0L
    // Whether *this* service instance has successfully called startForeground. Tracked so
    // progress-driven re-starts (which re-enter onStartCommand) don't re-call startForeground
    // and re-bind the 5s obligation each time — that would let a backgrounded re-start throw
    // ForegroundServiceStartNotAllowedException even though the service is already in the
    // foreground. After the first successful startForeground, progress updates refresh the
    // notification via NotificationManager.notify directly (see [updateProgress]), which
    // never throws the background-start exception.
    private var isInForeground: Boolean = false
    // The most recent session title/id/profile/progress handed to onStartCommand, retained so
    // [updateProgress] can rebuild the notification with a new progress string without a fresh
    // startForegroundService intent (which can throw when backgrounded).
    private var lastTitle: String? = null
    private var lastSessionId: String? = null
    private var lastProfileId: String? = null
    private var lastProgress: String? = null

    @android.annotation.SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        // The session title (when a single run is active) is passed via the intent extra so
        // the notification can identify which session is running, instead of a generic
        // "Agent is working…". Null/blank falls back to the generic title.
        val sessionTitle = intent?.getStringExtra(EXTRA_SESSION_TITLE)?.takeIf { it.isNotBlank() }
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }
        val progress = intent?.getStringExtra(EXTRA_PROGRESS)?.takeIf { it.isNotBlank() }
        lastTitle = sessionTitle
        lastSessionId = sessionId
        lastProfileId = profileId
        lastProgress = progress
        // Capture the wall-clock start of the *first* intent of this run and reuse it across
        // rebuilds so the chronometer's base doesn't jump back to 0:00 every time the agent
        // advances a step. As an instance field it's 0 on a fresh service, so a recreated
        // service (after onDestroy reset) starts the clock fresh too.
        if (runStartMillis == 0L) runStartMillis = System.currentTimeMillis()
        val notification = buildNotification(sessionTitle, sessionId, profileId, progress)
        // Only call startForeground once per service lifetime — re-calling it on every
        // progress-driven re-start re-binds the "must call startForeground within 5s"
        // obligation each time, and if the app is backgrounded between two progress
        // updates the new call can throw ForegroundServiceStartNotAllowedException even
        // though the service is already in the foreground. That would drop foreground
        // priority mid-run (the catch below stopSelf()s, killing the FGS while
        // anyRunActive is still true). After the first successful startForeground,
        // progress updates refresh the notification via NotificationManager.notify
        // directly, which never throws the background-start exception.
        if (!isInForeground) {
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
            }.also { isInForeground = it.isSuccess }
        } else {
            // Already in the foreground: refresh the notification in place. NotificationManager
            // updates an ongoing notification without re-asserting foreground priority.
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
            }.onFailure { Log.w(TAG, "Failed to update foreground notification", it) }
        }
        return START_NOT_STICKY
    }

    /** Rebuild the notification with an updated progress string and re-notify in place, *without*
     *  re-entering [onStartCommand] (and therefore without calling [Context.startForegroundService],
     *  which can throw [android.app.ForegroundServiceStartNotAllowedException] on Android 12+ when
     *  the app is backgrounded — even if the service is already in the foreground). Called directly
     *  by the companion [updateProgress] when a progress refresh is needed while the service is
     *  already running; falls back to [start] (which goes through startForegroundService) when the
     *  service isn't alive yet, so the first progress update still lands. */
    @SuppressLint("MissingPermission")
    private fun updateProgress(progress: String?) {
        // Guard against the companion start() → instance race: start() reads @Volatile instance
        // on a background dispatcher and calls updateProgress, but onDestroy (on the main thread)
        // can null instance and call stopForeground between the read and this notify(). isInForeground
        // is cleared in onDestroy, so checking it here covers the stopForeground-already-called case
        // and avoids touching NotificationManager during teardown.
        if (!isInForeground) return
        lastProgress = progress
        val notification = buildNotification(lastTitle, lastSessionId, lastProfileId, progress)
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
        }.onFailure { Log.w(TAG, "Failed to update foreground notification progress", it) }
    }

    private fun mainContentIntent(): PendingIntent {
        // Tapping the notification opens the app so the user can see the running session.
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(
        sessionTitle: String?,
        sessionId: String?,
        profileId: String? = null,
        progress: String? = null,
    ): Notification {
        val pendingIntent = mainContentIntent()
        val contentText = progress ?: sessionTitle ?: getString(R.string.notif_running_text)
        val builder = NotificationCompat.Builder(this, NotificationChannels.STATUS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(brandColor(this))
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            // Show a live elapsed timer so the user can see how long the current run has
            // been going — no per-tick plumbing needed; the system renders the chronometer.
            // runStartMillis is captured once at the start of the run (see onStartCommand)
            // and reused across progress-driven rebuilds so the chronometer base is stable.
            .setWhen(runStartMillis)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // An indeterminate progress bar so the notification reads as actively "in progress"
            // at a glance (alongside the chronometer), matching the conventional run affordance.
            .setProgress(0, 0, true)
        // Add a Stop action so the user can cancel the run without opening the app — the
        // core of the "kick it off and walk away" flow. Only when a specific session is
        // known (multiple concurrent runs can't be targeted individually). The profile id
        // is embedded so [AppContainer.abortRunFromNotification]'s routing guard sends the
        // abort to the originating server even if the user has switched profiles between
        // starting the run and tapping Stop (mirroring the other notification actions).
        if (sessionId != null) {
            val stopIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_STOP_RUN
                putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
                profileId?.let { putExtra(NotificationActionReceiver.EXTRA_PROFILE_ID, it) }
            }
            val stopPending = PendingIntent.getBroadcast(
                this, sessionId.hashCode(), stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                R.drawable.ic_action_stop,
                getString(R.string.notif_action_stop),
                stopPending,
            )
        }
        return builder.build()
    }

    // Android 14+ can time out a foreground service (notably the ~6h/day cumulative cap on
    // dataSync in Android 15) and calls onTimeout expecting a prompt stop; not stopping risks
    // the system force-stopping/ANR-ing the app. Stop cleanly — a still-active run just
    // continues without foreground priority. Post a low-priority notification so the user
    // knows their long-running task may be paused in the background, rather than silently
    // losing foreground priority. Both overloads are covered (the 2-arg form is API 35+).
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "Foreground service timed out; stopping")
        postTimeoutNotification()
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type=$fgsType); stopping")
        postTimeoutNotification()
        stopSelf()
    }

    /** Post a low-priority notification informing the user that the foreground service was
     *  timed out by the system — the run continues but may be killed by Doze without warning. */
    @SuppressLint("MissingPermission")
    private fun postTimeoutNotification() {
        runCatching {
            val notification = NotificationCompat.Builder(this, NotificationChannels.STATUS)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(brandColor(this))
                .setContentTitle(getString(R.string.notif_running_title))
                .setContentText(getString(R.string.notif_fg_timeout_text))
                .setContentIntent(mainContentIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            NotificationManagerCompat.from(this).notify(NOTIF_TIMEOUT_ID, notification)
        }
    }

    override fun onDestroy() {
        // Safety net: if the process is being killed while a run is in progress,
        // ensure the notification is removed rather than lingering.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Clear the run-start / foreground / instance state so the next run's chronometer
        // begins fresh and startForeground is re-asserted on the first intent of the next
        // run. These are instance fields, so a recreated service resets them by construction
        // — but clearing here also covers the same-instance restart case.
        runStartMillis = 0L
        isInForeground = false
        lastTitle = null
        lastSessionId = null
        lastProfileId = null
        lastProgress = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Reset the brand-color cache when the configuration (notably uiMode/night mode) changes
    // so the notification icon tint follows the app theme instead of staying pinned to whatever
    // the first build resolved. The service isn't recreated on a config change (it's a service,
    // not an Activity), so without this the cached color is stale until onDestroy.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        brandColorCache = 0
    }

    companion object {
        private const val TAG = "RunForegroundService"
        private const val NOTIF_ID = 1
        private const val NOTIF_TIMEOUT_ID = 2
        // Brand accent for the foreground notifications' small-icon tint circle. Resolved
        // from resources (R.color.notif_brand) so it follows the app theme (light/dark)
        // instead of a hardcoded constant. Lazily computed on first use from the app context
        // and reset in onConfigurationChanged so a theme toggle mid-run doesn't pin the tint
        // to the old theme for the rest of the run.
        private var brandColorCache: Int = 0
        private fun brandColor(context: Context): Int {
            if (brandColorCache == 0) {
                brandColorCache = androidx.core.content.ContextCompat.getColor(context, R.color.notif_brand)
            }
            return brandColorCache
        }
        // Live reference to the running service instance, published in onStartCommand and
        // cleared in onDestroy. @Volatile so [updateProgress]'s read from the app-scope
        // coroutine sees the most recent publication. Null when the service isn't running.
        @Volatile private var instance: RunForegroundService? = null
        const val EXTRA_SESSION_TITLE = "soy.iko.opencode.extra.SESSION_TITLE"
        const val EXTRA_SESSION_ID = "soy.iko.opencode.extra.SESSION_ID"
        const val EXTRA_PROFILE_ID = "soy.iko.opencode.extra.PROFILE_ID"
        const val EXTRA_PROGRESS = "soy.iko.opencode.extra.PROGRESS"

        /** Start the service (or refresh an already-running one). [profileId] is embedded in the
         *  Stop action's PendingIntent so an abort from the notification reaches the originating
         *  server even after a profile switch. */
        fun start(
            context: Context,
            sessionTitle: String? = null,
            sessionId: String? = null,
            profileId: String? = null,
            progress: String? = null,
        ) {
            // If the service is already in the foreground, refresh the notification in place
            // via NotificationManager.notify (through the live instance) instead of going
            // through startForegroundService. On Android 12+, startForegroundService can
            // throw ForegroundServiceStartNotAllowedException when the app is backgrounded
            // — *even if the service is already running in the foreground* — and the
            // runCatching below would swallow it, leaving the progress text un-updated
            // precisely when the app is backgrounded (the FGS's whole purpose). The in-place
            // notify never throws, so progress refreshes always land while the service runs.
            val live = instance
            if (live != null) {
                live.updateProgress(progress)
                return
            }
            // startForegroundService can throw ForegroundServiceStartNotAllowedException
            // on Android 12+ if the app is in the background. Wrap it so a backgrounded
            // start (e.g. the user navigates away at the wrong moment) doesn't crash.
            runCatching {
                val intent = Intent(context, RunForegroundService::class.java)
                sessionTitle?.let { intent.putExtra(EXTRA_SESSION_TITLE, it) }
                sessionId?.let { intent.putExtra(EXTRA_SESSION_ID, it) }
                profileId?.let { intent.putExtra(EXTRA_PROFILE_ID, it) }
                progress?.let { intent.putExtra(EXTRA_PROGRESS, it) }
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
