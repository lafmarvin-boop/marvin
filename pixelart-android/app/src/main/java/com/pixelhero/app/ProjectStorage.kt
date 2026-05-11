package com.pixelhero.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ProjectStorage {

    private fun dir(context: Context): File {
        val d = File(context.filesDir, "projects")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun list(context: Context): List<JSONObject> {
        return dir(context).listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.optLong("updatedAt", 0L) }
            ?: emptyList()
    }

    fun save(context: Context, project: Project) {
        project.updatedAt = System.currentTimeMillis()
        val json = JSONObject().apply {
            put("id", project.id)
            put("name", project.name)
            put("width", project.width)
            put("height", project.height)
            put("fps", project.fps)
            put("currentIndex", project.currentIndex)
            put("updatedAt", project.updatedAt)
            put("symmetry", project.symmetry.name)
            put("primaryColor", project.primaryColor.toLong() and 0xFFFFFFFFL)
            put("secondaryColor", project.secondaryColor.toLong() and 0xFFFFFFFFL)
            put("onionRange", project.onionRange)
            put("pixelPerfect", project.pixelPerfect)
            put("palette", JSONArray().apply { project.palette.forEach { put(it.toLong() and 0xFFFFFFFFL) } })
            put("recentColors", JSONArray().apply { project.recentColors.forEach { put(it.toLong() and 0xFFFFFFFFL) } })
            val framesArr = JSONArray()
            project.frames.forEach { f ->
                val buf = ByteBuffer.allocate(f.pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                f.pixels.forEach { buf.putInt(it) }
                val obj = JSONObject()
                obj.put("data", Base64.encodeToString(buf.array(), Base64.NO_WRAP))
                obj.put("tag", f.tag)
                obj.put("delayMs", f.delayMs)
                framesArr.put(obj)
            }
            put("frames", framesArr)
        }
        File(dir(context), "${project.id}.json").writeText(json.toString())
    }

    fun load(context: Context, id: String): Project? {
        val f = File(dir(context), "$id.json")
        if (!f.exists()) return null
        return fromJson(JSONObject(f.readText()))
    }

    fun delete(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
    }

    fun fromJson(json: JSONObject): Project {
        val w = json.getInt("width")
        val h = json.getInt("height")
        val framesArr = json.getJSONArray("frames")
        val frames = mutableListOf<Frame>()
        for (i in 0 until framesArr.length()) {
            val entry = framesArr.get(i)
            val (b64, tag, delay) = when (entry) {
                is JSONObject -> Triple(entry.getString("data"), entry.optString("tag", ""), entry.optInt("delayMs", 0))
                is String -> Triple(entry, "", 0)
                else -> continue
            }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val ints = IntArray(w * h)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until ints.size) ints[k] = buf.int
            val f = Frame(w, h, ints).apply { this.tag = tag; this.delayMs = delay }
            frames.add(f)
        }
        val palette = mutableListOf<Int>()
        json.optJSONArray("palette")?.let { for (i in 0 until it.length()) palette.add(it.getLong(i).toInt()) }
        if (palette.isEmpty()) palette.addAll(Project.DEFAULT_PALETTE)
        val recent = mutableListOf<Int>()
        json.optJSONArray("recentColors")?.let { for (i in 0 until it.length()) recent.add(it.getLong(i).toInt()) }
        val sym = runCatching { SymmetryAxis.valueOf(json.optString("symmetry", "NONE")) }.getOrDefault(SymmetryAxis.NONE)
        val resolvedFrames = if (frames.isEmpty()) mutableListOf(Frame(w, h)) else frames
        val resolvedIndex = json.optInt("currentIndex", 0).coerceIn(0, resolvedFrames.size - 1)
        return Project(
            name = json.optString("name", "Sans titre"),
            width = w, height = h,
            fps = json.optInt("fps", 8),
            frames = resolvedFrames,
            currentIndex = resolvedIndex,
            palette = palette,
            recentColors = recent,
            id = json.optString("id", "p_" + System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            symmetry = sym,
            primaryColor = json.optLong("primaryColor", 0xFFFF5577L).toInt(),
            secondaryColor = json.optLong("secondaryColor", 0xFF000000L).toInt(),
            onionRange = json.optInt("onionRange", 1),
            pixelPerfect = json.optBoolean("pixelPerfect", false)
        )
    }
}
