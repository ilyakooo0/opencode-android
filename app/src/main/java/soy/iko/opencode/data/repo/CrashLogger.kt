package soy.iko.opencode.data.repo

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
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

    private val _reports = MutableStateFlow<List<CrashReport>>(emptyList())
    val reports: StateFlow<List<CrashReport>> = _reports.asStateFlow()

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(thread, throwable) }
                .onFailure { Log.e("CrashLogger", "Failed to write crash report", it) }
            previous?.uncaughtException(thread, throwable)
        }
        // Load crash reports off the main thread so startup isn't blocked by file I/O
        // (listFiles + reading the first line of each report). The StateFlow updates
        // whenever the scan completes, so the Diagnostics screen reflects the result.
        Thread { refresh() }.start()
    }

    fun refresh() {
        _reports.value = crashDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                CrashReport(
                    fileName = f.name,
                    timestamp = f.lastModified(),
                    preview = runCatching { f.useLines { it.firstOrNull() ?: "" } }.getOrDefault(""),
                )
            }
            .orEmpty()
    }

    fun readReport(fileName: String): String? {
        val file = File(crashDir, fileName).canonicalFile
        if (!file.path.startsWith(crashDir.canonicalPath + File.separator) || !file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun deleteReport(fileName: String) {
        val file = File(crashDir, fileName).canonicalFile
        if (file.path.startsWith(crashDir.canonicalPath + File.separator)) {
            file.delete()
        }
        refresh()
    }

    fun clearAll() {
        crashDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.forEach { it.delete() }
        refresh()
    }

    fun reportCount(): Int = _reports.value.size

    private fun writeReport(thread: Thread, throwable: Throwable) {
        val now = Date()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(now)
        val file = File(crashDir, "crash-$stamp.txt")
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
        file.writeText(sw.toString())
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
        @Volatile
        private var instance: CrashLogger? = null

        fun get(context: Context): CrashLogger =
            instance ?: synchronized(this) {
                instance ?: CrashLogger(context.applicationContext).also { instance = it }
            }
    }
}
