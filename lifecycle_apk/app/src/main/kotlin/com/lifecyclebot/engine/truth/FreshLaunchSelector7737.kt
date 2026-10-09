package com.lifecyclebot.engine.truth

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7737 §THE_RIGHT_LAUNCH_IN_THE_RIGHT_PHASE.
 *
 * Operator: "I still want it to hit fresh launches but it needs to be grabbing
 * the right tokens with the right metrics. tokens that are in the right phase
 * to grab a quick 50-100% profit."
 *
 * 5.0.7736 at 2972 s: live N=32, PF 0.50. The largest loss, 5F8gGB (pump.fun,
 * $32k, MOONSHOT), was bought and sold 72 seconds later at -49% through a
 * catastrophic stop. Over the same session the forward labeler measured
 * PROJECT_SNIPER candidates at +110.6% net an hour later with 40% runners and
 * SHITCOIN at +41% with 23%: fresh launches do run; the bot was buying the
 * wrong ones and not banking the ones that ran.
 *
 * WHAT A FRESH LAUNCH IS HERE. A token whose canonical birth (create event,
 * metadata creation or first pool, CanonicalTokenBirthTime7440) is under
 * [FRESH_MAX_AGE_MS_7737] old. Watchlist age is never birth age.
 *
 * THE SETUP. Each fresh launch is read into a signature from the launch-phase
 * authority's own tape (LaunchPhaseAuthority7401, no network):
 *
 *     phase | flow | concentration | multiple-from-create
 *
 * THE OUTCOME IT IS MEASURED ON. Not the 60-minute mark: a launch that prints
 * +60% at minute eight and is -50% at minute sixty was a winner for a quick
 * take and a loser for a hold. Every fresh launch FinalDecisionGate rules on,
 * admitted or refused, is followed for an hour and booked by FIRST TOUCH:
 * TP_FIRST (+[TP_PCT_7737]% gross before -[STOP_PCT_7737]%), STOP_FIRST (the
 * reverse) or NEITHER (the 60-minute net). One booking per mint per hour.
 *
 * THE DECISION (live only; paper buys everything so the table keeps filling).
 *   - Structural refusals, the shapes the 72-second stop came from: the dev
 *     selling, a 5-minute cascade, a sell-dominant tape, a one-wallet pump, a
 *     buy at three times the create price or more. Each is lifted by its own
 *     cell when that cell proves it is wrong (n >= [OVERTURN_MIN_N_7737],
 *     TP_FIRST >= 30% and above STOP_FIRST): pivot, never permanent.
 *   - A learned refusal: a setup cell with n >= [LEARNED_MIN_N_7737] that hits
 *     its stop first at least 45% of the time and half again as often as it
 *     hits +50% is refused. Below that the setup is admitted and learned
 *     (learn before tighten).
 *
 * THE EXIT. A live position whose entry was a fresh launch banks 60% at +50%
 * and half the rest at +100% (Executor.checkProfitLock); the remainder rides
 * the ordinary trail.
 */
object FreshLaunchSelector7737 {
    private const val FRESH_MAX_AGE_MS_7737 = 30L * 60_000L
    private const val TP_PCT_7737 = 50.0
    private const val STOP_PCT_7737 = 30.0
    private const val QUICK_TAKE_FIRST_FRACTION_7737 = 0.60
    private const val QUICK_TAKE_SECOND_FRACTION_7737 = 0.50
    private const val QUICK_TAKE_SECOND_PCT_7737 = 100.0
    private const val LEARNED_MIN_N_7737 = 20
    private const val OVERTURN_MIN_N_7737 = 30
    private const val EARLY_OVERTURN_N_7925 = 15
    private const val HORIZON_MS_7737 = 60L * 60_000L
    private const val LOST_GRACE_MS_7737 = 10L * 60_000L
    private const val MARK_MAX_GAP_MS_7737 = 120_000L
    private const val MAX_PENDING_7737 = 3_000
    private const val MAX_CELLS_7737 = 300
    private const val PREFS_7737 = "fresh_launch_selector_7737"
    /** V5.0.7867 — cells re-keyed: concentration and phase now exclude the creator's own buys. */
    internal const val CELLS_KEY_7867 = "cells_7867"

    data class Setup(val key: String, val structuralRefusal: String?, val ageMs: Long, val detail: String)

