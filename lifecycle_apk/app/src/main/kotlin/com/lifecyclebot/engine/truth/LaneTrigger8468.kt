package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.cortex.LaneGradeHorizon8406
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8468 — EACH LANE IS ADMITTED ON ITS OWN TRIGGER, JUDGED AT ITS OWN HORIZON (strategy audit fix 3; owner: "all 4.
 * but be smart").
 *
 * The audit (8467): the lane brains barely filter (MOONSHOT qualified 690 of 697, SHITCOIN 454 of 485) and the decision
 * is made by ~12 shared doors after them, none of which reads the lane's own trigger. The live door (8059 "proven setups
 * only") asks whether one SHARED setup menu (8383: trend pullback, sweet spot, band momentum, ...) is proven on the
 * 5-minute read, so EXPRESS's ignition, MANIPULATED's bundle pump, DIP_HUNTER's healthy dip and the sniper's launch were
 * never what got judged — and their records measured a different trade from the one the lane runs.
 *
 * Now every forward label carries whether the lane's own native brain qualified the coin at the decision
 * (ForwardReturnLabeler7731 TRIGGER|lane|NATIVE, graded like every label, net of the coin's cost). A coin the lane's
 * brain qualifies NOW is admitted past the shared-setup doors when that trigger record is proven at the lane's horizon:
 *  - a lane graded on the hour (LaneGradeHorizon8406: mandate >= +20 %) — [HOUR_MIN_N_8468]+ hour labels, mean > 0, and a
 *    5-minute read that is not an implausible-basis outlier;
 *  - every other lane — [MIN_N_8468]+ 5-minute labels, mean - SE > 0 (the door's own 8060 standard).
 * It is graded-label evidence, the same class the door already accepted (provenSetup8056); no paper / shadow P&L sizes or
 * admits anything. The tactic's realised-loss refusal, the chase rule, watch-first, Cortex, net edge, the lane's realised
 * live-loss lock, FDG, sizing and the buy door all still decide; hard safety is untouched.
 */
object LaneTrigger8468 {
    private const val MIN_N_8468 = 30
    private const val HOUR_MIN_N_8468 = 40
    /** V5.0.8474 — the share of the trigger record's labels that must have been priced for it to prove anything. */
    private const val MIN_COVERAGE_8474 = 0.5

    private val admitted8468 = ConcurrentHashMap<String, AtomicLong>()

    /** Pure: does [st] prove the lane's own trigger pays at the lane's horizon ([hourGraded])? */
    fun proven8468(hourGraded: Boolean, st: ForwardReturnLabeler7731.CellStat?): Boolean {
        if (st == null || st.outlierSuspect8185) return false
        // V5.0.8474 — a record whose labels were mostly lost holds only the survivors (dead coins drop out): not proof.
        if (st.resolvedShare < MIN_COVERAGE_8474) return false
        return if (hourGraded) {
            st.n240 >= HOUR_MIN_N_8468 && st.meanNet240Pct.isFinite() && st.meanNet240Pct > 0.0 && !st.outlierFlag8322
        } else {
            st.n60 >= MIN_N_8468 && st.meanNet60Pct.isFinite() && st.stderr60Pct.isFinite() && st.meanNet60Pct - st.stderr60Pct > 0.0
        }
    }

    /** Pure: the admit — the lane's brain qualifies the coin now ([nativeEligible] == true) and its trigger record is proven. */
    fun admits8468(nativeEligible: Boolean?, hourGraded: Boolean, st: ForwardReturnLabeler7731.CellStat?): Boolean =
        nativeEligible == true && proven8468(hourGraded, st)

    /** LiveEdgeGate7877.canonicalRefusal8392: is [ts] admitted on [lane]'s own proven trigger? */
    fun admits8468(ts: TokenState, lane: String): Boolean {
        val l = lane.trim().uppercase()
        val native = try { com.lifecyclebot.engine.OwnershipChain8364.nativeEligible8364(ts.mint, l) } catch (_: Throwable) { null }
        if (native != true) return false
        val ok = admits8468(native, try { LaneGradeHorizon8406.hourGraded8406(l) } catch (_: Throwable) { false },
            try { ForwardReturnLabeler7731.laneTriggerStat8468(l) } catch (_: Throwable) { null })
        if (ok) {
            admitted8468.getOrPut(l) { AtomicLong() }.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LIVE_LANE_TRIGGER_ADMIT_8468_$l") } catch (_: Throwable) {}
        }
        return ok
    }

    fun statusLine8468(lanes: List<String>): String = "laneTrigger8468[" + lanes.joinToString(" ") { lane ->
        val st = try { ForwardReturnLabeler7731.laneTriggerStat8468(lane) } catch (_: Throwable) { null }
        val hour = try { LaneGradeHorizon8406.hourGraded8406(lane) } catch (_: Throwable) { false }
        val rec = if (st == null) "n0" else if (hour) "h n${st.n240} ${"%.1f".format(st.meanNet240Pct)}%" else "5m n${st.n60} ${"%.1f".format(st.meanNet60Pct)}±${"%.1f".format(st.stderr60Pct)}%"
        "$lane:$rec${if (proven8468(hour, st)) " PROVEN" else ""} admits=${admitted8468[lane]?.get() ?: 0}"
    } + "]"
}
