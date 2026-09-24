package com.lifecyclebot.engine.market

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TreasuryScannerFeed
import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import com.lifecyclebot.v3.scoring.BlueChipTraderAI
import com.lifecyclebot.v3.scoring.DipHunterAI
import com.lifecyclebot.v3.scoring.MoonshotTraderAI
import com.lifecyclebot.v3.scoring.QualityTraderAI
import com.lifecyclebot.v3.scoring.ShitCoinTraderAI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * V5.0.7297 §B EVERY SPECIALIST HUNTS ITS OWN BAND.
 *
 * Operator: "all of the specialist traders like quality bluechip shitcoin we
 * meant to have their own fluid scorebands marketcaps token types they were
 * meant to focus on buying. thats drifted into they are last choices not
 * lanes that trade as designed" … "each individual lane had its own scanner
 * and brain to help the scanner tune".
 *
 * The drift, in code: a token reaches a specialist only if the lane election
 * picks it as owner or hands it the one rescue slot. The election is seeded
 * by intake source; Pump sources seed SHITCOIN / PROJECT_SNIPER / EXPRESS, and
 * the sources that seeded QUALITY / BLUECHIP / TREASURY (DexScreener,
 * GeckoTerminal, CoinGecko) were locked out or dead. The per-mode scanner
 * brain built for this (ModeLearning.getScannerPrefs, "Paper mode will use
 * these") never got a caller, and TreasuryScannerFeed's dedicated watchlist
 * was published to and never read.
 *
 * Each specialist now hunts the MarketSweep7297 market view itself:
 *   - its band is the trader's own market-cap limits (read from the trader,
 *     not copied), plus the token type it is built for: DIP_HUNTER wants a
 *     falling 1h price with liquidity at its ratio, MOONSHOT a rising one,
 *     BLUECHIP no pump.fun mint, TREASURY/CASHGEN established liquidity;
 *   - it ranks the market rows by what that lane trades on;
 *   - picks are dealt round-robin so one lane cannot take every row;
 *   - every pick is a CLAIM: while the token is still inside the lane's band
 *     the election makes that lane the owner, so the specialist trades its
 *     own prey as primary instead of waiting for a rescue slot.
 *
 * The brain is per lane. Each claimed trade that settles on the canonical
 * finalized bus is graded into a market-cap bucket (half a decade wide) of
 * the lane that took it. A bucket with at least [MIN_BUCKET_N] closes and a
 * positive mean lifts that lane's ranking there; a negative mean lowers it.
 * The band is fluid outward only: a profitable bucket just outside the
 * trader's limits widens the hunt by that bucket. It never narrows the
 * trader's own band, and ranking never removes a row, it only orders it.
 *
 * Claims decide ownership only. The lane's own scorer, FDG, sizing and every
 * safety gate still run on the token.
 */
object LaneHunter7297 {

    data class Profile(
        val lane: String,
        val baseMin: Double,
        val baseMax: Double,
        val fits: (MarketSweep7297.Row) -> Boolean,
        val rank: (MarketSweep7297.Row) -> Double,
    )

    data class Claim(val lane: String, val mcapAtHunt: Double, val atMs: Long)

    const val SOURCE_PREFIX = "MARKET_HUNT_"
    private const val PICKS_PER_LANE = 8
    private const val CLAIM_TTL_MS = 30L * 60 * 1000
    private const val MIN_BUCKET_N = 8
    private const val PREFS = "aate_lane_hunter_7297"

    private fun turnover(r: MarketSweep7297.Row) =
        if (r.liquidityUsd > 0.0) r.volumeH1Usd / r.liquidityUsd else 0.0

    private fun activity(r: MarketSweep7297.Row) = log10(1.0 + r.txCountH1) + log10(1.0 + r.volumeH1Usd)

