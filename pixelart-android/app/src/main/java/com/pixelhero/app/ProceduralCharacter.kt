package com.pixelhero.app

import java.util.Random

/**
 * Generate a random pixel-art character by starting from a pose template
 * and filling regions with random body, clothing, and accessory colours.
 */
object ProceduralCharacter {

    fun generate(pose: PoseTemplates.Pose, w: Int, h: Int, seed: Long = System.currentTimeMillis()): IntArray {
        val r = Random(seed)
        val baseOutline = PoseTemplates.render(pose, w, h)
        // The pose template paints outline + joint markers. We rebuild a full character
        // by filling the bounding box with skin, then layering clothes/hair/accessories.
        val out = IntArray(w * h)

        // Random palettes
        val skin = pickRandom(SKIN_TONES, r)
        val hair = pickRandom(HAIR_COLORS, r)
        val shirt = pickRandom(SHIRT_COLORS, r)
        val pants = pickRandom(PANTS_COLORS, r)
        val shoes = pickRandom(SHOE_COLORS, r)
        val outline = 0xFF1A1428.toInt()

        // Find the outline bounding box
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((baseOutline[y * w + x] ushr 24) and 0xFF >= 128) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return baseOutline // no template, return as-is

        val bbH = maxY - minY + 1
        val headBottom = minY + bbH / 4
        val bodyBottom = minY + (bbH * 2) / 3
        val legsBottom = maxY

        // Fill inside the silhouette per region
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val k = y * w + x
                val isOutline = (baseOutline[k] ushr 24) and 0xFF >= 128
                if (isOutline) {
                    out[k] = outline
                    continue
                }
                // Check if inside silhouette: scan-line approach (find outline pixels to left and right)
                var leftHit = false; var rightHit = false
                for (xx in minX..x) if ((baseOutline[y * w + xx] ushr 24) and 0xFF >= 128) { leftHit = true; break }
                for (xx in x..maxX) if ((baseOutline[y * w + xx] ushr 24) and 0xFF >= 128) { rightHit = true; break }
                if (!leftHit || !rightHit) continue
                // Assign color based on vertical region
                out[k] = when {
                    y <= headBottom -> skin   // head
                    y <= bodyBottom -> shirt  // body
                    y <= legsBottom - bbH / 8 -> pants
                    else -> shoes
                }
            }
        }

        // Hair on top of head: shift the top 1/4 to hair color (except face area)
        for (y in minY..headBottom - 2) {
            for (x in minX..maxX) {
                val k = y * w + x
                if (out[k] == skin) out[k] = hair
            }
        }
        // Eyes (random color, 2 dots inside head)
        val eyeY = minY + bbH / 6 + r.nextInt(2)
        val cx = (minX + maxX) / 2
        if (eyeY in 0 until h) {
            setSafe(out, w, h, cx - 1, eyeY, outline)
            setSafe(out, w, h, cx + 1, eyeY, outline)
        }
        // Random accessory: belt
        if (r.nextBoolean()) {
            val beltY = bodyBottom
            for (x in minX..maxX) {
                val k = beltY * w + x
                if (out[k] == shirt || out[k] == pants) out[k] = pickRandom(BELT_COLORS, r)
            }
        }
        // Random accessory: shoulder piece
        if (r.nextInt(3) == 0) {
            val shoulderY = headBottom + 1
            for (dx in -1..1) {
                val k = shoulderY * w + (cx + dx)
                if (k in out.indices && out[k] == shirt) out[k] = pickRandom(ACCESSORY_COLORS, r)
            }
        }
        // Compose with outline on top (already done since outline > 0 alpha overrides)
        return out
    }

    private fun setSafe(p: IntArray, w: Int, h: Int, x: Int, y: Int, c: Int) {
        if (x in 0 until w && y in 0 until h) p[y * w + x] = c
    }

    private fun <T> pickRandom(arr: List<T>, r: Random): T = arr[r.nextInt(arr.size)]

    private val SKIN_TONES = listOf(
        0xFFF2C09B.toInt(), 0xFFE6A872.toInt(), 0xFFC68642.toInt(),
        0xFF8D5524.toInt(), 0xFFFCE2C9.toInt(), 0xFFCB8156.toInt()
    )
    private val HAIR_COLORS = listOf(
        0xFF2E1A0B.toInt(), 0xFF3D2A1A.toInt(), 0xFF8B4513.toInt(),
        0xFFFFCC33.toInt(), 0xFFD64545.toInt(), 0xFFAAAAAA.toInt(),
        0xFF0066CC.toInt(), 0xFF66FF99.toInt(), 0xFFFF66CC.toInt()
    )
    private val SHIRT_COLORS = listOf(
        0xFFD64545.toInt(), 0xFF3366FF.toInt(), 0xFF22AA66.toInt(),
        0xFFFFAA33.toInt(), 0xFF8844CC.toInt(), 0xFFFFFFFF.toInt(),
        0xFF222222.toInt(), 0xFFAABB22.toInt()
    )
    private val PANTS_COLORS = listOf(
        0xFF333366.toInt(), 0xFF553322.toInt(), 0xFF222222.toInt(),
        0xFF606060.toInt(), 0xFF8B4513.toInt()
    )
    private val SHOE_COLORS = listOf(
        0xFF222222.toInt(), 0xFF3D2A1A.toInt(), 0xFF606060.toInt()
    )
    private val BELT_COLORS = listOf(
        0xFF3D2A1A.toInt(), 0xFFFFCC33.toInt(), 0xFF222222.toInt()
    )
    private val ACCESSORY_COLORS = listOf(
        0xFFFFCC33.toInt(), 0xFFCCDDFF.toInt(), 0xFFFF6666.toInt(),
        0xFF99CC66.toInt(), 0xFFCC66CC.toInt()
    )
}
