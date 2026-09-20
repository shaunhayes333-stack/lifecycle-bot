package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
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

    /**
     * V5.0.7177 — free-cash fraction of equity below which a lane's budget
     * share is treated as a real limit rather than an accounting convenience.
     *
     * Above this, capital is uncontended: the lane's nominal share is being
     * exceeded only because other lanes are not claiming theirs, and refusing
     * a funded trade there is a brake the operator has explicitly ruled out.
     *
     * Deliberately just above CASH_STARVE_RATIO (0.20) so lane fairness
     * re-engages slightly BEFORE the hard cash-starve gate, giving a graduated
     * response instead of a cliff.
     */
    private const val LANE_BUDGET_CONTENTION_CASH_RATIO_7177 = 0.25

    /**
     * V5.0.6912 — lane budget utilisation at which NEW OPENS for that lane
     * are refused. See the block in evaluate() for the full rationale.
     *
     * 1.50 = "half again over its expectancy-weighted target". Deliberately
     * above 1.0: a lane may sit slightly over while a fill settles, and the
     * targets themselves move as expectancy updates. BLUECHIP's observed
     * 3.68x clears this by a wide margin; a healthy lane never reaches it.
     */
    private const val OVERSPEND_BLOCK_RATIO_6912 = 1.50

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
        val cash: Double
        val open: Double
        try {
            if (m == "live") {
                val cap = CanonicalCapitalAuthority6450.snapshot()
                cash = cap.cashSol
                open = cap.openMarketValueSol
            } else {
                val cap = PaperCapitalAuthority6577.snapshot()
                cash = cap.availableCashSol
                open = cap.openMarketValueSol
            }
        } catch (_: Throwable) {
            return Verdict(true, "OK_FAIL_OPEN", 0, 0.0, 0.0, 1.0)
        }
        val equity = cash + open
        val openCount = try {
            CanonicalPositionAuthority6441.openPositions().count { it.mode == m }
        } catch (_: Throwable) { 0 }
        val cashRatio = if (equity > 0.0) cash / equity else 1.0

        // Hard cap: absolute open count exceeds sanity ceiling regardless
        // of cash. Prevents runaway inventory even when a fresh deposit
        // temporarily lifts cashRatio.
        //
        // V5.0.7027 §THE_CAP_COUNTED_ROWS_IT_COULD_NOT_MANAGE. The ceiling now
        // compares MANAGED rows, not canonical rows. A row whose token has had
        // no usable mark for ten minutes cannot be stopped, trailed or taken
        // profit on — the scheduler pings it with markPx=0 and the universal
        // sweep skips it on `last <= 0.0` — so counting it here means the bot
        // stops trading in proportion to how much of its book has gone dark.
        // The operator's 5.0.7025 capture is that state at its limit: 100 open
        // rows, zero closes in the session, 967 BUY verdicts, and every
        // authorised entry resolved to size zero behind this very line.
        //
        // The discount is bounded inside the breaker so a total feed blackout
        // cannot uncap the bot, and the cash gate below is untouched — this
        // corrects an inventory COUNT, it does not widen capital exposure.
        // See InventoryDeadlockBreaker7027 for the full rationale.
        val relief7027 = try {
            InventoryDeadlockBreaker7027.relief(m, POSITION_HARD_CAP)
        } catch (_: Throwable) { null }
        val managedCount7027 = relief7027?.managedRows ?: openCount
        if (managedCount7027 >= POSITION_HARD_CAP) {
            try { PipelineHealthCollector.labelInc("EXIT_THROUGHPUT_BLOCKED_POSITION_HARD_CAP_6727") } catch (_: Throwable) {}
            return Verdict(false, "POSITION_HARD_CAP_EXIT_THROUGHPUT_6727", openCount, cash, equity, cashRatio)
        }
        if (relief7027 != null && relief7027.discounted > 0 && openCount >= POSITION_HARD_CAP) {
            // The cap WOULD have blocked this and the dark-row discount is the
            // only reason it did not. Named, because a silent relaxation of a
            // safety ceiling is worse than the deadlock it fixes.
            try {
                PipelineHealthCollector.labelInc("EXIT_THROUGHPUT_HARD_CAP_RELIEVED_7027")
            } catch (_: Throwable) {}
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
        // V5.0.6912 §FAIRNESS_WAS_EXEMPTION_ONLY.
        //
        // OPERATOR EVIDENCE (5.0.6909 snapshot, MEME SPECIALIST CAPITAL):
        //
        //   BLUECHIP        targetSol=0.8474  usedAllocation=3.1184  (368%)
        //                   exec=26  finalized=1  W/L=0/1  avgPct=-53.3%
        //   PROJECT_SNIPER  targetSol=0.8261  usedAllocation=1.0579  (128%)
        //                   exec=2   EV=+83.12%/trade  PnL=+0.3793 SOL
        //   capitalStarved=false  starvedByLane=NONE   (for every lane)
        //
        // The lane with the only positive expectancy in the book got two
        // fills. The lane at 368% of its budget, 0-for-1 at -53%, got
        // twenty-six. That inversion is most of the win rate.
        //
        // LaneCapitalFairness6732's own header says "the specialist report is
        // the observability surface, this authority is the enforcement
        // surface". It enforced nothing. Its single production caller was the
        // line below, which used it ONE WAY: headroom -> bypass the global
        // gate. Saturation returned false, which merely meant "the global gate
        // is fair" — and the global gate measures portfolio cash and inventory
        // count, not this lane's budget. So no code path anywhere ever told a
        // lane it was over its allocation. A budget that cannot be exceeded in
        // one direction and cannot be enforced in the other is not a budget.
        //
        // Now both directions are honoured: headroom still bypasses (that is
        // the 6732 fairness fix and it stays), and saturation past
        // OVERSPEND_BLOCK_RATIO_6912 blocks NEW OPENS for that lane.
        //
        // NOT A LANE DISABLE (V5.9.1358). This is a budget, and it clears
        // itself two ways without any operator action: the lane frees capital
        // when a position closes, and its target grows when its expectancy
        // improves (laneTargetSol is expectancy-weighted). Exits are never
        // touched — this authority is consulted by the ENTRY resolver only,
        // so a saturated lane keeps draining normally, which is precisely how
        // it earns headroom back. The block is also per-lane, so a saturated
        // BLUECHIP cannot starve PROJECT_SNIPER; it frees the shared cash that
        // the profitable lane was being outbid for.
        //
        // The ratio is deliberately well above 1.0 rather than at it. A lane
        // may legitimately sit slightly over target while a fill settles, and
        // targets themselves move as expectancy updates. 1.50 means "half
        // again over budget", which BLUECHIP's 3.68x clears by a wide margin
        // and a normal lane never reaches.
        val laneHeadroom6732 = try {
            if (lane.isNotBlank()) LaneCapitalFairness6732.headroomFor(m, lane) else null
        } catch (_: Throwable) { null }
        if (laneHeadroom6732?.hasHeadroom == true) {
            try { PipelineHealthCollector.labelInc("EXIT_THROUGHPUT_LANE_FAIRNESS_BYPASS_6732") } catch (_: Throwable) {}
            return Verdict(true, "LANE_HEADROOM_FAIRNESS_6732", openCount, cash, equity, cashRatio)
        }
        // V5.0.7177 §A_SHARE_IS_ONLY_A_LIMIT_WHEN_SOMEONE_ELSE_WANTS_IT.
        //
        // Operator standing directive: "I dont necessarily want a portfolio
        // brake. dont miss profitable trade opportunities if the capital is
        // there to fund the trade."
        //
        // laneTargetSol divides sharedEquity across EVERY meme lane by
        // expectancy weight, so the targets sum to the whole book whether or
        // not a lane is using its share. Operator 5.0.7176 is that arithmetic
        // at its worst:
        //
        //   CORE      used 2.3677  target 1.1854  = 2.00x  -> BLOCKED
        //   QUALITY   used 2.0715  target 1.1854  = 1.75x  -> BLOCKED
        //   BLUECHIP / SHITCOIN / EXPRESS / DIP_HUNTER /
        //   MANIPULATED / TREASURY / CASHGEN        used 0.0000 each
        //   CASH 6.6321 SOL idle, cashRatio 39.5%
        //
        // Roughly ten SOL of lane budget was claimed by nobody and 6.63 SOL of
        // cash was sitting idle, and WBTC was still refused with
        // FINALITY_EXEC_OPEN_BLOCKED_EXIT_THROUGHPUT_6727:LANE_OVERSPEND_6912
        // as a HARD_SAFETY reject. That is precisely the miss the directive
        // forbids: the capital was there, the trade was funded, and a
        // bookkeeping share said no.
        //
        // 6912's purpose is kept intact. It exists because a lane at 368% of
        // budget going 0-for-1 at -53% was crowding out the only profitable
        // lane in the book — and crowding out is only possible when capital is
        // CONTENDED. In that 5.0.6909 evidence cash was 2.1427 against 11.31
        // of cost basis, a cashRatio of 19%: genuinely scarce, and the block
        // was correct. At 39.5% nobody is being crowded out, so the same block
        // is a pure brake.
        //
        // So the budget now binds only when free cash is actually scarce. This
        // is deliberately graduated rather than a cliff: the threshold sits
        // just above CASH_STARVE_RATIO, so lane fairness re-engages shortly
        // BEFORE the hard cash-starve gate below, and a lane that runs away on
        // idle capital re-enters enforcement by its own spending as the cash
        // ratio falls. Nothing else is relaxed — POSITION_HARD_CAP, the
        // cash-starve gate, the velocity guards and LaneExpectancyDamper's
        // size shrink on a bleeding lane all still apply.
        val laneBudgetContended7177 = cashRatio < LANE_BUDGET_CONTENTION_CASH_RATIO_7177
        if (laneHeadroom6732 != null &&
            laneHeadroom6732.targetSol > 0.0 &&
            laneHeadroom6732.utilization >= OVERSPEND_BLOCK_RATIO_6912 &&
            !laneBudgetContended7177
        ) {
            try {
                PipelineHealthCollector.labelInc("LANE_OVERSPEND_ALLOWED_UNCONTENDED_CASH_7177")
                PipelineHealthCollector.labelInc(
                    "LANE_OVERSPEND_ALLOWED_UNCONTENDED_CASH_7177_${laneHeadroom6732.lane}",
                )
                ForensicLogger.lifecycle(
                    "LANE_OVERSPEND_ALLOWED_UNCONTENDED_CASH_7177",
                    "mode=$m lane=${laneHeadroom6732.lane} " +
                        "used=${"%.4f".format(laneHeadroom6732.usedSol)} " +
                        "target=${"%.4f".format(laneHeadroom6732.targetSol)} " +
                        "util=${"%.2f".format(laneHeadroom6732.utilization)}x " +
                        "cash=${"%.4f".format(cash)} cashRatio=${"%.3f".format(cashRatio)} " +
                        "contendAt=$LANE_BUDGET_CONTENTION_CASH_RATIO_7177 " +
                        "action=fund_the_trade_lane_share_is_not_a_limit_while_cash_is_idle",
                )
            } catch (_: Throwable) {}
        }
        if (laneHeadroom6732 != null &&
            laneHeadroom6732.targetSol > 0.0 &&
            laneHeadroom6732.utilization >= OVERSPEND_BLOCK_RATIO_6912 &&
            laneBudgetContended7177
        ) {
            try {
                PipelineHealthCollector.labelInc("EXIT_THROUGHPUT_BLOCKED_LANE_OVERSPEND_6912")
                PipelineHealthCollector.labelInc("EXIT_THROUGHPUT_BLOCKED_LANE_OVERSPEND_6912_${laneHeadroom6732.lane}")
                ForensicLogger.lifecycle(
                    "EXIT_THROUGHPUT_BLOCKED_LANE_OVERSPEND_6912",
                    "mode=$m lane=${laneHeadroom6732.lane} used=${"%.4f".format(laneHeadroom6732.usedSol)} " +
                        "target=${"%.4f".format(laneHeadroom6732.targetSol)} " +
                        "util=${"%.2f".format(laneHeadroom6732.utilization)}x " +
                        "blockAt=${OVERSPEND_BLOCK_RATIO_6912}x openCount=$openCount " +
                        "action=refuse_new_opens_until_lane_drains_or_earns_target",
                )
            } catch (_: Throwable) {}
            return Verdict(false, "LANE_OVERSPEND_6912", openCount, cash, equity, cashRatio)
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
