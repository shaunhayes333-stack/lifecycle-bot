package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8065 — LIFECYCLE HOLD. Owner: "it keeps trading like a Sniper bot. some trades take days or weeks to truly run
 * their lifecycle! saadboi took a few weeks to a few months to run."
 *
 * 5.0.8063 live: 42 sells, 2 wins, most closed inside one to four minutes on a tick stop (RAPID_FLUID_STOP,
 * RAPID_CATASTROPHE_STOP at -22% after 11 s, PROTECTIVE_EXIT_STOP_LOSS, time exits, flat culls). Every lane traded
 * a 5-minute scalp. A lifecycle lane's position is now held like a position trader holds it:
 *
 *   - ordinary exits (tick stops, catastrophe ticks above the floor, time exits, flat culls, profit locks, trails,
 *     spike tiers, runner / chart exits) are HELD; integrity exits (rug, liquidity pull, dev dump, honeypot, freeze,
 *     dead token, manual, operator stop, wallet / reconciler) always pass (TailBag8034.structural8034);
 *   - its own exits, on its own 15-minute bars:
 *       FLOOR       gross <= -[FLOOR_PCT] at any time;
 *       BASE BROKE  after [SETTLE_MS], still under the 2x take, a 15m close below the lowest low of the prior 16 bars
 *                   (the 4-hour base) while red more than [BASE_BROKE_RED_PCT];
 *       2X TAKE     +100%: half is sold — the stake is back, the rest is a free ride (and takes no lane slot);
 *       LADDER      +400%: 30% of the rest; +900%: 30% of the rest;
 *       TREND BREAK after the take: a 15m close below the lowest low of the prior 24 bars (the 6-hour range), or
 *                   after a +300% peak the price at 45% of the peak multiple;
 *       DEAD MONEY  after 72 h flat (-15%..+20%) with no new high in 48 h: released, the capital works elsewhere.
 *
 * Lifecycle lanes: [LANES_8065]. Any other live position that reaches +100% is promoted into the hold. State and
 * bars survive restarts (lifecycle_8065.txt). The forward labels grade these lanes on the same floor
 * (StopAuthority7887.laneStopMag8029), and their progressive checkpoints run to 7 days.
 */
object LifecycleHold8065 {
    private val LANES_8065 = setOf("MOONSHOT", "QUALITY", "BLUECHIP", "CORE")
    const val FLOOR_PCT = 40.0
    private const val PROMOTE_PCT = 100.0
    private const val SETTLE_MS = 2L * 3_600_000L
    private const val BASE_BROKE_RED_PCT = -15.0
    private const val BAR_MS = 15L * 60_000L
    private const val MAX_BARS = 700
    private const val DEAD_AFTER_MS = 72L * 3_600_000L
    private const val NO_HIGH_MS = 48L * 3_600_000L
    // The previous 30s interval prevented a second profit attempt during many
    // one-minute meme runs. CloseLease and SellExecutionLocks already provide
    // authoritative per-mint single-flight and provider backoff.
    private const val SELL_RETRY_MS = 3_000L
    private const val FILE = "lifecycle_8065.txt"
    /** (gross %, share of the CURRENT holding sold). */
    private val TAKES_8065 = listOf(100.0 to 0.50, 400.0 to 0.30, 900.0 to 0.30)

    /** Per position (mint|entryTime): takes done, promoted, peak gross, when the peak was set. */
    internal class TakeProgress8076(initial: Int = 0) {
        var taken = initial.coerceIn(0, TAKES_8065.size)
            private set
        @Synchronized fun confirm(reason: String): Boolean {
            val rung = Regex("^LIFECYCLE_PROFIT_TAKE([1-3])_8065_").find(reason)?.groupValues?.get(1)?.toIntOrNull() ?: return false
            // A verified partial can cover several tiers crossed in a single price print.
            if (rung <= taken || rung > TAKES_8065.size) return false
            taken = rung
            return true
        }
    }
    private class State(taken: Int, var promoted: Boolean, var peak: Double, var peakAt: Long) {
        val takes = TakeProgress8076(taken)
        val taken get() = takes.taken
    }

