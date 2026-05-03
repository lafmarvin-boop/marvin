package com.marvin.cryptobot.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stockage chiffré des clés API Binance via Android Keystore.
 *
 * Les valeurs ne sont JAMAIS écrites en clair sur le disque.
 */
class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "cryptobot_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)
        set(value) = prefs.edit().putString(KEY_API, value).apply()

    var apiSecret: String?
        get() = prefs.getString(KEY_SECRET, null)
        set(value) = prefs.edit().putString(KEY_SECRET, value).apply()

    fun hasCredentials(): Boolean = !apiKey.isNullOrBlank() && !apiSecret.isNullOrBlank()

    fun clear() {
        prefs.edit().remove(KEY_API).remove(KEY_SECRET).apply()
    }

    private companion object {
        const val KEY_API = "binance_api_key"
        const val KEY_SECRET = "binance_api_secret"
    }
}
