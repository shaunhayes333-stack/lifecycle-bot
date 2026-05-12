package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.9.339 — BootstrapAdaptiveEngine
 *
 * PROBLEM: During bootstrap (<40% progress / <400 trades), UnifiedScorer sets
 * bootstrapBypass=true which:
 *   1. Drops all 14 outer-ring layers to score=0 (no contribution, no data)
 *   2. Skips AITrustNetworkAI weight adjustments entirely
 *   3. Skips approvalBoostFor() (pattern memory always returns 0)
 *   4. All 44 layers record outcomes but never adjust scoring → 11% WR loop
 *
 * SOLUTION: Run all 44 layers at PROPORTIONAL weight from trade 1.
 * Each layer gets a rolling 10-trade adjustment multiplier:
 *   - If a layer was net-positive on the last 10 completed trades → BOOST it
 *   - If a layer was net-negative on the last 10 completed trades → DAMPEN it
 *   - Multiplier range: [0.3, 2.0] — never muted, never dominant alone
 *   - After every 10 trades, the multiplier is recalculated from scratch
 *   - If the adjustment made things WORSE over the next 10 trades, it auto-reverts
 *
 * REVERSIBILITY: Each adjustment window is 10 trades. If the adjusted window
 * performs worse than the previous window (by WR delta), the next window
 * reverts to multiplier=1.0 for that layer before re-learning.
 *
 * ALL 44 LAYERS INVOLVED: Each layer contributes its score × multiplier.
 * Layers with no data yet get multiplier=1.0 (neutral, not penalised).
 *
 * This replaces the binary bootstrap bypass with a proportional ramp:
 *   Trades 1-50:    all layers at 10-50% weight (data gathering, mild influence)
 *   Trades 50-200:  layers ramping 50-80% weight, worst performers dampened
 *   Trades 200-400: approaching full weight, trust net starts having real data
 *   Trades 400+:    bootstrapBypass=false, normal operation, this engine idle
 */
object BootstrapAdaptiveEngine {

    private const val TAG = "BootstrapAdaptive"

    // How many trades form one adjustment window
    private const val WINDOW_SIZE = 10

    // Multiplier bounds — wide enough to matter, bounded so no single layer dominates
    private const val MULT_MIN  = 0.30
    private const val MULT_MAX  = 2.00
    private const val MULT_NEUTRAL = 1.0

    // Only active during bootstrap phase (< 40% progress)
    private const val BOOTSTRAP_PROGRESS_THRESHOLD = 0.40

    // ── Per-layer rolling state ───────────────────────────────────────────────

    data class LayerWindow(
        // Current 10-trade window
        val tradesInWindow: ArrayDeque<Boolean> = ArrayDeque(),   // true=trade won, false=trade lost
        val scoresInWindow: ArrayDeque<Int>      = ArrayDeque(),  // layer score on each trade

        // Committed multiplier for the NEXT window (calculated from completed window)
        var currentMultiplier: Double = MULT_NEUTRAL,

        // Previous window WR for revert check
        var prevWindowWr: Double = 0.50,
        var prevWindowMult: Double = MULT_NEUTRAL,

        // Reverted flag — if last adjustment made things worse, hold neutral for 1 window
        var revertedThisWindow: Boolean = false,
    )

    private val layerWindows = ConcurrentHashMap<String, LayerWindow>()
    private val globalTradeCount = AtomicInteger(0)

    // ── 44 canonical layer names (must match UnifiedScorer component names) ──

    val ALL_LAYER_NAMES = listOf(
        // Inner ring (classic 20)
        "entry", "momentum", "liquidity", "volume", "holders",
        "narrative", "memory", "regime", "time", "copytrade",
        "suppression", "feargreed", "social", "volatility",
        "orderflow", "smartmoney", "holdtime", "liquiditycycle",
        "insider_tracker", "source",
        // Outer ring (14 — bypassed in bootstrap currently)
        "correlationhedgeai", "liquidityexitpathai", "mevdetectionai",
        "stablecoinflowai", "operatorfingerprintai", "sessionedgeai",
        "executioncostpredictorai", "drawdowncircuitai", "capitalefficiencyai",
        "tokendnaclusteringai", "peeralphaverificationai", "newsshockai",
        "fundingrateawarenessai", "orderbookimbalancepulseai",
        // Meta layers
        "metacognition", "behavior", "approval_memory",
        "collective_ai", "v4_crosstalk",
        // Trader layers (tracked by name in EducationSubLayerAI)
        "fluidlearningai", "strategytrustai", "aitrustnetworkai",
        "educationsuplayerai",
        // Additional V4 meta
        "crossmarketregimeai", "crossassetleadlagai", "narrativeflowai",
        "portfolioheatai", "regimetransitionai"
    )

