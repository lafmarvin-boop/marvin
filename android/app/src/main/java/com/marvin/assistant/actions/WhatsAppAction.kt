package com.marvin.assistant.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.marvin.assistant.util.Contacts

/**
 * WhatsApp's own URL scheme `https://wa.me/<number>?text=<msg>` is the only
 * officially supported way to pre-fill a message. The user still has to tap
 * "send" – WhatsApp has no public API to actually send a message without a tap.
 *
 * Pour automatiser le tap final, il faudrait un AccessibilityService qui clique
 * le bouton d'envoi (cf. MarvinAccessibilityService). On ne le fait pas par
 * défaut: on préfère que tu valides toi-même chaque envoi WhatsApp.
 */
class WhatsAppAction(private val context: Context) {

    fun send(recipient: String, message: String): String {
        val number = Contacts.findPhoneNumber(context, recipient)
            ?.replace(Regex("[^+0-9]"), "")
            ?: return "Je ne trouve pas $recipient dans tes contacts."

        val url = "https://wa.me/$number?text=" + Uri.encode(message)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage("com.whatsapp")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "WhatsApp ouvert pour $recipient. Appuie sur envoyer."
        } catch (t: Throwable) {
            "WhatsApp n'est pas installé."
        }
    }
}
