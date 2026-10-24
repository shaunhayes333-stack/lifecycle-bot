package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8363 — EXITS KEEP WINNERS (owner-forwarded audit 8360, items 3 and 4).
 *
 *  1. [ProfitLock8363] — a profit lock / give-back / peak lock / drawdown-from-peak / trail / runner floor protects a
 *     gain that is shrinking: it is protective, never "profit-taking". ProfitExitTruth8163's requote-above-cost test
 *     refused exactly these sells as price fell back toward entry (103 refusals, +180 % peaks realised at -13 %).
 *     Genuine take-profit / harvest / spike-capture / milestone sells (selling into strength) are still checked.
 *     One classification, read first by ProfitExitTruth8163.profitTaking, NetEdge8030.exitRefusal8030 and
 *     PartialNetEconomics8094.discretionary8094 (Executor.partialUneconomic8094).
 *  2. [GreenFloor8363] — a green hold (RunnerGrab7967's up-to-6 h lock, TailBag8034's retained tail, a lifecycle hold)
 *     carries a floor once its peak cleared net break-even: max(net break-even, 50 % of the peak gain), the 8337
 *     ExitDoctrine8169.runnerProtectFloor8169 rule (reused). Rug / catastrophic exits never wait for it.
 *  3. [ScalpStop8363] — the scalp stop stands outside entry noise: max(0.6 x lane stop, 2 x the coin's measured
 *     1-minute range + the lane's measured entry chase, 6 %), and no scalp stop in the position's first 60 s.
 *  4. [StopBroadcast8363] — a stop-class sell is never parked behind a balance proof: it broadcasts on the cached
 *     canonical quantity (the confirmed fill) and the proof reconciles after.
 */
object ProfitLock8363 {
    /** Selling into strength: these keep ProfitExitTruth8163's executable-quote test. */
    private val TAKE_WORDS_8363 = listOf("TAKE_PROFIT", "PROFIT_TAKE", "HARVEST", "SPIKE_CAPTURE", "MILESTONE",
        "CAPITAL_RECOVERY", "RUNNER_BANK", "RUNNER_LIVE_BANK", "CHART_CAPTURE")
    /** Protecting a shrinking gain: never refused by the requote test. */
    private val LOCK_WORDS_8363 = listOf("PROFIT_LOCK", "PEAK_LOCK", "GIVEBACK", "GIVE_BACK", "DRAWDOWN_FROM_PEAK",
        "TRAIL", "BREAKEVEN", "BREAK_EVEN", "RUNNER_FLOOR", "GREEN_FLOOR", "PROTECT_FLOOR", "FLOOR_LOCK")
    private val passed = AtomicLong(0)

    /** Pure: is [reason] a protective profit lock (give-back / trail / floor), not a take-profit into strength? */
    fun profitLock8363(reason: String?): Boolean {
        val r = reason.orEmpty().uppercase()
        if (r.isBlank()) return false
        if (TAKE_WORDS_8363.any { r.contains(it) } || r.startsWith("TP_") || r.contains("_TP_")) return false
        return LOCK_WORDS_8363.any { r.contains(it) }
    }

    /** Counted at the 8163 door: a lock that this build no longer refuses. */
    fun notePassed8363() {
        passed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("PROFIT_LOCK_PROTECTIVE_8363") } catch (_: Throwable) {}
    }

    fun statusLine8363(): String = "profitLockProtective8363=${passed.get()}"
}

object GreenFloor8363 {
    /** The floor keeps at least this share of the peak gain once armed. */
    const val KEEP_FRACTION_8363 = 0.5
    private val fired = AtomicLong(0)

