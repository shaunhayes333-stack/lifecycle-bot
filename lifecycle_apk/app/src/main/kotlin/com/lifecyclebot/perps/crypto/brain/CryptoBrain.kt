package com.lifecyclebot.perps.crypto.brain

import android.content.Context
import com.lifecyclebot.engine.ErrorLogger

/**
 * V5.9.1442 — Crypto-universe brain orchestrator (public facade).
 *
 * CryptoAltTrader talks to ONLY this object. The orchestrator delegates
 * to the isolated sister modules:
 *
 *   • [CryptoFluidLearning]       — maturity arc + threshold/TP/SL ladder
 *   • [CryptoBehavior]            — sentiment, sizing, tilt protection
 *   • [CryptoLosingPatternMemory] — bucket-level loss memory, shadow gate
 *   • [CryptoTacticSwitcher]      — tactic rotation on bleeders
 *   • [CryptoLaneExitTuner]       — per-tier exit verdict
 *   • [CryptoCanonicalLearning]   — outcome reconciliation counters
 *
 * All state lives under the [CryptoBrainState] SharedPreferences namespace
 * ("crypto_brain_v1") — completely isolated from the meme V3/V4 brains.
 *
 * Doctrine: the orchestrator NEVER reads or writes any meme AI state.
 * Memetrader behaviour is byte-identical with or without this file.
 *
 * Recommended call patterns from CryptoAltTrader
 * ──────────────────────────────────────────────
 *
 * Init                : CryptoBrain.init(context)
 * Pre-entry score adj : CryptoBrain.scoreAdjustment(tier, score)
 * Pre-entry conf adj  : CryptoBrain.confidenceModifier()
 * Pre-entry sizing    : CryptoBrain.sizingMultiplier(tier, score)
 * Should shadow only  : CryptoBrain.shouldShadowOnly(tier, score)
 * Should pause (tilt) : CryptoBrain.isTiltProtectionActive()
 * Get spot thresholds : CryptoBrain.getSpotScoreFloor() / getSpotConfFloor()
 * Get lev thresholds  : CryptoBrain.getLevScoreFloor() / getLevConfFloor()
 * Get TP/SL           : CryptoBrain.getTpPct(spot) / getSlPct(spot)
 * Trade start         : CryptoBrain.onTradeStart()
 * Trade close         : CryptoBrain.onTradeClose(tier, score, pnlPct, pnlSol, isPaper, reason, symbol)
 * UI summary          : CryptoBrain.summary()
 */
object CryptoBrain {

    private const val TAG = "🪙CryptoBrain"

    fun init(ctx: Context) {
        CryptoBrainState.init(ctx)
        try { ErrorLogger.info(TAG, "Crypto isolated brain ready — ${CryptoFluidLearning.summary()}") } catch (_: Throwable) {}
    }

    fun isReady(): Boolean = CryptoBrainState.isReady()

    // ── Maturity & thresholds ─────────────────────────────────────────────────
    fun maturity(): CryptoFluidLearning.Maturity = CryptoFluidLearning.maturity()
    fun progressPct(): Int = CryptoFluidLearning.progressPct()
    fun tradeCount(): Int = CryptoFluidLearning.tradeCount()
    fun winRate(): Double = CryptoFluidLearning.winRate()

    fun getSpotScoreFloor(): Int = CryptoFluidLearning.getSpotScoreThreshold()
    fun getSpotConfFloor(): Int = CryptoFluidLearning.getSpotConfThreshold()
    fun getLevScoreFloor(): Int = CryptoFluidLearning.getLevScoreThreshold()
    fun getLevConfFloor(): Int = CryptoFluidLearning.getLevConfThreshold()

    fun getTpPct(spot: Boolean): Double =
        if (spot) CryptoFluidLearning.getSpotTpPct() else CryptoFluidLearning.getLevTpPct()

    fun getSlPct(spot: Boolean): Double =
        if (spot) CryptoFluidLearning.getSpotSlPct() else CryptoFluidLearning.getLevSlPct()

    fun getDynamicFluidStop(staticSl: Double, peakGainPct: Double): Double =
        CryptoFluidLearning.getDynamicFluidStop(staticSl, peakGainPct)

