package soy.iko.opencode.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import soy.iko.opencode.MainActivity
import soy.iko.opencode.R
import soy.iko.opencode.data.repo.RecentSession

/**
 * Maintains the app's dynamic launcher shortcuts (long-press the icon): "New session" and
 * "Resume last". Rebuilt whenever the recent-session list changes so "Resume last" points at
 * the most recent conversation via the existing `opencode://session/{id}` deep link.
 */
object AppShortcuts {

    private const val ID_NEW = "new_session"
    private const val ID_RESUME = "resume_last"
    private val idRegex = Regex("[^A-Za-z0-9_-]")

    fun update(context: Context, lastSession: RecentSession?) {
        val ctx = context.applicationContext
        val shortcuts = mutableListOf<ShortcutInfoCompat>()

        shortcuts += ShortcutInfoCompat.Builder(ctx, ID_NEW)
            .setShortLabel(ctx.getString(R.string.shortcut_new_short))
            .setLongLabel(ctx.getString(R.string.shortcut_new_long))
            .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_launcher_foreground))
            .setIntent(
                Intent(ctx, MainActivity::class.java).apply { action = MainActivity.ACTION_NEW_SESSION },
            )
            .build()

        if (lastSession != null) {
            val safeId = idRegex.replace(lastSession.id, "")
            if (safeId.isNotEmpty()) {
                shortcuts += ShortcutInfoCompat.Builder(ctx, ID_RESUME)
                    .setShortLabel(ctx.getString(R.string.shortcut_resume_short))
                    .setLongLabel(
                        lastSession.title.ifBlank { ctx.getString(R.string.shortcut_resume_long) }.take(40),
                    )
                    .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_launcher_foreground))
                    .setIntent(
                        Intent(ctx, MainActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            data = Uri.parse("opencode://session/$safeId")
                        },
                    )
                    .build()
            }
        }

        runCatching { ShortcutManagerCompat.setDynamicShortcuts(ctx, shortcuts) }
    }
}
