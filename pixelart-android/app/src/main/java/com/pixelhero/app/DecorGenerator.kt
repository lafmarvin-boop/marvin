package com.pixelhero.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.Random

/**
 * Procedural decor generator. Produces a pixel art scenery of [width]x[height] based
 * on the selected [Decor] preset. Each call uses a new random seed so re-running
 * the same preset yields a new variation.
 */
object DecorGenerator {

    enum class Decor(val displayName: String) {
        SKY("Ciel + nuages"),
        GRASS("Herbe + fleurs"),
        FOREST("Forêt (sapins)"),
        MOUNTAINS("Montagnes + neige"),
        BRICK_WALL("Mur de briques"),
        WOOD_FLOOR("Plancher bois"),
        CAVE("Caverne + cristaux"),
        WATER("Eau / lac"),
        DESERT("Désert + cactus"),
        SNOW("Neige + sapins"),
        STARS("Ciel étoilé + lune"),
        DUNGEON("Sol pierre (donjon)");

        override fun toString() = displayName
    }

    fun generate(width: Int, height: Int, decor: Decor, seed: Long = System.currentTimeMillis()): IntArray {
        val p = IntArray(width * height)
        val r = Random(seed)
        when (decor) {
            Decor.SKY -> drawSky(p, width, height, r)
            Decor.GRASS -> drawGrass(p, width, height, r)
            Decor.FOREST -> drawForest(p, width, height, r)
            Decor.MOUNTAINS -> drawMountains(p, width, height, r)
            Decor.BRICK_WALL -> drawBrickWall(p, width, height, r)
            Decor.WOOD_FLOOR -> drawWoodFloor(p, width, height, r)
            Decor.CAVE -> drawCave(p, width, height, r)
            Decor.WATER -> drawWater(p, width, height, r)
            Decor.DESERT -> drawDesert(p, width, height, r)
            Decor.SNOW -> drawSnow(p, width, height, r)
            Decor.STARS -> drawStars(p, width, height, r)
            Decor.DUNGEON -> drawDungeonFloor(p, width, height, r)
        }
        return p
    }

    // ---- Helpers ----
    private fun setPx(p: IntArray, x: Int, y: Int, c: Int, w: Int, h: Int) {
        if (x in 0 until w && y in 0 until h) p[y * w + x] = c
    }

    private fun fillRect(p: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, c: Int, w: Int, h: Int) {
        val a = max(0, min(x0, x1)); val b = min(w - 1, max(x0, x1))
        val d = max(0, min(y0, y1)); val e = min(h - 1, max(y0, y1))
        for (y in d..e) for (x in a..b) p[y * w + x] = c
    }

