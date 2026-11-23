package com.chdroid.app.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * On-device diagnostic log. Every terminal path drop is written as a breadcrumb,
 * and an uncaught exception appends its full stack trace. The next launch shows
 * the whole file in a dialog so a force-close can be read without adb.
 */
object CrashLogger {
    const val FILE_NAME = "chdroid-crash.txt"

    private fun file(context: Context): File? =
        context.applicationContext.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

    /** Append a breadcrumb so the last line before a crash names the failing step. */
    fun log(message: String) {
        runCatching {
            val f = file(appContext ?: return) ?: return
            f.appendText("[trace] $message\n")
        }
    }

    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val body = buildString {
                    appendLine("==========================================")
                    appendLine("CRASH ${System.currentTimeMillis()} - thread ${thread.name}")
                    appendLine("==========================================")
                    append(sw.toString())
                    appendLine()
                }
                (file(context) ?: return@runCatching).appendText(body)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Full log text, or null if nothing has been recorded. */
    fun read(context: Context): String? {
        val f = file(context) ?: return null
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    fun clear(context: Context) {
        runCatching { file(context)?.delete() }
    }
}
