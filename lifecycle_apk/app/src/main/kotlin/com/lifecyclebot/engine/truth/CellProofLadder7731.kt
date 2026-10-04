package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7731 §ONE_PROOF_LADDER.
 *
 * Nineteen learners each hold their own bootstrap threshold (8, 10, 15, 20,
 * 30, 60 ...) and none of them is fed enough closes to clear it. This is the
 * single measured gate they were all approximating, read from the forward
 * labels ForwardReturnLabeler7731 books on every candidate the gate rules on:
 *
 *   UNPROVEN   fewer than [PROOF_MIN_N_7731] 60-minute labels in the cell, or
 *              most of the cell's observations lost their mark. Nothing is
 *              tightened: the operator's rule is learn before you tighten.
 *   NEGATIVE   [PROOF_MIN_N_7731]+ labels, mean net return at 60 minutes
 *              below [NEGATIVE_MEAN_PCT_7731] by more than one standard error,
 *              and under half of them winners. A LIVE entry in this cell is
 *              refused (CELL_PROOF_NEGATIVE_7731). Paper is never refused:
 *              paper is how the next hundred labels get made.
 *   POSITIVE   [PROOF_MIN_N_7731]+ labels, mean above [POSITIVE_MEAN_PCT_7731]
 *              by more than one standard error. Tagged for the operator and
 *              for sizing to read; this build only records it.
 *
 * The cell is source | lane | market-cap band | age band, so "pump.fun create,
 * MOONSHOT, under $10k, under 15 minutes" is judged on its own record and not
 * on BLUECHIP's, and a lane that pays in one band is not shut by the band
 * where it bleeds.
 */
object CellProofLadder7731 {
    const val PROOF_MIN_N_7731 = 100
    private const val NEGATIVE_MEAN_PCT_7731 = -2.0
    private const val POSITIVE_MEAN_PCT_7731 = 1.0
    private const val MIN_RESOLVED_SHARE_7731 = 0.5

    enum class Tier { UNPROVEN, NEGATIVE, POSITIVE }

    private val liveBlocks = AtomicLong(0)
    private val positiveReads = AtomicLong(0)
    private val unprovenReads = AtomicLong(0)
    @Volatile private var lastBlock: String = ""

    /**
     * V5.0.7769 — a cell's 4-hour record overrules a negative 60-minute read.
     * Launch cells pay late: the run that takes a $6k pump.fun create to $1M is
     * hours long, and the 60-minute mean books it before it has happened. A cell
     * is not proven negative while [PROOF_240_MIN_N_7769]+ of its 4-hour labels
     * say otherwise (Field Manual §12: judge the strategy on its tested
     * distribution, not on the slice that is cheapest to measure).
     */
    private const val PROOF_240_MIN_N_7769 = 30

    /** Pure. */
    fun tierFor(stat: ForwardReturnLabeler7731.CellStat?): Tier {
        if (stat == null || stat.n60 < PROOF_MIN_N_7731) return Tier.UNPROVEN
        if (stat.n240 >= PROOF_240_MIN_N_7769 && stat.meanNet240Pct >= NEGATIVE_MEAN_PCT_7731 &&
            stat.meanNet60Pct - (if (stat.stderr60Pct.isFinite()) stat.stderr60Pct else 0.0) <= POSITIVE_MEAN_PCT_7731) return Tier.UNPROVEN
        val se = if (stat.stderr60Pct.isFinite()) stat.stderr60Pct else 0.0
        // V5.0.7753 — a cell with most of its marks lost was unjudgeable, so
        // PUMP_PORTAL_WS|PROJECT_SNIPER (n=494, net -16.1%, wr 8%, lost=1112) and
        // PUMP_PORTAL_WS|SHITCOIN (n=940, net -10.5%, lost=1734) kept every live
        // slot they wanted. A lost mark is not assumed to be a loss: the cell is
        // negative only if it stays negative with every lost observation booked
        // flat (break-even less the round-trip cost), the most generous reading
        // of a token no feed can price. Field Manual §12: small or doubtful
        // samples stay uncertain; this one is neither small nor doubtful.
        if (stat.resolvedShare < MIN_RESOLVED_SHARE_7731) {
            return if (stat.meanNet60Pct + se < NEGATIVE_MEAN_PCT_7731 && stat.winRate60 < 0.5 &&
                lostFlatMeanPct7753(stat) < NEGATIVE_MEAN_PCT_7731) Tier.NEGATIVE else Tier.UNPROVEN
        }
        return when {
            stat.meanNet60Pct + se < NEGATIVE_MEAN_PCT_7731 && stat.winRate60 < 0.5 -> Tier.NEGATIVE
            stat.meanNet60Pct - se > POSITIVE_MEAN_PCT_7731 -> Tier.POSITIVE
            else -> Tier.UNPROVEN
        }
    }

