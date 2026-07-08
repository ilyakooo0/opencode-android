package soy.iko.opencode

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight crash reporter that installs as the global uncaught-exception
 * handler. Crashes are logged to logcat and persisted to internal storage so
 * they survive process death and can be inspected or exported later.
 */
object CrashLogger {

    private const val TAG = "opencode-crash"
    private const val CRASH_DIR = "crashes"
    private const val MAX_REPORTS = 10

    private lateinit var appContext: Context
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            report(throwable, thread.name)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun report(throwable: Throwable, source: String = Thread.currentThread().name) {
        val stackTrace = stackTraceString(throwable)
        Log.e(TAG, "Uncaught exception in [$source]\n$stackTrace", throwable)
        persistReport(source, stackTrace)
    }

    fun getReports(): List<File> =
        crashDir().listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun clearReports() {
        crashDir().listFiles()?.forEach { it.delete() }
    }

    private fun persistReport(source: String, stackTrace: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(crashDir(), "crash_${timestamp}.txt")
            file.writeText("Time: $timestamp\nThread: $source\n\n$stackTrace")
            pruneOldReports()
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
