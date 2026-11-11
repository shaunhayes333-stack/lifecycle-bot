package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Candle

/**
 * V5.0.8692 §THE_EXPERT_PLAYBOOK — owner: "research how to trade meme coins like an expert whale ... their cheat sheets,
 * guides, chart shapes ... apply that learning into how to trade".
 *
 * What the practitioner guides and studies agree on (sources in the 8692 commit):
 *  - Most participants lose; the ones who win are selective ("an avoidance game first and a prediction game second"), size
 *    small, and take profit systematically. Pump.fun's curve charges 1.25 % per side, so every trade starts several % down.
 *  - Entries that recur across guides: buy the dip of a coin that has already run, on a high-volume flush (red bar > 15 %,
 *    close > 20 % under the recent high, the last bars' volume above average; target the prior high); follow size on a
 *    brand-new coin (a buy > 5 SOL, or several large buys inside ~10 s); the first ~20 % pullback after migration.
 *  - Shapes to avoid: the spike-and-fade (a tall candle with a long upper wick, then shrinking bodies while sells fill the
 *    tape) and the staircase down (successively lower highs).
 *  - Concentration matters: this bot's own 41,547 labelled decisions agree (top holder < 15 % was the least-bad band in
 *    both halves, -4.2 % / -1.2 %, against -10 .. -16 % for 15-35 %).
 *
 * None of this is trusted on reputation. Every entry setup here is filed as its own forward-label cohort
 * (SETUP8692_ + family, ForwardReturnLabeler7731: executable marks, net of cost) and becomes a LIVE edge only when
 * that cohort is proven positive (LiveEdgeGate7877.cohortProvenPositive7948). The avoid shapes are LIVE doors that retire
 * themselves once their own graded refusals prove they refuse winners (GateOutcome8418.refusesWinners8418).
 */
object ExpertSetups8692 {
    private const val SETUP_PREFIX_8692 = "SETUP8692_"
    const val SPIKE_FADE_8692 = "EXPERT_AVOID_SPIKE_FADE_8692"
    const val STAIRCASE_DOWN_8692 = "EXPERT_AVOID_STAIRCASE_DOWN_8692"

    private fun hi(c: Candle) = if (c.highUsd > 0.0) c.highUsd else c.priceUsd
    private fun lo(c: Candle) = if (c.lowUsd > 0.0) c.lowUsd else c.priceUsd
    private fun open(c: Candle, prior: Candle?) = if (c.openUsd > 0.0) c.openUsd else prior?.priceUsd ?: c.priceUsd
    private fun real(bars: List<Candle>) = bars.filter { it.priceUsd > 0.0 && !it.synthetic }

    /**
     * Pure: the high-volume dip buy, on closed 1-minute [bars] (oldest first). The coin ran (window high >= 2x window low
     * before the dip), one of the last three bars is a red bar of >= 15 % with volume above the window average, the close
     * sits >= 20 % under the window high, and the last three bars' mean volume is above the window average. The why, or null.
     */
    fun dipBuyVolume8692(bars: List<Candle>): String? {
        val b = real(bars).takeLast(60)
        if (b.size < 15) return null
        val vols = b.mapNotNull { it.periodVolumeUsd8521?.takeIf { v -> v.isFinite() && v >= 0.0 } }
        if (vols.size < b.size * 3 / 4) return null            // a dip on unknown volume is not this setup
        val avgVol = vols.average()
        if (!(avgVol > 0.0)) return null
        val high = b.maxOf { hi(it) }
        val lowBefore = b.dropLast(3).minOfOrNull { lo(it) } ?: return null
        if (!(lowBefore > 0.0) || high < lowBefore * 2.0) return null
        val last3 = b.takeLast(3)
        val flush = last3.withIndex().firstOrNull { (i, c) ->
            val prior = b.getOrNull(b.size - 3 + i - 1)
            val o = open(c, prior)
            o > 0.0 && (o - c.priceUsd) / o >= 0.15 && (c.periodVolumeUsd8521 ?: 0.0) > avgVol
        } ?: return null
        val close = b.last().priceUsd
        if (close > high * 0.80) return null
        val last3Vol = last3.map { it.periodVolumeUsd8521 ?: 0.0 }.average()
        if (last3Vol <= avgVol) return null
        return "ran=${"%.1f".format(high / lowBefore)}x flushBar=${flush.index} offHigh=${"%.0f".format((1 - close / high) * 100)}% vol=${"%.1f".format(last3Vol / avgVol)}x"
    }

