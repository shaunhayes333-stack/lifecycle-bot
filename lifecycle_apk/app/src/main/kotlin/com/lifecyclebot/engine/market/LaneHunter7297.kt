package com.lifecyclebot.engine.market

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TreasuryScannerFeed
import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
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
    private const val PICKS_PER_LANE = 12  // V5.0.8029 — 8 -> 12: the sweep now carries ~420 rows across every venue
    private const val CLAIM_TTL_MS = 30L * 60 * 1000
    private const val MIN_BUCKET_N = 8
    private const val PREFS = "aate_lane_hunter_7297"

    /** Pure (V5.0.8201): is a sweep row of [ageHours] at sweep time [snapAtMs] past the sniper window by publish time [nowMs]? */
    internal fun sniperPastWindowAtPublish8201(ageHours: Double, snapAtMs: Long, nowMs: Long): Boolean {
        val ageSecs = (if (ageHours.isFinite() && ageHours > 0.0) ageHours * 3600.0 else 0.0) + ((nowMs - snapAtMs).coerceAtLeast(0L) / 1000.0)
        return ageSecs > com.lifecyclebot.v3.scoring.ProjectSniperAI.SNIPER_WINDOW_SECS_8169
    }

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
            // V5.0.8323 — the ideal freshness lives on each lane's cheat sheet.
            "PROJECT_SNIPER", "EXPRESS", "MANIPULATED", "MOONSHOT", "SHITCOIN" -> freshnessFit7797(r, com.lifecyclebot.engine.cortex.LaneCheatSheet8323.idealMaxAgeHours8323(lane))
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
            "SHITCOIN", com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323("SHITCOIN"), com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMax8323("SHITCOIN"),  // V5.0.8323 — the lane's sheet (ShitCoinTraderAI's band)
            fits = { true },
            rank = { r -> activity(r) + 2.0 * turnover(r).coerceAtMost(5.0) + r.organicScore / 50.0 },
        ),
        Profile(
            "QUALITY", com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323("QUALITY"), com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMax8323("QUALITY"),  // V5.0.8323
            fits = { r -> r.liquidityUsd > 0.0 },
            rank = { r -> r.organicScore / 20.0 + turnover(r).coerceAtMost(3.0) + log10(1.0 + r.holders) + (if (r.verified) 1.0 else 0.0) },
        ),
        Profile(
            "BLUECHIP", com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323("BLUECHIP"), com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMax8323("BLUECHIP"),  // V5.0.8323
            // 8168: mint suffix is provenance, not a native BLUECHIP veto. The cap band above and
            // native liquidity/quality checks still decide whether a graduated token fits this desk.
            fits = { r -> r.liquidityUsd.isFinite() && r.liquidityUsd > 0.0 },
            rank = { r -> log10(1.0 + r.liquidityUsd) + log10(1.0 + r.volumeH1Usd) + (if (r.verified) 1.0 else 0.0) },
        ),
        Profile(
            "DIP_HUNTER", com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323("DIP_HUNTER"), com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMax8323("DIP_HUNTER"),  // V5.0.8323
            // V5.0.8031 — the native mandate (DipHunterAI): 2h+ old, $10k+ liquidity and 10% of cap, a real pullback
            // (15-55% over the day, or 5-35% in the hour) and not still falling in the last 5 minutes. Ranked on the
            // pullback sweet spot (~25%), not the deepest knife.
            fits = { r -> HunterMandates8031.dipFits8031(r.ageHours, r.liquidityUsd, r.mcapUsd, r.priceChangeH1Pct, r.priceChangeH24Pct, r.priceChange5mPct) },
            rank = { r -> HunterMandates8031.dipRank8031(r.priceChangeH1Pct, r.priceChangeH24Pct) + log10(1.0 + r.volumeH1Usd) },
        ),
        Profile(
            "MOONSHOT", com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323("MOONSHOT"), com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMax8323("MOONSHOT"),  // V5.0.8323
            fits = { r ->
                // V5.0.7796 — not "anything green". Moonshot hunts asymmetry:
                // real liquidity/activity, positive ignition, and no already-consumed
                // vertical spike. Continuation up to $5M stays eligible; ranking below
                // strongly prefers the pre-liftoff end of that universe.
                // V5.0.8293 — at the native liquidity floor (MoonshotTraderAI.minLiquidityUsdNow8180: $2k -> $15k mature);
                // a $1.5k pool was hunted and then refused natively as liq_too_low.
                HunterMandates8293.moonshotFits8293(r.priceChangeH1Pct, r.liquidityUsd, r.txCountH1, HunterMandates8293.moonshotMinLiq8293())
            },
            rank = { r ->
                // V5.0.8293 — the sub-$10k / first-3-minute peak (6.0 / 3.0) was the realised losing shape (AGE_MIN<3,
                // MCAP<10k strongly negative); the priors now peak at $10k-$100k and 3-15 minutes.
                val earlyCap = HunterMandates8293.moonshotCapPrior8293(r.mcapUsd)
                val youth = HunterMandates8293.moonshotYouthPrior8293(r.ageHours)
                val ignition = r.priceChangeH1Pct.coerceIn(0.0, 40.0) / 8.0
                val exhaustionPenalty = if (r.priceChangeH1Pct > 60.0) 0.55 else 1.0
                exhaustionPenalty * (earlyCap + youth + ignition +
                    activity(r) + 2.0 * turnover(r).coerceAtMost(4.0))
            },
        ),
        Profile(
            "TREASURY", com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323("TREASURY"), com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMax8323("TREASURY"),  // V5.0.8323
            fits = { r -> r.liquidityUsd >= TreasuryScannerFeed.MIN_TREASURY_LIQUIDITY },
            rank = { r -> turnover(r).coerceAtMost(3.0) * 2.0 + log10(1.0 + r.liquidityUsd) },
        ),
        Profile(
            "CASHGEN", com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323("CASHGEN"), com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMax8323("CASHGEN"),  // V5.0.8323
            fits = { r -> r.liquidityUsd >= TreasuryScannerFeed.MIN_TREASURY_LIQUIDITY },
            rank = { r -> turnover(r).coerceAtMost(3.0) * 2.0 + log10(1.0 + r.volumeH1Usd) },
        ),
        // V5.0.7796 — the five specialists below previously had NO hunter profile.
        // They only received generic scanner spillover / source affinity, violating
        // the lane contract that every specialist hunts prey matching its own design.
        // These profiles only DISCOVER + CLAIM. Native specialist brains still have
        // to qualify the token before ownership can change.
        Profile(
            "EXPRESS", com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323("EXPRESS"), com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMax8323("EXPRESS"),  // V5.0.8323 — ShitCoinExpress's band, via the sheet
            // V5.0.8031 — the native mandate (ShitCoinExpress): known age 5 min+, 1h continuation 1-30% (above 30%
            // it rejects as CHASE_EXTENDED), the last 5 minutes still up but not vertical.
            fits = { r ->
                // V5.0.8293 — the native terms exactly (ShitCoinExpress.evaluate): no age floor, 5m chase line at 20%
                // (8031's 5-minute age and 0.5-12% 5m window passed 7 of 118 in-band rows the native would read).
                r.txCountH1 >= 4 && r.volumeH1Usd > 0.0 && r.liquidityUsd >= com.lifecyclebot.engine.cortex.LaneCheatSheet8323.EXPRESS_MIN_LIQ_8323 &&
                    HunterMandates8293.expressFits8293(r.priceChangeH1Pct, r.priceChange5mPct)
            },
            rank = { r ->
                // Momentum ignition / acceleration: prefer active turnover and
                // a real positive move, but penalise already-exhausted >80% spikes.
                val chasePenalty = if (r.priceChangeH1Pct > 80.0) 0.45 else 1.0
                chasePenalty * (activity(r) + 2.5 * turnover(r).coerceAtMost(4.0) +
                    r.priceChangeH1Pct.coerceIn(0.0, 40.0) / 8.0 + HunterMandates8293.expressM5Rank8293(r.priceChange5mPct))
            },
        ),
        Profile(
            "PROJECT_SNIPER", com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323("PROJECT_SNIPER"), com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMax8323("PROJECT_SNIPER"),  // V5.0.8323 — ProjectSniperAI's band
            fits = { r ->
                // ProjectSniperAI is explicitly a pre-ignition / first-minutes desk.
                // V5.0.8031 — age must be KNOWN (0 meant unknown and let old coins in) and liquidity at the native $2k.
                HunterMandates8031.knownAgeWithin8031(r.ageHours, 0.05) && r.priceChangeH1Pct in -5.0..35.0 &&
                    r.liquidityUsd >= 2_000.0
            },
            rank = { r ->
                val youth = (1.0 - (r.ageHours / 0.05)).coerceIn(0.0, 1.0) * 5.0
                youth + activity(r) + 2.0 * turnover(r).coerceAtMost(3.0) +
                    r.priceChangeH1Pct.coerceIn(-5.0, 15.0) / 10.0
            },
        ),
        Profile(
            "MANIPULATED", com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323("MANIPULATED"), com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMax8323("MANIPULATED"),  // V5.0.8323 — ManipulatedTraderAI's band
            fits = { r ->
                // The hunter finds young, violently one-sided candidates; the
                // native ManipulatedTraderAI still requires actual manipulation
                // evidence (bundle/order-flow/etc.) before qualification.
                // V5.0.8031 — known age within the native 8 minutes; the native gate still wants bundle / one-sided flow.
                // V5.0.8293 — the native FLUID age / liquidity (12 min / $1.5k bootstrap -> 8 min / $3k mature).
                HunterMandates8293.manipulatedFits8293(r.ageHours, r.txCountH1, r.priceChangeH1Pct, r.liquidityUsd,
                    HunterMandates8293.manipulatedMaxAgeHours8293(), HunterMandates8293.manipulatedMinLiq8293())
            },
            rank = { r ->
                3.0 * turnover(r).coerceAtMost(5.0) + activity(r) +
                    r.priceChangeH1Pct.coerceIn(0.0, 60.0) / 6.0
            },
        ),
        Profile(
            "CYCLIC", com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323("CYCLIC"), com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMax8323("CYCLIC"),  // V5.0.8323 — the sheet owns CYCLIC's mandate
            fits = { r ->
                // Cyclic is not a first-minute sniper. Hunt sellable, active,
                // established-enough names with turnover but without a one-way
                // exhaustion move; its own engine decides whether the cycle exists.
                r.ageHours >= com.lifecyclebot.engine.cortex.LaneCheatSheet8323.CYCLIC_MIN_AGE_HOURS_8323 && r.liquidityUsd >= com.lifecyclebot.engine.cortex.LaneCheatSheet8323.CYCLIC_MIN_LIQ_8323 &&
                    r.volumeH1Usd > 0.0 && r.priceChangeH1Pct in -15.0..20.0
            },
            rank = { r ->
                2.0 * turnover(r).coerceAtMost(3.0) + activity(r) -
                    kotlin.math.abs(r.priceChangeH1Pct) / 20.0
            },
        ),
        Profile(
            "CORE", com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323("CORE"), com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMax8323("CORE"),  // V5.0.8323
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

    private class Stat { var n = 0; var sumRet = 0.0; fun mean() = if (n > 0) sumRet / n else 0.0 }

    private val stats = ConcurrentHashMap<String, ConcurrentHashMap<Int, Stat>>()
    // V5.0.7803 — claims are lane+mint scoped. A mint may live on several
    // specialist desks simultaneously; discovery no longer forces one hunter
    // winner before any strategy has actually become executable.
    private val claims = ConcurrentHashMap<String, Claim>()
    private fun claimKey7803(lane: String, mint: String) = "${lane.uppercase()}|$mint"
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
        return fluidBand(HunterMandates8293.baseMin8293(p.lane, p.baseMin), p.baseMax, bucketStatsFor(lane))  // V5.0.8293
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
            val k7803 = claimKey7803("MOONSHOT", m.mint)
            val existing = claims[k7803]
            if (existing != null && now - existing.atMs <= CLAIM_TTL_MS) continue
            val mcap = try { com.lifecyclebot.engine.GlobalTradeRegistry.getEntry(m.mint)?.initialMcap ?: 0.0 } catch (_: Throwable) { 0.0 }
            claims[k7803] = Claim("MOONSHOT", mcap, now)
            try { SpecialistCandidateBooks7803.publishHunt("MOONSHOT", m.mint, m.symbol, "MOMENTUM_7298") } catch (_: Throwable) {}
            n++
        }
        if (n > 0) try { PipelineHealthCollector.labelInc("LANE_HUNT_7298_MOMENTUM_CLAIMED") } catch (_: Throwable) {}
        return n
    }

    /**
     * V5.0.7812 — autonomous realised-edge pressure at discovery.
     * Clean terminal outcomes already grade each lane/mcap cohort. Apply that
     * evidence with sample confidence so repeated losers sink in the hunter
     * ordering while profitable cohorts rise. Ordering only: nothing is hidden
     * from learning and no safety/FDG authority is bypassed.
     */
    private fun brainMultiplier(lane: String, mcap: Double): Double {
        val s = stats[lane]?.get(bucketOf(mcap)) ?: return 1.0
        if (s.n < MIN_BUCKET_N) return 1.0
        val sampleConfidence7812 = (s.n.toDouble() / (s.n + 12.0)).coerceIn(0.0, 1.0)
        val learnedDelta7812 = (s.mean() * 3.0 * sampleConfidence7812).coerceIn(-0.50, 0.45)
        return (1.0 + learnedDelta7812).coerceIn(0.50, 1.45)
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
            "DIP_HUNTER" -> o.setup == "DIP_RECOVERY"
            "SHITCOIN" -> o.setup in setOf("EARLY_MOMENTUM_IGNITION", "BREAKOUT_EXPANSION", "LIQUIDITY_EXPANSION")
            "QUALITY", "BLUECHIP" -> o.setup in setOf("CONTINUATION", "LIQUIDITY_EXPANSION", "RELATIVE_STRENGTH_LEADER")
            "TREASURY", "CASHGEN" -> o.setup in setOf("CONTINUATION", "LIQUIDITY_EXPANSION")
            else -> false
        }
        if (affinity) m *= 1.10
        if (o.setup == "DISTRIBUTION" || o.setup == "EXHAUSTION") m *= 0.88
        return m.coerceIn(0.70, 1.40)
    }

    /**
     * V5.0.7812 — adaptive token-cheat-sheet timing from the resident market
     * tape. It ranks current transition quality; it never authorizes a trade.
     */
    private fun adaptiveTimingMultiplier7812(lane: String, r: MarketSweep7297.Row): Double {
        val o = MarketSweep7297.opportunityFor7777(r.mint) ?: return 1.0
        val l = lane.uppercase()
        val runner = l in setOf("MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "SHITCOIN", "MANIPULATED")
        val quality = l in setOf("QUALITY", "BLUECHIP", "TREASURY", "CASHGEN")
        var m = when (o.setup) {
            "EARLY_MOMENTUM_IGNITION" -> if (runner) 1.28 else if (quality) 1.06 else 1.02
            "BREAKOUT_EXPANSION" -> if (runner) 1.20 else if (quality) 1.08 else 1.02
            "LIQUIDITY_EXPANSION" -> if (quality || l == "SHITCOIN") 1.16 else 1.08
            "RELATIVE_STRENGTH_LEADER" -> if (l in setOf("MOONSHOT", "QUALITY", "BLUECHIP")) 1.14 else 1.05
            "CONTINUATION" -> if (l in setOf("MOONSHOT", "QUALITY", "BLUECHIP", "TREASURY", "CASHGEN")) 1.12 else 1.05
            "DIP_RECOVERY" -> if (l == "DIP_HUNTER") 1.30 else 0.96
            "DISTRIBUTION" -> if (l == "DIP_HUNTER") 0.78 else 0.62
            "EXHAUSTION" -> if (l == "DIP_HUNTER") 0.72 else 0.55
            else -> 1.0
        }
        m *= when {
            o.providerAgreement >= 3 -> 1.08
            o.providerAgreement == 2 -> 1.04
            else -> 0.96
        }
        if (o.buyPressurePct >= 60.0 && o.volumeAcceleration >= 1.25 && o.txAcceleration >= 1.10) m *= 1.10
        if (o.buyPressurePct <= 42.0) m *= 0.82
        if (o.liquidityDeltaPct <= -12.0) m *= 0.82
        if (runner && o.priceVelocity5mPct > 0.0 && o.relativeStrengthPct > 0.0) m *= 1.06
        if (runner && o.priceVelocity5mPct < -4.0) m *= 0.78
        if (l == "DIP_HUNTER" && o.setup != "DIP_RECOVERY" &&
            r.priceChangeH1Pct < -3.0 && o.priceVelocity5mPct <= 0.0) m *= 0.70
        if (quality && o.providerAgreement >= 2 && o.liquidityDeltaPct >= 0.0) m *= 1.05
        if (l == "CYCLIC" && o.setup in setOf(
                "EARLY_MOMENTUM_IGNITION", "BREAKOUT_EXPANSION", "DISTRIBUTION", "EXHAUSTION"
            )) m *= 0.78
        return m.coerceIn(0.45, 1.55)
    }

    /**
     * Each lane picks from the sweep. Returns lane → rows, and records every
     * pick as a claim the election honours while the token stays in band.
     */
    fun hunt(snap: MarketSweep7297.Snapshot): Map<String, List<MarketSweep7297.Row>> {
        ensureSubscribed()
        val byMint = snap.rows.associateBy { it.mint }
        val audit8168 = ArrayList<com.lifecyclebot.engine.BotImprovementReport8168.Hunt>()
        // V5.0.8169 — tokenised equities and pegged / receipt tokens are not meme prey (HunterAssetClass8169); rows on the
        // intake's 10-minute requeue cooldown (re-picked, never re-deliverable) rank after every deliverable row.
        val nonMeme8169 = snap.rows.mapNotNull { r -> HunterAssetClass8169.of(r).takeIf { it != HunterAssetClass8169.Kind.MEME_TRADEABLE }?.let { r.mint to it } }.toMap()
        val now8169 = System.currentTimeMillis()
        requeuedAt8169.entries.removeIf { !onCooldown8169(it.value, now8169) }
        val top8293 = snap.rows.mapNotNull { r -> r.mint.takeIf { OpportunityRoute8293.isTop8293(MarketSweep7297.opportunityFor7777(it)) } }.toSet()
        val tests8293 = ArrayList<OpportunityRoute8293.LaneTest8293>()
        val ranked = profiles.associate { p ->
            val (lo, hi) = fluidBand(HunterMandates8293.baseMin8293(p.lane, p.baseMin), p.baseMax, bucketStatsFor(p.lane))  // V5.0.8293 native floor
            tests8293 += OpportunityRoute8293.LaneTest8293(p.lane, { r -> r.mcapUsd in lo..hi && r.mint !in nonMeme8169 }, p.fits)
            val inBand8168 = snap.rows.filter { it.mcapUsd in lo..hi && it.mint !in nonMeme8169 }
            val eligible8168 = inBand8168.filter { p.fits(it) && com.lifecyclebot.engine.cortex.LaneCheatSheet8323.hunterFits8323(p.lane, it.ageHours, it.liquidityUsd) }  // V5.0.8323 — the lane's mandate (age band, liquidity floor now)
            p.lane to eligible8168
                // Compute each expensive evidence/ranking multiplier exactly
                // once per candidate, not O(n log n) times inside TimSort's
                // comparator. Preserve the original score and stable tie order.
                .map { r ->
                    val heat = MarketSweep7297.Band.of(r.mcapUsd)?.let { snap.bands[it]?.breadthPct } ?: 50.0
                    r to (p.rank(r) * brainMultiplier(p.lane, r.mcapUsd) * modeLiqMultiplier(p.lane, r.liquidityUsd) *
                        (0.9 + 0.2 * heat / 100.0) * opportunityLaneMultiplier7777(p.lane, r) *
                        commonSenseMult7797(p.lane, r) * adaptiveTimingMultiplier7812(p.lane, r) *
                        com.lifecyclebot.engine.ExpertTraderKnowledge7813.marketRankMultiplier7813(p.lane, r))
                }
                .sortedByDescending { it.second }
                .map { it.first.mint }
                // V5.0.8293 — opportunity leaders first, the lane's learned losing shapes last; escalated requeue cooldown last of all.
                .let { order -> HunterShape8293.orderLive8293(p.lane, order, byMint, top8293) }
                .let { order -> deliverableFirst8169(order) { m -> onCooldown8169(requeuedAt8169[m], now8169) || HuntDelivery8293.cooling8293(m, now8169) } }
                .also { ranked8168 -> audit8168 += com.lifecyclebot.engine.BotImprovementReport8168.hunt(
                    p.lane, snap.rows, inBand8168.size, eligible8168.size,
                    ranked8168.take(PICKS_PER_LANE).mapNotNull { byMint[it] }) }
        }
        com.lifecyclebot.engine.BotImprovementReport8168.publishHunts(audit8168, snap.atMs)
        // V5.0.7803 — DO NOT deal the market into mutually-exclusive hunter
        // buckets. Each specialist owns a resident candidate book and may watch
        // the same mint independently. Arbitration belongs at READY execution,
        // not at discovery.
        // V5.0.8170: 8169 saw 1046 already-watched hits against only 27 resident
        // emissions in 14 sweeps. Preserve the best three for hot continuation;
        // use the remaining slots for fresh mints before repeating watchlist
        // residents. All 12 lanes keep their own ranking and can still select
        // the SAME mint; this is discovery ordering, never execution authority.
        val residentPicks7803 = ranked.mapValues { (lane, mints) ->
            // V5.0.8293 — fresh-first only among deliverable rows outside the lane's learned losing shapes.
            HunterShape8293.deliveryPicks8293(mints, { mint ->
                try { BotService.status.tokens.containsKey(mint) } catch (_: Throwable) { false }
            }, { m -> onCooldown8169(requeuedAt8169[m], now8169) || HuntDelivery8293.cooling8293(m, now8169) ||
                byMint[m]?.let { r -> HunterShape8293.losingShape8293(lane, r, HunterShape8293::live8293) } != null }, PICKS_PER_LANE)
        }
        OpportunityRoute8293.noteHunt8293(snap.rows, tests8293, residentPicks7803)
        noteHuntAudit8169(snap, nonMeme8169, residentPicks7803, now8169)
        val now = System.currentTimeMillis()
        claims.entries.removeIf { now - it.value.atMs > CLAIM_TTL_MS }
        val out = residentPicks7803.mapValues { (lane, mints) ->
            mints.mapNotNull { byMint[it] }.also { rows ->
                rows.forEach { r ->
                    // V5.0.8201 — ProjectSniperAI is a first-180 s desk and LaunchBirth8169 strips PROJECT_SNIPER from a
                    // past-window coin's native scope. A sweep pick that is already past the window when it is published
                    // (row age + sweep latency) can never be evaluated as a sniper candidate: 5.0.8200 hunted 7, book 0.
                    // It still emits as discovery (MOONSHOT / EXPRESS / SHITCOIN read it natively); it is not a sniper
                    // resident and is counted.
                    if (lane == "PROJECT_SNIPER" && sniperPastWindowAtPublish8201(r.ageHours, snap.atMs, now)) {
                        try { PipelineHealthCollector.labelInc("LANE_HUNT_SNIPER_PAST_WINDOW_AT_PUBLISH_8201") } catch (_: Throwable) {}
                        return@forEach
                    }
                    claims[claimKey7803(lane, r.mint)] = Claim(lane, r.mcapUsd, now)
                    try { SpecialistCandidateBooks7803.publishHunt(lane, r.mint, r.symbol, SOURCE_PREFIX + lane) } catch (_: Throwable) {}
                    com.lifecyclebot.engine.LaneDispatch8418.notePoolAtHunt8418(lane, r.mint)  // V5.0.8418 — the sniper's own selection is its discovery
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
                    try {
                        val tm7812 = adaptiveTimingMultiplier7812(lane, r)
                        PipelineHealthCollector.labelInc(
                            when {
                                tm7812 >= 1.12 -> "LANE_HUNT_TIMING_STRONG_7812_$lane"
                                tm7812 < 0.82 -> "LANE_HUNT_TIMING_WEAK_7812_$lane"
                                else -> "LANE_HUNT_TIMING_NEUTRAL_7812_$lane"
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

    // ── V5.0.8169 — deliverable picks first; hunt audit ──

    /** The scanner's requeue cooldown (SolanaMarketScanner.emitResidentHunt7807: one requeue per mint per 10 minutes). */
    const val REQUEUE_COOLDOWN_MS_8169 = 10L * 60 * 1000
    private val requeuedAt8169 = ConcurrentHashMap<String, Long>()

    /**
     * SolanaMarketScanner.emitResidentHunt7807: [mint] was just re-queued to intake. Until its cooldown ends a re-pick of
     * it cannot be delivered again (8168: MARKET_HUNT_7301_REQUEUE_COOLDOWN_CASHGEN=130, TREASURY=127, BLUECHIP=117 — the
     * same rows re-picked every sweep), so it ranks after every deliverable row.
     */
    fun noteRequeued8169(mint: String, atMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        if (requeuedAt8169.size > 4_000) requeuedAt8169.entries.removeIf { !onCooldown8169(it.value, atMs) }
        requeuedAt8169[mint] = atMs
    }

    /** Pure: is a mint re-queued at [requeuedAtMs] still inside the intake's requeue cooldown at [nowMs]? */
    fun onCooldown8169(requeuedAtMs: Long?, nowMs: Long): Boolean =
        requeuedAtMs != null && requeuedAtMs > 0L && nowMs - requeuedAtMs in 0L until REQUEUE_COOLDOWN_MS_8169

    /** Pure: [ranked] with the rows [cooling] says cannot be delivered moved to the end, order otherwise unchanged. */
    fun deliverableFirst8169(ranked: List<String>, cooling: (String) -> Boolean): List<String> {
        val (cold, hot) = ranked.partition(cooling)
        return hot + cold
    }

    /** Keeps a small top-ranked set while rotating new candidates into the rest of a lane's watchbook.
     * Does not fabricate fit or widen the lane's mandate: inputs were already native ranked.
     */
    fun freshFirst8170(ranked: List<String>, watched: (String) -> Boolean, pickLimit: Int): List<String> {
        if (pickLimit <= 0) return emptyList()
        val leaders = ranked.take(minOf(3, pickLimit))
        val rest = ranked.drop(leaders.size)
        val (old, fresh) = rest.partition(watched)
        return (leaders + fresh + old).take(pickLimit)
    }

    @Volatile private var lastAudit8169 = "no hunt yet"

    private fun noteHuntAudit8169(snap: MarketSweep7297.Snapshot, nonMeme: Map<String, HunterAssetClass8169.Kind>,
                                  picks: Map<String, List<String>>, nowMs: Long) {
        try {
            val all = picks.values.flatten()
            val cooling = all.count { onCooldown8169(requeuedAt8169[it], nowMs) }
            val byKind = nonMeme.values.groupingBy { it.name }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "0" }
            val examples = nonMeme.keys.take(3).mapNotNull { m -> snap.rows.firstOrNull { it.mint == m }?.symbol?.take(12) }.joinToString(",")
            lastAudit8169 = "rows=${snap.rows.size} nonMemeExcluded8169[$byKind${if (examples.isNotBlank()) " e.g. $examples" else ""}] " +
                "picks=${all.size} uniqueMints=${all.toSet().size} pickedOnRequeueCooldown=$cooling " +
                "perLane=[${picks.entries.joinToString(" ") { "${it.key}:${it.value.size}" }}]"
            if (nonMeme.isNotEmpty()) PipelineHealthCollector.labelInc("LANE_HUNT_8169_NON_MEME_EXCLUDED")
        } catch (_: Throwable) {}
    }

    /** The latest hunt as the 8169 audit reads it (non-meme exclusions, overlap across lanes, cooldown picks). */
    fun auditLine8169(): String = lastAudit8169 + " · " + OpportunityRoute8293.line8293() + " · " + statusLine()

    /** All resident hunter lanes for [mint] that still fit their own fluid band. */
    internal fun claimsFor7803(mint: String, currentMcap: Double): Set<String> {
        val now = System.currentTimeMillis()
        val out = linkedSetOf<String>()
        claims.entries.removeIf { now - it.value.atMs > CLAIM_TTL_MS }
        claims.values.asSequence().filter { c ->
            val key = claimKey7803(c.lane, mint)
            claims[key] === c
        }.forEach { c ->
            val inBand = if (currentMcap > 0.0 && currentMcap.isFinite()) {
                val band = fluidBandFor(c.lane)
                band != null && currentMcap >= band.first && currentMcap <= band.second
            } else true
            if (inBand) out += c.lane
        }
        return out
    }

    /**
     * V5.0.8293 — RunTape8293: a coin its executed tape shows entering a run is claimed for [lane] (MOONSHOT / SHITCOIN /
     * PROJECT_SNIPER) at [mcapUsd] (0 = not yet read; claimsFor7803 then honours it until a cap is known). An existing
     * live claim for the lane is never overwritten. Ownership only; the lane's scorer, FDG and safety still decide.
     */
    internal fun claimRun8293(lane: String, mint: String, mcapUsd: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lane.isBlank() || mint.isBlank() || profiles.none { it.lane == lane }) return false
        val k = claimKey7803(lane, mint)
        val existing = claims[k]
        if (existing != null && nowMs - existing.atMs <= CLAIM_TTL_MS) return false
        claims[k] = Claim(lane, if (mcapUsd.isFinite() && mcapUsd > 0.0) mcapUsd else 0.0, nowMs)
        return true
    }

    /**
     * Legacy single-claim compatibility. No execution authority should depend
     * on this after 7803; callers needing discovery ownership use claimsFor7803.
     */
    internal fun claimFor(mint: String, currentMcap: Double): String? =
        claimsFor7803(mint, currentMcap).firstOrNull()

    // V5.0.7809 — hunter claims frozen on the canonical position at OPEN.
    private val boundClaims7809 = ConcurrentHashMap<String, Claim>()
    private const val KEY_BOUND_7809 = "bound_claims_7809"
    private const val MAX_BOUND_7809 = 400

    private fun laneAlias7809(raw: String): String = raw.trim().uppercase()
        .replace("BLUE_CHIP", "BLUECHIP")
        .replace("SHITCOIN_EXPRESS", "EXPRESS")

    /**
     * V5.0.7809 — called at the canonical OPEN (LearningAttributionBinder7809)
     * with the position's OWNER lane. Only the owner lane's own claim is bound
     * (TREASURY may own a CASHGEN hunt, the one pairing 7803 already grades);
     * a contributor lane that also watched the mint is never credited.
     */
    fun bindPosition7809(positionId: String, mint: String, ownerLane: String): Boolean {
        if (positionId.isBlank() || mint.isBlank() || ownerLane.isBlank()) return false
        if (boundClaims7809.containsKey(positionId)) return true
        val lane = laneAlias7809(ownerLane)
        val now = System.currentTimeMillis()
        val direct = claims[claimKey7803(lane, mint)]
        val cashgen = if (lane == "TREASURY") claims[claimKey7803("CASHGEN", mint)] else null
        val c = listOfNotNull(direct, cashgen)
            .filter { now - it.atMs in 0L..CLAIM_TTL_MS }
            .maxByOrNull { it.atMs } ?: return false
        boundClaims7809[positionId] = c
        if (boundClaims7809.size > MAX_BOUND_7809) {
            boundClaims7809.entries.sortedBy { it.value.atMs }
                .take(boundClaims7809.size - MAX_BOUND_7809)
                .forEach { boundClaims7809.remove(it.key, it.value) }
        }
        persistBound7809()
        try { PipelineHealthCollector.labelInc("LANE_HUNT_7809_POSITION_BOUND_${c.lane}") } catch (_: Throwable) {}
        return true
    }

    private fun persistBound7809() {
        val p = prefs ?: return
        try {
            val body = boundClaims7809.entries
                .filter { !it.key.contains(',') && !it.key.contains(';') }
                .joinToString(";") { "${it.key},${it.value.lane},${it.value.mcapAtHunt},${it.value.atMs}" }
            p.edit().putString(KEY_BOUND_7809, body).apply()
        } catch (_: Throwable) {}
    }

    private fun restoreBound7809(p: SharedPreferences) {
        try {
            p.getString(KEY_BOUND_7809, null)?.split(';')?.forEach { row ->
                val f = row.split(',')
                if (f.size != 4 || f[0].isBlank()) return@forEach
                val mcap = f[2].toDoubleOrNull() ?: return@forEach
                val at = f[3].toLongOrNull() ?: return@forEach
                boundClaims7809.putIfAbsent(f[0], Claim(f[1], mcap, at))
            }
        } catch (_: Throwable) {}
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
        restoreBound7809(p)
        ensureSubscribed()
    }

    private fun ensureSubscribed() {
        if (!subscribed.compareAndSet(false, true)) return
        try {
            CanonicalTradeFinalizedBus6450.subscribe { e -> onSettled(e) }
        } catch (_: Throwable) { subscribed.set(false) }
    }

    private fun onSettled(e: CanonicalTradeFinalizedBus6450.Event) {
        // V5.0.7807 — lane bands learn from clean terminal truth only (Field Manual L357).
        if (!CanonicalTradeFinalizedBus6450.isCleanForLearning7807(e)) return
        // V5.0.7803 — grade the exact resident desk+mint that produced the
        // executed lane. Never select another mint merely because its lane matches.
        // V5.0.7809 — the resident book grades the owner lane that held this
        // candidate at OPEN, exactly once per position (Field Manual L356).
        try { SpecialistCandidateBooks7803.gradeSettled7809(e.positionId, e.outcome == CanonicalTradeFinalizedBus6450.Outcome.WIN) } catch (_: Throwable) {}
        // V5.0.7809 — prefer the claim frozen on this position at OPEN. A claim
        // lives CLAIM_TTL_MS (30 min) and is refreshed only while the mint stays in
        // the lane's top picks, so by the time a held position settled the claim
        // had usually expired or been re-stamped at a later mcap — graded=0.
        val boundClaim7809 = boundClaims7809.remove(e.positionId)
        if (boundClaim7809 != null) persistBound7809()
        val directLane7803 = laneAlias7809(e.entryLane)
        val direct7803 = claims[claimKey7803(directLane7803, e.mint)]
        val cashgen7803 = if (directLane7803 == "TREASURY")
            claims[claimKey7803("CASHGEN", e.mint)] else null
        val c = boundClaim7809 ?: listOfNotNull(direct7803, cashgen7803).maxByOrNull { it.atMs } ?: return
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
    } + " · boundOpen7809=${boundClaims7809.size} · resident7809[${SpecialistCandidateBooks7803.gradedLine7809()}]"
}
