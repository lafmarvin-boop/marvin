package com.marvin.assistant.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.marvin.assistant.util.ClaudeModel
import com.marvin.assistant.util.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Backend cloud via api.anthropic.com (Messages API, raw HTTP).
 *
 * Pourquoi raw HTTP plutôt que l'Anthropic Java SDK : le SDK pèse ~2 MB sur
 * l'APK, dépendances complexes. OkHttp est déjà dans le projet, l'API est
 * simple, on garde l'app légère.
 *
 * Caractéristiques:
 *  - Modèle réglable depuis Settings (Haiku 4.5 par défaut, Sonnet 4.6 dispo)
 *  - max_tokens=200 (réponses vocales courtes)
 *  - Prompt caching activé sur le system prompt (cf. note ci-dessous)
 *  - Tool use loop: re-soumet jusqu'à `stop_reason="end_turn"` (max 4 itérations)
 *  - Quota quotidien géré par [Settings.consumeDailyQuota]
 *
 * Note caching: Haiku 4.5 demande un préfixe ≥ 4096 tokens pour écrire en cache.
 * Notre system prompt court ne déclenchera probablement pas le cache (silently
 * ignoré). À 50 req/jour avec un prompt court, le coût total reste sous 1 €/mois.
 */
class ClaudeBackend(
    private val context: Context,
    private val settings: Settings,
    private val tools: Tools
) : LlmBackend {

    override val displayName: String get() = "Claude ${settings.claudeModel.name.lowercase()}"

    override fun isReady(): Boolean = settings.anthropicApiKey.isNotBlank()

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun ask(history: List<ChatMessage>): LlmResult = withContext(Dispatchers.IO) {
        if (!hasNetwork()) return@withContext LlmResult.NoNetwork("Pas de réseau.")
        val apiKey = settings.anthropicApiKey
        if (apiKey.isBlank()) return@withContext LlmResult.Error("Clé API Claude absente. Va dans les réglages.")
        if (!settings.consumeDailyQuota()) {
            return@withContext LlmResult.QuotaExceeded(settings.dailyLimit)
        }

        val toolList = tools.all()
        val toolsJson = JSONArray().apply {
            toolList.forEach { t ->
                put(JSONObject().apply {
                    put("name", t.name)
                    put("description", t.description)
                    put("input_schema", t.inputSchema)
                })
            }
        }

        val messagesArray = JSONArray()
        history.forEach { m ->
            messagesArray.put(JSONObject().apply {
                put("role", if (m.role == ChatMessage.Role.USER) "user" else "assistant")
                put("content", m.content)
            })
        }

        // Multi-turn loop: re-submit until no more tool_use.
        repeat(MAX_TOOL_ITERATIONS) { iter ->
            val response = postMessages(apiKey, messagesArray, toolsJson)
                ?: return@withContext LlmResult.Error("Réseau ou API inaccessible.")

            val stopReason = response.optString("stop_reason")
            val content = response.optJSONArray("content") ?: JSONArray()

            if (stopReason == "tool_use") {
                // Append assistant turn (must include tool_use blocks verbatim) then resolve tools.
                messagesArray.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", content)
                })
                val toolResults = JSONArray()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.optString("type") != "tool_use") continue
                    val name = block.getString("name")
                    val id = block.getString("id")
                    val input = block.optJSONObject("input") ?: JSONObject()
                    val tool = toolList.firstOrNull { it.name == name }
                    val result = if (tool == null) {
                        "Outil inconnu: $name"
                    } else {
                        try { tool.execute(input) } catch (t: Throwable) {
                            Log.e(TAG, "Tool $name failed", t)
                            "Erreur de l'outil ${name}: ${t.message}"
                        }
                    }
                    toolResults.put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", id)
                        put("content", result)
                    })
                }
                messagesArray.put(JSONObject().apply {
                    put("role", "user")
                    put("content", toolResults)
                })
                return@repeat // continue loop
            }

            // end_turn (or anything else) — extract text and return.
            val text = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") text.append(block.optString("text"))
            }
            val result = text.toString().trim()
            return@withContext if (result.isBlank())
                LlmResult.Error("Réponse vide.")
            else
                LlmResult.Ok(result)
        }
        LlmResult.Error("Trop d'allers-retours d'outils.")
    }

    private fun postMessages(
        apiKey: String,
        messages: JSONArray,
        toolsJson: JSONArray
    ): JSONObject? {
        val systemBlocks = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", SYSTEM_PROMPT)
                put("cache_control", JSONObject().put("type", "ephemeral"))
            })
        }
        val payload = JSONObject().apply {
            put("model", modelId(settings.claudeModel))
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("system", systemBlocks)
            put("tools", toolsJson)
            put("messages", messages)
        }
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Claude API ${resp.code}: $body")
                    null
                } else if (body == null) {
                    null
                } else {
                    JSONObject(body)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Claude HTTP failed", t)
            null
        }
    }

    private fun modelId(m: ClaudeModel): String = m.id

    private fun hasNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private const val TAG = "ClaudeBackend"
        private const val MAX_OUTPUT_TOKENS = 200
        private const val MAX_TOOL_ITERATIONS = 4

        private const val SYSTEM_PROMPT = """Tu es Marvin, un assistant vocal personnel en français.

Règles strictes:
- Réponds toujours en français.
- Réponses courtes (2-3 phrases max), ton naturel, comme à l'oral.
- Pas de listes à puces, pas de markdown — c'est lu à voix haute.
- Si tu utilises un outil, intègre la donnée dans une phrase complète (ex: « Il fait 18 degrés à Paris. »).
- Pour la météo sans ville précisée, appelle d'abord get_location.
- Si tu ne sais pas et qu'aucun outil ne peut t'aider, dis-le simplement.
- N'invente pas de données factuelles (date, position, agenda) — utilise les outils."""
    }
}
