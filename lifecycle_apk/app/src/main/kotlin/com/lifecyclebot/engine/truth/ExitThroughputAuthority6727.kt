package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6727 — §EXIT_THROUGHPUT_BACK_PRESSURE.
 *
 * Runtime symptom (from 6726 diagnostic dump): 200 active positions,
 * cash 0.0040 SOL (0.004% of equity), size-resolver returning
 * `final=0.00000 reason=CAPITAL_BELOW_MIN_EXECUTABLE_6490`, slot health
 * `forced=194 exitPending=true`, and 19 minutes of paper-execution
 * silence while SCAN and FDG continued. Cascading counters:
 *   EXEC_DEFERRED_SLOT_HEALTH   = 2586
 *   MEME_TURNOVER_PRESSURE_DEFER = 2586
 *   SIZE_ZERO_UNPRICED_INTAKE   =  349
 *   BELOW_MIN_NOTIONAL          =  200
 *
 * Root cause: the buy-admission path has NO hard back-pressure on
 * capital-saturation state. It relies on the size resolver to return
 * zero, which happens too late — by then the pipeline has already
 * churned SCAN → INTAKE → FDG → EXEC_GATE 2500+ times per cycle and
 * spammed the block counters. The admission surface needs to KNOW
 * about the saturation state and short-circuit before the churn.
 *
 * This authority owns the single-source-of-truth answer for
 * "should admission be paused right now?" It reads from
 * PaperCapitalAuthority6577 (paper) or CanonicalCapitalAuthority6450
 * (live) plus CanonicalPositionAuthority6441 and returns a verdict
 * consumers can act on WITHOUT re-implementing the pressure math.
 *
 * Deliberately DOES NOT block exits — this is admission-side only.
 * Exit-throughput is already gated by the exit coordinator; adding
 * an admission clamp lets exits drain the inventory without new
 * opens re-saturating.
 */
object ExitThroughputAuthority6727 {

    /** Fraction of equity below which cash is considered starved. */
    private const val CASH_STARVE_RATIO = 0.20        // 20% — was 5%. Proactive: engage BEFORE saturation.
    /** Position-count threshold above which the guard engages. */
    private const val POSITION_CAP_HINT = 40          // was 100. Proactive: engage BEFORE saturation.
    /** Extreme threshold — at this open count admission blocks regardless of cash ratio. */
    private const val POSITION_HARD_CAP = 100         // was 180. Proactive: cap total inventory before the flood.
    /** Rolling-window sample for the velocity guard. */
    private const val VELOCITY_SAMPLE_WINDOW_MS = 60_000L
    /** Position-open velocity above which the guard engages. */
    private const val VELOCITY_OPEN_PER_MIN_MAX = 20
    /** Ratio of opens-to-sells above which the guard engages (imbalance). */
    private const val VELOCITY_OPEN_TO_SELL_MAX = 3.0
    /** Minimum sells within the window before ratio can engage. */
    private const val VELOCITY_MIN_SELLS = 5

    data class Verdict(
        val allow: Boolean,
        val reason: String,
        val openPositions: Int,
        val cashSol: Double,
        val equitySol: Double,
        val cashRatio: Double,
    )

