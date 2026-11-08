package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8584 §RUNNER_DOCTRINE — owner, 485eacbd log: "the pipeline self adjustments are too aggressive. its still not making
 * money. the goal is 5x and 10x, not 5 %." Live 8W/12L PF 0.55; the average winning position closed at +27.7 % while a
 * MOONSHOT winner was sold by partial_25pct, partial_43pct, TAIL_SET_ASIDE and RAPID_DRAWDOWN_FROM_PEAK inside four minutes.
 *
 * One exit doctrine for the meme launch lanes, enforced at both sell doors (Executor.moonbagGate7322 for a full exit,
 * Executor.requestPartialSellWithDelivery8527 for a slice), so no tuner, take-profit, partial ladder, tail bag or
 * peak-drawdown lock can shrink a winner again:
 *  - the ladder is RunnerGrab7967.TIERS_7967 (+100 % sells 25 %, +400 % sells 18 %, +900 % sells 67 % of what remains) for every meme
 *    position from entry;
 *  - any other profit exit is held while the price stays above the doctrine line: below a 2x peak the line keeps half the
 *    peak gain (never under +5 %), from a 2x peak it is a 45 % give-back trail of the multiple (never under 1.5x);
 *  - stops, safety, liquidity, rug, kill switch and every tier-1 emergency always pass, and nothing is held at or below
 *    +5 %, so the loss side is unchanged;
 *  - a time exit on a coin that never reached +50 % passes (a flat coin is not a runner).
 * The lane exit tuner may widen a meme lane's targets and stops but no longer shrinks them ([tunerFloor8584]).
 */
object RunnerDoctrine8584 {
    @Volatile var enabled8584: Boolean = true

