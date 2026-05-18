package com.pixelhero.app

import android.graphics.Bitmap
import androidx.appcompat.app.AlertDialog

/**
 * Procedural decor generator UI: pick a scene preset and either replace the
 * current frame, set as background, or generate a 4/8-frame animated loop.
 */

internal fun MainActivity.showDecorGenerator() {
    val items = arrayOf(
        "Décor statique → frame courante",
        "Décor statique → image de fond",
        "🎬 Décor animé → 4 nouvelles frames",
        "🎬 Décor animé → 8 nouvelles frames"
    )
    AlertDialog.Builder(this)
        .setTitle("Générer un décor")
        .setItems(items) { _, which ->
            when (which) {
                0 -> pickAndGenerateStaticDecor(replaceFrame = true)
                1 -> pickAndGenerateStaticDecor(replaceFrame = false)
                2 -> pickAndGenerateAnimatedDecor(frameCount = 4)
                3 -> pickAndGenerateAnimatedDecor(frameCount = 8)
            }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun MainActivity.pickAndGenerateStaticDecor(replaceFrame: Boolean) {
    val decors = DecorGenerator.Decor.values()
    val labels = decors.map { it.displayName }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle(if (replaceFrame) "Décor → frame courante" else "Décor → image de fond")
        .setItems(labels) { _, which -> generateDecor(decors[which], replaceFrame) }
        .show()
}

internal fun MainActivity.pickAndGenerateAnimatedDecor(frameCount: Int) {
    val decors = DecorGenerator.Decor.values()
    val labels = decors.map {
        val animated = it in listOf(
            DecorGenerator.Decor.SKY, DecorGenerator.Decor.WATER, DecorGenerator.Decor.SNOW,
            DecorGenerator.Decor.STARS, DecorGenerator.Decor.FOREST, DecorGenerator.Decor.CAVE,
            DecorGenerator.Decor.GRASS, DecorGenerator.Decor.DESERT
        )
        it.displayName + if (animated) " 🎬" else " (statique)"
    }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("Décor animé $frameCount frames")
        .setItems(labels) { _, which -> generateAnimatedDecor(decors[which], frameCount) }
        .show()
}

internal fun MainActivity.generateAnimatedDecor(decor: DecorGenerator.Decor, frameCount: Int) {
    pushUndo()
    val seed = System.currentTimeMillis()
    val frames = DecorGenerator.generateFrames(project.width, project.height, decor, frameCount, seed)
    val tag = "decor_${decor.name.lowercase()}"
    var insertAt = project.currentIndex + 1
    frames.forEachIndexed { _, pixels ->
        val nf = Frame(project.width, project.height, pixels)
        nf.tag = tag
        project.frames.add(insertAt++, nf)
    }
    framesAdapter.notifyDataSetChanged()
    toast("${frames.size} frames « ${decor.displayName} » générées")
    AlertDialog.Builder(this)
        .setTitle("Décor animé « ${decor.displayName} »")
        .setMessage("${frames.size} frames ajoutées. Régénérer avec une nouvelle variation ?")
        .setPositiveButton("Régénérer") { _, _ ->
            val removeStart = project.currentIndex + 1
            for (i in 0 until frames.size) {
                if (removeStart < project.frames.size) project.frames.removeAt(removeStart)
            }
            framesAdapter.notifyDataSetChanged()
            generateAnimatedDecor(decor, frameCount)
        }
        .setNegativeButton("Garder", null)
        .show()
}

internal fun MainActivity.generateDecor(decor: DecorGenerator.Decor, replaceFrame: Boolean) {
    if (replaceFrame) {
        pushUndo()
        val pixels = DecorGenerator.generate(project.width, project.height, decor)
        pixels.copyInto(project.currentFrame.pixels)
        binding.canvas.syncFrameBitmap()
        framesAdapter.notifyItemChanged(project.currentIndex)
    } else {
        val pixels = DecorGenerator.generate(project.width, project.height, decor)
        val bmp = Bitmap.createBitmap(project.width, project.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, project.width, 0, 0, project.width, project.height)
        binding.canvas.bgBitmap = bmp
    }
    AlertDialog.Builder(this)
        .setTitle("Décor « ${decor.displayName} »")
        .setMessage(if (replaceFrame) "Frame remplacée. Voulez-vous une autre variante ?" else "Image de fond définie. Voulez-vous une autre variante ?")
        .setPositiveButton("Régénérer") { _, _ -> generateDecor(decor, replaceFrame) }
        .setNegativeButton("Garder", null)
        .show()
}