    private val states = ConcurrentHashMap<String, State>()
    private val bars = ConcurrentHashMap<String, ArrayDeque<DoubleArray>>()  // mint -> [startMs, o, h, l, c]
    private val lastSell = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false
    @Volatile private var lastSaveMs = 0L
    private val held = AtomicLong(0)
    private val partialsHeld = AtomicLong(0)
    private val promotions = AtomicLong(0)
    private val exits = ConcurrentHashMap<String, AtomicLong>()

    // ── pure ──

    /** Pure: is [reason] an exit a lifecycle hold never holds through (integrity, operator, its own)? */
    // V5.0.8098 — owner: a better opportunity force-sells the poorest held trade to free the lane (rotation8098).
    fun passes8065(reason: String): Boolean {
        val r = reason.uppercase()
        return r.startsWith("LIFECYCLE_") || TailBag8034.structural8034(r) || rotation8098(r) ||
            com.lifecyclebot.engine.sell.ProtectiveExitClass7807.of(r).rank <=
                com.lifecyclebot.engine.sell.ProtectiveExitClass7807.Priority.STRUCTURAL_EMERGENCY.rank ||
            RunnerExitContract8136.emergency8136(r)  // V5.0.8136 — tier 1 is never held
    }

    private fun rotation8098(r: String) = r.startsWith(com.lifecyclebot.engine.truth.OpportunityRotation8098.REASON)

    /** V5.0.8071 — the lifecycle ladder, for the forward labels that grade lifecycle lanes on it. */
    fun ladder8071(): List<Pair<Double, Double>> = TAKES_8065

    /** Pure: the take (index, fraction) due at [grossPct] with [taken] already done, or null. */
    fun takeDue8065(grossPct: Double, taken: Int): Pair<Int, Double>? {
        if (!grossPct.isFinite() || taken >= TAKES_8065.size) return null
        val (bar, frac) = TAKES_8065[taken]
        return if (grossPct >= bar) taken to frac else null
    }

    /**
     * 8079 rapid peak capture: if one observed executable mark crosses two or
     * three rungs, combine their residual fractions into one partial attempt.
     * The runner remainder remains held; only chain-confirmed token consumption
     * advances the highest crossed rung via onConfirmedTake8076.
     */
    internal fun cumulativeTakeDue8079(grossPct: Double, taken: Int): Pair<Int, Double>? {
        if (!grossPct.isFinite() || taken !in 0 until TAKES_8065.size) return null
        val highest = TAKES_8065.indexOfLast { grossPct >= it.first }
        if (highest < taken) return null
        var remainder = 1.0
        for (i in taken..highest) remainder *= (1.0 - TAKES_8065[i].second.coerceIn(0.0, 1.0))
        return highest to (1.0 - remainder)
    }

    /** Pure: a close below the lowest low of the [lookback] bars before the last one (bars: [start, o, h, l, c]). */
    fun rangeBroken8065(b: List<DoubleArray>, lookback: Int): Boolean {
        if (b.size < lookback + 1) return false
        val last = b[b.size - 1]
        val prior = b.subList(b.size - 1 - lookback, b.size - 1)
        val floor = prior.minOf { it[3] }
        return last[4] < floor
    }

    /** Pure: after a +300% peak, price at or below 45% of the peak multiple. */
    fun peakGiveback8065(peakPct: Double, grossPct: Double): Boolean =
        peakPct >= 300.0 && grossPct.isFinite() && (1.0 + grossPct / 100.0) <= 0.45 * (1.0 + peakPct / 100.0)

    /**
     * V5.0.8080 §UNBANKED_PEAK_GIVEBACK. Operator 5.0.8080 live: Bear (SHITCOIN) peaked +2872%, was promoted here at
     * +100%, its 2x take never confirmed, and it rode back to +9.65% while the card said "lock +2635%". Every
     * TICK_PROFIT_LOCK was held (defer8065) and the hold's only give-back exit required `taken >= 1`, so an unbanked
     * hold had no profit protection above the -40% floor. The stake-back premise of the hold never happened: an
     * unbanked hold that has given back half its peak gain exits.
     */
    fun unbankedGiveback8080(peakPct: Double, grossPct: Double, taken: Int): Boolean =
        taken == 0 && peakPct.isFinite() && peakPct >= PROMOTE_PCT && grossPct.isFinite() && grossPct <= peakPct * 0.5

