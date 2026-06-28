package dev.local.smsforwarder

import android.content.Context

/**
 * Lightweight forwarding counters. These are not secret (no message content,
 * no addresses), so they live in a plain SharedPreferences file rather than the
 * encrypted config store — cheaper for the frequent writes the worker makes.
 *
 * Increments come from ForwardWorker, which may run on WorkManager's multi-thread
 * executor, so the read-modify-write is guarded by a process-wide lock.
 */
class Stats(context: Context) {

    private val sp = context.getSharedPreferences("stats", Context.MODE_PRIVATE)

    val forwarded: Long get() = sp.getLong(K_FWD, 0)
    val filtered: Long get() = sp.getLong(K_FILT, 0)
    val failed: Long get() = sp.getLong(K_FAIL, 0)
    /** Epoch millis of the last successful forward; 0 = never. */
    val lastForwardedAt: Long get() = sp.getLong(K_LAST, 0)

    fun recordForwarded(at: Long) = synchronized(LOCK) {
        sp.edit().putLong(K_FWD, forwarded + 1).putLong(K_LAST, at).apply()
    }

    fun recordFiltered() = synchronized(LOCK) {
        sp.edit().putLong(K_FILT, filtered + 1).apply()
    }

    fun recordFailed() = synchronized(LOCK) {
        sp.edit().putLong(K_FAIL, failed + 1).apply()
    }

    fun reset() = synchronized(LOCK) { sp.edit().clear().apply() }

    companion object {
        private val LOCK = Any()
        private const val K_FWD = "forwarded"
        private const val K_FILT = "filtered"
        private const val K_FAIL = "failed"
        private const val K_LAST = "last_forwarded_at"
    }
}