    /**
     * Query the current back-pressure state for buy admission.
     * @param mode "paper" or "live" (case-insensitive).
     * @param lane the canonical lane this admission is scoped to. When
     *   supplied, the guard consults `LaneCapitalFairness6732` before
     *   emitting a CASH_STARVED_* or INVENTORY_VELOCITY_* block. A lane
     *   with headroom is NEVER blocked by portfolio-wide throughput —
     *   it is fair per-lane, not fair global. Blank lane preserves
     *   legacy portfolio-wide behaviour.
     * @return Verdict with .allow = true when admission is unblocked;
     *         .allow = false when the guard is engaged. Reason is one of:
     *         "OK", "CASH_STARVED_EXIT_THROUGHPUT_6727",
     *         "POSITION_HARD_CAP_EXIT_THROUGHPUT_6727",
     *         "INVENTORY_VELOCITY_OPM_6730",
     *         "INVENTORY_VELOCITY_RATIO_6730".
     */
    @JvmOverloads
    fun evaluate(mode: String, lane: String = ""): Verdict {
        val m = mode.trim().lowercase()
        if (m !in setOf("paper", "live")) return Verdict(false, "INVALID_CAPITAL_MODE_6737", 0, 0.0, 0.0, 0.0)
        val cash: Double
        val open: Double
        try {
            if (m == "live") {
                // The 6450 snapshot is PAPER-only. Never apply paper cash to live admission.
                cash = com.lifecyclebot.engine.WalletManager.cachedSolBalance()
                open = CanonicalPositionAuthority6441.fundedPositions6737("live")
                    .sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
            } else {
                val cap = PaperCapitalAuthority6577.snapshot()
                cash = cap.availableCashSol
                open = cap.openMarketValueSol
            }
        } catch (_: Throwable) {
            return Verdict(false, "CAPITAL_SNAPSHOT_UNAVAILABLE_6737", 0, 0.0, 0.0, 0.0)
        }
        if (!cash.isFinite() || cash < 0.0 || !open.isFinite() || open < 0.0)
            return Verdict(false, "CAPITAL_SNAPSHOT_UNAVAILABLE_6737", 0, 0.0, 0.0, 0.0)
        val equity = cash + open
        val openCount = try {
            CanonicalPositionAuthority6441.fundedPositions6737(m).size
        } catch (_: Throwable) { 0 }
        val cashRatio = if (equity > 0.0) cash / equity else 1.0

        // Hard cap: absolute open count exceeds sanity ceiling regardless
        // of cash. Prevents runaway inventory even when a fresh deposit
        // temporarily lifts cashRatio.
        if (openCount >= POSITION_HARD_CAP) {
            try { PipelineHealthCollector.labelInc("EXIT_THROUGHPUT_BLOCKED_POSITION_HARD_CAP_6727") } catch (_: Throwable) {}
            return Verdict(false, "POSITION_HARD_CAP_EXIT_THROUGHPUT_6727", openCount, cash, equity, cashRatio)
        }

        // V5.0.6732 §LANE_SCOPED_CAPITAL_FAIRNESS — if this admission
        // targets a specific lane and that lane still has budget
        // headroom under its target allocation, DON'T let the global
        // portfolio-wide gates (cash-starved / inventory-velocity)
        // hard-block it. The 6731 dump proved these portfolio-wide
        // gates were choking 613/652 EXEC blocks while individual
        // lanes reported capitalStarved=false. This deferral restores
        // per-lane fairness. Position hard cap above is preserved as
        // an unconditional sanity ceiling.
        val laneHeadroom6732 = try {
            if (lane.isNotBlank()) LaneCapitalFairness6732.hasHeadroom(m, lane) else false
        } catch (_: Throwable) { false }
        if (laneHeadroom6732 && !(cashRatio < CASH_STARVE_RATIO && openCount >= POSITION_CAP_HINT)) {
            try { PipelineHealthCollector.labelInc("EXIT_THROUGHPUT_LANE_FAIRNESS_BYPASS_6732") } catch (_: Throwable) {}
            return Verdict(true, "LANE_HEADROOM_FAIRNESS_6732", openCount, cash, equity, cashRatio)
        }

        // Compound guard: cash starved AND we're already carrying real
        // inventory. Either condition alone can be recovered; the
        // combination is the saturation state we saw in the dump.
        if (cashRatio < CASH_STARVE_RATIO && openCount >= POSITION_CAP_HINT) {
            try { PipelineHealthCollector.labelInc("EXIT_THROUGHPUT_BLOCKED_CASH_STARVED_6727") } catch (_: Throwable) {}
            return Verdict(false, "CASH_STARVED_EXIT_THROUGHPUT_6727", openCount, cash, equity, cashRatio)
        }

        // V5.0.6730 §PROACTIVE_INVENTORY_VELOCITY — 6729 fresh-boot
        // dump: 158 opens / 43 sells / 158 open positions in 207
        // seconds. The 6727 level-based guard fires AFTER saturation;
        // this velocity guard fires DURING the flood, when the rate
        // of admissions clearly exceeds exit capacity. Two triggers,
        // either engages: (a) opens-per-minute above the sustainable
        // rate, or (b) open-to-sell ratio inside a rolling window
        // above the healthy round-trip imbalance. Never blocks when
        // the pipeline is quiet or when sells are keeping up.
        val vel6730 = try { InventoryVelocityCounters6730.snapshot(m) } catch (_: Throwable) { null }
        if (vel6730 != null && vel6730.opensPerMinute >= VELOCITY_OPEN_PER_MIN_MAX) {
            try { PipelineHealthCollector.labelInc("EXIT_THROUGHPUT_BLOCKED_INVENTORY_VELOCITY_OPM_6730") } catch (_: Throwable) {}
            return Verdict(false, "INVENTORY_VELOCITY_OPM_6730", openCount, cash, equity, cashRatio)
        }
        if (vel6730 != null && vel6730.sellsInWindow >= VELOCITY_MIN_SELLS &&
            vel6730.opensInWindow.toDouble() / vel6730.sellsInWindow.toDouble() >= VELOCITY_OPEN_TO_SELL_MAX) {
            try { PipelineHealthCollector.labelInc("EXIT_THROUGHPUT_BLOCKED_INVENTORY_VELOCITY_RATIO_6730") } catch (_: Throwable) {}
            return Verdict(false, "INVENTORY_VELOCITY_RATIO_6730", openCount, cash, equity, cashRatio)
        }

        return Verdict(true, "OK", openCount, cash, equity, cashRatio)
    }
}

