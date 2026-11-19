package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder

/**
 * V5.0.8873 §THE_WHOLE_NETWORK_IS_MEASURED — owner, 20 Nov: "the meme trader isnt a pumpfun trader exclusively is a solana
 * network trader. the full network" / "there's an entire network that does billions of dollars a day in trade. the fresh
 * launches are one element of a massive stack that is the meme trader alone".
 *
 * The edge datasets of 9-20 Nov: 82-87 % of the coins the bot decided on were under an hour old and 80 % were pump.fun mints;
 * established coins (over $1 M, or over a day old and over $100 k) were 4.6-6.7 %. Off pump.fun, coins at $5 k-$20 k netted
 * +7.5 % unscored (9-17 Nov, 614 coins) while pump.fun mints in the same band lost 19.2 %.
 *
 * This names the tier of the network every labelled decision came from and keeps a running count and mean outcome per tier,
 * per venue family and per mint origin, so each report shows where the bot looks and where it is paid. It decides nothing.
 */
object NetworkTier8873 {
    enum class Tier8873 { FRESH, YOUNG, OLDER_SMALL, ESTABLISHED, UNKNOWN }

    /**
     * Pure: the tier of a coin [ageMin] minutes old at [mcapUsd]. Established: $1 M or more, or a day old and $100 k or more.
     * Fresh: under an hour. Young: an hour to a day. Older small: a day or more, under $100 k.
     */
    fun tierOf8873(ageMin: Double, mcapUsd: Double): Tier8873 {
        val cap = if (mcapUsd.isFinite() && mcapUsd > 0.0) mcapUsd else Double.NaN
        if (cap.isFinite() && cap >= 1_000_000.0) return Tier8873.ESTABLISHED
        if (!ageMin.isFinite() || ageMin < 0.0) return Tier8873.UNKNOWN
        return when {
            ageMin < 60.0 -> Tier8873.FRESH
            ageMin < 1_440.0 -> Tier8873.YOUNG
            cap.isFinite() && cap >= 100_000.0 -> Tier8873.ESTABLISHED
            else -> Tier8873.OLDER_SMALL
        }
    }

    /** Pure: the venue family of a LearnerColumns8798.venueOf code. */
    fun venueName8873(code: Double): String = when (code) {
        0.0 -> "PUMP_CURVE"; 1.0 -> "PUMPSWAP"; 2.0 -> "LAUNCHLAB"; 3.0 -> "RAYDIUM"; 4.0 -> "METEORA"; 5.0 -> "ORCA"; 6.0 -> "OTHER_DEX"
        else -> "UNREAD"
    }

    private class Cell8873 { val n = AtomicLong(0L); val filled = AtomicLong(0L); val sum = DoubleAdder() }
    private val tiers = ConcurrentHashMap<String, Cell8873>()
    private val venues = ConcurrentHashMap<String, Cell8873>()
    private val origins = ConcurrentHashMap<String, Cell8873>()

    private fun add(m: ConcurrentHashMap<String, Cell8873>, key: String, net: Double, filled: Boolean) {
        val c = m.computeIfAbsent(key) { Cell8873() }
        c.n.incrementAndGet()
        if (filled && net.isFinite()) { c.filled.incrementAndGet(); c.sum.add(net) }
    }

    /** EdgeModel8537.appendDataset: one labelled decision (filled or not) with its raw age, cap, venue code and outcome. */
    fun note8873(mint: String, ageMin: Double, mcapUsd: Double, venueCode: Double, net: Double, filled: Boolean) {
        add(tiers, tierOf8873(ageMin, mcapUsd).name, net, filled)
        add(venues, venueName8873(venueCode), net, filled)
        add(origins, if (mint.endsWith("pump")) "PUMP_MINT" else "OTHER_MINT", net, filled)
    }

    private fun line(m: ConcurrentHashMap<String, Cell8873>): String {
        val total = m.values.sumOf { it.n.get() }.coerceAtLeast(1L)
        return m.entries.sortedByDescending { it.value.n.get() }.joinToString(" ") { (k, c) ->
            val f = c.filled.get()
            "$k=${c.n.get() * 100 / total}%(n${c.n.get()}${if (f > 0) " filled$f ${"%+.1f".format(c.sum.sum() / f)}%" else ""})"
        }.ifBlank { "-" }
    }

    /** Report line (DiagnosisBrief8438 beside edge8537): attention share and mean labelled outcome by tier, venue and origin. */
    fun statusLine8873(): String =
        "network8873[tiers{${line(tiers)}} venues{${line(venues)}} origin{${line(origins)}} " +
            "rule=established = \$1M+ or a day old and \$100k+; fresh < 1 h; outcome = the label horizon's net]"

    internal fun resetForTest8873() { tiers.clear(); venues.clear(); origins.clear() }
}
