package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7871 — bounded, learned entry shaping for early launches.
 *
 * 5.0.7868 runtime evidence:
 *  - PLANWAIT_TOO_FEW_BARS n=106 net +11.7%: the plan waited on young tokens
 *    whose tape had not printed five bars yet, and the forward labels on those
 *    waits paid. Too few bars is uncertainty, not a danger shape.
 *  - Fresh-launch -15% ladder: EXPANDING|FLOW_OK|CONC_ONE n=15 ev15 +11%,
 *    PRE_IGNITION|FLOW_OK n=8 +9.4%; FLOW_WEAK / FLOW_SELL about -15%;
 *    SELL_DOMINANT_TAPE -51.9%.
 *
 * Field Manual §12 (authority is earned by measured, net-of-cost outcomes) and
 * §7 (size follows edge): nothing here lowers an entry floor or overturns a
 * structural refusal. TOO_FEW_BARS is admitted at reduced size only when every
 * other quality read is clean, and a measured cell moves size inside
 * [MIN_MULT, MAX_MULT], weighted by how many results it has. Size is shaped
 * before FinalDecisionGate seals it; never after.
 */
object LaunchEntryShaping7871 {
    const val MIN_MULT = 0.5
    const val MAX_MULT = 1.25
    /** Reduced size for an early launch admitted without five bars. */
    private const val TOO_FEW_BARS_MULT = 0.70
    /** FLOW_WEAK measured about -15% on the ladder. */
    private const val FLOW_WEAK_MULT = 0.80
    private const val CELL_MIN_N15 = 8
    private const val CELL_FULL_WEIGHT_N15 = 30
    private const val COST_PCT = 4.0
    private const val MIN_LIQ_USD = 5_000.0
    private const val MIN_CONFIDENCE = 50
    private const val UNCERTAIN_TTL_MS = 2L * 60_000L

    private val uncertainAt = ConcurrentHashMap<String, Long>()
    private val tooFewBarsAdmitted = AtomicLong(0)
    private val tooFewBarsRefused = ConcurrentHashMap<String, AtomicLong>()
    private val shapedUp = AtomicLong(0)
    private val shapedDown = AtomicLong(0)

    /**
     * Pure: the -15% ladder's size multiplier for a measured cell. Thin cells
     * (under [CELL_MIN_N15]) do not move size; past that the net edge moves it
     * by 1.5x its percent, weighted up to full at [CELL_FULL_WEIGHT_N15].
     */
    fun cellSizeMult(ev15Pct: Double, n15: Int, costPct: Double = COST_PCT): Double {
        if (n15 < CELL_MIN_N15 || !ev15Pct.isFinite()) return 1.0
        val weight = (n15.toDouble() / CELL_FULL_WEIGHT_N15).coerceAtMost(1.0)
        return (1.0 + weight * 1.5 * (ev15Pct - costPct) / 100.0).coerceIn(MIN_MULT, MAX_MULT)
    }

    /** Pure: the flow bucket's size multiplier (FLOW_SELL is refused structurally upstream). */
    fun flowSizeMult(flow: String): Double = when (flow) {
        "FLOW_WEAK" -> FLOW_WEAK_MULT
        "FLOW_SELL" -> MIN_MULT
        else -> 1.0
    }

    /** Pure: the combined, bounded multiplier. */
    fun combine(cellMult: Double, flowMult: Double, tooFewBars: Boolean): Double =
        (cellMult * flowMult * (if (tooFewBars) TOO_FEW_BARS_MULT else 1.0)).coerceIn(MIN_MULT, MAX_MULT)

    /**
     * Pure: may an early launch whose tape only says TOO_FEW_BARS be planned at
     * reduced size? Every quality read must be clean: real liquidity, no hard
     * safety block, flow measured and not sell-dominant, not a one-wallet pump,
     * native confidence, and the waits' own forward labels not proven negative.
     * Returns null to admit, else the named refusal.
     */
    fun tooFewBarsRefusal(
        liquidityUsd: Double,
        hardBlocks: Int,
        flow: String?,
        conc: String?,
        confidence: Int?,
        waitsProvenNegative: Boolean,
    ): String? = when {
        waitsProvenNegative -> "WAITS_NEGATIVE"
        !liquidityUsd.isFinite() || liquidityUsd < MIN_LIQ_USD -> "LIQ"
        hardBlocks > 0 -> "SAFETY"
        flow == null || flow == "FLOW_NONE" || flow == "FLOW_SELL" -> "FLOW"
        conc == null || conc == "CONC_ONE" -> "CONCENTRATION"
        confidence == null || confidence < MIN_CONFIDENCE -> "CONFIDENCE"
        else -> null
    }