    /** The launch / meme lanes the doctrine owns (established-coin lanes keep their own exits). */
    val MEME_LANES_8584: Set<String> = setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "DIP_HUNTER")

    /** Exits the doctrine never holds: safety, structure, stops, liquidity, operator and accounting integrity. */
    private val NEVER_HELD_8584 = listOf(
        "HARD_FLOOR", "CATASTROPH", "RUG", "STOP_LOSS", "STRICT_SL", "HARD_STOP", "MANUAL", "EMERGENCY", "DRAIN", "LIQUIDAT",
        "KILL", "HONEYPOT", "CANNOT_SELL", "UNSELLABLE", "RECONCIL", "INVARIANT", "ORPHAN", "COLLAPS", "FROZEN", "FREEZE",
        "LIQUIDITY_PULLED", "LIQ_PULL", "DEAD_TOKEN", "SAFETY", "MAYHEM", "HALT", "SHUTDOWN", "QUARANT", "DUST", "WALLET",
        "STOP_FLOOR", "STRUCTURE_STOP", "INVALIDAT", "BASE_BROKE", "DEV_DUMP", "DEV_SELL", "DEV_SOLD",
    )
    private val TIME_EXIT_8584 = listOf("TIME", "MAX_HOLD", "STALE", "EXPIRED", "TIMEOUT", "FLAT")

    fun memeLane8584(lane: String?): Boolean = !lane.isNullOrBlank() && foldLane8630(lane) in MEME_LANES_8584

    /**
     * V5.0.8630 — the doctrine keys on the lane the position was BOUGHT in, folded. It matched the raw tradingMode, so every
     * MOONSHOT_<mode> position and every STANDARD relabel (the 8622 session's only green trade, bought as SHITCOIN, ran as
     * STANDARD and a +8 % profit lock sold it) had no doctrine at all (audit_exits_now §0.3).
     */
    fun foldLane8630(lane: String?): String {
        val d = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.desk8392(lane) } catch (_: Throwable) { lane.orEmpty().trim().uppercase() }
        return if (d.startsWith("MOONSHOT_")) "MOONSHOT" else d
    }

    /** The position's entry lane: the canonical position's own lane (immutable), else its trading mode. */
    fun entryLane8630(ts: com.lifecyclebot.data.TokenState): String {
        val canonical = try {
            val pid = com.lifecyclebot.engine.truth.ExecutorCanonicalMirror6442.positionIdOf(ts.mint, ts.position.isPaperPosition)
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.getPosition(pid)?.lane
        } catch (_: Throwable) { null }
        return canonical?.takeIf { it.isNotBlank() && !it.equals("STANDARD", ignoreCase = true) } ?: ts.position.tradingMode
    }

    /**
     * V5.0.8630 — the doctrine SELLS, not only holds: once a doctrine position's confirmed peak reached
     * [TRAIL_ARM_PEAK_PCT_8630], falling to the doctrine line sells the whole remainder ([TRAIL_REASON_8630]). Before this
     * the doctrine held profit exits above its line and relied on whatever other exit happened to fire below it.
     */
    const val TRAIL_REASON_8630 = "RUNNER_DOCTRINE_TRAIL_8630"
    const val TRAIL_ARM_PEAK_PCT_8630 = 25.0

    /** Pure: has a doctrine position at [pnlPct] after a peak of [peakPct] fallen to its line? */
    fun trailBreached8630(lane: String?, pnlPct: Double, peakPct: Double): Boolean {
        if (!rides8584(lane) || !pnlPct.isFinite() || !peakPct.isFinite() || peakPct < TRAIL_ARM_PEAK_PCT_8630) return false
        return 1.0 + pnlPct / 100.0 <= line8584(1.0 + peakPct / 100.0)
    }

    /** Pure: is [reason] one of the doctrine's own exits (never held, deferred or banked as a partial by another door)? */
    fun ownExit8630(reason: String?): Boolean = reason?.trim()?.uppercase()?.startsWith("RUNNER_DOCTRINE_") == true

    /**
     * V5.0.8630 — profit-taking on a doctrine position is owned by the doctrine (ladder + trail). Executor.checkProfitLock
     * (fresh-launch quick take 60 % at +50 %, capital recovery at 1.5-3x, wallet-growth harvest, PROFIT_LOCK_5X, ultra bank)
     * and Executor.checkPartialSell (milestones +50 / +150 / +1000 %) never consulted the doctrine (audit_exits_now §0.2).
     */
    fun profitTakingOwned8630(lane: String?): Boolean = rides8584(lane)

    /** Pure: does the position in [lane] ride the doctrine ladder from entry? */
    fun rides8584(lane: String?): Boolean = enabled8584 && memeLane8584(lane)

    /** Pure: the price multiple a profit exit must fall to before it may sell, given the peak multiple [peakMult]. */
    fun line8584(peakMult: Double): Double =
        if (peakMult < 2.0) maxOf(1.05, 1.0 + 0.5 * (peakMult - 1.0)) else maxOf(1.5, peakMult * 0.55)

    /**
     * Pure: is the exit [reason] of a [lane] position at [pnlPct] (peak [peakPct]) held by the doctrine? The doctrine's own
     * ladder rungs (SPIKE_CAPTURE_7943) and every never-held exit pass; nothing at or below the line is held.
     */
    fun holds8584(lane: String?, reason: String?, pnlPct: Double, peakPct: Double): Boolean {
        if (!rides8584(lane) || reason.isNullOrBlank() || !pnlPct.isFinite()) return false
        if (ownExit8630(reason)) return false
        val r = reason.uppercase()
        if (r.contains("SPIKE_CAPTURE_7943")) return false
        if (NEVER_HELD_8584.any { r.contains(it) }) return false
        if (try { Tier1Deference8141.tier1(reason) } catch (_: Throwable) { false }) return false
        val now = 1.0 + pnlPct / 100.0
        val peak = 1.0 + maxOf(if (peakPct.isFinite()) peakPct else pnlPct, pnlPct) / 100.0
        if (peak < 1.5 && TIME_EXIT_8584.any { r.contains(it) }) return false
        return now > line8584(peak)
    }

    /**
     * MemePaperTrader8544: the paper book's stop level (gross %) under the doctrine — the lane stop until the peak reaches
     * +10 %, then the doctrine line.
     */
    fun paperStopLevel8584(stopPct: Double, peakPct: Double): Double =
        if (peakPct.isFinite() && peakPct >= 10.0) (line8584(1.0 + peakPct / 100.0) - 1.0) * 100.0 else -stopPct

    /** LaneExitTuner: a meme lane's take-profit / stop multiplier may widen, never shrink. */
    fun tunerFloor8584(lane: String?, mult: Double): Double =
        if (rides8584(lane) && mult.isFinite()) maxOf(1.0, mult) else mult

    private val held8584 = ConcurrentHashMap<String, AtomicLong>()

    fun noteHeld8584(door: String, lane: String?, reason: String) {
        val key = "$door:${(lane ?: "?").trim().uppercase()}:${reason.substringBefore('_').take(24)}"
        held8584.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RUNNER_DOCTRINE_${door}_HELD_8584") } catch (_: Throwable) {}
    }

    fun heldCount8584(): Long = held8584.values.sumOf { it.get() }

    /** Brief: the exits the doctrine held this session, most often first. */
    fun statusLine8584(): String {
        val top = held8584.entries.sortedByDescending { it.value.get() }.take(8)
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "runnerDoctrine8584[on=$enabled8584 held=${heldCount8584()} {$top} ladder=+100%:25/+400%:18/+900%:67-of-remainder " +
            "line<2x=half the peak gain (>=+5%) >=2x=45% give-back (>=1.5x); stops/safety pass; tuner never shrinks meme exits]"
    }
}
