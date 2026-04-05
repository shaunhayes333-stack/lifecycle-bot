/**
 * AATE V3 Starter Pack — Single File Version (Hardened)
 * =====================================================
 *
 * Copy into Android Studio and split into packages later if needed.
 * This version is intentionally self-contained and defensive.
 *
 * Notes:
 * - Score-based architecture
 * - Hard gates only in eligibility/risk
 * - Structured process result returned by orchestrator
 * - Safer defaults/clamps to avoid weird runtime behavior
 */

package bot

import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// LIFECYCLE
// ═══════════════════════════════════════════════════════════════════════════

enum class LifecycleState {
    DISCOVERED,
    ELIGIBLE,
    SCORED,
    WATCH,
    EXECUTE_READY,
    EXECUTED,
    BLOCKED_FATAL,
    REJECTED,
    SHADOW_TRACKED,
    CLOSED,
    CLASSIFIED
}

class LifecycleManager {
    private val states = mutableMapOf<String, LifecycleState>()

    fun mark(mint: String, state: LifecycleState) {
        if (mint.isBlank()) return
        states[mint] = state
    }

    fun get(mint: String): LifecycleState? = states[mint]

    fun snapshot(): Map<String, LifecycleState> = states.toMap()
}

// ═══════════════════════════════════════════════════════════════════════════
// SCANNER
// ═══════════════════════════════════════════════════════════════════════════

enum class SourceType {
    DEX_BOOSTED,
    RAYDIUM_NEW_POOL,
    PUMP_FUN_GRADUATE,
    DEX_TRENDING
}