    // ── Bootstrap ramp: scale all layer scores by progress ───────────────────
    // At 0 trades: all layers contribute at 15% (gather data without over-fitting)
    // At 400 trades (40% progress): full 100% contribution
    // This means layers see REAL data early but can't over-penalise one bad setup

    fun getBootstrapRamp(): Double {
        val progress = try { FluidLearningAI.getLearningProgress() } catch (_: Exception) { 0.0 }
        if (progress >= BOOTSTRAP_PROGRESS_THRESHOLD) return 1.0
        // Linear ramp: 0.15 at progress=0 → 1.0 at progress=0.40
        return (0.15 + (progress / BOOTSTRAP_PROGRESS_THRESHOLD) * 0.85).coerceIn(0.15, 1.0)
    }

    fun isBootstrapActive(): Boolean {
        val progress = try { FluidLearningAI.getLearningProgress() } catch (_: Exception) { 0.0 }
        return progress < BOOTSTRAP_PROGRESS_THRESHOLD
    }

    // ── Get the current adjustment multiplier for a layer ────────────────────

    fun getMultiplier(layerName: String): Double {
        if (!isBootstrapActive()) return 1.0
        val key = layerName.lowercase().trim()
        val window = layerWindows[key] ?: return MULT_NEUTRAL
        return window.currentMultiplier
    }

    /**
     * Apply bootstrap scaling to a score component.
     * Returns the adjusted score value.
     *
     * Two adjustments applied:
     *   1. Bootstrap ramp (global — scales down ALL layers early on)
     *   2. Layer multiplier (per-layer — boosts/dampens based on recent accuracy)
     */
    // V5.9.718 — Core safety layers are never ramped down during bootstrap.
    // Rug signals, bad liquidity, dev sells MUST fire at full strength from trade 1.
    // Only "learning" (non-safety) layers get the 15% ramp on their negative scores.
    private val CORE_SAFETY_LAYERS = setOf(
        "liquidity", "volume", "rug", "mcap", "insider_tracker",
        "mevdetectionai", "drawdowncircuitai", "suppression"
    )

    fun applyBootstrapScale(layerName: String, score: Int): Int {
        if (!isBootstrapActive()) return score
        if (score == 0) return 0

        val ramp = getBootstrapRamp()
        val mult = getMultiplier(layerName)

        val key = layerName.lowercase().trim()
        val isSafetyLayer = CORE_SAFETY_LAYERS.any { key.contains(it) }

        // V5.9.718: Core safety layers always fire at FULL negative strength (no ramp).
        // Learning layers: ramp negative scores so early noise doesn't permanently block entries.
        // Positive scores pass through at full strength for all layers (encourage entries).
        val ramped = when {
            score > 0 -> score                        // positive: full strength always
            isSafetyLayer -> score                    // safety negative: full strength (no ramp)
            else -> (score * ramp).toInt()            // learning negative: ramped
        }
        return (ramped * mult).toInt()
    }

    // ── Record a completed trade outcome for all layers ───────────────────────
    // Call this after every closed trade with the layer scores that were used at entry.

    fun recordTradeOutcome(layerScores: Map<String, Int>, tradeWon: Boolean) {
        val tradeNum = globalTradeCount.incrementAndGet()

        layerScores.forEach { (rawName, score) ->
            val key = rawName.lowercase().trim()
            val window = layerWindows.getOrPut(key) { LayerWindow() }
            window.tradesInWindow.addLast(tradeWon)
            window.scoresInWindow.addLast(score)

            // When we complete a full 10-trade window, recalculate multiplier
            if (window.tradesInWindow.size >= WINDOW_SIZE) {
                recalculateMultiplier(key, window, tradeNum)
                // Slide — keep last 5 trades as context for next window
                while (window.tradesInWindow.size > WINDOW_SIZE / 2) {
                    window.tradesInWindow.removeFirst()
                    window.scoresInWindow.removeFirst()
                }
            }
        }

        if (tradeNum % 10 == 0) {
            logSummary(tradeNum)
        }
    }

