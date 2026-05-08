package com.marvin.cryptobot.domain.model

/**
 * Un portefeuille = une stratégie + ses fonds + ses positions.
 *
 * Chaque wallet est indépendant : son cash en quote (EUR), ses holdings en base (BTC),
 * ses paramètres de stratégie. Les wallets sont reliés uniquement par les transferts
 * EUR-EUR que l'utilisateur peut faire manuellement.
 */
data class Wallet(
    val id: String,
    val name: String,
    val type: StrategyType,
    val enabled: Boolean = false,
    val mode: TradingMode = TradingMode.PAPER,
    val symbol: String = "BTCEUR",

    // Soldes (mis à jour au fil des trades)
    val balanceQuote: Double = 0.0,    // EUR disponibles à dépenser
    val holdingsBase: Double = 0.0,    // BTC actuellement détenu
    val totalInvested: Double = 0.0,   // cumul EUR effectivement investis (pour stats)
    val cashInjected: Double = 0.0,    // capital initialement alloué + dépôts - retraits/transferts

    // Paramètres DCA
    val dcaAmount: Double = 5.0,
    val dcaIntervalHours: Int = 12,

    // Paramètres Grid
    val gridStepPercent: Double = 2.0,      // déclenche un trade tous les +/- 2%
    val gridAmountPerStep: Double = 5.0,    // 5 EUR (achat) ou équivalent BTC (vente)
    val gridReferencePrice: Double = 0.0,   // dernier prix de référence; 0 = init au 1er run

    // Plafond cumulé d'investissement (0 = illimité)
    val maxTotalSpend: Double = 0.0,
) {
    companion object {
        const val DCA_ID = "dca"
        const val GRID_ID = "grid"

        fun defaults(): List<Wallet> = listOf(
            Wallet(
                id = DCA_ID,
                name = "DCA",
                type = StrategyType.DCA,
                balanceQuote = 50.0,
                cashInjected = 50.0,
                dcaAmount = 5.0,
                dcaIntervalHours = 12,
                maxTotalSpend = 50.0,
            ),
            Wallet(
                id = GRID_ID,
                name = "Grid Trading",
                type = StrategyType.GRID,
                balanceQuote = 50.0,
                cashInjected = 50.0,
                gridStepPercent = 2.0,
                gridAmountPerStep = 5.0,
                maxTotalSpend = 50.0,
            ),
        )
    }
}
