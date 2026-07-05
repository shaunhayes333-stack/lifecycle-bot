package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6123 — SCANNER INTAKE PATTERN GATE.
 *
 * This is the bridge between the TokenLifecycleStageDetector (the comprehensive
 * scanner brain) and the actual scanner intake funnel (admitProtectedMemeIntake).
 *
 * It calls the full lifecycle assessment and translates the verdict into:
 * 1. Adjusted intake confidence
 * 2. Probation-vs-hot-watchlist routing
 * 3. Lane affinity hints
 * 4. Telemetry for forensic pipeline analysis
 *
 * All soft-shape — no hard rejects (safety gates handle that).
 */
object ScannerIntakePatternGate {
    private const val TAG = "ScannerIntakePatternGate"

    data class IntakeVerdict(
        val adjustedConfidence: Int,
        val patternScoreBias: Int,
        val probabilityBoost: Double,
        val chartShapeBias: Double,
        val recommendProbationOnly: Boolean,
        val reason: String,
        val lifecycleStage: String,
        val cheatSheetSetup: String,
        val evScore: Double,
        val recommendedLanes: Set<String>,
    )

    private data class CacheEntry(val verdict: IntakeVerdict, val ts: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private const val CACHE_TTL_MS = 30_000L

    /**
     * Full intake assessment using the TokenLifecycleStageDetector.
     */
    fun evaluate(
        mint: String,
        symbol: String,
        name: String = symbol,
        source: String,
        rawConfidence: Int,
        liquidityUsd: Double,
        marketCapUsd: Double,
        volumeH1: Double = 0.0,
        volume24h: Double = 0.0,
        poolAgeMs: Long? = null,
        holderGrowthRate: Double = 0.0,
        bondingCurveStatus: String = "UNKNOWN",
        migratedOrGraduated: Boolean = false,
        priceChange1h: Double = 0.0,
        buyPressurePct: Double = 50.0,
        allSources: Set<String> = setOf(source),
        ts: TokenState? = null,
    ): IntakeVerdict {
        val now = System.currentTimeMillis()
        val cached = cache[mint]
        if (cached != null && (now - cached.ts) < CACHE_TTL_MS) return cached.verdict

        // ── Full lifecycle assessment ───────────────────────────────────────
        val lifecycle = TokenLifecycleStageDetector.assess(
            mint = mint,
            symbol = symbol,
            name = name,
            source = source,
            rawConfidence = rawConfidence,
            marketCapUsd = marketCapUsd,
            liquidityUsd = liquidityUsd,
            volumeH1 = volumeH1,
            volume24h = volume24h,
            poolAgeMs = poolAgeMs,
            holderGrowthRate = holderGrowthRate,
            bondingCurveStatus = bondingCurveStatus,
            migratedOrGraduated = migratedOrGraduated,
            priceChange1h = priceChange1h,
            buyPressurePct = buyPressurePct,
            ts = ts,
        )

        // ── LiveProbabilityEngine lane EV adjustment ────────────────────────
        val laneProbBoost = try {
            val regime = try { MarketRegimeAI.getCurrentRegime().label } catch (_: Throwable) { "UNKNOWN" }
            val edge = LiveProbabilityEngine.forecast("STANDARD", lifecycle.compositeScore, "C", regime)
            when {
                edge.expectedPnlPct > 2.0 -> 3
                edge.expectedPnlPct < -2.0 -> -3
                else -> 0
            }
        } catch (_: Throwable) { 0 }

        val finalConfidence = (lifecycle.compositeScore + laneProbBoost).coerceIn(0, 100)

        // ── Probation routing: combine lifecycle + pattern gate signals ─────
        val recommendProbation = lifecycle.probationRecommended ||
            (lifecycle.evScore < 0.55 && rawConfidence < 60)

        val verdict = IntakeVerdict(
            adjustedConfidence = finalConfidence,
            patternScoreBias = lifecycle.confidenceAdjustment,
            probabilityBoost = laneProbBoost.toDouble(),
            chartShapeBias = lifecycle.evScore - 1.0,
            recommendProbationOnly = recommendProbation,
            reason = lifecycle.reasonChain,
            lifecycleStage = lifecycle.stage.name,
            cheatSheetSetup = lifecycle.cheatSheetSetup.name,
            evScore = lifecycle.evScore,
            recommendedLanes = lifecycle.recommendedLanes,
        )

        cache[mint] = CacheEntry(verdict, now)
        return verdict
    }

    /**
     * Evaluate a token at probation promotion time using chart shape, movement
     * patterns, and lifecycle stage. Blocks promotion for tokens in death/freefall/
     * exhaustion, and boosts promotion for tokens in breakout/accumulation.
     */
    fun shouldPromoteFromProbation(ts: TokenState): Boolean {
        // MovementPatternSignal — check chart shape
        val movement = try { MovementPatternSignal.from(ts) } catch (_: Throwable) { null }
        if (movement != null) {
            if (movement.pattern == "FREEFALL_NO_RECLAIM" || movement.pattern == "EXHAUSTION_CHASE") {
                try {
                    ForensicLogger.lifecycle(
                        "PROBATION_PATTERN_BLOCK_6123",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} pattern=${movement.pattern} conf=${movement.confidence.toInt()} — chart shape does not support promotion"
                    )
                    PipelineHealthCollector.labelInc("PROBATION_PATTERN_BLOCK_6123")
                } catch (_: Throwable) {}
                return false
            }
        }

        // Full lifecycle assessment for promotion candidates (they have history)
        val lifecycle = try {
            TokenLifecycleStageDetector.assess(
                mint = ts.mint,
                symbol = ts.symbol,
                name = ts.name,
                source = "PROBATION",
                rawConfidence = 50,
                marketCapUsd = ts.lastMcap,
                liquidityUsd = ts.lastLiquidityUsd,
                volumeH1 = 0.0,
                holderGrowthRate = ts.holderGrowthRate,
                priceChange1h = ts.lastPriceChange1h,
                buyPressurePct = ts.lastBuyPressurePct,
                ts = ts,
            )
        } catch (_: Throwable) { null }

        if (lifecycle != null) {
            // Block promotion for death-stage tokens
            if (lifecycle.stage == TokenLifecycleStageDetector.LifecycleStage.DEATH) {
                try {
                    ForensicLogger.lifecycle(
                        "PROBATION_DEATH_STAGE_BLOCK_6123",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} stage=${lifecycle.stage.name} — lifecycle stage is DEATH, no promotion"
                    )
                    PipelineHealthCollector.labelInc("PROBATION_DEATH_STAGE_BLOCK_6123")
                } catch (_: Throwable) {}
                return false
            }

            // Block promotion for distribution-stage tokens
            if (lifecycle.stage == TokenLifecycleStageDetector.LifecycleStage.DISTRIBUTION) {
                try {
                    ForensicLogger.lifecycle(
                        "PROBATION_DISTRIBUTION_BLOCK_6123",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} stage=${lifecycle.stage.name} setup=${lifecycle.cheatSheetSetup.name} — distribution stage, no promotion"
                    )
                    PipelineHealthCollector.labelInc("PROBATION_DISTRIBUTION_BLOCK_6123")
                } catch (_: Throwable) {}
                return false
            }

