package com.lifecyclebot.engine.sell

/**
 * V5.9.1522 — CANONICAL ROUTER ERROR CLASSIFICATION.
 *
 * Operator directive (live execution finalisation): custom program error
 * 0x1788 is NOT simply "slippage". Retry policy must depend on the REAL error
 * class, not a generic SELL_FAILED bucket. Previously three near-identical
 * `when` blocks in Executor collapsed 0x1788 + 0x1789 + TooLittleSolReceived +
 * "Slippage" into one SLIPPAGE_EXCEEDED label, so a simulation failure (which
 * needs a re-quote / fresh blockhash, not just wider slippage) retried with the
 * same stale quote and looped.
 *
 * This is the single source of truth. All sell catch-blocks route through
 * classify(); retryPolicy() then decides what to do per class.
 */
object SellRouteErrorClassifier {

    enum class Class {
        ROUTE_SIMULATION_FAILED,   // 0x1788 / sim failed → MUST re-quote fresh, not just widen slippage
        SLIPPAGE_EXCEEDED,         // 0x1789 / TooLittleSolReceived / explicit Slippage → widen bps + re-quote
        INSUFFICIENT_FUNDS,        // lamports/rent → terminal-ish, no blind retry
        TOKEN_BALANCE_EMPTY,       // nothing to sell → terminal (already gone)
        NO_SIGNATURE,              // broadcast produced no sig → clear lock unless retry scheduled
        ULTRA_REJECTED,            // Jupiter Ultra rejected the order
        PUMPPORTAL_BAD_REQUEST,    // PumpPortal 400 / bad partial request
        RATE_LIMITED,              // 429 → backoff retry
        TX_EXPIRED,                // blockhash expired → re-quote fresh blockhash
        TIMEOUT,                   // network timeout → retry
        ROUTE_BUILD_FAILED,        // buildSwapTx/transactionBase64 missing → re-quote
        UNKNOWN,
    }

    fun classify(rawMessage: String?): Class {
        val s = (rawMessage ?: "").lowercase()
        return when {
            // ── order matters: most-specific first ──
            // 0x1788 (6024) = bonding-curve / AMM custom program error. On pump.fun
            // / Raydium this is a SIMULATION/route failure, NOT a pure slippage cap.
            s.contains("0x1788") || s.contains("custom program error: 0x1788") ||
                s.contains("simulation failed") || s.contains("instructionerror") ->
                Class.ROUTE_SIMULATION_FAILED
            s.contains("0x1789") || s.contains("toolittlesolreceived") ||
                s.contains("slippage") || s.contains("slippagetolerance") ->
                Class.SLIPPAGE_EXCEEDED
            s.contains("token balance") && (s.contains("empty") || s.contains("zero") || s.contains("0")) ->
                Class.TOKEN_BALANCE_EMPTY
            s.contains("no tokens") || s.contains("balance_empty") || s.contains("nothing to sell") ->
                Class.TOKEN_BALANCE_EMPTY
            s.contains("insufficientfunds") || s.contains("insufficient lamports") ||
                s.contains("insufficient funds") || s.contains("rent") ->
                Class.INSUFFICIENT_FUNDS
            s.contains("no signature") || s.contains("no_signature") ||
                s.contains("null signature") || s.contains("empty signature") ->
                Class.NO_SIGNATURE
            s.contains("ultra") && (s.contains("reject") || s.contains("not allowed") || s.contains("400")) ->
                Class.ULTRA_REJECTED
            s.contains("pumpportal") && (s.contains("400") || s.contains("bad request") || s.contains("invalid")) ->
                Class.PUMPPORTAL_BAD_REQUEST
            s.contains("rate limit") || s.contains("429") ->
                Class.RATE_LIMITED
            s.contains("blockhash") || s.contains("expired") || s.contains("block height exceeded") ->
                Class.TX_EXPIRED
            s.contains("timeout") || s.contains("timed out") ->
                Class.TIMEOUT
            s.contains("buildswaptx") || s.contains("transactionbase64") ||
                s.contains("no route") || s.contains("route not found") ->
                Class.ROUTE_BUILD_FAILED
            else -> Class.UNKNOWN
        }
    }

