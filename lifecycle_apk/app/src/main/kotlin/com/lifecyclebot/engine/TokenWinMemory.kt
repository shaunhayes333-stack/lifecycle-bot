package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * TokenWinMemory
 *
 * Decisive-trade classification:
 * - WIN  = pnlPct >= 0.5
 * - LOSS = pnlPct <= -2.0
 * - Anything between is SCRATCH and is ignored for memory learning
 */
object TokenWinMemory {

    private const val PREFS_NAME = "token_win_memory"
    private const val KEY_WINNERS = "winners"
    private const val KEY_PATTERNS = "patterns"
    private const val KEY_CREATORS = "creators"
    private const val KEY_TOKEN_STATS = "token_stats"

    private const val MAX_WINNERS = 5000
    private const val MAX_PATTERNS = 1000

    private const val WIN_THRESHOLD_PCT = 0.5
    private const val LOSS_THRESHOLD_PCT = -2.0

    // V5.0.6011 — PHANTOM PNL CEILING (Issue: TokenWinMemory Phantom Purge).
    // Previous ceiling used LearningPnlSanitizer.MAX_TRAINABLE_PNL_PCT (100_000%),
    // which technically allows real moonshots but also stored fabricated
    // >50,000% phantom rows that were poisoning pattern recognition. In practice
    // no legitimate exit on this scanner has cleared 50k% — those are basis-switch
    // / phantom bonding-curve reads. Hard ceiling at 50,000% for stored winners.
    private const val PHANTOM_PNL_PCT_HARD_CEILING_6011 = 50_000.0

    private var ctx: Context? = null

    // ═══════════════════════════════════════════════════════════════════════
    // WINNING TOKEN RECORDS
    // ═══════════════════════════════════════════════════════════════════════

    data class WinningToken(
        val mint: String,
        val symbol: String,
        val name: String,
        val pnlPercent: Double,
        val peakPnl: Double,
        val entryMcap: Double,
        val exitMcap: Double,
        val entryLiquidity: Double,
        val holdTimeMinutes: Int,
        val buyPercent: Double,
        val timestamp: Long,
        val source: String,
        val phase: String,
        var timesTraded: Int = 1,
        var totalPnl: Double = pnlPercent,
        // V5.0.6123 — FULL TRADE CONTEXT (operator: "not just a theme scorer")
        // Buy details
        val entryPrice: Double = 0.0,
        val exitPrice: Double = 0.0,
        val costSol: Double = 0.0,
        val pnlSol: Double = 0.0,
        val buyRoute: String = "",           // Jupiter, PumpPortal, Raydium, etc.
        val sellRoute: String = "",          // same
        val launchPlatform: String = "",     // PumpFun, Raydium, Bonk, etc.
        val entryScore: Double = 0.0,
        val entryConfidence: Double = 0.0,
        val lane: String = "",               // MOONSHOT, QUALITY, SHITCOIN, etc.
        val trader: String = "",             // traderSource
        val setupQuality: String = "",       // HIGH, MEDIUM, LOW
        val marketRegime: String = "",       // STRONG BULL, DUMP, NEUTRAL, etc.
        // Hold characteristics
        val maxDrawdownPct: Double = 0.0,
        val timeToPeakMinutes: Double = 0.0,
        val exitReason: String = "",
        val volatility: Double = 0.0,
        // Token launch context
        val holderCount: Int = 0,
        val holderGrowthRate: Double = 0.0,
        val topHolderPct: Double = 0.0,
        val devWalletPct: Double = 0.0,
        val bondingCurveProgress: Double = 0.0,
        val rugcheckScore: Double = 0.0,
        val emaFanState: String = "",
        val tokenAgeMinutes: Double = 0.0,
        val volumeUsd: Double = 0.0,
        // Creator
        val creatorAddress: String = "",
    )

    data class PatternStats(
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnl: Double = 0.0,
        var avgWinPnl: Double = 0.0,
    ) {
        val winRate: Double
            get() = if (wins + losses > 0) wins.toDouble() / (wins + losses).toDouble() else 0.5

        val isReliable: Boolean
            get() = wins + losses >= 5
    }

    data class TokenStats(
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnl: Double = 0.0,
        var bestPnl: Double = Double.NEGATIVE_INFINITY,
        var lastPnl: Double = 0.0,
        var lastSeen: Long = 0L,
    ) {
        val decisiveTrades: Int
            get() = wins + losses

        val winRate: Double
            get() = if (decisiveTrades > 0) wins.toDouble() / decisiveTrades.toDouble() else 0.5
    }

    // V5.0.3825 — public aggregate for hive sync. This exposes only pattern
    // aggregates, never wallet/user data or raw token lists.
    data class ExportedPatternAggregate(
        val type: String,
        val value: String,
        val wins: Int,
        val losses: Int,
        val totalPnl: Double,
        val avgWinPnl: Double,
    ) {
        val totalTrades: Int get() = wins + losses
        val winRate: Double get() = if (totalTrades > 0) wins.toDouble() / totalTrades.toDouble() else 0.5
    }

    // Mint -> WinningToken
    private val winningTokens = ConcurrentHashMap<String, WinningToken>()

    // Pattern type -> pattern value -> stats
    private val patterns = ConcurrentHashMap<String, ConcurrentHashMap<String, PatternStats>>()

    // Creator address -> stats
    private val creatorStats = ConcurrentHashMap<String, PatternStats>()

    // Mint -> decisive trade history
    private val tokenStats = ConcurrentHashMap<String, TokenStats>()

