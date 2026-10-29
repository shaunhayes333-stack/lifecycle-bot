package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.8418 §A_STOP_SELLS_ON_A_PRICE_THE_ROUTE_AGREES_WITH — the 8418 live journal (c92cda8e2), trigger mark vs fill:
 *   Ac6PLX   RAPID_CATASTROPHE_STOP (needs <= -25 %)  realised -3.3 % gross, 48 s after entry
 *   WJJkGmNB SWEEP_FLUID_FLOOR_-15  (needs <= -15 %)  realised -4.2 % gross
 *   9Pq7aLAb RAPID_FLUID_STOP                          realised -2.7 % gross
 * The 7385 guard already confirmed an off-basis mark with an executable quote — but only inside the first 45 s and only
 * for a short list of source names, so a lagging poll, a best-pair switch or a stale row sold positions at any later age.
 * Rule now: a LOSS stop (catastrophe, hard floor, fluid, sweep floor) whose trigger mark is not a chain read of the
 * position's own venue, nor an executable quote, asks the executable price first — at any age. The quote is cached 60 s
 * and debounced 30 s (MarkIdentityRepairAuthority7236); a real dump still exits: the quote agrees within seconds, and a
 * mark at or under [BYPASS_PCT_8418] after [BYPASS_AGE_MS_8418] never waits. Paper is never gated.
 */
object StopMarkConfirm8418 {
    private const val BYPASS_PCT_8418 = -60.0
    private const val BYPASS_AGE_MS_8418 = 10_000L
    private val CHAIN_8418 = listOf("CURVE_RPC", "CURVE_ACCOUNT", "VAULT", "POOL_STATE", "ONCHAIN", "LOCKED_VENUE", "EXEC_QUOTE", "JUPITER_QUOTE", "EXECUTABLE")

    /** Pure: is a mark from [source] (pool [markPool]) on the basis of a position entered on [entryPool]? */
    fun onBasis8418(source: String, markPool: String, entryPool: String): Boolean {
        val s = source.uppercase()
        if (CHAIN_8418.any { s.contains(it) }) return true
        if (s.contains("SYNTH") || s.contains("CAP_SEED") || s.contains("UNCORROBORATED") || s.contains("OBSERVATION") ||
            s.contains("ORACLE_FALLBACK") || s.contains("CANDIDATE_REFRESH")) return false
        // a poll / stream print is on basis only when it names the position's own venue
        return entryPool.isNotBlank() && markPool.isNotBlank() && entryPool == markPool
    }

    /** Pure: the verdict given the executable price when one is fresh (null = not yet known). */
    fun executableBreaches8418(execPx: Double?, entryPx: Double, thresholdPct: Double): Boolean? {
        if (execPx == null || !execPx.isFinite() || execPx <= 0.0 || !entryPx.isFinite() || entryPx <= 0.0) return null
        return (execPx / entryPx - 1.0) * 100.0 <= thresholdPct
    }

    /**
     * May a loss stop at [thresholdPct] (negative %) fire for [ts] whose mark reads [pnlPct]? True when the mark is on the
     * position's basis, the executable price agrees, or the bypass holds; false while an executable confirmation is pending.
     */
    fun confirmed8418(ts: TokenState, pnlPct: Double, thresholdPct: Double, paper: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (paper) return true
        val pos = ts.position
        if (pos.entryPrice <= 0.0) return true
        val ageMs = if (pos.entryTime > 0L) nowMs - pos.entryTime else Long.MAX_VALUE
        if (pnlPct <= BYPASS_PCT_8418 && ageMs >= BYPASS_AGE_MS_8418) return true
        if (onBasis8418(ts.lastPriceSource, ts.lastPricePoolAddr, pos.entryPoolAddress)) return true
        val exec = try { com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.getExecutablePriceIfFresh7301(ts.mint) } catch (_: Throwable) { null }
        executableBreaches8418(exec, pos.entryPrice, thresholdPct)?.let { breached ->
            try { PipelineHealthCollector.labelInc(if (breached) "STOP_MARK_CONFIRMED_EXECUTABLE_8418" else "STOP_MARK_REFUTED_EXECUTABLE_8418") } catch (_: Throwable) {}
            return breached
        }
        try {
            val dec = com.lifecyclebot.engine.truth.MintDecimalsAuthority6392.get(ts.mint) ?: ts.tokenMap.decimals ?: 6
            com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.requestExecutableQuote7301(ts.mint, dec)
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("STOP_MARK_AWAITING_EXECUTABLE_8418") } catch (_: Throwable) {}
        return false
    }
}