    /**
     * Pure: the floor (gross %) of a green hold at confirmed peak [peakPct] with round trip [costPct], or NaN while the
     * peak has not cleared net break-even. ExitDoctrine8169.runnerProtectFloor8169 decides when it arms (the 8337 rule);
     * once armed the remainder exits at max(that floor, 50 % of the peak gain).
     */
    fun floor8363(peakPct: Double, costPct: Double): Double {
        if (!peakPct.isFinite() || peakPct <= 0.0) return Double.NaN
        val base = ExitDoctrine8169.runnerProtectFloor8169(peakPct, costPct)
        if (!base.isFinite()) return Double.NaN
        return maxOf(base, peakPct * KEEP_FRACTION_8363)
    }

    /** Pure: has gross [grossPct] fallen to the armed floor? */
    fun breached8363(grossPct: Double, peakPct: Double, costPct: Double): Boolean {
        val f = floor8363(peakPct, costPct)
        return f.isFinite() && grossPct.isFinite() && grossPct <= f
    }

    // ── live ──

    private class Cost8363(val pct: Double, val atMs: Long)
    private val costs = ConcurrentHashMap<String, Cost8363>()

    /** The position's round trip (%), priced at most once a minute per position (the sell door's model). */
    private fun costPct8363(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Double {
        val k = "${ts.mint}|${ts.position.entryTime}"
        val c = costs[k]
        if (c != null && nowMs - c.atMs in 0L..60_000L) return c.pct
        val pct = try {
            com.lifecyclebot.engine.truth.NetEdge8030.costPct8030(ts.position.tradingMode.ifBlank { "UNKNOWN" }, ts.position.costSol,
                com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts), ts.mint, ts.lastMcap)
        } catch (_: Throwable) { Double.NaN }
        if (costs.size > 2_000) costs.clear()
        costs[k] = Cost8363(pct, nowMs)
        return pct
    }

    /** The confirmed (chain-verified) peak gain of [ts]'s open position, NaN when none. */
    private fun peakPct8363(ts: TokenState): Double {
        val p = ts.position.peakGainPct
        return if (p.isFinite() && p > 0.0) p else Double.NaN
    }

    /** The armed floor of [ts]'s open position, NaN when not armed. */
    fun floorFor8363(ts: TokenState): Double = floor8363(peakPct8363(ts), costPct8363(ts))

    /** Is [ts] at gross [grossPct] at or under its armed floor? */
    fun breachedFor8363(ts: TokenState, grossPct: Double): Boolean {
        val f = floorFor8363(ts)
        return f.isFinite() && grossPct.isFinite() && grossPct <= f
    }

    fun noteFired8363(site: String) {
        fired.incrementAndGet()
        try { PipelineHealthCollector.labelInc("GREEN_FLOOR_8363_$site") } catch (_: Throwable) {}
    }

    fun statusLine8363(): String = "greenFloor8363=${fired.get()} rule=max(netBE,${(KEEP_FRACTION_8363 * 100).toInt()}%peak)"
}

object ScalpStop8363 {
    const val WARMUP_MS_8363 = 60_000L
    const val MIN_STOP_PCT_8363 = 6.0
    const val LANE_STOP_SHARE_8363 = 0.6
    const val NOISE_MULT_8363 = 2.0

    /**
     * Pure: the scalp stop magnitude (positive %): max(0.6 x [laneStopPct], 2 x [noise1mPct] + [chasePct], 6 %).
     * An unmeasured noise or chase (NaN / negative) contributes nothing.
     */
    fun stopPct8363(laneStopPct: Double, noise1mPct: Double, chasePct: Double): Double {
        val lane = if (laneStopPct.isFinite() && laneStopPct > 0.0) laneStopPct * LANE_STOP_SHARE_8363 else 0.0
        val noise = if (noise1mPct.isFinite() && noise1mPct > 0.0) noise1mPct else 0.0
        val chase = if (chasePct.isFinite() && chasePct > 0.0) chasePct else 0.0
        return maxOf(lane, NOISE_MULT_8363 * noise + chase, MIN_STOP_PCT_8363)
    }

