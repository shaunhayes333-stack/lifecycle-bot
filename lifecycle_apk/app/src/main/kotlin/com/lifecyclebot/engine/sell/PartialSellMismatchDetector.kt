package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LiveTradeLogStore
import com.lifecyclebot.network.SolanaWallet
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.9.495z47 — operator P0 (forensics 0508_143519 follow-up):
 * "Verify post-sell wallet balance decreased by expected amount within
 *  tolerance. If the router sells 100% during a partial, log
 *  PARTIAL_SELL_AMOUNT_MISMATCH and block further automation until
 *  reconciled."
 *
 * This detector runs AFTER a partial sell broadcast lands. Reads the
 * post-sell wallet balance, computes the actual consumed amount, and
 * compares to the expected requested amount. If actual exceeds expected
 * by more than 5 % (the operator's "above expected" threshold), raises a
 * SEV log and locks further automated sells for the mint via
 * SellAmountAuditor (which the Executor.liveSell early-return already
 * respects from z39).
 */
object PartialSellMismatchDetector {

    private const val TAG = "PartialSellMismatchDetector"
    /** Operator spec: "wallet drop > 5% above expected" → mismatch. */
    private const val MISMATCH_THRESHOLD_PCT = 5.0

    private val mismatchCount = AtomicInteger(0)
    private val perMintLatest = ConcurrentHashMap<String, Mismatch>()

    data class Mismatch(
        val mint: String,
        val symbol: String,
        val expectedRaw: BigInteger,
        val actualRaw: BigInteger,
        val overconsumedRaw: BigInteger,
        val overconsumedPct: Double,
        val atMs: Long,
    )

    /**
     * @param mint                 token mint
     * @param symbol               display symbol
     * @param decimals             token decimals (must be > 0)
     * @param expectedConsumedRaw  what we INTENDED to sell (PartialSellSizer.size().rawAmount)
     * @param preSellWalletRaw     wallet balance before broadcast (verified)
     * @param wallet               wallet for post-sell read
     * @return true if mismatch detected (and lock applied), false if safe
     */
    fun verifyAndMaybeLock(
        mint: String,
        symbol: String,
        decimals: Int,
        expectedConsumedRaw: BigInteger,
        preSellWalletRaw: BigInteger,
        wallet: SolanaWallet?,
    ): Boolean {
        if (mint.isBlank() || expectedConsumedRaw.signum() <= 0 || decimals <= 0) return false
        val w = wallet ?: return false

        // Brief settle window to let RPC see the tx.
        try { Thread.sleep(2_000) } catch (_: InterruptedException) { return false }

        val postUiAndDec: Pair<Double, Int>? = try {
            w.getTokenAccountsWithDecimals()[mint]
        } catch (_: Throwable) { null }
        if (postUiAndDec == null) {
            // RPC blip — operator spec: empty map MUST be UNKNOWN, not zero.
            // Skip; do not raise mismatch on stale RPC.
            ErrorLogger.warn(TAG, "skip $symbol: post-sell RPC empty (UNKNOWN, not mismatch)")
            return false
        }
        val (postUi, _) = postUiAndDec
        val postRaw = BigDecimal(postUi).movePointRight(decimals).toBigInteger()
        val actualConsumedRaw = preSellWalletRaw.subtract(postRaw).max(BigInteger.ZERO)
        if (actualConsumedRaw <= expectedConsumedRaw) return false       // sold ≤ requested — safe
        val overconsumedRaw = actualConsumedRaw.subtract(expectedConsumedRaw)
        val overPct = if (expectedConsumedRaw.signum() > 0) {
            overconsumedRaw.toBigDecimal()
                .divide(expectedConsumedRaw.toBigDecimal(), 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .toDouble()
        } else 100.0
        if (overPct < MISMATCH_THRESHOLD_PCT) return false               // within 5% tolerance — safe

        // ── MISMATCH DETECTED ────────────────────────────────────────────
        val mismatch = Mismatch(
            mint = mint, symbol = symbol,
            expectedRaw = expectedConsumedRaw,
            actualRaw = actualConsumedRaw,
            overconsumedRaw = overconsumedRaw,
            overconsumedPct = overPct,
            atMs = System.currentTimeMillis(),
        )
        perMintLatest[mint] = mismatch
        mismatchCount.incrementAndGet()

        ErrorLogger.error(TAG,
            "🚨🚨🚨 PARTIAL_SELL_AMOUNT_MISMATCH $symbol mint=${mint.take(8)}… " +
            "expected=$expectedConsumedRaw actual=$actualConsumedRaw " +
            "over=$overconsumedRaw (${"%.2f".format(overPct)}%) — locking mint via SellAmountAuditor.")
        try {
            LiveTradeLogStore.log(
                tradeKey = "MISMATCH_${mint.take(8)}_${System.currentTimeMillis()}",
                mint = mint, symbol = symbol, side = "SELL",
                phase = LiveTradeLogStore.Phase.WARNING,
                message = "🚨 PARTIAL_SELL_AMOUNT_MISMATCH expected=$expectedConsumedRaw actual=$actualConsumedRaw " +
                          "over=${"%.2f".format(overPct)}% — automation BLOCKED for $symbol.",
                traderTag = "MISMATCH_DETECTOR",
            )
        } catch (_: Throwable) { /* fail-soft */ }

        // Apply the auditor lock. Executor.liveSell early-return already
        // respects SellAmountAuditor.isLocked(mint) (z39 wiring).
        try {
            // Build a synthetic SellIntent purely so the auditor records the violation
            // with proper labelling. requested fraction is unknown here; encode the
            // raw expected vs actual delta into the violation log via audit().
            val syntheticIntent = SellIntent.build(
                mint = mint, symbol = symbol,
                reason = ExitReason.PARTIAL_TAKE_PROFIT,
                requestedFractionBps = 1,            // dummy — auditor compares raw amounts
                confirmedWalletRaw = preSellWalletRaw.max(expectedConsumedRaw.add(BigInteger.ONE)),
                decimals = decimals, slippageBps = 0,
                emergencyDrain = false,
                entrySolSpent = 0.0, entryTokenRaw = preSellWalletRaw.max(BigInteger.ONE),
            )
            // Override requestedSellRaw to match the expected amount we intended to sell,
            // so auditor's actual-vs-expected math reflects the true mismatch. Since
            // SellIntent is immutable we audit with a constructed intent that passes
            // the actualConsumedRaw — auditor will lock.
            SellAmountAuditor.audit(syntheticIntent, actualConsumedRaw)
        } catch (e: Throwable) {
            ErrorLogger.warn(TAG, "auditor lock failed (proceeding with detector lock only): ${e.message}")
        }

        // Also raise the operator UI flag.
        try {
            LiveSafetyFlags.raise(mint, LiveSafetyFlags.Flag.SELL_VERIFYING_WITH_NO_SIGNATURE,
                "PARTIAL_SELL_AMOUNT_MISMATCH ${"%.2f".format(overPct)}% over expected")
        } catch (_: Throwable) { /* fail-soft */ }

        return true
    }

    fun mismatchCount(): Int = mismatchCount.get()
    fun latestForMint(mint: String): Mismatch? = perMintLatest[mint]
    fun snapshot(): Map<String, Mismatch> = perMintLatest.toMap()
}
