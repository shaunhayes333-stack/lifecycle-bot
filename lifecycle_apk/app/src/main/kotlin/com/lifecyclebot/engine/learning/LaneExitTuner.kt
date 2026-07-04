package com.lifecyclebot.engine.learning

import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.9.1379 — LANE EXIT TUNER (closed-loop exit-ladder auto-tuning).
 *
 * Closes the gap where V3 meme lanes (MOONSHOT/SHITCOIN/TREASURY/BLUECHIP/
 * MANIPULATED/SNIPER/QUALITY) read STATIC take-profit/stop-loss literals that
 * FluidLearningAI only lerps along a global bootstrap->mature curve (and leaves
 * high-upside modes unchanged). Nothing moved a lane's TP/SL based on how that
 * lane ACTUALLY performed. This makes the exit ladder a real feedback loop.
 *
 * Emits two BOUNDED multipliers per lane from realized outcomes:
 *   tpMult in [0.60,1.40] applied to the lane base take-profit
 *   slMult in [0.70,1.30] applied to the MAGNITUDE of the lane base stop
 *
 * DOCTRINE GUARANTEES:
 *  - The -15% unconditional hard floor is NEVER touched here. slMult only scales
 *    a lane's own (tighter-than-floor) stop; the caller still clamps to -15%.
 *  - Soft-shape only: never vetoes/zeroes a trade. Fail-open: errors return 1.0.
 *  - Bootstrap-safe: requires MIN_SAMPLE matured closes per lane before nudging
 *    (doctrine #5 - read the curve, not the noise).
 *  - Persisted via LearningPersistence (sacred-persistence rule).
 */
object LaneExitTuner {

    private const val TP_MIN = 0.60
    private const val TP_MAX = 1.40
    private const val SL_MIN = 0.70
    private const val SL_MAX = 1.30
    private const val STEP   = 0.04

    // V5.0.6044 — LOWERED FROM 20 TO 8 (operator throughput doctrine).
    // Report 2026-07-03 showed BLUECHIP n=9, MOONSHOT n=17, SHITCOIN n=7 all
    // stuck at neutral tpMult=1.00/slMult=1.00 because they hadn't crossed
    // the old n>=20 threshold. Bot was accumulating losses at these lanes'
    // hardcoded defaults with no closed-loop tuning kicking in. Lower the
    // sample floor so tuning engages earlier — still bootstrap-safe (needs
    // n>=8 real closes, not noise), but stops the bleed while lanes learn.
    private const val WINDOW       = 60
    private const val MIN_SAMPLE   = 8
    private const val RECALC_EVERY = 5

    private data class Outcome(
        val pnlPct: Double,
        val peakPct: Double,
        val win: Boolean,
        val stopHit: Boolean,
    )

    private class LaneState {
        val window = ArrayDeque<Outcome>()
        var sinceRecalc = 0
        var lifetimeCloses = 0L
        @Volatile var tpMult = 1.0
        @Volatile var slMult = 1.0
    }

    data class ReplayBias(
        val profile: String,
        val tpMult: Double,
        val slMult: Double,
        val netSol: Double,
        val n: Int,
    )

    private val lanes = ConcurrentHashMap<String, LaneState>()
    @Volatile private var replayBiasByLane: Map<String, ReplayBias> = emptyMap()
    @Volatile private var replayBiasAtMs: Long = 0L
    private val replayBiasInFlight = AtomicBoolean(false)

    private fun canon(lane: String): String {
        val u = lane.uppercase()
        return when {
            u.contains("MOONSHOT")                          -> "MOONSHOT"
            u.contains("MANIPUL")                           -> "MANIPULATED"
            u.contains("EXPRESS")                            -> "EXPRESS"
            u.contains("SHITCOIN")                           -> "SHITCOIN"
            u.contains("CYCLIC")                             -> "CYCLIC"
            u.contains("TREASURY") || u.contains("CASH")    -> "TREASURY"
            u.contains("PRESALE") || u.contains("SNIPER")   -> "PRESALE_SNIPE"
            u.contains("QUALITY")                            -> "QUALITY"
            u.contains("BLUE")                               -> "BLUECHIP"
            u.contains("DIP")                                -> "DIP_HUNTER"
            else                                            -> "STANDARD"
        }
    }

    private fun refreshReplayBiasAsync(reason: String = "close") {
        val now = System.currentTimeMillis()
        if (now - replayBiasAtMs < 60_000L && replayBiasByLane.isNotEmpty()) return
        if (!replayBiasInFlight.compareAndSet(false, true)) return
        kotlinx.coroutines.GlobalScope.launch(com.lifecyclebot.engine.AppDispatchers.sideEffect) {
            try {
                val best = com.lifecyclebot.engine.LaneStrategyEvaluator.bestPerLane()
                replayBiasByLane = best.mapNotNull { (lane, r) ->
                    val b = when (r.profile) {
                        "TIGHT_STOP_-5" -> ReplayBias(r.profile, tpMult = 0.92, slMult = 0.70, netSol = r.netSol, n = r.n)
                        "FLOOR_-15_LETRUN" -> ReplayBias(r.profile, tpMult = 1.10, slMult = 1.00, netSol = r.netSol, n = r.n)
                        "FLOOR_-15_TRAIL25" -> ReplayBias(r.profile, tpMult = 1.16, slMult = 1.08, netSol = r.netSol, n = r.n)
                        "EARLY_TP_+30" -> ReplayBias(r.profile, tpMult = 0.78, slMult = 0.85, netSol = r.netSol, n = r.n)
                        "NO_TRADE" -> ReplayBias(r.profile, tpMult = 0.72, slMult = 0.70, netSol = r.netSol, n = r.n)
                        else -> null
                    }
                    if (b != null) canon(lane) to b else null
                }.toMap()
                replayBiasAtMs = System.currentTimeMillis()
                try {
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "LANE_STRATEGY_REPLAY_BIAS_REFRESH_6093",
                        "reason=$reason lanes=${replayBiasByLane.entries.joinToString(";") { "${it.key}:${it.value.profile}:n=${it.value.n}:net=${"%.3f".format(it.value.netSol)}" }.take(400)}",
                    )
                } catch (_: Throwable) {}
            } catch (_: Throwable) {
            } finally {
                replayBiasInFlight.set(false)
            }
        }
    }


    private val STOP_REASONS = listOf(
        "STOP_LOSS", "HARD_FLOOR", "DISTRIBUTION_STOP", "V8_DISTRIBUTION",
        "RAPID_ENTRY_PROTECT_STOP", "SWEEP_FLUID_FLOOR", "PROTECT_STOP"
    )

    fun recordClose(lane: String, pnlPct: Double, peakPct: Double, exitReason: String) {
        try {
            val key = canon(lane)
            val st = lanes.getOrPut(key) { LaneState() }
            val stopHit = STOP_REASONS.any { exitReason.uppercase().contains(it) }
            val peakSane = when {
                peakPct.isNaN() || peakPct.isInfinite() -> 0.0
                peakPct < 0.0 -> 0.0
                peakPct > 5000.0 -> 5000.0
                else -> peakPct
            }
            val o = Outcome(
                pnlPct = if (pnlPct.isNaN() || pnlPct.isInfinite()) 0.0 else pnlPct,
                peakPct = peakSane,
                win = pnlPct > 0.0,
                stopHit = stopHit,
            )
            synchronized(st) {
                st.window.addLast(o)
                while (st.window.size > WINDOW) st.window.removeFirst()
                st.lifetimeCloses++
                st.sinceRecalc++
                if (st.sinceRecalc >= RECALC_EVERY && st.window.size >= MIN_SAMPLE) {
                    st.sinceRecalc = 0
                    recompute(st)
                }
                refreshReplayBiasAsync("recordClose")
            }
        } catch (_: Throwable) { }
    }

    private fun recompute(st: LaneState) {
        val w = st.window.toList()
        val n = w.size
        if (n < MIN_SAMPLE) return
        val wins = w.count { it.win }
        val wr = wins.toDouble() / n
        val avgPeak = w.map { it.peakPct }.average()
        val avgReal = w.map { it.pnlPct }.average()
        val giveBack = avgPeak - avgReal
        val slHitRate = w.count { it.stopHit }.toDouble() / n
        val losers = w.filter { !it.win }
        val avgLoss = if (losers.isEmpty()) 0.0 else losers.map { it.pnlPct }.average()

        // V5.9.1562 — RUNNER-PRESERVATION first principle (operator forensic 5.0.3659).
        // Bug in the old decision table: branch 2 fired on WR<0.30 + avgPeak≥20 + avgReal≤5,
        // which is the textbook signature of runner-cutting (the lane shows fat peaks but
        // realizes nothing). It then DECREASED tpMult → exits even SOONER, making the
        // bleed worse. MOONSHOT dump: WR 18.7%, avgPeak +630%, avgReal -1.4%, tpMult 0.84.
        // The learner was actively choking the lane that needed to be widened.
        //
        // New ordering: when giveBack is large in absolute terms (≥40pp) AND peaks are
        // real (avgPeak ≥ 30%), the ONLY correct adjustment is to widen TP (let winners
        // breathe) — regardless of WR. WR low + peaks fat = give the bot space to LET
        // them run, not pull the rip-cord earlier.
        var tp = st.tpMult
        when {
            // Strong runner evidence — widen TP no matter what WR looks like.
            giveBack >= 40.0 && avgPeak >= 30.0 -> tp += STEP
            // Healthy lane with moderate give-back — widen further.
            wr >= 0.45 && avgPeak >= 25.0 && giveBack >= 15.0 -> tp += STEP
            // Low-WR with small/no peaks — entry signal weak, exit shouldn't be widened
            // beyond neutral. Only bank-sooner when peaks themselves are tiny.
            wr < 0.30 && avgPeak < 15.0 && avgReal <= -5.0 -> tp -= STEP
            // Already tight lane that's banking too aggressively — nudge up.
            wr >= 0.50 && giveBack < 8.0 && tp < 1.0 -> tp += STEP * 0.5
        }
        st.tpMult = tp.coerceIn(TP_MIN, TP_MAX)

        var sl = st.slMult
        // V5.0.3921 — RUNNER-PRESERVATION SL FLOOR. Operator dump V5.0.3922
        // exit-reason P&L showed MOONSHOT n=200 STOP_LOSS μ=-24.4% vs n=25
        // TAKE_PROFIT μ=+1284.5%, SHITCOIN n=200 STOP_LOSS μ=-25.7% vs n=25
        // TAKE_PROFIT μ=+1796.6%. The TPs are ~50× the |SLs|, so even with
        // 8:1 stop:profit ratio the EV is hugely positive — but the lane
        // tuner had tightened MOONSHOT slMult to 0.92 (TIGHTER than neutral),
        // cutting more would-be runners. When winners are ≥10× the size of
        // losers, the stop should NEVER be tightened below 1.0× — let
        // runners breathe. Compute runner-strength on the win-only subset
        // because peakPct can be diluted by losers' near-zero peaks.
        val winners = w.filter { it.win }
        val avgWinPct = if (winners.isEmpty()) 0.0 else winners.map { it.pnlPct }.average()
        val runnerLane = avgWinPct >= 10.0 * kotlin.math.abs(avgLoss) && winners.size >= 3
        when {
            // V5.0.3765 — low-WR/no-runner bleed fix. The old rule widened stops
            // whenever stop-hit rate was high and avgPeak was low. In a sub-20% WR
            // negative-PF regime that is exactly backwards: there are no runners to
            // preserve, so widening only increases realized loss. Tighten the lane
            // until it proves it can produce peaks/wins again. Runner lanes are still
            // protected above by the TP/giveBack logic and by the avgPeak guard here.
            wr < 0.20 && avgReal < 0.0 && avgPeak < 15.0 -> sl -= STEP * 2.0
            // V5.0.3973 — STOP-LOSS LEAK CLAMP. If a lane's stop rows are
            // repeatedly deep red, do not widen its stop just because some peak
            // evidence exists elsewhere. The 3971 report showed STOP_LOSS rows
            // around -25% to -34% across several lanes; widening those stops
            // directly leaks live wallet. Keep runner preservation via TP/trails,
            // but cap SL at neutral until stop leakage improves.
            slHitRate >= 0.50 && avgLoss <= -20.0 -> sl -= STEP
            slHitRate >= 0.50 && avgPeak < 8.0 && wr >= 0.30 -> sl += STEP
            slHitRate < 0.25 && avgLoss <= -10.0 -> sl -= STEP
        }
        val stopLeakClamp = slHitRate >= 0.35 && avgLoss <= -20.0
        val slCap = if ((wr < 0.20 && avgReal < 0.0 && avgPeak < 15.0) || stopLeakClamp) 1.0 else SL_MAX
        // RUNNER-LANE FLOOR: never let the stop tighten below 1.0× when wins
        // dwarf losses by ≥10×. Widens further if existing tuner logic
        // already raised it; never pulls it back below neutral.
        val slFloor = if (runnerLane && !stopLeakClamp) maxOf(SL_MIN, 1.0) else SL_MIN
        st.slMult = sl.coerceIn(slFloor, slCap)
    }

    fun getTpMult(lane: String): Double = try {
        refreshReplayBiasAsync("getTpMult")
        val key = canon(lane)
        (lanes[key]?.tpMult ?: 1.0) * (replayBiasByLane[key]?.tpMult ?: 1.0)
    } catch (_: Throwable) { 1.0 }

    fun getSlMult(lane: String): Double = try {
        refreshReplayBiasAsync("getSlMult")
        val key = canon(lane)
        (lanes[key]?.slMult ?: 1.0) * (replayBiasByLane[key]?.slMult ?: 1.0)
    } catch (_: Throwable) { 1.0 }

    fun formatForPipelineDump(): String {
      return try {
        if (lanes.isEmpty()) return ""
        buildString {
            append("\n===== Lane Exit Tuner (V5.9.1379 - closed-loop TP/SL) =====\n")
            lanes.entries.sortedBy { it.key }.forEach { (lane, st) ->
                val n = st.window.size
                val matured = n >= MIN_SAMPLE
                val tag = if (matured) "" else "  (bootstrap n=$n - neutral)"
                append(String.format(
                    "  %-12s tpMult=%.2f  slMult=%.2f  lifetime=%d%s\n",
                    lane, st.tpMult, st.slMult, st.lifetimeCloses, tag))
            }
            append("  Read: tpMult>1 => lane lets winners run further; <1 => banks sooner.\n")
            append("        slMult>1 => wider stop (still clamped to -15%); <1 => tighter.\n")
        }
      } catch (_: Throwable) { "" }
    }

    fun exportState(): String = try {
        val root = JSONObject()
        lanes.forEach { (lane, st) ->
            synchronized(st) {
                val o = JSONObject()
                o.put("tp", st.tpMult)
                o.put("sl", st.slMult)
                o.put("life", st.lifetimeCloses)
                val arr = org.json.JSONArray()
                st.window.forEach { oc ->
                    arr.put(JSONObject().apply {
                        put("p", oc.pnlPct); put("k", oc.peakPct)
                        put("w", oc.win); put("s", oc.stopHit)
                    })
                }
                o.put("win", arr)
                o.put("sr", st.sinceRecalc)
                root.put(lane, o)
            }
        }
        root.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            if (json.isBlank()) return
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val lane = keys.next()
                val o = root.optJSONObject(lane) ?: continue
                val st = lanes.getOrPut(lane) { LaneState() }
                synchronized(st) {
                    st.tpMult = o.optDouble("tp", 1.0).coerceIn(TP_MIN, TP_MAX)
                    st.slMult = o.optDouble("sl", 1.0).coerceIn(SL_MIN, SL_MAX)
                    st.lifetimeCloses = o.optLong("life", 0L)
                    st.sinceRecalc = o.optInt("sr", 0)
                    st.window.clear()
                    val arr = o.optJSONArray("win")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val e = arr.optJSONObject(i) ?: continue
                            st.window.addLast(Outcome(
                                pnlPct = e.optDouble("p", 0.0),
                                peakPct = e.optDouble("k", 0.0),
                                win = e.optBoolean("w", false),
                                stopHit = e.optBoolean("s", false),
                            ))
                        }
                        while (st.window.size > WINDOW) st.window.removeFirst()
                    }
                }
            }
        } catch (_: Throwable) { }
    }

    fun reset() { lanes.clear() }
}