    /** Pure: the cell's 60-minute mean if every lost observation had closed flat, less the round-trip cost. */
    fun lostFlatMeanPct7753(stat: ForwardReturnLabeler7731.CellStat): Double {
        val n = stat.n60 + stat.lost
        if (n <= 0) return 0.0
        return (stat.meanNet60Pct * stat.n60 - FieldManual7715.BASE_ROUND_TRIP_COST_PCT_7715 * stat.lost) / n
    }

    /**
     * LIVE only. Returns a block reason when the candidate's cell has proven
     * negative on the labeler's own record; null otherwise (paper always null).
     */
    fun liveBlockReason(ts: TokenState, lane: String, paper: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        if (paper) return null
        val stat = try { ForwardReturnLabeler7731.cellStatFor(ts, lane, nowMs) } catch (_: Throwable) { null }
        return when (tierFor(stat)) {
            Tier.NEGATIVE -> {
                liveBlocks.incrementAndGet()
                lastBlock = "${stat?.key} n=${stat?.n60} net=${"%+.1f".format(stat?.meanNet60Pct ?: 0.0)}%"
                try {
                    PipelineHealthCollector.labelInc("CELL_PROOF_NEGATIVE_7731")
                    PipelineHealthCollector.labelInc("CELL_PROOF_NEGATIVE_7731_${lane.trim().uppercase().take(20)}")
                    ForensicLogger.lifecycle(
                        "CELL_PROOF_NEGATIVE_7731",
                        "mint=${ts.mint.take(10)} sym=${ts.symbol} cell=${stat?.key} n60=${stat?.n60} " +
                            "net60=${"%+.1f".format(stat?.meanNet60Pct ?: 0.0)}% wr=${"%.0f".format((stat?.winRate60 ?: 0.0) * 100)}% " +
                            "lost=${stat?.lost} action=live_entry_refused_on_measured_cell_expectancy",
                    )
                } catch (_: Throwable) {}
                "CELL_PROOF_NEGATIVE_7731"
            }
            Tier.POSITIVE -> {
                positiveReads.incrementAndGet()
                try { PipelineHealthCollector.labelInc("CELL_PROOF_POSITIVE_7731") } catch (_: Throwable) {}
                null
            }
            Tier.UNPROVEN -> { unprovenReads.incrementAndGet(); null }
        }
    }

    fun statusLine(): String =
        "bar=n60>=$PROOF_MIN_N_7731,resolved>=${(MIN_RESOLVED_SHARE_7731 * 100).toInt()}%,neg<${NEGATIVE_MEAN_PCT_7731}%-se,pos>+${POSITIVE_MEAN_PCT_7731}%+se " +
            "liveBlocks=${liveBlocks.get()} positiveReads=${positiveReads.get()} unprovenReads=${unprovenReads.get()}" +
            (if (lastBlock.isNotBlank()) " lastBlock=[$lastBlock]" else "") +
            " read=paper_is_never_refused_live_is_refused_only_on_a_hundred_labels"
}
