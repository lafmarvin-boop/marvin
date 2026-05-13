package com.pixelhero.app

import java.util.Random

/**
 * Text-prompt pixel-art generator. Parses keywords from a free-form description
 * and composes a sprite from the project's existing assets:
 *   - Pose templates (warrior, mage, dragon, slime, etc.)
 *   - Procedural character coloring
 *   - View transformations (front/side/back/3-quarter)
 *   - Accessory overlays (sword, shield, hat, etc.)
 *   - Palette biasing (colors mentioned in the prompt)
 *
 * NOT a real ML model: this is keyword interpretation + procedural composition.
 * For real AI generation (Stable Diffusion etc.) you'd need a cloud API or a
 * heavy on-device ML runtime — out of scope for this offline mobile app.
 */
object AIGenerator {

    data class Analysis(
        val pose: PoseTemplates.Pose,
        val colors: List<Int>,         // empty = use defaults
        val accessories: List<Accessory>,
        val direction: ViewTransform.View?,
        val style: SmartPixelize.Style?,
        val mood: Mood,
        val originalPrompt: String
    )

    enum class Accessory { SWORD, AXE, BOW, STAFF, SHIELD, HAT, HELMET, CAPE, WAND, CROWN }
    enum class Mood { NEUTRAL, EVIL, HOLY, MAGICAL, FIERY, ICY, GHOSTLY }

    /** Parse a free-form prompt into an Analysis. */
    fun parse(prompt: String): Analysis {
        val lower = prompt.lowercase()
            .replace("é","e").replace("è","e").replace("ê","e")
            .replace("à","a").replace("ô","o").replace("û","u").replace("î","i")

        val pose = detectPose(lower)
        val colors = COLOR_KEYWORDS.filter { lower.contains(it.first) }.map { it.second }
        val accessories = ACCESSORY_KEYWORDS.mapNotNull { (kw, acc) ->
            if (kw.any { lower.contains(it) }) acc else null
        }
        val direction = detectDirection(lower)
        val style = detectStyle(lower)
        val mood = detectMood(lower)
        return Analysis(pose, colors, accessories, direction, style, mood, prompt)
    }

    private fun detectPose(lower: String): PoseTemplates.Pose {
        return when {
            anyOf(lower, "knight", "warrior", "guerrier", "chevalier", "soldier", "soldat", "fighter") -> PoseTemplates.Pose.WARRIOR
            anyOf(lower, "mage", "wizard", "sorcier", "sorciere", "magicien", "witch", "necromancer") -> PoseTemplates.Pose.MAGE
            anyOf(lower, "archer", "ranger", "rodeur", "hunter", "chasseur", "bowman") -> PoseTemplates.Pose.ARCHER
            anyOf(lower, "dragon", "drake", "wyvern") -> PoseTemplates.Pose.DRAGON_SIDE
            anyOf(lower, "slime", "blob", "ooze") -> PoseTemplates.Pose.SLIME
            anyOf(lower, "skeleton", "squelette", "skull", "undead", "mort-vivant", "zombie") -> PoseTemplates.Pose.SKELETON
            anyOf(lower, "bird", "oiseau", "eagle", "aigle", "hawk", "owl") -> PoseTemplates.Pose.BIRD
            anyOf(lower, "fish", "poisson", "shark", "requin") -> PoseTemplates.Pose.FISH
            anyOf(lower, "dog", "chien", "cat", "chat", "wolf", "loup", "fox", "renard", "horse", "cheval", "deer", "cerf") -> PoseTemplates.Pose.QUADRUPED
            anyOf(lower, "from behind", "from back", "de dos", "back view", "dos") -> PoseTemplates.Pose.HUMANOID_BACK
            anyOf(lower, "profile", "profil", "side view", "side") -> PoseTemplates.Pose.HUMANOID_SIDE
            else -> PoseTemplates.Pose.HUMANOID_FRONT
        }
    }

    private fun detectDirection(lower: String): ViewTransform.View? {
        return when {
            anyOf(lower, "facing back", "from behind", "de dos", "back view") -> ViewTransform.View.BACK
            anyOf(lower, "facing left", "vers la gauche") -> ViewTransform.View.SIDE_LEFT
            anyOf(lower, "facing right", "vers la droite") -> ViewTransform.View.SIDE_RIGHT
            anyOf(lower, "3/4", "three quarter", "trois quart") -> ViewTransform.View.THREE_QUARTER_RIGHT
            else -> null
        }
    }

