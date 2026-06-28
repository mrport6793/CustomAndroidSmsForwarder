package dev.local.smsforwarder

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Forwards an SMS as email via the Resend HTTPS API (https://resend.com).
 *
 * Why Resend instead of SMTP/JavaMail:
 *  - the API key can be scoped to SENDING ONLY, a far smaller blast radius than a
 *    Gmail app password if the device is ever compromised;
 *  - HTTPS validates the server certificate by default (no JavaMail
 *    STARTTLS-stripping / checkserveridentity footgun);
 *  - the Idempotency-Key makes WorkManager's retries safe — a retried send with
 *    the same key returns the original result instead of delivering a duplicate.
 *
 * Sandbox note: with the default From (onboarding@resend.dev) Resend only
 * delivers to the email address that owns the API key's account. So sign up to
 * Resend with the inbox you want forwards to land in (e.g. your Proton address)
 * and set that same address as the destination. To send to an arbitrary address
 * you must verify your own domain and use a From on it.
 */
object ResendSender {

    private const val ENDPOINT = "https://api.resend.com/emails"
    private const val DEFAULT_FROM = "onboarding@resend.dev"

    /** @param receivedAt epoch millis when the SMS arrived; keeps the idempotency key stable across retries. */
    fun send(prefs: Prefs, sender: String, body: String, receivedAt: Long) {
        val apiKey = prefs.resendApiKey
        require(apiKey.isNotBlank()) { "No Resend API key configured" }
        require(prefs.emailTo.isNotBlank()) { "No destination email configured" }

        val payload = JSONObject().apply {
            put("from", prefs.resendFrom.ifBlank { DEFAULT_FROM })
            put("to", JSONArray().put(prefs.emailTo))
            put("subject", "SMS from $sender")
            put("text", body)
        }.toString()

        // Stable across retries of the same received SMS -> Resend won't duplicate.
        val idempotencyKey = "sms-forward/$receivedAt-${(sender + body).hashCode()}"

        val conn = (URL(ENDPOINT).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Idempotency-Key", idempotencyKey)
        }

        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                // Resend's error body names the bad field; it does not echo the SMS
                // text, so this is safe to surface (and never contains the API key).
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                throw IOException("Resend HTTP $code: ${err.take(300)}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
