package dev.local.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Wakes on every incoming SMS (works even if the app's UI was never opened or
 * was swiped away, because it is declared in the manifest). It does as little as
 * possible: parse the message, hand it to WorkManager, return.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        // Multipart messages arrive as several SmsMessage parts; concatenate bodies.
        val sender = parts.first().displayOriginatingAddress ?: "(unknown)"
        val body = parts.joinToString(separator = "") { it.displayMessageBody.orEmpty() }

        // Secret-safe: log sender + length only, never the message body.
        Log.i(TAG, "SMS_RECEIVED from=$sender parts=${parts.size} bodyLen=${body.length}; enqueuing forward")

        val mode = Prefs(context).mode
        val request = OneTimeWorkRequestBuilder<ForwardWorker>()
            .setInputData(workDataOf(KEY_SENDER to sender, KEY_BODY to body, KEY_TS to System.currentTimeMillis()))
            // Email needs the network; SMS->SMS does not. Only constrain in email mode.
            .apply {
                if (mode == Prefs.Mode.EMAIL) {
                    setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                }
            }
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }

    companion object {
        const val TAG = "SmsFwd"
        const val KEY_SENDER = "sender"
        const val KEY_BODY = "body"
        const val KEY_TS = "ts"
    }
}