    val profiles: List<Profile> = listOf(
        Profile(
            "SHITCOIN", ShitCoinTraderAI.MIN_MARKET_CAP_USD, ShitCoinTraderAI.MAX_MARKET_CAP_USD,
            fits = { true },
            rank = { r -> activity(r) + 2.0 * turnover(r).coerceAtMost(5.0) + r.organicScore / 50.0 },
        ),
        Profile(
            "QUALITY", QualityTraderAI.MIN_MARKET_CAP_USD, QualityTraderAI.MAX_MARKET_CAP_USD,
            fits = { r -> r.liquidityUsd > 0.0 },
            rank = { r -> r.organicScore / 20.0 + turnover(r).coerceAtMost(3.0) + log10(1.0 + r.holders) + (if (r.verified) 1.0 else 0.0) },
        ),
        Profile(
            "BLUECHIP", BlueChipTraderAI.MIN_MARKET_CAP_USD, Double.MAX_VALUE,
            fits = { r -> !r.mint.endsWith("pump", ignoreCase = true) && r.liquidityUsd > 0.0 },
            rank = { r -> log10(1.0 + r.liquidityUsd) + log10(1.0 + r.volumeH1Usd) + (if (r.verified) 1.0 else 0.0) },
        ),
        Profile(
            "DIP_HUNTER", DipHunterAI.MIN_MCAP_USD, DipHunterAI.MAX_MCAP_USD,
            fits = { r -> r.priceChangeH1Pct < 0.0 && r.liquidityUsd >= r.mcapUsd * DipHunterAI.MIN_LIQUIDITY_RATIO },
            rank = { r -> -r.priceChangeH1Pct / 5.0 + log10(1.0 + r.volumeH1Usd) },
        ),
        Profile(
            "MOONSHOT", MoonshotTraderAI.MIN_MARKET_CAP_USD, MoonshotTraderAI.MAX_MARKET_CAP_USD,
            fits = { r -> r.priceChangeH1Pct > 0.0 },
            rank = { r -> r.priceChangeH1Pct / 10.0 + activity(r) },
        ),
        Profile(
            "TREASURY", TreasuryScannerFeed.MIN_TREASURY_MCAP, Double.MAX_VALUE,
            fits = { r -> r.liquidityUsd >= TreasuryScannerFeed.MIN_TREASURY_LIQUIDITY },
            rank = { r -> turnover(r).coerceAtMost(3.0) * 2.0 + log10(1.0 + r.liquidityUsd) },
        ),
        Profile(
            "CASHGEN", TreasuryScannerFeed.MIN_TREASURY_MCAP, Double.MAX_VALUE,
            fits = { r -> r.liquidityUsd >= TreasuryScannerFeed.MIN_TREASURY_LIQUIDITY },
            rank = { r -> turnover(r).coerceAtMost(3.0) * 2.0 + log10(1.0 + r.volumeH1Usd) },
        ),
    )

    // ── per-lane brain ───────────────────────────────────────────────────

    private class Stat { var n = 0; var sumRet = 0.0; fun mean() = if (n > 0) sumRet / n else 0.0 }

    private val stats = ConcurrentHashMap<String, ConcurrentHashMap<Int, Stat>>()
    private val claims = ConcurrentHashMap<String, Claim>()
    private val hunted = ConcurrentHashMap<String, Long>()
    private val subscribed = AtomicBoolean(false)
    @Volatile private var prefs: SharedPreferences? = null

    /** Pure: half-decade market-cap bucket (1k→6, 3.16k→7, 10k→8 …). */
    fun bucketOf(mcap: Double): Int = if (mcap <= 0.0 || !mcap.isFinite()) Int.MIN_VALUE else floor(2.0 * log10(mcap)).toInt()

    private val HALF_DECADE = 10.0.pow(0.5)
    private const val MAX_EDGE_STEPS = 2

    /**
     * Pure: the lane's band, moved outward half a decade at a time while the
     * lane's graded closes in the bucket at that edge are profitable (at most
     * [MAX_EDGE_STEPS] steps a side). Never narrower than [baseMin]..[baseMax].
     */
    fun fluidBand(baseMin: Double, baseMax: Double, bucketStats: Map<Int, Pair<Int, Double>>): Pair<Double, Double> {
        fun proven(b: Int) = bucketStats[b]?.let { it.first >= MIN_BUCKET_N && it.second > 0.0 } == true
        var lo = baseMin
        var hi = baseMax
        if (baseMin > 0.0) {
            repeat(MAX_EDGE_STEPS) { if (proven(bucketOf(lo * 1.0001))) lo /= HALF_DECADE else return@repeat }
        }
        if (baseMax.isFinite() && baseMax < Double.MAX_VALUE / HALF_DECADE) {
            repeat(MAX_EDGE_STEPS) { if (proven(bucketOf(hi * 0.9999))) hi *= HALF_DECADE else return@repeat }
        }
        return lo to hi
    }

    /** The trader's effective floor: its own constant, or lower where its closes earned it. */
    fun floorFor(lane: String, base: Double): Double = fluidBandFor(lane)?.first?.coerceAtMost(base) ?: base

    /** The trader's effective ceiling: its own constant, or higher where its closes earned it. */
    fun ceilingFor(lane: String, base: Double): Double = fluidBandFor(lane)?.second?.coerceAtLeast(base) ?: base