    data class RetryPolicy(
        val retryable: Boolean,
        /** force a brand-new quote (and blockhash) before the next attempt — never reuse stale. */
        val requireFreshQuote: Boolean,
        /** widen slippage bps on the next attempt. */
        val widenSlippage: Boolean,
        /** the in-progress sell lock MUST be released now (no retry will hold it). */
        val releaseLock: Boolean,
        val reason: String,
    )

    /**
     * Retry policy keyed off the REAL class. `retryScheduled` = caller has (or
     * will) enqueue a retry job that legitimately keeps the lock; if false and
     * the class is non-retryable, the lock must be released so the next stop can
     * fire (operator rule: NO_SIGNATURE must clear the lock unless a retry job
     * is actually scheduled).
     */
    fun retryPolicy(cls: Class, attempt: Int, retryScheduled: Boolean): RetryPolicy = when (cls) {
        Class.ROUTE_SIMULATION_FAILED ->
            RetryPolicy(attempt < 4, requireFreshQuote = true, widenSlippage = true, releaseLock = !retryScheduled, "0x1788 sim — re-quote fresh + wider")
        Class.SLIPPAGE_EXCEEDED ->
            RetryPolicy(attempt < 4, requireFreshQuote = true, widenSlippage = true, releaseLock = !retryScheduled, "slippage — re-quote wider")
        Class.TX_EXPIRED ->
            RetryPolicy(attempt < 4, requireFreshQuote = true, widenSlippage = false, releaseLock = !retryScheduled, "blockhash expired — re-quote")
        Class.RATE_LIMITED ->
            RetryPolicy(attempt < 5, requireFreshQuote = false, widenSlippage = false, releaseLock = !retryScheduled, "429 — backoff retry")
        Class.TIMEOUT ->
            RetryPolicy(attempt < 4, requireFreshQuote = true, widenSlippage = false, releaseLock = !retryScheduled, "timeout — re-quote retry")
        Class.ROUTE_BUILD_FAILED ->
            RetryPolicy(attempt < 4, requireFreshQuote = true, widenSlippage = false, releaseLock = !retryScheduled, "route build failed — re-quote")
        Class.ULTRA_REJECTED ->
            RetryPolicy(attempt < 3, requireFreshQuote = true, widenSlippage = true, releaseLock = !retryScheduled, "ultra rejected — re-quote alt route")
        Class.PUMPPORTAL_BAD_REQUEST ->
            // bad partial request — do not loop on the same bad body; release + escalate to full-exit if caller decides
            RetryPolicy(false, requireFreshQuote = true, widenSlippage = false, releaseLock = true, "pumpportal 400 — release, escalate")
        Class.NO_SIGNATURE ->
            // CRITICAL operator rule: clear the lock unless a retry job is genuinely scheduled
            RetryPolicy(attempt < 3, requireFreshQuote = true, widenSlippage = false, releaseLock = !retryScheduled, "no signature — clear lock unless retry scheduled")
        Class.INSUFFICIENT_FUNDS ->
            RetryPolicy(false, requireFreshQuote = false, widenSlippage = false, releaseLock = true, "insufficient funds — terminal")
        Class.TOKEN_BALANCE_EMPTY ->
            RetryPolicy(false, requireFreshQuote = false, widenSlippage = false, releaseLock = true, "balance empty — already gone, terminal")
        Class.UNKNOWN ->
            RetryPolicy(attempt < 2, requireFreshQuote = true, widenSlippage = true, releaseLock = !retryScheduled, "unknown — limited cautious retry")
    }

    /** Legacy string label (back-compat with existing forensic phases). */
    fun label(cls: Class): String = cls.name
}
