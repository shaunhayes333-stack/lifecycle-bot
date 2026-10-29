package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8418 §A_STOP_SELLS_ON_A_PRICE_THE_ROUTE_AGREES_WITH — the 8418 live journal (c92cda8e2), trigger mark vs fill:
 *   Ac6PLX   RAPID_CATASTROPHE_STOP (needs <= -25 %)  realised -3.3 % gross, 48 s after entry
 *   WJJkGmNB SWEEP_FLUID_FLOOR_-15  (needs <= -15 %)  realised -4.2 % gross
 *   9Pq7aLAb RAPID_FLUID_STOP                          realised -2.7 % gross
 * The 7385 guard already confirmed an off-basis mark with an executable quote — but only inside the first 45 s and only
 * for a short list of source names, so a lagging poll, a best-pair switch or a stale row sold positions at any later age.
 * Rule now: a LOSS stop (catastrophe, hard floor, fluid, sweep floor) whose trigger mark is not a chain read of the
 * position's own venue, nor an executable quote, asks the executable price first — at any age. A real dump still exits:
 * the quote agrees within seconds, and a mark at or under [BYPASS_PCT_8418] after [BYPASS_AGE_MS_8418] never waits.
 * Paper is never gated.
 *
 * V5.0.8419 (review of 8418) — two holes closed:
 *  1. The wait had no bound. With the 45 s expiry gone, a quote API that is down or routes nothing (MARK_EXECUTABLE_QUOTE_
 *     NO_ROUTE_7301) held a -15 % / -25 % stop until -60 %. An unconfirmed stop now fires on its mark once the executable
 *     price has been asked for [AWAIT_MAX_MS_8419] without an answer; a refutation counts only from a quote observed AFTER
 *     the ask (a 60 s-cached quote that pre-dates the mark cannot answer for it — 8094 H9NC5D: a lagging cached quote held a
 *     catastrophe stop from -23 % to -43 %), and a refuted stop asks again every [REASK_GAP_MS_8419], not every 30 s.
 *  2. The coin's own chain state was off-basis. CurveTicks7968 (the bonding-curve account) and the decoded pump trades all
 *     carry PUMP_PORTAL_TRADE_WS_7278 (StopFeed8327: "those prints ARE the executable price"; 3,434 of the 8418 session's
 *     held marks), which the 7385 heuristic reads as a trade-stream observation — so every pump-curve stop was quote-gated
 *     at any age. StopFeed8327.chainMark8327 sources are on the position's basis here and in the rapid monitor.
 */
object StopMarkConfirm8418 {
    private const val BYPASS_PCT_8418 = -60.0
    private const val BYPASS_AGE_MS_8418 = 10_000L
    private val CHAIN_8418 = listOf("CURVE_RPC", "CURVE_ACCOUNT", "VAULT", "POOL_STATE", "ONCHAIN", "LOCKED_VENUE", "EXEC_QUOTE", "JUPITER_QUOTE", "EXECUTABLE")
    // V5.0.8419
    private const val AWAIT_MAX_MS_8419 = 15_000L
    private const val REASK_GAP_MS_8419 = 5_000L
    private const val MAX_AWAITS_8419 = 2_048
    private val awaitSince8419 = ConcurrentHashMap<String, Long>()   // mint|entryTime -> when the executable price was first asked

    /** Pure: is a mark from [source] (pool [markPool]) on the basis of a position entered on [entryPool]? */
    fun onBasis8418(source: String, markPool: String, entryPool: String): Boolean {
        val s = source.uppercase()
        // V5.0.8419 — the coin's own chain state (curve account, decoded curve trade, pool vault / state) is the basis.
        if (StopFeed8327.chainMark8327(s)) return true
        if (CHAIN_8418.any { s.contains(it) }) return true
        if (s.contains("SYNTH") || s.contains("CAP_SEED") || s.contains("UNCORROBORATED") || s.contains("OBSERVATION") ||
            s.contains("ORACLE_FALLBACK") || s.contains("CANDIDATE_REFRESH")) return false
        // a poll / stream print is on basis only when it names the position's own venue
        return entryPool.isNotBlank() && markPool.isNotBlank() && entryPool == markPool
    }

    /**
     * V5.0.8419 — pure: the rapid monitor's verdict. The 7385 heuristic ([legacyOffBasis]: SYNTH / PUMP_FUN_BC / TRADE_WS /
     * another pool) still marks a non-chain source off-basis, but it never overrides a chain mark of the coin itself.
     */
    fun offBasis8419(legacyOffBasis: Boolean, source: String, markPool: String, entryPool: String): Boolean =
        if (StopFeed8327.chainMark8327(source)) false else legacyOffBasis || !onBasis8418(source, markPool, entryPool)

