package com.lifecyclebot.perps

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8886 §THE_CRYPTO_TREND_CELL (plan W7: "keeps the classic signal engine; adds a multi-horizon price and volume trend
 * score for majors, with order-flow imbalance for entry timing; twin coverage").
 *
 * The classic engine is untouched. Every crypto candidate it hands to the canonical contract now carries one more fact, read
 * from the registry row its evaluation owns: which way the price has moved over every horizon a provider measured, whether
 * buyers or sellers lead the last five minutes, and whether those five minutes trade above the day's pace.
 *
 *  - horizons: 5 m (the row's five-minute set, under CryptoShortWindow8832's own freshness), 1 h and 6 h (DexScreener pair /
 *    GeckoTerminal pool), 24 h, and 7 d (CoinGecko markets, the majors' source). A reading counts while it still describes
 *    mostly the current window: its age is at most half its horizon ([current8886]). Nothing is filled in.
 *  - band ([band8886]): TU with fewer than two horizons read; T3 every read horizon rising; T0 every one falling; T2 more rising
 *    than falling; T1 otherwise.
 *  - flow ([flow8886]): F1 more buys than sells in the five-minute set, F0 not, FU unobserved.
 *  - volume ([volume8886]): V1 five-minute volume above the day's five-minute mean, V0 not, VU unknown.
 *
 * The key is band + flow + volume ("T3F1V0"); the graded cell is band + flow ("T3F1"). The key goes on the position's reasons
 * (TREND8886=) so the crypto twin files each close under its lane and under lane|cell as well (CryptoTwin8883), and on the
 * candidate's evidence ("trend8886" = the cell) for the lane backing (CryptoSpotPaperBacking8788): a cell whose own twin record
 * backs — the lane rule (paperBacks8788) and its mean held above zero by the family-deflated margin ([familyZ8886]: the
 * codebase's one-standard-error confidence, held for every cell of the lane that is graded) — backs a candidate its lane
 * record does not; a cell proven losing (MemePaperTrader8544.provenLosing8608) withdraws the lane's backing from its
 * candidates. Volume is recorded on the key, not graded on its own yet.
 */
object CryptoTrend8886 {
    const val TAG_8886 = "TREND8886="
    const val EVIDENCE_8886 = "trend8886"
    private const val HOUR_MS = 3_600_000L

    /** One candidate's read: the full key (band, flow, volume) and how many horizons it saw rising / falling. */
    data class Read8886(val key: String, val horizons: Int, val up: Int, val down: Int) {
        /** The graded cell: band + flow. */
        val cell: String get() = key.take(4)
    }

    // ── pure ──

    /** Pure: does a change over [horizonMs] observed at [atMs] still describe mostly the current window (age <= half the horizon)? */
    fun current8886(atMs: Long, horizonMs: Long, nowMs: Long): Boolean = atMs > 0L && nowMs - atMs in 0L..(horizonMs / 2)

    /** Pure: the trend band of [changes] (NaN = not read). */
    fun band8886(changes: List<Double>): String {
        val seen = changes.filter { it.isFinite() }
        if (seen.size < 2) return "TU"
        val up = seen.count { it > 0.0 }
        val down = seen.count { it < 0.0 }
        return when {
            up == seen.size -> "T3"
            down == seen.size -> "T0"
            up > down -> "T2"
            else -> "T1"
        }
    }

    /** Pure: who leads the five-minute set. */
    internal fun flow8886(ev: CryptoShortWindow8832.Evidence): String = when {
        !ev.flowObserved -> "FU"
        ev.buys5m > ev.sells5m -> "F1"
        else -> "F0"
    }

    /** Pure: is the five-minute volume above the day's five-minute mean? */
    internal fun volume8886(ev: CryptoShortWindow8832.Evidence, volume24h: Double): String {
        val pace = ev.volumeAccelerationVsDay(volume24h) ?: return "VU"
        return if (pace > 1.0) "V1" else "V0"
    }

    /** Pure: the read of [tok] at [nowMs]; an absent row reads unknown on every part. */
    fun read8886(tok: DynamicAltTokenRegistry.DynToken?, nowMs: Long): Read8886 {
        if (tok == null) return Read8886("TUFUVU", 0, 0, 0)
        val ev = CryptoShortWindow8832.from(nowMs, tok.shortWindowObservedAtMs8832, tok.buys5m8832, tok.sells5m8832,
            tok.volume5m8832, tok.change5mPct8832)
        val at = tok.trendObservedAtMs8886
        fun held(v: Double, atMs: Long, horizonMs: Long): Double = if (v.isFinite() && current8886(atMs, horizonMs, nowMs)) v else Double.NaN
        val changes = listOf(
            ev.change5mPct ?: Double.NaN,
            held(tok.change1hPct8886, at, HOUR_MS),
            held(tok.change6hPct8886, at, 6L * HOUR_MS),
            held(tok.priceChange24h, tok.change24hObservedAtMs8752, 24L * HOUR_MS),
            held(tok.change7dPct8886, at, 7L * 24L * HOUR_MS),
        )
        val seen = changes.filter { it.isFinite() }
        return Read8886(band8886(changes) + flow8886(ev) + volume8886(ev, tok.volume24h), seen.size,
            seen.count { it > 0.0 }, seen.count { it < 0.0 })
    }

    /** Pure: the graded cell a position's reasons carry, or null. */
    fun cellOfReasons8886(reasons: List<String>): String? =
        reasons.firstOrNull { it.startsWith(TAG_8886) }?.removePrefix(TAG_8886)?.take(4)?.takeIf { it.length == 4 }

    /** Pure: the standard normal CDF (Abramowitz & Stegun 7.1.26 erf; absolute error under 1.5e-7). */
    fun phi8886(x: Double): Double {
        val z = kotlin.math.abs(x) / kotlin.math.sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * z)
        val erf = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t *
            kotlin.math.exp(-z * z)
        return if (x >= 0.0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
    }

    /**
     * Pure: the margin, in standard errors, that keeps the one-standard-error confidence (Φ(1)) across a family of [k] graded
     * cells (Šidák): the z with Φ(z) = Φ(1)^(1/k). One cell is the plain one-SE rule.
     */
    fun familyZ8886(k: Int): Double {
        val target = Math.pow(phi8886(1.0), 1.0 / k.coerceAtLeast(1))
        var lo = 0.0
        var hi = 10.0
        repeat(80) {
            val mid = (lo + hi) / 2.0
            if (phi8886(mid) < target) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }

    /** Pure: (n, mean, se, profit factor) of closes' pnl %, each clamped as the leaderboard clamps it (MemePaperTrader8544.proofOf8546's form). */
    fun proof8886(pnls: List<Double>): DoubleArray {
        val x = pnls.map { if (it.isFinite()) it.coerceIn(-100.0, com.lifecyclebot.engine.StrategyTelemetry.LEARNABLE_GAIN_CEILING_PCT_7349) else 0.0 }
        val n = x.size.toDouble()
        if (n < 1.0) return doubleArrayOf(0.0, Double.NaN, Double.NaN, Double.NaN)
        val mean = x.sum() / n
        val variance = (x.sumOf { it * it } / n - mean * mean).coerceAtLeast(0.0)
        val se = if (n > 1.0) kotlin.math.sqrt(variance / n) else Double.NaN
        val gain = x.filter { it > 0.0 }.sum()
        val loss = -x.filter { it < 0.0 }.sum()
        val pf = if (loss > 0.0) gain / loss else if (gain > 0.0) Double.POSITIVE_INFINITY else Double.NaN
        return doubleArrayOf(n, mean, se, pf)
    }

    /** Pure: does a cell's record back its candidates — the lane rule, and its mean above zero by the family margin of [k] cells? */
    fun cellBacks8886(wins: Int, losses: Int, meanPct: Double, totalSol: Double, pfEdgePp: Double, proof: DoubleArray, k: Int): Boolean =
        com.lifecyclebot.engine.truth.CryptoSpotPaperBacking8788.paperBacks8788(wins, losses, meanPct, totalSol, pfEdgePp) &&
            proof[1].isFinite() && proof[2].isFinite() && proof[1] - familyZ8886(k) * proof[2] > 0.0

    /** Pure: is a cell's record proven losing (the meme books' rule)? */
    fun cellLosing8886(proof: DoubleArray): Boolean =
        com.lifecyclebot.engine.truth.MemePaperTrader8544.provenLosing8608(proof[0].toInt(), proof[1], proof[2], proof[3])

    // ── counters ──

    private val byCell = ConcurrentHashMap<String, AtomicLong>()
    private val byHorizons = Array(6) { AtomicLong(0L) }

    /** CryptoAltTrader.executeSignal: one candidate's read, counted by cell and by horizons seen. */
    fun note8886(r: Read8886) {
        (byCell[r.cell] ?: if (byCell.size >= 40) return else byCell.computeIfAbsent(r.cell) { AtomicLong(0L) }).incrementAndGet()
        byHorizons[r.horizons.coerceIn(0, 5)].incrementAndGet()
    }

    /** Brief: candidates by cell and by how many horizons were read. */
    fun statusLine8886(): String {
        val cells = byCell.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val seen = byHorizons.withIndex().joinToString(",") { "n${it.index}=${it.value.get()}" }
        return "cryptoTrend8886[cells{$cells} horizonsRead{$seen} rule=band over 5m/1h/6h/24h/7d (a read counts while its age <= half its horizon) x five-minute flow; graded per lane|cell on the twin, deflated across cells]"
    }

    internal fun resetForTest8886() { byCell.clear(); byHorizons.forEach { it.set(0L) } }
}