    /** V5.0.8080 — the hold's own give-back exit is due (either rule, banked or not). */
    fun givebackDue8080(peakPct: Double, grossPct: Double, taken: Int): Boolean =
        peakGiveback8065(peakPct, grossPct) || unbankedGiveback8080(peakPct, grossPct, taken)

    /** V5.0.8080 — pure: the gross % at which the hold's give-back exit fires, for the position card. */
    fun givebackLevel8080(peakPct: Double, taken: Int): Double? {
        if (!peakPct.isFinite()) return null
        val multiple = if (peakPct >= 300.0) (0.45 * (1.0 + peakPct / 100.0) - 1.0) * 100.0 else Double.NaN
        val unbanked = if (taken == 0 && peakPct >= PROMOTE_PCT) peakPct * 0.5 else Double.NaN
        val lvl = listOf(multiple, unbanked).filter { it.isFinite() }.maxOrNull()
        return lvl
    }

    /** V5.0.8080 — the card's lock for a held position: (level %, banked) or null when not held / not armed. */
    fun displayLock8080(ts: TokenState): Pair<Double, Boolean>? {
        if (!holding8065(ts)) return null
        val st = states[key(ts)] ?: return null
        val peak = maxOf(st.peak, ts.position.peakGainPct.takeIf { it.isFinite() } ?: Double.NEGATIVE_INFINITY)
        return givebackLevel8080(peak, st.taken)?.let { it to (st.taken >= 1) }
    }

    /**
     * An unbanked runner must not be held through a major reversal merely
     * because its earlier profit-take transaction failed to land.
     * Only act on a fresh price observation while meaningful gross profit remains;
     * downstream sell quote/chain finality still determine actual economics.
     */
    internal fun profitablePeakReversal8079(peakPct: Double, currentPct: Double, fresh: Boolean): Boolean =
        fresh && peakPct >= 300.0 && currentPct >= 20.0 && peakGiveback8065(peakPct, currentPct)

    /**
     * Pure (V5.0.8109) §A_HOLD_THAT_NEVER_RAN_IS_NOT_A_RUNNER. Owner, 5.0.8108: "absolutely drop moonshots like any token
     * metrics collapse etc. so yes". 5.0.8098: losers held 196.8 min vs winners 16.7 min — the hold deferred every
     * ordinary stop down to -40% and its own base break needs ~4.5 h of bars. A held position that never reached
     * +[UNPROVEN_PEAK_PCT_8109] takes its ordinary stops once red for [UNPROVEN_RED_MS_8109] or at [UNPROVEN_DEEP_PCT_8109];
     * a position that did run keeps the hold and its give-back exits.
     */
    fun unprovenLoser8109(peakPct: Double, grossPct: Double, ageMs: Long): Boolean =
        grossPct.isFinite() && grossPct < 0.0 && !(peakPct.isFinite() && peakPct >= UNPROVEN_PEAK_PCT_8109) &&
            (ageMs >= UNPROVEN_RED_MS_8109 || grossPct <= UNPROVEN_DEEP_PCT_8109)

    /**
     * Pure (V5.0.8109): the token's own metrics have collapsed under a red hold — pool liquidity below half its entry
     * depth, or sellers in control (fresh buy share <= [COLLAPSE_BUY_SHARE_8109]%). Any hold, run or not, releases.
     */
    fun metricsCollapsed8109(grossPct: Double, entryLiqUsd: Double, liqNowUsd: Double, buySharePct: Double, buyShareFresh: Boolean): Boolean {
        if (!(grossPct.isFinite() && grossPct < 0.0)) return false
        val liqGone = entryLiqUsd > 0.0 && liqNowUsd > 0.0 && liqNowUsd < entryLiqUsd * 0.5
        val sellersOwnIt = buyShareFresh && buySharePct.isFinite() && buySharePct in 0.0..COLLAPSE_BUY_SHARE_8109
        return liqGone || sellersOwnIt
    }

    private const val UNPROVEN_PEAK_PCT_8109 = 25.0
    private const val UNPROVEN_RED_MS_8109 = 45L * 60_000L
    private const val UNPROVEN_DEEP_PCT_8109 = -20.0
    private const val COLLAPSE_BUY_SHARE_8109 = 25.0

