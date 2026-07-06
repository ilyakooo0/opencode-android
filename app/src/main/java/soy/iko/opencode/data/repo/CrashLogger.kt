package soy.iko.opencode.data.repo

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import soy.iko.opencode.util.runCatchingCancellable

/**
 * A self-contained crash reporter with no network or third-party backend. An uncaught
 * exception is written to app-private storage ([Context.filesDir]/crashes) as a plain
 * text report (timestamp, device/app metadata, and the full stack trace), then the
 * previous handler is re-invoked so the process still dies normally.
 *
 * Reports are surfaced in the in-app Diagnostics screen, where they can be viewed,
 * shared, or deleted. This closes the "no idea what's crashing in the field" gap
 * without pulling in a hosted analytics SDK.
 */
class CrashLogger private constructor(private val appContext: Context) {

    data class CrashReport(
        val fileName: String,
        val timestamp: Long,
        val preview: String,
    )

    private val crashDir = File(appContext.filesDir, "crashes").apply { mkdirs() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val crashPrefs = appContext.getSharedPreferences("crash_logger", android.content.Context.MODE_PRIVATE)

    private val _reports = MutableStateFlow<List<CrashReport>>(emptyList())
    val reports: StateFlow<List<CrashReport>> = _reports.asStateFlow()

    @Volatile private var installed = false

    fun install() {
        // Guard against double-install: a second call would capture this handler as `previous`
        // and chain it to itself, double-writing crash reports and double-invoking the original
        // handler on every crash. The singleton get() mitigates this in production, but install()
        // itself must be idempotent (tests, a misconfigured init) so the chain can't form.
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(thread, throwable) }
                .onFailure { Log.e("CrashLogger", "Failed to write crash report", it) }
            previous?.uncaughtException(thread, throwable)
        }
        // Load crash reports off the main thread so startup isn't blocked by file I/O
        // (listFiles + reading the first line of each report). The StateFlow updates
        // whenever the scan completes, so the Diagnostics screen reflects the result.
        scope.launch { runCatchingCancellable { refresh() } }
    }

    /** Monotonic guard so a slower earlier scan can't overwrite a newer one's result.
     *  refresh() runs on the multi-threaded IO dispatcher from several call sites
     *  (install/deleteReport/scheduleDelete/clearAll), so two scans can overlap. */
    private val refreshGeneration = java.util.concurrent.atomic.AtomicLong(0)

    fun refresh() {
        val generation = refreshGeneration.incrementAndGet()
        // Trim over-capacity reports here (off the crashing thread, on the IO dispatcher) so
        // the UncaughtExceptionHandler path does only the single writeText it must to land the
        // report before the OS kills the process. Trimming on refresh keeps the cap enforced
        // across normal app launches (install calls refresh, and every delete/clear calls it).
        trimOldReports()
        val scanned = crashDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                CrashReport(
                    fileName = f.name,
                    timestamp = f.lastModified(),
                    preview = runCatching { f.useLines { it.firstOrNull() ?: "" } }.getOrDefault(""),
                )
            }
            .orEmpty()
        // Publish only if no newer refresh has started meanwhile, so a stale scan (e.g. one
        // that started before a delete) can't momentarily re-show a just-removed report.
        if (generation == refreshGeneration.get()) _reports.value = scanned
    }

    fun readReport(fileName: String): String? {
        val file = File(crashDir, fileName).canonicalFile
        if (!file.path.startsWith(crashDir.canonicalPath + File.separator) || !file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun deleteReport(fileName: String) {
        scope.launch {
            val file = File(crashDir, fileName).canonicalFile
            if (file.path.startsWith(crashDir.canonicalPath + File.separator)) {
                file.delete()
            }
            refresh()
        }
    }

    /** Whether any crash report is newer than the last-acknowledged timestamp — i.e. a
     *  crash happened in a previous run that the user hasn't been prompted about yet.
     *  Called on cold start to surface a "crashed last time" dialog. */
    fun hasUnacknowledgedCrash(): Boolean {
        val acknowledgedAt = crashPrefs.getLong(KEY_ACKNOWLEDGED_AT, 0L)
        return crashDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.any { it.lastModified() > acknowledgedAt } == true
    }

    /** Mark all current crash reports as acknowledged so the cold-start prompt doesn't
     *  re-fire on the next launch. */
    fun acknowledgeCrashes() {
        crashPrefs.edit().putLong(KEY_ACKNOWLEDGED_AT, System.currentTimeMillis()).apply()
    }

    /** Deferred deletions keyed by file name, so an Undo can cancel one before it fires. */
    private val pendingDeletes = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * Schedule [fileName] for deletion after [delayMs], cancellable via
     * [cancelScheduledDelete]. Runs on the logger's own long-lived scope — not the
     * Diagnostics screen's composition scope — so navigating away during the undo window
     * still commits the delete instead of silently dropping it. A repeated schedule for
     * the same name replaces the prior timer.
     */
    fun scheduleDelete(fileName: String, delayMs: Long) {
        val job = scope.launch {
            delay(delayMs)
            // Atomically claim the delete by removing our own map entry BEFORE deleting.
            // The remove is the synchronization point with cancelScheduledDelete: whichever
            // removes the entry first wins. If an undo raced the timer and removed it first,
            // this returns false and we skip the delete — so the file survives, matching the
            // `true` that cancel returned to the UI. Deleting first (then removing) would let
            // a cancel land in the gap and falsely report "undo succeeded" for a gone file.
            // remove(name, thisJob) also no-ops for a stale job after a reschedule.
            val claimed = coroutineContext[Job]?.let { pendingDeletes.remove(fileName, it) } == true
            if (!claimed) return@launch
            val file = File(crashDir, fileName).canonicalFile
            if (file.path.startsWith(crashDir.canonicalPath + File.separator)) {
                file.delete()
            }
            refresh()
        }
        pendingDeletes.put(fileName, job)?.cancel()
    }

    /** Cancel a pending deferred delete (the Undo action). Returns true if it was still
     *  pending (undo succeeded), false if the delete had already fired. */
    fun cancelScheduledDelete(fileName: String): Boolean {
        val job = pendingDeletes.remove(fileName) ?: return false
        job.cancel()
        return true
    }

    fun clearAll() {
        scope.launch {
            crashDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.forEach { it.delete() }
            refresh()
        }
    }

    /** Deferred whole-directory clear, cancellable via [cancelScheduledClearAll] so the bulk
     *  action gets the same undo window as a single-report delete. Runs on the logger's own
     *  scope so it commits even if the user leaves the Diagnostics screen. */
    // AtomicReference (not a bare @Volatile var): the timer-fired claim must be a CAS,
    // not an unconditional null, else a reschedule that installed a newer job loses its
    // entry to the old coroutine's null and the clear runs immediately instead of after
    // the new delay (and cancelScheduledClearAll then falsely reports "already fired").
    // Mirrors scheduleDelete's ConcurrentHashMap.remove(name, job) claim.
    private val pendingClearAll = java.util.concurrent.atomic.AtomicReference<Job?>(null)

    fun scheduleClearAll(delayMs: Long) {
        val job = scope.launch {
            delay(delayMs)
            // Atomically claim the clear by CAS-ing our own entry to null, mirroring
            // scheduleDelete's claim pattern: a reschedule (or cancel) that nulls first
            // makes this a no-op. Without the CAS, an unconditional null would wipe a
            // newer job installed by a reschedule.
            val mine = coroutineContext[Job]
            if (mine == null || !pendingClearAll.compareAndSet(mine, null)) return@launch
            crashDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.forEach { it.delete() }
            refresh()
        }
        pendingClearAll.getAndSet(job)?.cancel()
    }

    /** Cancel a pending clear-all (the Undo action). Returns true if it was still pending. */
    fun cancelScheduledClearAll(): Boolean {
        val job = pendingClearAll.getAndSet(null) ?: return false
        job.cancel()
        return true
    }

    fun reportCount(): Int = _reports.value.size

    /** Cancel the internal coroutine scope so background work doesn't outlive the
     *  process. Called from [AppContainer.shutdown]. The UncaughtExceptionHandler is
     *  intentionally left installed — a crash during shutdown still needs to be logged. */
    fun shutdown() {
        scope.cancel()
    }

    private fun writeReport(thread: Thread, throwable: Throwable) {
        val now = Date()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(now)
        // Append a short random suffix so two crashes in the same millisecond don't
        // overwrite each other.
        val suffix = java.lang.Long.toHexString(System.nanoTime())
        val file = File(crashDir, "crash-$stamp-$suffix.txt")
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("opencode-android crash report")
            pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(now)}")
            pw.println("Thread: ${thread.name}")
            pw.println("App version: ${appVersion()}")
            pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            pw.println()
            pw.println(throwable.javaClass.name + ": " + throwable.message)
            throwable.printStackTrace(pw)
        }
        // Scrub URLs from the *entire* report. printStackTrace re-emits the exception
        // message (and every "Caused by:" message) verbatim, so scrubbing only the
        // top-level message above is futile — Ktor/OkHttp embed the full request URL
        // in those messages, which may contain auth or internal paths. Scrubbing the
        // assembled text guarantees no URL survives anywhere in the stored report.
        //
        // NOTE: trimOldReports() is intentionally NOT called here. writeReport runs on the
        // crashing thread inside the UncaughtExceptionHandler, where the OS gives ~5s before
        // SIGKILL; trimOldReports does listFiles + sortedByDescending + N deletes that could
        // exceed that window on a slow device with many old reports, losing THIS report. The
        // trim is driven by refresh() instead (IO dispatcher, off the crashing thread), so the
        // crash path does only the single writeText it must to preserve the report.
        file.writeText(scrubUrls(sw.toString()))
    }

    /** Keep only the [MAX_REPORTS] most recent crash reports, deleting older ones. Runs inside
     *  the uncaught-exception handler (the whole call is wrapped in runCatching upstream), so it
     *  must not throw; each delete is guarded. */
    private fun trimOldReports() {
        val files = crashDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return
        if (files.size <= MAX_REPORTS) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_REPORTS)
            .forEach { runCatching { it.delete() } }
    }

    private fun appVersion(): String = runCatching {
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        "${pi.versionName} (${PackageInfoCompat.getLongVersionCode(pi)})"
    }.getOrDefault("unknown")

    companion object {
        /** Cap on retained crash report files. A deterministic crash-loop (e.g. a crash on
         *  startup) writes one report per crash with no other bound, so without this the
         *  crashes/ dir grows without limit until the user manually clears it. */
        private const val MAX_REPORTS = 20

        private const val KEY_ACKNOWLEDGED_AT = "acknowledged_at"

        /** Regex matching http(s) URLs, shared by report writing and [scrubUrls]. */
        internal val SCRUB_URL_REGEX = Regex("https?://[^\\s\"']+")

        /**
         * Replace every http(s) URL in [text] with `[url]`. Exposed as `internal` so the
         * redaction (used on the whole crash report, including exception messages and
         * stack traces produced by printStackTrace) can be unit-tested in isolation.
         */
        internal fun scrubUrls(text: String): String = SCRUB_URL_REGEX.replace(text, "[url]")

        @Volatile
        private var instance: CrashLogger? = null

        fun get(context: Context): CrashLogger =
            instance ?: synchronized(this) {
                instance ?: CrashLogger(context.applicationContext).also { instance = it }
            }
    }
}