data class CandidateSnapshot(
    val mint: String,
    val symbol: String,
    val source: SourceType,
    val discoveredAtMs: Long,
    val ageMinutes: Double,
    val liquidityUsd: Double,
    val marketCapUsd: Double,
    val buyPressurePct: Double,
    val volume1mUsd: Double,
    val volume5mUsd: Double,
    val holders: Int? = null,
    val topHolderPct: Double? = null,
    val bundledPct: Double? = null,
    val hasIdentitySignals: Boolean = false,
    val isSellable: Boolean? = null,
    val rawRiskScore: Int? = null,
    val extra: Map<String, Any?> = emptyMap()
) {
    fun sanitized(): CandidateSnapshot {
        return copy(
            mint = mint.trim(),
            symbol = symbol.trim().uppercase().ifBlank { "UNKNOWN" },
            ageMinutes = ageMinutes.safeFinite().coerceAtLeast(0.0),
            liquidityUsd = liquidityUsd.safeFinite().coerceAtLeast(0.0),
            marketCapUsd = marketCapUsd.safeFinite().coerceAtLeast(0.0),
            buyPressurePct = buyPressurePct.safeFinite().coerceIn(0.0, 100.0),
            volume1mUsd = volume1mUsd.safeFinite().coerceAtLeast(0.0),
            volume5mUsd = volume5mUsd.safeFinite().coerceAtLeast(0.0),
            holders = holders?.coerceAtLeast(0),
            topHolderPct = topHolderPct?.safeFinite()?.coerceIn(0.0, 100.0),
            bundledPct = bundledPct?.safeFinite()?.coerceIn(0.0, 100.0),
            rawRiskScore = rawRiskScore?.coerceIn(0, 100)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// CORE CONFIG
// ═══════════════════════════════════════════════════════════════════════════

enum class BotMode { PAPER, LEARNING, LIVE }

data class TradingConfig(
    val minLiquidityUsd: Double = 1_000.0,
    val maxTokenAgeMinutes: Double = 30.0,
    val watchScoreMin: Int = 20,
    val executeSmallMin: Int = 35,
    val executeStandardMin: Int = 50,
    val executeAggressiveMin: Int = 65,
    val fatalRugThreshold: Int = 90,
    val candidateTtlMinutes: Long = 20,
    val shadowTrackNearMissMin: Int = 15,
    val reserveSol: Double = 0.05,
    val maxSmallSizePct: Double = 0.04,
    val maxStandardSizePct: Double = 0.07,
    val maxAggressiveSizePct: Double = 0.12,
    val paperLearningSizeMult: Double = 0.50
) {
    init {
        require(minLiquidityUsd >= 0.0) { "minLiquidityUsd must be >= 0" }
        require(maxTokenAgeMinutes >= 0.0) { "maxTokenAgeMinutes must be >= 0" }
        require(watchScoreMin <= executeSmallMin) { "watchScoreMin must be <= executeSmallMin" }
        require(executeSmallMin <= executeStandardMin) { "executeSmallMin must be <= executeStandardMin" }
        require(executeStandardMin <= executeAggressiveMin) { "executeStandardMin must be <= executeAggressiveMin" }
        require(fatalRugThreshold in 0..100) { "fatalRugThreshold must be 0..100" }
        require(candidateTtlMinutes > 0) { "candidateTtlMinutes must be > 0" }
        require(shadowTrackNearMissMin >= 0) { "shadowTrackNearMissMin must be >= 0" }
        require(reserveSol >= 0.0) { "reserveSol must be >= 0" }
        require(maxSmallSizePct in 0.0..1.0) { "maxSmallSizePct must be 0..1" }
        require(maxStandardSizePct in 0.0..1.0) { "maxStandardSizePct must be 0..1" }
        require(maxAggressiveSizePct in 0.0..1.0) { "maxAggressiveSizePct must be 0..1" }
        require(paperLearningSizeMult in 0.0..1.0) { "paperLearningSizeMult must be 0..1" }
    }
}

data class TradingContext(
    val config: TradingConfig,
    val mode: BotMode,
    val marketRegime: String = "NEUTRAL",
    val apiHealthy: Boolean = true,
    val priceFeedsHealthy: Boolean = true,
    val clockMs: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════════════════════
// ELIGIBILITY (Hard Gates Only)
// ═══════════════════════════════════════════════════════════════════════════

data class EligibilityResult(
    val passed: Boolean,
    val reason: String
) {
    companion object {
        fun pass() = EligibilityResult(true, "PASS")
        fun fail(reason: String) = EligibilityResult(false, reason)
    }
}

class CooldownManager {
    private val cooldowns = mutableMapOf<String, Long>()

    fun setCooldown(mint: String, untilMs: Long) {
        if (mint.isBlank()) return
        cooldowns[mint] = untilMs
    }

    fun isCoolingDown(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = cooldowns[mint] ?: return false
        return nowMs < until
    }

    fun clearExpired(nowMs: Long = System.currentTimeMillis()) {
        cooldowns.entries.removeIf { it.value <= nowMs }
    }
}

class ExposureGuard(
    private val maxOpenPositions: Int = 5,
    private val maxExposurePct: Double = 0.70
) {
    private val openMints = mutableSetOf<String>()
    var currentExposurePct: Double = 0.0

    fun openPosition(mint: String) {
        if (mint.isBlank()) return
        openMints += mint
    }

    fun closePosition(mint: String) {
        openMints -= mint
    }

    fun isTokenAlreadyOpen(mint: String): Boolean = mint in openMints

    fun isGlobalExposureMaxed(): Boolean {
        val safeExposure = currentExposurePct.safeFinite().coerceIn(0.0, 1.0)
        return openMints.size >= maxOpenPositions || safeExposure >= maxExposurePct
    }

    fun openCount(): Int = openMints.size
}

class EligibilityGate(
    private val config: TradingConfig,
    private val cooldownManager: CooldownManager,
    private val exposureGuard: ExposureGuard
) {
    fun evaluate(candidate: CandidateSnapshot): EligibilityResult {
        val c = candidate.sanitized()

        if (c.mint.isBlank()) return EligibilityResult.fail("INVALID_MINT")
        if (c.symbol.isBlank()) return EligibilityResult.fail("INVALID_SYMBOL")
        if (c.liquidityUsd <= 0.0) return EligibilityResult.fail("ZERO_LIQUIDITY")
        if (c.ageMinutes > config.maxTokenAgeMinutes) return EligibilityResult.fail("TOO_OLD")
        if (c.liquidityUsd < config.minLiquidityUsd) return EligibilityResult.fail("LOW_LIQUIDITY")
        if (cooldownManager.isCoolingDown(c.mint)) return EligibilityResult.fail("COOLDOWN")
        if (exposureGuard.isTokenAlreadyOpen(c.mint)) return EligibilityResult.fail("ALREADY_OPEN")
        if (exposureGuard.isGlobalExposureMaxed()) return EligibilityResult.fail("GLOBAL_EXPOSURE_MAX")

        return EligibilityResult.pass()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SCORING
// ═══════════════════════════════════════════════════════════════════════════

data class ScoreComponent(
    val name: String,
    val value: Int,
    val reason: String,
    val fatal: Boolean = false
)

data class ScoreCard(val components: List<ScoreComponent>) {
    val total: Int get() = components.sumOf { it.value }

    fun byName(name: String): ScoreComponent? = components.find { it.name == name }

    fun thesis(): String = components.joinToString(" | ") { "${it.name}:${it.value}" }

    fun detailedReasons(): List<String> = components.map { "${it.name}:${it.value} (${it.reason})" }
}

interface ScoringModule {
    val name: String
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent
}

fun sourceScore(source: SourceType): ScoreComponent = when (source) {
    SourceType.DEX_BOOSTED -> ScoreComponent("source", 4, "Boosted visibility")
    SourceType.RAYDIUM_NEW_POOL -> ScoreComponent("source", 7, "Fresh pool discovery")
    SourceType.PUMP_FUN_GRADUATE -> ScoreComponent("source", 5, "Pump graduate candidate")
    SourceType.DEX_TRENDING -> ScoreComponent("source", 3, "Trending visibility")
}

class EntryAI : ScoringModule {
    override val name = "entry"

    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()

        when {
            candidate.buyPressurePct >= 70.0 -> {
                score += 8
                reasons += "Strong buy pressure"
            }
            candidate.buyPressurePct >= 60.0 -> {
                score += 4
                reasons += "Good buy pressure"
            }
            candidate.buyPressurePct < 35.0 -> {
                score -= 8
                reasons += "Weak buy pressure"
            }
        }

        if (candidate.extra.bool("rsiOversold")) {
            score += 5
            reasons += "RSI bounce setup"
        }
        if (candidate.extra.bool("momentumUp")) {
            score += 4
            reasons += "Momentum rising"
        }
        if (candidate.extra.bool("higherLows")) {
            score += 3
            reasons += "Higher lows forming"
        }

        return ScoreComponent(
            name = name,
            value = score.coerceIn(-20, 20),
            reason = reasons.ifEmpty { listOf("Neutral entry") }.joinToString(", ")
        )
    }
}

class MomentumAI : ScoringModule {
    override val name = "momentum"

    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()

        if (candidate.extra.bool("pumpBuilding")) {
            score += 10
            reasons += "Pump building"
        }
        if (candidate.extra.bool("momentumUp")) {
            score += 4
            reasons += "Momentum up"
        }
        if (candidate.extra.bool("momentumWeak")) {
            score -= 8
            reasons += "Momentum weak"
        }

        return ScoreComponent(
            name = name,
            value = score.coerceIn(-15, 15),
            reason = reasons.ifEmpty { listOf("Neutral momentum") }.joinToString(", ")
        )
    }
}

class LiquidityAI : ScoringModule {
    override val name = "liquidity"

    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()

        val draining = candidate.extra.bool("liquidityDraining")
        val phase = candidate.extra.string("phase")
        val volumeExpanding = candidate.extra.bool("volumeExpanding")

        when {
            candidate.liquidityUsd >= 40_000.0 -> {
                score += 8
                reasons += "Strong liquidity base"
            }
            candidate.liquidityUsd >= 15_000.0 -> {
                score += 5
                reasons += "Good liquidity"
            }
            candidate.liquidityUsd < 3_000.0 -> {
                score -= 8
                reasons += "Thin liquidity"
            }
        }

        if (draining) {
            val penalty = when {
                phase.contains("pre", ignoreCase = true) && volumeExpanding -> 2
                phase.contains("early", ignoreCase = true) && volumeExpanding -> 3
                else -> 8
            }
            score -= penalty
            reasons += "Liquidity draining"
        }

        return ScoreComponent(
            name = name,
            value = score.coerceIn(-15, 10),
            reason = reasons.ifEmpty { listOf("Neutral liquidity") }.joinToString(", ")
        )
    }
}

class VolumeProfileAI : ScoringModule {
    override val name = "volume"

    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()

        if (candidate.extra.bool("accumulationAtVal")) {
            score += 8
            reasons += "Accumulation at value"
        }
        if (candidate.extra.bool("volumeExpanding")) {
            score += 5
            reasons += "Volume expanding"
        }
        if (candidate.extra.bool("sellCluster")) {
            score -= 8
            reasons += "Sell cluster detected"
        }

        return ScoreComponent(
            name = name,
            value = score.coerceIn(-10, 15),
            reason = reasons.ifEmpty { listOf("Neutral volume") }.joinToString(", ")
        )
    }
}

class HolderSafetyAI : ScoringModule {
    override val name = "holders"

    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()

        val topHolder = candidate.topHolderPct ?: 0.0
        val bundled = candidate.bundledPct ?: 0.0
        val holderCount = candidate.holders ?: 0

        when {
            holderCount == 0 -> {
                score -= 10
                reasons += "No holder data"
            }
            holderCount > 80 -> {
                score += 4
                reasons += "Healthy holder spread"
            }
        }

        when {
            topHolder >= 20.0 -> {
                score -= 12
                reasons += "Top holder concentration high"
            }
            topHolder >= 10.0 -> {
                score -= 6
                reasons += "Top holder moderately high"
            }
            topHolder in 0.1..5.0 -> {
                score += 3
                reasons += "Top holder acceptable"
            }
        }

        when {
            bundled >= 25.0 -> {
                score -= 10
                reasons += "Heavy bundle concentration"
            }
            bundled >= 10.0 -> {
                score -= 5
                reasons += "Moderate bundle concentration"
            }
        }

        return ScoreComponent(
            name = name,
            value = score.coerceIn(-20, 10),
            reason = reasons.ifEmpty { listOf("Neutral holder safety") }.joinToString(", ")
        )
    }
}

class NarrativeAI : ScoringModule {
    override val name = "narrative"

    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()

        if (candidate.hasIdentitySignals) {
            score += 6
            reasons += "Has identity signals"
        } else {
            score -= 2
            reasons += "No identity"
        }

        if (candidate.extra.bool("suspiciousName")) {
            score -= 3
            reasons += "Suspicious naming pattern"
        }

        return ScoreComponent(
            name = name,
            value = score.coerceIn(-5, 10),
            reason = reasons.ifEmpty { listOf("Neutral narrative") }.joinToString(", ")
        )
    }
}

