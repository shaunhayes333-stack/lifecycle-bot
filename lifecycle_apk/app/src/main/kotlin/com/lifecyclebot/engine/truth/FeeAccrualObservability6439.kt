package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6439 — FEE ACCRUAL OBSERVABILITY.
 *
 * OPERATOR DIRECTIVE:
 *   "Live trading transaction fees still aren't being sent to the two
 *    coded wallets. They should send on every transaction. They don't
 *    send EVER!"
 *
 * The plumbing IS there (Executor.sendFeeSplit → FeeAccumulator.accrue →
 * BotService cycle tryFlush → SolanaWallet.sendSol). If nothing is
 * landing at the two coded wallets, we need to prove exactly WHERE the
 * chain breaks. This module records every accrue attempt with:
 *   • timestamp
 *   • destination
 *   • amount
 *   • tag ("buy_fee_w1", "sell_fee_w2", etc.)
 *   • paperMode flag
 *
 * The pipeline health dump prints the last N attempts + a per-tag
 * running count so the operator can see at a glance "yes we accrued
 * 47 buy_fee_w1 today totalling 0.023 SOL, none of them flushed."
 */
object FeeAccrualObservability6439 {

    private const val KEEP_LAST = 24

    private data class AccrueRecord(
        val whenMs: Long,
        val dest: String,
        val amountSol: Double,
        val tag: String,
        val paperMode: Boolean,
    )

    private val totalAccrues = AtomicLong(0L)
    private val totalAccruedSol = AtomicReference(0.0)
    private val lastFlushAtMs = AtomicLong(0L)
    private val lastFlushSol = AtomicReference(0.0)
    private val recent = java.util.concurrent.ConcurrentLinkedDeque<AccrueRecord>()
    private val tagCounts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun noteAccrue(dest: String, amountSol: Double, tag: String, paperMode: Boolean) {
        totalAccrues.incrementAndGet()
        totalAccruedSol.getAndUpdate { it + amountSol }
        tagCounts.merge(tag, 1L) { a, b -> a + b }
        val r = AccrueRecord(System.currentTimeMillis(), dest, amountSol, tag, paperMode)
        recent.addLast(r)
        while (recent.size > KEEP_LAST) recent.pollFirst()
        try {
            ForensicLogger.lifecycle(
                "FEE_ACCRUE_6439",
                "dest=${dest.take(12)} sol=${"%.6f".format(amountSol)} tag=$tag paper=$paperMode " +
                    "totalCount=${totalAccrues.get()} totalSol=${"%.5f".format(totalAccruedSol.get())}",
            )
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("FEE_ACCRUE_6439") } catch (_: Throwable) {}
    }

    fun noteFlush(totalSolFlushed: Double) {
        if (totalSolFlushed <= 0.0) return
        lastFlushAtMs.set(System.currentTimeMillis())
        lastFlushSol.set(totalSolFlushed)
        try {
            ForensicLogger.lifecycle(
                "FEE_FLUSH_6439",
                "solFlushed=${"%.5f".format(totalSolFlushed)}",
            )
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("FEE_FLUSH_6439") } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val n = totalAccrues.get()
        val total = totalAccruedSol.get()
        val lastFlushMs = lastFlushAtMs.get()
        val lastFlushAgeMin =
            if (lastFlushMs <= 0L) -1L
            else (System.currentTimeMillis() - lastFlushMs) / 60_000L
        val lastFlush = lastFlushSol.get()
        return "accrues=$n accruedSol=${"%.5f".format(total)} " +
            "lastFlushSol=${"%.5f".format(lastFlush)} lastFlushAgeMin=$lastFlushAgeMin"
    }

    fun tagCountsSnapshot(): Map<String, Long> = HashMap(tagCounts)
}