    /** Pure: the flow and concentration buckets from the launch tape, or null when unmeasured. */
    private fun buckets(lp: LaunchPhaseAuthority7401.Snapshot?): Pair<String, String>? {
        if (lp == null) return null
        val flow = FreshLaunchSelector7737.flowBucket(lp.buyTx60s, lp.sellTx60s, lp.buySharePct, lp.distinctBuyers60s, lp.accelerationRising)
        val conc = FreshLaunchSelector7737.concentrationBucket(lp.largestBuyerSharePct60s, lp.distinctBuyers60s)
        return flow to conc
    }

    /**
     * TradePlan7739's TOO_FEW_BARS read: true admits the token as an early
     * launch at [TOO_FEW_BARS_MULT] size (recorded for the sizing step).
     */
    fun admitTooFewBars(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean {
        val lp = try { LaunchPhaseAuthority7401.snapshot(ts, nowMs) } catch (_: Throwable) { null }
        val b = buckets(lp)
        val waitStat = try { ForwardReturnLabeler7731.laneStatFor7737("PLANWAIT_TOO_FEW_BARS") } catch (_: Throwable) { null }
        val negative = try { CellProofLadder7731.tierFor(waitStat) == CellProofLadder7731.Tier.NEGATIVE } catch (_: Throwable) { false }
        val refusal = tooFewBarsRefusal(
            liquidityUsd = ts.lastLiquidityUsd,
            hardBlocks = ts.safety.hardBlockReasons.size,
            flow = b?.first,
            conc = b?.second,
            confidence = ts.lastV3Confidence,
            waitsProvenNegative = negative,
        )
        if (refusal != null) {
            tooFewBarsRefused.computeIfAbsent(refusal) { AtomicLong(0) }.incrementAndGet()
            return false
        }
        uncertainAt[ts.mint] = nowMs
        if (uncertainAt.size > 1_000) uncertainAt.entries.removeIf { nowMs - it.value > UNCERTAIN_TTL_MS }
        tooFewBarsAdmitted.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("PLAN_TOO_FEW_BARS_ADMITTED_REDUCED_7871")
            ForensicLogger.lifecycle(
                "PLAN_TOO_FEW_BARS_ADMITTED_REDUCED_7871",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} liq=${ts.lastLiquidityUsd.toInt()} flow=${b?.first} conc=${b?.second} " +
                    "conf=${ts.lastV3Confidence} waits=${waitStat?.n60 ?: 0}/${"%+.1f".format(waitStat?.meanNet60Pct ?: 0.0)}% size=x$TOO_FEW_BARS_MULT",
            )
        } catch (_: Throwable) {}
        return true
    }

    /**
     * The bounded live size multiplier for [ts] at seal time (1.0 for paper and
     * for anything that is neither a fresh launch nor a reduced-size admission).
     */
    fun sizeMult(ts: TokenState, paper: Boolean, nowMs: Long = System.currentTimeMillis()): Double {
        if (paper) return 1.0
        val tooFewBars = uncertainAt[ts.mint]?.let { nowMs - it in 0L..UNCERTAIN_TTL_MS } == true
        val read = try { FreshLaunchSelector7737.shapingRead7871(ts, nowMs) } catch (_: Throwable) { null }
        val cell = read?.second
        val cellMult = if (cell == null) 1.0 else cellSizeMult(cell.ev15Pct, cell.n15)
        val flow = read?.first?.split('|')?.getOrNull(1).orEmpty()
        val mult = combine(cellMult, flowSizeMult(flow), tooFewBars)
        if (mult > 1.0 + 1e-9) shapedUp.incrementAndGet() else if (mult < 1.0 - 1e-9) shapedDown.incrementAndGet()
        if (mult != 1.0) try { PipelineHealthCollector.labelInc(if (mult > 1.0) "LAUNCH_SIZE_SHAPED_UP_7871" else "LAUNCH_SIZE_SHAPED_DOWN_7871") } catch (_: Throwable) {}
        return mult
    }

    /**
     * Pure: the shaped size. A size-down never takes a request that met the
     * executable minimum below it (operator: size down, never stop trading);
     * a size-up is still capped by every downstream depth/risk/wallet cap.
     */
    fun shapedSize(requested: Double, mult: Double, minimum: Double): Double {
        if (!requested.isFinite() || requested <= 0.0) return requested
        val shaped = requested * mult.coerceIn(MIN_MULT, MAX_MULT)
        return if (shaped < requested) maxOf(shaped, minOf(requested, minimum)) else shaped
    }

    fun statusLine(): String =
        "tooFewBars[admitted=${tooFewBarsAdmitted.get()} refused=${tooFewBarsRefused.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }}] " +
            "size[up=${shapedUp.get()} down=${shapedDown.get()}] bounds=x$MIN_MULT..x$MAX_MULT"
}