    private fun fillCircle(p: IntArray, cx: Int, cy: Int, radius: Int, c: Int, w: Int, h: Int) {
        if (radius < 1) { setPx(p, cx, cy, c, w, h); return }
        val r2 = radius * radius
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (dx * dx + dy * dy <= r2) setPx(p, cx + dx, cy + dy, c, w, h)
        }
    }

    private fun lerpColor(a: Int, b: Int, tt: Float): Int {
        val t = tt.coerceIn(0f, 1f)
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt()
        val g = (ag + (bg - ag) * t).toInt()
        val bl = (ab + (bb - ab) * t).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
    }

    // ---- SKY + clouds ----
    private fun drawSky(p: IntArray, w: Int, h: Int, r: Random) {
        val top = 0xFF4A9BE5.toInt()
        val horizon = 0xFFCDE7FA.toInt()
        for (y in 0 until h) {
            val c = lerpColor(top, horizon, y.toFloat() / max(1, h - 1))
            for (x in 0 until w) p[y * w + x] = c
        }
        // Sun in upper-right
        if (r.nextInt(3) != 0) {
            val sr = (3 + w / 24).coerceAtLeast(2)
            val sx = (w * 3 / 4) + r.nextInt(max(1, w / 6))
            val sy = (h / 5) + r.nextInt(max(1, h / 8))
            fillCircle(p, sx, sy, sr, 0xFFFFEB80.toInt(), w, h)
            fillCircle(p, sx, sy, (sr * 2 / 3).coerceAtLeast(1), 0xFFFFFAE0.toInt(), w, h)
        }
        // Clouds
        val cloudCount = (2 + h / 32 + r.nextInt(3)).coerceAtLeast(1)
        for (i in 0 until cloudCount) {
            val cx = r.nextInt(w)
            val cy = r.nextInt(max(1, h * 3 / 5))
            val sz = (2 + r.nextInt(2 + w / 24)).coerceAtLeast(2)
            drawCloud(p, cx, cy, sz, w, h, r)
        }
    }

    private fun drawCloud(p: IntArray, cx: Int, cy: Int, sz: Int, w: Int, h: Int, r: Random) {
        val white = 0xFFFFFFFF.toInt()
        val shade = 0xFFCEDDEE.toInt()
        val parts = 3 + r.nextInt(3)
        for (i in 0 until parts) {
            val ox = cx + r.nextInt(sz * 2 + 1) - sz
            val oy = cy + r.nextInt(max(1, sz)) - sz / 4
            val rr = (sz / 2 + r.nextInt(sz / 2 + 1)).coerceAtLeast(1)
            fillCircle(p, ox, oy, rr, white, w, h)
        }
        // Bottom shading
        for (i in 0 until parts) {
            val ox = cx + r.nextInt(sz * 2 + 1) - sz
            val oy = cy + sz / 3 + r.nextInt(max(1, sz / 2))
            fillCircle(p, ox, oy, (sz / 3).coerceAtLeast(1), shade, w, h)
        }
    }

    // ---- GRASS field ----
    private fun drawGrass(p: IntArray, w: Int, h: Int, r: Random) {
        val base = 0xFF6FA84A.toInt()
        val light = 0xFF8CCB5C.toInt()
        val dark = 0xFF4F7E2E.toInt()
        fillRect(p, 0, 0, w - 1, h - 1, base, w, h)
        val noise = w * h / 5
        for (i in 0 until noise) {
            val x = r.nextInt(w); val y = r.nextInt(h)
            p[y * w + x] = if (r.nextBoolean()) light else dark
        }
        // Tufts (small 3-px patterns)
        for (i in 0 until w * h / 60) {
            val x = r.nextInt(w); val y = r.nextInt(h)
            setPx(p, x, y, dark, w, h); setPx(p, x + 1, y - 1, dark, w, h); setPx(p, x - 1, y - 1, dark, w, h)
        }
        // Flowers
        val flowers = intArrayOf(0xFFE53935.toInt(), 0xFFFFEB3B.toInt(), 0xFFFFFFFF.toInt(), 0xFFEC407A.toInt(), 0xFFBA68C8.toInt())
        val nFlowers = (w * h / 64).coerceAtLeast(1)
        for (i in 0 until nFlowers) {
            val x = r.nextInt(w); val y = r.nextInt(h)
            val color = flowers[r.nextInt(flowers.size)]
            setPx(p, x, y, color, w, h)
            // Small petal cross
            if (r.nextInt(3) == 0) {
                setPx(p, x + 1, y, color, w, h)
                setPx(p, x, y + 1, color, w, h)
            }
        }
    }

    // ---- FOREST (sky + ground + pine trees) ----
    private fun drawForest(p: IntArray, w: Int, h: Int, r: Random) {
        val groundY = (h * 2 / 3).coerceAtLeast(1)
        // Sky
        for (y in 0 until groundY) {
            val c = lerpColor(0xFF4A9BE5.toInt(), 0xFFCDE7FA.toInt(), y.toFloat() / max(1, groundY - 1))
            for (x in 0 until w) p[y * w + x] = c
        }
        // Ground
        for (y in groundY until h) {
            val c = lerpColor(0xFF3C5A14.toInt(), 0xFF2A4010.toInt(), (y - groundY).toFloat() / max(1, h - groundY))
            for (x in 0 until w) p[y * w + x] = c
        }
        // Trees
        val treeColors = intArrayOf(0xFF234F1E.toInt(), 0xFF1C3A12.toInt(), 0xFF2E5A20.toInt())
        val nTrees = max(2, w / 6 + r.nextInt(w / 8 + 1))
        repeat(nTrees) {
            val tx = r.nextInt(w)
            val ty = groundY + r.nextInt(max(1, h - groundY))
            val th = (h / 6 + r.nextInt(h / 3 + 1)).coerceAtLeast(3)
            drawPineTree(p, tx, ty, th, treeColors[r.nextInt(treeColors.size)], w, h)
        }
        // Grass tufts
        for (i in 0 until w / 2) {
            val gx = r.nextInt(w); val gy = groundY - r.nextInt(2)
            setPx(p, gx, gy, 0xFF52803D.toInt(), w, h)
        }
    }

    private fun drawPineTree(p: IntArray, x: Int, baseY: Int, height: Int, foliage: Int, w: Int, h: Int) {
        val trunk = 0xFF4A2C18.toInt()
        val trunkH = max(1, height / 4)
        for (dy in 0 until trunkH) setPx(p, x, baseY - dy, trunk, w, h)
        val foliageH = max(1, height - trunkH)
        for (dy in 0 until foliageH) {
            val radius = (foliageH - dy) / 2 + 1
            for (dx in -radius..radius) setPx(p, x + dx, baseY - trunkH - dy, foliage, w, h)
        }
        setPx(p, x, baseY - height, foliage, w, h)
    }

    // ---- MOUNTAINS + snow caps ----
    private fun drawMountains(p: IntArray, w: Int, h: Int, r: Random) {
        // Sky
        val skyEnd = h * 3 / 4
        for (y in 0 until skyEnd) {
            val c = lerpColor(0xFF4B6584.toInt(), 0xFFA5C2D8.toInt(), y.toFloat() / max(1, skyEnd - 1))
            for (x in 0 until w) p[y * w + x] = c
        }
        // Far mountains (lighter blue-grey)
        drawMountainLayer(p, w, h, h / 3, skyEnd, 0xFF8DA1B5.toInt(), 0xFFE8EEF5.toInt(), max(2, w / 16), 3, r)
        // Near mountains (darker)
        drawMountainLayer(p, w, h, h / 2, skyEnd, 0xFF4A5564.toInt(), 0xFFFFFFFF.toInt(), max(3, w / 12), 5, r)
        // Foreground grass/ground
        fillRect(p, 0, skyEnd, w - 1, h - 1, 0xFF2F3E2E.toInt(), w, h)
    }

    private fun drawMountainLayer(p: IntArray, w: Int, h: Int, peakMinY: Int, baseY: Int,
                                   color: Int, snow: Int, slopeWidth: Int, count: Int, r: Random) {
        data class Peak(val x: Int, val y: Int, val slope: Float)
        val peaks = (0 until count).map {
            val px = r.nextInt(w + slopeWidth * 2) - slopeWidth
            val py = peakMinY + r.nextInt(max(1, baseY - peakMinY) * 3 / 4)
            val sl = 0.6f + r.nextFloat() * 0.7f
            Peak(px, py, sl)
        }
        for (x in 0 until w) {
            var topY = baseY
            for (peak in peaks) {
                val dx = abs(x - peak.x)
                val mY = peak.y + (dx / peak.slope).toInt()
                if (mY < topY) topY = mY
            }
            for (y in max(0, topY)..min(h - 1, baseY)) {
                p[y * w + x] = if (y < topY + max(1, h / 32)) snow else color
            }
        }
    }

    // ---- BRICK WALL (tileable) ----
    private fun drawBrickWall(p: IntArray, w: Int, h: Int, r: Random) {
        val mortar = 0xFF3D2A1A.toInt()
        val bricks = intArrayOf(0xFF8B3A3A.toInt(), 0xFFA04545.toInt(), 0xFF6B2424.toInt(), 0xFF7A3030.toInt(), 0xFF8F4444.toInt())
        fillRect(p, 0, 0, w - 1, h - 1, mortar, w, h)
        val bW = max(4, w / 8)
        val bH = max(2, bW / 2)
        var row = 0
        var y = 0
        while (y < h) {
            val offset = if (row % 2 == 0) 0 else bW / 2
            var col = -1
            while (col * bW + offset < w) {
                val x0 = col * bW + offset
                val color = bricks[r.nextInt(bricks.size)]
                fillRect(p, x0 + 1, y + 1, x0 + bW - 1, y + bH - 1, color, w, h)
                // Highlight on top edge
                fillRect(p, x0 + 1, y + 1, x0 + bW - 1, y + 1, lerpColor(color, 0xFFFFFFFF.toInt(), 0.2f), w, h)
                col++
            }
            row++
            y += bH
        }
    }

    // ---- WOOD FLOOR (horizontal planks) ----
    private fun drawWoodFloor(p: IntArray, w: Int, h: Int, r: Random) {
        val planks = intArrayOf(0xFF8B5A2B.toInt(), 0xFF6F4520.toInt(), 0xFFA0731D.toInt(), 0xFF7B5A28.toInt(), 0xFF9B6B2C.toInt())
        val plankH = max(3, h / 6 + r.nextInt(3))
        val grain = 0xFF5C3A18.toInt()
        val seam = 0xFF2E1A0B.toInt()
        var idx = 0
        var y = 0
        while (y < h) {
            val base = planks[idx % planks.size]
            val plankEnd = min(h - 1, y + plankH - 1)
            // Fill plank
            for (yy in y..plankEnd) for (xx in 0 until w) {
                val noise = if (r.nextInt(12) == 0) -8 else 0
                p[yy * w + xx] = shade(base, noise)
            }
            // Grain lines
            val grainCount = max(1, w / 8)
            for (i in 0 until grainCount) {
                val gx = r.nextInt(w)
                val gy = y + r.nextInt(plankH)
                val len = 2 + r.nextInt(max(2, w / 8))
                for (k in 0 until len) setPx(p, gx + k, gy, grain, w, h)
            }
            // Seam between planks
            if (plankEnd < h - 1) for (xx in 0 until w) p[(plankEnd + 1) * w + xx] = seam
            y += plankH + 1
            idx++
        }
    }

    private fun shade(c: Int, delta: Int): Int {
        val r0 = (((c shr 16) and 0xFF) + delta).coerceIn(0, 255)
        val g0 = (((c shr 8) and 0xFF) + delta).coerceIn(0, 255)
        val b0 = ((c and 0xFF) + delta).coerceIn(0, 255)
        return 0xFF000000.toInt() or (r0 shl 16) or (g0 shl 8) or b0
    }

    // ---- CAVE with stalactites + crystals ----
    private fun drawCave(p: IntArray, w: Int, h: Int, r: Random) {
        val bg = 0xFF1A1A2A.toInt()
        val rock = 0xFF3A3A4A.toInt()
        val rockL = 0xFF4F4F5F.toInt()
        fillRect(p, 0, 0, w - 1, h - 1, bg, w, h)
        // Stalactites (top down)
        val nTop = (w / 6 + r.nextInt(w / 4 + 1)).coerceAtLeast(2)
        repeat(nTop) {
            val sx = r.nextInt(w)
            val sh = (1 + r.nextInt(max(2, h / 4))).coerceAtLeast(1)
            for (dy in 0 until sh) {
                val tw = max(0, (sh - dy) / 3)
                for (dx in -tw..tw) {
                    setPx(p, sx + dx, dy, if (dy < sh / 3) rockL else rock, w, h)
                }
            }
        }
        // Stalagmites (bottom up)
        val nBot = (nTop / 2).coerceAtLeast(1)
        repeat(nBot) {
            val sx = r.nextInt(w)
            val sh = (1 + r.nextInt(max(2, h / 6))).coerceAtLeast(1)
            for (dy in 0 until sh) {
                val tw = max(0, dy / 3)
                for (dx in -tw..tw) {
                    setPx(p, sx + dx, h - 1 - dy, if (dy > sh * 2 / 3) rockL else rock, w, h)
                }
            }
        }
        // Crystals (small bright colors)
        val crystals = intArrayOf(0xFF00E5FF.toInt(), 0xFFFF66CC.toInt(), 0xFFFFEB3B.toInt(), 0xFF66FF99.toInt(), 0xFFB44CFF.toInt())
        val nC = (3 + r.nextInt(5)).coerceAtMost(w * h / 8)
        repeat(nC) {
            val cx = r.nextInt(w)
            val cy = (h / 2) + r.nextInt(max(1, h / 2))
            val col = crystals[r.nextInt(crystals.size)]
            setPx(p, cx, cy, col, w, h)
            setPx(p, cx, cy - 1, lerpColor(col, 0xFFFFFFFF.toInt(), 0.4f), w, h)
            setPx(p, cx + 1, cy, lerpColor(col, 0xFF000000.toInt(), 0.2f), w, h)
        }
    }

    // ---- WATER with wave highlights ----
    private fun drawWater(p: IntArray, w: Int, h: Int, r: Random) {
        for (y in 0 until h) {
            val c = lerpColor(0xFF2A7BD8.toInt(), 0xFF0B2F5C.toInt(), y.toFloat() / max(1, h - 1))
            for (x in 0 until w) p[y * w + x] = c
        }
        val wave = 0xFFD2E8FF.toInt()
        val nWaves = (h / 3).coerceAtLeast(2)
        repeat(nWaves) {
            val wy = r.nextInt(h)
            val wx = r.nextInt(w)
            val wlen = 2 + r.nextInt(max(2, w / 8))
            for (dx in 0 until wlen) setPx(p, (wx + dx) % w, wy, wave, w, h)
        }
        // Sparkles
        repeat(w * h / 80 + 2) {
            setPx(p, r.nextInt(w), r.nextInt(h), 0xFFFFFFFF.toInt(), w, h)
        }
    }

    // ---- DESERT with cactus + sun ----
    private fun drawDesert(p: IntArray, w: Int, h: Int, r: Random) {
        val horizon = h * 2 / 5
        for (y in 0 until horizon) {
            val c = lerpColor(0xFFFF9248.toInt(), 0xFFFFE5B4.toInt(), y.toFloat() / max(1, horizon - 1))
            for (x in 0 until w) p[y * w + x] = c
        }
        for (y in horizon until h) {
            val c = lerpColor(0xFFEDD9A3.toInt(), 0xFFB8945C.toInt(), (y - horizon).toFloat() / max(1, h - horizon))
            for (x in 0 until w) p[y * w + x] = c
        }
        // Sun
        val sr = (3 + w / 12).coerceAtLeast(2)
        val sx = w * 2 / 3 + r.nextInt(max(1, w / 6))
        val sy = horizon - sr - 1
        fillCircle(p, sx, sy, sr, 0xFFFFC95C.toInt(), w, h)
        // Cacti
        val nCacti = (1 + r.nextInt(max(2, w / 16))).coerceAtMost(8)
        repeat(nCacti) {
            val cx = r.nextInt(w)
            val cy = horizon + r.nextInt(max(1, h / 8))
            val ch = (h / 8 + r.nextInt(h / 6 + 1)).coerceAtLeast(3)
            drawCactus(p, cx, cy, ch, w, h)
        }
        // Sand speckles
        for (i in 0 until w * h / 30) {
            val x = r.nextInt(w); val y = horizon + r.nextInt(max(1, h - horizon))
            p[y * w + x] = shade(p[y * w + x], if (r.nextBoolean()) -8 else 8)
        }
    }

    private fun drawCactus(p: IntArray, x: Int, baseY: Int, height: Int, w: Int, h: Int) {
        val body = 0xFF4F7B2A.toInt()
        for (dy in 0 until height) {
            setPx(p, x, baseY - dy, body, w, h)
            setPx(p, x - 1, baseY - dy, body, w, h)
        }
        if (height > 5) {
            val armY = baseY - height / 2
            setPx(p, x + 1, armY, body, w, h); setPx(p, x + 2, armY, body, w, h)
            setPx(p, x + 2, armY - 1, body, w, h); setPx(p, x + 2, armY - 2, body, w, h)
            setPx(p, x - 2, armY + 1, body, w, h); setPx(p, x - 3, armY + 1, body, w, h)
            setPx(p, x - 3, armY, body, w, h); setPx(p, x - 3, armY - 1, body, w, h)
        }
    }

    // ---- SNOW with pines + flakes ----
    private fun drawSnow(p: IntArray, w: Int, h: Int, r: Random) {
        val groundY = (h * 3 / 5).coerceAtLeast(1)
        for (y in 0 until groundY) {
            val c = lerpColor(0xFF7E9FBF.toInt(), 0xFFCFE0EE.toInt(), y.toFloat() / max(1, groundY - 1))
            for (x in 0 until w) p[y * w + x] = c
        }
        for (y in groundY until h) {
            val c = lerpColor(0xFFF5F8FA.toInt(), 0xFFD5DEE8.toInt(), (y - groundY).toFloat() / max(1, h - groundY))
            for (x in 0 until w) p[y * w + x] = c
        }
        // Pine trees with snow caps
        val nTrees = (2 + r.nextInt(max(2, w / 10))).coerceAtMost(8)
        repeat(nTrees) {
            val tx = r.nextInt(w)
            val ty = groundY + r.nextInt(max(1, h / 10))
            val th = (h / 5 + r.nextInt(h / 4 + 1)).coerceAtLeast(4)
            drawPineTree(p, tx, ty, th, 0xFF1B5E20.toInt(), w, h)
            // Snow on top
            setPx(p, tx, ty - th, 0xFFFFFFFF.toInt(), w, h)
        }
        // Falling snowflakes
        val nFlakes = (w * h / 16).coerceAtLeast(8)
        repeat(nFlakes) {
            setPx(p, r.nextInt(w), r.nextInt(groundY), 0xFFFFFFFF.toInt(), w, h)
        }
    }

    // ---- STARRY SKY with moon ----
    private fun drawStars(p: IntArray, w: Int, h: Int, r: Random) {
        // Deep blue gradient
        for (y in 0 until h) {
            val c = lerpColor(0xFF050524.toInt(), 0xFF1B1F4D.toInt(), y.toFloat() / max(1, h - 1))
            for (x in 0 until w) p[y * w + x] = c
        }
        // Stars
        val colors = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFE5B4.toInt(), 0xFFB4D8FF.toInt(), 0xFFFFB4D8.toInt())
        val nStars = (w * h / 15).coerceAtLeast(20)
        repeat(nStars) {
            val x = r.nextInt(w); val y = r.nextInt(h)
            val col = colors[r.nextInt(colors.size)]
            setPx(p, x, y, col, w, h)
            // Some stars get a "+" twinkle
            if (r.nextInt(20) == 0 && w > 24) {
                setPx(p, x + 1, y, col, w, h); setPx(p, x - 1, y, col, w, h)
                setPx(p, x, y + 1, col, w, h); setPx(p, x, y - 1, col, w, h)
            }
        }
        // Moon (50% chance)
        if (r.nextBoolean()) {
            val mr = (3 + r.nextInt(max(2, w / 12))).coerceAtLeast(2)
            val mx = r.nextInt(max(1, w - mr * 2)) + mr
            val my = r.nextInt(max(1, h * 2 / 5)) + mr
            fillCircle(p, mx, my, mr, 0xFFFFFAE5.toInt(), w, h)
            // Crescent shadow
            if (r.nextBoolean()) {
                fillCircle(p, mx + mr / 2, my - mr / 4, mr * 3 / 4, lerpColor(0xFF050524.toInt(), 0xFF1B1F4D.toInt(), my.toFloat() / h), w, h)
            } else {
                fillCircle(p, mx + mr / 3, my - mr / 4, (mr / 2).coerceAtLeast(1), 0xFFD9CFB8.toInt(), w, h)
                fillCircle(p, mx - mr / 3, my + mr / 4, (mr / 3).coerceAtLeast(1), 0xFFD9CFB8.toInt(), w, h)
            }
        }
        // Shooting star (rare)
        if (r.nextInt(3) == 0 && w >= 16) {
            val sx = r.nextInt(w - w / 4); val sy = r.nextInt(h / 2)
            val len = w / 5
            for (k in 0 until len) {
                setPx(p, sx + k, sy + k / 2, lerpColor(0xFFFFFFFF.toInt(), 0xFF1B1F4D.toInt(), k.toFloat() / len), w, h)
            }
        }
    }

    // ---- DUNGEON stone floor (tileable) ----
    private fun drawDungeonFloor(p: IntArray, w: Int, h: Int, r: Random) {
        val mortar = 0xFF2A2A2A.toInt()
        val tiles = intArrayOf(0xFF555555.toInt(), 0xFF666666.toInt(), 0xFF4A4A4A.toInt(), 0xFF707070.toInt(), 0xFF5A5A5A.toInt())
        fillRect(p, 0, 0, w - 1, h - 1, mortar, w, h)
        val ts = max(4, w / 8)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val color = tiles[r.nextInt(tiles.size)]
                fillRect(p, x + 1, y + 1, x + ts - 1, y + ts - 1, color, w, h)
                // Highlight + shadow edges for 3D effect
                fillRect(p, x + 1, y + 1, x + ts - 1, y + 1, lerpColor(color, 0xFFFFFFFF.toInt(), 0.18f), w, h)
                fillRect(p, x + 1, y + ts - 1, x + ts - 1, y + ts - 1, lerpColor(color, 0xFF000000.toInt(), 0.25f), w, h)
                // Random crack
                if (r.nextInt(10) == 0 && ts >= 6) {
                    val cx = x + 2 + r.nextInt(max(1, ts - 4))
                    val cy = y + 2 + r.nextInt(max(1, ts - 4))
                    val crack = 0xFF2A2A2A.toInt()
                    setPx(p, cx, cy, crack, w, h)
                    setPx(p, cx + 1, cy, crack, w, h)
                    setPx(p, cx + 1, cy + 1, crack, w, h)
                }
                // Random moss patch
                if (r.nextInt(20) == 0 && ts >= 6) {
                    val mx = x + 2 + r.nextInt(max(1, ts - 4))
                    val my = y + 2 + r.nextInt(max(1, ts - 4))
                    setPx(p, mx, my, 0xFF4F8030.toInt(), w, h)
                    setPx(p, mx + 1, my, 0xFF4F8030.toInt(), w, h)
                }
                x += ts
            }
            y += ts
        }
    }
}
