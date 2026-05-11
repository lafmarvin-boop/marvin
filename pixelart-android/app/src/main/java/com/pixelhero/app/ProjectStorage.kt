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
            put("palette", JSONArray().apply { project.palette.forEach { put(it.toLong() and 0xFFFFFFFFL) } })
            put("recentColors", JSONArray().apply { project.recentColors.forEach { put(it.toLong() and 0xFFFFFFFFL) } })
            val framesArr = JSONArray()
            project.frames.forEach { f ->
                val buf = ByteBuffer.allocate(f.pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                f.pixels.forEach { buf.putInt(it) }
                framesArr.put(Base64.encodeToString(buf.array(), Base64.NO_WRAP))
            }
            put("frames", framesArr)
        }
        File(dir(context), "${project.id}.json").writeText(json.toString())
    }

    fun load(context: Context, id: String): Project? {
        val f = File(dir(context), "$id.json")
        if (!f.exists()) return null
        val json = JSONObject(f.readText())
        return fromJson(json)
    }

    fun delete(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
    }

    fun fromJson(json: JSONObject): Project {
        val w = json.getInt("width")
        val h = json.getInt("height")
        val framesB64 = json.getJSONArray("frames")
        val frames = mutableListOf<Frame>()
        for (i in 0 until framesB64.length()) {
            val bytes = Base64.decode(framesB64.getString(i), Base64.DEFAULT)
            val ints = IntArray(w * h)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until ints.size) ints[k] = buf.int
            frames.add(Frame(w, h, ints))
        }
        val palette = mutableListOf<Int>()
        val pArr = json.optJSONArray("palette")
        if (pArr != null) for (i in 0 until pArr.length()) palette.add(pArr.getLong(i).toInt())
        else palette.addAll(Project.DEFAULT_PALETTE)
        val recent = mutableListOf<Int>()
        val rArr = json.optJSONArray("recentColors")
        if (rArr != null) for (i in 0 until rArr.length()) recent.add(rArr.getLong(i).toInt())
        val resolvedFrames = if (frames.isEmpty()) mutableListOf(Frame(w, h)) else frames
        val resolvedIndex = json.optInt("currentIndex", 0).coerceIn(0, resolvedFrames.size - 1)
        return Project(
            name = json.optString("name", "Sans titre"),
            width = w,
            height = h,
            fps = json.optInt("fps", 8),
            frames = resolvedFrames,
            currentIndex = resolvedIndex,
            palette = palette,
            recentColors = recent,
            id = json.optString("id", "p_" + System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}
