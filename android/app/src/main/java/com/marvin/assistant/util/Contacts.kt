package com.marvin.assistant.util

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import java.text.Normalizer
import java.util.Locale

object Contacts {

    private const val TAG = "Contacts"

    /**
     * Best-effort contact lookup by display-name fragment. Returns the first
     * matching phone number (E.164 style if present in the OS), or null.
     */
    fun findPhoneNumber(context: Context, displayNameFragment: String): String? {
        val needle = normalize(displayNameFragment)
        if (needle.isEmpty()) return null

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        ) ?: return null

        var bestNumber: String? = null
        var bestScore = Int.MAX_VALUE

        cursor.use { c ->
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val name = c.getString(nameIdx) ?: continue
                val number = c.getString(numIdx) ?: continue
                val n = normalize(name)
                if (!n.contains(needle)) continue
                // Prefer the shortest containing match (most specific).
                val score = n.length - needle.length
                if (score < bestScore) {
                    bestScore = score
                    bestNumber = number
                }
            }
        }
        Log.i(TAG, "Resolved \"$displayNameFragment\" → ${bestNumber ?: "<none>"}")
        return bestNumber
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(Locale.FRENCH)
            .trim()
}
