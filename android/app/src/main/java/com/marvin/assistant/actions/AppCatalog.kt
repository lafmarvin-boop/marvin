package com.marvin.assistant.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import kotlin.math.max

/**
 * Catalogue dynamique de TOUTES les apps installées sur le téléphone,
 * avec fuzzy matching pour permettre à Jarvis d'en lancer n'importe
 * laquelle par voix.
 *
 * Stratégie de matching (par ordre de priorité) :
 *  1. Match exact (insensible casse) avec le label de l'app
 *  2. Le label commence par la query (ex. "What" → "WhatsApp Messenger")
 *  3. Le label contient la query en mot complet
 *  4. Le label contient la query en sous-chaîne
 *  5. Distance de Levenshtein < 30 % de la longueur de la query
 *
 * Si plusieurs apps matchent au même niveau, on choisit la première
 * dans l'ordre alphabétique (déterministe).
 *
 * Permission `QUERY_ALL_PACKAGES` déjà au manifest pour pouvoir enumérer
 * toutes les apps lançables sur Android 11+.
 */
class AppCatalog(private val context: Context) {

    data class App(val label: String, val packageName: String) {
        val normalized: String = label.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
    }

    @Volatile private var cache: List<App>? = null

    /** Liste toutes les apps lançables. Mise en cache 1x au 1er appel. */
    fun all(): List<App> {
        cache?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolves: List<ResolveInfo> = try {
            pm.queryIntentActivities(intent, 0)
        } catch (t: Throwable) {
            Log.e(TAG, "queryIntentActivities failed", t); return emptyList()
        }
        val list = resolves
            .mapNotNull { r ->
                val label = r.loadLabel(pm)?.toString()?.trim() ?: return@mapNotNull null
                if (label.isBlank()) return@mapNotNull null
                App(label, r.activityInfo.packageName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        Log.i(TAG, "App catalog : ${list.size} apps trouvées")
        cache = list
        return list
    }

    /** Force le re-scan (utile après installation/désinstallation). */
    fun refresh() { cache = null; all() }

    /**
     * Cherche l'app correspondant le mieux à [query]. Renvoie null si
     * aucun match plausible (score trop bas).
     */
    fun find(query: String): App? {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return null
        val apps = all()
        if (apps.isEmpty()) return null

        // 1. Match exact
        apps.firstOrNull { it.normalized == q }?.let { return it }

        // 2. Starts with
        apps.firstOrNull { it.normalized.startsWith("$q ") || it.normalized.startsWith(q) }?.let { return it }

        // 3. Contient en mot complet
        val wordRegex = Regex("\\b" + Regex.escape(q) + "\\b")
        apps.firstOrNull { wordRegex.containsMatchIn(it.normalized) }?.let { return it }

        // 4. Sous-chaîne
        apps.firstOrNull { it.normalized.contains(q) }?.let { return it }

        // 5. Distance de Levenshtein
        return apps
            .map { it to levenshtein(it.normalized, q) }
            .filter { (app, d) ->
                val maxLen = max(app.normalized.length, q.length)
                maxLen > 0 && d.toFloat() / maxLen < 0.30f
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(
                    cur[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            System.arraycopy(cur, 0, prev, 0, prev.size)
        }
        return prev[b.length]
    }

    companion object { private const val TAG = "AppCatalog" }
}