    /**
     * One setup cell. Units: counts, and summed 60-minute net percent for NEITHER.
     * V5.0.7742 — a second ladder, +50% before -[STOP15_PCT_7742]%, is measured on
     * the same observations (n15/tp15/stop15): the lane floors stop live
     * positions at -10..-15%, so a -30% ladder does not describe the trade the
     * bot actually takes. Rows written before 7742 decode with an empty ladder.
     */
    class Cell {
        var n = 0; var tpFirst = 0; var stopFirst = 0; var neither = 0; var sumNeitherNet = 0.0; var lost = 0
        var n15 = 0; var tp15 = 0; var stop15 = 0
        fun encode(): String = "$n,$tpFirst,$stopFirst,$neither,$sumNeitherNet,$lost,$n15,$tp15,$stop15"
        fun decode(s: String): Boolean {
            val f = s.split(',')
            if (f.size != 6 && f.size != 9) return false
            n = f[0].toIntOrNull() ?: 0; tpFirst = f[1].toIntOrNull() ?: 0; stopFirst = f[2].toIntOrNull() ?: 0
            neither = f[3].toIntOrNull() ?: 0; sumNeitherNet = f[4].toDoubleOrNull() ?: 0.0; lost = f[5].toIntOrNull() ?: 0
            if (f.size == 9) { n15 = f[6].toIntOrNull() ?: 0; tp15 = f[7].toIntOrNull() ?: 0; stop15 = f[8].toIntOrNull() ?: 0 }
            return true
        }
        val tpRate: Double get() = if (n > 0) tpFirst.toDouble() / n else 0.0
        val stopRate: Double get() = if (n > 0) stopFirst.toDouble() / n else 0.0
        /** Expected gross percent per trade on the -15% ladder (+50 x hit rate - 15 x stop rate). */
        val ev15Pct: Double get() = if (n15 > 0) (tp15 * TP_PCT_7737 - stop15 * STOP15_PCT_7742) / n15 else 0.0
    }

    private class Obs(val mint: String, val key: String, val admitted: Boolean, val entryPrice: Double, val costPct: Double, val atMs: Long) {
        @Volatile var lastPricedMs = atMs
        /** V5.0.7742 — the -15% ladder resolved (TP, STOP or NEITHER booked). */
        @Volatile var resolved15 = false
    }

    private const val STOP15_PCT_7742 = 15.0
    private const val LAUNCH_PLAN_MIN_N_7742 = 20