/**
 * V5.0.6730 §PROACTIVE_INVENTORY_VELOCITY — companion counter object.
 * The exit-coordinator's terminal handler calls `recordBuy(mode)` on
 * each admission and `recordSell(mode)` on each close. This object
 * maintains a small rolling ring buffer and returns per-mode velocity
 * so the throughput guard can pre-empt saturation.
 */
object InventoryVelocityCounters6730 {
    private const val WINDOW_MS = 60_000L
    private val buysByMode = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<Long>>()
    private val sellsByMode = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<Long>>()

    data class Snapshot(
        val opensPerMinute: Int,
        val opensInWindow: Int,
        val sellsInWindow: Int,
    )

    private fun norm(mode: String) = mode.trim().lowercase().ifBlank { "paper" }

    fun recordBuy(mode: String) {
        val m = norm(mode)
        val q = buysByMode.computeIfAbsent(m) { java.util.concurrent.ConcurrentLinkedDeque() }
        q.addLast(System.currentTimeMillis())
        pruneLocked(q)
    }

    fun recordSell(mode: String) {
        val m = norm(mode)
        val q = sellsByMode.computeIfAbsent(m) { java.util.concurrent.ConcurrentLinkedDeque() }
        q.addLast(System.currentTimeMillis())
        pruneLocked(q)
    }

    private fun pruneLocked(q: java.util.concurrent.ConcurrentLinkedDeque<Long>) {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        while (true) {
            val head = q.peekFirst() ?: break
            if (head < cutoff) q.pollFirst() else break
        }
    }

    fun snapshot(mode: String): Snapshot {
        val m = norm(mode)
        val bq = buysByMode[m]?.also { pruneLocked(it) }
        val sq = sellsByMode[m]?.also { pruneLocked(it) }
        val opens = bq?.size ?: 0
        val sells = sq?.size ?: 0
        return Snapshot(opensPerMinute = opens, opensInWindow = opens, sellsInWindow = sells)
    }
}