    fun crossLearnedConfidence(rawConf: Double): Double =
        CryptoFluidLearning.getCrossLearnedConfidence(rawConf)

    // ── Behaviour (sentiment + tilt) ──────────────────────────────────────────
    fun scoreAdjustment(): Int = CryptoBehavior.getScoreAdjustment()
    fun confidenceModifier(): Int = CryptoBehavior.getConfidenceModifier()
    fun sentimentClassification(): String = CryptoBehavior.getSentimentClassification()
    fun isTiltProtectionActive(): Boolean = CryptoBehavior.isTiltProtectionActive()

    /**
     * Composite sizing multiplier — combines BehaviourAI sentiment nudge
     * AND LosingPatternMemory bucket-level dampener, both clamped 0.05x..1.4x.
     */
    fun sizingMultiplier(tier: String, score: Int): Double {
        val be = CryptoBehavior.getSizingMultiplier()
        val lp = CryptoLosingPatternMemory.recommendedSizeMult(tier, score)
        return (be * lp).coerceIn(0.05, 1.4)
    }

    // ── Routing / shadow gate ─────────────────────────────────────────────────
    fun shouldShadowOnly(tier: String, score: Int): Boolean =
        CryptoLosingPatternMemory.recommendedShadowOnly(tier, score)

    fun activeTactic(tier: String, score: Int): CryptoTacticSwitcher.Tactic =
        CryptoTacticSwitcher.activeTacticFor(tier, score)

    fun laneExitVerdict(tier: String): CryptoLaneExitTuner.Verdict =
        CryptoLaneExitTuner.verdict(tier)

    // ── Trade lifecycle hooks ─────────────────────────────────────────────────
    fun onTradeStart() {
        CryptoFluidLearning.recordTradeStart()
        CryptoCanonicalLearning.recordOpen()
        CryptoBrainState.save()
    }

    fun onTradeClose(
        tier: String,
        score: Int,
        pnlPct: Double,
        pnlSol: Double,
        isPaper: Boolean,
        reason: String,
        symbol: String,
    ) {
        val win = pnlPct > 0.0
        val trainable = kotlin.math.abs(pnlPct) >= 1.0   // scratch is not trainable

        // 1. Maturity counters
        if (isPaper) CryptoFluidLearning.recordPaperTrade(win, pnlPct)
        else CryptoFluidLearning.recordLiveTrade(win)

        // 2. Sentiment / tilt
        CryptoBehavior.recordTrade(pnlPct, reason, symbol, isPaper)

        // 3. Bucket loss memory
        CryptoLosingPatternMemory.record(tier, score, pnlPct)

        // 4. Tactic rotation
        CryptoTacticSwitcher.onOutcome(tier, score, win)

        // 5. Per-tier exit tuner
        CryptoLaneExitTuner.recordClose(tier, pnlPct, pnlSol)

        // 6. Canonical reconciliation: the position was OPEN; un-bump and
        //    re-bucket into its final state. V5.0.4581 hardens old restored /
        //    pre-hook positions: never decrement below zero. CryptoAltTrader now
        //    calls onTradeStart() only after an actual paper/live open commits,
        //    so new positions reconcile perfectly; legacy positions settle safely.
        if (CryptoCanonicalLearning.openTrades.get() > 0L) {
            CryptoCanonicalLearning.openTrades.decrementAndGet()
            if (CryptoCanonicalLearning.canonicalTotal.get() > 0L) {
                CryptoCanonicalLearning.canonicalTotal.decrementAndGet()
            }
        } else {
            CryptoCanonicalLearning.recoveredTrades.incrementAndGet()
        }
        CryptoCanonicalLearning.recordSettled(win, trainable)

        CryptoBrainState.save()
    }

    fun summary(): String = buildString {
        appendLine(CryptoFluidLearning.summary())
        appendLine(CryptoBehavior.summary())
        appendLine(CryptoLosingPatternMemory.summary())
        appendLine(CryptoTacticSwitcher.summary())
        appendLine(CryptoLaneExitTuner.summary())
        appendLine(CryptoCanonicalLearning.summary())
        append(CryptoFunnel.summary())
    }
}
