package soy.iko.opencode.data.repo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import soy.iko.opencode.util.runCatchingCancellable
import java.io.File

/** A minimal session summary persisted for the home-screen widget and launcher shortcuts.
 *  Carries the originating [profileId] so a widget tap routes back to the server that ran
 *  the session even after the user has switched to a different connection — without it,
 *  the session would open on whichever server is currently active and 404 or show empty. */
@Serializable
data class RecentSession(
    val id: String,
    val title: String,
    // Defaulted so a pre-profileId persisted file (from an older app version) still decodes:
    // the field is absent → default "" → the widget falls back to the active connection.
    val profileId: String = "",
)

/**
 * Persists a short list of recent sessions to a small JSON file under filesDir so app-external
 * surfaces (the home-screen widget's [android.widget.RemoteViewsService] and the "Resume last"
 * launcher shortcut) can read them without a live connection or in-memory state. Both the app
 * and the widget's RemoteViews factory run in the same process, so a plain file is sufficient.
 */
object RecentSessionsStore {

    private const val FILE = "recent_sessions.json"
    const val MAX = 8

    private val json = Json { ignoreUnknownKeys = true }

    /** Serializes concurrent [write]s. The synchronous [read] (called from the widget's
     *  RemoteViewsService) can't take a suspend lock, so [write] instead swaps the file in
     *  atomically via a temp-file rename — the reader always sees either the old or the new
     *  complete file, never a half-written one. */
    private val writeMutex = Mutex()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    /** Synchronous read — safe to call from the widget factory (bounded, tiny file). */
    fun read(context: Context): List<RecentSession> {
        val f = file(context).takeIf { it.exists() } ?: return emptyList()
        return runCatching { json.decodeFromString<List<RecentSession>>(f.readText()) }
            .getOrDefault(emptyList())
    }

    /** Persist [sessions] (capped to [MAX]); a no-op empty list writes an empty file. */
    suspend fun write(context: Context, sessions: List<RecentSession>) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            // runCatchingCancellable (not plain runCatching): this runs inside a coroutine, and
            // plain runCatching swallows CancellationException — if the caller is cancelled
            // mid-write, cancellation wouldn't propagate and the write could complete (or
            // partially complete) on a dying scope. Matches the AGENTS.md convention.
            runCatchingCancellable {
                val target = file(context)
                val encoded = json.encodeToString(sessions.take(MAX))
                val tmp = File(target.parentFile, "$FILE.tmp")
                tmp.writeText(encoded)
                // Atomic replace on POSIX filesystems (app's internal storage); the reader
                // never observes a torn file. renameTo can fail when the target exists on
                // some OEM filesystems — delete the target first so the rename is a pure
                // create. If that still fails, copy to a SECOND temp file and atomically
                // rename THAT to target — writing tmp.copyTo(target) directly would expose a
                // half-written target to a concurrent reader (read takes no mutex), breaking
                // the documented invariant. The second-rename leaves target either old or new,
                // never torn.
                if (!tmp.renameTo(target)) {
                    target.delete()
                    if (!tmp.renameTo(target)) {
                        val tmp2 = File(target.parentFile, "$FILE.tmp2")
                        tmp2.writeText(encoded)
                        if (!tmp2.renameTo(target)) {
                            // Truly last resort: some OEM filesystems reject even a create-rename.
                            // Only here do we accept a non-atomic overwrite, with a final cleanup.
                            target.delete()
                            tmp2.renameTo(target)
                        }
                        tmp.delete()
                    }
                }
            }
            Unit
        }
    }
}
