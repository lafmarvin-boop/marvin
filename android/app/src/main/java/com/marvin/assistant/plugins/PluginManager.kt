package com.marvin.assistant.plugins

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Plugins utilisateur : commandes vocales custom définies dans un fichier
 * JSON local. L'utilisateur peut ajouter ses propres patterns sans
 * recompiler.
 *
 * Fichier : `filesDir/plugins.json`
 *
 * Format :
 * [
 *   {
 *     "name": "ouvrir maps",
 *     "regex": "(?:ouvre|lance)\\s+maps",
 *     "action": "url",
 *     "target": "https://maps.google.com"
 *   },
 *   {
 *     "name": "appeler sophie",
 *     "regex": "appelle\\s+sophie",
 *     "action": "tel",
 *     "target": "+33612345678",
 *     "say": "J'appelle Sophie."
 *   },
 *   {
 *     "name": "lancer netflix",
 *     "regex": "(?:lance|ouvre)\\s+netflix",
 *     "action": "pkg",
 *     "target": "com.netflix.mediaclient"
 *   }
 * ]
 *
 * Actions supportées :
 *  - url : ouvre une URL dans le navigateur
 *  - pkg : lance une app par son package name
 *  - tel : déclenche un appel téléphonique
 *  - intent : lance un Intent générique avec uri (param target)
 */
class PluginManager(private val context: Context) {

    data class Plugin(
        val name: String,
        val regex: Regex,
        val action: String,
        val target: String,
        val say: String? = null
    )

    private fun pluginsFile(): File = File(context.filesDir, "plugins.json")

    fun loadAll(): List<Plugin> {
        val file = pluginsFile()
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Plugin(
                    name = o.optString("name", "plugin#$i"),
                    regex = Regex(o.optString("regex"), RegexOption.IGNORE_CASE),
                    action = o.optString("action"),
                    target = o.optString("target"),
                    say = o.optString("say").takeIf { it.isNotBlank() }
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load plugins.json", t)
            emptyList()
        }
    }

    /** Match une commande à un plugin. Renvoie null si rien ne matche. */
    fun match(text: String): Plugin? {
        for (p in loadAll()) {
            if (p.regex.containsMatchIn(text)) return p
        }
        return null
    }

    /**
     * Exécute le plugin. Renvoie la phrase à TTS (say ou défaut).
     */
    fun execute(plugin: Plugin): String {
        try {
            when (plugin.action) {
                "url" -> {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(plugin.target))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                }
                "pkg" -> {
                    val i = context.packageManager.getLaunchIntentForPackage(plugin.target)
                        ?: return "Application ${plugin.target} introuvable."
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                }
                "tel" -> {
                    val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:${plugin.target}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                }
                "intent" -> {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(plugin.target))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                }
                else -> return "Action de plugin inconnue : ${plugin.action}."
            }
            return plugin.say ?: "OK."
        } catch (t: Throwable) {
            Log.e(TAG, "Plugin ${plugin.name} failed", t)
            return "Erreur : ${t.message}"
        }
    }

    /** Crée un fichier exemple si plugins.json n'existe pas. */
    fun ensureExample() {
        val file = pluginsFile()
        if (file.exists()) return
        val example = JSONArray().apply {
            put(JSONObject().apply {
                put("name", "Exemple : ouvrir Maps")
                put("regex", "(?:ouvre|lance)\\s+maps")
                put("action", "url")
                put("target", "https://maps.google.com")
                put("say", "J'ouvre Maps.")
            })
        }
        file.writeText(example.toString(2))
    }

    companion object { private const val TAG = "Plugins" }
}
