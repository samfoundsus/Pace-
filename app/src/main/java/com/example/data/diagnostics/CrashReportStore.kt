package com.example.data.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stores the latest uncaught exception report locally in the app's private storage
 * (maximum 20 KB, never transmitted anywhere).
 */
class CrashReportStore(context: Context) {

    private val appContext = context.applicationContext
    private val reportFile = File(appContext.filesDir, REPORT_FILE_NAME)

    private val _hasCrashReport = MutableStateFlow(reportExistsOnDisk())
    val hasCrashReport: StateFlow<Boolean> = _hasCrashReport.asStateFlow()

    fun refresh(): Boolean {
        val exists = reportExistsOnDisk()
        _hasCrashReport.value = exists
        return exists
    }

    fun readReport(): String? {
        return runCatching {
            if (!reportFile.exists() || reportFile.length() <= 0L) {
                null
            } else {
                reportFile.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    fun writeReport(
        threadName: String,
        throwable: Throwable,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        val reportText = formatReport(
            context = appContext,
            threadName = threadName,
            throwable = throwable,
            timestampMillis = timestampMillis,
        )
        writeReportText(reportText)
    }

    fun writeReportText(rawText: String) {
        val boundedBytes = truncateToUtf8ByteLimit(rawText, MAX_REPORT_BYTES)
        runCatching {
            reportFile.parentFile?.mkdirs()
            reportFile.writeBytes(boundedBytes)
        }
        _hasCrashReport.value = reportExistsOnDisk()
    }

    fun deleteReport() {
        runCatching {
            if (reportFile.exists()) {
                reportFile.delete()
            }
        }
        _hasCrashReport.value = false
    }

    private fun reportExistsOnDisk(): Boolean {
        return runCatching {
            reportFile.exists() && reportFile.isFile && reportFile.length() > 0L
        }.getOrDefault(false)
    }

    companion object {
        const val REPORT_FILE_NAME = "last_crash_report.txt"
        const val MAX_REPORT_BYTES = 20 * 1024
        private const val TRUNCATION_NOTICE = "\n... [truncated to 20 KB]\n"

        private val handlerInstalled = AtomicBoolean(false)

        /**
         * Installs a process-wide uncaught exception handler that writes the latest crash report
         * to private app storage and then delegates to the previous default handler.
         */
        fun installUncaughtExceptionHandler(context: Context) {
            if (!handlerInstalled.compareAndSet(false, true)) return
            val appContext = context.applicationContext
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                createUncaughtExceptionHandler(
                    context = appContext,
                    delegate = previousHandler,
                ),
            )
        }

        internal fun createUncaughtExceptionHandler(
            context: Context,
            delegate: Thread.UncaughtExceptionHandler?,
        ): Thread.UncaughtExceptionHandler {
            val appContext = context.applicationContext
            return Thread.UncaughtExceptionHandler { thread, throwable ->
                try {
                    val store = CrashReportStore(appContext)
                    store.writeReport(
                        threadName = thread.name.ifBlank { "unknown" },
                        throwable = throwable,
                    )
                } catch (_: Throwable) {
                    // Never suppress or replace the original crash.
                } finally {
                    delegate?.uncaughtException(thread, throwable)
                }
            }
        }

        internal fun formatReport(
            context: Context,
            threadName: String,
            throwable: Throwable,
            timestampMillis: Long = System.currentTimeMillis(),
        ): String {
            val timestamp = formatTimestampUtc(timestampMillis)
            val appVersion = readAppVersion(context)
            val androidVersion = "Android ${Build.VERSION.RELEASE ?: "unknown"} (API ${Build.VERSION.SDK_INT})"
            val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
            val model = Build.MODEL?.trim().orEmpty()
            val deviceModel = when {
                manufacturer.isBlank() && model.isBlank() -> "Unknown"
                model.startsWith(manufacturer, ignoreCase = true) -> model
                manufacturer.isBlank() -> model
                model.isBlank() -> manufacturer
                else -> "$manufacturer $model"
            }

            val stackTrace = StringWriter().use { sw ->
                PrintWriter(sw).use { pw ->
                    throwable.printStackTrace(pw)
                    pw.flush()
                }
                sw.toString()
            }

            return buildString {
                appendLine("Pace Crash Report")
                appendLine("Time (UTC): $timestamp")
                appendLine("App version: $appVersion")
                appendLine("Android: $androidVersion")
                appendLine("Device: $deviceModel")
                appendLine("Thread: $threadName")
                appendLine()
                append(stackTrace)
            }
        }

        internal fun truncateToUtf8ByteLimit(text: String, maxBytes: Int): ByteArray {
            val fullBytes = text.toByteArray(Charsets.UTF_8)
            if (fullBytes.size <= maxBytes) {
                return fullBytes
            }
            val noticeBytes = TRUNCATION_NOTICE.toByteArray(Charsets.UTF_8)
            val budget = (maxBytes - noticeBytes.size).coerceAtLeast(0)
            if (budget == 0) {
                return fullBytes.copyOf(maxBytes)
            }
            // Walk back to a valid UTF-8 character boundary so truncated output stays valid UTF-8.
            var end = budget
            while (end > 0 && (fullBytes[end].toInt() and 0xC0) == 0x80) {
                end--
            }
            val result = ByteArray(end + noticeBytes.size)
            System.arraycopy(fullBytes, 0, result, 0, end)
            System.arraycopy(noticeBytes, 0, result, end, noticeBytes.size)
            return result
        }

        private fun readAppVersion(context: Context): String {
            return runCatching {
                val pm = context.packageManager
                val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(context.packageName, 0)
                }
                val versionName = pkgInfo.versionName?.takeIf { it.isNotBlank() } ?: "unknown"
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode.toLong()
                }
                "$versionName ($versionCode)"
            }.getOrDefault("unknown")
        }

        private fun formatTimestampUtc(timestampMillis: Long): String {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            return formatter.format(Date(timestampMillis))
        }
    }
}
