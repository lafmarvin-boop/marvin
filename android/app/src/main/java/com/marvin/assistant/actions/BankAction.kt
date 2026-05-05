package com.marvin.assistant.actions

import android.content.Context
import android.content.Intent
import android.util.Log
import com.marvin.assistant.nlu.BankKind
import com.marvin.assistant.nlu.BankRequest
import com.marvin.assistant.service.MarvinAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * SÉCURITÉ : Marvin n'effectue **jamais** d'action transactionnelle (virement,
 * paiement, validation 2FA, clic sur quoi que ce soit) côté banque. On se
 * limite à :
 *  1. Ouvrir l'app bancaire (à toi de te logger manuellement si besoin)
 *  2. **Lire** passivement le solde affiché à l'écran via
 *     [MarvinAccessibilityService] (scraping en lecture seule, regex sur les
 *     montants en euros)
 *
 * Pour que ça marche :
 *  - Le service d'accessibilité doit être activé (Réglages → Accessibilité → Marvin)
 *  - L'app bancaire doit afficher le solde immédiatement (sinon il faut se logger)
 *  - Le solde est l'élément le plus visible matchant le pattern d'un montant —
 *    si l'app affiche d'autres montants plus gros, on peut tomber sur le
 *    mauvais. Heuristique acceptable pour un démarrage, à raffiner si besoin.
 */
class BankAction(private val context: Context) {

    suspend fun read(bank: BankKind, request: BankRequest): String {
        val pkg = when (bank) {
            BankKind.BOURSOBANK -> "com.boursorama.android.clients"
            BankKind.BANQUE_POP -> "fr.banquepopulaire.cyberplus"
        }
        val bankName = when (bank) {
            BankKind.BOURSOBANK -> "Boursobank"
            BankKind.BANQUE_POP -> "Banque Pop"
        }

        // Lance l'app bancaire au premier plan (si déjà loggé, le solde s'affiche).
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "L'app $bankName n'est pas installée."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)

        // Pour les requêtes "dernières opérations", on ne fait que l'ouverture.
        if (request != BankRequest.BALANCE) {
            return "J'ouvre $bankName. Connecte-toi pour voir tes dernières opérations."
        }

        // Lecture du solde via AccessibilityService.
        if (!MarvinAccessibilityService.isEnabled()) {
            return "J'ouvre $bankName. Active le service d'accessibilité Marvin pour que je puisse lire ton solde."
        }

        val deferred = CompletableDeferred<String>()
        MarvinAccessibilityService.bankBalanceRequest =
            MarvinAccessibilityService.BankBalanceRequest(pkg, deferred)

        return try {
            val balance = withTimeout(SCRAPE_TIMEOUT_MS) { deferred.await() }
            "Tu as $balance sur $bankName."
        } catch (e: TimeoutCancellationException) {
            "Je n'ai pas pu lire ton solde dans $bankName. Vérifie que tu es bien connecté."
        } catch (t: Throwable) {
            Log.w(TAG, "Bank balance scrape failed", t)
            "Erreur en lisant ton solde dans $bankName."
        } finally {
            // Nettoie au cas où.
            if (MarvinAccessibilityService.bankBalanceRequest?.deferred === deferred) {
                MarvinAccessibilityService.bankBalanceRequest = null
            }
        }
    }

    companion object {
        private const val TAG = "BankAction"
        // 25 s pour laisser à l'utilisateur le temps de débloquer l'app bancaire
        // (empreinte digitale, code PIN) avant que le solde s'affiche.
        private const val SCRAPE_TIMEOUT_MS = 25_000L
    }
}
