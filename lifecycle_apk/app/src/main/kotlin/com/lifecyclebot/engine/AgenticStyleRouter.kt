package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.learning.TacticSwitcher

/**
 * V5.9.1575 — AgenticStyleRouter.
 *
 * Source fix for strategy monoculture. The bot already owns many lane tools
 * (sniper, express, moonshot, treasury scalp, dip reclaim, quality/bluechip
 * holds, manipulated narrative burst) and a persistent TacticSwitcher, but the
 * intake path compressed each candidate into a lane and most lanes ignored the
 * per-bucket tactic. This router chooses a per-trade STYLE from:
 *   - ModeRouter character classification
 *   - TacticSwitcher lane × score-band state
 *   - current defensive/regime context
 *   - live candidate shape (age/liquidity/mcap/buy pressure)
 *
 * It does NOT hard-block anything. It expands/ranks lane/tool exposure and
 * emits bounded TP/hold/size biases so the existing sub-traders can sample a
 * wider strategy surface without moving gates.
 */
object AgenticStyleRouter {
    enum class Style(
        val label: String,
        val lanes: Set<String>,
        val tools: Set<String>,
        val sizeMult: Double,
        val tpMult: Double,
        val holdMult: Double,
    ) {
        MICRO_SNIPE("micro_snipe", setOf("PROJECT_SNIPER", "SHITCOIN", "EXPRESS"), setOf("SNIPER", "PUMP_FUN", "EXPRESS"), 0.55, 0.85, 0.45),
        QUICK_FLIP("quick_flip", setOf("SHITCOIN", "EXPRESS", "MANIPULATED"), setOf("EXPRESS", "MEME", "PUMP_FUN"), 0.75, 0.95, 0.65),
        BREAKOUT_RUNNER("breakout_runner", setOf("MOONSHOT", "SHITCOIN", "PROJECT_SNIPER"), setOf("MOONSHOT", "BREAKOUT", "RAYDIUM"), 1.05, 1.25, 1.50),
        SWING_HOLD("swing_hold", setOf("MOONSHOT", "QUALITY", "BLUECHIP"), setOf("SWING", "MFE_TRAIL", "QUALITY_DEPTH"), 0.90, 1.35, 2.00),
        PULLBACK_RECLAIM("pullback_reclaim", setOf("DIP_HUNTER", "QUALITY", "TREASURY"), setOf("PULLBACK", "DIP_RECLAIM", "DEX"), 0.85, 1.05, 1.25),
        WHALE_FOLLOW("whale_follow", setOf("BLUECHIP", "QUALITY", "MOONSHOT"), setOf("WHALE", "QUALITY_DEPTH", "HOLD"), 0.90, 1.20, 1.80),
        REACCUMULATION("reaccumulation", setOf("QUALITY", "TREASURY", "MOONSHOT"), setOf("REACCUMULATION", "SWING", "TRENDING"), 0.80, 1.10, 1.50),
        DEFENSIVE_PROBE("defensive_probe", setOf("SHITCOIN", "PROJECT_SNIPER", "MOONSHOT"), setOf("PROBE", "SNIPER", "MEME"), 0.35, 0.75, 0.55),
        LAB_EXPLORATION("lab_exploration", setOf("SHITCOIN", "MOONSHOT", "MANIPULATED", "PROJECT_SNIPER"), setOf("LAB", "HYPOTHESIS", "MEME"), 0.50, 1.00, 1.00),
    }

    data class Decision(
        val style: Style,
        val tactic: TacticSwitcher.Tactic,
        val tradeType: ModeRouter.TradeType,
        val confidence: Double,
        val reason: String,
    ) {
        val lanes: Set<String> get() = style.lanes
        val tools: Set<String> get() = style.tools
    }

    fun decide(ts: TokenState, classification: ModeRouter.Classification, laneHint: String = ""): Decision {
        val score = try { (ts.lastV3Score ?: ts.entryScore.toInt()).coerceIn(0, 150) } catch (_: Throwable) { 0 }
        val tactic = try { TacticSwitcher.currentTactic(if (laneHint.isBlank()) "SHITCOIN" else laneHint, score) } catch (_: Throwable) { TacticSwitcher.Tactic.MOMENTUM }
        val ddAgg = try { com.lifecyclebot.v3.scoring.DrawdownCircuitAI.getAggression() } catch (_: Throwable) { 1.0 }
        val ageMin = try { ((System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0).coerceAtLeast(0.0) } catch (_: Throwable) { 999.0 }
        val lowInfoFresh = ageMin <= 5.0 && ts.lastBuyPressurePct in 45.0..55.0 && ts.lastLiquidityUsd in 1_000.0..8_000.0

        val style = when {
            ddAgg < 0.50 && lowInfoFresh -> Style.DEFENSIVE_PROBE
            tactic == TacticSwitcher.Tactic.LAB_PROPOSED -> Style.LAB_EXPLORATION
            tactic == TacticSwitcher.Tactic.PULLBACK -> Style.PULLBACK_RECLAIM
            tactic == TacticSwitcher.Tactic.REACCUMULATION -> Style.REACCUMULATION
            tactic == TacticSwitcher.Tactic.BREAKOUT -> Style.BREAKOUT_RUNNER
            classification.tradeType == ModeRouter.TradeType.FRESH_LAUNCH && ageMin <= 3.0 -> Style.MICRO_SNIPE
            classification.tradeType == ModeRouter.TradeType.FRESH_LAUNCH -> Style.QUICK_FLIP
            classification.tradeType == ModeRouter.TradeType.BREAKOUT_CONTINUATION -> Style.BREAKOUT_RUNNER
            classification.tradeType == ModeRouter.TradeType.GRADUATION -> Style.SWING_HOLD
            classification.tradeType == ModeRouter.TradeType.REVERSAL_RECLAIM -> Style.PULLBACK_RECLAIM
            classification.tradeType == ModeRouter.TradeType.WHALE_ACCUMULATION -> Style.WHALE_FOLLOW
            classification.tradeType == ModeRouter.TradeType.TREND_PULLBACK -> Style.REACCUMULATION
            classification.tradeType == ModeRouter.TradeType.SENTIMENT_IGNITION -> Style.QUICK_FLIP
            else -> if (ddAgg < 0.80) Style.DEFENSIVE_PROBE else Style.LAB_EXPLORATION
        }
        return Decision(
            style = style,
            tactic = tactic,
            tradeType = classification.tradeType,
            confidence = classification.confidence,
            reason = "type=${classification.tradeType} tactic=$tactic dd=${"%.2f".format(ddAgg)} age=${"%.1f".format(ageMin)} liq=${ts.lastLiquidityUsd.toInt()} bp=${ts.lastBuyPressurePct.toInt()}",
        )
    }

    fun lanesFor(ts: TokenState, classification: ModeRouter.Classification, base: Set<String>): Set<String> {
        val d = decide(ts, classification)
        return (base + d.lanes).filter { it.isNotBlank() }.toCollection(linkedSetOf())
    }

    fun toolsFor(ts: TokenState, classification: ModeRouter.Classification, base: Set<String>): Set<String> {
        val d = decide(ts, classification)
        return (base + d.tools).filter { it.isNotBlank() }.toCollection(linkedSetOf())
    }
}
