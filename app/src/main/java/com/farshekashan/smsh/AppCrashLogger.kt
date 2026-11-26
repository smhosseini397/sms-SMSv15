package com.farshekashan.smsh

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent, privacy-conscious diagnostics. SMS bodies and phone numbers are never logged. */
object AppCrashLogger {
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val KEEP_AFTER_ROTATION_BYTES = 256 * 1024L
    private const val LOG_FILE_NAME = "smsh-diagnostics.log"
    private val lock = Any()
    @Volatile private var initialized = false

    private fun logFile(context: Context): File = File(context.applicationContext.filesDir, LOG_FILE_NAME)

    fun install(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    write(appContext, "FATAL", "Uncaught exception on thread=${thread.name}", error)
                } catch (_: Throwable) { }
                try {
                    previous?.uncaughtException(thread, error)
                } catch (_: Throwable) { }
            }
            initialized = true
            record(
                appContext,
                "APP",
                "Application process started; device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; android=${android.os.Build.VERSION.SDK_INT}; appVersion=${runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull() ?: "unknown"}"
            )
        }
    }

    fun record(context: Context, category: String, message: String) {
        write(context.applicationContext, category, message, null)
    }

    fun recordError(context: Context, category: String, message: String, error: Throwable) {
        write(context.applicationContext, category, message, error)
    }

    private fun write(context: Context, category: String, message: String, error: Throwable?) {
        synchronized(lock) {
            try {
                val file = logFile(context)
                file.parentFile?.mkdirs()
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    val old = file.readBytes()
                    val start = (old.size - KEEP_AFTER_ROTATION_BYTES.toInt()).coerceAtLeast(0)
                    FileOutputStream(file, false).use { out ->
                        out.write("\n--- log rotated; keeping recent entries ---\n".toByteArray(Charsets.UTF_8))
                        out.write(old, start, old.size - start)
                        out.fd.sync()
                    }
                }
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val builder = StringBuilder()
                    .append('\n').append('[').append(timestamp).append("] ")
                    .append(category).append(" | ").append(message).append('\n')
                if (error != null) {
                    val trace = StringWriter()
                    error.printStackTrace(PrintWriter(trace))
                    builder.append(trace)
                }
                FileOutputStream(file, true).use { out ->
                    out.write(builder.toString().toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
            } catch (_: Throwable) {
                // Diagnostics must never crash or interfere with the SMS application.
            }
        }
    }

    fun read(context: Context): String {
        return try {
            val file = logFile(context)
            if (!file.exists()) "هنوز گزارشی ثبت نشده است." else {
                val text = file.readText(Charsets.UTF_8)
                if (text.length > 200_000) text.takeLast(200_000) else text
            }
        } catch (error: Exception) {
            "خواندن گزارش ناموفق بود: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }
    }

    fun clear(context: Context): Boolean = try {
        synchronized(lock) {
            val file = logFile(context)
            if (file.exists()) file.writeText("گزارش‌ها پاک شدند.\n", Charsets.UTF_8)
            true
        }
    } catch (_: Exception) { false }
}
