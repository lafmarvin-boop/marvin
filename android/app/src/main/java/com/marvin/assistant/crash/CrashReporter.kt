package com.marvin.assistant.crash

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reporter local : install un UncaughtExceptionHandler global qui
 * sauvegarde chaque crash dans un fichier .log dans filesDir/crashes/.
 *
 * Pas d'envoi cloud (pas de Sentry / Firebase Crashlytics) — tout reste
 * local. L'utilisateur peut exporter le dossier via Diagnostic dump pour
 * partager.
 *
 * Rotation : on garde les 10 derniers crash logs, on drop les plus vieux.
 */
class CrashReporter(private val context: Context) {

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                dumpCrash(thread, ex)
            } catch (t: Throwable) {
                Log.e(TAG, "Crash reporter itself failed", t)
            }
            // Délégue ensuite au handler système pour que l'app crash
            // proprement (sinon elle reste figée en zombie).
            previous?.uncaughtException(thread, ex)
        }
        Log.i(TAG, "Crash reporter installed")
    }

    private fun dumpCrash(thread: Thread, ex: Throwable) {
        val dir = File(context.filesDir, "crashes").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$ts.log")
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        file.writeText(buildString {
            append("=== Marvin crash ===\n")
            append("When: ${Date()}\n")
            append("Thread: ${thread.name}\n")
            append("Exception: ${ex::class.java.name}\n")
            append("Message: ${ex.message}\n\n")
            append("--- Stack ---\n")
            append(sw.toString())
        })
        Log.e(TAG, "Crash dumped to ${file.absolutePath}")

        // Rotation : garde les 10 plus recents
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(10).forEach { runCatching { it.delete() } }
    }

    /** Liste les crashes enregistrés (pour UI debug). */
    fun list(): List<File> {
        val dir = File(context.filesDir, "crashes")
        return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun clearAll() {
        File(context.filesDir, "crashes").deleteRecursively()
    }

    companion object { private const val TAG = "CrashReporter" }
}
