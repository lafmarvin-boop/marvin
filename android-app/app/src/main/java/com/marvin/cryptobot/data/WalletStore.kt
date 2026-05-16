package com.marvin.cryptobot.data

import android.content.Context
import com.marvin.cryptobot.domain.model.StrategyType
import com.marvin.cryptobot.domain.model.TradingMode
import com.marvin.cryptobot.domain.model.Wallet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistance des wallets. Stockés en JSON dans SharedPreferences.
 *
 * Au premier lancement, on initialise avec les wallets par défaut (DCA + Grid).
 */
class WalletStore(context: Context) {

    private val prefs = context.getSharedPreferences("cryptobot_wallets", Context.MODE_PRIVATE)

    private val _wallets = MutableStateFlow(load())
    val wallets: StateFlow<List<Wallet>> = _wallets.asStateFlow()

    fun byId(id: String): Wallet? = _wallets.value.firstOrNull { it.id == id }

    fun update(id: String, transform: (Wallet) -> Wallet) {
        val next = _wallets.value.map { if (it.id == id) transform(it) else it }
        _wallets.value = next
        save(next)
    }

    fun replaceAll(list: List<Wallet>) {
        _wallets.value = list
        save(list)
    }

    fun addWallet(
        name: String,
        type: StrategyType,
        symbol: String,
        initialCash: Double,
    ): Wallet {
        val w = Wallet(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { typeDefaultName(type, symbol) },
            type = type,
            symbol = symbol.uppercase(),
            balanceQuote = initialCash,
            cashInjected = initialCash,
            // Defaults raisonnables; l'utilisateur peut éditer après dans Réglages.
            dcaAmount = if (type == StrategyType.DCA) 5.0 else 0.0,
            dcaIntervalHours = 12,
            gridStepPercent = if (type == StrategyType.GRID) 2.0 else 0.0,
            gridAmountPerStep = if (type == StrategyType.GRID) 5.0 else 0.0,
        )
        replaceAll(_wallets.value + w)
        return w
    }

    fun removeWallet(id: String) {
        replaceAll(_wallets.value.filter { it.id != id })
    }

    private fun typeDefaultName(type: StrategyType, symbol: String): String = when (type) {
        StrategyType.DCA -> "DCA $symbol"
        StrategyType.GRID -> "Grid $symbol"
    }

    /** Transfert d'EUR (cash) entre deux wallets. Les holdings BTC ne bougent pas. */
    fun transfer(fromId: String, toId: String, amount: Double): Result<Unit> {
        if (amount <= 0) return Result.failure(IllegalArgumentException("Montant doit être > 0"))
        val from = byId(fromId) ?: return Result.failure(IllegalStateException("Source introuvable"))
        val to = byId(toId) ?: return Result.failure(IllegalStateException("Destination introuvable"))
        if (from.balanceQuote < amount) {
            return Result.failure(IllegalStateException("Solde insuffisant: ${from.balanceQuote} EUR"))
        }
        // Le transfert déplace aussi cashInjected pour ne pas fausser le P&L des deux wallets.
        val updated = _wallets.value.map {
            when (it.id) {
                fromId -> it.copy(
                    balanceQuote = it.balanceQuote - amount,
                    cashInjected = it.cashInjected - amount,
                )
                toId -> it.copy(
                    balanceQuote = it.balanceQuote + amount,
                    cashInjected = it.cashInjected + amount,
                )
                else -> it
            }
        }
        _wallets.value = updated
        save(updated)
        return Result.success(Unit)
    }

    private fun load(): List<Wallet> {
        val raw = prefs.getString(KEY, null) ?: return Wallet.defaults()
        return runCatching { parse(raw) }.getOrElse { Wallet.defaults() }
    }

    private fun save(list: List<Wallet>) {
        prefs.edit().putString(KEY, serialize(list)).apply()
    }

    private fun serialize(list: List<Wallet>): String {
        val arr = JSONArray()
        list.forEach { w ->
            arr.put(JSONObject().apply {
                put("id", w.id)
                put("name", w.name)
                put("type", w.type.name)
                put("enabled", w.enabled)
                put("mode", w.mode.name)
                put("symbol", w.symbol)
                put("balanceQuote", w.balanceQuote)
                put("holdingsBase", w.holdingsBase)
                put("totalInvested", w.totalInvested)
                put("cashInjected", w.cashInjected)
                put("dcaAmount", w.dcaAmount)
                put("dcaIntervalHours", w.dcaIntervalHours)
                put("gridStepPercent", w.gridStepPercent)
                put("gridAmountPerStep", w.gridAmountPerStep)
                put("gridReferencePrice", w.gridReferencePrice)
                put("maxTotalSpend", w.maxTotalSpend)
                put("takeProfitEnabled", w.takeProfitEnabled)
                put("takeProfitThresholdEur", w.takeProfitThresholdEur)
                put("takeProfitSellPercent", w.takeProfitSellPercent)
            })
        }
        return arr.toString()
    }

    private fun parse(raw: String): List<Wallet> {
        val arr = JSONArray(raw)
        val out = mutableListOf<Wallet>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Wallet(
                id = o.getString("id"),
                name = o.getString("name"),
                type = StrategyType.valueOf(o.getString("type")),
                enabled = o.optBoolean("enabled", false),
                mode = TradingMode.valueOf(o.optString("mode", TradingMode.PAPER.name)),
                symbol = o.optString("symbol", "BTCEUR"),
                balanceQuote = o.optDouble("balanceQuote", 0.0),
                holdingsBase = o.optDouble("holdingsBase", 0.0),
                totalInvested = o.optDouble("totalInvested", 0.0),
                cashInjected = o.optDouble("cashInjected", o.optDouble("balanceQuote", 0.0)),
                dcaAmount = o.optDouble("dcaAmount", 5.0),
                dcaIntervalHours = o.optInt("dcaIntervalHours", 12),
                gridStepPercent = o.optDouble("gridStepPercent", 2.0),
                gridAmountPerStep = o.optDouble("gridAmountPerStep", 5.0),
                gridReferencePrice = o.optDouble("gridReferencePrice", 0.0),
                maxTotalSpend = o.optDouble("maxTotalSpend", 0.0),
                takeProfitEnabled = o.optBoolean("takeProfitEnabled", false),
                takeProfitThresholdEur = o.optDouble("takeProfitThresholdEur", 100.0),
                takeProfitSellPercent = o.optDouble("takeProfitSellPercent", 10.0),
            )
        }
        return out
    }

    private companion object {
        const val KEY = "wallets_json"
    }
}
