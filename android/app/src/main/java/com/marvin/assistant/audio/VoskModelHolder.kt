package com.marvin.assistant.audio

import android.content.Context
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream

/**
 * Loads and shares the Vosk French model between [WakeWordEngine] and
 * [SpeechToText]. Loading takes ~1-2 s the first time, so we cache the
 * [Model] instance for the lifetime of [com.marvin.assistant.service.AssistantService].
 *
 * Le modèle est copié depuis `assets/vosk-fr/` vers le stockage interne au
 * premier lancement (Vosk a besoin d'un chemin de fichier réel, pas d'un
 * AssetManager).
 */
class VoskModelHolder(private val context: Context) {

    @Volatile
    private var model: Model? = null

    @Synchronized
    fun get(): Model {
        model?.let { return it }
        val modelDir = File(context.filesDir, "vosk-fr")
        val needsCopy = !modelDir.exists() ||
            modelDir.listFiles()?.isEmpty() != false ||
            !File(modelDir, "conf").exists()
        if (needsCopy) {
            modelDir.mkdirs()
            copyAssetTree("vosk-fr", modelDir)
        }
        return Model(modelDir.absolutePath).also { model = it }
    }

    fun release() {
        model?.close()
        model = null
    }

    private fun copyAssetTree(assetPath: String, outDir: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // It's a file
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outDir).use { input.copyTo(it) }
            }
            return
        }
        outDir.mkdirs()
        for (child in children) {
            val childAsset = "$assetPath/$child"
            val childOut = File(outDir, child)
            val sub = context.assets.list(childAsset) ?: emptyArray()
            if (sub.isEmpty()) {
                context.assets.open(childAsset).use { input ->
                    FileOutputStream(childOut).use { input.copyTo(it) }
                }
            } else {
                copyAssetTree(childAsset, childOut)
            }
        }
    }
}
