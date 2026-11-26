package io.github.oatomh.spox

import android.content.Context
import java.io.File

/** Keeps the last crash (what broke, where) on the phone, for Settings → Report. */
object CrashLog {
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val file = File(ctx.applicationContext.filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                file.writeText("$time on ${t.name}\n" + e.stackTraceToString().lines().take(25).joinToString("\n"))
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun last(ctx: Context): String = runCatching { File(ctx.filesDir, "crash.txt").readText() }.getOrDefault("none")
}
