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
import java.io.File

/** A minimal session summary persisted for the home-screen widget and launcher shortcuts. */
@Serializable
data class RecentSession(val id: String, val title: String)

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
            runCatching {
                val target = file(context)
                val encoded = json.encodeToString(sessions.take(MAX))
                val tmp = File(target.parentFile, "$FILE.tmp")
                tmp.writeText(encoded)
                // Atomic replace on POSIX filesystems (app's internal storage); the reader
                // never observes a torn file. Fall back to a direct write if rename fails.
                if (!tmp.renameTo(target)) {
                    target.writeText(encoded)
                    tmp.delete()
                }
            }
            Unit
        }
    }
}
