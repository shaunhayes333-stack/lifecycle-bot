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
 *    position from entry; V5.0.8873: from 10x a quarter of what remains at 10x, 30x and 100x, 22 % at 300x, so a fifth
 *    of the original rides the trail and the bag that sees 30x-600x is no longer sold at 10x;
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
    fun trailBreached8630(lane: String?, pnlPct: Double, peakPct: Double, stopPct: Double = stopFor8719(lane),
                          armPct: Double = TRAIL_ARM_PEAK_PCT_8630): Boolean {   // V5.0.8941 — a launch rider arms at 10x
        if (!rides8584(lane) || !pnlPct.isFinite() || !peakPct.isFinite() || peakPct < armPct) return false
        return 1.0 + pnlPct / 100.0 <= lineWithStop8719(1.0 + peakPct / 100.0, stopPct)
    }

    /**
     * V5.0.8759 §THE_TRAIL_SELLS_ON_TWO_MARKS — code audit (exits F9): the trail priced its line off the stored
     * peakGainPct, which has held one-print phantom peaks (8642: "+12,105 %, realised -3 %"); one bad mark at or under
     * the resulting line sold a runner whole. The trail now arms and prices on the highest gain two consecutive distinct
     * marks agree on ([twoMarkPeak8759]), capped by the stored peak, and sells only when two consecutive distinct marks
     * are at or under the line. A mark is distinct when its write time ([markAtMs]) changed; marks more than
     * [TWO_MARK_WINDOW_MS_8759] apart do not pair.
     */
    const val TWO_MARK_WINDOW_MS_8759 = 30_000L
    private class Trail8759(var prevPnl: Double, var prevMarkAt: Long, var peak: Double)
    private val trail8759 = ConcurrentHashMap<String, Trail8759>()

    /** Pure: the peak once a mark at [pnlPct] follows one at [prevPnl] — the lower of the two is what both agree on. */
    fun twoMarkPeak8759(peak: Double, prevPnl: Double, pnlPct: Double): Double =
        if (prevPnl.isFinite() && pnlPct.isFinite()) maxOf(peak, minOf(prevPnl, pnlPct)) else peak

    /** BotService.doctrineTrail8630: is the trail due on position [key] at this mark? */
    fun trailDue8759(key: String, lane: String?, pnlPct: Double, storedPeakPct: Double, markAtMs: Long,
                     armPct: Double = TRAIL_ARM_PEAK_PCT_8630): Boolean {
        if (!rides8584(lane) || !pnlPct.isFinite()) return false
        if (trail8759.size > 2_000) trail8759.clear()
        val s = trail8759.getOrPut(key) { Trail8759(Double.NaN, 0L, Double.NEGATIVE_INFINITY) }
        synchronized(s) {
            if (markAtMs == s.prevMarkAt) return false   // the same mark again: nothing new to pair
            val paired = s.prevPnl.isFinite() && markAtMs - s.prevMarkAt in 1L..TWO_MARK_WINDOW_MS_8759
            val prev = s.prevPnl
            if (paired) s.peak = twoMarkPeak8759(s.peak, prev, pnlPct)
            s.prevPnl = pnlPct
            s.prevMarkAt = markAtMs
            if (!paired) return false
            val peak = if (storedPeakPct.isFinite()) minOf(storedPeakPct, s.peak) else s.peak
            return trailBreached8630(lane, pnlPct, peak, armPct = armPct) && trailBreached8630(lane, prev, peak, armPct = armPct)
        }
    }

    /**
     * V5.0.8719 §A_WINNER_GETS_THE_ROOM_ITS_STOP_GAVE_IT — owner: "are we starting to narrow into the 4x and 10x runners".
     * 8712, 24 h: 60 coins went 4x+, live held 5 of them and sold every one early; no ladder rung has ever triggered
     * (runnerFunnel8331 rungTriggered=0). The session's two MOONSHOT winners: AJFnmp peaked about +31 % and was sold at
     * +15.7 %, 5v5zdV was sold 43 s after the buy at +13.9 % — both by this trail. Below 2x the line kept half the peak
     * gain, so from a +28 % peak an 11 % dip sold the position, while the same position's own stop (cost + twice its
     * one-minute range, 25 % here; noise1m 3.9 %) had allowed a 25 % dip a minute earlier. A winner was given less than
     * half the room it had at entry, and a memecoin does not get to 2x without a pullback of its own noise.
     *
     * Below a 2x peak the line is now the LOWER of half the peak gain and the peak less the position's own stop distance,
     * never under +5 % (unchanged: once armed, a winner is not given back to a loss). From 2x the 45 % give-back trail is
     * unchanged, and the two meet at 2x. The paper book runs the same line, so its record measures the change.
     */
    private const val RAMP_FROM_8784 = 1.8

    fun lineWithStop8719(peakMult: Double, stopPct: Double, keep: Double = KEEP_8584): Double {
        val half = line8584(peakMult, keep)
        if (!stopPct.isFinite() || stopPct <= 0.0) return half
        val withStop = peakMult * (1.0 - stopPct.coerceAtMost(95.0) / 100.0)
        // V5.0.8769 — code audit (exits F10): the line jumped at 2x for stops over 25 % (cost-widened runner stops reach 40 %):
        // at 1.99x it sat at +19..+42 %, at 2.00x at +50 %, so a position at +45 % whose peak ticked to 2x was sold at once.
        // V5.0.8784 — the doctrine from 2x (the 1.5x floor, then the 45 % give-back) is unchanged; between 1.8x and 2x the
        // stop-widened line rises evenly to it, so the two meet at 2x for every stop (8769 lowered the 2x floor instead).
        if (peakMult >= 2.0) return half
        val base = maxOf(1.05, minOf(half, withStop))
        if (peakMult <= RAMP_FROM_8784) return base
        val t = (peakMult - RAMP_FROM_8784) / (2.0 - RAMP_FROM_8784)
        return minOf(half, base + (line8584(2.0, keep) - base) * t).coerceAtLeast(base)
    }

    /** The stop distance (%) a position of [lane] carries: the lane's stop at its measured round trip; NaN when unreadable. */
    private fun stopFor8719(lane: String?): Double =
        try { com.lifecyclebot.engine.truth.FieldManual7715.laneStopPct8392(foldLane8630(lane)) } catch (_: Throwable) { Double.NaN }

    /**
     * V5.0.8979 §SHOW_THE_EXIT_THAT_FIRES — owner, 24 Nov 05:27 (tur, MOONSHOT live, peak +65 %, card "STOP / LOCK: lock +60 %",
     * held at +19.3 %): "profit locks aren't firing". The card's lock was FluidLearningAI.getDynamicFluidStop at a 20 % default,
     * a number no exit of a doctrine position uses: since 8630 a meme position's profit exits are the doctrine's (the tick's
     * fluid lock defers to it, BotService doctrineOwns8769), and since 8941 a launch rider (a pump.fun mint bought within 15
     * minutes of its birth) holds every profit exit above its lane stop until a 10x peak. The card now names the exit that
     * fires: a rider below 10x "rides to 10x · SL x %", any other doctrine position below its +25 % arm "SL x % · trail from
     * +25 %", and once armed "trail +y %", the doctrine line ([lineWithStop8719] on the lane's stop distance) the trail sells at.
     * Pure; null when the doctrine does not own [lane]'s profit exits (the card shows what it showed before).
     */
    fun lockText8979(lane: String?, rider: Boolean, pnlPct: Double, peakPct: Double, stopPct: Double, laneStopPct: Double? = null): String? {
        if (!rides8584(lane)) return null
        val stopTxt = when {
            laneStopPct != null && laneStopPct.isFinite() && laneStopPct < 0.0 -> "SL ${laneStopPct.toInt()}%"
            stopPct.isFinite() && stopPct > 0.0 -> "SL -${stopPct.toInt()}%"
            else -> "SL —"
        }
        val peak = listOf(peakPct, pnlPct).filter { it.isFinite() }.maxOrNull() ?: return stopTxt
        val arm = if (rider) LAUNCH_RIDE_FROM_PCT_8941 else TRAIL_ARM_PEAK_PCT_8630
        if (peak >= arm) return "trail +${((lineWithStop8719(1.0 + peak / 100.0, stopPct) - 1.0) * 100.0).toInt()}%"
        return if (rider) "rides to 10x · $stopTxt" else "$stopTxt · trail from +${arm.toInt()}%"
    }

    /** V5.0.8979 — [ts]'s STOP / LOCK text under the doctrine at [pnlPct] / [peakPct]; null when the doctrine does not own it. */
    fun lockText8979(ts: com.lifecyclebot.data.TokenState, pnlPct: Double, peakPct: Double): String? = try {
        val lane = entryLane8630(ts)
        if (!rides8584(lane)) null else {
            val moonshotStop = if (foldLane8630(lane) == "MOONSHOT") try { com.lifecyclebot.v3.scoring.MoonshotTraderAI.stopFor(ts.mint) } catch (_: Throwable) { null } else null
            lockText8979(lane, launchRider8941(ts), pnlPct, peakPct, stopFor8719(lane), moonshotStop)
        }
    } catch (_: Throwable) { null }

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

    /**
     * V5.0.8888 — the share of a 2x-plus peak multiple the line keeps (a 45 % give-back). The live doctrine always uses it;
     * LaneExitGrade8888 grades other shares beside it on the same paths ([line8584] / [lineWithStop8719] / [paperStopLevel8584]
     * take it as a parameter whose default is this, so every live call is unchanged, bit for bit).
     */
    const val KEEP_8584 = 0.55

    /** Pure: the price multiple a profit exit must fall to before it may sell, given the peak multiple [peakMult]. */
    fun line8584(peakMult: Double, keep: Double = KEEP_8584): Double =
        if (peakMult < 2.0) maxOf(1.05, 1.0 + 0.5 * (peakMult - 1.0)) else maxOf(1.5, peakMult * keep)

    /**
     * Pure: is the exit [reason] of a [lane] position at [pnlPct] (peak [peakPct]) held by the doctrine? The doctrine's own
     * ladder rungs (SPIKE_CAPTURE_7943) and every never-held exit pass; nothing at or below the line is held.
     */
    fun holds8584(lane: String?, reason: String?, pnlPct: Double, peakPct: Double, stopPct: Double = stopFor8719(lane),
                  rider8941: Boolean = false, heldMs8941: Long = 0L): Boolean {
        if (!rides8584(lane) || reason.isNullOrBlank() || !pnlPct.isFinite()) return false
        if (ownExit8630(reason)) return false
        val r = reason.uppercase()
        if (r.contains("SPIKE_CAPTURE_7943")) return false
        if (NEVER_HELD_8584.any { r.contains(it) }) return false
        if (try { Tier1Deference8141.tier1(reason) } catch (_: Throwable) { false }) return false
        val now = 1.0 + pnlPct / 100.0
        val peak = 1.0 + maxOf(if (peakPct.isFinite()) peakPct else pnlPct, pnlPct) / 100.0
        if (peak < 1.5 && TIME_EXIT_8584.any { r.contains(it) }) return false
        // V5.0.8941 — a launch rider below a 10x peak: every profit exit is held while the price is above the lane stop (the
        // stop, every safety exit and the trail from 10x still sell); its time exit passes once the hour is up
        if (rider8941 && stopPct.isFinite() && stopPct > 0.0 && peak < 1.0 + LAUNCH_RIDE_FROM_PCT_8941 / 100.0) {
            if (heldMs8941 >= LAUNCH_HOUR_MS_8941 && TIME_EXIT_8584.any { r.contains(it) }) return false
            return now > 1.0 - stopPct.coerceAtMost(95.0) / 100.0
        }
        return now > lineWithStop8719(peak, stopPct)
    }

    /**
     * V5.0.8941 §LAUNCH_RUNNERS_RIDE — owner, 22 Nov 15:07: "your entire focus more the moment is now capturing these big
     * winners as early as possible ... how to extract the maximum amount of profits from these runners". The device's own
     * launch paths (13 launch-hour exports, 14-20 Nov: 9,688 pump.fun launches in 10 s bars; each entered at its 20th print,
     * the moment the coin itself shows 20 prints, so no hindsight): 9.5 % of them ran 4x+ after entry and those made 166 %
     * of all net; this doctrine as it runs live (rungs 25 % at 2x and 18 % at 5x of what remains, the trail armed at +25 %
     * on the stop-widened line, a 25 % stop) against the same with its rungs and trail from 10x:
     *   fill at the next bar, 3 % cost: +7.3 % -> +11.0 % (n8144; halves +8.5/+6.2 -> +14.5/+7.5)
     *   20 s entry lag, 3 % cost:       +3.5 % -> +6.9 % (n6234; halves +4.1/+3.0 -> +9.0/+4.8)
     *   20 s lag, 4 % cost, every sell a bar late at that bar's low, 3 % slip on rungs: -8.8 % -> -4.1 %
     *     (halves -9.8/-7.7 -> -3.9/-4.4)
     * A launch pulls back hard on its way to 4x-10x: the +25 % trail and the 2x / 5x rungs sell it on the way. Off-pump base
     * coins are the other way round (the base pocket +37.6 % under these exits against +29.1 % held, n156), so only a
     * launch rider changes: a pump.fun mint bought within [LAUNCH_ENTRY_MAX_MS_8941] of its resolved chain birth. Its rungs
     * below 10x wait (SpikeCapture7943), its trail arms at 10x (BotService.doctrineTrail8630) on the doctrine's own line, its
     * other profit exits are held above the lane stop below 10x and its time exit passes at the hour. The lane stop, every
     * safety / rug / liquidity / LP-pull exit, the kill switch and every tier-1 emergency are unchanged. Unresolved birth:
     * not a rider (the doctrine as before).
     */
    private const val LAUNCH_ENTRY_MAX_MS_8941 = 15L * 60_000L
    const val LAUNCH_RIDE_FROM_PCT_8941 = 900.0
    private const val LAUNCH_HOUR_MS_8941 = 60L * 60_000L

    /** Pure (V5.0.8941): is a position on [mint] bought [entryAgeMs] after the coin's chain birth a launch rider? */
    fun launchRider8941(mint: String, entryAgeMs: Long?): Boolean =
        mint.trim().endsWith("pump", ignoreCase = true) && entryAgeMs != null && entryAgeMs in 0L..LAUNCH_ENTRY_MAX_MS_8941

    /** V5.0.8941 — [ts]'s open position: is it a launch rider (its entry age read from the resolved chain birth)? */
    fun launchRider8941(ts: com.lifecyclebot.data.TokenState, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        val entry = ts.position.entryTime
        val ageMin = com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, nowMs)
        if (entry <= 0L || ageMin == null || !ageMin.isFinite()) false
        else launchRider8941(ts.mint, entry - (nowMs - (ageMin * 60_000.0).toLong()))
    } catch (_: Throwable) { false }

    /** V5.0.8941 — the trail's arming peak for [ts]: 10x for a launch rider, else the doctrine's +25 %. */
    fun trailArm8941(ts: com.lifecyclebot.data.TokenState): Double =
        if (launchRider8941(ts)) LAUNCH_RIDE_FROM_PCT_8941 else TRAIL_ARM_PEAK_PCT_8630

    /** V5.0.8941 — the rungs below 10x wait for a launch rider: the ladder index the rider's ladder counts from. */
    fun riderRungFloor8941(tiers: List<Pair<Double, Double>>): Int = tiers.count { it.first < LAUNCH_RIDE_FROM_PCT_8941 }

    /**
     * MemePaperTrader8544: the paper book's stop level (gross %) under the doctrine — the lane stop until the peak reaches
     * +10 %, then the doctrine line.
     */
    fun paperStopLevel8584(stopPct: Double, peakPct: Double, keep: Double = KEEP_8584): Double =
        if (peakPct.isFinite() && peakPct >= 10.0) (lineWithStop8719(1.0 + peakPct / 100.0, stopPct, keep) - 1.0) * 100.0 else -stopPct

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
        val ladder = RunnerGrab7967.TIERS_7967.joinToString("/") { (pct, f) -> "+${pct.toInt()}%:${(f * 100.0).toInt()}" }
        return "runnerDoctrine8584[on=$enabled8584 held=${heldCount8584()} {$top} ladder=$ladder-of-remainder (8873) " +
            "line<2x=lower of half the peak gain and peak less the stop distance (>=+5%, 8719) >=2x=45% give-back (>=1.5x); stops/safety pass; tuner never shrinks meme exits]"
    }
}
