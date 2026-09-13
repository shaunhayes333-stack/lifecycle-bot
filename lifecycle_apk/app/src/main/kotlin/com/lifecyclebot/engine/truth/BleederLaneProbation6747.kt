package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.ForensicLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6747 §BLEEDER_LANE_PROBATION — operator directive Feb 2026:
 *   > "EXPRESS at 6.9%, CORE at 15.4%, MOONSHOT at 14.3% should not
 *   >  retain normal trade frequency. Don't permanently kill them;
 *   >  drastically reduce their capital/frequency until evidence
 *   >  improves. Reprove → tiny probes only → regain authority."
 *
 * Sliding-window per-lane WR tracker. When a lane's terminal WR falls
 * below WR_THRESHOLD over the last MIN_WINDOW trades, the lane enters
 * PROBATION: only tiny probes (size <= PROBE_SIZE_MAX_SOL) may admit;
 * anything larger is refused with a source-visible reason. Probation
 * clears the moment the WR recovers past WR_RECOVERY over the window.
 *
 * Never mutates capital / P&L / lane authority itself. Never blocks
 * SELL / EXIT paths. Never applies to PROJECT_SNIPER (only lane
 * consistently above 40% WR — kept authoritative). Fail-open on any
 * exception so it can never take down admission wholesale.
 */
object BleederLaneProbation6747 {

    private const val WR_THRESHOLD = 0.20      // enter probation
    private const val WR_RECOVERY  = 0.30      // exit probation
    private const val MIN_WINDOW   = 15        // trades in the window before ruling
    private const val WINDOW_SIZE  = 30        // sliding window depth
    /** Max probe size that may bypass probation. Above this the lane is refused. */
    const val PROBE_SIZE_MAX_SOL = 0.02

    /** Lanes exempt from bleeder probation. Kept intentionally short:
     *  the operator explicitly called out PROJECT_SNIPER as the only
     *  current lane at acceptable hit-rate quality. */
    private val EXEMPT_LANES = setOf("PROJECT_SNIPER", "TREASURY", "CASHGEN")

    private data class Window(
        val results: ArrayDeque<Boolean> = ArrayDeque(WINDOW_SIZE + 1),
        var wins: Int = 0,
    ) {
        @Synchronized
        fun add(win: Boolean) {
            results.addLast(win)
            if (win) wins++
            while (results.size > WINDOW_SIZE) {
                val dropped = results.removeFirst()
                if (dropped) wins--
            }
        }
        val n: Int get() = results.size
        val wr: Double get() = if (n > 0) wins.toDouble() / n.toDouble() else 0.0
    }

    private val windows = ConcurrentHashMap<String, Window>()
    private val probationSince = ConcurrentHashMap<String, Long>()
    private val admissionsRefused = AtomicLong(0L)
    private val probeAdmissionsAllowed = AtomicLong(0L)

    /** Feed a settled trade outcome. Called from TacticSwitcher.onTradeClosed
     *  and V3JournalRecorder.recordClose (already the two authoritative sinks). */
    fun onTradeClosed(lane: String, pnlPct: Double) {
        val laneU = canonicalLane(lane) ?: return
        if (laneU in EXEMPT_LANES) return
        val win = pnlPct > 0.0
        val w = windows.computeIfAbsent(laneU) { Window() }
        w.add(win)
        if (w.n < MIN_WINDOW) return
        val wr = w.wr
        val isProbation = probationSince.containsKey(laneU)
        if (!isProbation && wr < WR_THRESHOLD) {
            probationSince[laneU] = System.currentTimeMillis()
            try {
                PipelineHealthCollector.labelInc("BLEEDER_LANE_ENTERED_PROBATION_6747")
                PipelineHealthCollector.labelInc("BLEEDER_LANE_ENTERED_PROBATION_6747|$laneU")
                ForensicLogger.lifecycle(
                    "BLEEDER_LANE_ENTERED_PROBATION_6747",
                    "lane=$laneU wr=${"%.3f".format(wr)} window=${w.n} threshold=${"%.2f".format(WR_THRESHOLD)} " +
                        "action=only_probes_admitted probeMaxSol=$PROBE_SIZE_MAX_SOL",
                )
            } catch (_: Throwable) {}
        } else if (isProbation && wr >= WR_RECOVERY) {
            probationSince.remove(laneU)
            try {
                PipelineHealthCollector.labelInc("BLEEDER_LANE_EXITED_PROBATION_6747")
                PipelineHealthCollector.labelInc("BLEEDER_LANE_EXITED_PROBATION_6747|$laneU")
                ForensicLogger.lifecycle(
                    "BLEEDER_LANE_EXITED_PROBATION_6747",
                    "lane=$laneU wr=${"%.3f".format(wr)} window=${w.n} recovery=${"%.2f".format(WR_RECOVERY)} " +
                        "action=admissions_restored",
                )
            } catch (_: Throwable) {}
        }
    }

    /** True if the lane is currently in bleeder probation. Read-only. */
    fun isOnProbation(lane: String): Boolean {
        val laneU = canonicalLane(lane) ?: return false
        if (laneU in EXEMPT_LANES) return false
        return probationSince.containsKey(laneU)
    }

    /**
     * Admission decision. Returns null to ALLOW, or a String reason
     * to REFUSE. Callers integrate at the same layer as the regime
     * floor so the refusal is source-visible and appears in verdict
     * telemetry. Size <= PROBE_SIZE_MAX_SOL always allowed so the
     * learner can still gather evidence to promote the lane back.
     */
    fun evaluate(lane: String, resolvedSizeSol: Double): String? {
        val laneU = canonicalLane(lane) ?: return null
        if (laneU in EXEMPT_LANES) return null
        if (!probationSince.containsKey(laneU)) return null
        // Tiny probe admissions are always allowed — that's how the
        // lane accumulates the evidence needed to leave probation.
        if (resolvedSizeSol.isFinite() && resolvedSizeSol > 0.0 &&
            resolvedSizeSol <= PROBE_SIZE_MAX_SOL) {
            probeAdmissionsAllowed.incrementAndGet()
            try { PipelineHealthCollector.labelInc("BLEEDER_LANE_PROBE_ADMITTED_6747|$laneU") } catch (_: Throwable) {}
            return null
        }
        admissionsRefused.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("BLEEDER_LANE_ADMISSION_REFUSED_6747")
            PipelineHealthCollector.labelInc("BLEEDER_LANE_ADMISSION_REFUSED_6747|$laneU")
        } catch (_: Throwable) {}
        val w = windows[laneU]
        val wr = w?.wr ?: 0.0
        return "BLEEDER_LANE_PROBATION_6747:wr=${"%.2f".format(wr)}<${"%.2f".format(WR_THRESHOLD)}"
    }

    /** Persist-free reset for unit tests. */
    fun resetForTest() {
        windows.clear(); probationSince.clear()
        admissionsRefused.set(0L); probeAdmissionsAllowed.set(0L)
    }

    fun summary(): String =
        "onProbation=${probationSince.keys.joinToString(",")} refused=${admissionsRefused.get()} " +
            "probeAdmitted=${probeAdmissionsAllowed.get()}"

    private fun canonicalLane(raw: String): String? {
        val u = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
        if (u.isBlank()) return null
        return when (u) {
            "BLUE_CHIP" -> "BLUECHIP"
            "SHIT_COIN" -> "SHITCOIN"
            else -> u
        }
    }
}
