package com.lifecyclebot.engine.cortex

/**
 * V5.0.8153 §EXIT_TRUTH_FROM_PROCEEDS — an exit is graded on the SOL it actually banked, the value it left in the
 * position, and what the sold quantity did afterwards.
 *
 * The exit cortex (CortexExit7897) graded every per-minute sample on the token's gross 30-minute move — the value of
 * HOLDING at that moment — and its realised record was one whole-position return per close. Neither looked at the
 * partial legs: a +80% half sold early and a final half dumped at +20% read the same as one +50% exit, and nothing
 * asked whether the quantity sold went on to run. Here, per closed position:
 *   proceeds       every canonical sell leg's NET proceeds (EconomicEventSchema6464.Sell) plus the confirmed legacy
 *                  fallback proceeds the canonical book does not carry (ExitTruthProceedsBook8144 via
 *                  RecoveryFallbackLedger8141) — chain-verified SOL, never a mark-to-market;
 *   remaining      the value still held (0 at a terminal close; a partial grade passes the live remainder);
 *   banked / total (proceeds [+ remaining]) over the entry cost basis;
 *   regret         per priced leg, what the sold quantity would be worth at the later mark minus what it banked, summed
 *                  over the cost basis — positive = the exits cut a move that kept going, negative = they saved money.
 * A leg with no recorded exit price (legacy rows, fallback fills) counts in proceeds and never in regret.
 * Pure: no Android, no I/O.
 */
object LearningExitTruth8153 {
    /** Regret above this share of the cost basis marks a close whose exits cut a runner. */
    const val TAIL_CUT_REGRET_PCT_8153 = 50.0
    private const val BASIS_MAX_RATIO_8153 = 20.0
    private const val MIN_BOOK_N_8153 = 8
    /** A lane's exits are proven to cut winners when mean regret - SE clears this (percent of cost). */
    const val TAIL_CUT_PROOF_PCT_8153 = 10.0

    /** One sell leg as recorded on the canonical book (quantities as Double: only their ratio is used). */
    data class SellLeg8153(
        val atMs: Long,
        val soldQty: Double,
        val qtyBefore: Double,
        val netProceedsSol: Double,
        val allocatedCostSol: Double,
        val remainingCostSol: Double,
        val exitPriceUsd: Double,
    )

    data class Slice8153(val soldFraction: Double, val netProceedsSol: Double, val exitPriceUsd: Double, val atMs: Long)

    data class Grade8153(
        val costSol: Double,
        val proceedsSol: Double,
        val remainingValueSol: Double,
        val bankedPct: Double,
        val totalPct: Double,
        val regretSol: Double,
        val regretPct: Double,
        val pricedSlices: Int,
    ) {
        val tailCut: Boolean get() = regretPct.isFinite() && regretPct > TAIL_CUT_REGRET_PCT_8153
    }

    /**
     * Pure: the entry cost basis and the slices of a position from its canonical sell legs (any order) plus
     * [fallbackSol] of confirmed proceeds the canonical book does not carry. Null without a positive cost basis.
     */
    fun fromLegs8153(legs: List<SellLeg8153>, fallbackSol: Double): Pair<Double, List<Slice8153>>? {
        val ok = legs.filter { it.netProceedsSol.isFinite() && it.soldQty.isFinite() && it.soldQty > 0.0 }.sortedBy { it.atMs }
        if (ok.isEmpty()) return null
        val first = ok.first()
        val cost = first.allocatedCostSol + first.remainingCostSol
        if (!cost.isFinite() || cost <= 0.0) return null
        val total = if (first.qtyBefore.isFinite() && first.qtyBefore >= first.soldQty) first.qtyBefore else ok.sumOf { it.soldQty }
        val slices = ok.map {
            Slice8153((it.soldQty / total).coerceIn(0.0, 1.0), it.netProceedsSol.coerceAtLeast(0.0), it.exitPriceUsd, it.atMs)
        }.toMutableList()
        if (fallbackSol.isFinite() && fallbackSol > 0.0) slices.add(Slice8153(0.0, fallbackSol, 0.0, ok.last().atMs))
        return cost to slices
    }

