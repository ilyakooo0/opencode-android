package soy.iko.opencode.data.repo

import android.content.Context
import android.util.Log
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

/**
 * One staged (not-yet-sent) attachment, in the minimal form needed to re-send and preview
 * it after a process restart. Only the self-contained wire payload is kept — the base64
 * `data:` [url] — because the original content Uri's read permission does NOT survive a
 * process restart, whereas the data URL is self-sufficient (and Coil re-renders it as the
 * thumbnail for images).
 */
@Serializable
data class PersistedAttachment(
    val id: String,
    val name: String,
    val mime: String,
    val url: String,
    val filename: String? = null,
)

/**
 * Persists attachments staged for each session so an interrupted compose survives process
 * death, mirroring how [DraftStore] persists the text draft.
 *
 * Backed by one small JSON file per session under filesDir (not cacheDir, which the OS may
 * evict) rather than SharedPreferences: a base64 payload can be several megabytes and
 * SharedPreferences loads its whole file into memory on first access.
 */
open class AttachmentDraftStore private constructor(
    private val appContext: Context?,
    @Suppress("unused") private val testMode: Boolean,
) {
    constructor(context: Context) : this(context.applicationContext, false)
    protected constructor() : this(null, true)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Session ids are validated tokens upstream, but sanitize anyway so a stray id can't
     *  escape the directory. */
    private val idRegex = Regex("[^A-Za-z0-9_-]")

    private val dir: File? by lazy {
        appContext?.let { File(it.filesDir, "attachment_drafts").apply { mkdirs() } }
    }

    private fun fileFor(sessionId: String): File? =
        dir?.let { File(it, idRegex.replace(sessionId, "_") + ".json") }

    /** Per-session write lock so rapid add/remove/clear mutations (each launched as an
     *  independent coroutine by the ViewModel) serialize their writes to the same file
     *  instead of racing — otherwise an older snapshot could land last and overwrite the
     *  newer one on disk, or two writes could tear the file. */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun lockFor(sessionId: String): Mutex = locks.getOrPut(sessionId) { Mutex() }

    /** Load the staged attachments for [sessionId], or empty if none / unreadable. */
    open suspend fun load(sessionId: String): List<PersistedAttachment> = lockFor(sessionId).withLock {
        withContext(Dispatchers.IO) {
            val file = fileFor(sessionId)?.takeIf { it.exists() } ?: return@withContext emptyList()
            runCatchingCancellable { json.decodeFromString<List<PersistedAttachment>>(file.readText()) }
                .getOrDefault(emptyList())
        }
    }

    /** Persist [attachments] for [sessionId] (an empty list deletes the file). */
    open suspend fun save(sessionId: String, attachments: List<PersistedAttachment>): Unit = lockFor(sessionId).withLock {
        withContext(Dispatchers.IO) {
            val file = fileFor(sessionId) ?: return@withContext
            runCatchingCancellable {
                if (attachments.isEmpty()) file.delete()
                else {
                    // FIX: write atomically (temp file + rename) like RecentSessionsStore so a
                    // process kill mid-write can't leave a truncated file that fails to decode
                    // and silently drops the staged attachments on reload.
                    val encoded = json.encodeToString(attachments)
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    // One try/finally covers BOTH write paths: a throw from the initial
                    // tmp.writeText (disk full, permission) is cleaned up too — otherwise the
                    // inner finally below only ran after the rename failed, orphaning *.tmp
                    // files across failed first writes.
                    try {
                        tmp.writeText(encoded)
                        if (!tmp.renameTo(file)) {
                            file.writeText(encoded)
                        }
                    } finally {
                        tmp.delete()
                    }
                }
            }.onFailure { Log.w("AttachmentDraftStore", "Failed to persist attachments for $sessionId", it) }
            Unit
        }
    }

    /** Remove staged attachments for a session (call on session deletion). */
    open suspend fun remove(sessionId: String) {
        // Keep the per-session Mutex in [locks] rather than evicting it: remove() is not always
        // terminal — a concurrent teardown save() for the same session must serialize against this
        // delete under the *same* Mutex instance. Evicting here would let that save() acquire a
        // fresh Mutex, so the two operations no longer serialize (resurrecting an orphan file, or
        // theoretically tearing a write). The bounded per-session map growth matches the other
        // lock-keyed stores.
        lockFor(sessionId).withLock {
            withContext(Dispatchers.IO) {
                runCatchingCancellable { fileFor(sessionId)?.delete() }
                Unit
            }
        }
    }
}
