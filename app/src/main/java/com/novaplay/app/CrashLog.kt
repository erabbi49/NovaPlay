package com.novaplay.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash in a small file, so it can be copied out of the app
 * (Settings > About) and sent to whoever fixes the bug. Nothing is ever uploaded.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val MAX_CHARS = 12_000

    private var file: File? = null

    fun install(context: Context) {
        val f = File(context.applicationContext.filesDir, FILE)
        file = f
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                f.writeText(describe(thread, error))
            } catch (e: Throwable) {
                // nothing more can be done while crashing
            }
            if (previous != null) previous.uncaughtException(thread, error)
        }
    }

    /** The saved report, or null when the app has not crashed since it was last cleared. */
    fun read(): String? {
        val f = file ?: return null
        return try {
            if (f.exists()) f.readText().ifBlank { null } else null
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        try {
            file?.delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun describe(thread: Thread, error: Throwable): String {
        val trace = StringWriter()
        error.printStackTrace(PrintWriter(trace))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val head = "NovaPlay 1.0.0 | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | " +
            "${Build.MANUFACTURER} ${Build.MODEL}\n$time | thread: ${thread.name}\n\n"
        val text = head + trace.toString()
        return if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
    }
}
