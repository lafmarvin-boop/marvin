package com.marvin.assistant.actions

import android.content.Context
import android.content.Intent
import com.marvin.assistant.nlu.BankKind
import com.marvin.assistant.nlu.BankRequest

/**
 * SÉCURITÉ: Marvin n'effectue **jamais** d'action transactionnelle (virement,
 * paiement, validation 2FA) côté banque. On se limite à ouvrir l'app, et plus
 * tard (via AccessibilityService) à lire le solde affiché à l'écran. Toute
 * extension de ce périmètre doit être discutée explicitement.
 */
class BankAction(private val context: Context) {

    fun read(bank: BankKind, request: BankRequest): String {
        val pkg = when (bank) {
            BankKind.BOURSOBANK -> "com.boursorama.android.clients"
            BankKind.BANQUE_POP -> "fr.banquepopulaire.cyberplus"
        }
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "L'app bancaire n'est pas installée."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        // TODO: brancher MarvinAccessibilityService pour lire le solde affiché.
        val what = when (request) {
            BankRequest.BALANCE -> "ton solde"
            BankRequest.LAST_OPS -> "tes dernières opérations"
        }
        return "J'ouvre l'app. Connecte-toi pour voir $what."
    }
}