            // Log promotion with lifecycle context
            try {
                ForensicLogger.lifecycle(
                    "PROBATION_LIFECYCLE_PROMOTE_6123",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} stage=${lifecycle.stage.name} setup=${lifecycle.cheatSheetSetup.name} ev=${"%.2f".format(lifecycle.evScore)} goose=${lifecycle.goldenGooseVerdict} narrative=${lifecycle.narrativeCluster} — lifecycle assessment supports promotion"
                )
                PipelineHealthCollector.labelInc("PROBATION_LIFECYCLE_PROMOTE_6123")
            } catch (_: Throwable) {}
        }

        // SmartChartCache — bearish confidence check
        val bearish = try { SmartChartCache.getBearishConfidence(ts.mint) } catch (_: Throwable) { null }
        if (bearish != null && bearish >= 70.0) {
            try {
                ForensicLogger.lifecycle(
                    "PROBATION_BEARISH_CHART_BLOCK_6123",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} bearish=${bearish.toInt()} — bearish chart confidence too high for promotion"
                )
                PipelineHealthCollector.labelInc("PROBATION_BEARISH_CHART_BLOCK_6123")
            } catch (_: Throwable) {}
            return false
        }

        // PatternGoldenGoose — catastrophic tokens don't promote
        val gooseCata = try { PatternGoldenGoose.isCatastrophic(ts.name, ts.symbol) } catch (_: Throwable) { false }
        if (gooseCata) {
            try {
                ForensicLogger.lifecycle(
                    "PROBATION_GOOSE_CATASTROPHIC_BLOCK_6123",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} — PatternGoldenGoose says catastrophic, no promotion"
                )
                PipelineHealthCollector.labelInc("PROBATION_GOOSE_CATASTROPHIC_BLOCK_6123")
            } catch (_: Throwable) {}
            return false
        }

        return true
    }

    /** Clear cache for a mint. */
    fun evict(mint: String) { cache.remove(mint) }
}
