package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.FluidLearningAI
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * BehaviorLearning — Separate learning layers for good vs bad trading behavior.
 *
 * Decisive-trade classification:
 * - WIN    = pnlPct >= 0.5
 * - LOSS   = pnlPct <= -2.0
 * - SCRATCH = between those thresholds (ignored)
 */
object BehaviorLearning {

    private const val TAG = "BehaviorLearn"

    private const val MAX_PATTERNS_PER_LAYER = 5000
    private const val MIN_OCCURRENCES_RELIABLE = 5
    private const val MIN_CONFIDENCE_TO_USE = 0.60

    private const val WIN_THRESHOLD_PCT = 1.0  // V5.9.320: unified — matches Education/FluidLearning/all traders
    private const val LOSS_THRESHOLD_PCT = -2.0

    // Self-healing
    private const val HEALTH_CHECK_INTERVAL_MS = 30 * 60 * 1000L
    private const val MIN_TRADES_FOR_HEALTH = 500  // V5.9.683-FIX: don't self-clear during bootstrap (<500 canonical outcomes)
    private const val CRITICAL_BAD_RATIO = 5.0  // V5.9.683-FIX: was 3.0 — triggered at 25% WR, wiped patterns every 30 min during bootstrap
    private const val WARNING_BAD_RATIO = 3.5  // V5.9.683-FIX: was 2.0 (too aggressive during learning phase)

    @Volatile
    private var lastHealthCheck = 0L

    @Volatile
    private var consecutiveBadPeriods = 0

    private val totalGoodRecorded = AtomicInteger(0)
    private val totalBadRecorded = AtomicInteger(0)
    // V5.9.745 — scratch counter. Scratch trades (PnL in [-2%, +1%]) used to
    // early-return from recordTrade(), leaving BehaviorLearning.tradeCount
    // stuck ~67% below the canonical bus (operator audit: canonical=600 vs
    // behavior=198, Δ=-402). Scratches DON'T go into good/bad pattern memory
    // (they aren't signal), but they DO count toward total-trades-seen so
    // WalletTruthDigest reflects reality and self-healing thresholds
    // (MIN_TRADES_FOR_HEALTH etc) fire on the correct sample-size.
    private val totalScratchRecorded = AtomicInteger(0)
    // V5.9.790 — operator audit Critical Fix 5: track legacy direct
    // recordTrade() invocations separately from canonical bus writes. The
    // operator's spec wants the canonical bus to be the ONLY writer of
    // pattern memory (so we never train on feature-poor samples). Legacy
    // direct calls now only increment counters for compatibility / drift
    // visibility unless `strategyLearningFromLegacy` is explicitly flipped.
    private val totalLegacyDirectRecorded = AtomicInteger(0)
    @Volatile
    var strategyLearningFromLegacy: Boolean = false       // BUS_ONLY by default

    fun getLegacyDirectRecorded(): Int = totalLegacyDirectRecorded.get()

    data class BehaviorPattern(
        val patternId: String,
        val timestamp: Long = System.currentTimeMillis(),

        // Entry
        val entryScore: Int,
        val entryPhase: String,
        val setupQuality: String,
        val tradingMode: String,

        // Market
        val marketSentiment: String,
        val volatilityLevel: String,
        val volumeSignal: String,

        // Token
        val liquidityBucket: String,
        val mcapBucket: String,
        val holderConcentration: String,
        val rugcheckScore: Int,

        // Timing
        val hourOfDay: Int,
        val dayOfWeek: Int,
        val holdTimeMinutes: Int,

        // Outcome
        val pnlPct: Double,
        val isWin: Boolean,
        val outcomeCategory: String,
    ) {
        fun getSignature(): String {
            return "${setupQuality}_${tradingMode}_${liquidityBucket}"
        }

        fun getBroadSignature(): String {
            return "${tradingMode}_${liquidityBucket}"
        }
    }

    data class PatternStats(
        val signature: String,
        var occurrences: Int = 0,
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnlPct: Double = 0.0,
        var avgHoldMinutes: Double = 0.0,
        var lastSeen: Long = 0L,
    ) {
        val winRate: Double
            get() = if (occurrences > 0) wins.toDouble() / occurrences * 100.0 else 0.0

        val avgPnl: Double
            get() = if (occurrences > 0) totalPnlPct / occurrences else 0.0

        val confidence: Double
            get() = minOf(occurrences / 10.0, 1.0)

        val isReliable: Boolean
            get() = occurrences >= MIN_OCCURRENCES_RELIABLE
    }

    data class BehaviorEvaluation(
        val scoreAdjustment: Int = 0,
        val confidence: Double = 0.0,
        val recommendation: BehaviorRecommendation = BehaviorRecommendation.NEUTRAL,
        val reasons: List<String> = emptyList(),
        val goodPatternMatch: PatternStats? = null,
        val badPatternMatch: PatternStats? = null,
    )

