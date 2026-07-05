package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * V5.0.6123 — TOKEN LIFECYCLE STAGE DETECTOR + CHEAT SHEET ENGINE.
 * ═══════════════════════════════════════════════════════════════════════════════
 * Operator mandate: "the pattern recognition system and the probability engine
 * need to be wired into the scanner brains as well. they need to help the scanner
 * determine better candidates at the discovery source. especially helping the
 * scanner find the right tokens in the right portion or stage of the token vs
 * the tokens life cycle and current chart position, shape against our crypto
 * traders cheat sheet and pattern recognition system!"
 *
 * This module is the ULTIMATE SCANNER BRAIN — it fuses every pattern/probability
 * signal the bot has into a single lifecycle-stage-aware intake assessment:
 *
 * 1. LIFECYCLE STAGE — determines where the token is in its lifecycle
 *    (LAUNCH, ACCUMULATION, PRE_PUMP, BREAKOUT, MARKUP, DISTRIBUTION, EXHAUSTION, DEATH)
 *    from market cap, liquidity, volume, age, holder growth, price action, and
 *    bonding curve status.
 *
 * 2. CHEAT SHEET MATCHING — maps the detected stage + chart patterns + token
 *    metrics against the "crypto trader's cheat sheet" of known high-probability
 *    setups. Each setup has an EV score, recommended lane, and entry/exit style.
 *
 * 3. PATTERN FUSION — combines SmartChartScanner patterns, MovementPatternSignal,
 *    PatternClassifier probability, PatternGoldenGoose edge, MemeNarrativeAI
 *    cluster, HistoricalChartScanner recommendations, and TokenWinMemory
 *    per-theme patterns into one unified intake score.
 *
 * 4. LANE ROUTING — recommends which trading lane is best suited for this
 *    candidate based on the detected setup.
 *
 * All shaping is SOFT — no hard rejects (safety gates handle that). The output
 * is a composite score adjustment, lifecycle stage label, cheat sheet match,
 * and lane routing hint that the scanner intake uses to prioritize candidates.
 *
 * Hot-path safe: no network calls, no LLM, no blocking I/O.
 */
object TokenLifecycleStageDetector {

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE STAGES — the token's position in its market lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    enum class LifecycleStage(val display: String, val evMultiplier: Double) {
        LAUNCH("Launch / Fresh Mint", 0.85),           // Just launched, high risk, high reward
        ACCUMULATION("Accumulation", 1.15),            // Quiet building, holders growing, low vol
        PRE_PUMP("Pre-Pump Compression", 1.25),        // Coiling, volume building, about to move
        BREAKOUT("Breakout", 1.40),                    // Breaking out of range with volume
        MARKUP("Markup / Trend Up", 1.20),             // Sustained uptrend, momentum
        DISTRIBUTION("Distribution", 0.60),            // Smart money selling into retail FOMO
        EXHAUSTION("Exhaustion / Top", 0.40),          // Parabolic spike, reversal imminent
        DEATH("Death / Abandonment", 0.20),            // Continuous decline, no recovery
        UNKNOWN("Unknown / Insufficient Data", 1.0),   // Not enough data to classify
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CHEAT SHEET — the trading setups that real meme traders look for
    // ═══════════════════════════════════════════════════════════════════════════