    /**
     * Pure: the scalp exit (ScalpExit8351.decide8351's ladder) with the 8363 stop. Inside [WARMUP_MS_8363] no scalp stop
     * fires (catastrophic / rug exits are other doors and act immediately); profit rungs, the give-back floor and the
     * clock are unchanged.
     */
    fun decide8363(g: Double, peak: Double, ageMs: Long, tier: Int, laneStopPct: Double, costPct: Double,
                   noise1mPct: Double, chasePct: Double): Pair<Double, String>? {
        if (!g.isFinite()) return null
        val stop = stopPct8363(laneStopPct, noise1mPct, chasePct)
        // decide8351's stop is max(0.6 x stopPct, 4): pass the 8363 stop through it, or no stop during the warmup.
        val stopArg = if (ageMs < WARMUP_MS_8363) Double.POSITIVE_INFINITY else stop / LANE_STOP_SHARE_8363
        return com.lifecyclebot.engine.truth.ScalpExit8351.decide8351(g, peak, ageMs, tier, stopArg, costPct)
    }

    /** The coin's measured 1-minute range (%), NaN until measured (ExitDoctrine8169's noise read over the 1m candles). */
    fun noise1mPct8363(ts: TokenState): Double = try {
        val candles = synchronized(ts.history) { ts.history.toList() }
        ExitDoctrine8169.noisePct8169(candles.takeLast(12).map { ExitDoctrine8169.candleRangePct8169(it.priceUsd, it.highUsd, it.lowUsd) })
    } catch (_: Throwable) { Double.NaN }

    /** The lane's measured entry chase (%), 0 until measured (EntryChase7961). */
    fun chasePct8363(lane: String): Double = try {
        com.lifecyclebot.engine.truth.EntryChase7961.lanePenaltyPct7961(lane)
    } catch (_: Throwable) { 0.0 }
}

object StopBroadcast8363 {
    private const val RETRY_MS_8363 = 20_000L
    private val lastAt = ConcurrentHashMap<String, Long>()
    private val released = AtomicLong(0)
    private val rescued = AtomicLong(0)

    /** Pure: a stop-class sell (hard stop / stop loss / rapid stop / catastrophic / rug) — ProtectiveExitClass7807 ranks 1-3. */
    fun stopClass8363(reason: String?): Boolean =
        try { com.lifecyclebot.engine.sell.ProtectiveExitClass7807.isEmergency(reason) } catch (_: Throwable) { false }

    /** Pure: may a stop broadcast on the canonical quantity? Not when the wait itself says the holding is absent / frozen. */
    fun waitAllowsStop8363(waitReason: String?): Boolean {
        val w = waitReason.orEmpty().uppercase()
        return listOf("ABSENT", "FROZEN", "NOT_ON_CHAIN", "ZERO").none { w.contains(it) }
    }

    /** The confirmed canonical remaining quantity of [ts]'s live position, or null (scale must equal the token decimals). */
    private fun canonical8363(ts: TokenState): com.lifecyclebot.engine.truth.CanonicalTokenAmount? = try {
        val p = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.getPosition(ts.position.positionId)
        if (p == null || p.mint != ts.mint || !p.mode.equals("live", true) || p.remainingQtyRaw.signum() <= 0 ||
            p.quantityScale != p.tokenDecimals || p.tokenDecimals !in 0..18 ||
            p.lifecycle !in setOf(com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.Lifecycle.OPEN,
                com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED)) null
        else com.lifecyclebot.engine.truth.CanonicalTokenAmount(p.remainingQtyRaw, p.tokenDecimals)
    } catch (_: Throwable) { null }

