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

    /**
     * V5.9.382 — clear the inherited layer demotion map. Called once on
     * boot after the recalibration flag is cleared, so the brain starts
     * fresh without the poisoned-state demotions that collapsed WR from
     * 33% → 4%. Safe idempotent call — the next recompute() cycle will
     * rebuild a proper weights map with the new gentler 0.80/0.90 curve.
     */
    fun clearDemotionWeights() {
        lastDirective = lastDirective.copy(layerWeightAdjust = emptyMap())
    }

    // ═════════════════════════════════════════════════════════════════════
    // INGESTION — called every time a trade closes (paper OR live)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Record a trade outcome. Called from sub-trader close paths and
     * Executor.closeTrade hooks. Cheap O(1) — keeps a 30-trade rolling window.
     */
    fun recordTrade(pnlPct: Double, isPaper: Boolean) =
        recordTradeForAsset(pnlPct, isPaper, assetClass = "MEME")

    /**
     * V5.9.388 — asset-class-scoped trade recording. The MEME window drives
     * the PROTECT / DEAD Copilot directive shown on the main UI, so it MUST
     * stay pure-meme. Sub-trader & markets closes feed their own rolling
     * windows so their regime / WR stats can be inspected without polluting
     * the meme Copilot. Existing recordTrade() callers default to MEME for
     * backwards compatibility.
     */
    fun recordTradeForAsset(pnlPct: Double, isPaper: Boolean, assetClass: String = "MEME") {
        val cls = assetClass.uppercase().ifBlank { "MEME" }
        if (cls == "MEME") {
            synchronized(recentPnlPcts) {
                recentPnlPcts.addLast(pnlPct)
                recentTimes.addLast(System.currentTimeMillis())
                while (recentPnlPcts.size > TRADE_WINDOW) {
                    recentPnlPcts.removeFirst()
                    recentTimes.removeFirst()
                }
            }
        } else {
            val dq = assetPnlWindows.getOrPut(cls) { ArrayDeque() }
            synchronized(dq) {
                dq.addLast(pnlPct)
                while (dq.size > TRADE_WINDOW) dq.removeFirst()
            }
        }
    }

    // V5.9.388 — per-asset rolling windows (populated by sub-trader + markets
    // closes via recordTradeForAsset). Only read for per-asset diagnostics;
    // the meme recentPnlPcts window continues to drive the global directive.
    private val assetPnlWindows = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Double>>()

    fun getAssetWindow(assetClass: String): List<Double> {
        val cls = assetClass.uppercase().ifBlank { "MEME" }
        if (cls == "MEME") return synchronized(recentPnlPcts) { recentPnlPcts.toList() }
        val dq = assetPnlWindows[cls] ?: return emptyList()
        return synchronized(dq) { dq.toList() }
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
            // V5.9.382 — bump active-layer min trades from 5 → 20. Tiny-sample
            // layers (1-4 trades) were distorting the average and feeding the
            // poisoning detector noise. A layer with 3 trades at 0% isn't
            // poisoned, it just hasn't voted yet.
            val activeLayers = maturity.filter { it.trades >= 20 }
            val avgAccuracy = if (activeLayers.isNotEmpty())
                activeLayers.map { it.smoothedAccuracy }.average() else 0.5

            // V5.9.382 — bootstrap-aware poisoning threshold. During learning
            // (< 70% progress) the bot is still exploring; 20-30% accuracy
            // is NORMAL for meme signals, not pathological. Only flag as
            // poisoned below 15% during bootstrap.
            val bootstrapForPoison = try {
                com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() < 0.70
            } catch (_: Exception) { false }
            val poisonFloor = if (bootstrapForPoison) 0.15 else 0.30

            val poisonedLayers = activeLayers.count { it.smoothedAccuracy < poisonFloor }
            val healthyLayers = activeLayers.count { it.smoothedAccuracy >= 0.55 }

            // V5.9.382 — loosened POISONED gate from ≥50% to ≥80%. The old
            // 50% gate fired on my V5.9.374 uniformity glitch (every layer
            // at 20.2%) and Copilot aggressively demoted half the brain,
            // collapsing WR from 33% → 4%. The new 80% gate requires an
            // overwhelming majority of active layers to be poisoned — which
            // indicates a real learning contract failure, not noise.
            val learningHealth = when {
                activeLayers.isEmpty() -> LearningHealth.STEADY
                poisonedLayers >= (activeLayers.size * 4) / 5 -> LearningHealth.POISONED
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
            // V5.9.337: Bootstrap-aware thresholds. During bootstrap (<70% progress),
            // the bot MUST keep trading to accumulate data. The WR < 25% gate was
            // permanently locking it into PROTECT because 24% WR with 74 trades is
            // normal for early learning — not a disaster requiring handbraking.
            val bootstrapProg = try { com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() } catch (_: Exception) { 0.0 }
            val isBootstrap = bootstrapProg < 0.70

            val emergencySteakThresh = if (isBootstrap) 12 else 7
            val protectStreakThresh  = if (isBootstrap) 8  else 4
            val protectWrThresh      = if (isBootstrap) 12.0 else 25.0   // bootstrap: only brake on truly disastrous WR
            val protectWrMinTrades   = if (isBootstrap) 30 else 8         // bootstrap: need more trades before WR gate fires

            val mood = when {
                lossStreak >= emergencySteakThresh || biggestLoss <= -60.0 -> TradeMood.EMERGENCY_BRAKE
                lossStreak >= protectStreakThresh || (wrPct < protectWrThresh && tradesObserved >= protectWrMinTrades) -> TradeMood.PROTECT
                winStreak >= 4 && wrPct >= 55 && learningHealth == LearningHealth.EXCELLENT -> TradeMood.AGGRESSIVE_HUNT
                else -> TradeMood.NORMAL
            }

            // ─── 5. CONFIDENCE FLOOR ──────────────────────────────────────
            // Bootstrap softens floors — paper mode needs volume, not discipline.
            // EMERGENCY_BRAKE: 25% mature / 15% bootstrap
            // PROTECT:         15% mature / 8%  bootstrap (don't choke learning)
            // NORMAL:          8%  always
            // AGGRESSIVE_HUNT: 5%  always
            val recMinConf = when (mood) {
                TradeMood.EMERGENCY_BRAKE -> if (isBootstrap) 15.0 else 25.0
                TradeMood.PROTECT -> if (isBootstrap) 8.0 else 15.0
                TradeMood.NORMAL -> 8.0
                TradeMood.AGGRESSIVE_HUNT -> 5.0
            }

            // ─── 6. SIZING MULTIPLIER ─────────────────────────────────────
            // Bootstrap: PROTECT only trims 25% (not 50%) — paper sizing is symbolic anyway
            val sizing = when (mood) {
                TradeMood.EMERGENCY_BRAKE -> if (isBootstrap) 0.5 else 0.25
                TradeMood.PROTECT -> if (isBootstrap) 0.75 else 0.5
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
            // V5.9.382 — gentler demotion curve. The old 0.5× demotion on
            // <30% accuracy silenced HALF the brain during the V5.9.374
            // uniformity glitch (every layer at 20.2%), which collapsed
            // WR from 33% → 4%. Even a layer that's genuinely under-
            // performing should still contribute 80% of its nominal
            // weight — the bot needs diversity to keep learning.
            val weights = mutableMapOf<String, Double>()
            for (m in activeLayers) {
                val w = when {
                    m.smoothedAccuracy < 0.30 -> 0.80  // soft distrust (was 0.50)
                    m.smoothedAccuracy < 0.45 -> 0.90  // minor distrust (was 0.80)
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

            // ─── V5.9.354 — ACTIVE INTERVENTION ───────────────────────────
            // User feedback: "the copilot is meant to be able to identify
            // that the win rate is poor and shake the ai layers etc back
            // into line. at the moment it just handbrakes."
            //
            // When learning health is POISONED for a sustained period
            // (>= 20 min) AND we have enough trades to be sure it's not
            // noise, call EducationSubLayerAI.shakeWeakLayers(0.5). That
            // pulls every layer with accuracy < 0.55 and ≥ 30 trades back
            // toward the 0.5 prior so they re-explore instead of staying
            // pinned in the converged-bad local minimum.
            try { maybeShakeLayers(directive, tradesObserved) } catch (_: Exception) { }

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

    // ─── V5.9.354 / V5.9.356 — Active layer-shake state ──────────────────
    @Volatile private var poisonedSinceMs: Long = 0L
    @Volatile private var lastShakeMs: Long = 0L
    private val POISON_DWELL_MS  = 20L * 60_000L  // need 20min sustained POISONED
    private val SHAKE_COOLDOWN_MS = 60L * 60_000L // default 1×/hour
    private val SHAKE_COOLDOWN_FAST_MS = 30L * 60_000L  // V5.9.356: 30min when severely lost

    private fun maybeShakeLayers(d: Directive, tradesObserved: Int) {
        val now = System.currentTimeMillis()
        if (d.learningHealth != LearningHealth.POISONED) {
            poisonedSinceMs = 0L
            return
        }
        if (poisonedSinceMs == 0L) {
            poisonedSinceMs = now
            return
        }
        if (now - poisonedSinceMs < POISON_DWELL_MS) return
        if (tradesObserved < 50) return  // don't fire during early bootstrap

        // V5.9.356: Adaptive shake severity + cooldown.
        // When the brain is severely lost (avg accuracy < 40% over 200+
        // trades — proven worse than random) we ramp from severity 0.5 →
        // 0.8 and cooldown 60min → 30min. Healthy regimes still get the
        // gentle once-per-hour nudge.
        val avgAcc = try {
            com.lifecyclebot.v3.scoring.EducationSubLayerAI.getAllLayerMaturity()
                .values.map { it.smoothedAccuracy }.average()
        } catch (_: Exception) { 0.5 }
        val severelyLost = tradesObserved >= 200 && avgAcc < 0.40
        val cooldown = if (severelyLost) SHAKE_COOLDOWN_FAST_MS else SHAKE_COOLDOWN_MS
        val severity = if (severelyLost) 0.8 else 0.5
        if (now - lastShakeMs < cooldown) return

        val shaken = com.lifecyclebot.v3.scoring.EducationSubLayerAI.shakeWeakLayers(severity = severity)
        if (shaken > 0) {
            ErrorLogger.warn(TAG,
                "🔧 COPILOT INTERVENTION: $shaken poisoned layers nudged toward 0.5 prior after " +
                "${(now - poisonedSinceMs) / 60_000}min POISONED · severity=${"%.1f".format(severity)} " +
                "(avgAcc=${(avgAcc * 100).toInt()}% · ${if (severelyLost) "AGGRESSIVE" else "GENTLE"})"
            )
            lastShakeMs = now
            poisonedSinceMs = now  // reset dwell after shake
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
