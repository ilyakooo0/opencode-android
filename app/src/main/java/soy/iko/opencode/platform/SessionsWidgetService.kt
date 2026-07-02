package soy.iko.opencode.platform

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import soy.iko.opencode.MainActivity
import soy.iko.opencode.R
import soy.iko.opencode.data.repo.RecentSession
import soy.iko.opencode.data.repo.RecentSessionsStore
import java.security.MessageDigest

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
    // Derive a stable id from the session id via a collision-resistant SHA-256 digest
    // (truncated to 63 bits) rather than String.hashCode(), whose 32-bit output can
    // collide for distinct session ids — with hasStableIds=true the RemoteViews framework
    // would then mis-recycle two sessions' row content. Mirrors SessionNotifications.notifId.
    // Falls back to the position for an out-of-range index.
    override fun getItemId(position: Int): Long {
        val id = items.getOrNull(position)?.id ?: return position.toLong()
        val digest = runCatching { MessageDigest.getInstance("SHA-256").digest(id.toByteArray()) }
            .getOrNull() ?: return position.toLong()
        // Take the first 8 bytes, mask to 63 bits (always positive) to fit a Long id.
        // The shl/or/and infix precedence requires parenthesizing each shift individually
        // (see SessionNotifications.notifId for the rationale).
        return (((digest[0].toLong() and 0xFF) shl 56) or
            ((digest[1].toLong() and 0xFF) shl 48) or
            ((digest[2].toLong() and 0xFF) shl 40) or
            ((digest[3].toLong() and 0xFF) shl 32) or
            ((digest[4].toLong() and 0xFF) shl 24) or
            ((digest[5].toLong() and 0xFF) shl 16) or
            ((digest[6].toLong() and 0xFF) shl 8) or
            (digest[7].toLong() and 0xFF)).and(0x7FFFFFFFFFFFFFFF)
    }
    override fun hasStableIds(): Boolean = true
}