    private fun eligible8363(ts: TokenState, reason: String, nowMs: Long): com.lifecyclebot.engine.truth.CanonicalTokenAmount? {
        if (ts.position.isPaperPosition || !stopClass8363(reason)) return null
        if (try { com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253.isFrozen(ts.mint) } catch (_: Throwable) { true }) return null
        val absent = try { com.lifecyclebot.engine.sell.SellAmountAuthority.absentFromCompleteSnapshot7733(ts.mint) } catch (_: Throwable) { null }
        if (absent != null && absent.count >= 2) return null
        val amt = canonical8363(ts) ?: return null
        val last = lastAt[ts.mint] ?: 0L
        if (nowMs - last in 0L until RETRY_MS_8363) return null
        return amt
    }

    /**
     * Executor.requestSellCore7948: a stop arriving while the mint waits on a balance proof is not merged into the wait —
     * the wait is cleared and the stop goes on to the lease and the broadcast (true). Absent / frozen holdings stay put.
     */
    fun releaseWait8363(ts: TokenState, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val wait = try { com.lifecyclebot.engine.sell.BalanceProofWaitState.getWaiting(ts.mint) } catch (_: Throwable) { null } ?: return false
        if (!waitAllowsStop8363(wait.desiredExitReason)) return false
        eligible8363(ts, reason, nowMs) ?: return false
        try { com.lifecyclebot.engine.sell.BalanceProofWaitState.clear(ts.mint, "STOP_BROADCAST_WITHOUT_PROOF_WAIT_8363") } catch (_: Throwable) { return false }
        released.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("STOP_BROADCAST_WITHOUT_PROOF_WAIT_8363")
            ForensicLogger.lifecycle("STOP_BROADCAST_WITHOUT_PROOF_WAIT_8363", "mint=${ts.mint.take(10)} reason=${reason.take(60)} site=request_sell action=broadcast_on_canonical_reconcile_after")
        } catch (_: Throwable) {}
        return true
    }

    /**
     * Executor.liveSellInScope7314 (wallet read empty / indeterminate, no owner proof): a stop broadcasts on the cached
     * canonical quantity — the swap fails cleanly if the tokens are not there and the proof reconciles after. Any other
     * exit keeps the strict balance authority (and its SELL_RPC_EMPTY_RESCUE_BLOCKED_INDETERMINATE forensic).
     */
    fun rescue8363(ts: TokenState, reason: String, recovered: Boolean, indeterminate: Boolean,
                   nowMs: Long = System.currentTimeMillis()): com.lifecyclebot.engine.truth.CanonicalTokenAmount? {
        if (recovered) return null
        // V5.0.8368 — never more than the wallet holds: min(canonical, the last wallet read after the last landed sell).
        val amt = eligible8363(ts, reason, nowMs)?.let { SellLanding8368.clampToWallet8368(ts.mint, it, mutatedAt8368(ts)) }
        if (amt == null) {
            if (indeterminate) try { ForensicLogger.lifecycle("SELL_RPC_EMPTY_RESCUE_BLOCKED_INDETERMINATE", "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=$reason action=no_stale_tracker_sell") } catch (_: Throwable) {}
            return null
        }
        if (lastAt.size > 2_000) lastAt.clear()
        lastAt[ts.mint] = nowMs
        rescued.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("STOP_BROADCAST_WITHOUT_PROOF_WAIT_8363")
            ForensicLogger.lifecycle("STOP_BROADCAST_WITHOUT_PROOF_WAIT_8363", "mint=${ts.mint.take(10)} reason=${reason.take(60)} raw=${amt.raw} decimals=${amt.decimals} site=live_sell indeterminate=$indeterminate action=broadcast_on_canonical_reconcile_after")
        } catch (_: Throwable) {}
        return amt
    }

    /** V5.0.8368 — when the canonical row of [ts]'s position last changed (0 = unknown: a landed sell then blocks the rescue). */
    private fun mutatedAt8368(ts: TokenState): Long = try {
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.getPosition(ts.position.positionId)?.lastMutationMs ?: 0L
    } catch (_: Throwable) { 0L }

    fun statusLine8363(): String = "stopBroadcastWithoutProofWait8363[released=${released.get()} canonical=${rescued.get()}]"
}