    enum class BehaviorRecommendation {
        STRONG_BUY,
        BUY,
        NEUTRAL,
        AVOID,
        STRONG_AVOID,
    }

    data class BehaviorInsights(
        val totalGoodPatterns: Int = 0,
        val totalBadPatterns: Int = 0,
        val topGoodPatterns: List<PatternStats> = emptyList(),
        val topBadPatterns: List<PatternStats> = emptyList(),
        val bestTradingMode: String = "UNKNOWN",
        val worstTradingMode: String = "UNKNOWN",
        val bestLiquidityBucket: String = "UNKNOWN",
    )

    data class HealthStatus(
        val status: String,
        val goodCount: Int,
        val badCount: Int,
        val badRatio: Double,
        val effectiveWinRate: Double,
        val consecutiveBadPeriods: Int,
        val goodPatternsCount: Int,
        val badPatternsCount: Int,
    ) {
        fun summary(): String {
            return "$status | Win:$goodCount Loss:$badCount | Patterns: ✅$goodPatternsCount ❌$badPatternsCount"
        }
    }

    private val goodPatterns = ConcurrentHashMap<String, MutableList<BehaviorPattern>>()
    private val badPatterns = ConcurrentHashMap<String, MutableList<BehaviorPattern>>()

    private val goodStats = ConcurrentHashMap<String, PatternStats>()
    private val badStats = ConcurrentHashMap<String, PatternStats>()

