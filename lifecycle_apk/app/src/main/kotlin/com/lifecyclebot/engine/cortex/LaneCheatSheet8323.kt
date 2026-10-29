package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.truth.StrategyRank8153
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8323 — ONE TAILORED CHEAT SHEET PER LANE.
 *
 * Owner: "It's meant to favour the good setups and rotate away from the bad ones. Common sense. All specialists,
 * traders and Cortex lanes are meant to have an individual tailored cheat sheet that the lane, trader, specialist,
 * token hunter, scanner and brains are meant to reference."
 *
 * The knowledge was scattered (LanePlaybook7907 menus, TokenMetricStageRouter.LANE_STAGE_SHEET_7928, each trader's
 * mcap / liquidity / age constants, LaneHunter7297's literals, FieldManual7715's lane mandates, ExitProfile7955,
 * LaunchTape7921's early bar, TapeStage8316, the merge queue's source map) and the components disagreed: the scanner
 * offered sub-$10k launches to QUALITY (whose own rule wants $75k+), hunters spent picks on rows the lane refuses,
 * and the Cortex refused setups the lane's own forward labels proved.
 *
 * This registry composes, per lane, from where each number already lives (no second copy):
 *   mandate   stages (LANE_STAGE_SHEET_7928), birth-age band, mcap band, liquidity band (the trader's own constants,
 *             fluid where the trader is fluid), venues and the lane-native thresholds;
 *   setups    every menu setup with its cheat-sheet prior and its record — forward labels (LanePlaybook7907's executable,
 *             8295-governed read) and live closes (LIVE account only: paper never enters a favour list) — ranked by a
 *             TAIL-AWARE expectancy (the shrunk MEAN net of executable cost, never the median: a runner lane's edge is
 *             its tail), with the runner hit-rate and the runner average shown;
 *   verdicts  FAVOUR / NEUTRAL / AVOID, rotated automatically with hysteresis ([judge8323]); an avoided setup re-probes on
 *             the lane's exploration slot ([reprobe8323]);
 *   timing    the early-buyers bar (LaunchTape7921.earlyPass8165), stage-from-tape (TapeStage8316) and the chase rule
 *             (LanePlaybook7907.timingBand8094);
 *   exits     runner vs scalp, stop and target (FieldManual7715.mandateFor), trail arm (RunnerExitProfile7277);
 *   hunting   the hunter band and ideal age (LaneHunter7297 reads them here) and the scanner sources that feed the lane
 *             (TokenMergeQueue reads [SOURCE_LANES_8323] here).
 *
 * Consumers read the sheet: the merge queue routes a coin only to lanes whose mandate fits ([routeLanes8323]); the
 * hunters take their bands, ideal ages and mandate fit from it; the playbook's best-setup choice and per-token strategy
 * pick put FAVOUR first and drop AVOID; CandidateAdmission8136 lets FAVOUR support and AVOID refuse (NEUTRAL falls to its
 * existing rules); TacticSwitcher rotates toward a favoured shape; one Cortex voter per lane (SHEET_FAVOUR_<lane>) reads the
 * verdict as an input, never as authority. Hard safety, the route minimum and execution economics are never touched.
 */
object LaneCheatSheet8323 {

    enum class Verdict8323 { FAVOUR, NEUTRAL, AVOID }

    /** Every lane with a playbook: the meme specialists, the traders, and CORE / CASHGEN / TREASURY / CYCLIC. */
    val LANES_8323: List<String> = listOf(
        "SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "QUALITY",
        "BLUECHIP", "DIP_HUNTER", "TREASURY", "CASHGEN", "CYCLIC", "CORE",
    )

    // ── evidence bars ──
    /** A setup earns FAVOUR on at least this many graded labels with mean - SE above max(prior, 0) + cost. */
    const val FAVOUR_MIN_N_8323 = 20.0
    /** A clear loss (mean + 2 SE below cost) needs this many labels. */
    private const val AVOID_MIN_N_8323 = 12.0
    /** A proven loss (mean + SE below cost) on this many labels. */
    private const val PROVEN_N_8323 = 30.0
    /** Live closes losing (8291/8295: 2+ closes with a negative mean) avoid the setup. */
    private const val LIVE_MIN_CLOSES_8323 = 2.0
    /** How often the sheets are rebuilt from the records. */
    private const val REFRESH_MS_8323 = 30_000L
    /** An avoided (lane, setup) re-probes at most once per this gap, on the lane's exploration slot. */
    const val REPROBE_GAP_MS_8323 = 20L * 60_000L
    /** A re-probe grant holds for the same coin this long (one decision's gates all see it). */
    private const val REPROBE_HOLD_MS_8323 = 120_000L

    // ── mandate numbers that had no owner but the hunter's literals (moved here; LaneHunter7297 reads them) ──
    private const val CYCLIC_MCAP_MIN_8323 = 10_000.0
    private const val CYCLIC_MCAP_MAX_8323 = 5_000_000.0
    const val CYCLIC_MIN_LIQ_8323 = 5_000.0
    const val CYCLIC_MIN_AGE_HOURS_8323 = 0.20
    private const val CORE_MCAP_MIN_8323 = 1_500.0
    private const val CORE_MCAP_MAX_8323 = 5_000_000.0
    const val EXPRESS_MIN_LIQ_8323 = 1_000.0
    /** The mandate band a merge-queue mcap print may sit outside of before routing refuses (prints lag a moving coin). */
    private const val ROUTE_MCAP_TOLERANCE_8323 = 0.80
    private const val ROUTE_LIQ_TOLERANCE_8323 = 0.50

    /** Scanner source families -> the lanes they feed (TokenMergeQueue.inferLaneAffinity reads this; was inline there). */
    val SOURCE_LANES_8323: List<Pair<List<String>, List<String>>> = listOf(
        listOf("PUMP", "PORTAL") to listOf("SHITCOIN", "MOONSHOT", "MANIPULATED", "PROJECT_SNIPER"),
        listOf("RAYDIUM", "NEW_POOL") to listOf("MOONSHOT", "SHITCOIN", "MANIPULATED", "DIP_HUNTER"),
        listOf("METEORA", "ORCA", "PUMPSWAP", "JUPITER", "HELIUS", "SOLANA_RPC") to
            listOf("MOONSHOT", "PROJECT_SNIPER", "QUALITY", "BLUECHIP", "STANDARD", "CORE", "V3"),
        listOf("DEX_BOOSTED", "DEX_TRENDING", "COINGECKO", "BIRDEYE") to listOf("QUALITY", "BLUECHIP", "TREASURY", "STANDARD", "CORE", "V3"),
        listOf("INSIDER", "SHARK") to listOf("INSIDER_SHARK", "WHALE_FOLLOW", "COPY_TRADE", "MOONSHOT", "QUALITY"),
        listOf("WHALE") to listOf("QUALITY", "BLUECHIP", "TREASURY"),
    )

    data class Mandate8323(
        val lane: String,
        val stages: Set<String>,
        val ageMinMin: Double,
        val ageMaxMin: Double,
        val mcapMin: Double,
        val mcapMax: Double,
        val liqMin: Double,
        val liqMax: Double,
        val venues: Set<String>,
        val native: String,
        val runner: Boolean,
    )

    data class Timing8323(val earlyBar: String, val tapeStage: Boolean, val chaseRule: String, val avoidBands: Set<String>)