class MemoryAI : ScoringModule {
    override val name = "memory"

    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        val memoryScore = (candidate.extra.int("memoryScore") ?: 0).coerceIn(-15, 10)
        val reason = when {
            memoryScore >= 6 -> "Strong positive analogs"
            memoryScore > 0 -> "Positive analogs"
            memoryScore < -8 -> "Strong negative analogs"
            memoryScore < 0 -> "Negative analogs"
            else -> "No memory edge"
        }
        return ScoreComponent(name, memoryScore, reason)
    }
}

class MarketRegimeAI : ScoringModule {
    override val name = "regime"

    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        val phase = candidate.extra.string("phase")
        val value = when {
            ctx.marketRegime.equals("BULL", true) && phase.contains("pre", true) -> 8
            ctx.marketRegime.equals("BULL", true) -> 4
            ctx.marketRegime.equals("BEAR", true) -> -6
            else -> 1
        }
        return ScoreComponent(name, value.coerceIn(-10, 10), "Regime ${ctx.marketRegime}")
    }
}

class TimeAI : ScoringModule {
    override val name = "time"

    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        val hour = ((ctx.clockMs / 1000L / 3600L) % 24L).toInt()
        val value = when (hour) {
            in 0..5 -> -2
            in 6..11 -> 2
            in 12..18 -> 3
            else -> 1
        }
        return ScoreComponent(name, value.coerceIn(-5, 5), "Hour=$hour")
    }
}

