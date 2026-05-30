package com.pixelhero.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight in-app crash reporter. Hooks [Thread.setDefaultUncaughtExceptionHandler]
 * to capture fatal exceptions into a file in the app's internal storage. On the
 * next launch, [consume] returns the report so the activity can show it to the
 * user.
 *
 * No network — the user copy-pastes the report manually. This avoids any
 * permission, privacy, or vendor-sdk concern.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("PixelHero crash report")
                pw.println("Date: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                pw.println("Thread: ${thread.name}")
                pw.println("Android: ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})")
                pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                pw.println("App version: ${appVersion(app)}")
                val runtime = Runtime.getRuntime()
                pw.println("Memory: used=${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576}MB " +
                    "total=${runtime.totalMemory() / 1_048_576}MB max=${runtime.maxMemory() / 1_048_576}MB")
                pw.println()
                pw.println("--- Stack trace ---")
                throwable.printStackTrace(pw)
                pw.flush()
                File(app.filesDir, FILE).writeText(sw.toString())
            } catch (_: Throwable) {
                // Don't loop if writing the report itself fails.
            }
            // Forward to whatever was registered before (system default usually).
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Read the last crash report and delete it. Returns null if none. */
    fun consume(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        return try {
            val text = f.readText()
            f.delete()
            text
        } catch (_: Throwable) {
            null
        }
    }

    private fun appVersion(context: Context): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        } catch (_: Throwable) {
            "unknown"
        }
    }
}