    /**
     * Pure: follow size on a new coin ([ageMin] < 5): one executed buy >= 5 SOL, or >= 3 executed buys of >= 1 SOL inside any
     * 10-second span, in the last 60 s of [prints] (oldest first). The tape carries no buyer wallet, so "several large buys"
     * cannot be checked for distinct wallets here. The why, or null.
     */
    fun followSize8692(prints: List<TradeTape8100.Print8128>, nowMs: Long, ageMin: Double?): String? {
        if (ageMin == null || !ageMin.isFinite() || ageMin >= 5.0) return null
        val buys = prints.filter { it.isBuy && !it.priceOnly && it.sol.isFinite() && nowMs - it.atMs in 0L..60_000L }
        buys.firstOrNull { it.sol >= 5.0 }?.let { return "whaleBuy=${"%.1f".format(it.sol)}SOL age=${"%.1f".format(ageMin)}m" }
        val large = buys.filter { it.sol >= 1.0 }.sortedBy { it.atMs }
        for (i in large.indices) {
            val inWindow = large.drop(i).takeWhile { it.atMs - large[i].atMs <= 10_000L }
            if (inWindow.size >= 3) return "largeBuys=${inWindow.size} in10s sol=${"%.1f".format(inWindow.sumOf { it.sol })} age=${"%.1f".format(ageMin)}m"
        }
        return null
    }

    /**
     * Pure: the spike-and-fade, on closed 1-minute [bars]. One of the last four bars spiked (range >= 10 % of its low) and left
     * an upper wick of >= 50 % of its range; every bar after it has a smaller body; the close is back under that bar's open
     * half-way; and the tape is sell-dominant ([sellDominant], when known).
     */
    fun spikeFade8692(bars: List<Candle>, sellDominant: Boolean?): Boolean {
        val b = real(bars).takeLast(6)
        if (b.size < 4) return false
        for (i in (b.size - 4).coerceAtLeast(1) until b.size - 1) {
            val c = b[i]; val o = open(c, b[i - 1])
            val range = hi(c) - lo(c)
            if (!(lo(c) > 0.0) || range / lo(c) < 0.10) continue
            val upperWick = hi(c) - maxOf(o, c.priceUsd)
            if (upperWick < 0.5 * range) continue
            val body = kotlin.math.abs(c.priceUsd - o)
            val after = b.drop(i + 1)
            val shrinking = after.withIndex().all { (k, a) -> kotlin.math.abs(a.priceUsd - open(a, if (k == 0) c else after[k - 1])) < body.coerceAtLeast(1e-30) }
            val faded = b.last().priceUsd <= o + (hi(c) - o) * 0.5
            if (shrinking && faded && sellDominant != false) return true
        }
        return false
    }

    /** Pure: the staircase down, on closed 1-minute [bars] — three successively lower swing highs in the last 15 bars, the close >= 10 % under the first. */
    fun staircaseDown8692(bars: List<Candle>): Boolean {
        val b = real(bars).takeLast(15)
        if (b.size < 7) return false
        val swings = (1 until b.size - 1).filter { hi(b[it]) > hi(b[it - 1]) && hi(b[it]) >= hi(b[it + 1]) }.map { hi(b[it]) }
        if (swings.size < 3) return false
        val last3 = swings.takeLast(3)
        return last3[0] > last3[1] && last3[1] > last3[2] && b.last().priceUsd <= last3[0] * 0.90
    }

    /** The forward-label cohort a setup signal is filed under. */
    fun cohort8692(family: String): String = SETUP_PREFIX_8692 + family.trim().uppercase()

    /** Pure: is [lane] a setup cohort (a pseudo-lane: no stage, trigger, liquidity or kind books)? */
    fun setupCohort8692(lane: String): Boolean = lane.startsWith(SETUP_PREFIX_8692)
}