    private fun recalculateMultiplier(layerKey: String, window: LayerWindow, tradeNum: Int) {
        val wins = window.tradesInWindow.count { it }
        val total = window.tradesInWindow.size
        if (total == 0) return

        val windowWr = wins.toDouble() / total

        // Revert check: if last adjustment made things worse, reset for this window
        val wrDelta = windowWr - window.prevWindowWr
        if (window.currentMultiplier != MULT_NEUTRAL && window.currentMultiplier != window.prevWindowMult) {
            if (wrDelta < -0.10) {
                // Adjustment made WR drop by >10% — revert to neutral for next window
                ErrorLogger.debug(TAG, "↩️ REVERT [$layerKey] | prev_mult=${window.prevWindowMult.fmt2()} | " +
                    "curr_mult=${window.currentMultiplier.fmt2()} | WR dropped ${(wrDelta*100).toInt()}% → back to 1.0")
                window.currentMultiplier = MULT_NEUTRAL
                window.revertedThisWindow = true
                window.prevWindowWr = windowWr
                window.prevWindowMult = MULT_NEUTRAL
                return
            }
        }

        // How directionally correct was this layer?
        // Score>0 + trade won = correct bullish call
        // Score<0 + trade lost = correct bearish call
        // Incorrect = opposite
        val directionalWins = window.tradesInWindow.indices.count { i ->
            val score = window.scoresInWindow.getOrElse(i) { 0 }
            val won   = window.tradesInWindow.getOrElse(i) { false }
            (score > 0 && won) || (score < 0 && !won) || (score == 0)
        }
        val directionalAcc = directionalWins.toDouble() / total

        // New multiplier based on directional accuracy:
        //   100% accuracy → 2.0x boost
        //   50% accuracy  → 1.0x neutral
        //   0% accuracy   → 0.3x dampen
        val newMult = when {
            window.revertedThisWindow -> MULT_NEUTRAL  // post-revert grace window
            directionalAcc >= 0.80    -> (MULT_NEUTRAL + (directionalAcc - 0.50) * 2.0).coerceIn(MULT_NEUTRAL, MULT_MAX)
            directionalAcc >= 0.60    -> (MULT_NEUTRAL + (directionalAcc - 0.50) * 1.4).coerceIn(MULT_NEUTRAL, MULT_MAX)
            directionalAcc >= 0.50    -> MULT_NEUTRAL
            directionalAcc >= 0.35    -> (MULT_NEUTRAL - (0.50 - directionalAcc) * 1.4).coerceIn(MULT_MIN, MULT_NEUTRAL)
            else                      -> (MULT_NEUTRAL - (0.50 - directionalAcc) * 2.0).coerceIn(MULT_MIN, MULT_NEUTRAL)
        }

        ErrorLogger.debug(TAG, "🔄 WINDOW [$layerKey] trade#$tradeNum | WR=${(windowWr*100).toInt()}% " +
            "dirAcc=${(directionalAcc*100).toInt()}% | ${window.currentMultiplier.fmt2()} → ${newMult.fmt2()}")

        window.prevWindowWr   = windowWr
        window.prevWindowMult = window.currentMultiplier
        window.currentMultiplier = newMult
        window.revertedThisWindow = false
    }

    private fun logSummary(tradeNum: Int) {
        val ramp = getBootstrapRamp()
        val topBoosted = layerWindows.entries
            .filter { it.value.currentMultiplier > 1.1 }
            .sortedByDescending { it.value.currentMultiplier }
            .take(5)
            .joinToString(", ") { "${it.key}×${it.value.currentMultiplier.fmt2()}" }
        val topDampened = layerWindows.entries
            .filter { it.value.currentMultiplier < 0.9 }
            .sortedBy { it.value.currentMultiplier }
            .take(5)
            .joinToString(", ") { "${it.key}×${it.value.currentMultiplier.fmt2()}" }
        ErrorLogger.info(TAG, "📊 Bootstrap#$tradeNum | ramp=${(ramp*100).toInt()}% | " +
            "layers=${layerWindows.size} | boosted=[$topBoosted] | dampened=[$topDampened]")
    }

    /** Get a snapshot of all current multipliers for the LLM/UI */
    fun getSummary(): String {
        if (!isBootstrapActive()) return "Bootstrap complete — engine idle"
        val ramp = getBootstrapRamp()
        val trades = globalTradeCount.get()
        val boosted  = layerWindows.count { it.value.currentMultiplier > 1.1 }
        val dampened = layerWindows.count { it.value.currentMultiplier < 0.9 }
        val neutral  = layerWindows.count { it.value.currentMultiplier in 0.9..1.1 }
        return "Bootstrap#$trades ramp=${(ramp*100).toInt()}% layers=${layerWindows.size} " +
            "boosted=$boosted neutral=$neutral dampened=$dampened"
    }

    private fun Double.fmt2() = "%.2f".format(this)
}
