package com.example.hinditv

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records why the app closed so it can tell you on the next start instead of vanishing silently.
 * (Crashes inside the TV's own video chip driver cannot be caught by any app.)
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(app: android.app.Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val oom = e is OutOfMemoryError || e.cause is OutOfMemoryError
                val ch = Session.channels.getOrNull(Session.index)?.name ?: "-"
                val sb = StringBuilder()
                sb.append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
                sb.append("OOM=").append(oom).append('\n')
                sb.append("Channel: ").append(ch).append('\n')
                sb.append(e.toString()).append('\n')
                e.stackTrace.take(8).forEach { sb.append("  at ").append(it).append('\n') }
                file(app).writeText(sb.toString())
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun pending(ctx: Context): Boolean = file(ctx).exists()

    /** Returns the stored report and deletes it. */
    fun consume(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val s = try { f.readText() } catch (e: Exception) { null }
        f.delete()
        return s
    }
}
