package soy.iko.opencode

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reporter that installs as the global [Thread.UncaughtExceptionHandler].
 *
 * Crashes are written to logcat and persisted to internal storage
 * (`filesDir/crashes/`) so they survive process death. The persisted reports
 * can be enumerated with [getReports], exported, or cleared with [clearReports].
 *
 * Call [init] once in [Application.onCreate] before any other work.
 */
object CrashLogger {

    private const val TAG = "opencode-crash"
    private const val CRASH_DIR = "crashes"
    private const val MAX_REPORTS = 10

    private lateinit var appContext: Context
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val _reportCount = MutableStateFlow(0)
    /** Live count of persisted crash reports, safe to observe in composition. */
    val reportCount: StateFlow<Int> = _reportCount.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshReportCount()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            report(throwable, thread.name)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun refreshReportCount() {
        _reportCount.value = getReports().size
    }

    /**
     * Persist a crash report and log it. Safe to call from any thread,
     * including the crashing thread.
     */
    fun report(throwable: Throwable, source: String = Thread.currentThread().name) {
        val stackTrace = stackTraceString(throwable)
        Log.e(TAG, "Uncaught exception in [$source]\n$stackTrace", throwable)
        persistReport(source, stackTrace)
    }

    /**
     * Enumerate persisted crash report files, newest first.
     */
    fun getReports(): List<File> =
        crashDir().listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    /**
     * Delete all persisted crash reports.
     */
    fun clearReports() {
        crashDir().listFiles()?.forEach { it.delete() }
        refreshReportCount()
    }

    private fun persistReport(source: String, stackTrace: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(crashDir(), "crash_${timestamp}.txt")
            file.writeText("Time: $timestamp\nThread: $source\n\n$stackTrace")
            pruneOldReports()
            refreshReportCount()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist crash report", e)
        }
    }

    private fun pruneOldReports() {
        val reports = getReports()
        if (reports.size > MAX_REPORTS) {
            reports.drop(MAX_REPORTS).forEach { it.delete() }
        }
    }

    private fun crashDir(): File =
        File(appContext.filesDir, CRASH_DIR).apply { mkdirs() }

    private fun stackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