    /**
     * Record a trade outcome.
     * Scratch trades are ignored.
     *
     * V5.9.790 — operator audit Critical Fix 5:
     *   Legacy direct callers (Executor.kt:8221 + 10556) invoke this with
     *   the local sentiment/volatility/volume snapshot they can scrape from
     *   TokenState — which is feature-poor compared to the canonical bus
     *   payload (CandidateFeatures with venue/route/safetyTier/holderConc
     *   buckets, etc.). To prevent feature-poor samples from polluting
     *   pattern memory and to make the canonical bus the single writer
     *   of strategy patterns, this method now ONLY increments the
     *   compatibility counter when `strategyLearningFromLegacy == false`
     *   (which is the default — see operator audit point 5). Pattern memory
     *   writes happen exclusively through `onCanonicalOutcome()`.
     *
     *   Flip `strategyLearningFromLegacy = true` from a debug/settings entry
     *   to fall back to the legacy behaviour during canonical bridge issues.
     */
    fun recordTrade(
        entryScore: Int,
        entryPhase: String,
        setupQuality: String,
        tradingMode: String,
        marketSentiment: String,
        volatilityLevel: String,
        volumeSignal: String,
        liquidityUsd: Double,
        mcapUsd: Double,
        holderTopPct: Double,
        rugcheckScore: Int,
        hourOfDay: Int,
        dayOfWeek: Int,
        holdTimeMinutes: Int,
        pnlPct: Double,
    ) {
        try {
            // V5.9.790 — compatibility counter ALWAYS bumps so dashboards
            // and self-healing thresholds keep working unchanged.
            totalLegacyDirectRecorded.incrementAndGet()
            if (!strategyLearningFromLegacy) {
                // Audit Critical Fix 5: pattern memory is bus-only by default.
                // Counter only — do not mutate goodPatterns / badPatterns.
                return
            }
            val isWin = isWin(pnlPct)
            val isLoss = isLoss(pnlPct)

            // V5.9.745 — count scratch trades, don't silently drop them.
            // Previously this early-returned on any trade with PnL in
            // [-2%, +1%], dropping ~67% of meme trades from the counter.
            // Scratches aren't signal for good/bad pattern memory but they
            // ARE settled trades and must increment total-seen so self-
            // healing operates on real sample size.
            if (!isWin && !isLoss) {
                totalScratchRecorded.incrementAndGet()
                return
            }

            val isBigWin = pnlPct >= 100.0
            val isBigLoss = pnlPct <= -15.0

            val outcomeCategory = when {
                pnlPct >= 100.0 -> "BIG_WIN"
                pnlPct >= 25.0 -> "SMALL_WIN"
                pnlPct >= WIN_THRESHOLD_PCT -> "WIN"
                pnlPct <= -15.0 -> "BIG_LOSS"
                pnlPct <= LOSS_THRESHOLD_PCT -> "LOSS"
                else -> "SCRATCH"
            }

            val pattern = BehaviorPattern(
                patternId = "${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}",
                entryScore = entryScore,
                entryPhase = entryPhase,
                setupQuality = setupQuality,
                tradingMode = tradingMode,
                marketSentiment = marketSentiment,
                volatilityLevel = volatilityLevel,
                volumeSignal = volumeSignal,
                liquidityBucket = getLiquidityBucket(liquidityUsd),
                mcapBucket = getMcapBucket(mcapUsd),
                holderConcentration = getHolderConcentration(holderTopPct),
                rugcheckScore = rugcheckScore,
                hourOfDay = hourOfDay,
                dayOfWeek = dayOfWeek,
                holdTimeMinutes = holdTimeMinutes,
                pnlPct = pnlPct,
                isWin = isWin,
                outcomeCategory = outcomeCategory,
            )

            val exactSignature = pattern.getSignature()
            val broadSignature = pattern.getBroadSignature()

            if (isWin) {
                recordGoodPattern(pattern, exactSignature)
                recordGoodPattern(pattern, broadSignature)
                totalGoodRecorded.incrementAndGet()

                if (isBigWin) {
                    ErrorLogger.info(TAG, "✅ BIG WIN pattern recorded: $exactSignature | +${pnlPct.toInt()}%")
                }
            } else {
                recordBadPattern(pattern, exactSignature)
                recordBadPattern(pattern, broadSignature)
                totalBadRecorded.incrementAndGet()

                if (isBigLoss) {
                    // V5.9.662c — operator: log spam every ~5s on paper
                    // rugfest. Demoted from info → debug; pattern is still
                    // recorded above via recordBadPattern().
                    ErrorLogger.debug(TAG, "❌ BIG LOSS pattern recorded: $exactSignature | ${pnlPct.toInt()}%")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordTrade error: ${e.message}")
        }
    }

    private fun recordGoodPattern(pattern: BehaviorPattern, signature: String) {
        val list = goodPatterns.getOrPut(signature) { mutableListOf() }
        synchronized(list) {
            list.add(pattern)
            while (list.size > 100) list.removeAt(0)
        }

        val stats = goodStats.getOrPut(signature) { PatternStats(signature = signature) }
        stats.occurrences++
        stats.wins++
        stats.totalPnlPct += weightedPnl(pattern.pnlPct)
        stats.avgHoldMinutes =
            ((stats.avgHoldMinutes * (stats.occurrences - 1)) + pattern.holdTimeMinutes) / stats.occurrences
        stats.lastSeen = pattern.timestamp
    }

    private fun recordBadPattern(pattern: BehaviorPattern, signature: String) {
        val list = badPatterns.getOrPut(signature) { mutableListOf() }
        synchronized(list) {
            list.add(pattern)
            while (list.size > 100) list.removeAt(0)
        }

        val stats = badStats.getOrPut(signature) { PatternStats(signature = signature) }
        stats.occurrences++
        stats.losses++
        stats.totalPnlPct += pattern.pnlPct
        stats.avgHoldMinutes =
            ((stats.avgHoldMinutes * (stats.occurrences - 1)) + pattern.holdTimeMinutes) / stats.occurrences
        stats.lastSeen = pattern.timestamp
    }

    /**
     * Evaluate a new setup against learned behavior.
     */
    fun evaluate(
        entryPhase: String,
        setupQuality: String,
        tradingMode: String,
        liquidityUsd: Double,
        volumeSignal: String,
    ): BehaviorEvaluation {
        return try {
            val exactSignature = "${setupQuality}_${tradingMode}_${getLiquidityBucket(liquidityUsd)}"
            val broadSignature = "${tradingMode}_${getLiquidityBucket(liquidityUsd)}"

            val goodMatch = goodStats[exactSignature] ?: goodStats[broadSignature]
            val badMatch = badStats[exactSignature] ?: badStats[broadSignature]

            var scoreAdjust = 0.0
            var confidence = 0.0
            val reasons = mutableListOf<String>()

            if (goodMatch != null && goodMatch.isReliable) {
                val goodBoost = (goodMatch.winRate * 0.15) * goodMatch.confidence
                scoreAdjust += goodBoost
                confidence = maxOf(confidence, goodMatch.confidence)
                reasons.add("✅ Matches winning pattern (${goodMatch.winRate.toInt()}% win, ${goodMatch.occurrences} trades)")
            }

            if (badMatch != null && badMatch.isReliable) {
                val lossRate = 100.0 - badMatch.winRate
                val penaltyMultiplier = when {
                    badMatch.confidence > 0.8 -> 0.25
                    badMatch.confidence > 0.6 -> 0.18
                    else -> 0.10
                }
                scoreAdjust -= (lossRate * penaltyMultiplier)
                confidence = maxOf(confidence, badMatch.confidence)
                reasons.add("❌ Matches losing pattern (${lossRate.toInt()}% loss, ${badMatch.occurrences} trades)")
            }

            val recommendation = when {
                scoreAdjust > 12 && confidence >= MIN_CONFIDENCE_TO_USE -> BehaviorRecommendation.STRONG_BUY
                scoreAdjust > 4 && confidence >= MIN_CONFIDENCE_TO_USE -> BehaviorRecommendation.BUY
                scoreAdjust < -18 && confidence >= MIN_CONFIDENCE_TO_USE -> BehaviorRecommendation.STRONG_AVOID
                scoreAdjust < -8 && confidence >= MIN_CONFIDENCE_TO_USE -> BehaviorRecommendation.AVOID
                else -> BehaviorRecommendation.NEUTRAL
            }

            BehaviorEvaluation(
                scoreAdjustment = scoreAdjust.toInt(),
                confidence = confidence,
                recommendation = recommendation,
                reasons = reasons,
                goodPatternMatch = goodMatch,
                badPatternMatch = badMatch,
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "evaluate error: ${e.message}")
            BehaviorEvaluation()
        }
    }

    fun getTopGoodPatterns(limit: Int = 10): List<PatternStats> {
        return try {
            goodStats.values
                .filter { it.isReliable }
                .sortedByDescending { it.avgPnl }
                .take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getTopBadPatterns(limit: Int = 10): List<PatternStats> {
        return try {
            badStats.values
                .filter { it.isReliable }
                .sortedBy { it.avgPnl }
                .take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getInsights(): BehaviorInsights {
        return try {
            val topGood = getTopGoodPatterns(5)
            val topBad = getTopBadPatterns(5)

            val modeWins = goodStats.values
                .groupBy { it.signature.split("_").getOrNull(1) ?: "UNKNOWN" }
                .mapValues { entry -> entry.value.sumOf { it.wins } }

            val modeLosses = badStats.values
                .groupBy { it.signature.split("_").getOrNull(1) ?: "UNKNOWN" }
                .mapValues { entry -> entry.value.sumOf { it.losses } }

            val liqWins = goodStats.values
                .groupBy { it.signature.split("_").getOrNull(2) ?: "UNKNOWN" }
                .mapValues { entry -> entry.value.sumOf { it.wins } }

            BehaviorInsights(
                totalGoodPatterns = totalGoodRecorded.get(),
                totalBadPatterns = totalBadRecorded.get(),
                topGoodPatterns = topGood,
                topBadPatterns = topBad,
                bestTradingMode = modeWins.maxByOrNull { it.value }?.key ?: "UNKNOWN",
                worstTradingMode = modeLosses.maxByOrNull { it.value }?.key ?: "UNKNOWN",
                bestLiquidityBucket = liqWins.maxByOrNull { it.value }?.key ?: "UNKNOWN",
            )
        } catch (_: Exception) {
            BehaviorInsights()
        }
    }

    /**
     * Hard veto for FDG.
     */
    fun shouldHardBlock(
        entryPhase: String,
        setupQuality: String,
        tradingMode: String,
        liquidityUsd: Double,
        volumeSignal: String,
    ): String? {
        return try {
            val exactSignature = "${setupQuality}_${tradingMode}_${getLiquidityBucket(liquidityUsd)}"
            val broadSignature = "${tradingMode}_${getLiquidityBucket(liquidityUsd)}"

            val badMatch = badStats[exactSignature] ?: badStats[broadSignature]
            if (badMatch != null && badMatch.isReliable && badMatch.confidence >= 0.8) {
                val lossRate = 100.0 - badMatch.winRate
                if (lossRate >= 80.0 && badMatch.occurrences >= 5) {
                    return "BEHAVIOR_HARD_BLOCK: Pattern has ${lossRate.toInt()}% loss rate (${badMatch.occurrences} trades)"
                }

                if (lossRate >= 70.0) {
                    ErrorLogger.warn(TAG, "⚠️ High-loss pattern detected: $exactSignature (${lossRate.toInt()}% loss)")
                }
            }

            null
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "shouldHardBlock error: ${e.message}")
            null
        }
    }

    /**
     * Confidence-weighted FDG score adjustment.
     */
    fun getScoreAdjustment(
        entryPhase: String,
        setupQuality: String,
        tradingMode: String,
        liquidityUsd: Double,
        volumeSignal: String,
    ): Int {
        return try {
            val eval = evaluate(
                entryPhase = entryPhase,
                setupQuality = setupQuality,
                tradingMode = tradingMode,
                liquidityUsd = liquidityUsd,
                volumeSignal = volumeSignal,
            )

            val maxPenalty = -2 - (9 * FluidLearningAI.getLearningProgress()).toInt()
            (eval.scoreAdjustment * eval.confidence).toInt().coerceIn(maxPenalty, 10)
        } catch (_: Exception) {
            0
        }
    }

    fun pruneStalePatterns() {
        try {
            val now = System.currentTimeMillis()
            val staleThreshold = 24 * 60 * 60 * 1000L

            val staleGood = goodStats.filter { now - it.value.lastSeen > staleThreshold && !it.value.isReliable }
            staleGood.keys.forEach {
                goodStats.remove(it)
                goodPatterns.remove(it)
            }

            val staleBad = badStats.filter { now - it.value.lastSeen > staleThreshold && !it.value.isReliable }
            staleBad.keys.forEach {
                badStats.remove(it)
                badPatterns.remove(it)
            }

            if (staleGood.isNotEmpty() || staleBad.isNotEmpty()) {
                ErrorLogger.info(TAG, "🧹 Pruned ${staleGood.size} stale good + ${staleBad.size} stale bad patterns")
            }

            while (goodStats.size > MAX_PATTERNS_PER_LAYER) {
                val oldest = goodStats.minByOrNull { it.value.lastSeen }?.key
                if (oldest != null) {
                    goodStats.remove(oldest)
                    goodPatterns.remove(oldest)
                } else {
                    break
                }
            }

            while (badStats.size > MAX_PATTERNS_PER_LAYER) {
                val oldest = badStats.minByOrNull { it.value.lastSeen }?.key
                if (oldest != null) {
                    badStats.remove(oldest)
                    badPatterns.remove(oldest)
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "pruneStalePatterns error: ${e.message}")
        }
    }

    fun decayPatternWeights() {
        try {
            val decayFactor = 0.95

            goodStats.values.forEach { stat ->
                if (stat.occurrences > 2) {
                    stat.occurrences = (stat.occurrences * decayFactor).toInt().coerceAtLeast(2)
                    stat.wins = (stat.wins * decayFactor).toInt().coerceAtLeast(0)
                }
            }

            badStats.values.forEach { stat ->
                if (stat.occurrences > 2) {
                    stat.occurrences = (stat.occurrences * decayFactor).toInt().coerceAtLeast(2)
                    stat.losses = (stat.losses * decayFactor).toInt().coerceAtLeast(0)
                }
            }

            ErrorLogger.debug(TAG, "📉 Decayed pattern weights (factor=$decayFactor)")
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "decayPatternWeights error: ${e.message}")
        }
    }

    // V5.9.745 — tradeCount now includes scratch settlements so the operator
    // dashboard's WalletTruthDigest reflects every settled trade. WR remains
    // calculated only over graded (win/loss) trades — scratches are excluded
    // from the denominator because they would artificially deflate the WR.
    //
    // V5.9.802 — operator audit Fix (c): drift hunt. Forensic build-5.0.2742
    // showed BehaviorLearning.tradeCount=49 vs canonical=3805 (Δ=-3756).
    // BehaviorLearning is gated by feature richness — incomplete-feature
    // outcomes never increment good/bad/scratch counters, while the canonical
    // bus increments on EVERY outcome regardless. The drift is by design.
    // Surface a canonical-aligned getter so the dashboard / cross-checkers
    // can read the bus-aligned count without breaking the WR/Pattern logic
    // that intentionally only counts feature-rich outcomes.
    fun getTradeCount(): Int = totalGoodRecorded.get() + totalBadRecorded.get() + totalScratchRecorded.get()
    fun getCanonicalAlignedTradeCount(): Int {
        // V5.9.804: switch to settledWins+settledLosses to match the
        // journal/UI round-trip trade count (canonicalOutcomesTotal
        // double-counts BUY+SELL legs).
        val canonical = try {
            (com.lifecyclebot.engine.CanonicalLearningCounters.settledWins.get() +
             com.lifecyclebot.engine.CanonicalLearningCounters.settledLosses.get()).toInt()
        } catch (_: Throwable) { 0 }
        return if (canonical > 0) canonical else getTradeCount()
    }
    fun getWinLossCount(): Int = totalGoodRecorded.get() + totalBadRecorded.get()
    fun getScratchCount(): Int = totalScratchRecorded.get()

    fun getWinRate(): Double {
        val winLoss = getWinLossCount()  // exclude scratches from WR denominator
        return if (winLoss > 0) totalGoodRecorded.get().toDouble() / winLoss * 100.0 else 0.0
    }

    fun selfHealingCheck(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHealthCheck < HEALTH_CHECK_INTERVAL_MS) return false
        lastHealthCheck = now

        return try {
            val goodCount = totalGoodRecorded.get()
            val badCount = totalBadRecorded.get()
            val total = goodCount + badCount

            if (total < MIN_TRADES_FOR_HEALTH) return false

            val badRatio = if (goodCount > 0) badCount.toDouble() / goodCount else badCount.toDouble()

            val recentBadLossRate = badStats.values
                .filter { it.isReliable }
                .map { 100.0 - it.winRate }
                .average()
                .takeIf { !it.isNaN() } ?: 50.0

            val isCritical = badRatio >= CRITICAL_BAD_RATIO && recentBadLossRate >= 60.0
            val isWarning = badRatio >= WARNING_BAD_RATIO && recentBadLossRate >= 50.0

            when {
                isCritical -> {
                    consecutiveBadPeriods++
                    if (consecutiveBadPeriods >= 2) {
                        ErrorLogger.warn(
                            TAG,
                            "🚨 SELF-HEALING: Critical bad ratio (${"%.1f".format(badRatio)}x), clearing behavior data after $consecutiveBadPeriods bad periods"
                        )
                        clear()
                        true
                    } else {
                        ErrorLogger.warn(
                            TAG,
                            "⚠️ HEALTH WARNING: Bad ratio ${"%.1f".format(badRatio)}x (bad=$badCount good=$goodCount) - period $consecutiveBadPeriods/2"
                        )
                        false
                    }
                }

                isWarning -> {
                    ErrorLogger.info(TAG, "📊 Health check: Elevated bad ratio ${"%.1f".format(badRatio)}x - monitoring")
                    false
                }

                else -> {
                    if (consecutiveBadPeriods > 0) {
                        ErrorLogger.info(TAG, "✅ Health recovered: Bad ratio ${"%.1f".format(badRatio)}x (was critical)")
                    }
                    consecutiveBadPeriods = 0
                    false
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "selfHealingCheck error: ${e.message}")
            false
        }
    }

    fun getHealthStatus(): HealthStatus {
        val goodCount = totalGoodRecorded.get()
        val badCount = totalBadRecorded.get()
        val total = goodCount + badCount

        val badRatio = if (goodCount > 0) badCount.toDouble() / goodCount else badCount.toDouble()
        val winRate = if (total > 0) goodCount.toDouble() / total * 100.0 else 0.0

        val status = when {
            total < MIN_TRADES_FOR_HEALTH -> "BOOTSTRAP"
            badRatio >= CRITICAL_BAD_RATIO -> "CRITICAL"
            badRatio >= WARNING_BAD_RATIO -> "WARNING"
            winRate >= 40.0 -> "EXCELLENT"
            winRate >= 30.0 -> "HEALTHY"
            else -> "LEARNING"
        }

        return HealthStatus(
            status = status,
            goodCount = goodCount,
            badCount = badCount,
            badRatio = badRatio,
            effectiveWinRate = winRate,
            consecutiveBadPeriods = consecutiveBadPeriods,
            goodPatternsCount = goodStats.size,
            badPatternsCount = badStats.size,
        )
    }

    fun toJson(): JSONObject {
        return try {
            JSONObject().apply {
                put("totalGood", totalGoodRecorded.get())
                put("totalBad", totalBadRecorded.get())
                put("totalScratch", totalScratchRecorded.get())  // V5.9.745

                val goodArray = JSONArray()
                goodStats.values.filter { it.isReliable }.forEach { stat ->
                    goodArray.put(
                        JSONObject().apply {
                            put("sig", stat.signature)
                            put("n", stat.occurrences)
                            put("w", stat.wins)
                            put("l", stat.losses)
                            put("pnl", stat.totalPnlPct)
                            put("hold", stat.avgHoldMinutes)
                            put("lastSeen", stat.lastSeen)
                        }
                    )
                }
                put("goodStats", goodArray)

                val badArray = JSONArray()
                badStats.values.filter { it.isReliable }.forEach { stat ->
                    badArray.put(
                        JSONObject().apply {
                            put("sig", stat.signature)
                            put("n", stat.occurrences)
                            put("w", stat.wins)
                            put("l", stat.losses)
                            put("pnl", stat.totalPnlPct)
                            put("hold", stat.avgHoldMinutes)
                            put("lastSeen", stat.lastSeen)
                        }
                    )
                }
                put("badStats", badArray)
            }
        } catch (_: Exception) {
            JSONObject()
        }
    }

    fun fromJson(json: JSONObject) {
        try {
            clear()

            totalGoodRecorded.set(json.optInt("totalGood", 0))
            totalBadRecorded.set(json.optInt("totalBad", 0))
            totalScratchRecorded.set(json.optInt("totalScratch", 0))  // V5.9.745

            json.optJSONArray("goodStats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val sig = obj.optString("sig", "")
                    if (sig.isBlank()) continue

                    goodStats[sig] = PatternStats(
                        signature = sig,
                        occurrences = obj.optInt("n", 0),
                        wins = obj.optInt("w", 0),
                        losses = obj.optInt("l", 0),
                        totalPnlPct = obj.optDouble("pnl", 0.0),
                        avgHoldMinutes = obj.optDouble("hold", 0.0),
                        lastSeen = obj.optLong("lastSeen", System.currentTimeMillis()),
                    )
                }
            }

            json.optJSONArray("badStats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val sig = obj.optString("sig", "")
                    if (sig.isBlank()) continue

                    badStats[sig] = PatternStats(
                        signature = sig,
                        occurrences = obj.optInt("n", 0),
                        wins = obj.optInt("w", 0),
                        losses = obj.optInt("l", 0),
                        totalPnlPct = obj.optDouble("pnl", 0.0),
                        avgHoldMinutes = obj.optDouble("hold", 0.0),
                        lastSeen = obj.optLong("lastSeen", System.currentTimeMillis()),
                    )
                }
            }

            ErrorLogger.info(TAG, "📂 Loaded: ${goodStats.size} good patterns, ${badStats.size} bad patterns")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "fromJson error: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // CANONICAL BUS SETTLEMENT HOOK
    // -------------------------------------------------------------------------

    /**
     * V5.9.683-FIX: Called by CanonicalSubscribers when a settled WIN/LOSS
     * arrives on the CanonicalOutcomeBus.
     *
     * Advances totalGoodRecorded / totalBadRecorded so the selfHealingCheck()
     * count gate (MIN_TRADES_FOR_HEALTH = 500) uses canonical settled trades
     * rather than only direct-call-site counts which miss shadow/recovery paths.
     * No double-counting: CanonicalSubscribers uses LRU dedup per (tradeId, layer).
     */
    fun onCanonicalSettlement(isWin: Boolean, mint: String = "") {
        // V5.9.717 — NO-OP for counters.
        // Executor.kt calls recordTrade() for every closed trade, which already
        // increments totalGoodRecorded / totalBadRecorded. Incrementing here too
        // caused every trade to be counted TWICE (Δ=+40 observed in LearningCounterActivity).
        // This method is kept for API compatibility; do not increment counters here.
    }

    /**
     * V5.9.782 — operator audit items C & D: feed BehaviorLearning from the
     * canonical bus with FEATURE-RICH outcomes. Replaces the prior no-op
     * onCanonicalSettlement so the canonical bus is the actual learning
     * source (audit item A) rather than a counter-only router.
     *
     * Semantics:
     *   • If outcome.featuresIncomplete == true → skip strategy learning
     *     entirely (audit item J). The legacy bridge from
     *     TradeHistoryStore.publishFromLegacyTrade emits with this flag
     *     set so we don't pollute pattern memory with feature-poor samples.
     *   • If outcome.candidate is null → skip. We require the structured
     *     payload to construct a rich pattern signature.
     *   • Otherwise → build a BehaviorPattern from outcome.candidate and
     *     update goodStats/badStats with a rich, venue-aware signature.
     *
     * Dedupe: CanonicalSubscribers already gates onCanonicalOutcome via
     * the (layer, tradeId) LRU so duplicate publishes are filtered. The
     * legacy direct path BehaviorLearning.recordTrade() (called by Executor.kt
     * on every settled trade) is the ONLY other path that writes pattern memory;
     * the operator's spec wants the bus to be the eventual primary path so
     * direct-recordTrade callers can be deprecated layer-by-layer without
     * losing learning samples.
     */
    fun onCanonicalOutcome(outcome: CanonicalTradeOutcome) {
        try {
            if (outcome.featuresIncomplete) return            // audit item J: skip
            if (outcome.result != TradeResult.WIN && outcome.result != TradeResult.LOSS) return
            val features = outcome.candidate ?: return
            val pnlPct = outcome.realizedPnlPct ?: return
            val isWin = isWin(pnlPct)
            val isLoss = isLoss(pnlPct)
            if (!isWin && !isLoss) {
                totalScratchRecorded.incrementAndGet()
                return
            }
            val outcomeCategory = when {
                pnlPct >= 100.0 -> "BIG_WIN"
                pnlPct >= 25.0 -> "SMALL_WIN"
                pnlPct >= WIN_THRESHOLD_PCT -> "WIN"
                pnlPct <= -15.0 -> "BIG_LOSS"
                pnlPct <= LOSS_THRESHOLD_PCT -> "LOSS"
                else -> "SCRATCH"
            }
            val holdMinutes = outcome.holdSeconds?.let { (it / 60).toInt() } ?: 0
            val pattern = BehaviorPattern(
                patternId = outcome.tradeId,
                timestamp = outcome.exitTimeMs ?: System.currentTimeMillis(),
                entryScore = 0,                                   // canonical doesn't carry raw FDG score yet
                entryPhase = features.entryPattern,
                setupQuality = features.fdgReasonFamily.ifBlank { "STANDARD" },
                tradingMode = features.trader.ifBlank { outcome.mode.name },
                marketSentiment = features.symbolicVerdict,
                volatilityLevel = features.volVelocity,
                volumeSignal = features.buyPressure,
                liquidityBucket = features.liqBucket,
                mcapBucket = features.mcapBucket,
                holderConcentration = features.holderConcentration,
                rugcheckScore = 0,
                hourOfDay = 0,
                dayOfWeek = 0,
                holdTimeMinutes = holdMinutes,
                pnlPct = pnlPct,
                isWin = isWin,
                outcomeCategory = outcomeCategory,
            )
            // V5.9.782 — RICH signature carries venue + route + safety + age so
            // pump.fun bonding-curve setups stop colliding with raydium graduated
            // setups in the pattern table (operator audit item C).
            val richSig = richSignature(features)
            val broadSig = broadSignature(features)
            if (isWin) {
                recordGoodPattern(pattern, richSig)
                recordGoodPattern(pattern, broadSig)
                totalGoodRecorded.incrementAndGet()
            } else {
                recordBadPattern(pattern, richSig)
                recordBadPattern(pattern, broadSig)
                totalBadRecorded.incrementAndGet()
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "onCanonicalOutcome error: ${e.message?.take(100)}")
        }
    }

    /**
     * V5.9.782 — operator audit item C: rich PatternKey signature.
     * Old exact signature was `${setupQuality}_${tradingMode}_${liquidityBucket}`
     * which is nowhere near enough for meme/pump trading — bonding-curve pumps,
     * migrated PumpSwap, raydium-graduated, etc. all collided under one key.
     */
    private fun richSignature(f: CandidateFeatures): String {
        return buildString {
            append(f.assetClass.ifBlank { "?" }); append('|')
            append(f.runtimeMode.ifBlank { "?" }); append('|')
            append(f.trader.ifBlank { "?" }); append('|')
            append(f.venue.ifBlank { "?" }); append('|')
            append(f.route.ifBlank { "?" }); append('|')
            append(if (f.bondingCurveActive) "BC1" else "BC0"); append('|')
            append(if (f.migrated) "MIG1" else "MIG0"); append('|')
            append(f.ageBucket.ifBlank { "?" }); append('|')
            append(f.liqBucket.ifBlank { "?" }); append('|')
            append(f.mcapBucket.ifBlank { "?" }); append('|')
            append(f.volVelocity.ifBlank { "?" }); append('|')
            append(f.holderConcentration.ifBlank { "?" }); append('|')
            append(f.safetyTier.ifBlank { f.rugTier.ifBlank { "?" } }); append('|')
            append(f.entryPattern.ifBlank { "?" })
        }
    }

    /**
     * Broad signature collapses the rich key to its anchor dimensions so we
     * still learn when sample counts on the rich key are too thin.
     */
    private fun broadSignature(f: CandidateFeatures): String {
        return buildString {
            append(f.trader.ifBlank { "?" }); append('|')
            append(f.venue.ifBlank { "?" }); append('|')
            append(f.route.ifBlank { "?" }); append('|')
            append(f.liqBucket.ifBlank { "?" })
        }
    }

    fun clear() {
        goodPatterns.clear()
        goodStats.clear()
        badPatterns.clear()
        badStats.clear()
        totalGoodRecorded.set(0)
        totalBadRecorded.set(0)
        totalScratchRecorded.set(0)  // V5.9.745
        lastHealthCheck = 0L
        consecutiveBadPeriods = 0
        ErrorLogger.warn(TAG, "🧹 Behavior learning cleared")
    }

    fun getSummary(): String {
        val goodCount = totalGoodRecorded.get()
        val badCount = totalBadRecorded.get()
        val ratio = if (badCount > 0) goodCount.toDouble() / badCount else goodCount.toDouble()
        return "Good: $goodCount | Bad: $badCount | Ratio: ${"%.1f".format(ratio)}"
    }

    private fun weightedPnl(pnlPct: Double): Double {
        return when {
            pnlPct > 300.0 -> pnlPct * 1.5
            pnlPct > 15.0 -> pnlPct * 1.2
            else -> pnlPct
        }
    }

    private fun getLiquidityBucket(liquidityUsd: Double): String {
        return when {
            liquidityUsd < 2_000.0 -> "LIQ_TINY"
            liquidityUsd < 20_000.0 -> "LIQ_LOW"
            liquidityUsd < 50_000.0 -> "LIQ_MED"
            liquidityUsd < 100_000.0 -> "LIQ_GOOD"
            else -> "LIQ_DEEP"
        }
    }

    private fun getMcapBucket(mcapUsd: Double): String {
        return when {
            mcapUsd < 20_000.0 -> "MCAP_MICRO"
            mcapUsd < 50_000.0 -> "MCAP_TINY"
            mcapUsd < 100_000.0 -> "MCAP_SMALL"
            mcapUsd < 500_000.0 -> "MCAP_MED"
            else -> "MCAP_LARGE"
        }
    }

    private fun getHolderConcentration(topHolderPct: Double): String {
        return when {
            topHolderPct > 50.0 -> "CONC_HIGH"
            topHolderPct > 30.0 -> "CONC_MED"
            else -> "CONC_LOW"
        }
    }

    private fun isWin(pnlPct: Double): Boolean = pnlPct >= WIN_THRESHOLD_PCT

    private fun isLoss(pnlPct: Double): Boolean = pnlPct <= LOSS_THRESHOLD_PCT
}