    /** Pure: deal ranked candidates to lanes one at a time; a mint goes to one lane. */
    fun dealRoundRobin(ranked: Map<String, List<String>>, perLane: Int): Map<String, List<String>> {
        val out = ranked.keys.associateWith { mutableListOf<String>() }
        val cursor = ranked.keys.associateWith { 0 }.toMutableMap()
        val taken = HashSet<String>()
        var progressed = true
        while (progressed) {
            progressed = false
            for ((lane, list) in ranked) {
                val picks = out.getValue(lane)
                if (picks.size >= perLane) continue
                var i = cursor.getValue(lane)
                while (i < list.size && list[i] in taken) i++
                if (i < list.size) {
                    picks += list[i]; taken += list[i]; progressed = true
                    cursor[lane] = i + 1
                } else cursor[lane] = i
            }
        }
        return out
    }

    private fun bucketStatsFor(lane: String): Map<Int, Pair<Int, Double>> =
        stats[lane]?.mapValues { it.value.n to it.value.mean() } ?: emptyMap()

    private fun fluidBandFor(lane: String): Pair<Double, Double>? {
        val p = profiles.firstOrNull { it.lane == lane } ?: return null
        return fluidBand(p.baseMin, p.baseMax, bucketStatsFor(lane))
    }

    /**
     * V5.0.7298 — the per-mode scanner brain, finally read. ModeLearning has
     * graded every close by trading mode (the lane names) and liquidity bucket
     * since March, and getScannerPrefs() — built "so paper mode will use these"
     * — never had a caller. Once a lane's history is reliable (≥5 closes), rows
     * inside its best-winning liquidity bucket rank up to +15% (scaled by that
     * history's confidence). Ordering only; nothing is removed.
     */
    private fun modeLiqMultiplier(lane: String, liquidityUsd: Double): Double {
        val keys = if (lane == "BLUECHIP") listOf("BLUECHIP", "BLUE_CHIP") else listOf(lane)
        val prefs = keys.firstNotNullOfOrNull { k ->
            try { com.lifecyclebot.engine.ModeLearning.getScannerPrefs(k).takeIf { it.confidence > 0.0 } } catch (_: Throwable) { null }
        } ?: return 1.0
        return if (liquidityUsd in prefs.preferredLiqMin..prefs.preferredLiqMax) 1.0 + 0.15 * prefs.confidence else 1.0
    }

    /**
     * V5.0.7298 — MomentumPredictorAI's discovery list, finally read.
     * It is fed every price point and classifies each watched token; its
     * getStrongMomentumTokens() ("for discovery") had no caller, so a token it
     * called STRONG_PUMP / PUMP_BUILDING was still elected by intake source.
     * Those tokens are now MOONSHOT's claims while inside MOONSHOT's band
     * (claimFor re-checks the band against the live cap). An existing hunt
     * claim is never overwritten.
     */
    fun claimMomentum7298(): Int {
        ensureSubscribed()
        val strong = try { com.lifecyclebot.engine.MomentumPredictorAI.getStrongMomentumTokens() } catch (_: Throwable) { emptyList() }
        val now = System.currentTimeMillis()
        var n = 0
        for (m in strong.take(20)) {
            val existing = claims[m.mint]
            if (existing != null && now - existing.atMs <= CLAIM_TTL_MS) continue
            val mcap = try { com.lifecyclebot.engine.GlobalTradeRegistry.getEntry(m.mint)?.initialMcap ?: 0.0 } catch (_: Throwable) { 0.0 }
            claims[m.mint] = Claim("MOONSHOT", mcap, now)
            n++
        }
        if (n > 0) try { PipelineHealthCollector.labelInc("LANE_HUNT_7298_MOMENTUM_CLAIMED") } catch (_: Throwable) {}
        return n
    }

    private fun brainMultiplier(lane: String, mcap: Double): Double {
        val s = stats[lane]?.get(bucketOf(mcap)) ?: return 1.0
        if (s.n < MIN_BUCKET_N) return 1.0
        return (1.0 + (s.mean() * 2.0).coerceIn(-0.3, 0.3))
    }