    data class Exit8323(val runner: Boolean, val stopPct: Double, val targetPct: Double, val trailArmPct: Double, val style: String)

    data class Hunter8323(val idealMaxAgeHours: Double, val sources: List<String>)

    /** One menu setup's prior and record. Labels are executable (net of the lane's cost) unless a cost is passed. */
    data class Record8323(
        val setup: String,
        val prior: Double,
        val n: Double,
        val meanPct: Double,
        val sePct: Double,
        val runnerRate: Double = 0.0,
        val runnerAvgPct: Double = Double.NaN,
        val liveN: Double = 0.0,
        val liveMeanPct: Double = Double.NaN,
        val suspect: Boolean = false,
        /** The playbook's shrunk expectancy (record moved off its prior by n/(n+20)); NaN = use the mean. */
        val expectedPct: Double = Double.NaN,
        /**
         * V5.0.8324 §HIVE_PRIOR_WITH_LOCAL_CONFIRMATION — the network's record of this setup read BOUNDED (RobustLabelRead8185:
         * its tail capped, other installs' labels), and this install's own local record (tail-aware read).
         */
        val hiveN: Double = 0.0,
        val hiveMeanPct: Double = Double.NaN,
        val hiveSePct: Double = Double.NaN,
        val localN: Double = Double.NaN,
        val localMeanPct: Double = Double.NaN,
        /**
         * V5.0.8325 §FAVOUR_IS_LOCAL — the standard error of this install's own executable record. FAVOUR reads ONLY the local
         * record ([localN] / [localMeanPct] / [localSePct]); the hive may rank and prior, never favour. [localN] NaN = the
         * record was built from local labels only (pure fixtures): [n] / [meanPct] / [sePct] are then the local record.
         */
        val localSePct: Double = Double.NaN,
    ) {
        /** V5.0.8325 — the local record [n, mean, SE] FAVOUR is judged on. */
        val local8325: DoubleArray get() =
            if (localN.isNaN()) doubleArrayOf(n, meanPct, sePct) else doubleArrayOf(localN, localMeanPct, localSePct)
        /** What the non-runners average (the "median side" of a tail-dependent setup), NaN when not derivable. */
        val nonRunnerAvgPct: Double get() =
            if (runnerAvgPct.isFinite() && runnerRate in 0.0..0.999 && meanPct.isFinite()) (meanPct - runnerRate * runnerAvgPct) / (1.0 - runnerRate) else Double.NaN
    }

    data class Row8323(val record: Record8323, val verdict: Verdict8323, val expectancyPct: Double, val why: String)

    data class Sheet8323(
        val lane: String,
        val mandate: Mandate8323,
        val timing: Timing8323,
        val exit: Exit8323,
        val hunter: Hunter8323,
        val rows: List<Row8323>,
        val rotated: Long,
        val lastRotation: String,
        val builtAtMs: Long,
    ) {
        val favour: List<String> get() = rows.filter { it.verdict == Verdict8323.FAVOUR }.map { it.record.setup }
        val avoid: List<String> get() = rows.filter { it.verdict == Verdict8323.AVOID }.map { it.record.setup }
        val neutral: List<String> get() = rows.filter { it.verdict == Verdict8323.NEUTRAL }.map { it.record.setup }
    }

    // ── lane keys ──

