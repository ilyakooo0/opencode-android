package soy.iko.opencode.platform

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import soy.iko.opencode.MainActivity
import soy.iko.opencode.R
import soy.iko.opencode.data.repo.RecentSession
import soy.iko.opencode.data.repo.RecentSessionsStore

/** Serves the list rows for [SessionsWidgetProvider] from [RecentSessionsStore]. */
class SessionsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        SessionsWidgetFactory(applicationContext)
}

private class SessionsWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<RecentSession> = emptyList()

    override fun onCreate() { items = RecentSessionsStore.read(context) }

    // Re-read from disk whenever the app signals the data changed (notifyAppWidgetViewDataChanged).
    override fun onDataSetChanged() { items = RecentSessionsStore.read(context) }

    override fun onDestroy() { items = emptyList() }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_session_item)
        val session = items.getOrNull(position) ?: return row
        row.setTextViewText(R.id.widget_item_title, session.title.ifBlank { context.getString(R.string.session) })
        // Fill-in intent merged into the list's template: carries the session id the app opens.
        row.setOnClickFillInIntent(
            R.id.widget_item_root,
            Intent().putExtra(MainActivity.EXTRA_SESSION_ID, session.id),
        )
        return row
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    // FIX 17: derive a stable id from the session id (not the position) so prepending a new
    // session doesn't mis-recycle RemoteViews and show stale row content. Falls back to the
    // position for an out-of-range index.
    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}
