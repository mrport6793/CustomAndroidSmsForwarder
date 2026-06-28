package dev.local.smsforwarder

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Thin SharedPreferences wrapper for all config + the filter logic.
 *
 * SECURITY: the store holds the Gmail app password. It is backed by
 * EncryptedSharedPreferences (AES-256, key held in the hardware-backed Android
 * Keystore), so the values are encrypted at rest and unreadable even via
 * `adb run-as` on this debuggable build. Never log or transmit secrets.
 */
class Prefs(context: Context) {

    enum class Mode { EMAIL, SMS, BOTH }

    private val sp: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "forwarder",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString(K_MODE, Mode.EMAIL.name).orEmpty()) }
            .getOrDefault(Mode.EMAIL)
        set(v) = sp.edit().putString(K_MODE, v.name).apply()

    // Email mode (via Resend HTTPS API)
    var resendApiKey: String get() = sp.getString(K_RESEND_KEY, "").orEmpty(); set(v) = put(K_RESEND_KEY, v)
    var resendFrom: String get() = sp.getString(K_RESEND_FROM, DEFAULT_FROM).orEmpty(); set(v) = put(K_RESEND_FROM, v)
    var emailTo: String get() = sp.getString(K_TO, "").orEmpty(); set(v) = put(K_TO, v)

    // SMS mode
    var destNumber: String get() = sp.getString(K_DEST, "").orEmpty(); set(v) = put(K_DEST, v)

    // Filtering (all optional). Empty = forward everything.
    var senderAllowList: String get() = sp.getString(K_SENDERS, "").orEmpty(); set(v) = put(K_SENDERS, v) // comma-separated
    var keyword: String get() = sp.getString(K_KEYWORD, "").orEmpty(); set(v) = put(K_KEYWORD, v)
    var regex: String get() = sp.getString(K_REGEX, "").orEmpty(); set(v) = put(K_REGEX, v)

    /** Returns true if the message should be forwarded given the configured rules. */
    fun passesFilter(sender: String, body: String): Boolean {
        val senders = senderAllowList.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (senders.isNotEmpty() && senders.none { sender.contains(it, ignoreCase = true) }) return false

        if (keyword.isNotEmpty() && !body.contains(keyword, ignoreCase = true)) return false

        if (regex.isNotEmpty()) {
            val ok = runCatching { Regex(regex, RegexOption.DOT_MATCHES_ALL).containsMatchIn(body) }
                .getOrDefault(true) // a bad regex shouldn't silently swallow messages
            if (!ok) return false
        }
        return true
    }

    private fun put(key: String, value: String) = sp.edit().putString(key, value).apply()

    companion object {
        const val DEFAULT_FROM = "onboarding@resend.dev"
        private const val K_MODE = "mode"
        private const val K_RESEND_KEY = "resend_key"
        private const val K_RESEND_FROM = "resend_from"
        private const val K_TO = "email_to"
        private const val K_DEST = "dest_number"
        private const val K_SENDERS = "senders"
        private const val K_KEYWORD = "keyword"
        private const val K_REGEX = "regex"
    }
}