    /** The sheet key of [lane] (canonical upper-case lane; aliases resolved). */
    private fun key8323(lane: String?): String {
        val s = lane?.trim()?.uppercase().orEmpty()
        if (s in LANES_8323) return s
        val c = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(s).uppercase() } catch (_: Throwable) { "" }
        return if (c.isBlank()) s else c
    }

    // ── mandate (pure over the traders' constants) ──

    /** The lane's own market-cap band (the trader's constants; LaneHunter7297's profiles read it here). */
    private fun mcapBand8323(lane: String): Pair<Double, Double> = when (lane.trim().uppercase()) {
        "SHITCOIN" -> com.lifecyclebot.v3.scoring.ShitCoinTraderAI.MIN_MARKET_CAP_USD to com.lifecyclebot.v3.scoring.ShitCoinTraderAI.MAX_MARKET_CAP_USD
        "QUALITY" -> com.lifecyclebot.v3.scoring.QualityTraderAI.MIN_MARKET_CAP_USD to com.lifecyclebot.v3.scoring.QualityTraderAI.MAX_MARKET_CAP_USD
        "BLUECHIP" -> com.lifecyclebot.v3.scoring.BlueChipTraderAI.MIN_MARKET_CAP_USD to Double.MAX_VALUE
        "DIP_HUNTER" -> com.lifecyclebot.v3.scoring.DipHunterAI.MIN_MCAP_USD to com.lifecyclebot.v3.scoring.DipHunterAI.MAX_MCAP_USD
        "MOONSHOT" -> com.lifecyclebot.v3.scoring.MoonshotTraderAI.MIN_MARKET_CAP_BOOTSTRAP_USD_7719 to com.lifecyclebot.v3.scoring.MoonshotTraderAI.MAX_MARKET_CAP_USD
        "TREASURY", "CASHGEN" -> com.lifecyclebot.engine.TreasuryScannerFeed.MIN_TREASURY_MCAP to Double.MAX_VALUE
        "EXPRESS" -> com.lifecyclebot.v3.scoring.ShitCoinExpress.MIN_MARKET_CAP_USD to com.lifecyclebot.v3.scoring.ShitCoinExpress.MAX_MARKET_CAP_USD
        "PROJECT_SNIPER" -> com.lifecyclebot.v3.scoring.ProjectSniperAI.MIN_MCAP_USD to com.lifecyclebot.v3.scoring.ProjectSniperAI.MAX_MCAP_USD
        "MANIPULATED" -> com.lifecyclebot.v3.scoring.ManipulatedTraderAI.MIN_MARKET_CAP_USD to com.lifecyclebot.v3.scoring.ManipulatedTraderAI.MAX_MARKET_CAP_USD
        "CYCLIC" -> CYCLIC_MCAP_MIN_8323 to CYCLIC_MCAP_MAX_8323
        "CORE" -> CORE_MCAP_MIN_8323 to CORE_MCAP_MAX_8323
        else -> 0.0 to Double.MAX_VALUE
    }

    fun mcapMin8323(lane: String): Double = mcapBand8323(lane).first
    fun mcapMax8323(lane: String): Double = mcapBand8323(lane).second

    /**
     * V5.0.8435 — the cap the lane's HUNTER may select up to: its trader's own maximum, cut at the playbook envelope where the
     * lane's own network labels proved the band above it dead (LanePlaybook7907.ENVELOPE_CUTS_8383 "MCAP_GT_5M"). 8434: the
     * TREASURY / CASHGEN hunters (trader max: none) selected MEW at ~$40M and the same lane's playbook then classified it
     * NO_MATCH "outside tradable envelope: MCAP_GT_5M" — the hunt spent the lane's slots on coins its own setups refuse.
     */
    fun huntMcapMax8435(lane: String): Double {
        val trader = mcapMax8323(lane)
        val cut = LanePlaybook7907.ENVELOPE_CUTS_8383[lane.trim().uppercase()]?.contains("MCAP_GT_5M") == true
        return if (cut) minOf(trader, LanePlaybook7907.ENV_MCAP_MAX_8383) else trader
    }
    /** V5.0.8389 — the trader's own constant liquidity floor (its mandate, not the fluid live floor). */
    fun liqMin8389(lane: String): Double = liqBand8323(lane).first

    /** The lane's base liquidity band (USD): the trader's own floor at cold start (the fluid floor is [liqFloorNow8323]). */
    private fun liqBand8323(lane: String): Pair<Double, Double> = when (lane.trim().uppercase()) {
        "SHITCOIN" -> com.lifecyclebot.v3.scoring.ShitCoinTraderAI.MIN_LIQUIDITY_USD_BOOTSTRAP to Double.MAX_VALUE
        "QUALITY" -> com.lifecyclebot.v3.scoring.QualityTraderAI.MIN_LIQUIDITY_USD to Double.MAX_VALUE
        "BLUECHIP" -> com.lifecyclebot.v3.scoring.BlueChipTraderAI.BC_LIQ_BOOTSTRAP to Double.MAX_VALUE
        "DIP_HUNTER" -> com.lifecyclebot.v3.scoring.DipHunterAI.MIN_LIQUIDITY_USD to Double.MAX_VALUE
        "MOONSHOT" -> com.lifecyclebot.v3.scoring.MoonshotTraderAI.MIN_LIQUIDITY_USD_BOOTSTRAP to Double.MAX_VALUE
        "TREASURY", "CASHGEN" -> com.lifecyclebot.engine.TreasuryScannerFeed.MIN_TREASURY_LIQUIDITY to Double.MAX_VALUE
        "EXPRESS" -> EXPRESS_MIN_LIQ_8323 to Double.MAX_VALUE
        "PROJECT_SNIPER" -> com.lifecyclebot.v3.scoring.ProjectSniperAI.MIN_LIQUIDITY_USD to com.lifecyclebot.v3.scoring.ProjectSniperAI.MAX_LIQUIDITY_USD
        "MANIPULATED" -> com.lifecyclebot.v3.scoring.ManipulatedTraderAI.MIN_LIQUIDITY_BOOTSTRAP to Double.MAX_VALUE
        "CYCLIC" -> CYCLIC_MIN_LIQ_8323 to Double.MAX_VALUE
        else -> 0.0 to Double.MAX_VALUE
    }

    /** The lane's liquidity floor right now: the trader's fluid floor where it has one, else its constant. */
    private fun liqFloorNow8323(lane: String): Double {
        val l = lane.trim().uppercase()
        val fluid = try {
            when (l) {
                "SHITCOIN" -> com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getFluidMinLiquidity()
                "BLUECHIP" -> com.lifecyclebot.v3.scoring.BlueChipTraderAI.getFluidMinLiquidity()
                "MANIPULATED" -> com.lifecyclebot.v3.scoring.ManipulatedTraderAI.getFluidMinLiquidity()
                "MOONSHOT" -> com.lifecyclebot.engine.market.HunterMandates8293.moonshotMinLiq8293()
                else -> Double.NaN
            }
        } catch (_: Throwable) { Double.NaN }
        return if (fluid.isFinite() && fluid >= 0.0) fluid else liqBand8323(l).first
    }

    /** The lane's birth-age band in minutes (0 / +inf = unbounded): the trader's own age rule. */
    fun ageBandMin8323(lane: String): Pair<Double, Double> = when (lane.trim().uppercase()) {
        "SHITCOIN" -> 0.0 to com.lifecyclebot.v3.scoring.ShitCoinTraderAI.MAX_TOKEN_AGE_HOURS_BOOTSTRAP * 60.0
        "PROJECT_SNIPER" -> com.lifecyclebot.v3.scoring.ProjectSniperAI.MIN_TOKEN_AGE_SECONDS / 60.0 to
            com.lifecyclebot.v3.scoring.ProjectSniperAI.SNIPER_WINDOW_SECS_8169 / 60.0
        "MANIPULATED" -> 0.0 to com.lifecyclebot.v3.scoring.ManipulatedTraderAI.MAX_AGE_MINUTES_BOOTSTRAP
        "QUALITY" -> com.lifecyclebot.v3.scoring.QualityTraderAI.MIN_AGE_MINUTES_BOOTSTRAP.toDouble() to Double.POSITIVE_INFINITY
        "DIP_HUNTER" -> com.lifecyclebot.v3.scoring.DipHunterAI.MIN_TOKEN_AGE_HOURS * 60.0 to Double.POSITIVE_INFINITY
        "CYCLIC" -> CYCLIC_MIN_AGE_HOURS_8323 * 60.0 to Double.POSITIVE_INFINITY
        else -> 0.0 to Double.POSITIVE_INFINITY
    }

    /** The lane's age ceiling right now in minutes (MANIPULATED is fluid: 12 -> 8 min). */
    private fun ageMaxNowMin8323(lane: String): Double {
        val l = lane.trim().uppercase()
        if (l == "MANIPULATED") {
            val f = try { com.lifecyclebot.v3.scoring.ManipulatedTraderAI.getFluidMaxAge() } catch (_: Throwable) { Double.NaN }
            if (f.isFinite() && f > 0.0) return f
        }
        return ageBandMin8323(l).second
    }

    /** Venues each lane trades on (pump.fun bonding curve, PumpSwap, Raydium, Meteora). */
    private fun venues8323(lane: String): Set<String> = when (lane.trim().uppercase()) {
        "PROJECT_SNIPER" -> setOf("CURVE", "PUMPSWAP")
        "SHITCOIN", "EXPRESS", "MANIPULATED" -> setOf("CURVE", "PUMPSWAP", "RAYDIUM", "METEORA")
        "MOONSHOT", "CORE" -> setOf("CURVE", "PUMPSWAP", "RAYDIUM", "METEORA")
        else -> setOf("PUMPSWAP", "RAYDIUM", "METEORA")
    }

    /** Hunter ideal freshness (hours) — LaneHunter7297.freshnessFit7797 reads it here; NaN = the lane has none. */
    fun idealMaxAgeHours8323(lane: String): Double = when (lane.trim().uppercase()) {
        "PROJECT_SNIPER" -> 0.05
        "EXPRESS", "MANIPULATED" -> 0.20
        "MOONSHOT" -> 1.0
        "SHITCOIN" -> 6.0
        else -> Double.NaN
    }

    /** The stages the stage sheet (TokenMetricStageRouter.LANE_STAGE_SHEET_7928) gives [lane]. */
    fun stagesOf8323(lane: String): Set<String> = try {
        val l = lane.trim().uppercase()
        com.lifecyclebot.engine.TokenMetricStageRouter.LANE_STAGE_SHEET_7928.filterValues { l in it }.keys.map { it.name }.toSet()
    } catch (_: Throwable) { emptySet() }

    private fun nativeLine8323(lane: String): String = when (lane) {
        "SHITCOIN" -> "liq fluid ${fmtUsd(liqBand8323(lane).first)}->mature, age<=${(ageBandMin8323(lane).second / 60.0).toInt()}h bootstrap"
        "MOONSHOT" -> "cap floor fluid ${fmtUsd(mcapMin8323(lane))}->\$10k, liq fluid, 1h up and not vertical"
        "EXPRESS" -> "1h continuation (HunterMandates8293.expressFits8293), 5m chase line"
        "PROJECT_SNIPER" -> "first ${ageBandMin8323(lane).second.toInt()} min, PRE_IGNITION"
        "MANIPULATED" -> "fluid age/liq, bundle or one-sided flow evidence"
        "QUALITY" -> "verified depth, age>=${ageBandMin8323(lane).first.toInt()}m"
        "BLUECHIP" -> "established large cap, fluid liq"
        "DIP_HUNTER" -> "pullback 15-55%/day, liq>=${(com.lifecyclebot.v3.scoring.DipHunterAI.MIN_LIQUIDITY_RATIO * 100).toInt()}% of cap, reclaim"
        "TREASURY", "CASHGEN" -> "role floor cap+liq, prefers ${com.lifecyclebot.engine.TreasuryScannerFeed.MIN_TREASURY_AGE_DAYS}d+"
        "CYCLIC" -> "sellable cycles, no one-way move"
        else -> "ensemble of lanes"
    }

    /** The lane's mandate (pure over the constants; the stage set read from the stage sheet). */
    fun mandateOf8323(laneRaw: String): Mandate8323 {
        val lane = key8323(laneRaw)
        val (mLo, mHi) = mcapBand8323(lane)
        val (lLo, lHi) = liqBand8323(lane)
        val (aLo, aHi) = ageBandMin8323(lane)
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        return Mandate8323(lane, stagesOf8323(lane), aLo, aHi, mLo, mHi, lLo, lHi, venues8323(lane), nativeLine8323(lane), runner)
    }

    // ── routing (scanner / merge queue) ──

    /** The lane's effective cap band right now: its own band, widened where its closes earned it (LaneHunter7297.fluidBand). */
    private fun mcapBandNow8323(lane: String): Pair<Double, Double> {
        val (lo, hi) = mcapBand8323(lane)
        val flo = try { com.lifecyclebot.engine.market.LaneHunter7297.floorFor(lane, lo) } catch (_: Throwable) { lo }
        val fhi = try { com.lifecyclebot.engine.market.LaneHunter7297.ceilingFor(lane, hi) } catch (_: Throwable) { hi }
        return flo to fhi
    }

    /**
     * Pure: does a coin at [mcapUsd] / [liqUsd] fit the mandate band [band] / liquidity floor [liqFloor]? Unknown (<= 0 / NaN)
     * values never refuse; the print may sit [ROUTE_MCAP_TOLERANCE_8323] below the floor (a moving coin) and the pool
     * [ROUTE_LIQ_TOLERANCE_8323] of the floor (estimated curve depth).
     */
    private fun routeFits8323(band: Pair<Double, Double>, liqFloor: Double, mcapUsd: Double, liqUsd: Double): Boolean {
        if (mcapUsd.isFinite() && mcapUsd > 0.0) {
            if (mcapUsd < band.first * ROUTE_MCAP_TOLERANCE_8323) return false
            if (band.second < Double.MAX_VALUE && mcapUsd > band.second / ROUTE_MCAP_TOLERANCE_8323) return false
        }
        if (liqUsd.isFinite() && liqUsd > 0.0 && liqFloor.isFinite() && liqUsd < liqFloor * ROUTE_LIQ_TOLERANCE_8323) return false
        return true
    }

    /** Does a coin fit [lane]'s mandate for routing? Lanes without a sheet (STANDARD, V3, INSIDER_SHARK…) always fit. */
    fun laneFits8323(lane: String, mcapUsd: Double, liqUsd: Double): Boolean {
        val l = lane.trim().uppercase()
        if (l !in LANES_8323) return true
        return routeFits8323(mcapBandNow8323(l), liqFloorNow8323(l), mcapUsd, liqUsd)
    }

    /**
     * The merge queue's lane offer for one sighting: [lanes] minus the sheet lanes whose mandate refuses the coin. When
     * every offered lane refuses, the sheet lanes that DO fit are offered instead; when none fit, the offer is unchanged
     * (the lanes' own scorers still decide). Counted per refused lane.
     */
    fun routeLanes8323(lanes: Set<String>, mcapUsd: Double, liqUsd: Double): Set<String> {
        if (lanes.isEmpty()) return lanes
        val kept = LinkedHashSet<String>()
        for (l in lanes) {
            if (laneFits8323(l, mcapUsd, liqUsd)) kept += l
            else try { PipelineHealthCollector.labelInc("SHEET_ROUTE_REFUSED_8323_${l.uppercase()}") } catch (_: Throwable) {}
        }
        if (kept.isNotEmpty()) return kept
        val fitting = LANES_8323.filter { laneFits8323(it, mcapUsd, liqUsd) && it != "CORE" }
        return if (fitting.isNotEmpty()) LinkedHashSet(fitting) else lanes
    }

    // ── hunting ──

    /** Pure: does a sweep row fit [lane]'s age band (known age only) and liquidity floor ([liqFloor])? */
    fun hunterFitsPure8323(ageBand: Pair<Double, Double>, ageMaxNow: Double, liqFloor: Double, ageHours: Double, liqUsd: Double): Boolean {
        if (liqFloor.isFinite() && liqFloor > 0.0 && !(liqUsd >= liqFloor)) return false
        if (ageHours.isFinite() && ageHours > 0.0) {
            val ageMin = ageHours * 60.0
            if (ageMaxNow.isFinite() && ageMin > ageMaxNow) return false
            // A young coin ages into a lane's minimum; only a minimum of 5 minutes or more is a mandate the hunt must wait for.
            if (ageBand.first >= 5.0 && ageMin < ageBand.first) return false
        }
        return true
    }

    /** LaneHunter7297: does the row fit [lane]'s mandate (the lane's own liquidity floor now and its age band)? */
    fun hunterFits8323(lane: String, ageHours: Double, liqUsd: Double): Boolean {
        val l = lane.trim().uppercase()
        if (l !in LANES_8323) return true
        return hunterFitsPure8323(ageBandMin8323(l), ageMaxNowMin8323(l), liqFloorNow8323(l), ageHours, liqUsd)
    }

    /** The scanner sources that feed [lane] (inverse of [SOURCE_LANES_8323]). */
    fun sourcesFor8323(lane: String): List<String> {
        val l = lane.trim().uppercase()
        return SOURCE_LANES_8323.filter { l in it.second }.flatMap { it.first }.distinct()
    }

    // ── timing and exits (read from where they live) ──

    private fun timingOf8323(lane: String): Timing8323 {
        val launch = lane in setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED")
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        val early = if (launch) "LaunchTape7921.earlyPass8165 (first buyers before the move) / priorPass (crowd forming)" else "n/a (not a launch desk)"
        val avoidBands = when {
            lane == "DIP_HUNTER" -> setOf("PEAK", "EXTENDED")
            runner -> setOf("PEAK")
            else -> setOf("PEAK", "EXTENDED")
        }
        val chase = "timingBand8094 ${avoidBands.joinToString("/")} = chase" + (if (lane in setOf("DIP_HUNTER", "QUALITY", "BLUECHIP", "TREASURY", "CASHGEN")) ", buys PULLBACK" else "")
        return Timing8323(early, launch, chase, avoidBands)
    }

    private fun exitOf8323(lane: String): Exit8323 {
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        val m = try { com.lifecyclebot.engine.truth.FieldManual7715.mandateFor(lane) } catch (_: Throwable) { null }
        val arm = if (runner) (try { com.lifecyclebot.engine.RunnerExitProfile7277.giveBackArmPct7791(lane) } catch (_: Throwable) { Double.NaN }) else Double.NaN
        val style = if (runner) "RUNNER: hold the tail, trail armed past +${if (arm.isFinite()) arm.toInt() else 0}%" else "SCALP: take the planned move"
        val stop8392 = try { com.lifecyclebot.engine.truth.FieldManual7715.laneStopPct8392(lane) } catch (_: Throwable) { m?.invalidationPct ?: Double.NaN }
        return Exit8323(runner, stop8392, m?.expectedGrossPct ?: Double.NaN, arm, style)   // V5.0.8392 — the stop the desk trades, cost-widened
    }

    // ── verdicts (pure) ──

    /** Pure: the setup's own live closes are losing (8291 / 8295: 2+ closes, negative mean). */
    private fun liveLosing8323(r: Record8323): Boolean =
        r.liveN >= LIVE_MIN_CLOSES_8323 && r.liveMeanPct.isFinite() && r.liveMeanPct < 0.0

    /**
     * Pure: the record cleared its prior with evidence (n, mean - SE above max(prior, 0) + cost) and the bank agrees.
     * V5.0.8325 §FAVOUR_IS_LOCAL — on this install's OWN executable record only (n >= [FAVOUR_MIN_N_8323], mean - SE above
     * max(prior, 0) + cost), and no live close of the setup losing ([liveAnyLosing8325]: 1+ close with a negative mean).
     * 5.0.8323 live favoured CROWD_FORMING n2 -10.1 (live1 -17.3) and RS_LEADER n30 -1.2 (live1 -3.9) on SHITCOIN through the
     * 8324 hive path and a tail-credited hysteresis; both lost live.
     */
    private fun favourEarned8323(r: Record8323, costPct: Double = 0.0): Boolean = localFavour8325(r, costPct)

    /** Pure (V5.0.8325): one live close (or more) of the setup and its mean below zero — never favoured. */
    fun liveAnyLosing8325(r: Record8323): Boolean = r.liveN >= 1.0 && r.liveMeanPct.isFinite() && r.liveMeanPct < 0.0

    /** Pure (V5.0.8325): the local executable record clears max(prior, 0) + cost by one SE on 20+ labels; live not losing. */
    fun localFavour8325(r: Record8323, costPct: Double = 0.0): Boolean {
        if (r.suspect || liveAnyLosing8325(r)) return false
        val (n, mean, se) = r.local8325.let { Triple(it[0], it[1], it[2]) }
        return n >= FAVOUR_MIN_N_8323 && mean.isFinite() && se.isFinite() && mean - se > maxOf(r.prior, 0.0) + costPct
    }

    /** Pure (V5.0.8329): a favoured setup KEEPS favour while its local record (20+) has a mean above max(prior, 0) + cost and live is not losing. */
    private fun keepsFavour8329(r: Record8323, costPct: Double): Boolean {
        if (r.suspect || liveAnyLosing8325(r)) return false
        val l = r.local8325
        return l[0] >= FAVOUR_MIN_N_8323 && l[1].isFinite() && l[1] - costPct > maxOf(r.prior, 0.0)
    }

    /** V5.0.8324 — network labels a hive prior needs, and local labels that must confirm it. */
    const val HIVE_FAVOUR_MIN_N_8324 = 100.0
    const val LOCAL_CONFIRM_N_8324 = 5.0
    /** V5.0.8324 — the merged record (local + the hive's lent labels) a FAVOUR verdict needs before admission may read it. */
    const val FAVOUR_ADMIT_MIN_N_8324 = 10

    /**
     * Pure (V5.0.8324): FAVOUR on a hive prior confirmed locally — the network record ([HIVE_FAVOUR_MIN_N_8324]+ labels) read
     * at its BOUNDED mean clears the prior plus cost by one SE, and this install's own labels ([LOCAL_CONFIRM_N_8324]+) are
     * above cost; never on a suspect local record or losing live closes. 5.0.8321: every lane favour=[] while the strong setup
     * records were hive-only (LAUNCH_LADDER_PROVEN local=0 hive=10/824): the 20-label bar counts the hive at 10 labels at most.
     */
    // V5.0.8325 — no longer a FAVOUR path (judge8323 does not read it): a hive record may rank and prior, never favour.
    fun hiveFavour8324(r: Record8323, costPct: Double = 0.0): Boolean =
        !r.suspect && !liveLosing8323(r) &&
            r.hiveN >= HIVE_FAVOUR_MIN_N_8324 && r.hiveMeanPct.isFinite() && r.hiveSePct.isFinite() &&
            r.hiveMeanPct - r.hiveSePct > maxOf(r.prior, 0.0) + costPct &&
            r.localN >= LOCAL_CONFIRM_N_8324 && r.localMeanPct.isFinite() && r.localMeanPct > costPct

    /**
     * Pure: a clear loss — mean + 2 SE below cost on 12+, or mean + SE below cost on 30+, or the live closes losing.
     * V5.0.8324 §TAIL_AWARE_LOSS — on a runner lane ([laneRate8324] / [premium8324] from the lane's tail pond,
     * CortexTailEv8324) the label mean is first credited the runners its sample could plausibly still be missing
     * ([CortexTailEv8324.missedRunnerPts8324]): on a 5%-runner pond 12 labels hold no runner 54% of the time, so a setup was
     * AVOIDED on a coin flip before its first runner arrived (SHEET_AVOID_8323 refused the coins that run). The live-close
     * rule (8291 / 8295) is unchanged.
     */
    private fun clearLoss8323(r: Record8323, costPct: Double = 0.0, laneRate8324: Double = Double.NaN, premium8324: Double = Double.NaN): Boolean {
        if (liveLosing8323(r)) return true
        if (!r.meanPct.isFinite() || !r.sePct.isFinite()) return false
        // V5.0.8325 — the record losing on its own labels is AVOID again (the 8324 missed-runner credit is withdrawn from the
        // verdict; an avoided setup still re-probes on the lane's exploration slot, reprobe8323). [laneRate8324] / [premium8324]
        // are accepted for call compatibility and ignored.
        val net = r.meanPct - costPct
        if (r.n >= AVOID_MIN_N_8323 && net + 2.0 * r.sePct < 0.0) return true
        return r.n >= PROVEN_N_8323 && net + r.sePct < 0.0
    }

    /**
     * Pure: the verdict on [r] given its previous verdict [prev] (hysteresis, so a setup does not flap):
     *   AVOID   on a clear loss; an avoided setup stays avoided until its measured mean is back above cost (and the bank agrees);
     *   FAVOUR  when earned; a favoured setup stays favoured while its mean stays above cost, the record is not suspect and
     *           the live closes are not losing;
     *   NEUTRAL otherwise (the lane's existing rules decide).
     */
    fun judge8323(r: Record8323, prev: Verdict8323, costPct: Double = 0.0,
                  laneRate8324: Double = Double.NaN, premium8324: Double = Double.NaN): Verdict8323 {
        if (clearLoss8323(r, costPct, laneRate8324, premium8324)) return Verdict8323.AVOID
        // V5.0.8325 — the hysteresis exit reads the record's own net (no missed-runner credit).
        val net = if (r.meanPct.isFinite()) r.meanPct - costPct else Double.NaN
        if (prev == Verdict8323.AVOID && !(r.n >= AVOID_MIN_N_8323 && net > 0.0)) return Verdict8323.AVOID
        // V5.0.8325 §FAVOUR_IS_LOCAL — favour earned and kept on the local record only (the 8324 hive path is withdrawn).
        if (favourEarned8323(r, costPct)) return Verdict8323.FAVOUR
        // V5.0.8329 — the 8323 no-flap hold, on the local record only and only while the mean still beats prior + cost.
        if (prev == Verdict8323.FAVOUR && keepsFavour8329(r, costPct)) return Verdict8323.FAVOUR
        return Verdict8323.NEUTRAL
    }

    /** Pure: the tail-aware expectancy — the shrunk MEAN (runners included) net of cost; never the median. */
    fun expectancy8323(r: Record8323, costPct: Double = 0.0): Double =
        (if (r.expectedPct.isFinite()) r.expectedPct else if (r.n > 0.0 && r.meanPct.isFinite()) r.meanPct else r.prior) - costPct

    private fun why8323(r: Record8323, v: Verdict8323, costPct: Double): String = when (v) {
        Verdict8323.AVOID -> if (liveLosing8323(r)) "live n${r.liveN.toInt()} ${fmt(r.liveMeanPct)}" else "loss n${r.n.toInt()} ${fmt(r.meanPct)}±${fmt(r.sePct)}"
        Verdict8323.FAVOUR -> "n${r.n.toInt()} ${fmt(r.meanPct)}±${fmt(r.sePct)} > prior ${fmt(maxOf(r.prior, 0.0) + costPct)}"
        Verdict8323.NEUTRAL -> if (r.n < 1.0) "prior ${fmt(r.prior)}" else "n${r.n.toInt()} ${fmt(r.meanPct)}"
    }

    /** Pure: one lane's ranked rows from its records and previous verdicts. */
    fun rows8323(records: List<Record8323>, prev: Map<String, Verdict8323>, costPct: Double = 0.0,
                 laneRate8324: Double = Double.NaN, premium8324: Double = Double.NaN): List<Row8323> =
        records.map { r ->
            val v = judge8323(r, prev[r.setup] ?: Verdict8323.NEUTRAL, costPct, laneRate8324, premium8324)
            Row8323(r, v, expectancy8323(r, costPct), why8323(r, v, costPct))
        }.sortedWith(compareBy<Row8323>({ it.verdict.ordinal }, { if (it.expectancyPct.isFinite()) -it.expectancyPct else Double.MAX_VALUE }, { -it.record.n }))

    // ── state ──

    private val verdicts = ConcurrentHashMap<String, Verdict8323>()       // "LANE|SETUP"
    private val sheets = ConcurrentHashMap<String, Sheet8323>()
    private val rotations = ConcurrentHashMap<String, AtomicLong>()
    private val lastRotation = ConcurrentHashMap<String, String>()
    private val runnerAcc = ConcurrentHashMap<String, DoubleArray>()      // "LANE|SETUP" -> [runners, sum of runner labels]
    private val reprobes = ConcurrentHashMap<String, Grant8323>()         // "LANE|SETUP" -> last grant
    private val building = AtomicBoolean(false)
    @Volatile private var lastBuildMs = 0L
    @Volatile private var loaded = false
    private val reprobeCount = AtomicLong(0)
    private const val PERSIST_KEY_8323 = "LANE_CHEAT_SHEET_8323"

    data class Grant8323(val mint: String, val atMs: Long)

    /** The current verdict of ([lane], [setup]); NEUTRAL when the sheet has none. Lock-free, never builds. */
    fun verdictOf8323(lane: String, setup: String?): Verdict8323 =
        if (setup.isNullOrBlank()) Verdict8323.NEUTRAL else verdicts["${key8323(lane)}|$setup"] ?: Verdict8323.NEUTRAL

    fun favourIds8323(lane: String): List<String> = sheets[key8323(lane)]?.favour.orEmpty()
    fun avoidIds8323(lane: String): List<String> = sheets[key8323(lane)]?.avoid.orEmpty()
    fun sheetOf8323(lane: String): Sheet8323? = sheets[key8323(lane)]

    /** LanePlaybook7907.onLabel: a graded label of ([lane], [setup]); runners feed the runner average shown on the sheet. */
    fun noteLabel8323(lane: String, setup: String, y: Double, runner: Boolean) {
        if (!runner || !y.isFinite()) return
        val a = runnerAcc.computeIfAbsent("${lane.trim().uppercase()}|$setup") { DoubleArray(2) }
        synchronized(a) { a[0] += 1.0; a[1] += y }
    }

    private fun runnerAvg8323(lane: String, setup: String): Double =
        runnerAcc["$lane|$setup"]?.let { a -> synchronized(a) { if (a[0] > 0.0) a[1] / a[0] else Double.NaN } } ?: Double.NaN

    /**
     * Pure: may an avoided setup re-probe for [mint] now? The same coin keeps a fresh grant ([REPROBE_HOLD_MS_8323]); a new
     * grant needs the lane's exploration slot free and [REPROBE_GAP_MS_8323] since the last one.
     */
    fun reprobeDecision8323(last: Grant8323?, mint: String, nowMs: Long, slotFree: Boolean): Boolean {
        if (last != null && last.mint == mint && nowMs - last.atMs in 0L..REPROBE_HOLD_MS_8323) return true
        if (!slotFree) return false
        return last == null || nowMs - last.atMs >= REPROBE_GAP_MS_8323 || nowMs < last.atMs
    }

    /**
     * Runtime: an avoided ([lane], [setup]) re-probes for [mint] on the lane's exploration slot (paper: always free; live:
     * MinPromotion8188.explorationSlotFree8316). [slotFree] overrides the slot read (tests).
     */
    fun reprobe8323(lane: String, setup: String, mint: String, nowMs: Long = System.currentTimeMillis(), slotFree: Boolean? = null): Boolean {
        val k = "${key8323(lane)}|$setup"
        val last = reprobes[k]
        if (last != null && last.mint == mint && nowMs - last.atMs in 0L..REPROBE_HOLD_MS_8323) return true
        if (last != null && nowMs - last.atMs in 0L until REPROBE_GAP_MS_8323) return false  // the gap first: no slot read
        val free = slotFree ?: try {
            com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() ||
                com.lifecyclebot.engine.truth.MinPromotion8188.explorationSlotFree8316(key8323(lane))
        } catch (_: Throwable) { false }
        if (!reprobeDecision8323(last, mint, nowMs, free)) return false
        reprobes[k] = Grant8323(mint, nowMs)
        reprobeCount.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SHEET_AVOID_REPROBE_8323_${key8323(lane)}") } catch (_: Throwable) {}
        return true
    }

    /** The verdict a decision for [mint] acts on: AVOID reads NEUTRAL while the setup holds its re-probe grant. */
    fun effectiveVerdict8323(lane: String, setup: String?, mint: String, nowMs: Long = System.currentTimeMillis()): Verdict8323 {
        val v = verdictOf8323(lane, setup)
        if (v != Verdict8323.AVOID || setup == null || mint.isBlank()) return v
        return if (reprobe8323(lane, setup, mint, nowMs)) Verdict8323.NEUTRAL else Verdict8323.AVOID
    }

    /**
     * Apply [records] to [lane]'s sheet: verdicts judged against the stored previous ones (hysteresis), rotations counted,
     * the sheet published. Lock-free for readers.
     */
    fun applyRecords8323(laneRaw: String, records: List<Record8323>, nowMs: Long = System.currentTimeMillis(), costPct: Double = 0.0,
                         pond8324: CortexTailEv8324.Pond8324? = null): Sheet8323 {
        val lane = key8323(laneRaw)
        val prev = records.associate { it.setup to (verdicts["$lane|${it.setup}"] ?: Verdict8323.NEUTRAL) }
        val rows = rows8323(records, prev, costPct, pond8324?.pBook ?: Double.NaN, pond8324?.premium ?: Double.NaN)
        var changed = false
        for (row in rows) {
            val k = "$lane|${row.record.setup}"
            val before = verdicts[k] ?: Verdict8323.NEUTRAL
            if (row.verdict != before) {
                rotations.computeIfAbsent(lane) { AtomicLong(0) }.incrementAndGet()
                lastRotation[lane] = "${row.record.setup} ${before.name}->${row.verdict.name}"
                changed = true
                try { PipelineHealthCollector.labelInc("SHEET_ROTATED_8323_${lane}_${row.verdict.name}") } catch (_: Throwable) {}
            }
            if (row.verdict == Verdict8323.NEUTRAL) verdicts.remove(k) else verdicts[k] = row.verdict
        }
        val sheet = Sheet8323(lane, mandateOf8323(lane), timingOf8323(lane), exitOf8323(lane),
            Hunter8323(idealMaxAgeHours8323(lane), sourcesFor8323(lane)), rows,
            rotations[lane]?.get() ?: 0L, lastRotation[lane].orEmpty(), nowMs)
        sheets[lane] = sheet
        lastBuildMs = nowMs
        if (changed) persist8323()
        return sheet
    }

    private fun sheetRunner8324(lane: String): Boolean =
        try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }

    /** The lane's records from the playbook (labels) with the runner average and, on a LIVE account only, the live closes. */
    private fun recordsFor8323(lane: String, live: Boolean): List<Record8323> =
        LanePlaybook7907.sheetRecords8323(lane).map { r ->
            val rr = if (live) (try { Cortex7885.setupRealised8187(lane, r.setup) } catch (_: Throwable) { null }) else null
            r.copy(runnerAvgPct = runnerAvg8323(lane, r.setup),
                liveN = rr?.getOrNull(0)?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                liveMeanPct = rr?.getOrNull(1) ?: Double.NaN)
        }

    /**
     * Rebuild every lane's sheet when due ([REFRESH_MS_8323]). Never blocks: one builder at a time, readers see the
     * previous sheet. Never call while holding LanePlaybook7907's lock from another object's lock.
     */
    fun refresh8323(nowMs: Long = System.currentTimeMillis(), force: Boolean = false) {
        if (!force && nowMs - lastBuildMs in 0L until REFRESH_MS_8323) return
        if (!building.compareAndSet(false, true)) return
        try {
            lastBuildMs = nowMs
            load8323()
            val live = try { !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
            for (lane in LANES_8323) {
                // V5.0.8324 — a runner lane's loss test reads its tail pond (the runners a thin record may still be missing).
                val pond8324 = if (sheetRunner8324(lane)) (try { Cortex7885.tailPond8324(lane) } catch (_: Throwable) { null }) else null
                try { applyRecords8323(lane, recordsFor8323(lane, live), nowMs, pond8324 = pond8324) } catch (_: Throwable) {}
            }
        } finally { building.set(false) }
    }

    // ── consumers (pure helpers + reads) ──

    /**
     * Pure: the playbook's best-setup choice with the sheet applied — among [candidates] (setups already fired in the lane),
     * AVOID ones are dropped (unless nothing else fired) and the FAVOUR ones go first; [choose] (LaneEvidence8077.choose)
     * ranks within the group. Null when [candidates] is empty.
     */
    fun chooseWith8323(candidates: List<LaneEvidence8077.Setup>, verdictOf: (String) -> Verdict8323,
                       choose: (List<LaneEvidence8077.Setup>) -> LaneEvidence8077.Setup?): LaneEvidence8077.Setup? {
        if (candidates.isEmpty()) return null
        val notAvoided = candidates.filter { verdictOf(it.id) != Verdict8323.AVOID }
        val favour = notAvoided.filter { verdictOf(it.id) == Verdict8323.FAVOUR }
        return choose(favour) ?: choose(notAvoided) ?: choose(candidates)
    }

    /** LanePlaybook7907 best-setup selection: the sheet-applied choice for [lane]. Lock-free. */
    fun choose8323(lane: String, candidates: List<LaneEvidence8077.Setup>): LaneEvidence8077.Setup? =
        chooseWith8323(candidates, { id -> verdictOf8323(lane, id) }) { LaneEvidence8077.choose(it) }

    private const val FAVOUR_BASIS_8323 = "SHEET_FAVOUR_8323"

    /**
     * Pure: the per-token strategy pick with the sheet applied. [pick] is StrategyRank8153's; [options] the fired setups'
     * records; [verdictOf] the sheet verdict per setup (re-probes already folded in):
     *   - the best evidenced (SUPPORTED / VALID) option that is not AVOID, FAVOUR first;
     *   - else a FAVOUR option (basis [FAVOUR_BASIS_8323]);
     *   - else the pick, unless it is AVOID: then the best non-avoided non-REJECTED option, if any.
     */
    fun pickWith8323(pick: StrategyRank8153.Pick8153?, defaultId: String?,
                     options: List<StrategyRank8153.Option8153>,
                     verdictOf: (String) -> Verdict8323): StrategyRank8153.Pick8153? {
        val pickId: String? = pick?.id
        val pickAvoided = pickId != null && verdictOf(pickId) == Verdict8323.AVOID
        val ranked = StrategyRank8153.rank8153(options, Double.NaN)
        if (ranked.isEmpty()) return if (pickAvoided) null else pick
        val usable = ranked.filter { verdictOf(it.option.id) != Verdict8323.AVOID }
        val evidenced = usable.filter { StrategyRank8153.evidenced8153(it.tier) }
        val bestEvidenced = evidenced.firstOrNull { verdictOf(it.option.id) == Verdict8323.FAVOUR } ?: evidenced.firstOrNull()
        if (bestEvidenced != null) {
            val fav = verdictOf(bestEvidenced.option.id) == Verdict8323.FAVOUR
            return StrategyRank8153.Pick8153(bestEvidenced.option.id, bestEvidenced.tier,
                (if (fav) "$FAVOUR_BASIS_8323|" else "") + "${bestEvidenced.tier.name}:${bestEvidenced.option.compact}",
                bestEvidenced.option.id != defaultId, pick?.rejected.orEmpty())
        }
        val favour = usable.firstOrNull { verdictOf(it.option.id) == Verdict8323.FAVOUR && it.tier != StrategyRank8153.Tier8153.REJECTED }
        if (favour != null) return StrategyRank8153.Pick8153(favour.option.id, favour.tier, "$FAVOUR_BASIS_8323|${favour.option.compact}",
            favour.option.id != defaultId, pick?.rejected.orEmpty())
        if (pick != null && pickAvoided) {
            val alt = usable.firstOrNull { it.tier != StrategyRank8153.Tier8153.REJECTED }
                ?: return StrategyRank8153.Pick8153(pickId, pick.tier, "SHEET_AVOID_8323|${pick.basis}", false, pick.rejected)
            return StrategyRank8153.Pick8153(alt.option.id, alt.tier, "SHEET_AVOID_ROTATED_8323|${alt.option.compact}", alt.option.id != defaultId, pick.rejected)
        }
        return pick
    }

    /** Is [basis] a sheet-favour pick (it may carry the token's identity like an evidenced tier)? */
    fun favourBasis8323(basis: String?): Boolean = basis?.startsWith(FAVOUR_BASIS_8323) == true

    /**
     * CandidateAdmission8136: the sheet verdict per fired setup of [mint] in [lane] (NEUTRAL omitted). Only the [selected]
     * strategy may hold a re-probe grant (an avoided alternative is never the coin's probe).
     */
    fun admissionVerdicts8323(lane: String, setups: Collection<String>, selected: String?, mint: String, nowMs: Long = System.currentTimeMillis()): Map<String, String> {
        refresh8323(nowMs)
        val out = HashMap<String, String>()
        for (s in (setups + listOfNotNull(selected)).distinct()) {
            val v = if (s == selected) effectiveVerdict8323(lane, s, mint, nowMs) else verdictOf8323(lane, s)
            if (v != Verdict8323.NEUTRAL) out[s] = v.name
        }
        return out
    }

    /**
     * TacticSwitcher.rotate: the next tactic ordinal for [lane] from [current] of [count] — the first tactic in rotation
     * order that a FAVOUR setup trades as; else the first that is not the shape of AVOID setups only; else current + 1.
     */
    fun rotationTarget8323(lane: String, current: Int, count: Int, tacticOf: (String) -> Int): Int {
        val next = (current + 1) % count.coerceAtLeast(1)
        val sheet = sheets[key8323(lane)] ?: return next
        val fav = sheet.favour.map(tacticOf).toSet()
        val avoidOnly = sheet.avoid.map(tacticOf).toSet() - (sheet.favour + sheet.neutral).map(tacticOf).toSet()
        val order = (1 until count).map { (current + it) % count }
        return order.firstOrNull { it in fav } ?: order.firstOrNull { it !in avoidOnly } ?: next
    }

    /** Cortex voter SHEET_FAVOUR_<lane>: +1 a favoured setup fired, -1 the coin's setup is avoided, 0 neutral; null = unclassified. */
    fun vote8323(ts: TokenState, lane: String, @Suppress("UNUSED_PARAMETER") nowMs: Long): Double? {
        val (matched, chosen) = LanePlaybook7907.peekMatched8323(ts.mint, lane) ?: return null
        if (matched.any { verdictOf8323(lane, it) == Verdict8323.FAVOUR }) return 1.0
        if (chosen != null && verdictOf8323(lane, chosen) == Verdict8323.AVOID) return -1.0
        return 0.0
    }

    // ── persistence (verdicts only: hysteresis survives a restart; the records live in their own books) ──

    private fun load8323() {
        if (loaded) return
        if (!(try { com.lifecyclebot.engine.LearningPersistence.ready() } catch (_: Throwable) { false })) return
        loaded = true
        try {
            val raw = com.lifecyclebot.engine.LearningPersistence.load(PERSIST_KEY_8323) ?: return
            for (part in raw.split(';')) {
                val k = part.substringBefore('=', ""); val v = part.substringAfter('=', "")
                val verdict = Verdict8323.values().firstOrNull { it.name == v } ?: continue
                if (k.contains('|') && verdict != Verdict8323.NEUTRAL && !verdicts.containsKey(k)) verdicts[k] = verdict
            }
        } catch (_: Throwable) {}
    }

    private fun persist8323() {
        if (!loaded) return
        try {
            com.lifecyclebot.engine.LearningPersistence.save(PERSIST_KEY_8323,
                verdicts.entries.joinToString(";") { "${it.key}=${it.value.name}" })
        } catch (_: Throwable) {}
    }

    // ── report ──

    private fun fmt(v: Double): String = if (v.isFinite()) "%+.1f".format(v) else "-"
    private fun fmtUsd(v: Double): String = when {
        !v.isFinite() || v >= Double.MAX_VALUE / 10 -> "inf"
        v >= 1_000_000.0 -> "${"%.1f".format(v / 1_000_000.0)}M"
        v >= 1_000.0 -> "${(v / 1_000.0).toInt()}k"
        else -> v.toInt().toString()
    }
    private fun fmtAge(m: Double): String = when {
        !m.isFinite() -> "inf"
        m >= 120.0 -> "${(m / 60.0).toInt()}h"
        else -> "${"%.1f".format(m).removeSuffix(".0")}m"
    }

    private const val LINE_CHARS_8323 = 420

    /** One compact line per lane: cheatSheet8323[<lane>: mandate… favour=[…] avoid=[…] rotated=…]. */
    fun statusLines8323(nowMs: Long = System.currentTimeMillis()): String {
        refresh8323(nowMs)
        return LANES_8323.joinToString("\n") { lane ->
            val s = sheets[lane]
            val m = s?.mandate ?: mandateOf8323(lane)
            fun setupList(ids: List<String>) = ids.take(4).joinToString(",") { id ->
                val r = s?.rows?.firstOrNull { it.record.setup == id }?.record
                if (r == null || r.n < 1.0) id else "$id n${r.n.toInt()} ${fmt(r.meanPct)}" +
                    (if (r.runnerRate > 0.0) " run${(r.runnerRate * 100).toInt()}%${if (r.runnerAvgPct.isFinite()) "@${fmt(r.runnerAvgPct)}" else ""}" else "") +
                    (if (r.liveN > 0.0) " live${r.liveN.toInt()}/${fmt(r.liveMeanPct)}" else "")
            } + (if (ids.size > 4) ",+${ids.size - 4}" else "")
            val ex = s?.exit
            ("cheatSheet8323[$lane: cap ${fmtUsd(m.mcapMin)}-${fmtUsd(m.mcapMax)} liq>=${fmtUsd(liqFloorNow8323(lane))}" +
                " age ${fmtAge(m.ageMinMin)}-${fmtAge(ageMaxNowMin8323(lane))} stages=${m.stages.joinToString("/").ifBlank { "any" }}" +
                " ${if (m.runner) "runner" else "scalp"}${if (ex != null && ex.stopPct.isFinite()) " stop-${ex.stopPct.toInt()}%" else ""}" +
                " favour=[${setupList(s?.favour.orEmpty())}] avoid=[${setupList(s?.avoid.orEmpty())}]" +
                " rotated=${s?.rotated ?: 0}${s?.lastRotation?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}]").take(LINE_CHARS_8323)
        } + "\n      cheatSheet8323 rules: favour LOCAL n>=${FAVOUR_MIN_N_8323.toInt()} mean-SE>prior+cost (no hive favour, no live close losing; 8325) · " +
            "avoid mean+2SE<0 (n>=${AVOID_MIN_N_8323.toInt()}) / mean+SE<0 (n>=${PROVEN_N_8323.toInt()}) / live closes losing · hysteresis · reprobes=${reprobeCount.get()}"
    }
}
