package com.marvin.cryptobot.data.remote

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Client minimal pour l'API REST Binance.
 *
 * - Endpoints publics: pas besoin de clés (ex: ticker, serverTime)
 * - Endpoints signés (account, order): HMAC-SHA256 sur la querystring,
 *   header X-MBX-APIKEY = clé publique.
 */
class BinanceClient(
    private val apiKey: String? = null,
    private val apiSecret: String? = null,
    private val baseUrl: String = "https://api.binance.com",
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Prix instantané d'un symbole, ex: BTCEUR. */
    fun lastPrice(symbol: String): Double {
        val url = "$baseUrl/api/v3/ticker/price".toHttpUrl()
            .newBuilder()
            .addQueryParameter("symbol", symbol)
            .build()
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "Binance lastPrice ${resp.code}: $body" }
            return JSONObject(body).getString("price").toDouble()
        }
    }

    /** Heure du serveur (epoch ms). Sert à éviter les erreurs de timestamp. */
    fun serverTime(): Long {
        val url = "$baseUrl/api/v3/time"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "Binance serverTime ${resp.code}: $body" }
            return JSONObject(body).getLong("serverTime")
        }
    }

    /** Soldes du compte (signé). Retourne map asset -> free. */
    fun accountBalances(): Map<String, Double> {
        val params = mapOf("timestamp" to serverTime().toString(), "recvWindow" to "10000")
        val resp = signedGet("/api/v3/account", params)
        val balances = JSONObject(resp).getJSONArray("balances")
        val out = mutableMapOf<String, Double>()
        for (i in 0 until balances.length()) {
            val b = balances.getJSONObject(i)
            val free = b.getString("free").toDouble()
            if (free > 0.0) out[b.getString("asset")] = free
        }
        return out
    }

    /**
     * Achat au marché par montant en quote-currency.
     *
     * Ex: symbol=BTCEUR, quoteAmount=10.0  => achète pour 10 EUR de BTC.
     */
    fun marketBuyQuote(symbol: String, quoteAmount: Double): OrderResult {
        require(apiKey != null && apiSecret != null) { "Clés API requises pour un ordre live" }
        val params = mapOf(
            "symbol" to symbol,
            "side" to "BUY",
            "type" to "MARKET",
            "quoteOrderQty" to formatNumber(quoteAmount),
            "newOrderRespType" to "FULL",
            "timestamp" to serverTime().toString(),
            "recvWindow" to "10000",
        )
        val resp = signedPost("/api/v3/order", params)
        val json = JSONObject(resp)
        val orderId = json.optLong("orderId", -1L)
        val executedQty = json.optString("executedQty", "0").toDouble()
        val cummulativeQuoteQty = json.optString("cummulativeQuoteQty", "0").toDouble()
        val avgPrice = if (executedQty > 0.0) cummulativeQuoteQty / executedQty else 0.0
        return OrderResult(
            orderId = orderId,
            executedQty = executedQty,
            quoteSpent = cummulativeQuoteQty,
            avgPrice = avgPrice,
            raw = resp,
        )
    }

    /**
     * Vente au marché par quantité en base-currency.
     *
     * Ex: symbol=BTCEUR, quantity=0.0001 => vend 0.0001 BTC contre EUR.
     */
    fun marketSellBase(symbol: String, quantity: Double): OrderResult {
        require(apiKey != null && apiSecret != null) { "Clés API requises pour un ordre live" }
        val params = mapOf(
            "symbol" to symbol,
            "side" to "SELL",
            "type" to "MARKET",
            "quantity" to formatNumber(quantity),
            "newOrderRespType" to "FULL",
            "timestamp" to serverTime().toString(),
            "recvWindow" to "10000",
        )
        val resp = signedPost("/api/v3/order", params)
        val json = JSONObject(resp)
        val orderId = json.optLong("orderId", -1L)
        val executedQty = json.optString("executedQty", "0").toDouble()
        val cummulativeQuoteQty = json.optString("cummulativeQuoteQty", "0").toDouble()
        val avgPrice = if (executedQty > 0.0) cummulativeQuoteQty / executedQty else 0.0
        return OrderResult(
            orderId = orderId,
            executedQty = executedQty,
            quoteSpent = cummulativeQuoteQty, // ici: montant reçu
            avgPrice = avgPrice,
            raw = resp,
        )
    }

    private fun signedGet(path: String, params: Map<String, String>): String {
        val key = requireNotNull(apiKey) { "apiKey absent" }
        val signed = sign(params)
        val url = "$baseUrl$path".toHttpUrl().newBuilder()
            .apply { signed.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val req = Request.Builder()
            .url(url)
            .header("X-MBX-APIKEY", key)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "Binance ${resp.code} on $path: $body" }
            return body
        }
    }

    private fun signedPost(path: String, params: Map<String, String>): String {
        val key = requireNotNull(apiKey) { "apiKey absent" }
        val signed = sign(params)
        val url = "$baseUrl$path"
        val formBuilder = FormBody.Builder()
        signed.forEach { (k, v) -> formBuilder.add(k, v) }
        val req = Request.Builder()
            .url(url)
            .header("X-MBX-APIKEY", key)
            .post(formBuilder.build())
            .build()
        http.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "Binance ${resp.code} on $path: $respBody" }
            return respBody
        }
    }

    private fun sign(params: Map<String, String>): Map<String, String> {
        val secret = requireNotNull(apiSecret) { "apiSecret absent" }
        val query = params.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(query.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return params + ("signature" to signature)
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun formatNumber(v: Double): String =
        // Binance attend un point décimal et pas de notation scientifique
        java.math.BigDecimal(v).setScale(8, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()

    data class OrderResult(
        val orderId: Long,
        val executedQty: Double,
        val quoteSpent: Double,
        val avgPrice: Double,
        val raw: String,
    )
}
