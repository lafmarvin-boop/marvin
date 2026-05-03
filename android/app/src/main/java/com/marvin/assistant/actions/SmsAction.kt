package com.marvin.assistant.actions

import android.content.Context
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import com.marvin.assistant.util.Contacts

class SmsAction(private val context: Context) {

    fun send(recipient: String, message: String): String {
        val number = Contacts.findPhoneNumber(context, recipient)
            ?: return "Je ne trouve pas $recipient dans tes contacts."
        return try {
            val sms = context.getSystemService(SmsManager::class.java)
            val parts = sms.divideMessage(message)
            sms.sendMultipartTextMessage(number, null, parts, null, null)
            "C'est envoyé à $recipient."
        } catch (t: Throwable) {
            Log.e(TAG, "SMS send failed", t)
            "L'envoi du SMS a échoué."
        }
    }

    fun call(recipient: String): String {
        val number = Contacts.findPhoneNumber(context, recipient)
            ?: return "Je ne trouve pas $recipient dans tes contacts."
        val intent = android.content.Intent(android.content.Intent.ACTION_CALL, Uri.parse("tel:$number"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "J'appelle $recipient."
    }

    companion object { private const val TAG = "SmsAction" }
}
