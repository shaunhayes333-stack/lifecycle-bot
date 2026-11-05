package com.lifecyclebot.engine.truth

/**
 * V5.0.8539 — a paper fill is only as honest as its price.
 *
 * 8539 paper run: TREASURY "Jimothy" filled at an entry implying a $4.62 M cap while the market sweep held it at $1.53 M
 * with $213 k of liquidity (a 3x wrong pair price); it then "crashed" -87 % in 12 s when the exit read the real price.
 * CASHGEN Hg5Ja5 booked a +2,126 % partial on a phantom mark. Every such row teaches the lanes, the labels and the Cortex
 * a move that never happened. A paper entry price is now checked against an independent read of the same coin (the market
 * sweep row, the DexScreener pair answer, the coin's own curve): if every independent read disagrees with it by more than
 * [MAX_RATIO] the fill is refused by name; with no independent read it proceeds (counted).
 */
object PaperFillTruth8539 {
    const val MAX_RATIO = 2.0
    private const val REF_MAX_AGE_MS = 5L * 60_000L
    const val REFUSAL = "PAPER_FILL_PRICE_UNCORROBORATED_8539"

    /** Pure: is [fill] off by more than [MAX_RATIO] from every positive reference (false with no reference)? */
    fun disagrees(fill: Double, references: List<Double>): Boolean {
        if (!fill.isFinite() || fill <= 0.0) return false
        val refs = references.filter { it.isFinite() && it > 0.0 }
        if (refs.isEmpty()) return false
        return refs.all { r -> maxOf(fill / r, r / fill) > MAX_RATIO }
    }

    /** Executor.paperBuy: the refusal name when [fill] is uncorroborated, else null. */
    fun refusal(mint: String, fill: Double, nowMs: Long): String? = try {
        val refs = ArrayList<Double>(3)
        com.lifecyclebot.engine.market.MarketSweep7297.rowFor8169(mint, nowMs, REF_MAX_AGE_MS)?.priceUsd?.let { refs += it }
        PairFlow8169.peek8169(mint)?.takeIf { nowMs - it.atMs in 0L..REF_MAX_AGE_MS }?.priceUsd8417?.let { refs += it }
        CurveReserves7951.activeExitObservation(mint, nowMs, REF_MAX_AGE_MS)?.let { o ->
            val sol = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            val px = CurveReserves7951.priceSol7951(o.virtualSol, o.virtualTokens) * sol
            if (px.isFinite() && px > 0.0) refs += px
        }
        val bad = disagrees(fill, refs)
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc(when {
                refs.isEmpty() -> "PAPER_FILL_NO_INDEPENDENT_READ_8539"
                bad -> REFUSAL
                else -> "PAPER_FILL_CORROBORATED_8539"
            })
        } catch (_: Throwable) {}
        if (bad) REFUSAL else null
    } catch (_: Throwable) { null }
}
