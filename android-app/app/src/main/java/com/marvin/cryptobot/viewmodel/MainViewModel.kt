package com.marvin.cryptobot.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.marvin.cryptobot.CryptoBotApp
import com.marvin.cryptobot.data.AppContainer
import com.marvin.cryptobot.data.db.TradeEntity
import com.marvin.cryptobot.domain.model.StrategyType
import com.marvin.cryptobot.domain.model.TradingMode
import com.marvin.cryptobot.domain.model.Wallet
import com.marvin.cryptobot.domain.strategy.DcaStrategy
import com.marvin.cryptobot.domain.strategy.GridStrategy
import com.marvin.cryptobot.domain.strategy.Strategy
import com.marvin.cryptobot.domain.strategy.StrategyOutcome
import com.marvin.cryptobot.worker.TradingWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val container: AppContainer) : ViewModel() {

    val wallets: StateFlow<List<Wallet>> = container.walletStore.wallets

    val trades: StateFlow<List<TradeEntity>> = container.tradeDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _prices = MutableStateFlow<Map<String, Double>>(emptyMap())
    val prices: StateFlow<Map<String, Double>> = _prices.asStateFlow()

    val hasCredentials: Boolean get() = container.secureKeyStore.hasCredentials()

    fun setWalletEnabled(id: String, enabled: Boolean) {
        container.walletStore.update(id) { it.copy(enabled = enabled) }
        // Si au moins un wallet est actif, le worker tourne. Sinon on l'arrête.
        val anyEnabled = wallets.value.any { it.enabled }
        val ctx = CryptoBotApp.instance
        if (anyEnabled) TradingWorker.schedule(ctx) else TradingWorker.cancel(ctx)
    }

    fun setMode(id: String, mode: TradingMode) {
        container.walletStore.update(id) { it.copy(mode = mode) }
    }

    fun setSymbol(id: String, symbol: String) {
        container.walletStore.update(id) {
            it.copy(symbol = symbol.uppercase().trim(), gridReferencePrice = 0.0)
        }
    }

    fun setDcaParams(id: String, amount: Double, intervalHours: Int) {
        container.walletStore.update(id) {
            it.copy(dcaAmount = amount, dcaIntervalHours = intervalHours.coerceAtLeast(1))
        }
    }

    fun setGridParams(id: String, stepPercent: Double, amountPerStep: Double) {
        container.walletStore.update(id) {
            it.copy(
                gridStepPercent = stepPercent,
                gridAmountPerStep = amountPerStep,
                gridReferencePrice = 0.0, // reset reference quand on change
            )
        }
    }

    fun setMaxSpend(id: String, value: Double) {
        container.walletStore.update(id) { it.copy(maxTotalSpend = value) }
    }

    fun setTakeProfit(id: String, enabled: Boolean, thresholdEur: Double, sellPercent: Double) {
        container.walletStore.update(id) {
            it.copy(
                takeProfitEnabled = enabled,
                takeProfitThresholdEur = thresholdEur.coerceAtLeast(0.0),
                takeProfitSellPercent = sellPercent.coerceIn(0.0, 100.0),
            )
        }
    }

    fun depositToWallet(id: String, amount: Double) {
        if (amount <= 0) return
        container.walletStore.update(id) {
            it.copy(
                balanceQuote = it.balanceQuote + amount,
                cashInjected = it.cashInjected + amount,
            )
        }
        _ui.value = _ui.value.copy(message = "Dépôt simulé: +%.2f €".format(amount))
    }

    fun resetWallet(id: String) {
        container.walletStore.update(id) {
            it.copy(
                balanceQuote = 50.0,
                holdingsBase = 0.0,
                totalInvested = 0.0,
                cashInjected = 50.0,
                gridReferencePrice = 0.0,
            )
        }
        _ui.value = _ui.value.copy(message = "Wallet réinitialisé (50 €)")
    }

    fun addWallet(name: String, type: StrategyType, symbol: String, initialCash: Double) {
        if (initialCash < 0) return
        val w = container.walletStore.addWallet(name, type, symbol, initialCash)
        _ui.value = _ui.value.copy(message = "Wallet '${w.name}' créé avec ${"%.2f".format(initialCash)} €")
    }

    fun removeWallet(id: String) {
        val w = wallets.value.firstOrNull { it.id == id } ?: return
        container.walletStore.removeWallet(id)
        // Si plus aucun wallet n'est actif, on stoppe le worker.
        if (wallets.value.none { it.enabled }) {
            TradingWorker.cancel(CryptoBotApp.instance)
        }
        _ui.value = _ui.value.copy(message = "Wallet '${w.name}' supprimé")
    }

    fun transferBetweenWallets(fromId: String, toId: String, amount: Double) {
        container.walletStore.transfer(fromId, toId, amount)
            .onSuccess {
                _ui.value = _ui.value.copy(
                    message = "Transfert: %.2f € de %s vers %s".format(
                        amount,
                        wallets.value.first { it.id == fromId }.name,
                        wallets.value.first { it.id == toId }.name,
                    )
                )
            }
            .onFailure {
                _ui.value = _ui.value.copy(message = "Échec transfert: ${it.message}")
            }
    }

    fun saveApiCredentials(apiKey: String, apiSecret: String) {
        container.secureKeyStore.apiKey = apiKey.trim()
        container.secureKeyStore.apiSecret = apiSecret.trim()
        _ui.value = _ui.value.copy(message = "Clés API enregistrées (chiffrées)")
    }

    fun clearApiCredentials() {
        container.secureKeyStore.clear()
        _ui.value = _ui.value.copy(message = "Clés API supprimées")
    }

    fun refreshPrices() {
        viewModelScope.launch(Dispatchers.IO) {
            val symbols = wallets.value.map { it.symbol }.distinct()
            val client = container.newBinanceClient()
            val results = symbols.associateWith { sym ->
                runCatching { client.lastPrice(sym) }.getOrNull()
            }.filterValues { it != null }.mapValues { it.value!! }
            _prices.value = results
        }
    }

    fun runWalletNow(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val wallet = wallets.value.firstOrNull { it.id == id } ?: return@launch
            val strategy: Strategy = when (wallet.type) {
                StrategyType.DCA -> DcaStrategy(container)
                StrategyType.GRID -> GridStrategy(container)
            }
            val outcome = strategy.runOnce(wallet.copy(enabled = true))
            val msg = when (outcome) {
                is StrategyOutcome.Executed -> {
                    container.walletStore.update(id) { outcome.updatedWallet }
                    if (outcome.trades.isEmpty()) "Initialisé (réf grid posée)"
                    else "${outcome.trades.size} trade(s) exécuté(s)"
                }
                is StrategyOutcome.Skipped -> "Ignoré: ${outcome.reason}"
                is StrategyOutcome.Failure -> "Échec: ${outcome.reason}"
            }
            _ui.value = _ui.value.copy(message = msg)
        }
    }

    fun consumeMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    data class UiState(val message: String? = null)
}

class MainViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MainViewModel(container) as T
}
