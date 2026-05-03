package com.marvin.cryptobot.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.marvin.cryptobot.CryptoBotApp
import com.marvin.cryptobot.data.AppContainer
import com.marvin.cryptobot.data.db.TradeEntity
import com.marvin.cryptobot.domain.model.BotConfig
import com.marvin.cryptobot.domain.model.TradingMode
import com.marvin.cryptobot.domain.strategy.DcaStrategy
import com.marvin.cryptobot.worker.DcaWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val container: AppContainer) : ViewModel() {

    val config: StateFlow<BotConfig> = container.configStore.config

    val trades: StateFlow<List<TradeEntity>> = container.tradeDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _lastPrice = MutableStateFlow<Double?>(null)
    val lastPrice: StateFlow<Double?> = _lastPrice.asStateFlow()

    val hasCredentials: Boolean get() = container.secureKeyStore.hasCredentials()

    fun setEnabled(enabled: Boolean) {
        container.configStore.update { it.copy(enabled = enabled) }
        val ctx = CryptoBotApp.instance
        if (enabled) DcaWorker.schedule(ctx, config.value) else DcaWorker.cancel(ctx)
    }

    fun setMode(mode: TradingMode) {
        container.configStore.update { it.copy(mode = mode) }
    }

    fun setSymbol(symbol: String) {
        container.configStore.update { it.copy(symbol = symbol.uppercase().trim()) }
    }

    fun setQuoteAmount(amount: Double) {
        container.configStore.update { it.copy(quoteAmount = amount) }
    }

    fun setIntervalHours(hours: Int) {
        container.configStore.update { it.copy(intervalHours = hours.coerceAtLeast(1)) }
        if (config.value.enabled) DcaWorker.schedule(CryptoBotApp.instance, config.value)
    }

    fun setMaxSpend(value: Double) {
        container.configStore.update { it.copy(maxTotalSpend = value) }
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

    fun refreshPrice() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.newBinanceClient().lastPrice(config.value.symbol) }
                .onSuccess { _lastPrice.value = it }
                .onFailure { _ui.value = _ui.value.copy(message = "Prix indisponible: ${it.message}") }
        }
    }

    fun runOnceNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = DcaStrategy(container).runOnce(config.value.copy(enabled = true))
            val msg = when (outcome) {
                is DcaStrategy.Outcome.Success -> "Achat enregistré (${outcome.trade.mode})"
                is DcaStrategy.Outcome.Skipped -> "Ignoré: ${outcome.reason}"
                is DcaStrategy.Outcome.Failure -> "Échec: ${outcome.reason}"
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
