package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6732 — §LANE_SCOPED_CAPITAL_FAIRNESS.
 *
 * Operator diagnostic from 6731: `ExitThroughputAuthority6727` is
 * globally blocking 300 admissions with `CASH_STARVED_EXIT_THROUGHPUT_6727`
 * and 313 with `INVENTORY_VELOCITY_OPM_6730`, together 94% of all EXEC
 * blocks. Simultaneously, every specialist lane reports `capitalStarved=false`.
 * The mismatch is real: the global guard evaluates portfolio-wide
 * cashRatio + total open count, while the specialist views only count
 * a lane starved if its own pending intents can't be funded from
 * shared cash (`pending > 0L && sharedCash <= 0.0`).
 *
 * A lane that owns 0 positions and has 3% target allocation is being
 * blocked by the same portfolio-saturation gate as a lane already
 * carrying 80% of open equity. This authority owns the single answer
 * for "does THIS specific lane still have budget headroom?" so the
 * global throughput guard can defer to per-lane fairness before
 * hard-blocking.
 *
 * Contract:
 *  - Returns `hasHeadroom = true` when the lane's own used allocation
 *    plus pending intents is under LANE_HEADROOM_RATIO of its target
 *    allocation.
 *  - Returns `hasHeadroom = false` when the lane has consumed at least
 *    LANE_HEADROOM_RATIO of its target — global throttling is fair.
 *
 * Never mutates state. Non-meme lanes fail open (return `hasHeadroom=true`)
 * so cross-asset parity is preserved.
 */
object LaneCapitalFairness6732 {

    /** Configured meme desk names — must match ToolkitSignalSheet.configuredMemeDesks6599. */
    /**
     * V5.0.7185 — ceiling on how much unclaimed hive budget one specialist may
     * absorb, as a multiple of its own nominal share. Stops a single lane on a
     * short good streak from owning the whole book while still letting the
     * working lanes use capital the idle ones are not asking for.
     */
    private const val MAX_CLAIM_MULT_7185 = 3.0

    /** V5.0.7342 — same-mode closes before a negative EV withholds released budget
     *  (matches the Strategy expectancy table's ">=5 trainable" bar). */
    private const val MIN_CLOSES_FOR_RELEASE_EVIDENCE_7342 = 5

