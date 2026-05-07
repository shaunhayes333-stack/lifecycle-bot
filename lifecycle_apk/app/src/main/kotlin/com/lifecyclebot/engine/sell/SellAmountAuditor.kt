package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.495z39 — Sell Amount Auditor.
 *
 * Operator spec item 1: "If actualConsumedRaw is materially greater
 * than requestedSellRaw, mark SELL_AMOUNT_VIOLATION and block future
 * sells for that mint until reconciliation completes."
 *
 * Tolerance is the larger of:
 *   - 1% of requested raw amount (rounding/dust)
 *   - 1000 raw atomic units (single-LP rounding)
 *
 * On violation:
 *   - emit SELL_AMOUNT_VIOLATION forensic
 *   - mark mint as locked; subsequent calls to isLocked() return true
 *     until the reconciler clears it.
 */
object SellAmountAuditor {

    private val violations = ConcurrentHashMap<String, ViolationRecord>()
    private val lockedMints = ConcurrentHashMap.newKeySet<String>()

    data class ViolationRecord(
        val mint: String,
        val symbol: String,
        val requestedRaw: BigInteger,
        val actualConsumedRaw: BigInteger,
        val overconsumedRaw: BigInteger,
        val overconsumedPct: Double,
        val tsMs: Long,
    )

    /**
     * @return true when audit passes (no violation), false when violated.
     */
    fun audit(
        intent: SellIntent,
        actualConsumedRaw: BigInteger,
    ): Boolean {
        val tolerance = intent.requestedSellRaw
            .multiply(BigInteger.valueOf(100))
            .divide(BigInteger.valueOf(10_000))   // 1% of requested
            .max(BigInteger.valueOf(1_000L))      // or 1000 raw, whichever is bigger
        val limit = intent.requestedSellRaw + tolerance
        if (actualConsumedRaw <= limit) return true

        val over = actualConsumedRaw - intent.requestedSellRaw
        val overPct = if (intent.requestedSellRaw.signum() > 0) {
            over.toBigDecimal()
                .divide(intent.requestedSellRaw.toBigDecimal(), 6, java.math.RoundingMode.HALF_UP)
                .toDouble() * 100.0
        } else 0.0
        violations[intent.mint] = ViolationRecord(
            mint = intent.mint,
            symbol = intent.symbol,
            requestedRaw = intent.requestedSellRaw,
            actualConsumedRaw = actualConsumedRaw,
            overconsumedRaw = over,
            overconsumedPct = overPct,
            tsMs = System.currentTimeMillis(),
        )
        lockedMints.add(intent.mint)
        ErrorLogger.warn("SellAmountAuditor",
            "🚨 SELL_AMOUNT_VIOLATION ${intent.symbol} mint=${intent.mint.take(8)}… " +
            "requested=${intent.requestedSellRaw} actual=$actualConsumedRaw " +
            "over=${over} (${"%.1f".format(overPct)}%) — locking mint until reconciliation.")
        return false
    }

    fun isLocked(mint: String): Boolean = mint in lockedMints
    fun unlock(mint: String) {
        lockedMints.remove(mint)
        violations.remove(mint)
    }
    fun getViolation(mint: String): ViolationRecord? = violations[mint]
    fun lockedCount(): Int = lockedMints.size
}
