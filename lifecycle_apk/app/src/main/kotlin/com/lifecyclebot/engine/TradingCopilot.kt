package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.EducationSubLayerAI
import com.lifecyclebot.v3.scoring.FluidLearningAI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V5.9.318 — TradingCopilot: AI Life Coach for the AATE Sentience Layer
 * ─────────────────────────────────────────────────────────────────────────
 *
 * The trading bot has 41+ AI layers, a sentience orchestrator, symbolic
 * reasoning, and an AGI loop. Each piece does its job in isolation, but
 * nothing is steering the *whole organism* toward its singular mission:
 *
 *      "Become the best meme bot ever built. Master the SOL meme market.
 *       Catch 500%+ moonshots. Use Markets/Alt traders as students."
 *
 * The Copilot sits ABOVE the sentience layer. It reads the bot's recent
 * trading state, layer learning health, and market regime, and emits real-
 * time coaching DIRECTIVES that nudge every layer in the right direction:
 *
 *   • If layers are over-tightening → loosens entry confidence floor.
 *   • If WR collapses → forces protective sizing + slows trade tempo.
 *   • If layers learn the wrong lesson (e.g. punishing winning runners
 *     for momentary drawdowns) → bumps their accuracy back up.
 *   • If a moonshot setup is forming → boosts conviction multipliers
 *     so the bot doesn't fumble the entry or exit too early.
 *   • If an alt/markets trader is consistently wrong → demotes its weight
 *     and lets the meme trader (the "teacher") drive learning instead.
 *
 * Wisdom > AGI. The Copilot trades faster than the AI layers can re-learn.
 * It can identify what the bot NEEDS in real time and give it to the bot
 * before it gets stuck in a doom loop.
 *
 * NOT used to override fundamental safety: live-mode hard guards still
 * apply. The Copilot only modulates SOFT signals (confidence floors,
 * layer weights, sizing multipliers, conviction boosts).
 */
object TradingCopilot {
    private const val TAG = "Copilot"

    // ═════════════════════════════════════════════════════════════════════
    // STATE
    // ═════════════════════════════════════════════════════════════════════

    enum class TradeMood {
        AGGRESSIVE_HUNT,   // bot is running hot, capital is fresh, layers are calibrated → press
        NORMAL,            // baseline learning + execution
        PROTECT,           // recent losses or layer-health dipping → tighten + size down
        EMERGENCY_BRAKE    // catastrophic drawdown / consecutive loss streak → near-halt
    }

    enum class LearningHealth {
        EXCELLENT,         // layers calibrating well, accuracy rising
        STEADY,            // layers learning normally
        DRIFTING,          // layers' accuracy stagnating or tilting
        POISONED           // many layers below 30% accuracy — likely bad learning contract
    }

    /**
     * A coaching directive emitted each cycle. All consumers of the Copilot
     * read this single struct — never call private state. Immutable.
     */
    data class Directive(
        val mood: TradeMood,
        val learningHealth: LearningHealth,
        val recommendedMinConfidence: Double,   // 0..100 — minimum entry confidence to honor
        val sizingMultiplier: Double,           // 0.25..1.50 — multiplier on per-trade size
        val convictionBoost: Double,            // 0.0..0.30 — added to confidence on moonshot setups
        val layerWeightAdjust: Map<String, Double>, // layerName → multiplier (0.5..1.5)
        val advice: String,                     // human-readable line for UI/log
        val regime: String,                     // RUNNER_MARKET | CHOP | DEAD | UNKNOWN
        val emittedAt: Long = System.currentTimeMillis(),
    )

    @Volatile private var lastDirective: Directive = Directive(
        mood = TradeMood.NORMAL,
        learningHealth = LearningHealth.STEADY,
        recommendedMinConfidence = 8.0,
        sizingMultiplier = 1.0,
        convictionBoost = 0.0,
        layerWeightAdjust = emptyMap(),
        advice = "Copilot warming up — gathering trade history.",
        regime = "UNKNOWN",
    )
    @Volatile private var lastUpdateMs: Long = 0L

