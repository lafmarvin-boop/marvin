package com.marvin.assistant.update

import android.content.Context
import android.util.Log
import com.marvin.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Vérifie s'il y a une release plus récente sur le repo GitHub
 * de Marvin via l'API publique GitHub.
 *
 * Endpoint utilisé : https://api.github.com/repos/{owner}/{repo}/commits/main
 * Compare le SHA du dernier commit main avec le SHA build de l'app
 * (BuildConfig.GIT_SHA, à injecter au build).
 *
 * Note : si BuildConfig.GIT_SHA n'est pas fourni, la check renvoie
 * juste le SHA distant pour info.
 */
class UpdateChecker(@Suppress("unused") private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(val hasUpdate: Boolean, val latestSha: String, val message: String)

    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/commits/main?per_page=1")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use UpdateInfo(false, "", "GitHub API ${resp.code}")
                }
                val body = resp.body?.string() ?: return@use UpdateInfo(false, "", "Réponse vide")
                val arr = JSONArray(body)
                if (arr.length() == 0) return@use UpdateInfo(false, "", "Pas de commit trouvé")
                val sha = arr.getJSONObject(0).optString("sha")
                val msg = arr.getJSONObject(0).optJSONObject("commit")
                    ?.optString("message")?.take(120) ?: ""
                val current = try { BuildConfig::class.java
                    .getField("GIT_SHA").get(null) as? String ?: "" } catch (_: Throwable) { "" }
                val hasUpdate = current.isNotBlank() && !sha.startsWith(current)
                UpdateInfo(hasUpdate, sha.take(7), msg)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "check failed", t)
            UpdateInfo(false, "", "Réseau indisponible")
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        // Owner/repo public Marvin
        private const val REPO = "lafmarvin-boop/marvin"
    }
}
