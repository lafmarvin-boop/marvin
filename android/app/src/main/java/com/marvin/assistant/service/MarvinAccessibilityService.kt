package com.marvin.assistant.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred

/**
 * AccessibilityService used to *read* and *click* in apps that don't expose
 * any public API (FamilyWall, Ecovacs Home) and — read-only — bank apps.
 *
 * Garde-fous:
 *  - On limite [packageNames] dans res/xml/accessibility_service_config.xml,
 *    donc Marvin ne reçoit JAMAIS d'événements depuis d'autres apps.
 *  - Pour les apps bancaires, on ne fait que de la lecture (jamais de
 *    performAction(ACTION_CLICK) sur des boutons de virement, etc.).
 *
 * Deux modes d'utilisation:
 *  1. **Pending action** : pose un [PendingAction] pour automatiser un
 *     enchaînement (clic, etc.) dans une app cible.
 *  2. **Bank balance scrape** : pose un [BankBalanceRequest], on parse
 *     l'écran à la recherche d'un montant en € et on complète la deferred.
 */
class MarvinAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return

        // Mode 1: pending action (FamilyWall, Ecovacs)
        pendingAction?.let { action ->
            if (action.targetPackage == pkg) {
                try {
                    if (action.run(root)) pendingAction = null
                } catch (t: Throwable) {
                    Log.e(TAG, "Pending action failed", t)
                    pendingAction = null
                }
                return
            }
        }

        // Mode 2: bank balance scraping (lecture seule, jamais de clic)
        bankBalanceRequest?.let { req ->
            if (req.targetPackage == pkg) {
                val balance = findBalanceText(root)
                if (balance != null) {
                    Log.i(TAG, "Scraped bank balance: $balance")
                    bankBalanceRequest = null
                    req.deferred.complete(balance)
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        // Annule toute requête de scraping pour libérer les coroutines.
        bankBalanceRequest?.deferred?.cancel()
        bankBalanceRequest = null
        super.onDestroy()
    }

    /**
     * Cherche dans l'arbre accessibilité un noeud avec un texte qui ressemble
     * à un solde bancaire. On préfère le plus gros (TextView avec la plus
     * grande aire visible) — c'est souvent le solde principal sur la home.
     */
    private fun findBalanceText(root: AccessibilityNodeInfo): String? {
        val candidates = mutableListOf<Pair<String, Int>>() // (text, area)
        walkTree(root) { node ->
            val raw = node.text?.toString()?.trim() ?: return@walkTree
            val match = BALANCE_PATTERN.find(raw) ?: return@walkTree
            // Filtre les faux positifs courants: numéros de compte longs, IBAN, etc.
            // Un solde a typiquement <= 9 chiffres avant la virgule.
            val before = match.value.substringBefore(',').substringBefore('.')
                .filter { it.isDigit() }
            if (before.length > 9) return@walkTree
            // Aire visible du noeud, pour préférer le plus gros affiché.
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val area = rect.width() * rect.height()
            if (area > 0) candidates.add(match.value.trim() to area)
        }
        return candidates.maxByOrNull { it.second }?.first
    }

    private fun walkTree(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walkTree(child, visit)
        }
    }

    /**
     * A pending UI automation. [run] is invoked on every accessibility event
     * from [targetPackage]; return true once the action is done so we stop
     * trying. The function MUST be idempotent and quick (returns immediately
     * if the target node isn't ready yet).
     */
    class PendingAction(
        val targetPackage: String,
        val run: (AccessibilityNodeInfo) -> Boolean
    )

    /**
     * Requête de scraping de solde bancaire. Le service écoute les events
     * de [targetPackage] et complète [deferred] avec le solde extrait dès
     * qu'il en trouve un. Annulable via [deferred.cancel].
     */
    class BankBalanceRequest(
        val targetPackage: String,
        val deferred: CompletableDeferred<String>
    )

    companion object {
        private const val TAG = "MarvinA11y"

        // Patterns: 1 234,56 € | 1.234,56 | 1234,56€ | -123,45 €
        // 1-9 chiffres, séparateur de milliers optionnel (espace, espace fine, point),
        // virgule ou point décimal, 2 chiffres, € optionnel.
        private val BALANCE_PATTERN = Regex(
            """-?\d{1,3}(?:[   .]\d{3})*[,.]\d{2}\s*€?"""
        )

        @Volatile private var instance: MarvinAccessibilityService? = null

        @Volatile var pendingAction: PendingAction? = null
        @Volatile var bankBalanceRequest: BankBalanceRequest? = null

        fun isEnabled(): Boolean = instance != null

        fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
            root.findAccessibilityNodeInfosByText(text)?.firstOrNull()

        fun findByViewId(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? =
            root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()

        fun click(node: AccessibilityNodeInfo): Boolean =
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