    private val MEME_LANES = setOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
        "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN",
    )
    /** Fraction of a lane's target allocation below which the lane is
     * considered to have headroom. 0.90 → a lane using <90% of its
     * target share is protected from portfolio-wide throughput blocks.
     * Above that, the global gate is fair. */
    private const val LANE_HEADROOM_RATIO = 0.90

    data class Headroom(
        val hasHeadroom: Boolean,
        val lane: String,
        val usedSol: Double,
        val targetSol: Double,
        val utilization: Double,
    )

    // V5.0.7115 §ONE_LANE_IDENTITY — delegated; both folds moved to the authority.
    private fun normLane(raw: String): String =
        CanonicalLaneIdentity6506.canonical(raw)

    /**
     * Compute per-lane headroom. Non-meme lanes fail open (headroom always true).
     * Any exception path fails open — this authority never blocks by accident.
     */
    fun headroomFor(mode: String, lane: String): Headroom {
        val nl = normLane(lane)
        if (nl.isBlank() || nl !in MEME_LANES) {
            return Headroom(true, nl, 0.0, 0.0, 0.0)
        }
        return try {
            val paperMode = mode.trim().equals("PAPER", true)
            // V5.0.7211 §THE_LIVE_BRANCH_WAS_PAPER_CASH_UNDER_ANOTHER_NAME.
            //
            // This read, before 7211:
            //
            //   if (paperMode) PaperCapitalAuthority6577…availableCashSol
            //   else           CanonicalCapitalAuthority6450.snapshot().cashSol
            //
            // A paper/live branch that is a distinction without a difference.
            // CanonicalCapitalAuthority6450's own header documents its source
            // as "CASH — PaperCapitalAuthority6577.cashSol", and :127-137 is
            // hardwired to it with no live branch anywhere. So BOTH arms
            // returned the paper bankroll and the else-arm only looked like it
            // was about live.
            //
            // Cost on the operator's 5.0.7210 live run: the paper account sat
            // idle at 11.7530 SOL cash while the live wallet had 0.1950 free.
            // Every lane's headroom was therefore scaled against ~60x the
            // capital that existed, which is why enforcedHeadroom=true sat on
            // nearly every lane in that snapshot while sharedCash read 0.1950.
            // This is the same paper-into-live bleed as 7187's sizing mirror
            // and 7209's lane pause, in the lane budget.
            //
            // BotService.status.walletSol is the live cash source the lane
            // allocator already uses and names LIVE_WALLET_AUTHORITY_6686
            // (ToolkitSignalSheet:877); on that run it read 0.1950 correctly.
            // Asking it here makes this authority and the allocator agree.
            //
            // NOTE ON SCOPE: CanonicalCapitalAuthority6450's arithmetic is on
            // the operator's no-touch list and is NOT modified. It remains the
            // paper account authority it is documented to be. What changes is
            // which authority this CONSUMER asks when the mode is live — the
            // paper number is still exactly right for the paper branch.
            //
            // Fails open on a non-positive or non-finite reading by falling
            // back to the previous value, because this authority's contract
            // (see headroomFor's KDoc) is that it never blocks by accident.
            val sharedCash = if (paperMode) {
                PaperCapitalAuthority6577.snapshot().availableCashSol
            } else {
                val live7211 = try {
                    com.lifecyclebot.engine.BotService.status.walletSol
                } catch (_: Throwable) { Double.NaN }
                if (live7211.isFinite() && live7211 > 0.0) {
                    try {
                        PipelineHealthCollector.labelInc("LANE_HEADROOM_ON_LIVE_WALLET_7211")
                    } catch (_: Throwable) {}
                    live7211
                } else {
                    CanonicalCapitalAuthority6450.snapshot().cashSol
                }
            }
            val positions = CanonicalPositionAuthority6441.openPositions()
            // V5.0.6912 §BUDGETS_MUST_NOT_BE_SCALED_BY_PHANTOM_UNREALISED_GAINS.
            //
            // OPERATOR EVIDENCE (5.0.6909 WALLET SURFACES):
            //
            //   CASH               2.1427 SOL
            //   OPEN MARKET VALUE 28.4239 SOL      <- against 9.1660 of cost
            //   UNREALIZED PNL    19.2584 SOL
            //   TOTAL EQUITY      30.5666 SOL
            //   HERO_OPENMV_PER_POSITION_QUARANTINE_6604 ... ratio=4570.9x
            //     (1,583 occurrences; mint=SLNDpmoWTV costBasis=0.055 rawMark=251.40)
            //
            // This used cash + openMarketValue as the equity that every lane
            // target is a fraction of. 19.26 of that 30.57 SOL is unrealised
            // gain on marks the system's OWN quarantine authority is rejecting
            // as fallback at ratios up to 4,570x. So every lane's budget was
            // being scaled by roughly 2.7x of fiction, which is why utilisation
            // read low, why hasHeadroom kept returning true, and why the
            // specialist report (which uses cost basis and showed BLUECHIP at
            // 368%) and this authority disagreed about the same lane.
            //
            // A budget denominated in unrealised profit grows every time a
            // phantom mark spikes — it hands out the most permission exactly
            // when the price data is least trustworthy. Use cash plus
            // REMAINING COST BASIS instead: money actually paid in, plus money
            // actually available. Same basis the numerator (`used`) is measured
            // in, so the ratio is finally comparing like with like. Realised
            // gains still grow the budget, through cash.
            val openCostBasisSol6912 = positions
                .filter { it.mode.equals(mode, true) }
                .sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
            val sharedEquity = (sharedCash + openCostBasisSol6912).coerceAtLeast(0.0)
            val laneOwned = positions.filter {
                it.mode.equals(mode, true) && (
                    it.lane.equals(nl, true) || (nl == "BLUECHIP" && it.lane.equals("BLUE_CHIP", true))
                )
            }
            val used = laneOwned.sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
            // V5.0.7185 — one pass for EVERY specialist's committed basis, so
            // the target below can tell a lane that is out of room from a lane
            // whose share is simply sitting unclaimed. Same mode filter and
            // same BLUE_CHIP alias as laneOwned above.
            val usedByLane7185 = HashMap<String, Double>(MEME_LANES.size)
            for (p in positions) {
                if (!p.mode.equals(mode, true)) continue
                val pl = normLane(p.lane).let { if (it == "BLUE_CHIP") "BLUECHIP" else it }
                if (pl !in MEME_LANES) continue
                val basis = (p.entryCostSol - p.soldCostBasisSol).coerceAtLeast(0.0)
                usedByLane7185[pl] = (usedByLane7185[pl] ?: 0.0) + basis
            }
            val targetSol = laneTargetSol(nl, sharedEquity, usedByLane7185)
            val util = if (targetSol > 0.0) used / targetSol else 0.0
            val ok = util < LANE_HEADROOM_RATIO
            if (ok) {
                try { PipelineHealthCollector.labelInc("LANE_HEADROOM_OK_6732_${nl}") } catch (_: Throwable) {}
            } else {
                try { PipelineHealthCollector.labelInc("LANE_HEADROOM_SATURATED_6732_${nl}") } catch (_: Throwable) {}
            }
            Headroom(ok, nl, used, targetSol, util)
        } catch (_: Throwable) {
            Headroom(true, nl, 0.0, 0.0, 0.0)
        }
    }

    /** Convenience: does this lane still have room to open a new position? */
    fun hasHeadroom(mode: String, lane: String): Boolean = headroomFor(mode, lane).hasHeadroom

    /** V5.0.6732 — compute the target SOL allocation for a lane from its
     * expectancy + opportunity weight (same formula ToolkitSignalSheet uses).
     * Kept intentionally simple; the specialist report is the observability
     * surface, this authority is the enforcement surface. */
    private fun laneTargetSol(
        lane: String,
        sharedEquity: Double,
        usedByLane7185: Map<String, Double> = emptyMap(),
    ): Double {
        if (sharedEquity <= 0.0) return 0.0
        val weights = MEME_LANES.associateWith { l ->
            val expectancy = try { com.lifecyclebot.engine.LaneExpectancyDamper.sizeMultiplier(l) } catch (_: Throwable) { 1.0 }
            // 6732: opportunity weight is deliberately flat here (1.0). Using
            // qualified counts would tie fairness to scanner traffic, which
            // conflates "this lane has candidates to admit" with "this lane
            // has budget". Budget fairness must be independent of traffic.
            expectancy.coerceIn(0.25, 1.50)
        }
        val weightSum = weights.values.sum().coerceAtLeast(0.01)
        val laneWeight = weights[lane] ?: 1.0
        val nominal7185 = sharedEquity * (laneWeight / weightSum)

        // V5.0.7185 §A_SHARE_NOBODY_IS_CLAIMING_IS_NOT_A_SHARE.
        //
        // The lanes are specialist traders in a hive, and this divides the
        // book across all twelve of them by expectancy weight — so the targets
        // sum to the WHOLE equity whether or not a specialist is using its
        // share. A lane sitting idle still reserves its slice, and a lane that
        // is actually trading is told it is over budget while that slice goes
        // unspent.
        //
        // Operator 5.0.7176, the shape of it:
        //
        //   EXPRESS   target 1.8901  used 0.0000  openPositions=0   <- largest
        //   MOONSHOT  target 0.9242  used 0.4531  openPositions=9   <- smallest
        //   CORE      target 1.1441  used 2.2687  util 1.98x  -> blocked
        //   QUALITY   target 1.0045  used 1.8274  util 1.82x  -> blocked
        //   SHITCOIN / DIP_HUNTER / MANIPULATED / TREASURY / CASHGEN
        //                             used 0.0000 each, ~8.5 SOL reserved
        //
        // Five specialists that have never produced a single buy intent were
        // holding roughly eight and a half SOL of reserved budget, while the
        // three that were trading got EXIT_THROUGHPUT_BLOCKED_LANE_OVERSPEND
        // _6912 = 496 times. That is the operator's standing rule broken by
        // bookkeeping: "dont miss profitable trade opportunities if the
        // capital is there to fund the trade."
        //
        // So unclaimed nominal is released to the specialists that are at or
        // over their own share, split by the same expectancy weight — the
        // hive reallocates to whoever is actually working. A lane still inside
        // its nominal keeps exactly the old number, so nothing changes for the
        // idle case and the fairness intent of 6732/6912 is untouched: a
        // genuinely bleeding lane is damped by its expectancy weight, which is
        // what shrinks both its nominal AND its share of the release.
        //
        // Bounded by MAX_CLAIM_MULT_7185 so one specialist cannot absorb the
        // whole hive's budget on a single good streak. Free CASH remains the
        // real ceiling regardless — OrderSizeResolver6441 clamps every order
        // to cashCap, and V5.0.7177 already re-engages the overspend block
        // when cash is genuinely contended.
        val ownUsed7185 = usedByLane7185[lane] ?: 0.0
        if (usedByLane7185.isEmpty() || ownUsed7185 < nominal7185) return nominal7185

        // V5.0.7342 §IDLE_CAPITAL_GOES_TO_THE_LANES_THAT_EARN_IT.
        //
        // 5.0.7340, 27 minutes in: cash 0.81 of 13.12 equity, 81 positions.
        // QUALITY (n=5, EV -11.4%) enforcedTarget 2.92 used 2.76, TREASURY
        // (n=7, EV -0.2%) enforcedTarget 2.92 used 2.13 — 4.9 SOL, 40% of the
        // book, parked in the two lanes that lose, while PROJECT_SNIPER
        // (+37.8%), CORE (+29.2%) and MOONSHOT (+26.9%) were refused for cash.
        // The release below handed idle budget to whoever was SPENDING, weighted
        // by a damper multiplier that barely separates them (0.88 vs 1.12), so
        // buying fast was rewarded the same as earning. A lane whose own closes
        // measure negative expectancy keeps its full nominal share — it is not
        // throttled or disabled — but it does not absorb other lanes' idle
        // capital. That capital compounds only where it has been shown to grow.
        val laneEv7342 = try {
            com.lifecyclebot.engine.LiveProbabilityEngine.laneSnapshots()
                .associate { normLane(it.lane).let { l -> if (l == "BLUE_CHIP") "BLUECHIP" else l } to it }
        } catch (_: Throwable) { emptyMap() }
        fun measuredLoser7342(l: String): Boolean =
            laneEv7342[l]?.let { it.sample >= MIN_CLOSES_FOR_RELEASE_EVIDENCE_7342 && it.evPct < 0.0 } == true
        if (measuredLoser7342(lane)) {
            try {
                PipelineHealthCollector.labelInc("LANE_RELEASE_WITHHELD_NEGATIVE_EV_7342")
                PipelineHealthCollector.labelInc("LANE_RELEASE_WITHHELD_NEGATIVE_EV_7342_$lane")
            } catch (_: Throwable) {}
            return nominal7185
        }

        var released7185 = 0.0
        var demandWeight7185 = 0.0
        for (l in MEME_LANES) {
            val w = weights[l] ?: 1.0
            val n = sharedEquity * (w / weightSum)
            val u = usedByLane7185[l] ?: 0.0
            if (u < n) released7185 += (n - u) else if (!measuredLoser7342(l)) demandWeight7185 += w
        }
        if (released7185 <= 0.0 || demandWeight7185 <= 0.0) return nominal7185

        val claim7185 = released7185 * (laneWeight / demandWeight7185)
        return (nominal7185 + claim7185).coerceAtMost(nominal7185 * MAX_CLAIM_MULT_7185)
    }
}
