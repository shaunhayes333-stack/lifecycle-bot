package com.lifecyclebot.engine.truth

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/**
 * V5.0.8169 — one live round trip's friction, split into sides that ADD UP, with impossible readings refused.
 *
 * 5.0.8168 cost ledger: MOONSHOT median 9.39% buySide=23.69%; SHITCOIN median -13.84% buySide=-9.48%. Two faults:
 *  - TradeCostMeasurement8090.buyPct is entry friction over the MARKET value at the entry mark while costPct is total
 *    friction over the SOL spent, so buySide could exceed the round trip and the two never summed;
 *  - friction is "paid SOL minus the market value at the tape mark 3 s before the fill" (and the same at each sale). On a
 *    fast pump the mark is a different price, not our price: a mark above the fill makes friction NEGATIVE (we "bought
 *    cheaper than the market"), a mark well below it books the market's own move as buy-side friction. A negative cost
 *    is impossible; a buy-side friction beyond the buy's own slippage bound + fees cannot be friction either.
 *
 * Here both sides are over the SOL spent (buyPct + sellPct == costPct exactly). A side below -[NEG_TOLERANCE_PCT_8169]
 * or a buy side above [MAX_BUY_SIDE_PCT_8169] is MARK_INCONSISTENT: the sample is not a cost measurement and is never
 * learned (counted, never silently zeroed). A side within the tolerance (rounding, a tick of mark noise) is floored at 0.
 * The entry network fee (base + priority, TradeVerifier meta.fee) is reported as its own fixed component; ATA rent is
 * already excluded from the SOL spent and received (TradeVerifier.buySolSpent7928 / sellSolReceived7928).
 */
object CostSides8169 {
    /** A side may read this far below zero from rounding / mark ticks before the marks are called inconsistent. */
    const val NEG_TOLERANCE_PCT_8169 = 1.0
    /**
     * The most a genuine buy can pay over the market at the fill: the widest live buy slippage bound (10%, the legacy
     * PumpPortal / urgent tolerance) plus venue, route and app fees (~2.25%/side) plus fixed legs on a small ticket.
     */
    const val MAX_BUY_SIDE_PCT_8169 = 15.0

    enum class Verdict8169 { MEASURED, MARK_INCONSISTENT, INVALID, SELL_SIDE_ONLY }

    data class Sides8169(
        val verdict: Verdict8169,
        val buyPct: Double,
        val sellPct: Double,
        val costPct: Double,
        val entryFeePct: Double,
    )

    private val INVALID = Sides8169(Verdict8169.INVALID, Double.NaN, Double.NaN, Double.NaN, Double.NaN)

    /**
     * Pure: the sides of one round trip. [spentSol] = SOL paid incl. network fee and tip, rent excluded; [entryFeeSol] =
     * the buy's network fee (a subset of [spentSol]); [raw]/[decimals] the tokens bought (= sold, all [sales]).
     */
    fun sides8169(spentSol: Double, entryFeeSol: Double, raw: BigInteger, decimals: Int, entryMarkUsd: Double,
                  entrySolUsd: Double, sales: List<TradeCostMeasurement8090.Sale>): Sides8169 {
        fun valid(x: Double) = x.isFinite() && x > 0.0
        if (!valid(spentSol) || raw.signum() <= 0 || decimals !in 0..255 || !valid(entryMarkUsd) || !valid(entrySolUsd) ||
            sales.isEmpty()) return INVALID
        if (sales.fold(BigInteger.ZERO) { a, s -> a + s.raw } != raw) return INVALID
        val mc = MathContext.DECIMAL128
        fun marketSol(q: BigInteger, usd: Double, solUsd: Double): BigDecimal =
            BigDecimal(q, decimals).multiply(BigDecimal.valueOf(usd), mc).divide(BigDecimal.valueOf(solUsd), mc)
        val spent = BigDecimal.valueOf(spentSol)
        val buyFriction = spent.subtract(marketSol(raw, entryMarkUsd, entrySolUsd))
        var sellFriction = BigDecimal.ZERO
        for (s in sales) {
            if (s.raw.signum() <= 0 || !valid(s.markUsd) || !valid(s.solUsd) || !s.netSol.isFinite() || s.netSol < 0.0) return INVALID
            sellFriction = sellFriction.add(marketSol(s.raw, s.markUsd, s.solUsd).subtract(BigDecimal.valueOf(s.netSol)))
        }
        val hundred = BigDecimal(100)
        val buy = buyFriction.divide(spent, mc).multiply(hundred).toDouble()
        val sell = sellFriction.divide(spent, mc).multiply(hundred).toDouble()
        val fee = if (entryFeeSol.isFinite() && entryFeeSol > 0.0) entryFeeSol / spentSol * 100.0 else 0.0
        if (!buy.isFinite() || !sell.isFinite()) return INVALID
        val inconsistent = buy < -NEG_TOLERANCE_PCT_8169 || sell < -NEG_TOLERANCE_PCT_8169 || buy > MAX_BUY_SIDE_PCT_8169
        // V5.0.8223 — the entry mark on a fast pump is the usual contradiction (a mark 3 s before the fill is another
        // price); the sell legs' marks are read at the sale and most often hold. The sell side is then still a
        // measurement (ProfitExitTruth8163 / the paper exit simulator read it); only the round trip is unmeasured.
        if (inconsistent && sell >= -NEG_TOLERANCE_PCT_8169)
            return Sides8169(Verdict8169.SELL_SIDE_ONLY, Double.NaN, sell.coerceAtLeast(0.0), Double.NaN, fee)
        if (inconsistent) return Sides8169(Verdict8169.MARK_INCONSISTENT, buy, sell, buy + sell, fee)
        val b = buy.coerceAtLeast(0.0)
        val s = sell.coerceAtLeast(0.0)
        // The network fee was paid on the buy, so the buy side is never below it.
        val bf = maxOf(b, fee.coerceAtMost(MAX_BUY_SIDE_PCT_8169))
        return Sides8169(Verdict8169.MEASURED, bf, s, bf + s, fee)
    }

    /** Pure: a persisted ledger row ([costPct, ticketSol, buyPct, ...]) that can be learned: finite, non-negative, sane. */
    fun learnableRow8169(row: DoubleArray, maxSanePct: Double): Boolean {
        if (row.size < 3) return false
        val cost = row[0]; val buy = row[2]
        if (!cost.isFinite() || !buy.isFinite()) return false
        if (cost < 0.0 || cost > maxSanePct || buy < 0.0 || buy > MAX_BUY_SIDE_PCT_8169) return false
        // V5.0.8169 rows carry the sell side: the sides must add up to the round trip.
        if (row.size >= 4 && row[3].isFinite() && (row[3] < 0.0 || kotlin.math.abs(buy + row[3] - cost) > 1e-6)) return false
        return true
    }
}
