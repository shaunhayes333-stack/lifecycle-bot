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
        DIAMOND_HANDS_RUNNER("diamond_hands_runner", setOf("MOONSHOT", "QUALITY", "BLUECHIP"), setOf("DIAMOND_HANDS", "MFE_TRAIL", "SMART_CHART"), 0.92, 1.55, 2.80),
        DEGEN_MICRO_SNIPE("degen_micro_snipe", setOf("PROJECT_SNIPER", "SHITCOIN", "EXPRESS"), setOf("DEGEN_ENTRY", "MICRO_SNIPE", "PUMP_FUN"), 0.55, 0.82, 0.45),
        PUMP_GRADUATION_SNIPE("pump_graduation_snipe", setOf("PROJECT_SNIPER", "MOONSHOT", "SHITCOIN"), setOf("PUMP_GRADUATION", "SNIPE_AGE_GATE", "RAYDIUM"), 0.62, 1.05, 0.80),
        CHART_BREAKOUT("chart_breakout", setOf("MOONSHOT", "PROJECT_SNIPER", "QUALITY"), setOf("SMART_CHART", "CHART_BREAKOUT", "PATTERN_CLASSIFIER"), 1.02, 1.30, 1.75),
        CHART_PULLBACK_RECLAIM("chart_pullback_reclaim", setOf("DIP_HUNTER", "QUALITY", "TREASURY"), setOf("PULLBACK_RECLAIM", "DIP_RECLAIM", "REENTRY_RECOVERY"), 0.82, 1.05, 1.25),
        WHALE_ACCUMULATION_HOLD("whale_accumulation_hold", setOf("QUALITY", "BLUECHIP", "MOONSHOT"), setOf("WHALE", "QUALITY_DEPTH", "SWING"), 0.96, 1.22, 2.10),
        EXHAUSTION_QUICK_FLIP("exhaustion_quick_flip", setOf("EXPRESS", "MANIPULATED", "SHITCOIN"), setOf("EXHAUSTION", "QUICK_FLIP", "UPPER_WICK"), 0.58, 0.72, 0.38),
        MAINSTREAM_CRYPTO_SWING("mainstream_crypto_swing", setOf("QUALITY", "BLUECHIP", "MOONSHOT"), setOf("MAINSTREAM_CRYPTO", "SWING", "QUALITY_DEPTH"), 0.96, 1.22, 2.10),
        VOLUME_IGNITION_SCALP("volume_ignition_scalp", setOf("EXPRESS", "SHITCOIN", "MANIPULATED"), setOf("VOLUME_IGNITION", "ORDER_FLOW", "SCALP"), 0.78, 0.92, 0.75),
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
        val toolkit: ToolkitSignalSheet.Sheet,
    ) {
        val lanes: Set<String> get() = style.lanes
        val tools: Set<String> get() = style.tools
    }

    private fun stablePick(seed: String, count: Int): Int = if (count <= 0) 0 else ((seed.hashCode() and 0x7fffffff) % count)

    private fun boundedLanes(mint: String, base: Set<String>, style: Style): Set<String> {
        // V5.9.1576 — bounded style fanout. 1575 fixed strategy monoculture
        // but unioned every style lane onto every candidate. Snapshot 5.0.3637
        // showed FDG/intake >3.2 and projected execs/day >1000. Keep variety by
        // rotating ONE alternate style lane deterministically per mint, instead
        // of evaluating the whole toolbox on every tick.
        val out = linkedSetOf<String>()
        val primary = style.lanes.firstOrNull()
        if (!primary.isNullOrBlank()) out += primary
        val alternates = (style.lanes.drop(1) + base).filter { it.isNotBlank() && it !in out }.distinct()
        if (alternates.isNotEmpty()) out += alternates[stablePick(mint, alternates.size)]
        return out
    }

    private fun boundedTools(mint: String, base: Set<String>, style: Style): Set<String> {
        val out = linkedSetOf<String>()
        val primary = style.tools.firstOrNull()
        if (!primary.isNullOrBlank()) out += primary
        val alternates = (style.tools.drop(1) + base).filter { it.isNotBlank() && it !in out }.distinct()
        if (alternates.isNotEmpty()) out += alternates[stablePick("tool:$mint", alternates.size)]
        return out
    }

    private fun styleForToolkit(sheet: ToolkitSignalSheet.Sheet): Style? = when (sheet.setup) {
        ToolkitSignalSheet.Setup.DIAMOND_HANDS_RUNNER -> Style.DIAMOND_HANDS_RUNNER
        ToolkitSignalSheet.Setup.DEGEN_MICRO_SNIPE -> Style.DEGEN_MICRO_SNIPE
        ToolkitSignalSheet.Setup.PUMP_GRADUATION_SNIPE -> Style.PUMP_GRADUATION_SNIPE
        ToolkitSignalSheet.Setup.CHART_BREAKOUT -> Style.CHART_BREAKOUT
        ToolkitSignalSheet.Setup.CHART_PULLBACK_RECLAIM -> Style.CHART_PULLBACK_RECLAIM
        ToolkitSignalSheet.Setup.WHALE_ACCUMULATION_HOLD -> Style.WHALE_ACCUMULATION_HOLD
        ToolkitSignalSheet.Setup.EXHAUSTION_QUICK_FLIP -> Style.EXHAUSTION_QUICK_FLIP
        ToolkitSignalSheet.Setup.MAINSTREAM_CRYPTO_SWING -> Style.MAINSTREAM_CRYPTO_SWING
        ToolkitSignalSheet.Setup.VOLUME_IGNITION_SCALP -> Style.VOLUME_IGNITION_SCALP
        ToolkitSignalSheet.Setup.NONE -> null
    }

    fun decide(ts: TokenState, classification: ModeRouter.Classification, laneHint: String = ""): Decision {
        val sheet = try { ToolkitSignalSheet.build(ts, classification) } catch (_: Throwable) { ToolkitSignalSheet.build(ts, null) }
        val score = try { (ts.lastV3Score ?: ts.entryScore.toInt()).coerceIn(0, 150) } catch (_: Throwable) { 0 }
        val tactic = try { TacticSwitcher.currentTactic(if (laneHint.isBlank()) "SHITCOIN" else laneHint, score) } catch (_: Throwable) { TacticSwitcher.Tactic.MOMENTUM }
        val ddAgg = try { com.lifecyclebot.v3.scoring.DrawdownCircuitAI.getAggression() } catch (_: Throwable) { 1.0 }
        val ageMin = try { ((System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0).coerceAtLeast(0.0) } catch (_: Throwable) { 999.0 }
        val lowInfoFresh = ageMin <= 5.0 && ts.lastBuyPressurePct in 45.0..55.0 && ts.lastLiquidityUsd in 1_000.0..8_000.0
        // V5.0.3718 — hot-path safe. Do NOT call the synchronous regime
        // snapshot here; it can refresh by scanning TradeHistoryStore. This router runs per
        // candidate, so use the O(1) stale-while-revalidate catastrophic flag.
        val lowScoreBleedContext = score <= 10 && try { CatastrophicPaperBleedGuard.isActive() } catch (_: Throwable) { false }

        val toolkitStyle = if (sheet.confidence >= 38.0) styleForToolkit(sheet) else null
        val style = when {
            toolkitStyle != null -> toolkitStyle
            // V5.0.3716 — do not let PULLBACK/LAB tactics route score-0 CHOP
            // candidates into DIP_HUNTER as primary during a catastrophic paper
            // WR collapse. Keep exploration alive, but via defensive meme-family
            // probes rather than specialist duplicate exposure.
            lowScoreBleedContext -> Style.DEFENSIVE_PROBE
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
            confidence = maxOf(classification.confidence, sheet.confidence),
            reason = "type=${classification.tradeType} tactic=$tactic toolkit=${sheet.setup} chart=${sheet.chartPattern} entry=${sheet.entryStyle} exit=${sheet.exitStyle} hold×=${"%.2f".format(sheet.holdMult)} size×=${"%.2f".format(sheet.sizeMult)} tp×=${"%.2f".format(sheet.tpMult)} dd=${"%.2f".format(ddAgg)} age=${"%.1f".format(ageMin)} liq=${ts.lastLiquidityUsd.toInt()} bp=${ts.lastBuyPressurePct.toInt()} why=${sheet.compactReason}",
            toolkit = sheet,
        )
    }

    fun lanesFor(ts: TokenState, classification: ModeRouter.Classification, base: Set<String>): Set<String> {
        val d = decide(ts, classification)
        return boundedLanes(ts.mint, base + d.toolkit.laneVotes, d.style)
    }

    fun toolsFor(ts: TokenState, classification: ModeRouter.Classification, base: Set<String>): Set<String> {
        val d = decide(ts, classification)
        return boundedTools(ts.mint, base + d.toolkit.toolVotes, d.style)
    }
}