    private val cells = ConcurrentHashMap<String, Cell>()
    private val pending = ConcurrentHashMap<String, Obs>()          // key = mint
    private val lastObservedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var lastPersistMs = 0L
    private val observed = AtomicLong(0)
    private val bookedTp = AtomicLong(0)
    private val bookedStop = AtomicLong(0)
    private val bookedNeither = AtomicLong(0)
    private val lostCount = AtomicLong(0)
    private val refusedStructural = AtomicLong(0)
    private val refusedLearned = AtomicLong(0)
    private val overturned = AtomicLong(0)
    private val admittedFresh = AtomicLong(0)

    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val p = try { context.applicationContext.getSharedPreferences(PREFS_7737, Context.MODE_PRIVATE) } catch (_: Throwable) { return }
        prefs = p
        try {
            p.getString(CELLS_KEY_7867, null)?.split(';')?.forEach { row ->
                val sep = row.lastIndexOf('=')
                if (sep <= 0) return@forEach
                val c = Cell()
                if (c.decode(row.substring(sep + 1))) cells[row.substring(0, sep)] = c
            }
        } catch (_: Throwable) {}
    }

    private fun persist(force: Boolean = false) {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistMs < 60_000L) return
        lastPersistMs = now
        try {
            p.edit().putString(CELLS_KEY_7867, cells.entries.joinToString(";") { (k, c) -> "$k=${synchronized(c) { c.encode() }}" }).apply()
        } catch (_: Throwable) {}
    }

    // ── setup (pure over the token state and the launch authority) ──

    fun flowBucket(buyTx: Int, sellTx: Int, buyShare: Double, buyers: Int, accel: Boolean): String = when {
        buyTx + sellTx == 0 -> "FLOW_NONE"
        sellTx >= 3 && sellTx > buyTx -> "FLOW_SELL"
        buyShare >= 65.0 && buyers >= 4 && accel -> "FLOW_STRONG"
        buyShare >= 55.0 -> "FLOW_OK"
        else -> "FLOW_WEAK"
    }

    fun concentrationBucket(largestBuyerPct: Double, buyers: Int): String = when {
        buyers <= 0 -> "CONC_NA"
        // V5.0.7925 — one crowd buyer is 100% of the crowd by arithmetic: that is a
        // launch seconds old, not a one-wallet pump (5.0.7914: 63 refusals while the
        // selector's own best cell, PRE_IGNITION|FLOW_OK|CONC_ONE, ran ev15 +26%).
        buyers == 1 -> "CONC_EARLY"
        largestBuyerPct >= 60.0 && buyers < 4 -> "CONC_ONE"
        largestBuyerPct >= 40.0 -> "CONC_HIGH"
        else -> "CONC_BROAD"
    }

    private fun multipleBucket(multiple: Double?): String = when {
        multiple == null || !multiple.isFinite() -> "MX_NA"
        multiple < 1.5 -> "MX_LT1_5"
        multiple < 3.0 -> "MX_1_5_3"
        else -> "MX_GE3"
    }

    /** Pure: the structural refusal for one setup's raw facts, or null. */
    fun structuralRefusal(devSellTx: Int, chg5mPct: Double, flow: String, conc: String, multiple: Double?): String? = when {
        devSellTx > 0 -> "DEV_SELLING"
        chg5mPct <= -18.0 -> "FIVE_MINUTE_CASCADE"
        flow == "FLOW_SELL" -> "SELL_DOMINANT_TAPE"
        conc == "CONC_ONE" -> "ONE_WALLET_PUMP"
        multiple != null && multiple.isFinite() && multiple >= 3.0 -> "CHASING_3X_FROM_CREATE"
        else -> null
    }

    private fun setupFor(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Setup? {
        // V5.0.7738 — a launch whose birth has not hydrated yet is still judged.
        // V5.0.7767 — through the one launch-age rule (CanonicalTokenBirthTime7440).
        val age = try { CanonicalTokenBirthTime7440.launchAgeMs7767(ts, nowMs) } catch (_: Throwable) { null } ?: return null
        if (age > FRESH_MAX_AGE_MS_7737) return null
        val lp = try { LaunchPhaseAuthority7401.snapshot(ts, nowMs) } catch (_: Throwable) { null } ?: return null
        val flow = flowBucket(lp.buyTx60s, lp.sellTx60s, lp.buySharePct, lp.distinctBuyers60s, lp.accelerationRising)
        val conc = concentrationBucket(lp.largestBuyerSharePct60s, lp.distinctBuyers60s)
        val mult = multipleBucket(lp.createMultiple)
        val chg5m = ts.lastPriceChange5m.takeIf { it.isFinite() } ?: 0.0
        val key = "${lp.phase.name}|$flow|$conc|$mult"
        val refusal = structuralRefusal(lp.devSellTx60s, chg5m, flow, conc, lp.createMultiple)
        return Setup(key, refusal, age, lp.reason)
    }

    /**
     * Pure: the watchlist age of a token with no resolved birth that came from
     * a launch feed (pump.fun, PumpPortal, a new Raydium pool) under $300k, or
     * null. Watchlist age is never older than birth age, so a token this
     * returns null for may still be fresh, and one it times is at least that old.
     */
    fun unresolvedLaunchAgeMs7738(source: String, addedToWatchlistAt: Long, mcapUsd: Double, nowMs: Long): Long? {
        if (addedToWatchlistAt <= 0L) return null
        val src = source.uppercase()
        val launchFeed = src.contains("PUMP") || src.contains("NEW_POOL")
        if (!launchFeed) return null
        if (mcapUsd.isFinite() && mcapUsd >= 300_000.0) return null
        return (nowMs - addedToWatchlistAt).coerceAtLeast(0L)
    }

    private fun cellSnapshot(key: String): Cell? {
        val c = cells[key] ?: return null
        return synchronized(c) { Cell().also { it.decode(c.encode()) } }
    }

    /** Pure: does a measured cell overturn a structural refusal? */
    fun cellOverturns(c: Cell?): Boolean = c != null && (
        (c.n >= OVERTURN_MIN_N_7737 && c.tpRate >= 0.30 && c.tpFirst > c.stopFirst) ||
            // V5.0.7925 — a cell running take-profit-first at twice its stops overturns sooner.
            (c.n >= EARLY_OVERTURN_N_7925 && c.tpRate >= 0.40 && c.tpFirst >= 2 * c.stopFirst)
        )

    /** Pure: does a measured cell refuse its setup? */
    fun cellRefuses(c: Cell?): Boolean = c != null && c.n >= LEARNED_MIN_N_7737 && c.stopRate >= 0.45 && c.stopFirst >= 1.5 * c.tpFirst

    /** Live-entry verdict for a fresh launch, or null to admit (and for anything that is not a fresh launch). */
    fun liveBlockReason(ts: TokenState, lane: String, paper: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        if (paper) return null
        val s = setupFor(ts, nowMs) ?: return null
        val c = cellSnapshot(s.key)
        val reason = when {
            s.structuralRefusal != null && !cellOverturns(c) -> {
                refusedStructural.incrementAndGet()
                "FRESH_LAUNCH_${s.structuralRefusal}_7737"
            }
            // V5.0.7948 — a setup cell's stops-first record (20+ results) yields to a proven
            // plan cohort this launch sits in, measured on more labels (LiveEdgeGate7877).
            cellRefuses(c) && !LiveEdgeGate7877.cohortOverrulesSmaller7948(LiveEdgeGate7877.provenCohort7948(ts, nowMs), c?.n ?: 0) &&
                !com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners7953("FRESH_LAUNCH_SETUP_STOPS_FIRST_7737") -> {
                refusedLearned.incrementAndGet()
                "FRESH_LAUNCH_SETUP_STOPS_FIRST_7737"
            }
            else -> null
        }
        try {
            if (reason == null) {
                admittedFresh.incrementAndGet()
                if (s.structuralRefusal != null) {
                    overturned.incrementAndGet()
                    PipelineHealthCollector.labelInc("FRESH_LAUNCH_REFUSAL_OVERTURNED_BY_CELL_7737")
                }
                PipelineHealthCollector.labelInc("FRESH_LAUNCH_ADMITTED_7737")
            } else {
                PipelineHealthCollector.labelInc(reason)
                if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("FRESH_LAUNCH_REFUSED_7737", ts.mint)) {
                    ForensicLogger.lifecycle(
                        "FRESH_LAUNCH_REFUSED_7737",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$lane reason=$reason setup=${s.key} ageS=${s.ageMs / 1000} " +
                            "cell=${c?.let { "n=${it.n} tp=${it.tpFirst} stop=${it.stopFirst}" } ?: "n=0"} ${s.detail}",
                    )
                }
            }
        } catch (_: Throwable) {}
        return reason
    }

    /** True when a position opened at [entryTimeMs] on [mint] was a fresh-launch entry. */
    fun wasFreshAtEntry(mint: String, entryTimeMs: Long): Boolean {
        if (entryTimeMs <= 0L) return false
        val birth = try { CanonicalTokenBirthTime7440.resolve(mint) } catch (_: Throwable) { null } ?: return false
        val ageAtEntry = entryTimeMs - birth.birthMs
        return ageAtEntry in 0L..FRESH_MAX_AGE_MS_7737
    }

    /**
     * Pure: the fraction of the CURRENT holding the quick take sells at
     * [gainPct], given how many quick-take steps this position has already
     * banked, or 0.0. The live profit-lock path does not move partialSoldPct,
     * so the steps are counted here, per position.
     */
    fun quickTakeFraction(gainPct: Double, stepsDone: Int): Double = when {
        stepsDone <= 0 && gainPct >= TP_PCT_7737 -> QUICK_TAKE_FIRST_FRACTION_7737
        stepsDone == 1 && gainPct >= QUICK_TAKE_SECOND_PCT_7737 -> QUICK_TAKE_SECOND_FRACTION_7737
        else -> 0.0
    }

    private val quickTakeSteps = ConcurrentHashMap<String, Int>()
    private val quickTakeLastTryMs = ConcurrentHashMap<String, Long>()
    private const val QUICK_TAKE_RETRY_MS_7737 = 20_000L

    private fun positionKey(mint: String, entryTimeMs: Long) = "$mint|$entryTimeMs"

    fun quickTakeStepsDone(mint: String, entryTimeMs: Long): Int = quickTakeSteps[positionKey(mint, entryTimeMs)] ?: 0

    /** False while a failed attempt on this position is inside its retry gap. */
    fun quickTakeMayTry(mint: String, entryTimeMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - (quickTakeLastTryMs[positionKey(mint, entryTimeMs)] ?: 0L) >= QUICK_TAKE_RETRY_MS_7737

    /** Records one attempt; [banked] advances the step only when the holding actually shrank. */
    fun onQuickTakeAttempt(mint: String, entryTimeMs: Long, banked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val k = positionKey(mint, entryTimeMs)
        quickTakeLastTryMs[k] = nowMs
        if (banked) quickTakeSteps.merge(k, 1) { a, b -> a + b }
        if (quickTakeLastTryMs.size > 2_000) {
            val cutoff = nowMs - 24L * 60L * 60_000L
            quickTakeLastTryMs.entries.removeIf { it.value < cutoff }
            quickTakeSteps.keys.retainAll(quickTakeLastTryMs.keys)
        }
    }

    // ── observation and first-touch booking ──

    /** Called by ForwardReturnLabeler7731.observe for every verdict it starts a label on. */
    fun observe(ts: TokenState, admitted: Boolean, entryPrice: Double, costPct: Double, nowMs: Long) {
        if (!entryPrice.isFinite() || entryPrice <= 0.0) return
        val seen = lastObservedAt[ts.mint]
        if (seen != null && nowMs - seen < HORIZON_MS_7737) return
        if (pending.size >= MAX_PENDING_7737) return
        val s = setupFor(ts, nowMs) ?: return
        if (pending.putIfAbsent(ts.mint, Obs(ts.mint, s.key, admitted, entryPrice, costPct, nowMs)) != null) return
        lastObservedAt[ts.mint] = nowMs
        if (lastObservedAt.size > 12_000) lastObservedAt.entries.removeIf { nowMs - it.value > HORIZON_MS_7737 }
        observed.incrementAndGet()
    }

    private fun cellFor(key: String): Cell = cells.getOrPut(key) {
        if (cells.size >= MAX_CELLS_7737) {
            cells.entries.sortedBy { synchronized(it.value) { it.value.n } }.take(MAX_CELLS_7737 / 10).forEach { cells.remove(it.key, it.value) }
        }
        Cell()
    }

    /** V5.0.7742 — books the -15% ladder once per observation. */
    private fun book15(o: Obs, outcome: String) {
        if (o.resolved15) return
        o.resolved15 = true
        val c = cellFor(o.key)
        synchronized(c) {
            c.n15 += 1
            if (outcome == "TP") c.tp15 += 1 else if (outcome == "STOP") c.stop15 += 1
        }
    }

    private fun bookOutcome(o: Obs, outcome: String, net: Double) {
        val c = cellFor(o.key)
        synchronized(c) {
            when (outcome) {
                "TP" -> { c.n += 1; c.tpFirst += 1 }
                "STOP" -> { c.n += 1; c.stopFirst += 1 }
                "NEITHER" -> { c.n += 1; c.neither += 1; c.sumNeitherNet += net }
                else -> { c.lost += 1 }
            }
        }
        when (outcome) {
            "TP" -> bookedTp.incrementAndGet()
            "STOP" -> bookedStop.incrementAndGet()
            "NEITHER" -> bookedNeither.incrementAndGet()
            else -> lostCount.incrementAndGet()
        }
        try { PipelineHealthCollector.labelInc("FRESH_LAUNCH_FIRST_TOUCH_${outcome}_7737") } catch (_: Throwable) {}
        persist()
    }

    /**
     * Ticked from ForwardReturnLabeler7731.tick with its price lookup. Returns
     * the pending mints that had no price this tick, for the labeler's
     * off-watchlist batch.
     */
    fun tick(markFor: (String) -> Double?, nowMs: Long): List<String> {
        if (pending.isEmpty()) return emptyList()
        val unpriced = ArrayList<String>()
        for ((mint, o) in pending.entries.toList()) {
            val age = nowMs - o.atMs
            val px = markFor(mint)
            if (px == null || !px.isFinite() || px <= 0.0) {
                if (age >= HORIZON_MS_7737 + LOST_GRACE_MS_7737) { pending.remove(mint, o); bookOutcome(o, "LOST", 0.0) }
                else if (nowMs - o.lastPricedMs >= MARK_MAX_GAP_MS_7737 / 2) unpriced.add(mint)
                continue
            }
            o.lastPricedMs = nowMs
            if (ForwardReturnLabeler7731.basisSuspect7738(o.entryPrice, px)) { pending.remove(mint, o); bookOutcome(o, "LOST", 0.0); continue }
            val gross = (px / o.entryPrice - 1.0) * 100.0
            // V5.0.7742 — the -15% ladder first: a mark at -15% or below resolves it as a stop,
            // +50% as a target; whatever the -30% ladder books afterwards cannot rewrite it.
            if (gross <= -STOP15_PCT_7742) book15(o, "STOP") else if (gross >= TP_PCT_7737) book15(o, "TP")
            when {
                gross >= TP_PCT_7737 -> { pending.remove(mint, o); bookOutcome(o, "TP", gross - o.costPct) }
                gross <= -STOP_PCT_7737 -> { pending.remove(mint, o); bookOutcome(o, "STOP", gross - o.costPct) }
                age >= HORIZON_MS_7737 -> { pending.remove(mint, o); book15(o, "NEITHER"); bookOutcome(o, "NEITHER", gross - o.costPct) }
            }
        }
        return unpriced
    }

    enum class LaunchVerdict { NOT_FRESH, REFUSED, LEARNING, PROVEN, NEGATIVE }
    data class LaunchRead(val verdict: LaunchVerdict, val key: String, val why: String)

    /** Pure: the -15% ladder's verdict on a cell (null cell = no record yet). */
    fun ladderVerdict7742(c: Cell?, costPct: Double): LaunchVerdict = when {
        c == null || c.n15 < LAUNCH_PLAN_MIN_N_7742 -> LaunchVerdict.LEARNING
        c.ev15Pct - costPct > 0.0 -> LaunchVerdict.PROVEN
        else -> LaunchVerdict.NEGATIVE
    }

    /**
     * V5.0.7742 — the launch plan's read for a token too young for one-minute
     * bars: not fresh, refused on a structural crash shape its cell has not
     * overturned, or the -15% ladder's verdict for its setup.
     */
    /**
     * V5.0.7932 — is this launch in a cell the selector's own ladder (+50 / -15 in
     * 15 min, net of the round trip at this liquidity) has PROVEN? That is the only
     * measured positive edge in the book (5.0.7930: PRE_IGNITION|FLOW_OK|CONC_ONE
     * n15=27 ev15 +21.1%, EXPANDING|FLOW_NONE n15=30 +21.8%), and it was being
     * refused by 60-minute hold cohorts and the generic V3 score floor, which
     * measure a different trade (no ladder exit).
     */
    fun ladderProven7932(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        launchRead7742(ts, FieldManual7715.allInCostPct(10.0, ts.lastLiquidityUsd), nowMs).verdict == LaunchVerdict.PROVEN
    } catch (_: Throwable) { false }

    fun launchRead7742(ts: TokenState, costPct: Double, nowMs: Long = System.currentTimeMillis()): LaunchRead {
        val s = setupFor(ts, nowMs) ?: return LaunchRead(LaunchVerdict.NOT_FRESH, "", "NOT_FRESH")
        val c = cellSnapshot(s.key)
        if (s.structuralRefusal != null && !cellOverturns(c)) return LaunchRead(LaunchVerdict.REFUSED, s.key, s.structuralRefusal)
        val v = ladderVerdict7742(c, costPct)
        val why = if (c == null) "n15=0" else "n15=${c.n15} ev15=${"%+.1f".format(c.ev15Pct)}%"
        return LaunchRead(v, s.key, why)
    }

    /**
     * V5.0.7871 — the setup key and a copy of its measured cell for a fresh
     * launch (null when the token is not one), for LaunchEntryShaping7871.
     */
    fun shapingRead7871(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Pair<String, Cell?>? {
        val s = setupFor(ts, nowMs) ?: return null
        return s.key to cellSnapshot(s.key)
    }

    fun statusLine(): String {
        val top = cells.entries.mapNotNull { (k, c) -> cellSnapshot(k)?.let { k to it } }
            .filter { it.second.n >= 5 }
            .sortedByDescending { it.second.tpRate - it.second.stopRate }
        val fmt = { p: Pair<String, Cell> -> "${p.first}[n=${p.second.n} tp=${"%.0f".format(p.second.tpRate * 100)}% stop=${"%.0f".format(p.second.stopRate * 100)}% n15=${p.second.n15} ev15=${"%+.1f".format(p.second.ev15Pct)}%]" }
        return "pending=${pending.size} observed=${observed.get()} firstTouch[tp=${bookedTp.get()} stop=${bookedStop.get()} neither=${bookedNeither.get()} lost=${lostCount.get()}] " +
            "live[admitted=${admittedFresh.get()} refusedStructural=${refusedStructural.get()} refusedLearned=${refusedLearned.get()} overturned=${overturned.get()}] cells=${cells.size}\n" +
            "      bestSetups: ${top.take(3).joinToString(" · ") { fmt(it) }.ifBlank { "none at n>=5" }}\n" +
            "      worstSetups: ${top.reversed().take(3).joinToString(" · ") { fmt(it) }.ifBlank { "none at n>=5" }}"
    }

    fun persistNow7737() { persist(force = true) }
}
