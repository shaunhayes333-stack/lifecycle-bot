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
        SMART_WALLET_COPY_FOLLOW("smart_wallet_copy_follow", setOf("QUALITY", "MOONSHOT", "BLUECHIP"), setOf("COPY_TRADE", "WHALE_WALLET", "SMART_MONEY"), 0.82, 1.12, 1.70),
        INSIDER_SHARK_FOLLOW("insider_shark_follow", setOf("INSIDER_SHARK", "MOONSHOT", "QUALITY"), setOf("INSIDER_WALLET", "SOCIAL_ALPHA", "SMART_MONEY", "COPY_TRADE"), 0.92, 1.35, 2.40),
        NARRATIVE_SOCIAL_IGNITION("narrative_social_ignition", setOf("MANIPULATED", "SHITCOIN", "EXPRESS"), setOf("NARRATIVE", "SOCIAL", "SENTIMENT"), 0.72, 1.02, 0.85),
        LIQUIDITY_DEPTH_QUALITY("liquidity_depth_quality", setOf("QUALITY", "BLUECHIP", "TREASURY"), setOf("LIQUIDITY_DEPTH", "QUALITY_DEPTH", "MAINSTREAM_CRYPTO"), 1.05, 1.18, 2.20),
        PANIC_REVERSION_BOUNCE("panic_reversion_bounce", setOf("DIP_HUNTER", "TREASURY", "QUALITY"), setOf("PANIC_REVERSION", "REENTRY_RECOVERY", "DIP_RECLAIM"), 0.62, 0.95, 1.05),
        ARB_FLOW_IMBALANCE("arb_flow_imbalance", setOf("EXPRESS", "SHITCOIN", "TREASURY"), setOf("ARB", "FLOW_IMBALANCE", "VENUE_LAG"), 0.60, 0.78, 0.55),
        MEV_PROTECTED_ENTRY("mev_protected_entry", setOf("SHITCOIN", "PROJECT_SNIPER", "EXPRESS"), setOf("MEV_PROTECTION", "JITO", "DEFENSIVE_PROBE"), 0.42, 0.75, 0.50),
        REENTRY_RECOVERY("reentry_recovery", setOf("DIP_HUNTER", "TREASURY", "QUALITY"), setOf("REENTRY_RECOVERY", "PATTERN_BACKTESTER", "DIP_RECLAIM"), 0.65, 0.98, 1.20),
        REGIME_DEFENSIVE_PROBE("regime_defensive_probe", setOf("SHITCOIN", "PROJECT_SNIPER", "MOONSHOT"), setOf("DEFENSIVE_PROBE", "TOXIC_GUARD", "REGIME"), 0.35, 0.75, 0.55),
        MICRO_SNIPE("micro_snipe", setOf("PROJECT_SNIPER", "SHITCOIN", "EXPRESS"), setOf("SNIPER", "PUMP_FUN", "EXPRESS"), 0.55, 0.85, 0.45),
        QUICK_FLIP("quick_flip", setOf("SHITCOIN", "EXPRESS", "MANIPULATED"), setOf("EXPRESS", "MEME", "PUMP_FUN"), 0.75, 0.95, 0.65),
        BREAKOUT_RUNNER("breakout_runner", setOf("MOONSHOT", "SHITCOIN", "PROJECT_SNIPER"), setOf("MOONSHOT", "BREAKOUT", "RAYDIUM"), 1.05, 1.25, 1.50),
        SWING_HOLD("swing_hold", setOf("MOONSHOT", "QUALITY", "BLUECHIP"), setOf("SWING", "MFE_TRAIL", "QUALITY_DEPTH"), 0.90, 1.35, 2.00),
        PULLBACK_RECLAIM("pullback_reclaim", setOf("DIP_HUNTER", "QUALITY", "TREASURY"), setOf("PULLBACK", "DIP_RECLAIM", "DEX"), 0.85, 1.05, 1.25),
        WHALE_FOLLOW("whale_follow", setOf("BLUECHIP", "QUALITY", "MOONSHOT"), setOf("WHALE", "QUALITY_DEPTH", "HOLD"), 0.90, 1.20, 1.80),
        REACCUMULATION("reaccumulation", setOf("QUALITY", "TREASURY", "MOONSHOT"), setOf("REACCUMULATION", "SWING", "TRENDING"), 0.80, 1.10, 1.50),
        // V5.0.4544 — inner-lane defensive style. Do not advertise QUALITY/DIP/
        // TREASURY as a toxic-meme escape hatch. The lane-local caller keeps the lane
        // owner and pivots tactic/style/confirmation inside that lane instead.
        DEFENSIVE_PROBE("defensive_probe", setOf("SHITCOIN", "MOONSHOT", "PROJECT_SNIPER", "EXPRESS", "MANIPULATED"), setOf("PROBE", "ORDER_FLOW", "SMART_MONEY", "TOXIC_GUARD"), 0.35, 0.75, 0.55),
        TOXIC_RECLAIM_TACTIC("toxic_reclaim_tactic", setOf("SHITCOIN", "EXPRESS", "MANIPULATED", "MOONSHOT"), setOf("PULLBACK_RECLAIM", "LIQUIDITY_DEPTH", "ORDER_FLOW", "SMART_MONEY", "TOXIC_GUARD"), 0.68, 0.88, 0.70),
        LAB_EXPLORATION("lab_exploration", setOf("SHITCOIN", "MOONSHOT", "MANIPULATED", "PROJECT_SNIPER"), setOf("LAB", "HYPOTHESIS", "MEME"), 0.50, 1.00, 1.00),
    }

    data class Decision(
        val style: Style,
        val tactic: TacticSwitcher.Tactic,
        val tradeType: ModeRouter.TradeType,
        val confidence: Double,
        val reason: String,
        val toolkit: ToolkitSignalSheet.Sheet,
        val tunedSizeMult: Double = style.sizeMult,
        val tunedTpMult: Double = style.tpMult,
        val tunedHoldMult: Double = style.holdMult,
    ) {
        val lanes: Set<String> get() = style.lanes
        val tools: Set<String> get() = style.tools
    }

    private fun stablePick(seed: String, count: Int): Int = if (count <= 0) 0 else ((seed.hashCode() and 0x7fffffff) % count)

    private fun boundedLanes(mint: String, base: Set<String>, style: Style, score: Int = 50): Set<String> {
        // V5.9.1576 — bounded style fanout. 1575 fixed strategy monoculture
        // but unioned every style lane onto every candidate. Snapshot 5.0.3637
        // showed FDG/intake >3.2 and projected execs/day >1000. Keep variety by
        // rotating ONE alternate style lane deterministically per mint, instead
        // of evaluating the whole toolbox on every tick.
        val out = linkedSetOf<String>()
        val rapidPivot = rapidToxicRegimePivot(style, score)
        val styleLaneList = (rapidPivot + style.lanes).filter { it.isNotBlank() }.distinct()
        val primary = LaneToxicityGuard.chooseNonToxicLane(mint, styleLaneList, score) ?: styleLaneList.firstOrNull()
        if (!primary.isNullOrBlank()) out += primary
        val growthFallbackLane4557 = LiveGrowthDoctrine.growthLaneFallback(mint, out + base + style.lanes)
        val growthFallback = growthFallbackLane4557?.let { listOf(it) } ?: emptyList()
        val alternatesRaw = (styleLaneList.drop(1) + rapidPivot + base + growthFallback).filter { it.isNotBlank() && it !in out }.distinct()
        val alternates = LaneToxicityGuard.filterNonToxic(alternatesRaw, score).ifEmpty { alternatesRaw }
        val forceContributionFallback4557 = growthFallbackLane4557 != null &&
            growthFallbackLane4557 !in out &&
            growthFallbackLane4557 in LiveGrowthDoctrine.dispatchableContributionLanes &&
            (out + base + style.lanes).map { LiveGrowthDoctrine.canonicalLane(it) }.none { it == growthFallbackLane4557 }
        if (forceContributionFallback4557) growthFallbackLane4557?.let { out += it }
        else if (alternates.isNotEmpty()) out += alternates[stablePick(mint, alternates.size)]
        return out
    }

    private fun rapidToxicRegimePivot(style: Style, score: Int): List<String> {
        // V5.0.4544 — INNER-LANE PIVOT DOCTRINE.
        // Toxic/regime adaptation must not dump a candidate into a different lane
        // family (MOONSHOT → QUALITY/DIP/TREASURY/etc.). The lane remains the same;
        // the strategy/tactic/style inside that lane changes via sameLaneWeakPivotStyle.
        // Returning cross-lane alternates here made the bot look like it was "pivoting"
        // while actually abandoning the lane-local thesis instead of asking: same lane,
        // different wave/mcap/tactic/confirmation/hold profile.
        return emptyList()
    }

    private fun boundedTools(mint: String, base: Set<String>, style: Style): Set<String> {
        val out = linkedSetOf<String>()
        val primary = style.tools.firstOrNull()
        if (!primary.isNullOrBlank()) out += primary
        val growthFallback = LiveGrowthDoctrine.growthToolFallback(mint, out + base + style.tools)?.let { listOf(it) } ?: emptyList()
        val alternates = (style.tools.drop(1) + base + growthFallback).filter { it.isNotBlank() && it !in out }.distinct()
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
        ToolkitSignalSheet.Setup.SMART_WALLET_COPY_FOLLOW -> Style.SMART_WALLET_COPY_FOLLOW
        ToolkitSignalSheet.Setup.NARRATIVE_SOCIAL_IGNITION -> Style.NARRATIVE_SOCIAL_IGNITION
        ToolkitSignalSheet.Setup.LIQUIDITY_DEPTH_QUALITY -> Style.LIQUIDITY_DEPTH_QUALITY
        ToolkitSignalSheet.Setup.PANIC_REVERSION_BOUNCE -> Style.PANIC_REVERSION_BOUNCE
        ToolkitSignalSheet.Setup.ARB_FLOW_IMBALANCE -> Style.ARB_FLOW_IMBALANCE
        ToolkitSignalSheet.Setup.MEV_PROTECTED_ENTRY -> Style.MEV_PROTECTED_ENTRY
        ToolkitSignalSheet.Setup.REENTRY_RECOVERY -> Style.REENTRY_RECOVERY
        ToolkitSignalSheet.Setup.REGIME_DEFENSIVE_PROBE -> Style.REGIME_DEFENSIVE_PROBE
        ToolkitSignalSheet.Setup.NONE -> null
    }

    private fun isRiskOffSheet(sheet: ToolkitSignalSheet.Sheet): Boolean =
        sheet.reasons.any { it.equals("risk_off", ignoreCase = true) || it.contains("risk_off", ignoreCase = true) }

    private fun isWeakChopSheet(sheet: ToolkitSignalSheet.Sheet): Boolean =
        sheet.reasons.any { it == "regime=CHOP" || it == "regime=DUMP" } || isRiskOffSheet(sheet)

    private fun isWeakRuntimeRegime(): Boolean = try {
        // V5.0.3863 — cached/fallback ToolkitSignalSheet can elect DEGEN_MICRO_SNIPE
        // before the sheet reasons carry regime=DUMP/CHOP. Router must treat weak
        // regime as direct authority, not as optional sheet decoration. current() is
        // memoized by RegimeDetector and ToolkitSignalSheet already warmed it on this path.
        val r = RegimeDetector.current()
        r.regime == RegimeDetector.Regime.DUMP ||
            (r.regime == RegimeDetector.Regime.CHOP && r.recentWrPct < 25.0)
    } catch (_: Throwable) { false }

    private fun sameLaneWeakPivotStyle(laneHint: String, fallback: Style): Style {
        val lane = BleederMemoryRouter.canon(laneHint)
        return when (lane) {
            // Same lane, different internal tactic: stop buying generic early-wave runners;
            // require smarter confirmation/whale/copy/breakout character while preserving
            // MOONSHOT ownership of the trade.
            "MOONSHOT" -> when (fallback) {
                Style.DIAMOND_HANDS_RUNNER, Style.BREAKOUT_RUNNER, Style.SWING_HOLD,
                Style.PUMP_GRADUATION_SNIPE, Style.MICRO_SNIPE, Style.QUICK_FLIP,
                Style.DEGEN_MICRO_SNIPE, Style.LAB_EXPLORATION -> Style.SMART_WALLET_COPY_FOLLOW
                else -> if (fallback.lanes.contains("MOONSHOT")) fallback else Style.SMART_WALLET_COPY_FOLLOW
            }
            // Same SHITCOIN lane, but pivot to volume/order-flow/narrative confirmation
            // instead of repeating blind low-score/fresh-wave buys.
            "SHITCOIN" -> when (fallback) {
                Style.DEGEN_MICRO_SNIPE, Style.MICRO_SNIPE, Style.QUICK_FLIP,
                Style.LAB_EXPLORATION, Style.REGIME_DEFENSIVE_PROBE -> Style.VOLUME_IGNITION_SCALP
                else -> if (fallback.lanes.contains("SHITCOIN")) fallback else Style.VOLUME_IGNITION_SCALP
            }
            "EXPRESS" -> if (fallback.lanes.contains("EXPRESS")) fallback else Style.EXHAUSTION_QUICK_FLIP
            "MANIPULATED" -> if (fallback.lanes.contains("MANIPULATED")) fallback else Style.NARRATIVE_SOCIAL_IGNITION
            "PROJECT_SNIPER" -> if (fallback.lanes.contains("PROJECT_SNIPER")) fallback else Style.PUMP_GRADUATION_SNIPE
            "DIP_HUNTER" -> if (fallback.lanes.contains("DIP_HUNTER")) fallback else Style.PANIC_REVERSION_BOUNCE
            "TREASURY", "CASHGEN" -> if (fallback.lanes.contains("TREASURY")) fallback else Style.PANIC_REVERSION_BOUNCE
            "QUALITY" -> if (fallback.lanes.contains("QUALITY")) fallback else Style.WHALE_ACCUMULATION_HOLD
            "BLUECHIP", "BLUE_CHIP" -> if (fallback.lanes.contains("BLUECHIP")) fallback else Style.MAINSTREAM_CRYPTO_SWING
            else -> fallback
        }
    }

    private fun weakChopStylePivot(style: Style, sheet: ToolkitSignalSheet.Sheet, weakRuntimeRegime: Boolean, laneHint: String = ""): Style {
        if (!weakRuntimeRegime && !isWeakChopSheet(sheet)) return style
        val laneLocal = sameLaneWeakPivotStyle(laneHint, style)
        return when (style) {
            // 5.0.4544: pivot the strategy INSIDE the lane, do not dump into another lane.
            Style.DEGEN_MICRO_SNIPE,
            Style.PUMP_GRADUATION_SNIPE,
            Style.VOLUME_IGNITION_SCALP,
            Style.EXHAUSTION_QUICK_FLIP,
            Style.ARB_FLOW_IMBALANCE,
            Style.MEV_PROTECTED_ENTRY,
            Style.NARRATIVE_SOCIAL_IGNITION,
            Style.MICRO_SNIPE,
            Style.QUICK_FLIP,
            Style.DIAMOND_HANDS_RUNNER,
            Style.BREAKOUT_RUNNER,
            Style.SWING_HOLD,
            Style.LAB_EXPLORATION,
            Style.DEFENSIVE_PROBE,
            Style.REGIME_DEFENSIVE_PROBE,
            Style.TOXIC_RECLAIM_TACTIC -> laneLocal
            else -> if (laneLocal.lanes.contains(BleederMemoryRouter.canon(laneHint))) laneLocal else style
        }
    }

    fun decide(ts: TokenState, classification: ModeRouter.Classification, laneHint: String = ""): Decision {
        val sheet = try { ToolkitSignalSheet.snapshot(ts, classification) } catch (_: Throwable) { ToolkitSignalSheet.fallbackSheet(ts, classification) }
        val score = try { (ts.lastV3Score ?: ts.entryScore.toInt()).coerceIn(0, 150) } catch (_: Throwable) { 0 }
        val tactic = try { TacticSwitcher.currentTactic(if (laneHint.isBlank()) "SHITCOIN" else laneHint, score) } catch (_: Throwable) { TacticSwitcher.Tactic.MOMENTUM }
        val ddAgg = try { com.lifecyclebot.v3.scoring.DrawdownCircuitAI.getAggression() } catch (_: Throwable) { 1.0 }
        val ageMin = try { ((System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0).coerceAtLeast(0.0) } catch (_: Throwable) { 999.0 }
        val lowInfoFresh = ageMin <= 5.0 && ts.lastBuyPressurePct in 45.0..55.0 && ts.lastLiquidityUsd in 1_000.0..8_000.0
        // V5.0.3718 — hot-path safe. Do NOT call the synchronous regime
        // snapshot here; it can refresh by scanning TradeHistoryStore. This router runs per
        // candidate, so use the O(1) stale-while-revalidate catastrophic flag.
        val lowScoreBleedContext = score <= 10 && try { CatastrophicPaperBleedGuard.isActive() } catch (_: Throwable) { false }

        val weakChopSheet = isWeakChopSheet(sheet) || isWeakRuntimeRegime()
        val toolkitStyle = if (sheet.confidence >= 38.0) styleForToolkit(sheet)?.let { weakChopStylePivot(it, sheet, weakChopSheet, laneHint) } else null
        val style = when {
            toolkitStyle != null -> toolkitStyle
            // V5.0.3716 — do not let PULLBACK/LAB tactics route score-0 CHOP
            // candidates into DIP_HUNTER as primary during a catastrophic paper
            // WR collapse. Keep exploration alive, but via defensive meme-family
            // probes rather than specialist duplicate exposure.
            lowScoreBleedContext -> sameLaneWeakPivotStyle(laneHint, Style.DEFENSIVE_PROBE)
            ddAgg < 0.50 && lowInfoFresh -> sameLaneWeakPivotStyle(laneHint, Style.DEFENSIVE_PROBE)
            tactic == TacticSwitcher.Tactic.LAB_PROPOSED -> Style.LAB_EXPLORATION
            tactic == TacticSwitcher.Tactic.PULLBACK -> Style.PULLBACK_RECLAIM
            tactic == TacticSwitcher.Tactic.REACCUMULATION -> Style.REACCUMULATION
            tactic == TacticSwitcher.Tactic.BREAKOUT -> Style.BREAKOUT_RUNNER
            weakChopSheet && classification.tradeType in setOf(ModeRouter.TradeType.FRESH_LAUNCH, ModeRouter.TradeType.SENTIMENT_IGNITION, ModeRouter.TradeType.GRADUATION) -> sameLaneWeakPivotStyle(laneHint, Style.DEFENSIVE_PROBE)
            weakChopSheet && classification.tradeType == ModeRouter.TradeType.BREAKOUT_CONTINUATION -> sameLaneWeakPivotStyle(laneHint, Style.BREAKOUT_RUNNER)
            classification.tradeType == ModeRouter.TradeType.FRESH_LAUNCH && ageMin <= 3.0 -> Style.MICRO_SNIPE
            classification.tradeType == ModeRouter.TradeType.FRESH_LAUNCH -> Style.QUICK_FLIP
            classification.tradeType == ModeRouter.TradeType.BREAKOUT_CONTINUATION -> Style.BREAKOUT_RUNNER
            classification.tradeType == ModeRouter.TradeType.GRADUATION -> Style.SWING_HOLD
            classification.tradeType == ModeRouter.TradeType.REVERSAL_RECLAIM -> Style.PULLBACK_RECLAIM
            classification.tradeType == ModeRouter.TradeType.INSIDER_SHARK -> Style.INSIDER_SHARK_FOLLOW
            classification.tradeType == ModeRouter.TradeType.WHALE_ACCUMULATION -> Style.WHALE_FOLLOW
            classification.tradeType == ModeRouter.TradeType.TREND_PULLBACK -> Style.REACCUMULATION
            classification.tradeType == ModeRouter.TradeType.SENTIMENT_IGNITION -> Style.QUICK_FLIP
            else -> if (ddAgg < 0.80) sameLaneWeakPivotStyle(laneHint, Style.DEFENSIVE_PROBE) else sameLaneWeakPivotStyle(laneHint, Style.LAB_EXPLORATION)
        }
        val tuneLane = laneHint.ifBlank { style.lanes.firstOrNull().orEmpty() }.ifBlank { classification.tradeType.name }
        val strategyTune = try { LiveStrategyTuner.adjustment(tuneLane) } catch (_: Throwable) { LiveStrategyTuner.adjustment("STANDARD") }
        // V5.0.4579 — toxic buckets pivot strategy inside the same lane instead
        // of buying the same failing setup smaller. Keep lane affinity, but switch
        // the style/tactic surface toward defensive/reclaim behavior before sizing.
        val toxicTacticPivot4584 = strategyTune.label == "toxic_reclaim_tactic_pivot" || strategyTune.label == "toxic_inner_lane_pivot"
        val tunedBaseStyle = if (toxicTacticPivot4584) sameLaneWeakPivotStyle(laneHint, Style.TOXIC_RECLAIM_TACTIC) else style
        val tunedSizeFloor = if (toxicTacticPivot4584) 0.18 else 0.18
        val tunedSize = (tunedBaseStyle.sizeMult * strategyTune.sizeMult).coerceIn(tunedSizeFloor, 1.95)
        val tunedTp = (tunedBaseStyle.tpMult * strategyTune.tpMult).coerceIn(0.65, 2.15)
        val tunedHold = (tunedBaseStyle.holdMult * strategyTune.holdMult).coerceIn(0.35, 4.00)
        return Decision(
            style = tunedBaseStyle,
            tactic = tactic,
            tradeType = classification.tradeType,
            confidence = maxOf(classification.confidence, sheet.confidence),
            reason = "type=${classification.tradeType} tactic=$tactic toolkit=${sheet.setup} chart=${sheet.chartPattern} entry=${sheet.entryStyle} exit=${sheet.exitStyle} hold×=${"%.2f".format(sheet.holdMult)} size×=${"%.2f".format(sheet.sizeMult)} tp×=${"%.2f".format(sheet.tpMult)} tunedSize×=${"%.2f".format(tunedSize)} tunedTp×=${"%.2f".format(tunedTp)} tunedHold×=${"%.2f".format(tunedHold)} tune=${strategyTune.compact} dd=${"%.2f".format(ddAgg)} age=${"%.1f".format(ageMin)} liq=${ts.lastLiquidityUsd.toInt()} bp=${ts.lastBuyPressurePct.toInt()} why=${sheet.compactReason}",
            toolkit = sheet,
            tunedSizeMult = tunedSize,
            tunedTpMult = tunedTp,
            tunedHoldMult = tunedHold,
        )
    }

    fun lanesFor(ts: TokenState, classification: ModeRouter.Classification, base: Set<String>): Set<String> {
        val d = decide(ts, classification)
        val score = (ts.lastV3Score ?: ts.entryScore.toInt()).coerceIn(-100, 150)
        return boundedLanes(ts.mint, base + d.toolkit.laneVotes, d.style, score)
    }

    fun toolsFor(ts: TokenState, classification: ModeRouter.Classification, base: Set<String>): Set<String> {
        val d = decide(ts, classification)
        return boundedTools(ts.mint, base + d.toolkit.toolVotes, d.style)
    }
}