    enum class CheatSheetSetup(val display: String, val baseEv: Double, val preferredLanes: Set<String>) {
        // ── Bullish setups ────────────────────────────────────────────────────
        FRESH_LAUNCH_WITH_NARRATIVE(
            "Fresh Launch + Strong Narrative (memetic cluster match)",
            1.30, setOf("SHITCOIN", "MOONSHOT", "STANDARD")
        ),
        ACCUMULATION_COMPRESSION(
            "Accumulation + Compression (coiling for breakout)",
            1.35, setOf("QUALITY", "STANDARD", "DIP_HUNTER")
        ),
        BREAKOUT_WITH_VOLUME(
            "Breakout + Volume Ignition (classic momentum entry)",
            1.45, setOf("MOONSHOT", "QUALITY", "STANDARD")
        ),
        PULLBACK_RECLAIM(
            "Pullback Reclaim (dip on a trending token, buyers stepping in)",
            1.25, setOf("DIP_HUNTER", "QUALITY", "STANDARD")
        ),
        CUP_AND_HANDLE(
            "Cup & Handle (classic bullish continuation)",
            1.30, setOf("QUALITY", "STANDARD", "MOONSHOT")
        ),
        BULL_FLAG_CONTINUATION(
            "Bull Flag ( consolidation after strong move, continuation)",
            1.20, setOf("QUALITY", "STANDARD", "MOONSHOT")
        ),
        VOLUME_IGNITION_SCALP(
            "Volume Ignition (sudden volume spike, momentum scalp)",
            1.15, setOf("SHITCOIN", "STANDARD", "MANIPULATED")
        ),
        GRADUATION_PLAY(
            "Pump.fun Graduation (bonding curve complete, Raydium migration)",
            1.20, setOf("SHITCOIN", "MOONSHOT", "STANDARD")
        ),
        WHALE_ACCUMULATION(
            "Whale Accumulation (large holders buying, holder growth positive)",
            1.25, setOf("QUALITY", "BLUECHIP", "STANDARD")
        ),
        NARRATIVE_SOCIAL_IGNITION(
            "Narrative + Social Ignition (memetic cluster + social velocity)",
            1.35, setOf("MOONSHOT", "SHITCOIN", "STANDARD")
        ),

        // ── Neutral / situational ─────────────────────────────────────────────
        LOW_DATA_PROBE(
            "Low Data Probe (insufficient chart, small probe)",
            0.90, setOf("SHITCOIN", "STANDARD")
        ),
        RANGE_BOUND(
            "Range Bound (no clear direction, chop risk)",
            0.85, setOf("STANDARD", "MANIPULATED")
        ),

        // ── Bearish / avoid ───────────────────────────────────────────────────
        EXHAUSTION_CHASE(
            "Exhaustion Chase (parabolic, late entry, high reversal risk)",
            0.50, setOf("SHITCOIN")  // scalp-only if anything
        ),
        DISTRIBUTION_TOP(
            "Distribution at Top (smart money exiting, retail buying)",
            0.45, emptySet()  // avoid
        ),
        FREEFALL_NO_RECLAIM(
            "Freefall (continuous decline, no buyer support)",
            0.30, emptySet()  // avoid
        ),
        DEATH_SPIRAL(
            "Death Spiral (abandoned, no volume, no holders)",
            0.15, emptySet()  // hard avoid
        ),
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPOSITE VERDICT
    // ═══════════════════════════════════════════════════════════════════════════

