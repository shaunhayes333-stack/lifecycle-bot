package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import kotlin.math.max
import kotlin.math.min

/**
 * V5.0.4033 — TOKEN METRIC STAGE ROUTER.
 *
 * Source fix for the operator report: the bot was buying ten-heavy/rug-prone
 * coins and buying the wrong part of the cycle (peaks instead of base/start or
 * mid-accumulation). Scanner/lane routing must be token-metrics aware before a
 * lane is allowed to turn a candidate into live capital.
 *
 * Hot-path safe: no network, no LLM, only TokenState/history/cache fields.
 */
object TokenMetricStageRouter {
    const val VERSION = "V5.0.4033_TOKEN_METRIC_STAGE_ROUTER"

    enum class Stage { FRESH_LAUNCH, BASE_START, MID_ACCUMULATION, CONTROLLED_MARKUP, PEAK_EXHAUSTION, DUMPING, RUG_PRONE, UNKNOWN }

    data class Snapshot(
        val stage: Stage,
        val ageMin: Double,
        val currentVsPeak: Double,
        val drawdownFromPeakPct: Double,
        val runupFromLocalLowPct: Double,
        val buyPressurePct: Double,
        val sellPressurePct: Double,
        val liquidityUsd: Double,
        val marketCapUsd: Double,
        val mcapToLiq: Double,
        val topHolderPct: Double,
        val reason: String,
    ) {
        val compact: String get() =
            "$VERSION stage=$stage age=${ageMin.toInt()}m peakPos=${"%.2f".format(currentVsPeak)} dd=${drawdownFromPeakPct.toInt()}% runup=${runupFromLocalLowPct.toInt()}% bp=${buyPressurePct.toInt()} sp=${sellPressurePct.toInt()} liq=${liquidityUsd.toInt()} mcapLiq=${"%.1f".format(mcapToLiq)} top=${topHolderPct.toInt()} $reason"
    }

    data class LaneFit(val allowed: Boolean, val lane: String, val stage: Stage, val reason: String)

    fun snapshot(ts: TokenState): Snapshot = try {
        val hist = ts.history.toList().filter { it.priceUsd > 0.0 }
        val now = System.currentTimeMillis()
        val ageMin = ((now - ts.addedToWatchlistAt) / 60_000.0).coerceAtLeast(0.0)
        val prices = hist.map { it.priceUsd }
        val current = (prices.lastOrNull() ?: ts.lastPrice).takeIf { it > 0.0 } ?: 0.0
        val local = prices.takeLast(24).ifEmpty { prices }
        val peak = local.maxOrNull()?.takeIf { it > 0.0 } ?: current.takeIf { it > 0.0 } ?: 0.0
        val low = local.minOrNull()?.takeIf { it > 0.0 } ?: current.takeIf { it > 0.0 } ?: 0.0
        val currentVsPeak = if (peak > 0.0 && current > 0.0) (current / peak).coerceIn(0.0, 2.0) else 0.0
        val dd = if (peak > 0.0 && current > 0.0) ((peak - current) / peak * 100.0).coerceAtLeast(0.0) else 0.0
        val runup = if (low > 0.0 && current > 0.0) ((current - low) / low * 100.0).coerceAtLeast(0.0) else 0.0
        val bp = ts.lastBuyPressurePct.coerceIn(0.0, 100.0)
        val sp = ts.lastSellPressurePct.coerceIn(0.0, 100.0)
        val liq = ts.lastLiquidityUsd.coerceAtLeast(0.0)
        val mcap = max(ts.lastMcap, ts.lastFdv).coerceAtLeast(0.0)
        val mcapToLiq = if (liq > 0.0) (mcap / liq).coerceIn(0.0, 10_000.0) else 9999.0
        val top = (ts.safety.topHolderPct.takeIf { it > 0.0 } ?: ts.topHolderPct ?: -1.0)
        val liqThin = liq in 1.0..2_500.0
        val topHeavy = top >= 35.0
        val valuationAir = mcapToLiq >= 85.0 && mcap >= 150_000.0
        val rugProne = topHeavy || (valuationAir && (sp >= 55.0 || bp < 52.0)) || (liqThin && runup >= 80.0)
        val peakExhaustion = currentVsPeak >= 0.88 && runup >= 70.0 && (sp >= 52.0 || bp < 52.0 || ts.lastPriceChange1h >= 80.0)
        val dumping = dd >= 24.0 && (bp < 52.0 || sp >= 55.0)
        val baseStart = ageMin <= 12.0 && runup <= 45.0 && dd <= 18.0 && bp >= 54.0 && sp <= 50.0 && liq >= 3_000.0
        val midAccum = currentVsPeak in 0.45..0.82 && dd in 8.0..35.0 && bp >= 50.0 && sp <= 54.0 && liq >= 8_000.0
        val markup = currentVsPeak in 0.68..0.92 && runup in 25.0..120.0 && bp >= 55.0 && sp <= 50.0 && liq >= 6_000.0
        // V5.0.4076 — FRESH_LAUNCH stage. Operator P0: bot starves on
        // pump.fun firehose because the classifier requires price history
        // bands that simply do not exist for age=0-2m tokens. peakPos always
        // returns 1.0 for a token that hasn't moved yet, killing every
        // peakPos-based check. Snapshot ground truth showed entries like:
        //   age=0m peakPos=1.00 dd=0% runup=0% bp=50 sp=50 liq=$245-$5297
        // The full FRESH_LAUNCH gate accepts: very young (<= 3 min), no
        // meaningful peak yet (history < 3 ticks OR peakPos >= 0.98), and
        // minimum survivable liquidity ($800 floor — below this the
        // HardRugPreFilter / PROVIDER_PROOF gates take over). Routes only
        // to fast-cycle meme lanes (SHITCOIN / PROJECT_SNIPER / EXPRESS /
        // MOONSHOT). Never routes to BLUECHIP/QUALITY/TREASURY which need
        // real price history to size correctly.
        val freshLaunch = !baseStart && !midAccum && !markup &&
            ageMin <= 3.0 && (hist.size < 3 || currentVsPeak >= 0.98) &&
            liq >= 800.0 && sp <= 70.0
        val stage = when {
            rugProne -> Stage.RUG_PRONE
            peakExhaustion -> Stage.PEAK_EXHAUSTION
            dumping -> Stage.DUMPING
            baseStart -> Stage.BASE_START
            midAccum -> Stage.MID_ACCUMULATION
            markup -> Stage.CONTROLLED_MARKUP
            freshLaunch -> Stage.FRESH_LAUNCH
            else -> Stage.UNKNOWN
        }
        Snapshot(stage, ageMin, currentVsPeak, dd, runup, bp, sp, liq, mcap, mcapToLiq, top, reasonFor(stage, rugProne, peakExhaustion, dumping, baseStart, midAccum, markup, freshLaunch))
    } catch (t: Throwable) {
        Snapshot(Stage.UNKNOWN, 999.0, 0.0, 0.0, 0.0, 50.0, 50.0, 0.0, 0.0, 9999.0, -1.0, "stage_error=${t.javaClass.simpleName}")
    }

