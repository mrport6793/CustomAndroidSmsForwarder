package dev.local.smsforwarder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Does the actual forward, off the main thread, with WorkManager handling retry
 * and surviving reboots. Returns retry() on transient failure (network, SMTP)
 * up to MAX_ATTEMPTS, then failure().
 */
class ForwardWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sender = inputData.getString(SmsReceiver.KEY_SENDER) ?: return Result.failure()
        val body = inputData.getString(SmsReceiver.KEY_BODY) ?: return Result.failure()
        val receivedAt = inputData.getLong(SmsReceiver.KEY_TS, 0L)

        val prefs = Prefs(applicationContext)

        // Filter: if a rule is set and the message doesn't match, silently drop.
        if (!prefs.passesFilter(sender, body)) {
            Log.i(TAG, "Filtered out (no match) from=$sender; dropping")
            return Result.success()
        }

        Log.i(TAG, "Forwarding from=$sender mode=${prefs.mode} attempt=${runAttemptCount + 1}")
        return try {
            when (prefs.mode) {
                Prefs.Mode.EMAIL -> ResendSender.send(prefs, sender, body, receivedAt)
                Prefs.Mode.SMS -> SmsSender.send(applicationContext, prefs.destNumber, sender, body)
                Prefs.Mode.BOTH -> {
                    // Send the SMS leg only on the first attempt: it is fire-and-forget
                    // and non-idempotent, so we must not re-send it when a later EMAIL
                    // retry runs. SMS throwing is non-transient (bad number/permission),
                    // so retrying wouldn't help anyway — log and move on.
                    if (runAttemptCount == 0) {
                        runCatching { SmsSender.send(applicationContext, prefs.destNumber, sender, body) }
                            .onFailure { Log.w(TAG, "SMS leg failed (not retried): ${it.javaClass.simpleName}: ${it.message}") }
                    }
                    // The email leg drives retry; its idempotency key makes retries safe.
                    ResendSender.send(prefs, sender, body, receivedAt)
                }
            }
            Log.i(TAG, "Forward OK from=$sender mode=${prefs.mode}")
            Result.success()
        } catch (e: Exception) {
            // Log class + message (not the body); helps diagnose SMTP/auth issues.
            Log.w(TAG, "Forward failed (attempt ${runAttemptCount + 1}): ${e.javaClass.simpleName}: ${e.message}")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val TAG = "SmsFwd"
        const val MAX_ATTEMPTS = 5
    }
}
