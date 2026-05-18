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

    /** Save a 64-px PNG thumbnail of the first frame next to the project JSON. */
    fun saveThumbnail(context: Context, project: Project) {
        runCatching {
            val frame = project.frames.firstOrNull() ?: return
            val srcPixels = if (frame.layers.size > 1) frame.composited() else frame.pixels
            val sw = frame.width; val sh = frame.height
            val target = 64
            val scale = maxOf(1, minOf(target / sw, target / sh).coerceAtLeast(1))
            val outW = sw * scale
            val outH = sh * scale
            val bmp = android.graphics.Bitmap.createBitmap(outW, outH, android.graphics.Bitmap.Config.ARGB_8888)
            // Nearest-neighbor scaling
            for (y in 0 until outH) for (x in 0 until outW) {
                val sx = x / scale
                val sy = y / scale
                bmp.setPixel(x, y, srcPixels[sy * sw + sx])
            }
            val bytes = java.io.ByteArrayOutputStream().apply {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this)
            }.toByteArray()
            bmp.recycle()
            File(dir(context), "${project.id}.thumb.png").writeBytes(bytes)
        }
    }

    fun thumbnailFile(context: Context, projectId: String): File? {
        val f = File(dir(context), "$projectId.thumb.png")
        return if (f.exists()) f else null
    }

    fun save(context: Context, project: Project) {
        project.updatedAt = System.currentTimeMillis()
        saveThumbnail(context, project)
        File(dir(context), "${project.id}.json").writeText(serializeToJson(project).toString())
    }

    /** Build the project JSON without writing to disk (used by Backup + CrashRecovery). */
    fun serializeToJson(project: Project): JSONObject {
        return JSONObject().apply {
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
            put("onionTrailOnly", project.onionTrailOnly)
            put("pixelPerfect", project.pixelPerfect)
            put("bgFit", project.bgFit.name)
            put("palette", JSONArray().apply { project.palette.forEach { put(it.toLong() and 0xFFFFFFFFL) } })
            put("recentColors", JSONArray().apply { project.recentColors.forEach { put(it.toLong() and 0xFFFFFFFFL) } })
            val framesArr = JSONArray()
            project.frames.forEach { f ->
                val obj = JSONObject()
                // Store all layers
                val layersArr = JSONArray()
                f.layers.forEach { layer ->
                    val buf = ByteBuffer.allocate(layer.pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    layer.pixels.forEach { buf.putInt(it) }
                    val lo = JSONObject()
                    lo.put("name", layer.name)
                    lo.put("visible", layer.visible)
                    lo.put("opacity", layer.opacity.toDouble())
                    layer.groupName?.let { lo.put("groupName", it) }
                    lo.put("data", Base64.encodeToString(buf.array(), Base64.NO_WRAP))
                    layersArr.put(lo)
                }
                obj.put("layers", layersArr)
                obj.put("activeLayer", f.activeLayer)
                obj.put("tag", f.tag)
                obj.put("delayMs", f.delayMs)
                if (f.kind != FrameKind.NONE) obj.put("kind", f.kind.name)
                // Legacy: also store flat composite for backward compat with old loaders
                framesArr.put(obj)
            }
            put("frames", framesArr)
        }
    }

    /** Alias for fromJson (cleaner name for external callers). */
    fun deserializeJson(json: JSONObject): Project = fromJson(json)

    fun load(context: Context, id: String): Project? {
        val f = File(dir(context), "$id.json")
        if (!f.exists()) return null
        return fromJson(JSONObject(f.readText()))
    }

    fun delete(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
        File(dir(context), "$id.thumb.png").delete()
    }

    fun fromJson(json: JSONObject): Project {
        val w = json.getInt("width")
        val h = json.getInt("height")
        val framesArr = json.getJSONArray("frames")
        val frames = mutableListOf<Frame>()
        for (i in 0 until framesArr.length()) {
            val entry = framesArr.get(i)
            val frame = when (entry) {
                is JSONObject -> {
                    val tag = entry.optString("tag", "")
                    val delay = entry.optInt("delayMs", 0)
                    val layersArr = entry.optJSONArray("layers")
                    if (layersArr != null && layersArr.length() > 0) {
                        // New format with layers
                        val f = Frame(w, h)
                        f.layers.clear()
                        for (li in 0 until layersArr.length()) {
                            val lo = layersArr.getJSONObject(li)
                            val bytes = Base64.decode(lo.getString("data"), Base64.DEFAULT)
                            val ints = IntArray(w * h)
                            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                            for (k in 0 until ints.size) ints[k] = buf.int
                            val layer = Layer(w, h, lo.optString("name", "Couche ${li + 1}"), ints)
                            layer.visible = lo.optBoolean("visible", true)
                            layer.opacity = lo.optDouble("opacity", 1.0).toFloat()
                            layer.groupName = if (lo.has("groupName")) lo.optString("groupName") else null
                            f.layers.add(layer)
                        }
                        f.activeLayer = entry.optInt("activeLayer", 0).coerceIn(0, f.layers.size - 1)
                        f.tag = tag; f.delayMs = delay
                        f.kind = runCatching { FrameKind.valueOf(entry.optString("kind", "NONE")) }.getOrDefault(FrameKind.NONE)
                        f
                    } else {
                        // Legacy format: single flat pixels array
                        val bytes = Base64.decode(entry.getString("data"), Base64.DEFAULT)
                        val ints = IntArray(w * h)
                        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        for (k in 0 until ints.size) ints[k] = buf.int
                        Frame(w, h, ints).apply { this.tag = tag; this.delayMs = delay }
                    }
                }
                is String -> {
                    val bytes = Base64.decode(entry, Base64.DEFAULT)
                    val ints = IntArray(w * h)
                    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (k in 0 until ints.size) ints[k] = buf.int
                    Frame(w, h, ints)
                }
                else -> continue
            }
            frames.add(frame)
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
            onionRange = json.optInt("onionRange", 2),
            onionTrailOnly = json.optBoolean("onionTrailOnly", false),
            pixelPerfect = json.optBoolean("pixelPerfect", false),
            bgFit = runCatching { BgFitMode.valueOf(json.optString("bgFit", "FIT")) }.getOrDefault(BgFitMode.FIT)
        )
    }
}