class CopyTradeAI : ScoringModule {
    override val name = "copytrade"

    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()

        if (candidate.extra.bool("copyTradeStale")) {
            score -= 8
            reasons += "Stale copy-trade pattern"
        }
        if (candidate.extra.bool("copyTradeCrowded")) {
            score -= 4
            reasons += "Crowded setup"
        }

        return ScoreComponent(
            name = name,
            value = score.coerceIn(-10, 5),
            reason = reasons.ifEmpty { listOf("No copy-trade penalty") }.joinToString(", ")
        )
    }
}

class UnifiedScorer(
    private val entryAI: EntryAI = EntryAI(),
    private val momentumAI: MomentumAI = MomentumAI(),
    private val liquidityAI: LiquidityAI = LiquidityAI(),
    private val volumeAI: VolumeProfileAI = VolumeProfileAI(),
    private val holderAI: HolderSafetyAI = HolderSafetyAI(),
    private val narrativeAI: NarrativeAI = NarrativeAI(),
    private val memoryAI: MemoryAI = MemoryAI(),
    private val regimeAI: MarketRegimeAI = MarketRegimeAI(),
    private val timeAI: TimeAI = TimeAI(),
    private val copyTradeAI: CopyTradeAI = CopyTradeAI()
) {
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreCard {
        return ScoreCard(
            listOf(
                sourceScore(candidate.source),
                entryAI.score(candidate, ctx),
                momentumAI.score(candidate, ctx),
                liquidityAI.score(candidate, ctx),
                volumeAI.score(candidate, ctx),
                holderAI.score(candidate, ctx),
                narrativeAI.score(candidate, ctx),
                memoryAI.score(candidate, ctx),
                regimeAI.score(candidate, ctx),
                timeAI.score(candidate, ctx),
                copyTradeAI.score(candidate, ctx)
            )
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// RISK (Fatal Only)
// ═══════════════════════════════════════════════════════════════════════════

data class FatalRiskResult(
    val blocked: Boolean,
    val reason: String? = null
)

class RugModel {
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): Int {
        var score = candidate.rawRiskScore ?: 0

        if (candidate.extra.bool("zeroHolders")) score += 20
        if (candidate.extra.bool("pureSellPressure")) score += 25
        if (candidate.extra.bool("liquidityDraining")) score += 10
        if (candidate.extra.bool("unsellableSignal")) score += 40

        return score.coerceIn(0, 100)
    }
}

class SellabilityCheck {
    fun pairValid(candidate: CandidateSnapshot): Boolean {
        return candidate.mint.isNotBlank() && candidate.symbol.isNotBlank()
    }
}

class FatalRiskChecker(
    private val config: TradingConfig,
    private val rugModel: RugModel = RugModel(),
    private val sellabilityCheck: SellabilityCheck = SellabilityCheck()
) {
    fun check(candidate: CandidateSnapshot, ctx: TradingContext): FatalRiskResult {
        if (candidate.liquidityUsd <= 250.0) return FatalRiskResult(true, "LIQUIDITY_COLLAPSED")
        if (candidate.isSellable == false) return FatalRiskResult(true, "UNSELLABLE")
        if (!sellabilityCheck.pairValid(candidate)) return FatalRiskResult(true, "PAIR_INVALID")

        val rugScore = rugModel.score(candidate, ctx)
        if (rugScore >= config.fatalRugThreshold) {
            return FatalRiskResult(true, "EXTREME_RUG_RISK_$rugScore")
        }

        return FatalRiskResult(false)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// CONFIDENCE ENGINE
// ═══════════════════════════════════════════════════════════════════════════

data class LearningMetrics(
    val classifiedTrades: Int = 0,
    val last20WinRatePct: Double = 0.0,
    val payoffRatio: Double = 1.0,
    val falseBlockRatePct: Double = 0.0,
    val missedWinnerRatePct: Double = 0.0
)

data class OpsMetrics(
    val apiHealthy: Boolean = true,
    val feedsHealthy: Boolean = true,
    val walletHealthy: Boolean = true,
    val latencyMs: Long = 100
)

data class ConfidenceBreakdown(
    val statistical: Int,
    val structural: Int,
    val operational: Int,
    val effective: Int
)

class ConfidenceEngine {
    fun compute(
        scoreCard: ScoreCard,
        metrics: LearningMetrics,
        ops: OpsMetrics
    ): ConfidenceBreakdown {
        val statistical = computeStatistical(metrics)
        val structural = computeStructural(scoreCard)
        val operational = computeOperational(ops)
        val effective = (
            0.50 * statistical +
                0.35 * structural +
                0.15 * operational
            ).roundToInt().coerceIn(0, 100)

        return ConfidenceBreakdown(
            statistical = statistical,
            structural = structural,
            operational = operational,
            effective = effective
        )
    }

    private fun computeStatistical(metrics: LearningMetrics): Int {
        var score = 10
        score += (metrics.classifiedTrades / 10).coerceAtMost(20)
        score += ((metrics.last20WinRatePct.safeFinite() - 50.0) / 5.0).roundToInt().coerceIn(-10, 10)
        score += ((metrics.payoffRatio.safeFinite() - 1.0) * 10.0).roundToInt().coerceIn(-10, 10)
        score -= (metrics.falseBlockRatePct.safeFinite() / 10.0).roundToInt().coerceIn(0, 10)
        score -= (metrics.missedWinnerRatePct.safeFinite() / 10.0).roundToInt().coerceIn(0, 10)
        return score.coerceIn(0, 100)
    }

    private fun computeStructural(scoreCard: ScoreCard): Int {
        val positives = scoreCard.components.count { it.value > 0 }
        val negatives = scoreCard.components.count { it.value < 0 }

        var score = 30
        score += (scoreCard.total / 2).coerceIn(-20, 35)
        score += positives * 2
        score -= negatives

        return score.coerceIn(0, 100)
    }

    private fun computeOperational(ops: OpsMetrics): Int {
        var score = 50
        if (ops.apiHealthy) score += 15 else score -= 20
        if (ops.feedsHealthy) score += 15 else score -= 20
        if (ops.walletHealthy) score += 10 else score -= 20
        if (ops.latencyMs > 2_000) score -= 20 else if (ops.latencyMs > 750) score -= 10
        return score.coerceIn(0, 100)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// DECISION ENGINE
// ═══════════════════════════════════════════════════════════════════════════

enum class DecisionBand {
    EXECUTE_SMALL,
    EXECUTE_STANDARD,
    EXECUTE_AGGRESSIVE,
    WATCH,
    REJECT,
    BLOCK_FATAL
}

data class DecisionResult(
    val band: DecisionBand,
    val finalScore: Int,
    val statisticalConfidence: Int,
    val structuralConfidence: Int,
    val operationalConfidence: Int,
    val effectiveConfidence: Int,
    val reasons: List<String>,
    val fatalReason: String? = null
)

class FinalDecisionEngine(
    private val config: TradingConfig
) {
    fun decide(
        scoreCard: ScoreCard,
        confidence: ConfidenceBreakdown,
        fatal: FatalRiskResult
    ): DecisionResult {
        if (fatal.blocked) {
            return DecisionResult(
                band = DecisionBand.BLOCK_FATAL,
                finalScore = scoreCard.total,
                statisticalConfidence = confidence.statistical,
                structuralConfidence = confidence.structural,
                operationalConfidence = confidence.operational,
                effectiveConfidence = confidence.effective,
                reasons = listOf("Fatal block"),
                fatalReason = fatal.reason
            )
        }

        val score = scoreCard.total
        val conf = confidence.effective

        val band = when {
            score >= config.executeAggressiveMin && conf >= 55 -> DecisionBand.EXECUTE_AGGRESSIVE
            score >= config.executeStandardMin && conf >= 45 -> DecisionBand.EXECUTE_STANDARD
            score >= config.executeSmallMin && conf >= 30 -> DecisionBand.EXECUTE_SMALL
            score >= config.watchScoreMin -> DecisionBand.WATCH
            else -> DecisionBand.REJECT
        }

        return DecisionResult(
            band = band,
            finalScore = score,
            statisticalConfidence = confidence.statistical,
            structuralConfidence = confidence.structural,
            operationalConfidence = confidence.operational,
            effectiveConfidence = confidence.effective,
            reasons = scoreCard.detailedReasons(),
            fatalReason = null
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SIZING
// ═══════════════════════════════════════════════════════════════════════════

data class WalletSnapshot(
    val totalSol: Double,
    val tradeableSol: Double
) {
    fun sanitized(): WalletSnapshot {
        val total = totalSol.safeFinite().coerceAtLeast(0.0)
        val tradeable = tradeableSol.safeFinite().coerceIn(0.0, total)
        return copy(totalSol = total, tradeableSol = tradeable)
    }
}

data class PortfolioRiskState(
    val recentDrawdownPct: Double = 0.0
)

data class SizeResult(
    val sizeSol: Double
)

class SmartSizerV3(
    private val config: TradingConfig
) {
    fun compute(
        band: DecisionBand,
        wallet: WalletSnapshot,
        confidence: Int,
        candidate: CandidateSnapshot,
        risk: PortfolioRiskState,
        mode: BotMode
    ): SizeResult {
        val safeWallet = wallet.sanitized()
        val tradeable = safeWallet.tradeableSol
        if (tradeable <= 0.0) return SizeResult(0.0)

        val basePct = when (band) {
            DecisionBand.EXECUTE_SMALL -> config.maxSmallSizePct.coerceAtMost(0.03)
            DecisionBand.EXECUTE_STANDARD -> config.maxStandardSizePct.coerceAtMost(0.07)
            DecisionBand.EXECUTE_AGGRESSIVE -> config.maxAggressiveSizePct.coerceAtMost(0.12)
            else -> 0.0
        }

        val confMult = when {
            confidence < 35 -> 0.60
            confidence < 50 -> 0.85
            confidence < 65 -> 1.00
            else -> 1.10
        }

        val liqMult = when {
            candidate.liquidityUsd < 5_000.0 -> 0.60
            candidate.liquidityUsd < 15_000.0 -> 0.80
            candidate.liquidityUsd < 40_000.0 -> 1.00
            else -> 1.05
        }

        val ddMult = when {
            risk.recentDrawdownPct.safeFinite() >= 20.0 -> 0.50
            risk.recentDrawdownPct.safeFinite() >= 10.0 -> 0.70
            else -> 1.00
        }

        val learningMult = if (mode == BotMode.PAPER || mode == BotMode.LEARNING) {
            config.paperLearningSizeMult
        } else {
            1.00
        }

        val rawSize = tradeable * basePct * confMult * liqMult * ddMult * learningMult
        val cap = tradeable * config.maxAggressiveSizePct
        val finalSize = rawSize.coerceAtLeast(0.0).coerceAtMost(cap)

        return SizeResult(finalSize)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SHADOW TRACKING
// ═══════════════════════════════════════════════════════════════════════════

enum class ShadowOutcome {
    BREAKOUT_WINNER,
    FAILED_BREAKOUT,
    RUG,
    SLOW_BLEED,
    BOUNCE_ONLY,
    NO_OPPORTUNITY
}

data class ShadowSnapshot(
    val mint: String,
    val symbol: String,
    val startPrice: Double?,
    val startLiquidity: Double,
    val startScore: Int,
    val startConfidence: Int,
    val reasonTracked: String,
    val capturedAtMs: Long
)

class ShadowTracker {
    private val tracked = mutableMapOf<String, ShadowSnapshot>()

    fun track(
        candidate: CandidateSnapshot,
        scoreCard: ScoreCard,
        confidence: Int,
        reason: String
    ) {
        if (candidate.mint.isBlank()) return

        tracked[candidate.mint] = ShadowSnapshot(
            mint = candidate.mint,
            symbol = candidate.symbol,
            startPrice = candidate.extra.double("price"),
            startLiquidity = candidate.liquidityUsd,
            startScore = scoreCard.total,
            startConfidence = confidence,
            reasonTracked = reason,
            capturedAtMs = System.currentTimeMillis()
        )
    }

    fun isTracked(mint: String): Boolean = tracked.containsKey(mint)

    fun get(mint: String): ShadowSnapshot? = tracked[mint]

    fun allTracked(): List<ShadowSnapshot> = tracked.values.toList()
}

// ═══════════════════════════════════════════════════════════════════════════
// LEARNING
// ═══════════════════════════════════════════════════════════════════════════

data class LearningEvent(
    val mint: String,
    val symbol: String,
    val decisionBand: DecisionBand,
    val finalScore: Int,
    val confidence: Int,
    val outcomeLabel: String,
    val pnlPct: Double? = null,
    val maxRunupPct: Double? = null,
    val maxDrawdownPct: Double? = null,
    val holdingTimeSec: Int? = null,
    val features: Map<String, Any?> = emptyMap()
)

// ═══════════════════════════════════════════════════════════════════════════
// EXECUTION & LOGGING
// ═══════════════════════════════════════════════════════════════════════════

class TradeExecutor {
    fun execute(
        candidate: CandidateSnapshot,
        size: SizeResult,
        decision: DecisionResult,
        scoreCard: ScoreCard
    ) {
        println(
            "[EXECUTION] ${candidate.symbol} | size=${"%.4f".format(size.sizeSol)} SOL | " +
                "band=${decision.band} | score=${decision.finalScore} | conf=${decision.effectiveConfidence}"
        )
        println("[THESIS] ${scoreCard.thesis()}")
    }
}

class BotLogger {
    fun stage(stage: String, symbol: String, result: String, detail: String) {
        println("[$stage] $symbol | $result | $detail")
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PROCESS RESULT
// ═══════════════════════════════════════════════════════════════════════════

sealed class ProcessResult {
    data class Executed(
        val candidate: CandidateSnapshot,
        val band: DecisionBand,
        val sizeSol: Double,
        val score: Int,
        val confidence: Int,
        val breakdown: String
    ) : ProcessResult()

    data class Watch(
        val candidate: CandidateSnapshot,
        val score: Int,
        val confidence: Int,
        val reason: String
    ) : ProcessResult()

    data class Rejected(
        val candidate: CandidateSnapshot,
        val reason: String,
        val score: Int? = null,
        val confidence: Int? = null
    ) : ProcessResult()

    data class BlockFatal(
        val candidate: CandidateSnapshot,
        val reason: String,
        val score: Int,
        val confidence: Int
    ) : ProcessResult()

    data class ShadowOnly(
        val candidate: CandidateSnapshot,
        val score: Int,
        val confidence: Int,
        val reason: String
    ) : ProcessResult()
}

// ═══════════════════════════════════════════════════════════════════════════
// ORCHESTRATOR
// ═══════════════════════════════════════════════════════════════════════════

class BotOrchestrator(
    private val ctx: TradingContext,
    private val lifecycle: LifecycleManager = LifecycleManager(),
    private val logger: BotLogger = BotLogger(),
    private val eligibilityGate: EligibilityGate,
    private val unifiedScorer: UnifiedScorer = UnifiedScorer(),
    private val fatalRiskChecker: FatalRiskChecker,
    private val confidenceEngine: ConfidenceEngine = ConfidenceEngine(),
    private val finalDecisionEngine: FinalDecisionEngine,
    private val smartSizer: SmartSizerV3,
    private val tradeExecutor: TradeExecutor = TradeExecutor(),
    private val shadowTracker: ShadowTracker = ShadowTracker()
) {
    fun getLifecycleManager(): LifecycleManager = lifecycle

    fun getShadowTracker(): ShadowTracker = shadowTracker

    fun processCandidate(
        candidate: CandidateSnapshot,
        wallet: WalletSnapshot,
        risk: PortfolioRiskState,
        learningMetrics: LearningMetrics,
        opsMetrics: OpsMetrics
    ): ProcessResult {
        val c = candidate.sanitized()

        lifecycle.mark(c.mint, LifecycleState.DISCOVERED)
        logger.stage(
            "DISCOVERY",
            c.symbol,
            "FOUND",
            "src=${c.source} liq=${c.liquidityUsd} age=${"%.2f".format(c.ageMinutes)}m"
        )

        val eligibility = eligibilityGate.evaluate(c)
        if (!eligibility.passed) {
            lifecycle.mark(c.mint, LifecycleState.REJECTED)
            logger.stage("ELIGIBILITY", c.symbol, "FAIL", eligibility.reason)
            return ProcessResult.Rejected(c, eligibility.reason)
        }
        lifecycle.mark(c.mint, LifecycleState.ELIGIBLE)

        val scoreCard = unifiedScorer.score(c, ctx)
        lifecycle.mark(c.mint, LifecycleState.SCORED)
        logger.stage(
            "SCORING",
            c.symbol,
            "OK",
            "total=${scoreCard.total} :: ${scoreCard.thesis()}"
        )

        val fatal = fatalRiskChecker.check(c, ctx)
        logger.stage(
            "FATAL",
            c.symbol,
            if (fatal.blocked) "BLOCK" else "PASS",
            fatal.reason ?: "none"
        )

        val confidence = confidenceEngine.compute(scoreCard, learningMetrics, opsMetrics)
        logger.stage(
            "CONFIDENCE",
            c.symbol,
            "OK",
            "stat=${confidence.statistical} struct=${confidence.structural} ops=${confidence.operational} eff=${confidence.effective}"
        )

        val decision = finalDecisionEngine.decide(scoreCard, confidence, fatal)
        logger.stage(
            "DECISION",
            c.symbol,
            decision.band.name,
            "score=${decision.finalScore} conf=${decision.effectiveConfidence}"
        )

        return when (decision.band) {
            DecisionBand.BLOCK_FATAL -> {
                lifecycle.mark(c.mint, LifecycleState.BLOCKED_FATAL)
                shadowTracker.track(c, scoreCard, confidence.effective, decision.fatalReason ?: "FATAL")
                lifecycle.mark(c.mint, LifecycleState.SHADOW_TRACKED)

                ProcessResult.BlockFatal(
                    candidate = c,
                    reason = decision.fatalReason ?: "FATAL_BLOCK",
                    score = decision.finalScore,
                    confidence = decision.effectiveConfidence
                )
            }

            DecisionBand.WATCH -> {
                lifecycle.mark(c.mint, LifecycleState.WATCH)
                shadowTracker.track(c, scoreCard, confidence.effective, "WATCH")
                lifecycle.mark(c.mint, LifecycleState.SHADOW_TRACKED)

                ProcessResult.Watch(
                    candidate = c,
                    score = decision.finalScore,
                    confidence = decision.effectiveConfidence,
                    reason = "WATCH_BAND"
                )
            }

            DecisionBand.REJECT -> {
                lifecycle.mark(c.mint, LifecycleState.REJECTED)

                if (decision.finalScore >= ctx.config.shadowTrackNearMissMin) {
                    shadowTracker.track(c, scoreCard, confidence.effective, "NEAR_MISS_REJECT")
                    lifecycle.mark(c.mint, LifecycleState.SHADOW_TRACKED)

                    ProcessResult.ShadowOnly(
                        candidate = c,
                        score = decision.finalScore,
                        confidence = decision.effectiveConfidence,
                        reason = "NEAR_MISS_REJECT"
                    )
                } else {
                    ProcessResult.Rejected(
                        candidate = c,
                        reason = "LOW_SCORE",
                        score = decision.finalScore,
                        confidence = decision.effectiveConfidence
                    )
                }
            }

            DecisionBand.EXECUTE_SMALL,
            DecisionBand.EXECUTE_STANDARD,
            DecisionBand.EXECUTE_AGGRESSIVE -> {
                lifecycle.mark(c.mint, LifecycleState.EXECUTE_READY)

                val size = smartSizer.compute(
                    band = decision.band,
                    wallet = wallet,
                    confidence = confidence.effective,
                    candidate = c,
                    risk = risk,
                    mode = ctx.mode
                )

                logger.stage(
                    "SIZING",
                    c.symbol,
                    "OK",
                    "size=${"%.4f".format(size.sizeSol)} SOL"
                )

                if (size.sizeSol <= 0.0) {
                    lifecycle.mark(c.mint, LifecycleState.REJECTED)
                    shadowTracker.track(c, scoreCard, confidence.effective, "SIZE_ZERO")
                    lifecycle.mark(c.mint, LifecycleState.SHADOW_TRACKED)

                    return ProcessResult.ShadowOnly(
                        candidate = c,
                        score = decision.finalScore,
                        confidence = decision.effectiveConfidence,
                        reason = "SIZE_ZERO"
                    )
                }

                tradeExecutor.execute(c, size, decision, scoreCard)
                lifecycle.mark(c.mint, LifecycleState.EXECUTED)

                ProcessResult.Executed(
                    candidate = c,
                    band = decision.band,
                    sizeSol = size.sizeSol,
                    score = decision.finalScore,
                    confidence = decision.effectiveConfidence,
                    breakdown = scoreCard.thesis()
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// HELPERS
// ═══════════════════════════════════════════════════════════════════════════

private fun Double.safeFinite(): Double = if (this.isFinite()) this else 0.0

private fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: false

private fun Map<String, Any?>.string(key: String): String = this[key] as? String ?: ""

private fun Map<String, Any?>.double(key: String): Double? {
    val v = this[key] ?: return null
    return when (v) {
        is Double -> v.safeFinite()
        is Float -> v.toDouble().safeFinite()
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Number -> v.toDouble().safeFinite()
        is String -> v.toDoubleOrNull()?.safeFinite()
        else -> null
    }
}

private fun Map<String, Any?>.int(key: String): Int? {
    val v = this[key] ?: return null
    return when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Float -> v.toInt()
        is Double -> v.toInt()
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// EXAMPLE USAGE
// ═══════════════════════════════════════════════════════════════════════════

fun main() {
    val config = TradingConfig()
    val ctx = TradingContext(
        config = config,
        mode = BotMode.PAPER,
        marketRegime = "BULL"
    )

    val cooldownManager = CooldownManager()
    val exposureGuard = ExposureGuard()

    val orchestrator = BotOrchestrator(
        ctx = ctx,
        eligibilityGate = EligibilityGate(config, cooldownManager, exposureGuard),
        fatalRiskChecker = FatalRiskChecker(config),
        finalDecisionEngine = FinalDecisionEngine(config),
        smartSizer = SmartSizerV3(config)
    )

    val candidate = CandidateSnapshot(
        mint = "abc123",
        symbol = "FROGGY",
        source = SourceType.DEX_BOOSTED,
        discoveredAtMs = System.currentTimeMillis(),
        ageMinutes = 2.0,
        liquidityUsd = 22_411.0,
        marketCapUsd = 394_845.0,
        buyPressurePct = 77.0,
        volume1mUsd = 12_000.0,
        volume5mUsd = 41_000.0,
        holders = 140,
        topHolderPct = 4.8,
        bundledPct = 6.0,
        hasIdentitySignals = true,
        isSellable = true,
        rawRiskScore = 22,
        extra = mapOf(
            "rsiOversold" to false,
            "momentumUp" to true,
            "higherLows" to true,
            "pumpBuilding" to true,
            "liquidityDraining" to true,
            "volumeExpanding" to true,
            "accumulationAtVal" to true,
            "phase" to "pre_pump",
            "memoryScore" to 4,
            "copyTradeStale" to false,
            "price" to 0.00001234
        )
    )

    val result = orchestrator.processCandidate(
        candidate = candidate,
        wallet = WalletSnapshot(totalSol = 5.39, tradeableSol = 4.74),
        risk = PortfolioRiskState(recentDrawdownPct = 3.0),
        learningMetrics = LearningMetrics(
            classifiedTrades = 23,
            last20WinRatePct = 54.0,
            payoffRatio = 1.4,
            falseBlockRatePct = 12.0,
            missedWinnerRatePct = 18.0
        ),
        opsMetrics = OpsMetrics(
            apiHealthy = true,
            feedsHealthy = true,
            walletHealthy = true,
            latencyMs = 220
        )
    )

    println("RESULT = $result")
}