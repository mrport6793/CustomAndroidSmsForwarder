package dev.local.smsforwarder

import android.content.Context
import android.os.Build
import android.telephony.SmsManager

/**
 * Forwards the message to another phone number as SMS.
 * Note: carrier charges may apply per forwarded text.
 */
object SmsSender {

    fun send(context: Context, destNumber: String, sender: String, body: String) {
        require(destNumber.isNotBlank()) { "No destination number configured" }

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        val text = "From $sender: $body"
        val parts = smsManager.divideMessage(text) // handles >160 char messages
        smsManager.sendMultipartTextMessage(destNumber, null, parts, null, null)
    }
}