    /** Pure: the grade of a position's exits, with [laterPriceUsd] the mark one exit horizon after the close. */
    fun grade8153(costSol: Double, slices: List<Slice8153>, remainingValueSol: Double, laterPriceUsd: Double): Grade8153? {
        if (!costSol.isFinite() || costSol <= 0.0) return null
        var proceeds = 0.0; var regret = 0.0; var priced = 0
        for (s in slices) {
            if (!s.netProceedsSol.isFinite() || s.netProceedsSol <= 0.0) continue
            proceeds += s.netProceedsSol
            if (!s.exitPriceUsd.isFinite() || s.exitPriceUsd <= 0.0 || !laterPriceUsd.isFinite() || laterPriceUsd <= 0.0) continue
            val ratio = laterPriceUsd / s.exitPriceUsd
            if (ratio > BASIS_MAX_RATIO_8153 || ratio < 1.0 / BASIS_MAX_RATIO_8153) continue
            regret += s.netProceedsSol * ratio - s.netProceedsSol
            priced++
        }
        val remaining = if (remainingValueSol.isFinite() && remainingValueSol > 0.0) remainingValueSol else 0.0
        return Grade8153(
            costSol, proceeds, remaining, (proceeds / costSol - 1.0) * 100.0, ((proceeds + remaining) / costSol - 1.0) * 100.0,
            if (priced > 0) regret else Double.NaN, if (priced > 0) regret / costSol * 100.0 else Double.NaN, priced,
        )
    }

    /** Per lane and mode: the realised exit record (banked total and regret), decay-free, bounded by the caller. */
    class Book8153 {
        var n = 0; var sumTotal = 0.0; var regretN = 0; var sumRegret = 0.0; var sumSqRegret = 0.0; var tailCuts = 0

        fun add(g: Grade8153) {
            if (!g.totalPct.isFinite()) return
            n++; sumTotal += g.totalPct
            if (g.regretPct.isFinite()) {
                regretN++; sumRegret += g.regretPct; sumSqRegret += g.regretPct * g.regretPct
                if (g.tailCut) tailCuts++
            }
        }

        fun meanRegret(): Double = if (regretN > 0) sumRegret / regretN else Double.NaN

        fun seRegret(): Double {
            if (regretN < 2) return Double.POSITIVE_INFINITY
            val m = sumRegret / regretN
            val v = ((sumSqRegret - regretN * m * m) / (regretN - 1)).coerceAtLeast(0.0)
            return kotlin.math.sqrt(v / regretN)
        }

        /** The lane's exits measurably cut moves that kept going: [MIN_BOOK_N_8153]+ priced closes, mean - SE > proof. */
        fun tailCutProven8153(): Boolean =
            regretN >= MIN_BOOK_N_8153 && meanRegret() - seRegret() > TAIL_CUT_PROOF_PCT_8153

        fun line(): String = if (n == 0) "-" else
            "n$n total=${"%+.1f".format(sumTotal / n)}% regret=${if (regretN > 0) "%+.1f".format(meanRegret()) else "-"}%/n$regretN tailCuts=$tailCuts${if (tailCutProven8153()) " TAIL_CUTTING" else ""}"

        fun encode(): String = "$n,$sumTotal,$regretN,$sumRegret,$sumSqRegret,$tailCuts"

        fun decode(s: String): Boolean {
            val f = s.split(',')
            if (f.size != 6) return false
            n = f[0].toIntOrNull() ?: return false
            sumTotal = f[1].toDoubleOrNull() ?: 0.0; regretN = f[2].toIntOrNull() ?: 0
            sumRegret = f[3].toDoubleOrNull() ?: 0.0; sumSqRegret = f[4].toDoubleOrNull() ?: 0.0; tailCuts = f[5].toIntOrNull() ?: 0
            return true
        }
    }
}
