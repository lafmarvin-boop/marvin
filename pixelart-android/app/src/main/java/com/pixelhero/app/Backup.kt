package com.pixelhero.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup / restore of the app's data. Exports every saved project
 * (JSON + thumbnail) as a single .zip file the user can share or store
 * for safekeeping. Restore takes a .zip and adds (or overwrites) all
 * contained projects into the app's storage.
 */
object Backup {

    private const val MANIFEST = "manifest.json"
    private const val PROJECTS_DIR = "projects/"

    /** Export all saved projects to a ZIP byte array. */
    fun exportAll(context: Context): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            // Manifest
            val manifest = JSONObject().apply {
                put("app", "PixelHero")
                put("version", 1)
                put("exportedAt", System.currentTimeMillis())
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            // Each project file
            val projects = ProjectStorage.list(context)
            for (p in projects) {
                val id = p.optString("id")
                if (id.isEmpty()) continue
                val jsonFile = File(File(context.filesDir, "projects"), "$id.json")
                if (jsonFile.exists()) {
                    zip.putNextEntry(ZipEntry("$PROJECTS_DIR$id.json"))
                    zip.write(jsonFile.readBytes())
                    zip.closeEntry()
                }
                val thumbFile = ProjectStorage.thumbnailFile(context, id)
                if (thumbFile != null && thumbFile.exists()) {
                    zip.putNextEntry(ZipEntry("$PROJECTS_DIR$id.thumb.png"))
                    zip.write(thumbFile.readBytes())
                    zip.closeEntry()
                }
            }
        }
        return bos.toByteArray()
    }

    /** Import a ZIP into the app's projects directory. Returns number of projects restored. */
    fun importAll(context: Context, input: InputStream): Int {
        val projectsDir = File(context.filesDir, "projects").apply { mkdirs() }
        var count = 0
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.startsWith(PROJECTS_DIR) && !entry.isDirectory) {
                    val filename = name.substring(PROJECTS_DIR.length)
                    val out = File(projectsDir, filename)
                    out.outputStream().use { zip.copyTo(it) }
                    if (filename.endsWith(".json")) count++
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return count
    }

    fun importAll(context: Context, uri: Uri): Int {
        val input = context.contentResolver.openInputStream(uri) ?: return 0
        return input.use { importAll(context, it) }
    }
}

/**
 * Crash recovery: keeps a snapshot of the current project on disk that's
 * automatically refreshed on every undo push. If the app crashes or is killed
 * before saving, the next launch can offer to restore from this snapshot.
 */
object CrashRecovery {

    private const val RECOVERY_FILE = "_crash_recovery.json"
    private const val FLAG_FILE = "_session_active.flag"

    fun beginSession(context: Context) {
        File(context.filesDir, FLAG_FILE).writeText("active")
    }

    fun endSession(context: Context) {
        File(context.filesDir, FLAG_FILE).delete()
        File(context.filesDir, RECOVERY_FILE).delete()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .remove("crashRestoredId").apply()
    }

    fun snapshot(context: Context, project: Project) {
        // Reuse ProjectStorage.save to a custom file
        val tmp = ProjectStorage.serializeToJson(project).toString()
        File(context.filesDir, RECOVERY_FILE).writeText(tmp)
    }

    /** Did the previous session end abnormally (flag still present)? */
    fun hasUnsavedSession(context: Context): Boolean {
        return File(context.filesDir, FLAG_FILE).exists() &&
               File(context.filesDir, RECOVERY_FILE).exists()
    }

    fun restore(context: Context): Project? {
        val f = File(context.filesDir, RECOVERY_FILE)
        if (!f.exists()) return null
        return runCatching {
            val p = ProjectStorage.deserializeJson(JSONObject(f.readText()))
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString("crashRestoredId", p.id).apply()
            p
        }.getOrNull()
    }

    /** Project id of the most recent crash-recovery restore (cleared on endSession). */
    fun lastRestoredId(context: Context): String? =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("crashRestoredId", null)
}
