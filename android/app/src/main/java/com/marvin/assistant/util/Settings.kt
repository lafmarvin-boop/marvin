package com.marvin.assistant.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LlmBackendChoice { CLOUD_CLAUDE, LOCAL_GEMMA }
enum class ClaudeModel(val id: String) {
    HAIKU("claude-haiku-4-5"),
    SONNET("claude-sonnet-4-6")
}

/**
 * Persisted user choices. La clé API Claude est stockée dans des
 * EncryptedSharedPreferences (chiffrement AES-256 piloté par le keystore
 * Android), le reste dans des prefs normales.
 */
class Settings(context: Context) {

    private val plain: SharedPreferences =
        context.getSharedPreferences("marvin", Context.MODE_PRIVATE)

    private val secure: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "marvin_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var backendChoice: LlmBackendChoice
        get() = LlmBackendChoice.valueOf(
            plain.getString(KEY_BACKEND, LlmBackendChoice.CLOUD_CLAUDE.name)!!
        )
        set(value) { plain.edit().putString(KEY_BACKEND, value.name).apply() }

    var claudeModel: ClaudeModel
        get() = ClaudeModel.valueOf(
            plain.getString(KEY_CLAUDE_MODEL, ClaudeModel.HAIKU.name)!!
        )
        set(value) { plain.edit().putString(KEY_CLAUDE_MODEL, value.name).apply() }

    var dailyLimit: Int
        get() = plain.getInt(KEY_DAILY_LIMIT, 50)
        set(value) { plain.edit().putInt(KEY_DAILY_LIMIT, value).apply() }

    var anthropicApiKey: String
        get() = secure.getString(KEY_ANTHROPIC_KEY, "") ?: ""
        set(value) { secure.edit().putString(KEY_ANTHROPIC_KEY, value).apply() }

    /** Returns true and increments the counter if a request is allowed today. */
    @Synchronized
    fun consumeDailyQuota(): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val storedDay = plain.getString(KEY_QUOTA_DAY, "")
        val used = if (storedDay == today) plain.getInt(KEY_QUOTA_USED, 0) else 0
        if (used >= dailyLimit) return false
        plain.edit()
            .putString(KEY_QUOTA_DAY, today)
            .putInt(KEY_QUOTA_USED, used + 1)
            .apply()
        return true
    }

    fun quotaUsedToday(): Int {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return if (plain.getString(KEY_QUOTA_DAY, "") == today)
            plain.getInt(KEY_QUOTA_USED, 0) else 0
    }

    companion object {
        private const val KEY_BACKEND = "backend"
        private const val KEY_CLAUDE_MODEL = "claude_model"
        private const val KEY_DAILY_LIMIT = "daily_limit"
        private const val KEY_ANTHROPIC_KEY = "anthropic_api_key"
        private const val KEY_QUOTA_DAY = "quota_day"
        private const val KEY_QUOTA_USED = "quota_used"
    }
}