    /** Pure: the dead-money release. */
    fun deadMoney8065(ageMs: Long, grossPct: Double, sinceHighMs: Long): Boolean =
        ageMs >= DEAD_AFTER_MS && grossPct in -15.0..20.0 && sinceHighMs >= NO_HIGH_MS

    // ── state ──

    private fun laneOf(ts: TokenState): String =
        (try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(ts.position.tradingMode) } catch (_: Throwable) { "" })
            .ifBlank { ts.position.tradingMode }.trim().uppercase()

    /** Is [lane] a lifecycle lane (labels graded on the lifecycle floor)? */
    fun lifecycleLane8065(lane: String): Boolean = lane.trim().uppercase() in LANES_8065

    private fun key(ts: TokenState) = "${ts.mint}|${ts.position.entryTime}"

    private fun gross(ts: TokenState, px: Double?): Double {
        val e = ts.position.entryPrice
        val p = px ?: ts.lastPrice
        return if (e > 0.0 && p > 0.0) (p / e - 1.0) * 100.0 else Double.NaN
    }

    /** Is [ts]'s open live position held for its lifecycle (a lifecycle lane, or promoted at +100%)? */
    internal fun holding8065(ts: TokenState): Boolean {
        val pos = ts.position
        if (!pos.isOpen || pos.isPaperPosition || pos.entryTime <= 0L || !(pos.entryPrice > 0.0)) return false
        load()
        // A quick contract cannot become a lifecycle hold. A long style still
        // needs the existing lane/ticket/promotion authority; style alone never widens risk.
        if (TradeExitContract8077.longHold(ts) == false) return false
        // V5.0.8189 — the hold needs runner evidence (a sealed runner style with a proven setup, or a corroborated
        // +100% peak). An unproven position keeps its lane stop and profit ladder (LifecycleEvidence8189).
        if (!LifecycleEvidence8189.holds8189(LifecycleEvidence8189.evidenceFor8189(ts, laneOf(ts)))) return false
        if (lifecycleLane8065(laneOf(ts))) return true
        // V5.0.8072 — a runner ticket is held for its lifecycle whatever lane bought it.
        if (try { RunnerTicket8073.isTicket8073(ts.mint) } catch (_: Throwable) { false }) return true
        // V5.0.8066 — once held, always held: a lane transfer (LaneTransitionManager, by market cap) never releases it.
        return states[key(ts)] != null
    }

    /** V5.0.8066 — the owner's Stop leaves lifecycle holds in the wallet (BotService.stopBot). */
    fun keepOnStop8066(ts: TokenState): Boolean = holding8065(ts)

    /** V5.0.8066 — the open-positions label: "" when not held, else HOLD / FREE RIDE. */
    fun label8066(ts: TokenState): String {
        if (!holding8065(ts)) return ""
        return if (TailBag8034.capitalRecovered8090(ts.position.positionId, ts.mint)) "🧬 FREE RIDE" else "🧬 HOLD"
    }

    /** LiveRiskPolicy7807: a lifecycle position past its 2x take is a free ride — it takes no lane slot. */
    fun freeRide8065(mint: String): Boolean =
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().any {
            it.mint == mint && TailBag8034.capitalRecovered8090(it.positionId, mint)
        }

    // ── the sell doors ──

    /** Executor.requestSellCore7948: non-null when this exit is held for the position's lifecycle. */
    fun defer8065(ts: TokenState, reason: String): String? {
        if (!holding8065(ts) || passes8065(reason)) return release8189(ts, reason)
        // Missing freshness cannot revoke the immutable holding contract.
        // Price-driven exits validate their observation at the executor boundary.
        val g = gross(ts, null)
        if (g.isFinite() && g <= -FLOOR_PCT) return release8189(ts, reason)
        // V5.0.8080 — a hold whose own give-back is due never swallows the profit lock / trail that
        // asked first (the rapid mark that would fire the hold's exit may not have a price this tick).
        val st8080 = states[key(ts)]
        val now8109 = System.currentTimeMillis()
        val peak8109 = maxOf(st8080?.peak ?: Double.NEGATIVE_INFINITY, ts.position.peakGainPct.takeIf { it.isFinite() } ?: Double.NEGATIVE_INFINITY)
        if (unprovenLoser8109(peak8109, g, now8109 - ts.position.entryTime) ||
            metricsCollapsed8109(g, ts.position.entryLiquidityUsd, ts.lastLiquidityUsd, ts.lastBuyPressurePct, now8109 - ts.buyPressureObservedAtMs8084 in 0L..120_000L)) {
            try { PipelineHealthCollector.labelInc("LIFECYCLE_LOSER_RELEASED_8109") } catch (_: Throwable) {}
            return release8189(ts, reason)
        }
        if (st8080 != null && givebackDue8080(maxOf(st8080.peak, ts.position.peakGainPct), g, st8080.taken)) {
            try { PipelineHealthCollector.labelInc("LIFECYCLE_GIVEBACK_RELEASED_8080") } catch (_: Throwable) {}
            return release8189(ts, reason)
        }
        held.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIFECYCLE_HELD_8065") } catch (_: Throwable) {}
        // V5.0.8189 — every deferral is named (evidence + exit tier) and its episode is kept for the regret count.
        val named8189 = LifecycleEvidence8189.deferralReason8189(LifecycleEvidence8189.evidenceFor8189(ts, laneOf(ts)), reason)
        LifecycleEvidence8189.noteDeferral8189(key(ts), named8189, g)
        return named8189
    }

    /**
     * V5.0.8189 — the exit passes the hold (null). A position whose earlier exits the hold deferred settles its episode
     * (regret when this exit is worse than the first deferred one); an unproven lifecycle-lane position is counted.
     */
    private fun release8189(ts: TokenState, reason: String): String? {
        try {
            val pos = ts.position
            if (pos.isOpen && !pos.isPaperPosition && pos.entryTime > 0L) {
                LifecycleEvidence8189.noteRelease8189(key(ts), gross(ts, null))
                if (!passes8065(reason) && lifecycleLane8065(laneOf(ts)) && TradeExitContract8077.longHold(ts) != false &&
                    !LifecycleEvidence8189.holds8189(LifecycleEvidence8189.evidenceFor8189(ts, laneOf(ts)))) LifecycleEvidence8189.noteUnprovenNotHeld8189()
            }
        } catch (_: Throwable) {}
        return null
    }

    /** Executor.requestPartialSell: an ordinary slice (spike tier, ladder, lock) is not sold from a lifecycle hold. */
    fun partialHeld8065(ts: TokenState, reason: String): Boolean {
        if (!holding8065(ts) || passes8065(reason)) return false
        partialsHeld.incrementAndGet()
        return true
    }

    // ── the hold's own exits (SpikeCapture7943.rapidMark, every open position, ~500 ms) ──

    /** Returns true when [ts] is a lifecycle hold (the caller then skips the scalp exits for it). */
    fun onMark8065(ts: TokenState, px: Double?, nowMs: Long, sell: (TokenState, Double, String) -> Unit): Boolean {
        val pos = ts.position
        if (!pos.isOpen || pos.isPaperPosition || pos.entryTime <= 0L) return false
        load()
        if (TradeExitContract8077.longHold(ts) == false) return false
        val g = gross(ts, px)
        val k = key(ts)
        if (!holding8065(ts)) {
            // Promotion: any live position that reaches +100% is held for its lifecycle from here.
            // V5.0.8080 — promotion needs the chain-verified peak, not one rapid-mark print.
            if (g.isFinite() && g >= PROMOTE_PCT && pos.peakGainPct >= PROMOTE_PCT) {
                states[k] = State(0, true, minOf(g, pos.peakGainPct), nowMs)
                promotions.incrementAndGet()
                try { PipelineHealthCollector.labelInc("LIFECYCLE_PROMOTED_8065") } catch (_: Throwable) {}
                saveMaybe(nowMs, force = true)
            } else return false
        }
        if (!g.isFinite() || px == null || !(px > 0.0)) return true
        val st = states.getOrPut(k) { State(0, false, g, nowMs) }
        // V5.0.8080 — the hold's peak never runs ahead of the chain-verified position peak.
        val gPeak8080 = minOf(g, pos.peakGainPct)
        if (gPeak8080 > st.peak) { st.peak = gPeak8080; st.peakAt = nowMs }
        val b = barsFor(ts.mint, px, nowMs)
        val age = nowMs - pos.entryTime
        // V5.0.8136 — a profit take is decided on a fresh executable mark only; a stale print waits (stops do not).
        val fresh8136 = takeMarkFresh8136(ts, px, nowMs)
        if (!fresh8136 && cumulativeTakeDue8079(g, st.taken) != null) try { RunnerExitLive8136.count8136("LIFECYCLE_TAKE_STALE_MARK_WAIT_8136") } catch (_: Throwable) {}
        val why: Pair<String, Double>? = when {
            g <= -FLOOR_PCT -> "LIFECYCLE_STOP_FLOOR_8065_${g.toInt()}PCT" to 1.0
            profitablePeakReversal8079(st.peak, g, ts.lastPrice > 0.0 && px == ts.lastPrice &&
                ts.lastPriceUpdate > 0L && nowMs - ts.lastPriceUpdate in 0L..5_000L) ->
                "LIFECYCLE_PROFIT_GIVEBACK_8065_${st.peak.toInt()}PK_${g.toInt()}PCT" to 1.0
            fresh8136 && cumulativeTakeDue8079(g, st.taken) != null -> cumulativeTakeDue8079(g, st.taken)!!.let { (i, f) -> "LIFECYCLE_PROFIT_TAKE${i + 1}_8065_${g.toInt()}PCT" to f }
            st.taken == 0 && age >= SETTLE_MS && g < BASE_BROKE_RED_PCT && closedBarBroke(b, 16) ->
                "LIFECYCLE_STOP_BASE_BROKE_8065_${g.toInt()}PCT" to 1.0
            st.taken >= 1 && (closedBarBroke(b, 24) || peakGiveback8065(st.peak, g)) ->
                "LIFECYCLE_STOP_TREND_BREAK_8065_${st.peak.toInt()}PK_${g.toInt()}PCT" to 1.0
            // V5.0.8080 — unbanked (the 2x take never confirmed): the same give-back rules guard the whole bag.
            st.taken == 0 && givebackDue8080(st.peak, g, st.taken) ->
                "LIFECYCLE_STOP_UNBANKED_GIVEBACK_8065_${st.peak.toInt()}PK_${g.toInt()}PCT" to 1.0
            deadMoney8065(age, g, nowMs - st.peakAt) -> "LIFECYCLE_STOP_DEAD_MONEY_8065_${g.toInt()}PCT" to 1.0
            else -> null
        }
        saveMaybe(nowMs)
        if (why == null) return true
        if (nowMs - (lastSell[k] ?: 0L) < SELL_RETRY_MS) return true
        if (lastSell.size > 2_000) lastSell.clear()
        lastSell[k] = nowMs
        val (reason, frac) = why
        // Dispatch is asynchronous and may refuse without throwing. Only a verified
        // canonical slice can consume a rung (onConfirmedTake8076).
        try {
            sell(ts, frac, reason)
        } catch (failure: Exception) {
            lastSell.remove(k, nowMs)
            try { PipelineHealthCollector.labelInc("LIFECYCLE_SELL_DISPATCH_FAILED_8065") } catch (_: Throwable) {}
            return true
        }
        exits.getOrPut(reason.substringBefore("_8065")) { AtomicLong(0) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(reason.substringBefore("_8065") + "_8065")
            ForensicLogger.lifecycle("LIFECYCLE_EXIT_8065", "mint=${ts.mint.take(10)} sym=${ts.symbol} gross=${"%.1f".format(g)}% peak=${"%.1f".format(st.peak)}% frac=$frac why=$reason")
        } catch (_: Throwable) {}
        saveMaybe(nowMs, force = true)
        return true
    }

    /** V5.0.8136 — is [px] the row's own mark and at most RunnerExitContract8136.FRESH_MARK_MS_8136 old? */
    private fun takeMarkFresh8136(ts: TokenState, px: Double, nowMs: Long): Boolean =
        ts.lastPriceUpdate > 0L && px == ts.lastPrice && RunnerExitContract8136.freshForCapture8136(nowMs - ts.lastPriceUpdate)

    /** Called only after the verified live slice has committed to canonical inventory. */
    fun onConfirmedTake8076(mint: String, entryTime: Long, reason: String, signature: String, consumedRaw: java.math.BigInteger) {
        if (entryTime <= 0L || signature.isBlank() || consumedRaw.signum() <= 0) return
        load()
        val st = states["$mint|$entryTime"] ?: return
        if (!st.takes.confirm(reason)) return
        try { PipelineHealthCollector.labelInc("LIFECYCLE_TAKE_CONFIRMED_8076") } catch (_: Throwable) {}
        saveMaybe(System.currentTimeMillis(), force = true)
    }

    /** The last CLOSED bar broke the range (the open bar is still forming). */
    private fun closedBarBroke(b: List<DoubleArray>, lookback: Int): Boolean =
        b.size >= 2 && rangeBroken8065(b.subList(0, b.size - 1), lookback)

    private fun barsFor(mint: String, px: Double, nowMs: Long): List<DoubleArray> {
        val q = bars.getOrPut(mint) { ArrayDeque() }
        synchronized(q) {
            val start = (nowMs / BAR_MS) * BAR_MS
            val last = q.lastOrNull()
            if (last == null || last[0].toLong() != start) {
                q.addLast(doubleArrayOf(start.toDouble(), px, px, px, px))
                while (q.size > MAX_BARS) q.removeFirst()
            } else {
                last[2] = maxOf(last[2], px); last[3] = minOf(last[3], px); last[4] = px
            }
            return q.toList()
        }
    }

    // ── persistence ──

    private fun file(): java.io.File? = com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, FILE) }

    private fun load() {
        if (loaded) return
        if (com.lifecyclebot.AATEApp.appContextOrNull() == null) return   // V5.0.8086 — no store yet: latching now made the next save replace the file with empty state
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                file()?.takeIf { it.exists() }?.readLines()?.forEach { line ->
                    val f = line.split('\t')
                    when (f.getOrNull(0)) {
                        "S" -> if (f.size >= 6) states[f[1]] = State(f[2].toIntOrNull() ?: 0, f[3] == "1", f[4].toDoubleOrNull() ?: 0.0, f[5].toLongOrNull() ?: 0L)
                        "B" -> if (f.size >= 7) bars.getOrPut(f[1]) { ArrayDeque() }.addLast(DoubleArray(5) { i -> f[2 + i].toDoubleOrNull() ?: 0.0 })
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun saveMaybe(nowMs: Long, force: Boolean = false) {
        if (!force && nowMs - lastSaveMs < 5L * 60_000L) return
        lastSaveMs = nowMs
        val f = file() ?: return
        try {
            // Only open positions' state and bars are kept.
            val open = try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().map { it.mint }.toSet() } catch (_: Throwable) { null }
            if (open != null) {
                states.keys.removeIf { it.substringBefore('|') !in open }
                bars.keys.removeIf { it !in open }
            }
            val sb = StringBuilder()
            for ((k, s) in states) sb.append("S\t$k\t${s.taken}\t${if (s.promoted) 1 else 0}\t${s.peak}\t${s.peakAt}\n")
            for ((m, q) in bars) synchronized(q) { for (b in q) sb.append("B\t$m\t${b[0]}\t${b[1]}\t${b[2]}\t${b[3]}\t${b[4]}\n") }
            val tmp = java.io.File(f.parentFile, "$FILE.tmp")
            tmp.writeText(sb.toString())
            tmp.renameTo(f)
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        load()
        // V5.0.8136 — a confirmed 2x take is "banked"; a free ride is only what confirmed proceeds prove (label8066).
        return "holds=${states.size} banked=${states.values.count { it.taken >= 1 }} promoted=${promotions.get()} exitsHeld=${held.get()} slicesHeld=${partialsHeld.get()} " +
            "exits=[${exits.entries.joinToString(",") { "${it.key.removePrefix("LIFECYCLE_")}=${it.value.get()}" }.ifBlank { "-" }}] " +
            "tickets8073[${try { RunnerTicket8073.statusLine() } catch (_: Throwable) { "-" }}] " +
            "lanes=${LANES_8065.joinToString("/")} rule=floor -${FLOOR_PCT.toInt()}% · half at 2x · 30% at 5x/10x · 15m base/trend breaks · dead money 72h" +
            " · " + LifecycleEvidence8189.statusLine8189()
    }
}
