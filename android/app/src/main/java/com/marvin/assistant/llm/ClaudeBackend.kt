package com.marvin.assistant.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.marvin.assistant.util.ClaudeModel
import com.marvin.assistant.util.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
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

    override fun isReady(): Boolean =
        !settings.localOnlyMode && settings.anthropicApiKey.isNotBlank()

    private val http = buildHttpClient()

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)

        // Certificate pinning. Désactivé par défaut tant que les pins ne sont
        // pas extraits du certificat réel d'api.anthropic.com (cf. README,
        // section Sécurité → Certificate pinning).
        //
        // Pour activer:
        //  1. Extraire le SPKI hash:
        //       echo | openssl s_client -servername api.anthropic.com \
        //         -connect api.anthropic.com:443 2>/dev/null \
        //         | openssl x509 -pubkey -noout \
        //         | openssl pkey -pubin -outform der \
        //         | openssl dgst -sha256 -binary | base64
        //  2. Extraire aussi un pin de l'intermediate CA (backup):
        //       openssl s_client -showcerts -servername api.anthropic.com \
        //         -connect api.anthropic.com:443 < /dev/null
        //     puis prendre le 2e cert et faire le même calcul.
        //  3. Coller ci-dessous, mettre PINS_ENABLED = true.
        if (PINS_ENABLED && CERT_PINS.isNotEmpty()) {
            val pinner = CertificatePinner.Builder().apply {
                CERT_PINS.forEach { pin -> add("api.anthropic.com", pin) }
            }.build()
            builder.certificatePinner(pinner)
        }

        return builder.build()
    }

    override suspend fun ask(history: List<ChatMessage>): LlmResult = withContext(Dispatchers.IO) {
        if (!hasNetwork()) return@withContext LlmResult.NoNetwork("Pas de réseau.")
        val apiKey = settings.anthropicApiKey
        if (apiKey.isBlank()) return@withContext LlmResult.Error("Clé API Claude absente. Va dans les réglages.")
        if (!settings.consumeDailyQuota()) {
            return@withContext LlmResult.QuotaExceeded(settings.dailyLimit)
        }

        val toolList = tools.all()
        val toolsJson = JSONArray().apply {
            // Outils locaux (météo, heure, SMS…)
            toolList.forEach { t ->
                put(JSONObject().apply {
                    put("name", t.name)
                    put("description", t.description)
                    put("input_schema", t.inputSchema)
                })
            }
            // Web search côté serveur Anthropic. Claude fait les recherches
            // tout seul et reçoit les résultats automatiquement, on n'a rien
            // à exécuter côté client. Coût : ~1 ¢ par recherche.
            // max_uses=3 limite le nombre de recherches par requête pour
            // éviter une explosion de coûts si Claude part en boucle.
            if (settings.webSearchEnabled && !settings.localOnlyMode) {
                put(JSONObject().apply {
                    put("type", "web_search_20250305")
                    put("name", "web_search")
                    put("max_uses", 3)
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
        // 400 tokens = ~300 mots = ~50 s de TTS. Suffisant pour les
        // recherches web qui peuvent avoir besoin de résumer plus d'infos.
        private const val MAX_OUTPUT_TOKENS = 400
        private const val MAX_TOOL_ITERATIONS = 4

        /** Active le certificate pinning. Garde à false tant que les pins
         *  ne sont pas vérifiés sur ton réseau (cf. extraction dans README). */
        private const val PINS_ENABLED = false

        /** SPKI sha256 hashes au format OkHttp ("sha256/<base64>").
         *  Au moins 2 pins recommandés (leaf + backup intermediate ou backup leaf). */
        private val CERT_PINS = listOf<String>(
            // "sha256/REPLACE_ME_WITH_LEAF_SPKI_HASH",
            // "sha256/REPLACE_ME_WITH_BACKUP_INTERMEDIATE_HASH",
        )

        private const val SYSTEM_PROMPT = """Tu es Jarvis, l'assistant vocal personnel de l'utilisateur. Tu es serviable, posé, légèrement formel — un majordome numérique compétent.

Règles strictes:
- Tu t'appelles Jarvis. Si on te demande qui tu es ou ton nom, dis "Jarvis".
- Réponds toujours en français.
- Réponses courtes et claires, ton naturel, comme à l'oral.
- Pas de listes à puces, pas de markdown — c'est lu à voix haute.
- Si tu utilises un outil, intègre la donnée dans une phrase complète (ex: « Il fait 18 degrés à Paris. »).
- Pour la météo sans ville précisée, appelle d'abord get_location.
- Pour l'heure, utilise get_time. Pour la batterie, get_battery. Pour les SMS, get_recent_sms. Etc.
- N'invente pas de données factuelles (date, position, agenda, batterie) — utilise les outils.

Recherche web (web_search) :
- Utilise web_search dès que la question concerne des FAITS ACTUELS,
  des informations qui peuvent changer (actualités, scores, prix,
  événements récents, météo précise hors get_weather, infos sur
  des personnes/entreprises, recherche de produits, horaires, etc.)
- Pour les connaissances générales stables (histoire, science,
  définitions), réponds directement sans web_search.
- Quand tu utilises web_search, ne mentionne pas explicitement que
  tu cherches sur internet — donne juste la réponse naturellement.
- Cite la source seulement si c'est pertinent (ex: « Selon Le Monde, … »)."""
    }
}