    // Rolling tally of recent trade outcomes (lightweight; backed by TradeJournal as truth source)
    private const val TRADE_WINDOW = 30
    private val recentPnlPcts = ArrayDeque<Double>()
    private val recentTimes = ArrayDeque<Long>()

    // Public read-only accessors
    fun current(): Directive = lastDirective
    fun lastUpdated(): Long = lastUpdateMs

    // ═════════════════════════════════════════════════════════════════════
    // INGESTION — called every time a trade closes (paper OR live)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Record a trade outcome. Called from sub-trader close paths and
     * Executor.closeTrade hooks. Cheap O(1) — keeps a 30-trade rolling window.
     */
    fun recordTrade(pnlPct: Double, isPaper: Boolean) {
        synchronized(recentPnlPcts) {
            recentPnlPcts.addLast(pnlPct)
            recentTimes.addLast(System.currentTimeMillis())
            while (recentPnlPcts.size > TRADE_WINDOW) {
                recentPnlPcts.removeFirst()
                recentTimes.removeFirst()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // CORE: COMPUTE DIRECTIVE
    // Called periodically (every ~30s) by BotService.
    // ═════════════════════════════════════════════════════════════════════

    fun update(): Directive {
        try {
            val window = synchronized(recentPnlPcts) { recentPnlPcts.toList() }
            val tradesObserved = window.size

            // ─── 1. WIN-RATE & STREAKS ────────────────────────────────────
            val wins = window.count { it > 0 }
            val losses = window.count { it < 0 }
            val wrPct = if (tradesObserved > 0) (wins * 100.0 / tradesObserved) else 50.0
            val avgPnl = if (tradesObserved > 0) window.average() else 0.0

            // Streak detection (most recent contiguous run of same-sign trades)
            var streakLen = 0
            var streakSign = 0
            for (p in window.reversed()) {
                val s = if (p > 0) 1 else if (p < 0) -1 else 0
                if (streakSign == 0) {
                    streakSign = s
                    streakLen = 1
                } else if (s == streakSign) {
                    streakLen++
                } else break
            }
            val lossStreak = if (streakSign == -1) streakLen else 0
            val winStreak = if (streakSign == 1) streakLen else 0
            val biggestLoss = window.minOrNull() ?: 0.0
            val biggestWin = window.maxOrNull() ?: 0.0

            // ─── 2. LAYER LEARNING HEALTH ─────────────────────────────────
            val maturity = try { EducationSubLayerAI.getAllLayerMaturity().values } catch (_: Exception) { emptyList() }
            val activeLayers = maturity.filter { it.trades >= 5 }
            val avgAccuracy = if (activeLayers.isNotEmpty())
                activeLayers.map { it.smoothedAccuracy }.average() else 0.5
            val poisonedLayers = activeLayers.count { it.smoothedAccuracy < 0.30 }
            val healthyLayers = activeLayers.count { it.smoothedAccuracy >= 0.55 }

            val learningHealth = when {
                activeLayers.isEmpty() -> LearningHealth.STEADY
                poisonedLayers >= activeLayers.size / 2 -> LearningHealth.POISONED
                avgAccuracy < 0.40 -> LearningHealth.DRIFTING
                avgAccuracy >= 0.62 && healthyLayers >= activeLayers.size / 2 -> LearningHealth.EXCELLENT
                else -> LearningHealth.STEADY
            }

            // ─── 3. MARKET REGIME (inferred from biggest-win + avgPnl) ────
            val regime = when {
                biggestWin >= 50.0 && wrPct >= 35 -> "RUNNER_MARKET"   // moonshots are firing
                avgPnl < -2.0 && lossStreak >= 5 -> "DEAD"              // flat/falling — most things lose
                abs(avgPnl) < 0.5 && tradesObserved >= 10 -> "CHOP"     // grinding sideways
                else -> "UNKNOWN"
            }

            // ─── 4. TRADE MOOD ───────────────────────────────────────────
            val mood = when {
                lossStreak >= 10 || biggestLoss <= -40.0 -> TradeMood.EMERGENCY_BRAKE
                lossStreak >= 6 || (wrPct < 25 && tradesObserved >= 10) -> TradeMood.PROTECT
                winStreak >= 4 && wrPct >= 55 && learningHealth == LearningHealth.EXCELLENT -> TradeMood.AGGRESSIVE_HUNT
                else -> TradeMood.NORMAL
            }

            // ─── 5. CONFIDENCE FLOOR ──────────────────────────────────────
            // EMERGENCY_BRAKE: 25% — only highest-confidence setups
            // PROTECT:         15% — selective
            // NORMAL:          8%  — V5.9.311 baseline
            // AGGRESSIVE_HUNT: 5%  — let the bot press its edge
            val recMinConf = when (mood) {
                TradeMood.EMERGENCY_BRAKE -> 25.0
                TradeMood.PROTECT -> 15.0
                TradeMood.NORMAL -> 8.0
                TradeMood.AGGRESSIVE_HUNT -> 5.0
            }

            // ─── 6. SIZING MULTIPLIER ─────────────────────────────────────
            val sizing = when (mood) {
                TradeMood.EMERGENCY_BRAKE -> 0.25
                TradeMood.PROTECT -> 0.5
                TradeMood.NORMAL -> 1.0
                TradeMood.AGGRESSIVE_HUNT -> 1.30
            }

            // ─── 7. CONVICTION BOOST (for moonshot setups) ───────────────
            // Only in RUNNER_MARKET regime with healthy learning, give a
            // small confidence kicker so the bot doesn't fumble the entry.
            val conviction = if (regime == "RUNNER_MARKET" && learningHealth != LearningHealth.POISONED) {
                if (mood == TradeMood.AGGRESSIVE_HUNT) 0.25 else 0.15
            } else 0.0

            // ─── 8. PER-LAYER WEIGHT ADJUSTMENTS ─────────────────────────
            // Layers that are clearly poisoned get demoted (0.5x).
            // Layers that are clearly excellent get amplified (1.30x).
            val weights = mutableMapOf<String, Double>()
            for (m in activeLayers) {
                val w = when {
                    m.smoothedAccuracy < 0.30 -> 0.50  // distrust — likely learning wrong
                    m.smoothedAccuracy < 0.45 -> 0.80
                    m.smoothedAccuracy >= 0.65 -> 1.30  // promote — calibrated
                    m.smoothedAccuracy >= 0.55 -> 1.10
                    else -> 1.0
                }
                if (w != 1.0) weights[m.layerName] = w
            }

            // ─── 9. HUMAN-READABLE ADVICE ─────────────────────────────────
            val advice = buildAdvice(mood, learningHealth, regime, wrPct, lossStreak, winStreak,
                avgAccuracy, biggestWin, biggestLoss, tradesObserved)

            val directive = Directive(
                mood = mood,
                learningHealth = learningHealth,
                recommendedMinConfidence = recMinConf,
                sizingMultiplier = sizing,
                convictionBoost = conviction,
                layerWeightAdjust = weights,
                advice = advice,
                regime = regime,
            )

            lastDirective = directive
            lastUpdateMs = System.currentTimeMillis()

            // Emit a log line whenever the mood or health flips, so the user
            // can SEE the copilot reasoning in their decision feed.
            if (didStateChange(directive)) {
                ErrorLogger.info(TAG,
                    "🧭 ${directive.mood.name} | LH=${directive.learningHealth.name} | regime=${directive.regime} | " +
                    "WR=${"%.0f".format(wrPct)}% acc=${"%.0f".format(avgAccuracy * 100)}% | " +
                    "minConf=${recMinConf.toInt()}% size×${"%.2f".format(sizing)} | $advice")
            }

            return directive
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Copilot update error: ${e.message}")
            return lastDirective
        }
    }

    private var prevMoodSig: String = ""
    private fun didStateChange(d: Directive): Boolean {
        val sig = "${d.mood}|${d.learningHealth}|${d.regime}"
        return if (sig != prevMoodSig) { prevMoodSig = sig; true } else false
    }

    private fun buildAdvice(
        mood: TradeMood, lh: LearningHealth, regime: String,
        wr: Double, lossStreak: Int, winStreak: Int,
        acc: Double, bigWin: Double, bigLoss: Double, n: Int,
    ): String {
        if (n < 5) return "Gathering data ($n/5 trades) — running baseline."
        return when (mood) {
            TradeMood.EMERGENCY_BRAKE ->
                "🛑 Emergency brake: $lossStreak-loss streak, deepest=${bigLoss.toInt()}%. Sizing×0.25, conf≥25% only — let the storm pass."
            TradeMood.PROTECT ->
                "🟠 Protect: WR=${wr.toInt()}% over $n trades. Tightening to conf≥15%, size×0.5. Wait for clean A-quality setups."
            TradeMood.AGGRESSIVE_HUNT ->
                "🟢 Aggressive hunt: $winStreak-win streak, layers calibrated (${(acc*100).toInt()}% avg dir). Press edge — size×1.3, conf≥5%${if (regime == "RUNNER_MARKET") ", moonshot regime detected." else "."}"
            TradeMood.NORMAL -> when {
                lh == LearningHealth.POISONED -> "⚠️ Layer poisoning detected (${(acc*100).toInt()}% avg). Demoting weak layers — let strong ones drive."
                regime == "RUNNER_MARKET" -> "🚀 Runner market: bigWin=${bigWin.toInt()}%. Conviction boost +0.15 on hot setups."
                regime == "CHOP" -> "↔️ Chop: avgPnL flat. Stay selective — fee drag will eat scratch trades."
                regime == "DEAD" -> "💀 Dead market: WR=${wr.toInt()}%, $lossStreak loss streak. Size down naturally."
                else -> "Normal cruise. WR=${wr.toInt()}%, ${(acc*100).toInt()}% layer accuracy."
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // CONSUMER HELPERS — used by LifecycleStrategy / FinalDecisionGate /
    // sub-traders to query the live coaching state.
    // ═════════════════════════════════════════════════════════════════════

    /** Confidence floor consumers should respect (paper mode ≥ this). */
    fun confidenceFloor(): Double = lastDirective.recommendedMinConfidence

    /** Multiplier applied on top of SmartSizer's base size. */
    fun sizingMultiplier(): Double = lastDirective.sizingMultiplier.coerceIn(0.25, 1.5)

    /** Conviction boost added to AI confidence on moonshot-class setups. */
    fun convictionBoost(): Double = lastDirective.convictionBoost.coerceIn(0.0, 0.30)

    /** Per-layer weight multiplier — 1.0 if no adjustment. */
    fun layerWeight(layerName: String): Double =
        lastDirective.layerWeightAdjust[layerName] ?: 1.0

    /** True iff Copilot wants the bot to slam the brakes on new entries. */
    fun isEmergencyBrake(): Boolean = lastDirective.mood == TradeMood.EMERGENCY_BRAKE

    /** True iff Copilot wants the bot to press its edge harder. */
    fun isAggressiveHunt(): Boolean = lastDirective.mood == TradeMood.AGGRESSIVE_HUNT

    /** Snapshot of state for UI / chat / debugging. */
    fun snapshot(): Map<String, Any> {
        val d = lastDirective
        return mapOf(
            "mood" to d.mood.name,
            "learning_health" to d.learningHealth.name,
            "regime" to d.regime,
            "min_confidence" to d.recommendedMinConfidence,
            "sizing_multiplier" to d.sizingMultiplier,
            "conviction_boost" to d.convictionBoost,
            "advice" to d.advice,
            "weight_adjustments" to d.layerWeightAdjust.size,
            "trades_in_window" to recentPnlPcts.size,
            "last_updated_age_ms" to (System.currentTimeMillis() - lastUpdateMs),
        )
    }
}