    private fun detectStyle(lower: String): SmartPixelize.Style? {
        return when {
            "gameboy" in lower || "game boy" in lower || "dmg" in lower -> SmartPixelize.Style.GAMEBOY
            "nes" in lower || "famicom" in lower -> SmartPixelize.Style.NES_STYLE
            "retro" in lower || "8-bit" in lower || "8 bit" in lower -> SmartPixelize.Style.RETRO
            "pastel" in lower || "soft" in lower || "doux" in lower -> SmartPixelize.Style.PASTEL
            "cartoon" in lower || "anime" in lower -> SmartPixelize.Style.CARTOON
            "vibrant" in lower -> SmartPixelize.Style.VIBRANT
            else -> null
        }
    }

    private fun detectMood(lower: String): Mood {
        return when {
            anyOf(lower, "evil", "dark", "demon", "demonic", "diabolique", "malefique", "sombre", "shadow", "ombre") -> Mood.EVIL
            anyOf(lower, "holy", "saint", "divine", "divin", "angel", "ange", "paladin") -> Mood.HOLY
            anyOf(lower, "magic", "magique", "magical", "mystical", "mystique", "arcane") -> Mood.MAGICAL
            anyOf(lower, "fire", "feu", "flame", "flamme", "burning", "ardent") -> Mood.FIERY
            anyOf(lower, "ice", "glace", "frozen", "givre", "frost", "cold", "froid") -> Mood.ICY
            anyOf(lower, "ghost", "fantome", "spirit", "esprit", "spectral", "wraith") -> Mood.GHOSTLY
            else -> Mood.NEUTRAL
        }
    }

    private fun anyOf(s: String, vararg keywords: String): Boolean =
        keywords.any { s.contains(it) }

    /** Generate a sprite from a prompt. */
    fun generate(prompt: String, w: Int, h: Int, seed: Long = System.currentTimeMillis()): Pair<IntArray, Analysis> {
        val a = parse(prompt)
        val r = Random(seed)
        // 1. Base sprite from procedural character on the chosen pose
        val pixels = ProceduralCharacter.generate(a.pose, w, h, seed)

        // 2. Mood-based palette biasing
        applyMood(pixels, a.mood, r)

        // 3. Color biasing from explicit color keywords
        if (a.colors.isNotEmpty()) {
            biasMainColors(pixels, a.colors, r)
        }

        // 4. View direction transformation
        if (a.direction != null) {
            val fromView = ViewTransform.View.FRONT
            val tmpFrame = Frame(w, h, pixels)
            val transformed = ViewTransform.apply(tmpFrame,
                ViewTransform.Transform(fromView, a.direction))
            transformed.pixels.copyInto(pixels)
        }

        // 5. Accessory overlays
        for (acc in a.accessories) {
            drawAccessory(pixels, w, h, acc, r)
        }

        return pixels to a
    }

