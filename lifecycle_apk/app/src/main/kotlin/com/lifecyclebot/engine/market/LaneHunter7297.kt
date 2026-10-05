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

    // V5.0.7797 — CHEAT-SHEET COMMON SENSE AT DISCOVERY.
    //
    // TradePlan7739/CommonSenseTradePlaybook already enforce the full
    // context-trigger-invalidation-payoff contract downstream. Hunters should not
    // duplicate that authority, but they also should not waste their limited picks
    // on obviously poor geometry. These helpers use ONLY the market snapshot the
    // hunter already has: no hot-path API/LLM/network calls, no hard execution veto.
    private fun exitability7797(r: MarketSweep7297.Row): Double {
        if (r.liquidityUsd <= 0.0 || r.mcapUsd <= 0.0) return 0.65
        val ratio = r.mcapUsd / r.liquidityUsd
        return when {
            ratio <= 8.0 -> 1.15
            ratio <= 25.0 -> 1.08
            ratio <= 60.0 -> 1.00
            ratio <= 120.0 -> 0.88
            else -> 0.72
        }
    }

    private fun participation7797(r: MarketSweep7297.Row): Double {
        val tx = r.txCountH1
        val turn = turnover(r)
        return when {
            tx >= 80 && turn in 0.5..4.0 -> 1.15
            tx >= 25 && turn in 0.25..5.0 -> 1.08
            tx >= 6 && turn > 0.0 -> 1.00
            else -> 0.82
        }
    }

    private fun chasePenalty7797(r: MarketSweep7297.Row, runner: Boolean): Double {
        val move = r.priceChangeH1Pct
        return when {
            move < -35.0 -> 0.62
            !runner && move > 45.0 -> 0.68
            runner && move > 120.0 -> 0.62
            runner && move > 70.0 -> 0.78
            else -> 1.0
        }
    }

    private fun freshnessFit7797(r: MarketSweep7297.Row, idealMaxHours: Double): Double = when {
        r.ageHours <= 0.0 -> 0.95
        r.ageHours <= idealMaxHours -> 1.12
        r.ageHours <= idealMaxHours * 4.0 -> 1.0
        else -> 0.88
    }

    private fun commonSenseMult7797(lane: String, r: MarketSweep7297.Row): Double {
        val runner = lane in setOf("MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "SHITCOIN")
        var m = exitability7797(r) * participation7797(r) * chasePenalty7797(r, runner)
        m *= when (lane) {
            "PROJECT_SNIPER" -> freshnessFit7797(r, 0.05)
            "EXPRESS", "MANIPULATED" -> freshnessFit7797(r, 0.20)
            "MOONSHOT" -> freshnessFit7797(r, 1.0)
            "SHITCOIN" -> freshnessFit7797(r, 6.0)
            "QUALITY" -> if (r.verified) 1.08 else 0.97
            "BLUECHIP" -> if (r.verified && r.liquidityUsd >= 25_000.0) 1.10 else 0.92
            "DIP_HUNTER" -> if (r.priceChangeH1Pct in -25.0..-3.0) 1.10 else 0.90
            "TREASURY", "CASHGEN" -> if (turnover(r) in 0.10..2.5) 1.08 else 0.92
            "CYCLIC" -> if (kotlin.math.abs(r.priceChangeH1Pct) <= 12.0) 1.08 else 0.90
            "CORE" -> 1.0
            else -> 1.0
        }
        return m.coerceIn(0.45, 1.35)
    }

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
            "MOONSHOT", MoonshotTraderAI.MIN_MARKET_CAP_BOOTSTRAP_USD_7719, MoonshotTraderAI.MAX_MARKET_CAP_USD,
            fits = { r ->
                // V5.0.7796 — not "anything green". Moonshot hunts asymmetry:
                // real liquidity/activity, positive ignition, and no already-consumed
                // vertical spike. Continuation up to $5M stays eligible; ranking below
                // strongly prefers the pre-liftoff end of that universe.
                r.priceChangeH1Pct > 0.0 && r.priceChangeH1Pct <= 120.0 &&
                    r.liquidityUsd >= 1_500.0 && r.txCountH1 >= 3
            },
            rank = { r ->
                val earlyCap = when {
                    r.mcapUsd < 10_000.0 -> 6.0
                    r.mcapUsd < 25_000.0 -> 5.0
                    r.mcapUsd < 100_000.0 -> 3.5
                    r.mcapUsd < 500_000.0 -> 2.0
                    else -> 0.5
                }
                val youth = when {
                    r.ageHours <= 0.25 -> 3.0
                    r.ageHours <= 1.0 -> 2.0
                    r.ageHours <= 6.0 -> 1.0
                    else -> 0.0
                }
                val ignition = r.priceChangeH1Pct.coerceIn(0.0, 40.0) / 8.0
                val exhaustionPenalty = if (r.priceChangeH1Pct > 60.0) 0.55 else 1.0
                exhaustionPenalty * (earlyCap + youth + ignition +
                    activity(r) + 2.0 * turnover(r).coerceAtMost(4.0))
            },
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
        // V5.0.7796 — the five specialists below previously had NO hunter profile.
        // They only received generic scanner spillover / source affinity, violating
        // the lane contract that every specialist hunts prey matching its own design.
        // These profiles only DISCOVER + CLAIM. Native specialist brains still have
        // to qualify the token before ownership can change.
        Profile(
            "EXPRESS", 1_000.0, 300_000.0,
            fits = { r ->
                r.priceChangeH1Pct > 1.0 && r.txCountH1 >= 4 &&
                    r.volumeH1Usd > 0.0 && r.liquidityUsd > 0.0
            },
            rank = { r ->
                // Momentum ignition / acceleration: prefer active turnover and
                // a real positive move, but penalise already-exhausted >80% spikes.
                val chasePenalty = if (r.priceChangeH1Pct > 80.0) 0.45 else 1.0
                chasePenalty * (activity(r) + 2.5 * turnover(r).coerceAtMost(4.0) +
                    r.priceChangeH1Pct.coerceIn(0.0, 40.0) / 8.0)
            },
        ),
        Profile(
            "PROJECT_SNIPER", 3_000.0, 500_000.0,
            fits = { r ->
                // ProjectSniperAI is explicitly a pre-ignition / first-minutes desk.
                r.ageHours in 0.0..0.05 && r.priceChangeH1Pct in -5.0..35.0 &&
                    r.liquidityUsd >= 1_500.0
            },
            rank = { r ->
                val youth = (1.0 - (r.ageHours / 0.05)).coerceIn(0.0, 1.0) * 5.0
                youth + activity(r) + 2.0 * turnover(r).coerceAtMost(3.0) +
                    r.priceChangeH1Pct.coerceIn(-5.0, 15.0) / 10.0
            },
        ),
        Profile(
            "MANIPULATED", 5_000.0, 300_000.0,
            fits = { r ->
                // The hunter finds young, violently one-sided candidates; the
                // native ManipulatedTraderAI still requires actual manipulation
                // evidence (bundle/order-flow/etc.) before qualification.
                r.ageHours in 0.0..0.20 && r.txCountH1 >= 6 &&
                    r.priceChangeH1Pct >= 5.0 && r.liquidityUsd >= 1_500.0
            },
            rank = { r ->
                3.0 * turnover(r).coerceAtMost(5.0) + activity(r) +
                    r.priceChangeH1Pct.coerceIn(0.0, 60.0) / 6.0
            },
        ),
        Profile(
            "CYCLIC", 10_000.0, 5_000_000.0,
            fits = { r ->
                // Cyclic is not a first-minute sniper. Hunt sellable, active,
                // established-enough names with turnover but without a one-way
                // exhaustion move; its own engine decides whether the cycle exists.
                r.ageHours >= 0.20 && r.liquidityUsd >= 5_000.0 &&
                    r.volumeH1Usd > 0.0 && r.priceChangeH1Pct in -15.0..20.0
            },
            rank = { r ->
                2.0 * turnover(r).coerceAtMost(3.0) + activity(r) -
                    kotlin.math.abs(r.priceChangeH1Pct) / 20.0
            },
        ),
        Profile(
            "CORE", 1_500.0, 5_000_000.0,
            fits = { r -> r.liquidityUsd > 0.0 && r.txCountH1 >= 3 },
            rank = { r ->
                // CORE is the ensemble/generalist desk: broad opportunity hunt,
                // deliberately lower-specificity than dedicated specialists.
                activity(r) + turnover(r).coerceAtMost(2.5) +
                    r.organicScore / 100.0
            },
        ),
    )

    // ── per-lane brain ───────────────────────────────────────────────────

    private class Stat {
        var n = 0
        var sumRet = 0.0
        var sumUtility = 0.0
        fun mean() = if (n > 0) sumRet / n else 0.0
        fun meanUtility() = if (n > 0) sumUtility / n else 0.0
    }

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
        stats[lane]?.mapValues { it.value.n to it.value.meanUtility() } ?: emptyMap()

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
        return (1.0 + (s.meanUtility() * 0.18).coerceIn(-0.30, 0.30))
    }

    /**
     * V5.0.7801 — each hunter consumes its OWN cached evidence.
     * No provider calls. Unknown data stays neutral. Native trader brains still
     * qualify the candidate later; this only spends discovery attention better.
     */
    private fun nativeDiscoveryMultiplier7801(lane: String, r: MarketSweep7297.Row): Double {
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[r.mint] } catch (_: Throwable) { null }
            ?: return 1.0
        val bp = ts.lastBuyPressurePct.takeIf { it.isFinite() } ?: 50.0
        val hg = ts.holderGrowthRate.takeIf { it.isFinite() } ?: 0.0
        val top = ts.topHolderPct ?: ts.tokenMap.topHolderConcentrationPct
            ?: ts.safety.topHolderPct.takeIf { it >= 0.0 } ?: 25.0
        val mom5 = ts.lastPriceChange5m.takeIf { it.isFinite() } ?: 0.0
        val mom1h = ts.lastPriceChange1h.takeIf { it.isFinite() } ?: r.priceChangeH1Pct
        val launch = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null }
        val danger = (ts.safety.summary + " " + ts.safety.bundleReason + " " +
            ts.safety.hardBlockReasons.joinToString(" ")).uppercase()
        val devSell = danger.contains("DEV_SELL") || danger.contains("DEV SELL") ||
            ((launch?.devSellTx60s ?: 0) > 0)
        val meta = try { com.lifecyclebot.engine.BirdeyeMetaDataProvider.peekCached(r.mint) } catch (_: Throwable) { null }
        val socialDepth = listOf(
            meta?.twitter.orEmpty(), meta?.telegram.orEmpty(), meta?.website.orEmpty(), meta?.discord.orEmpty()
        ).count { it.isNotBlank() }

        val hist = try { ts.history.toList().filter { it.priceUsd.isFinite() && it.priceUsd > 0.0 } } catch (_: Throwable) { emptyList() }
        val prices = hist.map { it.priceUsd }
        val current = ts.lastPrice.takeIf { it.isFinite() && it > 0.0 } ?: prices.lastOrNull() ?: 0.0
        val recentHigh = prices.takeLast(24).maxOrNull()?.takeIf { it > 0.0 } ?: current
        val drawdown = if (recentHigh > 0.0 && current > 0.0) (current / recentHigh - 1.0) * 100.0 else 0.0
        val tail = prices.takeLast(8)
        val low = tail.minOrNull() ?: current
        val lowIdx = tail.indexOf(low)
        val bounce = low > 0.0 && lowIdx >= 0 && lowIdx < tail.lastIndex &&
            current >= low * 1.02 && bp >= 50.0

        val m = when (lane) {
            "PROJECT_SNIPER" -> {
                var x = 1.0
                if (launch?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.PRE_IGNITION) x *= 1.18
                if ((launch?.distinctBuyers60s ?: 0) >= 3) x *= 1.12
                if (launch?.accelerationRising == true) x *= 1.10
                if ((launch?.largestBuyerSharePct60s ?: 0.0) > 65.0) x *= 0.72
                if (devSell) x *= 0.55
                if (ts.safety.firstBlockSupplyPct >= 40.0) x *= 0.70
                x
            }
            "EXPRESS" -> {
                var x = 1.0
                if (mom5 in 2.0..20.0) x *= 1.15 else if (mom5 > 35.0) x *= 0.72
                if (mom1h > 0.0) x *= 1.08
                if (bp >= 58.0) x *= 1.10
                if (hg > 0.0) x *= 1.06
                if (launch?.accelerationRising == true) x *= 1.10
                x
            }
            "SHITCOIN" -> {
                var x = 1.0
                if (ts.source.contains("PUMP", true)) x *= 1.12
                if (ts.meta.curveProgress in 5.0..85.0) x *= 1.08
                if (hg >= 2.0) x *= 1.10
                if (socialDepth >= 2) x *= 1.06
                if (bp >= 55.0) x *= 1.08
                if (top >= 50.0 || devSell) x *= 0.62
                x
            }
            "MANIPULATED" -> {
                var x = 1.0
                val bundled = ts.safety.firstBlockSupplyPct >= 25.0 ||
                    ts.safety.bundleRisk.equals("HIGH", true) ||
                    ts.safety.bundleRisk.equals("MEDIUM", true)
                if (bundled) x *= 1.18
                if (bp >= 70.0 && mom5 >= 8.0) x *= 1.16
                if ((launch?.repeatBuyerWallets60s ?: 0) >= 2) x *= 1.10
                if (launch?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.POST_PUMP_FADE || devSell) x *= 0.55
                x
            }
            "DIP_HUNTER" -> {
                var x = 1.0
                if (drawdown in -30.0..-6.0) x *= 1.16
                if (bounce) x *= 1.18
                if (hg >= 0.0) x *= 1.05 else if (hg <= -5.0) x *= 0.70
                if (bp >= 52.0) x *= 1.08
                if (devSell || drawdown <= -45.0) x *= 0.55
                x
            }
            "CYCLIC" -> {
                var x = 1.0
                if (tail.size >= 6) {
                    val turns = tail.zipWithNext().map { it.second - it.first }.zipWithNext()
                        .count { (a,b) -> (a > 0 && b < 0) || (a < 0 && b > 0) }
                    if (turns >= 2) x *= 1.14
                }
                if (kotlin.math.abs(mom1h) <= 12.0) x *= 1.08
                if (bp in 45.0..65.0) x *= 1.05
                if (devSell) x *= 0.65
                x
            }
            "QUALITY" -> {
                var x = 1.0
                if (r.liquidityUsd >= 15_000.0) x *= 1.10
                if (top <= 25.0) x *= 1.10 else if (top >= 45.0) x *= 0.65
                if (hg >= 1.0) x *= 1.08
                if (socialDepth >= 2) x *= 1.06
                if (bp >= 52.0) x *= 1.05
                x
            }
            "BLUECHIP" -> {
                var x = 1.0
                val ratio = if (r.liquidityUsd > 0.0) r.mcapUsd / r.liquidityUsd else Double.MAX_VALUE
                if (r.liquidityUsd >= 50_000.0 && ratio <= 100.0) x *= 1.14
                if (socialDepth >= 2) x *= 1.05
                if (top <= 20.0) x *= 1.08
                if (mom1h in -5.0..20.0) x *= 1.05
                x
            }
            "TREASURY" -> {
                var x = 1.0
                val vol = ts.volatility?.takeIf { it.isFinite() } ?: kotlin.math.abs(ts.meta.avgAtr)
                if (r.liquidityUsd >= 50_000.0) x *= 1.15
                if (vol <= 25.0) x *= 1.10 else if (vol >= 60.0) x *= 0.65
                if (ts.tokenMap.routeStatus.contains("READY", true) || ts.tokenMap.jupiterQuoteOk || ts.tokenMap.dexRouteOk) x *= 1.08
                if (top >= 40.0 || devSell) x *= 0.55
                x
            }
            "CASHGEN" -> {
                var x = 1.0
                if (r.liquidityUsd >= 20_000.0) x *= 1.10
                if (mom5 in 0.5..10.0) x *= 1.12
                if (bp >= 52.0) x *= 1.08
                if (kotlin.math.abs(mom1h) > 35.0) x *= 0.72
                if (devSell) x *= 0.60
                x
            }
            "CORE" -> 0.96 // native specialists should win when they have a thesis.
            else -> 1.0
        }
        return m.coerceIn(0.45, 1.40)
    }

    /**
     * V5.0.7777 — specialist affinity over the market-wide opportunity rank.
     * This only changes ordering. It never filters a row or overrides a native
     * specialist opinion, preserving lane autonomy and the canonical owner/FDG.
     */
    private fun opportunityLaneMultiplier7777(lane: String, r: MarketSweep7297.Row): Double {
        val o = MarketSweep7297.opportunityFor7777(r.mint) ?: return 1.0
        var m = MarketSweep7297.opportunityMultiplier7777(r.mint)
        val affinity = when (lane) {
            "MOONSHOT" -> o.setup in setOf("EARLY_MOMENTUM_IGNITION", "BREAKOUT_EXPANSION", "RELATIVE_STRENGTH_LEADER", "CONTINUATION")
            "PROJECT_SNIPER" -> o.setup == "EARLY_MOMENTUM_IGNITION"
            "EXPRESS" -> o.setup in setOf("EARLY_MOMENTUM_IGNITION", "BREAKOUT_EXPANSION", "RELATIVE_STRENGTH_LEADER")
            "SHITCOIN" -> o.setup in setOf("EARLY_MOMENTUM_IGNITION", "BREAKOUT_EXPANSION", "LIQUIDITY_EXPANSION")
            "MANIPULATED" -> o.setup in setOf("EARLY_MOMENTUM_IGNITION", "BREAKOUT_EXPANSION")
            "DIP_HUNTER" -> o.setup == "DIP_RECOVERY"
            "CYCLIC" -> o.setup in setOf("CONTINUATION", "DIP_RECOVERY", "BREAKOUT_EXPANSION")
            "QUALITY", "BLUECHIP" -> o.setup in setOf("CONTINUATION", "LIQUIDITY_EXPANSION", "RELATIVE_STRENGTH_LEADER")
            "TREASURY", "CASHGEN" -> o.setup in setOf("CONTINUATION", "LIQUIDITY_EXPANSION")
            "CORE" -> o.setup !in setOf("DISTRIBUTION", "EXHAUSTION")
            else -> false
        }
        if (affinity) m *= 1.10
        if (o.setup == "DISTRIBUTION" || o.setup == "EXHAUSTION") m *= 0.88
        return m.coerceIn(0.70, 1.40)
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
                        (0.9 + 0.2 * heat / 100.0) * opportunityLaneMultiplier7777(p.lane, r) *
                        commonSenseMult7797(p.lane, r) * nativeDiscoveryMultiplier7801(p.lane, r)
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
                    try {
                        val cs = commonSenseMult7797(lane, r)
                        PipelineHealthCollector.labelInc(
                            when {
                                cs >= 1.10 -> "LANE_HUNT_COMMON_SENSE_STRONG_7797_$lane"
                                cs < 0.80 -> "LANE_HUNT_COMMON_SENSE_WEAK_7797_$lane"
                                else -> "LANE_HUNT_COMMON_SENSE_NEUTRAL_7797_$lane"
                            }
                        )
                    } catch (_: Throwable) {}
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
                if (f.size >= 3) {
                    val b = f[0].toIntOrNull() ?: return@forEach
                    m[b] = Stat().apply {
                        n = f[1].toIntOrNull() ?: 0
                        sumRet = f[2].toDoubleOrNull() ?: 0.0
                        sumUtility = if (f.size >= 4) f[3].toDoubleOrNull() ?: 0.0 else sumRet
                    }
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
        // V5.0.7301 — a CASHGEN hunt executes through the TREASURY book.
        val laneMatches = e.entryLane.equals(c.lane, ignoreCase = true) ||
            (c.lane == "CASHGEN" && e.entryLane.equals("TREASURY", ignoreCase = true))
        if (!laneMatches) return
        if (e.settledAtMs < c.atMs) return
        val ret = e.returnFraction
        if (!ret.isFinite()) return
        val b = bucketOf(c.mcapAtHunt)
        if (b == Int.MIN_VALUE) return
        val utility7801 = try {
            com.lifecyclebot.engine.truth.SpecialistObjective7801.evaluate(
                c.lane, e.netReturnPct, e.holdingTimeMs, e.exitReason
            )
        } catch (_: Throwable) { null }
        val m = stats.getOrPut(c.lane) { ConcurrentHashMap() }
        synchronized(m) {
            val s = m.getOrPut(b) { Stat() }
            s.n++
            s.sumRet += ret
            s.sumUtility += utility7801?.utility ?: ret
            try {
                prefs?.edit()?.putString(
                    c.lane,
                    m.entries.joinToString(";") { "${it.key},${it.value.n},${it.value.sumRet},${it.value.sumUtility}" }
                )?.apply()
            } catch (_: Throwable) {}
        }
        try {
            PipelineHealthCollector.labelInc("LANE_HUNT_7297_GRADED_${c.lane}")
            if (utility7801 != null) {
                PipelineHealthCollector.labelInc("SPECIALIST_OBJECTIVE_7801_${utility7801.lane}_${utility7801.magnitudeClass}")
            }
        } catch (_: Throwable) {}
    }

    fun statusLine(): String = profiles.joinToString(" · ") { p ->
        val (lo, hi) = fluidBandFor(p.lane) ?: (p.baseMin to p.baseMax)
        val graded = stats[p.lane]?.values?.sumOf { it.n } ?: 0
        val fmt = { v: Double -> if (v >= Double.MAX_VALUE / 2) "∞" else if (v >= 1e6) "${"%.1f".format(v / 1e6)}M" else "${(v / 1e3).toInt()}k" }
        "${p.lane}[band=${fmt(lo)}-${fmt(hi)} hunted=${hunted[p.lane] ?: 0} claims=${claims.values.count { it.lane == p.lane }} graded=$graded]"
    }
}
