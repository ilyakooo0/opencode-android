package soy.iko.opencode.platform

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import soy.iko.opencode.MainActivity
import soy.iko.opencode.R

/**
 * Home-screen widget listing recent opencode sessions (fed by
 * [soy.iko.opencode.data.repo.RecentSessionsStore]) with a "New session" action. Each row deep-links
 * into its session; the header opens the app. The list is backed by [SessionsWidgetService].
 */
class SessionsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) render(context, appWidgetManager, id)
    }

    companion object {
        /** Re-render all widgets and tell them to reload their list data (call after the
         *  recent-session list changes). No-op when no widget is placed. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, SessionsWidgetProvider::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            for (id in ids) render(context, manager, id)
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_sessions)

            // Header: title opens the app; the "+" starts a new session.
            views.setOnClickPendingIntent(
                R.id.widget_title,
                activityPending(context, requestCode = 2, Intent(context, MainActivity::class.java)),
            )
            views.setOnClickPendingIntent(
                R.id.widget_new,
                activityPending(
                    context, requestCode = 1,
                    Intent(context, MainActivity::class.java).apply { action = MainActivity.ACTION_NEW_SESSION },
                ),
            )

            // Bind the list to the RemoteViewsService. A unique data Uri per widget id keeps
            // the framework from reusing one adapter across widgets.
            val serviceIntent = Intent(context, SessionsWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            // Template intent filled in per row (fill-in carries EXTRA_SESSION_ID). Must be
            // mutable on Android 12+ so the row's fill-in can be merged.
            val templateIntent = Intent(context, MainActivity::class.java).apply { action = Intent.ACTION_VIEW }
            val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val templatePending = PendingIntent.getActivity(
                context, 3, templateIntent, PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
            )
            views.setPendingIntentTemplate(R.id.widget_list, templatePending)

            manager.updateAppWidget(widgetId, views)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)
        }

        private fun activityPending(context: Context, requestCode: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