    /** Apply mood-based color shift (darken, lighten, tint). */
    private fun applyMood(pixels: IntArray, mood: Mood, r: Random) {
        when (mood) {
            Mood.NEUTRAL -> {}
            Mood.EVIL -> tintAll(pixels, -25, -15, +20)
            Mood.HOLY -> tintAll(pixels, +20, +20, +5)
            Mood.MAGICAL -> tintAll(pixels, +15, -10, +30)
            Mood.FIERY -> tintAll(pixels, +30, +10, -25)
            Mood.ICY -> tintAll(pixels, -10, +10, +30)
            Mood.GHOSTLY -> {
                // Half-transparent + desaturated
                for (i in pixels.indices) {
                    val c = pixels[i]
                    if ((c ushr 24) and 0xFF < 128) continue
                    val r0 = (c shr 16) and 0xFF
                    val g0 = (c shr 8) and 0xFF
                    val b0 = c and 0xFF
                    val gray = (r0 + g0 + b0) / 3
                    val nr = (r0 + gray) / 2
                    val ng = (g0 + gray) / 2
                    val nb = (b0 + gray + 30).coerceAtMost(255) / 2
                    pixels[i] = (0xAA000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
                }
            }
        }
    }

    private fun tintAll(pixels: IntArray, dr: Int, dg: Int, db: Int) {
        for (i in pixels.indices) {
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) continue
            val r = (((c shr 16) and 0xFF) + dr).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + dg).coerceIn(0, 255)
            val b = ((c and 0xFF) + db).coerceIn(0, 255)
            pixels[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Find dominant non-skin colors and replace them with prompt colors. */
    private fun biasMainColors(pixels: IntArray, biasColors: List<Int>, r: Random) {
        val histogram = HashMap<Int, Int>()
        for (c in pixels) {
            if ((c ushr 24) and 0xFF < 128) continue
            if (isSkinLike(c)) continue
            histogram[c] = (histogram[c] ?: 0) + 1
        }
        // Take top N non-skin colors and replace with biasColors
        val topColors = histogram.entries.sortedByDescending { it.value }
            .take(biasColors.size).map { it.key }
        if (topColors.isEmpty()) return
        val map = topColors.zip(biasColors).toMap()
        for (i in pixels.indices) {
            map[pixels[i]]?.let { pixels[i] = it }
        }
    }

    private fun isSkinLike(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return r > 90 && r < 250 && r > g && g > b && (r - g) in 8..80
    }

    /** Draw a small accessory overlay (sword, hat, etc.). */
    private fun drawAccessory(pixels: IntArray, w: Int, h: Int, acc: Accessory, r: Random) {
        // Find character bounding box
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((pixels[y * w + x] ushr 24) and 0xFF >= 128) {
                if (x < minX) minX = x; if (y < minY) minY = y
                if (x > maxX) maxX = x; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return
        val bbW = maxX - minX + 1; val bbH = maxY - minY + 1
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2

        when (acc) {
            Accessory.SWORD -> {
                // Vertical sword to the right of body
                val sx = maxX + 1
                val blade = 0xFFE0E5F0.toInt(); val grip = 0xFF8B5A2B.toInt(); val pommel = 0xFFFFC347.toInt()
                for (k in 0 until bbH / 2) setPx(pixels, w, h, sx, minY + bbH / 3 - k, blade)
                setPx(pixels, w, h, sx, minY + bbH / 3 + 1, grip)
                setPx(pixels, w, h, sx, minY + bbH / 3 + 2, grip)
                setPx(pixels, w, h, sx - 1, minY + bbH / 3, blade)
                setPx(pixels, w, h, sx + 1, minY + bbH / 3, blade)
                setPx(pixels, w, h, sx, minY + bbH / 3 + 3, pommel)
            }
            Accessory.AXE -> {
                val sx = maxX + 1
                val head = 0xFFAAAAAA.toInt(); val handle = 0xFF8B5A2B.toInt()
                for (k in 0 until bbH / 2) setPx(pixels, w, h, sx, minY + bbH / 4 + k, handle)
                setPx(pixels, w, h, sx + 1, minY + bbH / 4, head)
                setPx(pixels, w, h, sx + 2, minY + bbH / 4, head)
                setPx(pixels, w, h, sx + 2, minY + bbH / 4 - 1, head)
                setPx(pixels, w, h, sx + 2, minY + bbH / 4 + 1, head)
            }
            Accessory.BOW -> {
                val sx = minX - 2
                val wood = 0xFF8B5A2B.toInt(); val string = 0xFFFFFFFF.toInt()
                for (k in 0 until bbH / 3) setPx(pixels, w, h, sx, cy - bbH / 6 + k, wood)
                setPx(pixels, w, h, sx + 1, cy - bbH / 6 - 1, wood)
                setPx(pixels, w, h, sx + 1, cy - bbH / 6 + bbH / 3, wood)
                for (k in 0 until bbH / 3) setPx(pixels, w, h, sx + 2, cy - bbH / 6 + k, string)
            }
            Accessory.STAFF, Accessory.WAND -> {
                val sx = maxX + 1
                val handle = 0xFF8B5A2B.toInt(); val orb = 0xFFCC66CC.toInt()
                for (k in 0 until bbH / 2) setPx(pixels, w, h, sx, minY + bbH / 4 + k, handle)
                // Glowing orb on top
                setPx(pixels, w, h, sx, minY + bbH / 4 - 1, orb)
                setPx(pixels, w, h, sx - 1, minY + bbH / 4 - 1, orb)
                setPx(pixels, w, h, sx + 1, minY + bbH / 4 - 1, orb)
                setPx(pixels, w, h, sx, minY + bbH / 4 - 2, 0xFFFFE5FF.toInt())
            }
            Accessory.SHIELD -> {
                val sx = minX - 2
                val rim = 0xFF606060.toInt(); val face = 0xFF8844CC.toInt()
                for (dy in 0 until bbH / 3) {
                    for (dx in 0..1) {
                        setPx(pixels, w, h, sx + dx, cy - bbH / 6 + dy, if (dx == 0) rim else face)
                    }
                }
                // Highlight
                setPx(pixels, w, h, sx + 1, cy - bbH / 8, 0xFFFFFFFF.toInt())
            }
            Accessory.HAT -> {
                val brim = 0xFF8B5A2B.toInt(); val crown = 0xFF333333.toInt()
                for (dx in -2..2) setPx(pixels, w, h, cx + dx, minY - 1, brim)
                for (dy in 1..2) for (dx in -1..1) setPx(pixels, w, h, cx + dx, minY - dy - 1, crown)
            }
            Accessory.HELMET -> {
                val metal = 0xFFAAAAAA.toInt(); val rim = 0xFF606060.toInt()
                for (dx in -bbW / 4..bbW / 4) {
                    setPx(pixels, w, h, cx + dx, minY, metal)
                    setPx(pixels, w, h, cx + dx, minY - 1, metal)
                }
                for (dx in -bbW / 4..bbW / 4) setPx(pixels, w, h, cx + dx, minY + 2, rim)
            }
            Accessory.CAPE -> {
                val cape = 0xFFAA2222.toInt()
                for (dy in 0 until bbH / 2) {
                    val width = bbW / 3 + dy / 2
                    for (dx in -width..width) setPx(pixels, w, h, cx + dx, cy + dy, cape)
                }
            }
            Accessory.CROWN -> {
                val gold = 0xFFFFCC33.toInt(); val gem = 0xFFD64545.toInt()
                for (dx in -bbW / 4..bbW / 4) setPx(pixels, w, h, cx + dx, minY - 1, gold)
                setPx(pixels, w, h, cx - 2, minY - 2, gold)
                setPx(pixels, w, h, cx, minY - 3, gold)
                setPx(pixels, w, h, cx + 2, minY - 2, gold)
                setPx(pixels, w, h, cx, minY - 2, gem)
            }
        }
    }

    private fun setPx(pixels: IntArray, w: Int, h: Int, x: Int, y: Int, c: Int) {
        if (x in 0 until w && y in 0 until h) pixels[y * w + x] = c
    }

    // ========================================================================
    // Keyword tables
    // ========================================================================
    private val COLOR_KEYWORDS = listOf(
        "red" to 0xFFD64545.toInt(), "rouge" to 0xFFD64545.toInt(),
        "blue" to 0xFF3366FF.toInt(), "bleu" to 0xFF3366FF.toInt(),
        "green" to 0xFF22AA66.toInt(), "vert" to 0xFF22AA66.toInt(),
        "yellow" to 0xFFFFCC33.toInt(), "jaune" to 0xFFFFCC33.toInt(),
        "purple" to 0xFF8844CC.toInt(), "violet" to 0xFF8844CC.toInt(),
        "pink" to 0xFFFF66CC.toInt(), "rose" to 0xFFFF66CC.toInt(),
        "orange" to 0xFFFFAA33.toInt(),
        "white" to 0xFFFFFFFF.toInt(), "blanc" to 0xFFFFFFFF.toInt(),
        "black" to 0xFF222222.toInt(), "noir" to 0xFF222222.toInt(),
        "brown" to 0xFF8B5A2B.toInt(), "marron" to 0xFF8B5A2B.toInt(),
        "gold" to 0xFFFFCC33.toInt(), " or " to 0xFFFFCC33.toInt(),
        "silver" to 0xFFC0C0C0.toInt(), "argent" to 0xFFC0C0C0.toInt(),
        "cyan" to 0xFF00CCFF.toInt(), "turquoise" to 0xFF00CCFF.toInt()
    )

    private val ACCESSORY_KEYWORDS: List<Pair<List<String>, Accessory>> = listOf(
        listOf("sword", "epee", "épée", "blade") to Accessory.SWORD,
        listOf("axe", "hache") to Accessory.AXE,
        listOf("bow", "arc") to Accessory.BOW,
        listOf("staff", "baton", "bâton") to Accessory.STAFF,
        listOf("wand", "baguette") to Accessory.WAND,
        listOf("shield", "bouclier") to Accessory.SHIELD,
        listOf("hat", "chapeau") to Accessory.HAT,
        listOf("helmet", "casque", "helm") to Accessory.HELMET,
        listOf("cape", "cloak") to Accessory.CAPE,
        listOf("crown", "couronne") to Accessory.CROWN
    )
}