    data class LifecycleVerdict(
        val stage: LifecycleStage,
        val cheatSheetSetup: CheatSheetSetup,
        val compositeScore: Int,          // 0-100, adjusted confidence for intake
        val evScore: Double,              // expected value multiplier (0.15 - 1.45)
        val patternConfidence: Double,    // 0-100, how confident the pattern match is
        val narrativeCluster: String,     // memetic cluster (FROG, DOG, AI_THEME, etc.)
        val goldenGooseVerdict: String,   // GOLD, WINNER, NEUTRAL, TOXIC, CATASTROPHIC
        val chartPattern: String,         // detected chart pattern name
        val movementPattern: String,      // detected movement pattern name
        val historicalWinRate: Double,    // from HistoricalChartScanner matched patterns
        val probabilityPWin: Double,      // from PatternClassifier / LiveProbabilityEngine
        val recommendedLanes: Set<String>,// lanes best suited for this setup
        val confidenceAdjustment: Int,    // additive adjustment to raw scanner confidence
        val probationRecommended: Boolean,// if true, route to probation instead of hot watchlist
        val reasonChain: String,          // human-readable chain of reasoning
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // STAGE DETECTION — determine where the token is in its lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Detect the lifecycle stage from available intake data.
     *
     * At scanner intake, we may only have: market cap, liquidity, volume, age,
     * bonding curve status, holder growth. For probation promotion candidates
     * with TokenState, we also have candle history and price action.
     */
    fun detectStage(
        marketCapUsd: Double,
        liquidityUsd: Double,
        volumeH1: Double,
        volume24h: Double = 0.0,
        poolAgeMs: Long? = null,
        holderGrowthRate: Double = 0.0,
        bondingCurveStatus: String = "UNKNOWN",
        migratedOrGraduated: Boolean = false,
        priceChange1h: Double = 0.0,
        buyPressurePct: Double = 50.0,
        hasChartHistory: Boolean = false,
        movementPattern: String? = null,
        chartPattern: String? = null,
    ): LifecycleStage {
        val ageMinutes = poolAgeMs?.let { it / 60_000.0 } ?: Double.MAX_VALUE
        val volToLiqRatio = if (liquidityUsd > 0.0) volumeH1 / liquidityUsd else 0.0
        val isFreshMint = ageMinutes < 30.0 || (marketCapUsd < 50_000.0 && liquidityUsd < 10_000.0)

        // ── Use movement pattern if available (highest signal) ─────────────
        if (movementPattern != null) {
            return when (movementPattern) {
                "BREAKOUT_CONTINUATION" -> LifecycleStage.BREAKOUT
                "VOLUME_IGNITION" -> LifecycleStage.BREAKOUT
                "PULLBACK_RECLAIM" -> LifecycleStage.MARKUP  // pulled back but still in uptrend
                "ACCUMULATION_COMPRESSION" -> LifecycleStage.PRE_PUMP
                "EXHAUSTION_CHASE" -> LifecycleStage.EXHAUSTION
                "FREEFALL_NO_RECLAIM" -> LifecycleStage.DEATH
                else -> detectStageFromMetrics(marketCapUsd, liquidityUsd, volumeH1, volToLiqRatio,
                    ageMinutes, holderGrowthRate, buyPressurePct, priceChange1h, isFreshMint,
                    migratedOrGraduated)
            }
        }

        // ── Use chart pattern if available ─────────────────────────────────
        if (chartPattern != null) {
            val bullishPattern = chartPattern in setOf(
                "BREAKOUT", "CUP_HANDLE", "DOUBLE_BOTTOM", "BULLISH_FLAG",
                "ASCENDING_TRIANGLE", "FALLING_WEDGE", "INVERSE_HEAD_SHOULDERS",
                "BULLISH_PENNANT", "TRIPLE_BOTTOM"
            )
            val bearishPattern = chartPattern in setOf(
                "DEAD_CAT_BOUNCE", "BREAKDOWN", "DESCENDING_TRIANGLE",
                "RISING_WEDGE", "HEAD_SHOULDERS", "DOUBLE_TOP", "TRIPLE_TOP",
                "BEARISH_FLAG", "BEARISH_PENNANT"
            )
            if (bullishPattern) {
                return if (volToLiqRatio > 0.5) LifecycleStage.BREAKOUT
                else LifecycleStage.MARKUP
            }
            if (bearishPattern) {
                return if (priceChange1h < -15.0) LifecycleStage.DEATH
                else LifecycleStage.DISTRIBUTION
            }
        }

        return detectStageFromMetrics(marketCapUsd, liquidityUsd, volumeH1, volToLiqRatio,
            ageMinutes, holderGrowthRate, buyPressurePct, priceChange1h, isFreshMint,
            migratedOrGraduated)
    }

    private fun detectStageFromMetrics(
        mcap: Double, liq: Double, vol1h: Double, volLiq: Double,
        ageMin: Double, holderGrowth: Double, buyPressure: Double,
        priceChange1h: Double, isFresh: Boolean, graduated: Boolean,
    ): LifecycleStage {
        // Fresh mint — just launched
        if (isFresh && ageMin < 15.0) return LifecycleStage.LAUNCH

        // Death — no volume, declining, no holders
        if (vol1h < 100.0 && priceChange1h < -10.0 && holderGrowth < -5.0) return LifecycleStage.DEATH
        if (vol1h < 500.0 && mcap < 5_000.0 && liq < 1_000.0) return LifecycleStage.DEATH

        // Exhaustion — parabolic spike, high buy pressure, huge volume relative to liq
        if (priceChange1h > 50.0 && buyPressure > 65.0 && volLiq > 1.0) return LifecycleStage.EXHAUSTION
        if (priceChange1h > 100.0) return LifecycleStage.EXHAUSTION  // 2x+ in 1h = parabolic

        // Distribution — declining from highs, sell pressure increasing
        if (priceChange1h in -20.0..-5.0 && buyPressure < 45.0 && volLiq > 0.3) return LifecycleStage.DISTRIBUTION

        // Breakout — strong volume, positive price, buy pressure
        if (priceChange1h > 8.0 && volLiq > 0.3 && buyPressure > 55.0) return LifecycleStage.BREAKOUT

        // Markup — sustained uptrend
        if (priceChange1h in 3.0..30.0 && buyPressure > 52.0) return LifecycleStage.MARKUP

        // Pre-pump / accumulation — low volatility, holders growing, building
        if (abs(priceChange1h) < 5.0 && holderGrowth > 2.0 && volLiq < 0.3) {
            return if (holderGrowth > 10.0) LifecycleStage.PRE_PUMP
            else LifecycleStage.ACCUMULATION
        }

        // Graduation play — just graduated from Pump.fun
        if (graduated && ageMin < 120.0) return LifecycleStage.BREAKOUT

        // Default — unknown
        return LifecycleStage.UNKNOWN
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CHEAT SHEET MATCHING — map stage + patterns + metrics to a setup
    // ═══════════════════════════════════════════════════════════════════════════

    fun matchCheatSheet(
        stage: LifecycleStage,
        narrativeCluster: String = "UNKNOWN",
        goldenGooseVerdict: String = "NEUTRAL",
        chartPattern: String = "",
        movementPattern: String = "",
        volumeH1: Double = 0.0,
        liquidityUsd: Double = 0.0,
        holderGrowthRate: Double = 0.0,
        buyPressurePct: Double = 50.0,
        bondingCurveStatus: String = "UNKNOWN",
        migratedOrGraduated: Boolean = false,
        hasChartHistory: Boolean = false,
    ): CheatSheetSetup {
        // Bearish/avoid setups take priority — protect capital first
        if (stage == LifecycleStage.DEATH) return CheatSheetSetup.DEATH_SPIRAL
        if (movementPattern == "FREEFALL_NO_RECLAIM") return CheatSheetSetup.FREEFALL_NO_RECLAIM
        if (stage == LifecycleStage.EXHAUSTION || movementPattern == "EXHAUSTION_CHASE") {
            return CheatSheetSetup.EXHAUSTION_CHASE
        }
        if (stage == LifecycleStage.DISTRIBUTION) return CheatSheetSetup.DISTRIBUTION_TOP

        // Bullish setups — match the strongest signal first
        // Graduation play
        if (migratedOrGraduated && (stage == LifecycleStage.BREAKOUT || stage == LifecycleStage.LAUNCH)) {
            return CheatSheetSetup.GRADUATION_PLAY
        }

        // Breakout with volume
        if (stage == LifecycleStage.BREAKOUT) {
            return if (movementPattern == "VOLUME_IGNITION" || (volumeH1 > 0.0 && liquidityUsd > 0.0 && volumeH1 / liquidityUsd > 0.5))
                CheatSheetSetup.BREAKOUT_WITH_VOLUME
            else CheatSheetSetup.BREAKOUT_WITH_VOLUME  // breakout is breakout
        }

        // Pullback reclaim
        if (movementPattern == "PULLBACK_RECLAIM" || (stage == LifecycleStage.MARKUP && buyPressurePct > 55.0)) {
            return CheatSheetSetup.PULLBACK_RECLAIM
        }

        // Cup & handle
        if (chartPattern == "CUP_HANDLE") return CheatSheetSetup.CUP_AND_HANDLE

        // Bull flag
        if (chartPattern == "BULLISH_FLAG" || chartPattern == "BULLISH_PENNANT") {
            return CheatSheetSetup.BULL_FLAG_CONTINUATION
        }

        // Accumulation compression
        if (stage == LifecycleStage.ACCUMULATION || stage == LifecycleStage.PRE_PUMP ||
            movementPattern == "ACCUMULATION_COMPRESSION") {
            return CheatSheetSetup.ACCUMULATION_COMPRESSION
        }

        // Volume ignition scalp
        if (movementPattern == "VOLUME_IGNITION") return CheatSheetSetup.VOLUME_IGNITION_SCALP

        // Whale accumulation
        if (holderGrowthRate > 15.0 && buyPressurePct > 55.0 && liquidityUsd > 20_000.0) {
            return CheatSheetSetup.WHALE_ACCUMULATION
        }

        // Fresh launch with narrative
        if (stage == LifecycleStage.LAUNCH && narrativeCluster != "UNKNOWN" && goldenGooseVerdict != "CATASTROPHIC") {
            return CheatSheetSetup.FRESH_LAUNCH_WITH_NARRATIVE
        }

        // Narrative + social ignition
        if (narrativeCluster != "UNKNOWN" && goldenGooseVerdict == "GOLD") {
            return CheatSheetSetup.NARRATIVE_SOCIAL_IGNITION
        }

        // Low data
        if (!hasChartHistory && stage == LifecycleStage.UNKNOWN) return CheatSheetSetup.LOW_DATA_PROBE

        // Range bound
        if (stage == LifecycleStage.UNKNOWN) return CheatSheetSetup.RANGE_BOUND

        // Default — range bound
        return CheatSheetSetup.RANGE_BOUND
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FULL ASSESSMENT — the main entry point for scanner intake
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Full lifecycle + cheat sheet + pattern fusion assessment for a scanner
     * intake candidate. This is the comprehensive "scanner brain" that fuses
     * every signal the bot has.
     *
     * @param mint Token mint
     * @param symbol Token symbol
     * @param name Token name
     * @param source Scanner source
     * @param rawConfidence Original scanner confidence (0-100)
     * @param marketCapUsd Market cap in USD
     * @param liquidityUsd Liquidity in USD
     * @param volumeH1 1h volume
     * @param volume24h 24h volume
     * @param poolAgeMs Pool age in milliseconds
     * @param holderGrowthRate Holder growth rate %
     * @param bondingCurveStatus Pump.fun bonding curve status
     * @param migratedOrGraduated Whether token graduated from Pump.fun
     * @param priceChange1h 1h price change %
     * @param buyPressurePct Buy pressure %
     * @param ts Optional TokenState (for candidates with chart history, e.g. probation promotes)
     * @return LifecycleVerdict with composite score and routing
     */
    fun assess(
        mint: String,
        symbol: String,
        name: String = symbol,
        source: String,
        rawConfidence: Int,
        marketCapUsd: Double,
        liquidityUsd: Double,
        volumeH1: Double = 0.0,
        volume24h: Double = 0.0,
        poolAgeMs: Long? = null,
        holderGrowthRate: Double = 0.0,
        bondingCurveStatus: String = "UNKNOWN",
        migratedOrGraduated: Boolean = false,
        priceChange1h: Double = 0.0,
        buyPressurePct: Double = 50.0,
        ts: TokenState? = null,
    ): LifecycleVerdict {
        val reasons = mutableListOf<String>()

        // ── 1. Movement pattern (if TokenState available) ───────────────────
        val movementPattern = if (ts != null) {
            try { MovementPatternSignal.from(ts).pattern } catch (_: Throwable) { null }
        } else null

        // ── 2. Chart pattern from cache ─────────────────────────────────────
        val chartPatterns = try { SmartChartCache.getPatternNames(mint) } catch (_: Throwable) { emptyList() }
        val chartPattern = chartPatterns.firstOrNull() ?: ts?.meta?.chartPattern?.takeIf { it.isNotBlank() } ?: ""
        val hasChartHistory = ts != null && ts.history.size >= 4

        // ── 3. Lifecycle stage detection ────────────────────────────────────
        val stage = detectStage(
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
            hasChartHistory = hasChartHistory,
            movementPattern = movementPattern,
            chartPattern = chartPattern,
        )
        reasons.add("stage=${stage.name}")

        // ── 4. Narrative cluster (MemeNarrativeAI) ──────────────────────────
        val narrativeCluster = try {
            com.lifecyclebot.v3.scoring.MemeNarrativeAI.detect(symbol, name).cluster.name
        } catch (_: Throwable) { "UNKNOWN" }
        if (narrativeCluster != "UNKNOWN") reasons.add("narrative=$narrativeCluster")

        // ── 5. Golden goose verdict ─────────────────────────────────────────
        val gooseEdge = try {
            PatternGoldenGoose.edge(name, symbol)
        } catch (_: Throwable) {
            TokenWinMemory.PatternEdge(TokenWinMemory.Verdict.NEUTRAL, 0, null, 0.0, 0, null, 1.0, 0)
        }
        val gooseVerdict = gooseEdge.verdict.name
        if (gooseVerdict != "NEUTRAL") reasons.add("goose=$gooseVerdict")

        // ── 6. Cheat sheet matching ─────────────────────────────────────────
        val setup = matchCheatSheet(
            stage = stage,
            narrativeCluster = narrativeCluster,
            goldenGooseVerdict = gooseVerdict,
            chartPattern = chartPattern,
            movementPattern = movementPattern ?: "",
            volumeH1 = volumeH1,
            liquidityUsd = liquidityUsd,
            holderGrowthRate = holderGrowthRate,
            buyPressurePct = buyPressurePct,
            bondingCurveStatus = bondingCurveStatus,
            migratedOrGraduated = migratedOrGraduated,
            hasChartHistory = hasChartHistory,
        )
        reasons.add("setup=${setup.name}")

        // ── 7. PatternClassifier probability ────────────────────────────────
        val pWin = try {
            if (ts != null) {
                val feats = PatternClassifier.extract(ts)
                val boostPoints = PatternClassifier.getConfidenceBoost(feats, isPaperMode = false)
                // boostPoints is an additive confidence adjustment (e.g. +5, -3)
                // Convert to pWin estimate: base 0.50 + scaled boost
                (0.50 + boostPoints / 100.0 * 0.5).coerceIn(0.05, 0.95)
            } else {
                // Without TokenState, use basic features
                val feats = DoubleArray(14) { 0.0 }
                feats[0] = (rawConfidence - 50.0) / 50.0
                feats[6] = if (liquidityUsd > 0.0) (log10(liquidityUsd.coerceAtLeast(1.0)) - 4.0) / 2.0 else 0.0
                feats[7] = if (marketCapUsd > 0.0) (log10(marketCapUsd.coerceAtLeast(1.0)) - 5.0) / 2.0 else 0.0
                val isMeme = symbol.length <= 12 && !symbol.contains("USD", ignoreCase = true)
                feats[13] = if (isMeme) 1.0 else -1.0
                val boostPoints = PatternClassifier.getConfidenceBoost(feats, isPaperMode = false)
                (0.50 + boostPoints / 100.0 * 0.5).coerceIn(0.05, 0.95)
            }
        } catch (_: Throwable) { 0.50 }

        // ── 8. HistoricalChartScanner recommendation ────────────────────────
        val histRec = try {
            HistoricalChartScanner.getHistoricalRecommendation(
                liquidityUsd, volumeH1, 0.0
            )
        } catch (_: Throwable) { null }
        val historicalWinRate = histRec?.let {
            // HistoricalRecommendation.confidence is 0-100 probability of success
            it.confidence / 100.0
        } ?: 0.0
        if (historicalWinRate > 0.0) reasons.add("histWr=${(historicalWinRate * 100).toInt()}%")

        // ── 9. Composite EV score ───────────────────────────────────────────
        // Start with the cheat sheet base EV, then adjust with all signals
        var evScore = setup.baseEv
        evScore *= stage.evMultiplier  // stage quality
        if (gooseVerdict == "GOLD") evScore *= 1.15
        if (gooseVerdict == "WINNER") evScore *= 1.08
        if (gooseVerdict == "TOXIC") evScore *= 0.75
        if (gooseVerdict == "CATASTROPHIC") evScore *= 0.40
        if (narrativeCluster != "UNKNOWN" && gooseVerdict != "CATASTROPHIC") evScore *= 1.05
        if (historicalWinRate > 0.5) evScore *= 1.10
        if (historicalWinRate in 0.01..0.3) evScore *= 0.85
        if (pWin > 0.55) evScore *= 1.08
        if (pWin < 0.35) evScore *= 0.85
        // Chart pattern multipliers from PatternAutoTuner
        if (chartPatterns.isNotEmpty()) {
            try {
                var product = 1.0
                for (pname in chartPatterns) product *= PatternAutoTuner.getPatternMultiplier(pname)
                val geomMean = Math.pow(product, 1.0 / chartPatterns.size).coerceIn(0.5, 1.5)
                evScore *= geomMean
            } catch (_: Throwable) {}
        }
        evScore = evScore.coerceIn(0.10, 2.00)

        // ── 10. Pattern confidence (how confident are we in the assessment) ─
        var patternConfidence = 50.0
        if (movementPattern != null) patternConfidence += 20.0
        if (chartPattern.isNotBlank()) patternConfidence += 15.0
        if (narrativeCluster != "UNKNOWN") patternConfidence += 10.0
        if (gooseVerdict != "NEUTRAL") patternConfidence += 10.0
        if (historicalWinRate > 0.0) patternConfidence += 5.0
        if (hasChartHistory) patternConfidence += 10.0
        patternConfidence = patternConfidence.coerceIn(0.0, 100.0)

        // ── 11. Confidence adjustment ───────────────────────────────────────
        // Map EV score to a confidence adjustment
        // EV > 1.0 = boost, EV < 0.7 = penalty
        val confidenceAdjustment = ((evScore - 1.0) * 15.0).toInt().coerceIn(-15, 15)
        if (confidenceAdjustment != 0) reasons.add("confAdj=$confidenceAdjustment")

        // ── 12. Composite score ─────────────────────────────────────────────
        val compositeScore = (rawConfidence + confidenceAdjustment).coerceIn(0, 100)

        // ── 13. Probation recommendation ────────────────────────────────────
        val probationRecommended = evScore < 0.65 && rawConfidence < 55

        // ── 14. Recommended lanes from cheat sheet ──────────────────────────
        val recommendedLanes = setup.preferredLanes

        return LifecycleVerdict(
            stage = stage,
            cheatSheetSetup = setup,
            compositeScore = compositeScore,
            evScore = evScore,
            patternConfidence = patternConfidence,
            narrativeCluster = narrativeCluster,
            goldenGooseVerdict = gooseVerdict,
            chartPattern = chartPattern,
            movementPattern = movementPattern ?: "",
            historicalWinRate = historicalWinRate,
            probabilityPWin = pWin,
            recommendedLanes = recommendedLanes,
            confidenceAdjustment = confidenceAdjustment,
            probationRecommended = probationRecommended,
            reasonChain = reasons.joinToString(" → "),
        )
    }

    /**
     * Lightweight stage-only detection for fast path / logging.
     */
    fun quickStage(marketCapUsd: Double, liquidityUsd: Double, volumeH1: Double, poolAgeMs: Long? = null): LifecycleStage {
        return detectStage(
            marketCapUsd = marketCapUsd,
            liquidityUsd = liquidityUsd,
            volumeH1 = volumeH1,
            poolAgeMs = poolAgeMs,
        )
    }
}