    fun preferredPrimaryLane(ts: TokenState, fallback: String): String {
        val s = snapshot(ts)
        return when (s.stage) {
            Stage.FRESH_LAUNCH -> when {
                s.liquidityUsd >= 5_000.0 && s.buyPressurePct >= 56.0 -> "MOONSHOT"
                s.liquidityUsd >= 2_500.0 -> "PROJECT_SNIPER"
                s.liquidityUsd >= 1_500.0 -> "EXPRESS"
                else -> "SHITCOIN"
            }
            Stage.BASE_START -> if (s.liquidityUsd >= 8_000.0 && s.buyPressurePct >= 58.0) "PROJECT_SNIPER" else "SHITCOIN"
            Stage.MID_ACCUMULATION -> if (s.liquidityUsd >= 20_000.0) "BLUECHIP" else "QUALITY"
            Stage.CONTROLLED_MARKUP -> "MOONSHOT"
            Stage.DUMPING -> "DIP_HUNTER"
            Stage.PEAK_EXHAUSTION, Stage.RUG_PRONE -> "QUALITY" // observation/defensive owner; laneFit blocks live buy exposure
            Stage.UNKNOWN -> fallback.uppercase().ifBlank { "STANDARD" }
        }
    }

    fun laneFit(ts: TokenState, laneRaw: String): LaneFit {
        val lane = laneRaw.uppercase()
        val s = snapshot(ts)
        val allowed = when (s.stage) {
            Stage.RUG_PRONE -> false
            Stage.PEAK_EXHAUSTION -> lane in setOf("DIP_HUNTER", "QUALITY") && s.drawdownFromPeakPct >= 12.0 && s.buyPressurePct >= 54.0
            Stage.DUMPING -> lane == "DIP_HUNTER" && s.drawdownFromPeakPct >= 25.0 && s.buyPressurePct >= 55.0
            Stage.FRESH_LAUNCH -> lane in setOf("SHITCOIN", "PROJECT_SNIPER", "EXPRESS", "MOONSHOT", "STANDARD", "CORE", "V3")
            Stage.BASE_START -> lane in setOf("SHITCOIN", "PROJECT_SNIPER", "EXPRESS", "STANDARD", "CORE", "V3")
            Stage.MID_ACCUMULATION -> lane in setOf("QUALITY", "BLUECHIP", "TREASURY", "STANDARD", "CORE", "V3")
            Stage.CONTROLLED_MARKUP -> lane in setOf("MOONSHOT", "QUALITY", "STANDARD", "CORE", "V3")
            Stage.UNKNOWN -> lane in setOf("QUALITY", "BLUECHIP", "TREASURY", "STANDARD", "CORE", "V3") && s.liquidityUsd >= 12_000.0 && s.mcapToLiq <= 65.0 && s.buyPressurePct >= 52.0
        }
        return LaneFit(allowed, lane, s.stage, s.compact)
    }

    fun reasonFor(stage: Stage, rug: Boolean, peak: Boolean, dump: Boolean, base: Boolean, mid: Boolean, markup: Boolean, fresh: Boolean = false): String = when (stage) {
        Stage.RUG_PRONE -> "rugProne=$rug topHeavy/liquidityAir/thinRunup"
        Stage.PEAK_EXHAUSTION -> "peakExhaustion=$peak nearHigh+extended+sellPressure"
        Stage.DUMPING -> "dumping=$dump drawdown+weakBP"
        Stage.FRESH_LAUNCH -> "freshLaunch=$fresh ageBelow3m+noHistoryYet+minSurvivableLiq"
        Stage.BASE_START -> "baseStart=$base early+notExtended+buyPressure"
        Stage.MID_ACCUMULATION -> "midAccum=$mid pullbackBand+liq+controlledSP"
        Stage.CONTROLLED_MARKUP -> "markup=$markup controlledRunup+bp+liq"
        Stage.UNKNOWN -> "unknown=noCleanMetricStage"
    }
}
