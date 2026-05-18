package com.pixelhero.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Export-related code extracted from MainActivity. These functions operate on
 * [MainActivity] via extension so they can reach `project`, `binding`, etc.
 * while keeping the activity file smaller.
 *
 * The activity exposes `binding`, `project`, `framesAdapter`, `paletteAdapter`,
 * and `toast()` as `internal` so this file can use them.
 */

/** Bake a frame to a Bitmap, optionally upscaled with nearest-neighbor. */
internal fun MainActivity.frameToBitmap(frame: Frame, scale: Int = 1): Bitmap {
    val composite = if (frame.layers.size > 1) frame.composited() else frame.pixels
    val bmp = Bitmap.createBitmap(frame.width * scale, frame.height * scale, Bitmap.Config.ARGB_8888)
    if (scale == 1) {
        bmp.setPixels(composite, 0, frame.width, 0, 0, frame.width, frame.height)
    } else {
        val small = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        small.setPixels(composite, 0, frame.width, 0, 0, frame.width, frame.height)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        c.drawBitmap(small, null, android.graphics.Rect(0, 0, bmp.width, bmp.height), p)
        small.recycle()
    }
    return bmp
}

internal fun MainActivity.exportPng() {
    val bmp = frameToBitmap(project.currentFrame, 8)
    val bytes = ByteArrayOutputStream().apply { bmp.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
    savePublicImage(bytes, "${project.name}_frame${project.currentIndex + 1}.png", "image/png")
    bmp.recycle()
}

internal fun MainActivity.exportAllFrames() {
    val scale = 8
    project.frames.forEachIndexed { i, f ->
        val bmp = frameToBitmap(f, scale)
        val bytes = ByteArrayOutputStream().apply { bmp.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        savePublicImage(bytes, "${project.name}_frame${i + 1}.png", "image/png")
        bmp.recycle()
    }
    toast("${project.frames.size} frames exportées")
}

internal fun MainActivity.exportSpriteSheet() {
    val total = project.frames.size
    val progress = androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Sprite sheet")
        .setMessage("Préparation…")
        .setCancelable(false)
        .show()
    lifecycleScope.launch {
        val scale = 4
        val cols = Math.ceil(Math.sqrt(total.toDouble())).toInt().coerceAtLeast(1)
        val rows = (total + cols - 1) / cols
        val fw = project.width * scale
        val fh = project.height * scale
        val bytes = withContext(Dispatchers.Default) {
            val sheet = Bitmap.createBitmap(cols * fw, rows * fh, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(sheet)
            val paint = android.graphics.Paint().apply { isFilterBitmap = false }
            project.frames.forEachIndexed { i, frame ->
                val b = frameToBitmap(frame, scale)
                canvas.drawBitmap(b, (i % cols * fw).toFloat(), (i / cols * fh).toFloat(), paint)
                b.recycle()
                withContext(Dispatchers.Main) {
                    progress.setMessage("Frame ${i + 1} / $total")
                }
            }
            val b = ByteArrayOutputStream().apply { sheet.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
            sheet.recycle()
            b
        }
        progress.dismiss()
        savePublicImage(bytes, "${project.name}_sheet.png", "image/png")
    }
}

internal fun MainActivity.exportGif() {
    // Progress dialog so the user sees frame-by-frame progress on long
    // animations (200 frames × 600×600 used to look frozen).
    val progress = androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(getString(R.string.generating_gif))
        .setMessage("Préparation…")
        .setCancelable(false)
        .show()
    lifecycleScope.launch {
        val total = project.frames.size
        val bytes = withContext(Dispatchers.Default) {
            val encoder = GifEncoder(project.width, project.height)
            project.frames.forEachIndexed { i, f ->
                val comp = if (f.layers.size > 1) f.composited() else f.pixels
                encoder.addFrame(comp, project.delayForFrame(i))
                withContext(Dispatchers.Main) {
                    progress.setMessage("Frame ${i + 1} / $total")
                }
            }
            encoder.encodeToBytes()
        }
        savePublicImage(bytes, "${project.name}.gif", "image/gif")
        progress.dismiss()
        toast(getString(R.string.gif_done))
    }
}

internal fun MainActivity.sharePng() {
    val bmp = frameToBitmap(project.currentFrame, 8)
    val bytes = ByteArrayOutputStream().apply { bmp.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
    savePublicImage(bytes, "${project.name}_frame${project.currentIndex + 1}.png", "image/png", share = true)
    bmp.recycle()
}

internal fun MainActivity.shareGif() {
    val progress = androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(getString(R.string.generating_gif))
        .setMessage("Préparation…")
        .setCancelable(false)
        .show()
    lifecycleScope.launch {
        val total = project.frames.size
        val bytes = withContext(Dispatchers.Default) {
            val encoder = GifEncoder(project.width, project.height)
            project.frames.forEachIndexed { i, f ->
                val comp = if (f.layers.size > 1) f.composited() else f.pixels
                encoder.addFrame(comp, project.delayForFrame(i))
                withContext(Dispatchers.Main) {
                    progress.setMessage("Frame ${i + 1} / $total")
                }
            }
            encoder.encodeToBytes()
        }
        progress.dismiss()
        savePublicImage(bytes, "${project.name}.gif", "image/gif", share = true)
    }
}

internal fun MainActivity.savePublicImage(bytes: ByteArray, filename: String, mime: String, share: Boolean = false): Uri? {
    return try {
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PixelHero")
            }
            val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            u?.let { contentResolver.openOutputStream(it)?.use { o -> o.write(bytes) } }
            u
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PixelHero")
            dir.mkdirs()
            val f = File(dir, filename)
            FileOutputStream(f).use { it.write(bytes) }
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)))
            Uri.fromFile(f)
        }
        if (uri == null) { toast("Échec d'enregistrement"); return null }
        if (share) shareUri(uri, mime, filename)
        else toast("Enregistré dans Pictures/PixelHero/$filename")
        uri
    } catch (e: Exception) {
        toast("Erreur: ${e.message}")
        null
    }
}

private fun MainActivity.shareUri(uri: Uri, mime: String, filename: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, filename)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        startActivity(Intent.createChooser(intent, "Partager $filename"))
    } catch (e: Exception) {
        toast("Aucune application pour partager")
    }
}