    private fun isShadowOrSimulatedSource(source: String?): Boolean {
        val s = source?.trim()?.uppercase() ?: return false
        return s.contains("SHADOW") || s.contains("SIMULATED") || s.contains("PAPER_ONLY") || s.contains("BACKTEST")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════

    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info(
            "TokenWinMemory",
            "📊 Loaded: ${winningTokens.size} winners, ${tokenStats.size} token stats, ${countPatterns()} patterns"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECORD A TRADE OUTCOME
    // ═══════════════════════════════════════════════════════════════════════

    fun recordTradeOutcome(
        mint: String,
        symbol: String,
        name: String,
        pnlPercent: Double,
        peakPnl: Double,
        entryMcap: Double,
        exitMcap: Double,
        entryLiquidity: Double,
        holdTimeMinutes: Int,
        buyPercent: Double,
        source: String,
        phase: String,
        creatorAddress: String? = null,
        // V5.0.6123 — FULL TRADE CONTEXT
        entryPrice: Double = 0.0,
        exitPrice: Double = 0.0,
        costSol: Double = 0.0,
        pnlSol: Double = 0.0,
        buyRoute: String = "",
        sellRoute: String = "",
        launchPlatform: String = "",
        entryScore: Double = 0.0,
        entryConfidence: Double = 0.0,
        lane: String = "",
        trader: String = "",
        setupQuality: String = "",
        marketRegime: String = "",
        maxDrawdownPct: Double = 0.0,
        timeToPeakMinutes: Double = 0.0,
        exitReason: String = "",
        volatility: Double = 0.0,
        holderCount: Int = 0,
        holderGrowthRate: Double = 0.0,
        topHolderPct: Double = 0.0,
        devWalletPct: Double = 0.0,
        bondingCurveProgress: Double = 0.0,
        rugcheckScore: Double = 0.0,
        emaFanState: String = "",
        tokenAgeMinutes: Double = 0.0,
        volumeUsd: Double = 0.0,
    ) {
        if (isShadowOrSimulatedSource(source)) {
            try { SourceChokeDiagnostics4584.learningQuarantined("TOKEN_WIN_MEMORY_SHADOW_SOURCE", "mint=${mint.take(8)} symbol=$symbol source=${source.take(60)} phase=${phase.take(40)}") } catch (_: Throwable) {}
            ErrorLogger.warn("TokenWinMemory", "TOKEN_WIN_MEMORY_SHADOW_SOURCE_QUARANTINED mint=${mint.take(8)} symbol=$symbol source=$source phase=$phase")
            return
        }
        val verdict = LearningPnlSanitizer.inspectPct(
            pnlPct = pnlPercent,
            context = "TokenWinMemory.recordTradeOutcome/${source.take(40)}/${phase.take(40)}",
        )
        if (!verdict.ok) {
            ErrorLogger.warn("TokenWinMemory", "LEARNING_PNL_QUARANTINED mint=${mint.take(8)} symbol=$symbol pnlPct=$pnlPercent reason=${verdict.reason}")
            return
        }
        val trainablePnl = verdict.pnlPct
        val isWin = isWinPnl(trainablePnl)
        val isLoss = isLossPnl(trainablePnl)
        val isScratch = !isWin && !isLoss

        if (isScratch) {
            ErrorLogger.debug(
                "TokenWinMemory",
                "$symbol: Scratch trade (${trainablePnl.toInt()}%) - not recorded"
            )
            return
        }

        val now = System.currentTimeMillis()

        // ── Track exact mint stats (wins and losses) ──────────────────────
        val mintStats = tokenStats.getOrPut(mint) { TokenStats() }
        if (isWin) {
            mintStats.wins++
        } else {
            mintStats.losses++
        }
        mintStats.totalPnl += trainablePnl
        mintStats.bestPnl = maxOf(mintStats.bestPnl, trainablePnl)
        mintStats.lastPnl = trainablePnl
        mintStats.lastSeen = now

        // ── Record / update winning token memory ──────────────────────────
        val existingWinner = winningTokens[mint]

        if (isWin) {
            if (existingWinner != null) {
                existingWinner.timesTraded++
                existingWinner.totalPnl += trainablePnl
                ErrorLogger.info(
                    "TokenWinMemory",
                    "🏆 REPEAT WINNER: $symbol | +${trainablePnl.toInt()}% | total: +${existingWinner.totalPnl.toInt()}% over ${existingWinner.timesTraded} trades"
                )
            } else {
                winningTokens[mint] = WinningToken(
                    mint = mint,
                    symbol = symbol,
                    name = name,
                    pnlPercent = trainablePnl,
                    peakPnl = peakPnl,
                    entryMcap = entryMcap,
                    exitMcap = exitMcap,
                    entryLiquidity = entryLiquidity,
                    holdTimeMinutes = holdTimeMinutes,
                    buyPercent = buyPercent,
                    timestamp = now,
                    source = source,
                    phase = phase,
                    // V5.0.6123 — full trade context
                    entryPrice = entryPrice,
                    exitPrice = exitPrice,
                    costSol = costSol,
                    pnlSol = pnlSol,
                    buyRoute = buyRoute,
                    sellRoute = sellRoute,
                    launchPlatform = launchPlatform,
                    entryScore = entryScore,
                    entryConfidence = entryConfidence,
                    lane = lane,
                    trader = trader,
                    setupQuality = setupQuality,
                    marketRegime = marketRegime,
                    maxDrawdownPct = maxDrawdownPct,
                    timeToPeakMinutes = timeToPeakMinutes,
                    exitReason = exitReason,
                    volatility = volatility,
                    holderCount = holderCount,
                    holderGrowthRate = holderGrowthRate,
                    topHolderPct = topHolderPct,
                    devWalletPct = devWalletPct,
                    bondingCurveProgress = bondingCurveProgress,
                    rugcheckScore = rugcheckScore,
                    emaFanState = emaFanState,
                    tokenAgeMinutes = tokenAgeMinutes,
                    volumeUsd = volumeUsd,
                    creatorAddress = creatorAddress ?: "",
                )
                ErrorLogger.info(
                    "TokenWinMemory",
                    "🏆 NEW WINNER: $symbol | +${trainablePnl.toInt()}% | mcap: $${entryMcap.toInt()} → $${exitMcap.toInt()}"
                )
                trimWinners()
            }
        } else if (existingWinner != null) {
            existingWinner.timesTraded++
            existingWinner.totalPnl += trainablePnl
            ErrorLogger.debug(
                "TokenWinMemory",
                "📉 KNOWN WINNER LOST: $symbol | ${trainablePnl.toInt()}% | cumulative: ${existingWinner.totalPnl.toInt()}%"
            )
        }

        // ── Learn patterns from decisive trades only ──────────────────────
        learnPatterns(
            symbol = symbol,
            name = name,
            mcap = entryMcap,
            liquidity = entryLiquidity,
            buyPercent = buyPercent,
            phase = phase,
            source = source,
            isWin = isWin,
            pnl = trainablePnl,
            // V5.0.6123 — full context
            lane = lane,
            trader = trader,
            buyRoute = buyRoute,
            sellRoute = sellRoute,
            launchPlatform = launchPlatform,
            setupQuality = setupQuality,
            marketRegime = marketRegime,
            exitReason = exitReason,
            holderCount = holderCount,
            holderGrowthRate = holderGrowthRate,
            topHolderPct = topHolderPct,
            devWalletPct = devWalletPct,
            bondingCurveProgress = bondingCurveProgress,
            rugcheckScore = rugcheckScore,
            emaFanState = emaFanState,
            holdTimeMinutes = holdTimeMinutes,
            entryScore = entryScore,
            volatility = volatility,
            tokenAgeMinutes = tokenAgeMinutes,
        )

        // ── Track creator stats ───────────────────────────────────────────
        if (!creatorAddress.isNullOrBlank()) {
            val stats = creatorStats.getOrPut(creatorAddress) { PatternStats() }
            if (isWin) {
                stats.wins++
                stats.totalPnl += trainablePnl
                stats.avgWinPnl = stats.totalPnl / stats.wins.toDouble()
            } else {
                stats.losses++
            }
        }

        // V5.0.6123 — STAMP INTO THE MINT REGISTER
        // The token's full trade context is now part of its permanent registry
        // identity, so every time the bot sees this mint again it knows the
        // complete history: wins, losses, lane, route, exit reason, holders, etc.
        try {
            GlobalTradeRegistry.stampTradeHistory(
                mint = mint,
                isWin = isWin,
                pnlPct = trainablePnl,
                lane = lane,
                exitReason = exitReason,
                holdMinutes = holdTimeMinutes,
                entryScore = entryScore,
                buyRoute = buyRoute,
                launchPlatform = launchPlatform,
                holderCount = holderCount,
                marketRegime = marketRegime,
            )
        } catch (_: Throwable) {}

        if (countPatterns() > MAX_PATTERNS + 50) {
            trimPatterns()
        }

        if ((winningTokens.size + tokenStats.size + countPatterns()) % 10 == 0) {
            save()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PATTERN LEARNING
    // ═══════════════════════════════════════════════════════════════════════

    private fun learnPatterns(
        symbol: String,
        name: String,
        mcap: Double,
        liquidity: Double,
        buyPercent: Double,
        phase: String,
        source: String,
        isWin: Boolean,
        pnl: Double,
        // V5.0.6123 — full context patterns
        lane: String = "",
        trader: String = "",
        buyRoute: String = "",
        sellRoute: String = "",
        launchPlatform: String = "",
        setupQuality: String = "",
        marketRegime: String = "",
        exitReason: String = "",
        holderCount: Int = 0,
        holderGrowthRate: Double = 0.0,
        topHolderPct: Double = 0.0,
        devWalletPct: Double = 0.0,
        bondingCurveProgress: Double = 0.0,
        rugcheckScore: Double = 0.0,
        emaFanState: String = "",
        holdTimeMinutes: Int = 0,
        entryScore: Double = 0.0,
        volatility: Double = 0.0,
        tokenAgeMinutes: Double = 0.0,
    ) {
        extractNamePatterns(name.lowercase()).forEach { pattern ->
            recordPattern("name_contains", pattern, isWin, pnl)
        }

        extractSymbolPatterns(symbol.uppercase()).forEach { pattern ->
            recordPattern("symbol_pattern", pattern, isWin, pnl)
        }

        val mcapBucket = when {
            mcap < 20_000.0 -> "micro_<20k"
            mcap < 50_000.0 -> "small_20k_50k"
            mcap < 100_000.0 -> "mid_50k_100k"
            mcap < 500_000.0 -> "large_100k_500k"
            else -> "mega_500k+"
        }
        recordPattern("mcap_bucket", mcapBucket, isWin, pnl)

        val liqRatio = if (mcap > 0.0) liquidity / mcap else 0.0
        val liqBucket = when {
            liqRatio < 0.1 -> "liq_ratio_<10%"
            liqRatio < 0.3 -> "liq_ratio_10_30%"
            liqRatio < 0.5 -> "liq_ratio_30_50%"
            else -> "liq_ratio_50%+"
        }
        recordPattern("liq_ratio", liqBucket, isWin, pnl)

        val buyBucket = when {
            buyPercent < 40.0 -> "buy_weak_<40%"
            buyPercent < 55.0 -> "buy_neutral_40_55%"
            buyPercent < 70.0 -> "buy_strong_55_70%"
            else -> "buy_fomo_70%+"
        }
        recordPattern("buy_pressure", buyBucket, isWin, pnl)

        recordPattern("phase", phase, isWin, pnl)
        recordPattern("source", source, isWin, pnl)

        // V5.0.6123 — FULL DIMENSIONAL PATTERN LEARNING
        // Lane + trader + route + launch context patterns
        if (lane.isNotBlank()) recordPattern("lane", lane, isWin, pnl)
        if (trader.isNotBlank()) recordPattern("trader", trader, isWin, pnl)
        if (buyRoute.isNotBlank()) recordPattern("buy_route", buyRoute, isWin, pnl)
        if (sellRoute.isNotBlank()) recordPattern("sell_route", sellRoute, isWin, pnl)
        if (launchPlatform.isNotBlank()) recordPattern("launch_platform", launchPlatform, isWin, pnl)
        if (setupQuality.isNotBlank()) recordPattern("setup_quality", setupQuality, isWin, pnl)
        if (marketRegime.isNotBlank()) recordPattern("market_regime", marketRegime, isWin, pnl)
        if (exitReason.isNotBlank()) recordPattern("exit_reason", exitReason, isWin, pnl)
        if (emaFanState.isNotBlank() && emaFanState != "UNKNOWN")
            recordPattern("ema_fan", emaFanState, isWin, pnl)

        // Holder context patterns
        val holderBucket = when {
            holderCount == 0 -> "holders_unknown"
            holderCount < 50 -> "holders_tiny_<50"
            holderCount < 200 -> "holders_small_50_200"
            holderCount < 1000 -> "holders_mid_200_1000"
            holderCount < 5000 -> "holders_large_1000_5000"
            else -> "holders_mega_5000+"
        }
        recordPattern("holder_count", holderBucket, isWin, pnl)

        val holderGrowthBucket = when {
            holderGrowthRate <= 0.0 -> "holder_growth_negative"
            holderGrowthRate < 0.05 -> "holder_growth_flat"
            holderGrowthRate < 0.20 -> "holder_growth_slow"
            holderGrowthRate < 0.50 -> "holder_growth_fast"
            else -> "holder_growth_explosive"
        }
        recordPattern("holder_growth", holderGrowthBucket, isWin, pnl)

        // Top holder concentration
        val topHolderBucket = when {
            topHolderPct <= 0.0 -> "topholder_unknown"
            topHolderPct < 10.0 -> "topholder_decent_<10%"
            topHolderPct < 25.0 -> "topholder_moderate_10_25%"
            topHolderPct < 50.0 -> "topholder_concentrated_25_50%"
            else -> "topholder_dangerous_50%+"
        }
        recordPattern("top_holder", topHolderBucket, isWin, pnl)

        // Dev wallet
        val devBucket = when {
            devWalletPct <= 0.0 -> "dev_unknown"
            devWalletPct < 5.0 -> "dev_small_<5%"
            devWalletPct < 15.0 -> "dev_moderate_5_15%"
            else -> "dev_large_15%+"
        }
        recordPattern("dev_wallet", devBucket, isWin, pnl)

        // Bonding curve progress
        val bcpBucket = when {
            bondingCurveProgress <= 0.0 -> "bcp_unknown"
            bondingCurveProgress < 30.0 -> "bcp_early_<30%"
            bondingCurveProgress < 60.0 -> "bcp_mid_30_60%"
            bondingCurveProgress < 90.0 -> "bcp_late_60_90%"
            else -> "bcp_graduated_90%+"
        }
        recordPattern("bonding_curve", bcpBucket, isWin, pnl)

        // Rugcheck score
        val rcBucket = when {
            rugcheckScore <= 0.0 -> "rc_unknown"
            rugcheckScore < 30.0 -> "rc_safe_<30"
            rugcheckScore < 60.0 -> "rc_moderate_30_60"
            else -> "rc_risky_60+"
        }
        recordPattern("rugcheck", rcBucket, isWin, pnl)

        // Hold time pattern
        val holdBucket = when {
            holdTimeMinutes == 0 -> "hold_unknown"
            holdTimeMinutes < 2 -> "hold_scalp_<2m"
            holdTimeMinutes < 10 -> "hold_fast_2_10m"
            holdTimeMinutes < 60 -> "hold_mid_10_60m"
            holdTimeMinutes < 240 -> "hold_long_60_240m"
            else -> "hold_runner_240m+"
        }
        recordPattern("hold_time", holdBucket, isWin, pnl)

        // Entry score bucket
        val scoreBucket = when {
            entryScore <= 0.0 -> "score_unknown"
            entryScore < 40.0 -> "score_low_<40"
            entryScore < 55.0 -> "score_mid_40_55"
            entryScore < 70.0 -> "score_high_55_70"
            else -> "score_elite_70+"
        }
        recordPattern("entry_score", scoreBucket, isWin, pnl)

        // Volatility bucket
        val volBucket = when {
            volatility <= 0.0 -> "vol_unknown"
            volatility < 30.0 -> "vol_low_<30"
            volatility < 60.0 -> "vol_mid_30_60"
            volatility < 100.0 -> "vol_high_60_100"
            else -> "vol_extreme_100+"
        }
        recordPattern("volatility", volBucket, isWin, pnl)

        // Token age at entry
        val ageBucket = when {
            tokenAgeMinutes <= 0.0 -> "age_unknown"
            tokenAgeMinutes < 5.0 -> "age_fresh_<5m"
            tokenAgeMinutes < 30.0 -> "age_young_5_30m"
            tokenAgeMinutes < 120.0 -> "age_established_30_120m"
            else -> "age_old_120m+"
        }
        recordPattern("token_age", ageBucket, isWin, pnl)

        // CROSS-DIMENSIONAL patterns — lane × mcap, lane × hold_time, source × holder_count
        // These capture interactions that single-dimension patterns miss
        if (lane.isNotBlank()) {
            recordPattern("lane_x_mcap", "${lane}_${mcapBucket}", isWin, pnl)
            recordPattern("lane_x_hold", "${lane}_${holdBucket}", isWin, pnl)
            recordPattern("lane_x_source", "${lane}_${source.take(20)}", isWin, pnl)
        }
        // Route × mcap — does buying via Jupiter work better at certain mcaps?
        if (buyRoute.isNotBlank()) {
            recordPattern("route_x_mcap", "${buyRoute}_${mcapBucket}", isWin, pnl)
        }
        // Launch platform × holder growth — PumpFun tokens with explosive holder growth
        if (launchPlatform.isNotBlank()) {
            recordPattern("platform_x_holders", "${launchPlatform}_${holderBucket}", isWin, pnl)
        }
        // Setup quality × exit reason — what exit reasons correlate with high-quality setups?
        if (setupQuality.isNotBlank() && exitReason.isNotBlank()) {
            recordPattern("setup_x_exit", "${setupQuality}_${exitReason.take(20)}", isWin, pnl)
        }
    }

    private fun recordPattern(type: String, value: String, isWin: Boolean, pnl: Double) {
        val typeMap = patterns.getOrPut(type) { ConcurrentHashMap() }
        val stats = typeMap.getOrPut(value) { PatternStats() }

        if (isWin) {
            stats.wins++
            stats.totalPnl += pnl
            stats.avgWinPnl = stats.totalPnl / stats.wins.toDouble()
        } else {
            stats.losses++
        }
    }

    private fun extractNamePatterns(name: String): List<String> {
        val found = mutableListOf<String>()

        val themes = listOf(
            "ai", "gpt", "agent", "bot",
            "pepe", "frog", "wojak", "chad",
            "doge", "shib", "inu", "dog", "cat",
            "elon", "trump", "biden", "musk",
            "moon", "rocket", "mars", "space",
            "baby", "mini", "mega", "giga", "ultra",
            "safe", "fair", "based", "wagmi",
            "sol", "solana", "pump", "bonk",
            "100x", "1000x", "gem",
        )

        themes.forEach { theme ->
            if (name.contains(theme)) {
                found.add("theme_$theme")
            }
        }

        val lengthBucket = when {
            name.length <= 4 -> "name_short"
            name.length <= 8 -> "name_medium"
            else -> "name_long"
        }
        found.add(lengthBucket)

        return found
    }

    private fun extractSymbolPatterns(symbol: String): List<String> {
        val found = mutableListOf<String>()

        val lenBucket = when {
            symbol.length <= 3 -> "sym_short"
            symbol.length <= 5 -> "sym_medium"
            else -> "sym_long"
        }
        found.add(lenBucket)

        if (symbol.all { it.isUpperCase() || it.isDigit() }) {
            found.add("sym_standard")
        }

        return found
    }

    private fun sanePatternStats(stats: PatternStats): Boolean {
        val n = stats.wins + stats.losses
        if (n <= 0) return false
        if (!stats.totalPnl.isFinite() || !stats.avgWinPnl.isFinite()) return false
        if (kotlin.math.abs(stats.totalPnl) > n.toDouble() * LearningPnlSanitizer.MAX_TRAINABLE_PNL_PCT) return false
        if (stats.wins > 0 && (stats.avgWinPnl < 0.0 || stats.avgWinPnl > LearningPnlSanitizer.MAX_TRAINABLE_PNL_PCT)) return false
        return true
    }

    private fun saneTokenStats(stats: TokenStats): Boolean {
        val n = stats.decisiveTrades
        if (n <= 0) return false
        if (!stats.totalPnl.isFinite() || !stats.bestPnl.isFinite() || !stats.lastPnl.isFinite()) return false
        return kotlin.math.abs(stats.totalPnl) <= n.toDouble() * LearningPnlSanitizer.MAX_TRAINABLE_PNL_PCT &&
            stats.bestPnl <= LearningPnlSanitizer.MAX_TRAINABLE_PNL_PCT && stats.lastPnl <= LearningPnlSanitizer.MAX_TRAINABLE_PNL_PCT
    }

    private fun saneWinner(w: WinningToken): Boolean =
        w.pnlPercent.isFinite() && w.totalPnl.isFinite() &&
            w.pnlPercent in WIN_THRESHOLD_PCT..PHANTOM_PNL_PCT_HARD_CEILING_6011 &&
            kotlin.math.abs(w.totalPnl) <= w.timesTraded.coerceAtLeast(1).toDouble() * LearningPnlSanitizer.MAX_TRAINABLE_PNL_PCT

    // V5.0.4508 — persisted-memory repair. New closes now carry SOL basis through
    // EducationSubLayerAI, but old WinningToken rows were persisted as pct-only.
    // When entry/exit market-cap snapshots exist, use them as a second basis so
    // fabricated +pct rows cannot survive restart and keep PatternGoldenGoose hot.
    private fun sanePersistedWinner4508(w: WinningToken): Boolean {
        if (isShadowOrSimulatedSource(w.source)) return false
        if (!saneWinner(w)) return false
        if (!w.peakPnl.isFinite() || !w.entryMcap.isFinite() || !w.exitMcap.isFinite()) return false
        if (w.peakPnl + 50.0 < w.pnlPercent) return false
        if (w.entryMcap > 0.0 && w.exitMcap > 0.0) {
            val impliedPct = ((w.exitMcap - w.entryMcap) / w.entryMcap) * 100.0
            val tolerance = max(50.0, abs(w.pnlPercent) * 0.35)
            if (abs(impliedPct - w.pnlPercent) > tolerance) return false
        }
        return true
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY
    // ═══════════════════════════════════════════════════════════════════════

    fun getConfidenceMultiplier(
        mint: String,
        symbol: String,
        name: String,
        mcap: Double,
        liquidity: Double,
        buyPercent: Double,
        phase: String,
        source: String,
    ): Double {
        var score = 0.0
        var factors = 0

        val exactStats = tokenStats[mint]
        if (exactStats != null && exactStats.decisiveTrades > 0 && saneTokenStats(exactStats)) {
            val rawExactScore = when {
                exactStats.totalPnl >= 100.0 -> 50.0
                exactStats.totalPnl >= 50.0 -> 35.0
                exactStats.totalPnl >= 20.0 -> 25.0
                exactStats.totalPnl >= 10.0 -> 15.0
                exactStats.totalPnl >= 0.5 -> 8.0
                exactStats.totalPnl <= -50.0 -> -25.0
                exactStats.totalPnl <= -20.0 -> -15.0
                exactStats.totalPnl <= -5.0 -> -8.0
                else -> 0.0
            }

            // ── V5.9.829 (A6) — liquidity + buyPercent context scaling ──
            // The "known winner" boost was ignoring CURRENT liquidity. A token
            // that 10x'd at 100k liquidity gets the same boost when it
            // restarts at 1k liquidity — a classic rug-restart shape. Same for
            // buy% — a known winner with current sell-dominance has flipped
            // character. Scale boost (positive scores only), keep penalties
            // fully active.
            val exactScore = if (rawExactScore > 0.0) {
                // Liquidity ratio: current vs entry-at-win-time. Look up the
                // most recent WinningToken record for this mint.
                val winRec = winningTokens[mint]
                val liqRatio = if (winRec != null && winRec.entryLiquidity > 0.0 && liquidity > 0.0) {
                    (liquidity / winRec.entryLiquidity).coerceIn(0.0, 2.0)
                } else 1.0
                val liqMult = when {
                    liqRatio >= 1.0 -> 1.0                    // same/better liquidity — full boost
                    liqRatio >= 0.5 -> 0.75                   // half drained — 75% boost
                    liqRatio >= 0.2 -> 0.40                   // 80% drained — quarter boost
                    else            -> 0.10                   // rug-restart shape — keep tiny memory only
                }
                val buyMult = when {
                    buyPercent >= 60.0 -> 1.0                 // strong buy dominance
                    buyPercent >= 50.0 -> 0.90
                    buyPercent >= 40.0 -> 0.70                // weakening
                    else               -> 0.40                // sell-dominated — winner shape has flipped
                }
                rawExactScore * liqMult * buyMult
            } else {
                rawExactScore   // penalties pass through unscaled
            }

            score += exactScore
            factors++

            if (exactScore > 0.0) {
                ErrorLogger.info(
                    "TokenWinMemory",
                    "🔥 KNOWN WINNER DETECTED: $symbol | total pnl: ${exactStats.totalPnl.toInt()}% | boost: +${"%.1f".format(exactScore)} (raw +${rawExactScore.toInt()}, liq×buy ctx)"
                )
            } else if (exactScore < 0.0) {
                ErrorLogger.debug(
                    "TokenWinMemory",
                    "⚠️ KNOWN LOSER DETECTED: $symbol | total pnl: ${exactStats.totalPnl.toInt()}% | penalty: $exactScore"
                )
            }
        }

        extractNamePatterns(name.lowercase()).forEach { pattern ->
            val stats = patterns["name_contains"]?.get(pattern)
            if (stats != null && stats.isReliable) {
                score += (stats.winRate - 0.5) * 20.0
                factors++
            }
        }

        val mcapBucket = when {
            mcap < 20_000.0 -> "micro_<20k"
            mcap < 50_000.0 -> "small_20k_50k"
            mcap < 100_000.0 -> "mid_50k_100k"
            mcap < 500_000.0 -> "large_100k_500k"
            else -> "mega_500k+"
        }
        patterns["mcap_bucket"]?.get(mcapBucket)?.let { stats ->
            if (stats.isReliable) {
                score += (stats.winRate - 0.5) * 15.0
                factors++
            }
        }

        patterns["phase"]?.get(phase)?.let { stats ->
            if (stats.isReliable) {
                score += (stats.winRate - 0.5) * 15.0
                factors++
            }
        }

        patterns["source"]?.get(source)?.let { stats ->
            if (stats.isReliable) {
                score += (stats.winRate - 0.5) * 10.0
                factors++
            }
        }

        val multiplier = when {
            score >= 30.0 -> 2.0
            score >= 15.0 -> 1.5
            score >= 5.0 -> 1.2
            score >= -5.0 -> 1.0
            score >= -15.0 -> 0.8
            else -> 0.5
        }

        if (factors > 0) {
            ErrorLogger.debug(
                "TokenWinMemory",
                "$symbol: memory score=${"%.1f".format(score)} multiplier=${"%.2f".format(multiplier)} ($factors factors)"
            )
        }

        return multiplier
    }

    fun isKnownWinner(mint: String): Boolean = winningTokens[mint]?.let { saneWinner(it) } == true

    fun getWinnerStats(mint: String): WinningToken? = winningTokens[mint]?.takeIf { saneWinner(it) }

    /**
     * V5.0.6123 — Full trade context for a mint.
     * Returns the complete WinningToken with all buy/sell/hold/launch details.
     * This is what downstream consumers (scorers, traders, lifecycle detector)
     * should use to understand WHY a token was a winner, not just that it was.
     */
    fun getFullTradeContext(mint: String): WinningToken? = winningTokens[mint]?.takeIf { saneWinner(it) }

    /**
     * V5.0.6123 — Multi-dimensional EV query.
     * Instead of just name/symbol patterns, query EV across lane, source, mcap,
     * holder count, hold time, route, launch platform, and their cross-products.
     * Returns a composite score bias that captures the FULL context EV.
     */
    fun fullContextEvScore(
        mint: String,
        symbol: String,
        name: String,
        mcap: Double,
        liquidity: Double,
        buyPercent: Double,
        phase: String,
        source: String,
        lane: String = "",
        buyRoute: String = "",
        launchPlatform: String = "",
        holderCount: Int = 0,
        holderGrowthRate: Double = 0.0,
        emaFanState: String = "",
        entryScore: Double = 0.0,
        volatility: Double = 0.0,
        tokenAgeMinutes: Double = 0.0,
        setupQuality: String = "",
        marketRegime: String = "",
    ): Double {
        var score = 0.0
        var factors = 0

        // Exact mint history (unchanged — still the strongest signal)
        val exactStats = tokenStats[mint]
        if (exactStats != null && exactStats.decisiveTrades > 0 && saneTokenStats(exactStats)) {
            val exactScore = when {
                exactStats.totalPnl >= 100.0 -> 50.0
                exactStats.totalPnl >= 50.0 -> 35.0
                exactStats.totalPnl >= 20.0 -> 25.0
                exactStats.totalPnl >= 10.0 -> 15.0
                exactStats.totalPnl >= 0.5 -> 8.0
                exactStats.totalPnl <= -50.0 -> -25.0
                exactStats.totalPnl <= -20.0 -> -15.0
                exactStats.totalPnl <= -5.0 -> -8.0
                else -> 0.0
            }
            score += exactScore
            factors++
        }

        // Name/symbol patterns (existing)
        extractNamePatterns(name.lowercase()).forEach { pattern ->
            val stats = patterns["name_contains"]?.get(pattern)
            if (stats != null && stats.isReliable) {
                score += (stats.winRate - 0.5) * 20.0
                factors++
            }
        }

        // Mcap bucket (existing)
        val mcapBucket = when {
            mcap < 20_000.0 -> "micro_<20k"
            mcap < 50_000.0 -> "small_20k_50k"
            mcap < 100_000.0 -> "mid_50k_100k"
            mcap < 500_000.0 -> "large_100k_500k"
            else -> "mega_500k+"
        }
        patterns["mcap_bucket"]?.get(mcapBucket)?.let { stats ->
            if (stats.isReliable) { score += (stats.winRate - 0.5) * 15.0; factors++ }
        }

        // V5.0.6123 — NEW dimensional patterns
        if (lane.isNotBlank()) {
            patterns["lane"]?.get(lane)?.let { stats ->
                if (stats.isReliable) { score += (stats.winRate - 0.5) * 20.0; factors++ }
            }
            // Cross-dimensional: lane × mcap
            patterns["lane_x_mcap"]?.get("${lane}_${mcapBucket}")?.let { stats ->
                if (stats.isReliable) { score += (stats.winRate - 0.5) * 25.0; factors++ }
            }
        }

        if (buyRoute.isNotBlank()) {
            patterns["buy_route"]?.get(buyRoute)?.let { stats ->
                if (stats.isReliable) { score += (stats.winRate - 0.5) * 12.0; factors++ }
            }
        }

        if (launchPlatform.isNotBlank()) {
            patterns["launch_platform"]?.get(launchPlatform)?.let { stats ->
                if (stats.isReliable) { score += (stats.winRate - 0.5) * 15.0; factors++ }
            }
        }

        if (setupQuality.isNotBlank()) {
            patterns["setup_quality"]?.get(setupQuality)?.let { stats ->
                if (stats.isReliable) { score += (stats.winRate - 0.5) * 10.0; factors++ }
            }
        }

        if (marketRegime.isNotBlank()) {
            patterns["market_regime"]?.get(marketRegime)?.let { stats ->
                if (stats.isReliable) { score += (stats.winRate - 0.5) * 12.0; factors++ }
            }
        }

        // Holder count
        val holderBucket = when {
            holderCount == 0 -> "holders_unknown"
            holderCount < 50 -> "holders_tiny_<50"
            holderCount < 200 -> "holders_small_50_200"
            holderCount < 1000 -> "holders_mid_200_1000"
            holderCount < 5000 -> "holders_large_1000_5000"
            else -> "holders_mega_5000+"
        }
        patterns["holder_count"]?.get(holderBucket)?.let { stats ->
            if (stats.isReliable) { score += (stats.winRate - 0.5) * 12.0; factors++ }
        }

        // Holder growth
        val holderGrowthBucket = when {
            holderGrowthRate <= 0.0 -> "holder_growth_negative"
            holderGrowthRate < 0.05 -> "holder_growth_flat"
            holderGrowthRate < 0.20 -> "holder_growth_slow"
            holderGrowthRate < 0.50 -> "holder_growth_fast"
            else -> "holder_growth_explosive"
        }
        patterns["holder_growth"]?.get(holderGrowthBucket)?.let { stats ->
            if (stats.isReliable) { score += (stats.winRate - 0.5) * 15.0; factors++ }
        }

        // EMA fan state
        if (emaFanState.isNotBlank() && emaFanState != "UNKNOWN") {
            patterns["ema_fan"]?.get(emaFanState)?.let { stats ->
                if (stats.isReliable) { score += (stats.winRate - 0.5) * 10.0; factors++ }
            }
        }

        // Entry score bucket
        val scoreBucket = when {
            entryScore <= 0.0 -> "score_unknown"
            entryScore < 40.0 -> "score_low_<40"
            entryScore < 55.0 -> "score_mid_40_55"
            entryScore < 70.0 -> "score_high_55_70"
            else -> "score_elite_70+"
        }
        patterns["entry_score"]?.get(scoreBucket)?.let { stats ->
            if (stats.isReliable) { score += (stats.winRate - 0.5) * 12.0; factors++ }
        }

        // Token age
        val ageBucket = when {
            tokenAgeMinutes <= 0.0 -> "age_unknown"
            tokenAgeMinutes < 5.0 -> "age_fresh_<5m"
            tokenAgeMinutes < 30.0 -> "age_young_5_30m"
            tokenAgeMinutes < 120.0 -> "age_established_30_120m"
            else -> "age_old_120m+"
        }
        patterns["token_age"]?.get(ageBucket)?.let { stats ->
            if (stats.isReliable) { score += (stats.winRate - 0.5) * 10.0; factors++ }
        }

        // Phase + source (existing)
        patterns["phase"]?.get(phase)?.let { stats ->
            if (stats.isReliable) { score += (stats.winRate - 0.5) * 15.0; factors++ }
        }
        patterns["source"]?.get(source)?.let { stats ->
            if (stats.isReliable) { score += (stats.winRate - 0.5) * 10.0; factors++ }
        }

        return if (factors > 0) score else 0.0
    }

    fun getMemoryScoreForMint(mint: String): Int {
        val stats = tokenStats[mint] ?: return 0
        if (!saneTokenStats(stats)) return 0

        return when {
            stats.totalPnl >= 100.0 -> 50
            stats.totalPnl >= 50.0 -> 35
            stats.totalPnl >= 20.0 -> 25
            stats.totalPnl >= 10.0 -> 15
            stats.totalPnl >= 0.5 -> 5
            stats.totalPnl <= -50.0 -> -20
            stats.totalPnl <= -20.0 -> -15
            stats.totalPnl <= -10.0 -> -10
            stats.totalPnl <= -5.0 -> -5
            else -> 0
        }
    }

    /**
     * V5.0.4123 — Pattern-based entry score from name/symbol themes.
     * Returns 0-100 where 50 = neutral (no pattern data or unreliable).
     * Uses the WR of matched name/symbol patterns to shape the score:
     *   theme_space 83% WR → ~83
     *   theme_musk 0% WR → ~0
     *   name_medium 54% WR → ~54
     * Only uses reliable patterns (>=5 samples). Unreliable = neutral 50.
     */
    fun patternScoreForToken(name: String, symbol: String): Double {
        val namePatterns = extractNamePatterns(name.lowercase())
        val symPatterns = extractSymbolPatterns(symbol.uppercase())
        val allPatterns = namePatterns + symPatterns

        var weightedSum = 0.0
        var totalWeight = 0.0

        for (pattern in allPatterns) {
            // Search both "name_contains" and "symbol_contains" type buckets
            for ((type, typeMap) in patterns) {
                val stats = typeMap[pattern] ?: continue
                if (!sanePatternStats(stats) || !stats.isReliable) continue
                val wr = stats.winRate  // 0.0 to 1.0
                val n = stats.wins + stats.losses
                // Weight by sample count (more samples = more confidence)
                val weight = n.toDouble().coerceAtMost(50.0)
                weightedSum += wr * 100.0 * weight
                totalWeight += weight
            }
        }

        return if (totalWeight > 0.0) (weightedSum / totalWeight).coerceIn(0.0, 100.0) else 50.0
    }

    // ═══════════════════════════════════════════════════════════════════════
    // V5.0.4128 — PATTERN GOLDEN GOOSE EDGE
    // Asymmetric edge detector. Where patternScoreForToken blends matches into
    // a single 0-100 score (and tends to cluster near 50 because positives and
    // negatives partly cancel), this returns the SHARPEST positive and
    // SHARPEST negative pattern matched independently — so a token that hits
    // theme_space (82% WR n=75) and theme_inu (0% WR n=13) is correctly read
    // as "GOLD AND TOXIC at the same time → net TOXIC" by the goose, not as
    // a blurred ~40% score.
    // ═══════════════════════════════════════════════════════════════════════

    enum class Verdict { CATASTROPHIC, TOXIC, NEUTRAL, WINNER, GOLD }

    data class PatternEdge(
        val verdict: Verdict,
        val scoreBias: Int,         // additive to lane score (already asymmetric: toxic > gold)
        val bestPattern: String?,   // e.g. "name_contains:theme_space"
        val bestWr: Double,         // 0..1
        val bestN: Int,
        val worstPattern: String?,  // e.g. "name_contains:theme_musk"
        val worstWr: Double,        // 0..1
        val worstN: Int,
    ) {
        val tag: String
            get() {
                val parts = mutableListOf<String>()
                bestPattern?.let { parts.add("+${it.substringAfter(':').take(14)}=${(bestWr*100).toInt()}%n$bestN") }
                worstPattern?.let { parts.add("-${it.substringAfter(':').take(14)}=${(worstWr*100).toInt()}%n$worstN") }
                if (parts.isEmpty()) parts.add("no_pattern")
                return "${verdict.name}_${parts.joinToString("|")}_bias${if (scoreBias >= 0) "+" else ""}$scoreBias"
            }
    }

    /**
     * Hot path: returns sharp asymmetric edge for a token name/symbol.
     * Reliability gate: requires n >= 10 (golden goose is sharper than the
     * `isReliable` n >= 5 floor — we only act on patterns we've seen play out
     * at least 10 times). Veto-grade is gated to n >= 15.
     *
     * Asymmetric reaction: a TOXIC bias (negative) is stronger than a GOLD
     * bias (positive) for the same WR delta — the bot tilts AWAY from losers
     * harder than it leans toward winners. Bleed-stop > moonshot capture.
     */
    fun patternEdgeForToken(name: String, symbol: String): PatternEdge {
        val namePatterns = extractNamePatterns(name.lowercase())
        val symPatterns = extractSymbolPatterns(symbol.uppercase())
        // Also let the symbol's lowercased text match `theme_*` themes — a
        // SPACE-symbol with no name field should still hit theme_space.
        val symThemes = extractNamePatterns(symbol.lowercase())
        val all = (namePatterns + symPatterns + symThemes).toSet().toList()

        var bestPattern: String? = null
        var bestWr = 0.0
        var bestN = 0
        var bestAvgWin = 0.0 // V5.0.6120c — EV path
        var worstPattern: String? = null
        var worstWr = 1.0
        var worstN = 0

        for (pat in all) {
            for ((type, typeMap) in patterns) {
                val stats = typeMap[pat] ?: continue
                if (!sanePatternStats(stats)) continue
                val n = stats.wins + stats.losses
                // V5.0.6120c — lowered inclusion floor from n>=10 to n>=8.
                // Operator report showed strong patterns (theme_frog n=8,
                // theme_elon n=9, theme_baby n=9) invisible to the goose.
                // Small-n patterns still gated by strict verdict thresholds
                // below, so a coin-flip at n=8 stays NEUTRAL — only tokens
                // with sharp asymmetric WR/EV cross into GOLD or TOXIC.
                if (n < 8) continue
                val wr = stats.winRate
                if (wr > bestWr || (wr == bestWr && n > bestN)) {
                    bestWr = wr; bestN = n; bestPattern = "$type:$pat"; bestAvgWin = stats.avgWinPnl
                }
                if (wr < worstWr || (wr == worstWr && n > worstN)) {
                    worstWr = wr; worstN = n; worstPattern = "$type:$pat"
                }
            }
        }

        if (bestPattern == null && worstPattern == null) {
            return PatternEdge(Verdict.NEUTRAL, 0, null, 0.0, 0, null, 0.0, 0)
        }

        // V5.0.6120c — SHARPENED VERDICTS based on operator July 2026 report:
        //   theme_dog   n=14 WR=0%              — was TOXIC (n<15 for CATA),
        //                                         now CATASTROPHIC → hard veto
        //   theme_pump  n=14 WR=0%              — same
        //   theme_elon  n=9  WR=22% avgWin=132% — was NEUTRAL (n<10, WR<70%),
        //                                         now GOLD via EV path
        //   theme_baby  n=9  WR=22% avgWin=80%  — same GOLD via EV path
        //   theme_frog  n=8  WR=37% avgWin=48%  — now WINNER via EV path
        //
        // EV = winRate × avgWinPnl. A rare-but-huge winner beats a common
        // small winner. theme_elon: 0.22 × 132% = +29% EV/attempt vs a
        // 60% × 10% = +6% EV bucket.
        //
        // Tighter negative thresholds: 14 straight losses at 0% is more
        // than enough evidence to hard-veto without waiting for the 15th
        // loss. Doctrine #86 (bounded, fail-open) preserved — this is a
        // score bias, not a policy override.

        val goldHit  = bestPattern != null  && bestWr >= 0.60 && bestN  >= 8
        val evGoldHit = bestPattern != null &&
                        (bestWr * bestAvgWin) >= 20.0 &&      // ≥20% EV/attempt
                        bestN >= 8 &&
                        bestAvgWin >= 30.0                    // avoids tiny-win bias
        val winHit   = bestPattern != null  && bestWr >= 0.50 && bestN  >= 8
        val toxicHit = worstPattern != null && worstWr <= 0.15 && worstN >= 8
        val cataHit  = worstPattern != null && worstWr <= 0.10 && worstN >= 10

        val verdict = when {
            cataHit  -> Verdict.CATASTROPHIC
            toxicHit -> Verdict.TOXIC
            goldHit || evGoldHit -> Verdict.GOLD
            winHit   -> Verdict.WINNER
            else     -> Verdict.NEUTRAL
        }

        // Bias: tuned so toxic veto is roughly 2× gold lift.
        val bias = when (verdict) {
            Verdict.CATASTROPHIC -> -35  // effective hard reject when combined with min score
            Verdict.TOXIC        -> -22
            Verdict.GOLD         -> +16
            Verdict.WINNER       -> +6
            Verdict.NEUTRAL      -> 0
        }

        return PatternEdge(verdict, bias, bestPattern, bestWr, bestN, worstPattern, worstWr, worstN)
    }



    // V5.0.6192 — paper/live pattern edge for LIVE context transfer.
    // Paper learning is not live PnL authority, but toxic source/setup/platform
    // knowledge is directly relevant to live risk. This exposes combined pattern
    // memory as a down-only live intelligence signal; callers must not use GOLD
    // here to increase live size without live-clean proof.
    fun patternEdgeForLiveContext6192(
        source: String = "",
        launchPlatform: String = "",
        setupQuality: String = "",
        lane: String = "",
        buyRoute: String = "",
    ): PatternEdge {
        val candidates = mutableListOf<Pair<String, String>>()
        if (source.isNotBlank()) candidates += "source" to source.take(80)
        if (launchPlatform.isNotBlank()) candidates += "launch_platform" to launchPlatform.take(80)
        if (setupQuality.isNotBlank()) candidates += "setup_quality" to setupQuality.take(80)
        if (lane.isNotBlank()) candidates += "lane" to lane.take(80)
        if (buyRoute.isNotBlank()) candidates += "buy_route" to buyRoute.take(80)

        var bestPattern: String? = null
        var bestWr = 0.0
        var bestN = 0
        var bestAvgWin = 0.0
        var worstPattern: String? = null
        var worstWr = 1.0
        var worstN = 0

        // V5.0.6207 — funnel un-choke: `lane` and `buy_route` are STRUCTURAL
        // dimensions the bot always trades through. A single bad paper cohort
        // in a lane (e.g. MEME @ 15% WR / n=8) would hard-block EVERY live meme
        // entry regardless of setup — this killed 180 live trades in the last
        // op-report. Only fine-grained dimensions may authorize live hard-block.
        val worstEligibleTypes = setOf("launch_platform", "setup_quality", "source")
        for ((type, value) in candidates) {
            val stats = patterns[type]?.get(value) ?: continue
            if (!sanePatternStats(stats)) continue
            val n = stats.wins + stats.losses
            if (n < 8) continue
            val wr = stats.winRate
            if (wr > bestWr || (wr == bestWr && n > bestN)) {
                bestWr = wr; bestN = n; bestPattern = "$type:$value"; bestAvgWin = stats.avgWinPnl
            }
            // Structural dimensions (lane, buy_route) contribute to guidance but
            // are NOT allowed to drive the worstPattern that hard-blocks live entry.
            if (type in worstEligibleTypes) {
                if (wr < worstWr || (wr == worstWr && n > worstN)) {
                    worstWr = wr; worstN = n; worstPattern = "$type:$value"
                }
            }
        }

        if (bestPattern == null && worstPattern == null) {
            return PatternEdge(Verdict.NEUTRAL, 0, null, 0.0, 0, null, 0.0, 0)
        }

        val goldHit = bestPattern != null && bestWr >= 0.60 && bestN >= 8
        val evGoldHit = bestPattern != null && (bestWr * bestAvgWin) >= 20.0 && bestN >= 8 && bestAvgWin >= 30.0
        val winHit = bestPattern != null && bestWr >= 0.50 && bestN >= 8
        // V5.0.6207 — raise sample thresholds so paper-derived toxicity needs real
        // evidence before abandoning live entries. Was 8 / 10 (trivially triggered).
        val toxicHit = worstPattern != null && worstWr <= 0.15 && worstN >= 20
        val cataHit = worstPattern != null && worstWr <= 0.10 && worstN >= 30
        val verdict = when {
            cataHit -> Verdict.CATASTROPHIC
            toxicHit -> Verdict.TOXIC
            goldHit || evGoldHit -> Verdict.GOLD
            winHit -> Verdict.WINNER
            else -> Verdict.NEUTRAL
        }
        val bias = when (verdict) {
            Verdict.CATASTROPHIC -> -35
            Verdict.TOXIC -> -22
            Verdict.GOLD -> +0      // down-only live bridge; no paper-authorized live size lift
            Verdict.WINNER -> +0    // guidance tag only unless live-clean proof elsewhere permits risk
            Verdict.NEUTRAL -> 0
        }
        return PatternEdge(verdict, bias, bestPattern, bestWr, bestN, worstPattern, worstWr, worstN)
    }

    fun exportPatternAggregates(limit: Int = 250): List<ExportedPatternAggregate> {
        return patterns.flatMap { (type, typePatterns) ->
            typePatterns.map { (value, stats) ->
                ExportedPatternAggregate(
                    type = type,
                    value = value,
                    wins = stats.wins,
                    losses = stats.losses,
                    totalPnl = stats.totalPnl,
                    avgWinPnl = stats.avgWinPnl,
                )
            }
        }
            .filter { it.totalTrades > 0 && kotlin.math.abs(it.totalPnl) <= it.totalTrades.toDouble() * LearningPnlSanitizer.MAX_TRAINABLE_PNL_PCT && it.avgWinPnl <= LearningPnlSanitizer.MAX_TRAINABLE_PNL_PCT }
            .sortedWith(
                compareByDescending<ExportedPatternAggregate> { it.totalTrades }
                    .thenByDescending { kotlin.math.abs(it.winRate - 0.5) }
                    .thenByDescending { kotlin.math.abs(it.totalPnl) }
            )
            .take(limit.coerceIn(1, 1000))
    }

    fun getBestPatterns(limit: Int = 5): List<Pair<String, PatternStats>> {
        return patterns.flatMap { (type, typePatterns) ->
            typePatterns.map { (value, stats) -> "$type:$value" to stats }
        }
            .filter { it.second.isReliable && sanePatternStats(it.second) }
            .sortedByDescending { it.second.winRate }
            .take(limit)
    }

    fun getWorstPatterns(limit: Int = 5): List<Pair<String, PatternStats>> {
        return patterns.flatMap { (type, typePatterns) ->
            typePatterns.map { (value, stats) -> "$type:$value" to stats }
        }
            .filter { it.second.isReliable && sanePatternStats(it.second) }
            .sortedBy { it.second.winRate }
            .take(limit)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STATS & UTILITIES
    // ═══════════════════════════════════════════════════════════════════════

    private fun countPatterns(): Int {
        return patterns.values.sumOf { it.size }
    }

    private fun trimWinners() {
        if (winningTokens.size <= MAX_WINNERS) return

        val toRemove = winningTokens.values
            .sortedBy { it.totalPnl }
            .take(winningTokens.size - MAX_WINNERS + 500)
            .map { it.mint }

        toRemove.forEach { winningTokens.remove(it) }
    }

    private fun trimPatterns() {
        val total = countPatterns()
        if (total <= MAX_PATTERNS) return

        val allPatterns = mutableListOf<Triple<String, String, PatternStats>>()
        patterns.forEach { (type, typePatterns) ->
            typePatterns.forEach { (value, stats) ->
                allPatterns.add(Triple(type, value, stats))
            }
        }

        val toRemove = allPatterns
            .sortedWith(
                compareBy<Triple<String, String, PatternStats>>(
                    { it.third.wins + it.third.losses },
                    { it.third.totalPnl }
                )
            )
            .take(total - MAX_PATTERNS)

        toRemove.forEach { (type, value, _) ->
            patterns[type]?.remove(value)
            if (patterns[type]?.isEmpty() == true) {
                patterns.remove(type)
            }
        }
    }

    fun getStats(): String {
        val saneWinners = winningTokens.values.filter { saneWinner(it) }
        val quarantinedWinners = winningTokens.size - saneWinners.size
        val totalWinners = saneWinners.size
        val totalPnl = saneWinners.sumOf { it.totalPnl }
        val avgPnl = if (totalWinners > 0) totalPnl / totalWinners.toDouble() else 0.0
        val repeatWinners = saneWinners.count { it.timesTraded > 1 }

        return "Winners: $totalWinners ($repeatWinners repeat) | Total PnL: ${totalPnl.toInt()}% | Avg: ${avgPnl.toInt()}%" + if (quarantinedWinners > 0) " | quarantinedLegacy=$quarantinedWinners" else ""
    }

    fun getPatternSummary(): String {
        val totalPatterns = countPatterns()
        val sanePatternCount = patterns.values.sumOf { typeMap -> typeMap.values.count { sanePatternStats(it) } }
        val reliablePatterns = patterns.values.sumOf { typeMap ->
            typeMap.values.count { it.isReliable && sanePatternStats(it) }
        }
        val saneWinnerCount = winningTokens.values.count { saneWinner(it) }

        val bestPatterns = getBestPatterns(3)
        val worstPatterns = getWorstPatterns(3)

        val bestStr = bestPatterns.joinToString(", ") {
            "${it.first.substringAfter(":")}(${(it.second.winRate * 100).toInt()}%)"
        }
        val worstStr = worstPatterns.joinToString(", ") {
            "${it.first.substringAfter(":")}(${(it.second.winRate * 100).toInt()}%)"
        }

        return "patterns=$sanePatternCount/$totalPatterns (reliable=$reliablePatterns) | " +
            "winners=$saneWinnerCount/${winningTokens.size} | best=[$bestStr] | worst=[$worstStr]"
    }

    fun forceSave() {
        save()
        ErrorLogger.debug(
            "TokenWinMemory",
            "💾 Force saved ${winningTokens.size} winners, ${tokenStats.size} token stats, ${countPatterns()} patterns"
        )
    }

    fun logDetailedStats() {
        ErrorLogger.info(
            "TokenWinMemory",
            """
            |📊 TOKEN WIN MEMORY STATS
            |   Winning tokens: ${winningTokens.size}
            |   Exact token stats: ${tokenStats.size}
            |   Total patterns: ${countPatterns()}
            |
            |   🏆 TOP PATTERNS:
            |   ${getBestPatterns(5).joinToString("\n|   ") { "${it.first}: ${(it.second.winRate * 100).toInt()}% win rate (${it.second.wins}W/${it.second.losses}L)" }}
            |
            |   ⚠️ WORST PATTERNS:
            |   ${getWorstPatterns(5).joinToString("\n|   ") { "${it.first}: ${(it.second.winRate * 100).toInt()}% win rate (${it.second.wins}W/${it.second.losses}L)" }}
            """.trimMargin()
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════

    fun save() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val winnersJson = JSONArray()
            winningTokens.values.forEach { w ->
                winnersJson.put(JSONObject().apply {
                    put("mint", w.mint)
                    put("symbol", w.symbol)
                    put("name", w.name)
                    put("pnlPercent", w.pnlPercent)
                    put("peakPnl", w.peakPnl)
                    put("entryMcap", w.entryMcap)
                    put("exitMcap", w.exitMcap)
                    put("entryLiquidity", w.entryLiquidity)
                    put("holdTimeMinutes", w.holdTimeMinutes)
                    put("buyPercent", w.buyPercent)
                    put("timestamp", w.timestamp)
                    put("source", w.source)
                    put("phase", w.phase)
                    put("timesTraded", w.timesTraded)
                    put("totalPnl", w.totalPnl)
                    // V5.0.6123 — full trade context
                    put("entryPrice", w.entryPrice)
                    put("exitPrice", w.exitPrice)
                    put("costSol", w.costSol)
                    put("pnlSol", w.pnlSol)
                    put("buyRoute", w.buyRoute)
                    put("sellRoute", w.sellRoute)
                    put("launchPlatform", w.launchPlatform)
                    put("entryScore", w.entryScore)
                    put("entryConfidence", w.entryConfidence)
                    put("lane", w.lane)
                    put("trader", w.trader)
                    put("setupQuality", w.setupQuality)
                    put("marketRegime", w.marketRegime)
                    put("maxDrawdownPct", w.maxDrawdownPct)
                    put("timeToPeakMinutes", w.timeToPeakMinutes)
                    put("exitReason", w.exitReason)
                    put("volatility", w.volatility)
                    put("holderCount", w.holderCount)
                    put("holderGrowthRate", w.holderGrowthRate)
                    put("topHolderPct", w.topHolderPct)
                    put("devWalletPct", w.devWalletPct)
                    put("bondingCurveProgress", w.bondingCurveProgress)
                    put("rugcheckScore", w.rugcheckScore)
                    put("emaFanState", w.emaFanState)
                    put("tokenAgeMinutes", w.tokenAgeMinutes)
                    put("volumeUsd", w.volumeUsd)
                    put("creatorAddress", w.creatorAddress)
                })
            }

            val patternsJson = JSONObject()
            patterns.forEach { (type, typePatterns) ->
                val typeJson = JSONObject()
                typePatterns.forEach { (value, stats) ->
                    typeJson.put(value, JSONObject().apply {
                        put("wins", stats.wins)
                        put("losses", stats.losses)
                        put("totalPnl", stats.totalPnl)
                        put("avgWinPnl", stats.avgWinPnl)
                    })
                }
                patternsJson.put(type, typeJson)
            }

            val creatorsJson = JSONObject()
            creatorStats.forEach { (creator, stats) ->
                creatorsJson.put(creator, JSONObject().apply {
                    put("wins", stats.wins)
                    put("losses", stats.losses)
                    put("totalPnl", stats.totalPnl)
                    put("avgWinPnl", stats.avgWinPnl)
                })
            }

            val tokenStatsJson = JSONObject()
            tokenStats.forEach { (mint, stats) ->
                tokenStatsJson.put(mint, JSONObject().apply {
                    put("wins", stats.wins)
                    put("losses", stats.losses)
                    put("totalPnl", stats.totalPnl)
                    put("bestPnl", stats.bestPnl)
                    put("lastPnl", stats.lastPnl)
                    put("lastSeen", stats.lastSeen)
                })
            }

            prefs.edit()
                .putString(KEY_WINNERS, winnersJson.toString())
                .putString(KEY_PATTERNS, patternsJson.toString())
                .putString(KEY_CREATORS, creatorsJson.toString())
                .putString(KEY_TOKEN_STATS, tokenStatsJson.toString())
                .apply()

            PersistentLearning.saveTokenWinMemory(
                winnersJson.toString(),
                patternsJson.toString()
            )

            ErrorLogger.debug(
                "TokenWinMemory",
                "💾 Saved ${winningTokens.size} winners, ${tokenStats.size} token stats, ${countPatterns()} patterns"
            )
        } catch (e: Exception) {
            ErrorLogger.error("TokenWinMemory", "Save failed: ${e.message}")
        }
    }

    private fun load() {
        val c = ctx ?: return
        try {
            winningTokens.clear()
            patterns.clear()
            creatorStats.clear()
            tokenStats.clear()
            var persistedWinnerQuarantine4508 = 0
            var persistedPatternQuarantine4508 = 0
            var persistedTokenStatsQuarantine4508 = 0
            var persistedCreatorQuarantine4508 = 0

            val persistent = PersistentLearning.loadTokenWinMemory()
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val winnersStr = persistent?.first ?: prefs.getString(KEY_WINNERS, "[]") ?: "[]"
            val patternsStr = persistent?.second ?: prefs.getString(KEY_PATTERNS, "{}") ?: "{}"
            val creatorsStr = prefs.getString(KEY_CREATORS, "{}") ?: "{}"
            val tokenStatsStr = prefs.getString(KEY_TOKEN_STATS, "{}") ?: "{}"

            val winnersJson = JSONArray(winnersStr)
            for (i in 0 until winnersJson.length()) {
                val j = winnersJson.getJSONObject(i)
                val w = WinningToken(
                    mint = j.getString("mint"),
                    symbol = j.getString("symbol"),
                    name = j.optString("name", j.getString("symbol")),
                    pnlPercent = j.getDouble("pnlPercent"),
                    peakPnl = j.optDouble("peakPnl", j.getDouble("pnlPercent")),
                    entryMcap = j.getDouble("entryMcap"),
                    exitMcap = j.optDouble("exitMcap", j.getDouble("entryMcap")),
                    entryLiquidity = j.getDouble("entryLiquidity"),
                    holdTimeMinutes = j.getInt("holdTimeMinutes"),
                    buyPercent = j.getDouble("buyPercent"),
                    timestamp = j.getLong("timestamp"),
                    source = j.getString("source"),
                    phase = j.getString("phase"),
                    timesTraded = j.optInt("timesTraded", 1),
                    totalPnl = j.optDouble("totalPnl", j.getDouble("pnlPercent")),
                    // V5.0.6123 — full trade context (backward-compatible optDouble/optString)
                    entryPrice = j.optDouble("entryPrice", 0.0),
                    exitPrice = j.optDouble("exitPrice", 0.0),
                    costSol = j.optDouble("costSol", 0.0),
                    pnlSol = j.optDouble("pnlSol", 0.0),
                    buyRoute = j.optString("buyRoute", ""),
                    sellRoute = j.optString("sellRoute", ""),
                    launchPlatform = j.optString("launchPlatform", ""),
                    entryScore = j.optDouble("entryScore", 0.0),
                    entryConfidence = j.optDouble("entryConfidence", 0.0),
                    lane = j.optString("lane", ""),
                    trader = j.optString("trader", ""),
                    setupQuality = j.optString("setupQuality", ""),
                    marketRegime = j.optString("marketRegime", ""),
                    maxDrawdownPct = j.optDouble("maxDrawdownPct", 0.0),
                    timeToPeakMinutes = j.optDouble("timeToPeakMinutes", 0.0),
                    exitReason = j.optString("exitReason", ""),
                    volatility = j.optDouble("volatility", 0.0),
                    holderCount = j.optInt("holderCount", 0),
                    holderGrowthRate = j.optDouble("holderGrowthRate", 0.0),
                    topHolderPct = j.optDouble("topHolderPct", 0.0),
                    devWalletPct = j.optDouble("devWalletPct", 0.0),
                    bondingCurveProgress = j.optDouble("bondingCurveProgress", 0.0),
                    rugcheckScore = j.optDouble("rugcheckScore", 0.0),
                    emaFanState = j.optString("emaFanState", ""),
                    tokenAgeMinutes = j.optDouble("tokenAgeMinutes", 0.0),
                    volumeUsd = j.optDouble("volumeUsd", 0.0),
                    creatorAddress = j.optString("creatorAddress", ""),
                )
                if (sanePersistedWinner4508(w)) {
                    winningTokens[w.mint] = w
                } else {
                    persistedWinnerQuarantine4508++
                }
            }

            val patternsJson = JSONObject(patternsStr)
            val patternTypeKeys = patternsJson.keys()
            while (patternTypeKeys.hasNext()) {
                val type = patternTypeKeys.next()
                val typeJson = patternsJson.getJSONObject(type)
                val typeMap = ConcurrentHashMap<String, PatternStats>()
                val valueKeys = typeJson.keys()
                while (valueKeys.hasNext()) {
                    val value = valueKeys.next()
                    val statsJson = typeJson.getJSONObject(value)
                    val loadedStats = PatternStats(
                        wins = statsJson.getInt("wins"),
                        losses = statsJson.getInt("losses"),
                        totalPnl = statsJson.optDouble("totalPnl", 0.0),
                        avgWinPnl = statsJson.optDouble("avgWinPnl", 0.0),
                    )
                    val shadowSourcePattern4584 = type.equals("source", ignoreCase = true) && isShadowOrSimulatedSource(value)
                    if (!shadowSourcePattern4584 && sanePatternStats(loadedStats)) {
                        typeMap[value] = loadedStats
                    } else {
                        persistedPatternQuarantine4508++
                        if (shadowSourcePattern4584) try { SourceChokeDiagnostics4584.learningQuarantined("PERSISTED_SOURCE_PATTERN_SHADOW", "type=$type value=${value.take(80)}") } catch (_: Throwable) {}
                    }
                }
                if (typeMap.isNotEmpty()) patterns[type] = typeMap
            }

            val creatorsJson = JSONObject(creatorsStr)
            val creatorKeys = creatorsJson.keys()
            while (creatorKeys.hasNext()) {
                val creator = creatorKeys.next()
                val statsJson = creatorsJson.getJSONObject(creator)
                val loadedStats = PatternStats(
                    wins = statsJson.getInt("wins"),
                    losses = statsJson.getInt("losses"),
                    totalPnl = statsJson.optDouble("totalPnl", 0.0),
                    avgWinPnl = statsJson.optDouble("avgWinPnl", 0.0),
                )
                if (sanePatternStats(loadedStats)) {
                    creatorStats[creator] = loadedStats
                } else {
                    persistedCreatorQuarantine4508++
                }
            }

            val tokenStatsJson = JSONObject(tokenStatsStr)
            val tokenKeys = tokenStatsJson.keys()
            while (tokenKeys.hasNext()) {
                val mint = tokenKeys.next()
                val statsJson = tokenStatsJson.getJSONObject(mint)
                val loadedStats = TokenStats(
                    wins = statsJson.getInt("wins"),
                    losses = statsJson.getInt("losses"),
                    totalPnl = statsJson.optDouble("totalPnl", 0.0),
                    bestPnl = statsJson.optDouble("bestPnl", Double.NEGATIVE_INFINITY),
                    lastPnl = statsJson.optDouble("lastPnl", 0.0),
                    lastSeen = statsJson.optLong("lastSeen", 0L),
                )
                if (saneTokenStats(loadedStats)) {
                    tokenStats[mint] = loadedStats
                } else {
                    persistedTokenStatsQuarantine4508++
                }
            }

            // Backfill tokenStats from winners if older storage had no token stats saved
            if (tokenStats.isEmpty() && winningTokens.isNotEmpty()) {
                winningTokens.values.forEach { winner ->
                    tokenStats[winner.mint] = TokenStats(
                        wins = winner.timesTraded.coerceAtLeast(1),
                        losses = 0,
                        totalPnl = winner.totalPnl,
                        bestPnl = winner.peakPnl,
                        lastPnl = winner.pnlPercent,
                        lastSeen = winner.timestamp,
                    )
                }
            }

            val repaired4508 = persistedWinnerQuarantine4508 + persistedPatternQuarantine4508 + persistedTokenStatsQuarantine4508 + persistedCreatorQuarantine4508
            if (repaired4508 > 0) {
                try { PipelineHealthCollector.labelInc("TOKEN_WIN_MEMORY_PERSISTED_POISON_PURGED_4508") } catch (_: Throwable) {}
                try {
                    ForensicLogger.lifecycle(
                        "TOKEN_WIN_MEMORY_PERSISTED_POISON_PURGED_4508",
                        "winners=$persistedWinnerQuarantine4508 patterns=$persistedPatternQuarantine4508 tokenStats=$persistedTokenStatsQuarantine4508 creators=$persistedCreatorQuarantine4508",
                    )
                } catch (_: Throwable) {}
                save()
            }
        } catch (e: Exception) {
            ErrorLogger.error("TokenWinMemory", "Load failed: ${e.message}")
        }
    }

    fun reset() {
        winningTokens.clear()
        patterns.clear()
        creatorStats.clear()
        tokenStats.clear()
        save()
        ErrorLogger.info("TokenWinMemory", "🔄 Reset all memory")
    }

    private fun isWinPnl(pnlPercent: Double): Boolean = pnlPercent >= WIN_THRESHOLD_PCT

    private fun isLossPnl(pnlPercent: Double): Boolean = pnlPercent <= LOSS_THRESHOLD_PCT
}