    /**
     * Each lane picks from the sweep. Returns lane → rows, and records every
     * pick as a claim the election honours while the token stays in band.
     */
    fun hunt(snap: MarketSweep7297.Snapshot): Map<String, List<MarketSweep7297.Row>> {
        ensureSubscribed()
        val byMint = snap.rows.associateBy { it.mint }
        val ranked = profiles.associate { p ->
            val (lo, hi) = fluidBand(p.baseMin, p.baseMax, bucketStatsFor(p.lane))
            p.lane to snap.rows
                .filter { it.mcapUsd in lo..hi && p.fits(it) }
                .sortedByDescending { r ->
                    val heat = MarketSweep7297.Band.of(r.mcapUsd)?.let { snap.bands[it]?.breadthPct } ?: 50.0
                    p.rank(r) * brainMultiplier(p.lane, r.mcapUsd) * modeLiqMultiplier(p.lane, r.liquidityUsd) *
                        (0.9 + 0.2 * heat / 100.0)
                }
                .map { it.mint }
        }
        val dealt = dealRoundRobin(ranked, PICKS_PER_LANE)
        val now = System.currentTimeMillis()
        claims.entries.removeIf { now - it.value.atMs > CLAIM_TTL_MS }
        val out = dealt.mapValues { (lane, mints) ->
            mints.mapNotNull { byMint[it] }.also { rows ->
                rows.forEach { r ->
                    claims[r.mint] = Claim(lane, r.mcapUsd, now)
                    hunted.merge(lane, 1L, Long::plus)
                    if ((lane == "TREASURY" || lane == "CASHGEN") && r.volumeH24Usd >= TreasuryScannerFeed.MIN_TREASURY_24H_VOL) {
                        try {
                            TreasuryScannerFeed.publishCandidate(
                                TreasuryScannerFeed.TreasuryCandidate(r.mint, r.symbol, r.mcapUsd, r.liquidityUsd, r.volumeH24Usd, SOURCE_PREFIX + lane)
                            )
                        } catch (_: Throwable) {}
                    }
                }
                try { PipelineHealthCollector.labelInc("LANE_HUNT_7297_PICKED_$lane") } catch (_: Throwable) {}
            }
        }
        return out
    }

    /** The lane that hunted [mint], while [currentMcap] is still inside that lane's band. */
    fun claimFor(mint: String, currentMcap: Double): String? {
        val c = claims[mint] ?: return null
        if (System.currentTimeMillis() - c.atMs > CLAIM_TTL_MS) { claims.remove(mint, c); return null }
        if (currentMcap > 0.0 && currentMcap.isFinite()) {
            val (lo, hi) = fluidBandFor(c.lane) ?: return null
            if (currentMcap < lo || currentMcap > hi) return null
        }
        return c.lane
    }

    fun laneFromSource(source: String?): String? {
        val s = source?.uppercase() ?: return null
        val i = s.indexOf(SOURCE_PREFIX)
        if (i < 0) return null
        val lane = s.substring(i + SOURCE_PREFIX.length).substringBefore('|').trim()
        return lane.takeIf { l -> profiles.any { it.lane == l } }
    }

    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val p = try { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) } catch (_: Throwable) { return }
        prefs = p
        profiles.forEach { prof ->
            val raw = p.getString(prof.lane, null) ?: return@forEach
            val m = stats.getOrPut(prof.lane) { ConcurrentHashMap() }
            raw.split(';').forEach { e ->
                val f = e.split(',')
                if (f.size == 3) {
                    val b = f[0].toIntOrNull() ?: return@forEach
                    m[b] = Stat().apply { n = f[1].toIntOrNull() ?: 0; sumRet = f[2].toDoubleOrNull() ?: 0.0 }
                }
            }
        }
        ensureSubscribed()
    }

    private fun ensureSubscribed() {
        if (!subscribed.compareAndSet(false, true)) return
        try {
            CanonicalTradeFinalizedBus6450.subscribe { e -> onSettled(e) }
        } catch (_: Throwable) { subscribed.set(false) }
    }

    private fun onSettled(e: CanonicalTradeFinalizedBus6450.Event) {
        val c = claims[e.mint] ?: return
        if (!e.entryLane.equals(c.lane, ignoreCase = true)) return
        if (e.settledAtMs < c.atMs) return
        val ret = e.returnFraction
        if (!ret.isFinite()) return
        val b = bucketOf(c.mcapAtHunt)
        if (b == Int.MIN_VALUE) return
        val m = stats.getOrPut(c.lane) { ConcurrentHashMap() }
        synchronized(m) {
            val s = m.getOrPut(b) { Stat() }
            s.n++; s.sumRet += ret
            try {
                prefs?.edit()?.putString(c.lane, m.entries.joinToString(";") { "${it.key},${it.value.n},${it.value.sumRet}" })?.apply()
            } catch (_: Throwable) {}
        }
        try { PipelineHealthCollector.labelInc("LANE_HUNT_7297_GRADED_${c.lane}") } catch (_: Throwable) {}
    }

    fun statusLine(): String = profiles.joinToString(" · ") { p ->
        val (lo, hi) = fluidBandFor(p.lane) ?: (p.baseMin to p.baseMax)
        val graded = stats[p.lane]?.values?.sumOf { it.n } ?: 0
        val fmt = { v: Double -> if (v >= Double.MAX_VALUE / 2) "∞" else if (v >= 1e6) "${"%.1f".format(v / 1e6)}M" else "${(v / 1e3).toInt()}k" }
        "${p.lane}[band=${fmt(lo)}-${fmt(hi)} hunted=${hunted[p.lane] ?: 0} claims=${claims.values.count { it.lane == p.lane }} graded=$graded]"
    }
}