    /** Pure: the verdict given the executable price when one is fresh (null = not yet known). */
    fun executableBreaches8418(execPx: Double?, entryPx: Double, thresholdPct: Double): Boolean? {
        if (execPx == null || !execPx.isFinite() || execPx <= 0.0 || !entryPx.isFinite() || entryPx <= 0.0) return null
        return (execPx / entryPx - 1.0) * 100.0 <= thresholdPct
    }

    /**
     * V5.0.8419 — pure: the verdict of an executable quote [execPx] observed at [execAtMs] for an ask first made at
     * [askedAtMs]. A breach confirms at any age; a refutation needs a quote observed at or after the ask (null = not yet).
     */
    fun quoteVerdict8419(execPx: Double?, execAtMs: Long, askedAtMs: Long, entryPx: Double, thresholdPct: Double): Boolean? {
        val breached = executableBreaches8418(execPx, entryPx, thresholdPct) ?: return null
        if (breached) return true
        return if (execAtMs >= askedAtMs) false else null
    }

    /** V5.0.8419 — pure: an unconfirmed loss stop fires on its own mark once the ask is [AWAIT_MAX_MS_8419] old. */
    fun awaitExpired8419(askedAtMs: Long, nowMs: Long): Boolean = askedAtMs > 0L && nowMs - askedAtMs >= AWAIT_MAX_MS_8419

    /**
     * May a loss stop at [thresholdPct] (negative %) fire for [ts] whose mark reads [pnlPct]? True when the mark is on the
     * position's basis, the executable price agrees, the ask went unanswered for [AWAIT_MAX_MS_8419], or the bypass holds;
     * false while an executable confirmation is pending.
     */
    fun confirmed8418(ts: TokenState, pnlPct: Double, thresholdPct: Double, paper: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (paper) return true
        val pos = ts.position
        if (pos.entryPrice <= 0.0) return true
        val key = "${ts.mint}|${pos.entryTime}"
        val ageMs = if (pos.entryTime > 0L) nowMs - pos.entryTime else Long.MAX_VALUE
        if (pnlPct <= BYPASS_PCT_8418 && ageMs >= BYPASS_AGE_MS_8418) { awaitSince8419.remove(key); return true }
        if (onBasis8418(ts.lastPriceSource, ts.lastPricePoolAddr, pos.entryPoolAddress)) { awaitSince8419.remove(key); return true }
        return executableConfirms8419(ts, thresholdPct, nowMs)
    }

    /**
     * V5.0.8419 — the executable-price leg shared with the rapid monitor's catastrophe / hard-floor block (BotService
     * stopConfirmed7385): a fresh quote decides; none within [AWAIT_MAX_MS_8419] of the first ask fires the stop on its mark.
     */
    fun executableConfirms8419(ts: TokenState, thresholdPct: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        val pos = ts.position
        val key = "${ts.mint}|${pos.entryTime}"
        if (awaitSince8419.size > MAX_AWAITS_8419) awaitSince8419.entries.removeIf { nowMs - it.value > 10L * 60_000L }
        awaitSince8419.putIfAbsent(key, nowMs)
        val asked = awaitSince8419[key] ?: nowMs
        val exec = try { com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.getExecutablePriceIfFresh7301(ts.mint) } catch (_: Throwable) { null }
        val execAt = try { com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.executableObservedAtMs7884(ts.mint) } catch (_: Throwable) { 0L }
        quoteVerdict8419(exec, execAt, asked, pos.entryPrice, thresholdPct)?.let { breached ->
            try { PipelineHealthCollector.labelInc(if (breached) "STOP_MARK_CONFIRMED_EXECUTABLE_8418" else "STOP_MARK_REFUTED_EXECUTABLE_8418") } catch (_: Throwable) {}
            // refuted: the market said "not breached" at execAt; only a NEWER quote may answer the next ask
            if (breached) awaitSince8419.remove(key) else awaitSince8419[key] = nowMs
            return breached
        }
        if (awaitExpired8419(asked, nowMs)) {
            try { PipelineHealthCollector.labelInc("STOP_MARK_UNCONFIRMED_FIRES_ON_MARK_8419") } catch (_: Throwable) {}
            try {
                ForensicLogger.lifecycle("STOP_MARK_UNCONFIRMED_FIRES_ON_MARK_8419",
                    "mint=${ts.mint.take(10)} source=${ts.lastPriceSource} askedMsAgo=${nowMs - asked} action=stop_fires_on_mark_no_executable_answer")
            } catch (_: Throwable) {}
            awaitSince8419.remove(key)
            return true
        }
        try {
            val dec = com.lifecyclebot.engine.truth.MintDecimalsAuthority6392.get(ts.mint) ?: ts.tokenMap.decimals ?: 6
            com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.requestExecutableQuote7301(ts.mint, dec, minGapMs = REASK_GAP_MS_8419)
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("STOP_MARK_AWAITING_EXECUTABLE_8418") } catch (_: Throwable) {}
        return false
    }

    internal fun resetForTest8419() { awaitSince8419.clear() }
}
