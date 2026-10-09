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
        // V5.0.7401 — token age is launch age, not "time since AATE noticed it".
        // A trending token discovered after its pump used to reset to age=0 and
        // was routed back into PROJECT_SNIPER as FRESH_LAUNCH.
        val launch7401 = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts, now) } catch (_: Throwable) { null }
        val ageMin = try { com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, now) ?: Double.NaN } catch (_: Throwable) { Double.NaN }
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
        // V5.0.7306 — a single pool's depth against a large cap's whole
        // valuation is normally hundreds of x (BONK read mcapLiq=780 on one
        // $428K pool and was staged RUG_PRONE, so it could never be BLUECHIP).
        // Valuation air is a thin-meme test; it does not apply to an
        // established asset.
        val valuationAir = mcapToLiq >= 85.0 && mcap >= 150_000.0 && !isEstablished7306(ts, mcap, liq, ageMin)
        val rugProne = topHeavy || (valuationAir && (sp >= 55.0 || bp < 52.0)) || (liqThin && runup >= 80.0)
        val acute5m7425 = ts.lastPriceChange5m.takeIf { it.isFinite() } ?: 0.0
        val pumpThenFade7425 = ts.lastPriceChange1h >= 80.0 && acute5m7425 <= -8.0
        val peakExhaustion = (currentVsPeak >= 0.88 && runup >= 70.0 &&
            (sp >= 52.0 || bp < 52.0 || ts.lastPriceChange1h >= 80.0)) || pumpThenFade7425
        val dumping = acute5m7425 <= -18.0 || (dd >= 24.0 && (bp < 52.0 || sp >= 55.0))
        val baseStart = ageMin <= 12.0 && runup <= 45.0 && dd <= 18.0 && bp >= 54.0 && sp <= 50.0 &&
            acute5m7425 > -8.0 && liq >= 3_000.0
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
        // V5.0.7401 — a fresh launch must still be in the launch/ignition phase.
        // "Young on our watchlist" is not enough, and an already-expanded/fading
        // token is explicitly not fresh even if it is only minutes old.
        val lifecycleEarly7401 = launch7401?.phase in setOf(
            com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.PRE_IGNITION,
            com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION,
            com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.EXPANDING,
        )
        val lifecycleFade7401 = launch7401?.tooLateForSnipe == true
        val freshLaunch = !baseStart && !midAccum && !markup && !lifecycleFade7401 &&
            !dumping && !pumpThenFade7425 &&
            ageMin <= 3.0 && lifecycleEarly7401 &&
            (hist.size < 5 || currentVsPeak >= 0.88) &&
            liq >= 800.0 && sp <= 65.0 && acute5m7425 > -12.0
        val stage = when {
            rugProne -> Stage.RUG_PRONE
            lifecycleFade7401 && dd >= 18.0 -> Stage.DUMPING
            lifecycleFade7401 -> Stage.PEAK_EXHAUSTION
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

    /**
     * V5.0.7306 — "established" without a watchlist clock. ageMin here is time
     * since the token joined THIS session's watchlist, so the 4091 override's
     * age>=60 test meant no established token could be BLUECHIP/DIP_HUNTER in
     * the first hour of any session (5.0.7305 ran 9 minutes: $WIF, BONK and
     * FWOG all elected QUALITY). Pool age is never populated, so the evidence
     * used is what exists: an established-universe scanner source, or scale
     * no fresh launch reaches, with the watchlist clock kept as a third route.
     */
    private fun isEstablished7306(ts: TokenState, mcap: Double, liq: Double, watchAgeMin: Double): Boolean {
        if (mcap < 5_000_000.0 || liq < 50_000.0) return false
        val src = ts.source.uppercase()
        val establishedSource = src.contains("BLUECHIP") || src.contains("ESTABLISHED") ||
            src.contains("COINGECKO") || src.contains("MARKET_HUNT_TREASURY")
        return establishedSource || mcap >= 50_000_000.0 || watchAgeMin >= 60.0
    }

    fun preferredPrimaryLane(ts: TokenState, fallback: String): String {
        val lane = preferredPrimaryLaneByStage(ts, fallback)
        return try { evidenceReroute7940(lane, snapshot(ts).stage) } catch (_: Throwable) { lane }
    }

    /**
     * V5.0.7940 — feed each lane the tokens it is paid for. The stage sheet picks the
     * owner by shape; the lanes' own forward labels then say whether that owner pays
     * at this stage. When the chosen lane is proven losing (LiveEdgeGate7877 bar) and
     * another lane that plays this stage has a measured positive record (n >= 50,
     * mean - se > 0), the token goes to the paying lane. 5.0.7937: FRESH_LAUNCH went
     * to MOONSHOT (labels n=251 -8.2%) while EXPRESS read n=85 +12.7% run 14%.
     */
    fun evidenceReroute7940(lane: String, stage: Stage): String {
        val stats = LANE_STAGE_SHEET_7928[stage].orEmpty().filter { it !in TRUNK_LANES_7940 }
            .associateWith { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.laneStatFor7737(it) }
        val own = stats[lane.uppercase()] ?: com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.laneStatFor7737(lane.uppercase())
        val to = rerouteTarget7940(lane.uppercase(), own, stats)
        if (to != lane.uppercase()) try { PipelineHealthCollector.labelInc("LANE_REROUTED_BY_EVIDENCE_7940_${lane.uppercase()}_TO_$to") } catch (_: Throwable) {}
        return to
    }

    /** Pure: the lane a token should go to, given the owner's and the alternatives' 60-minute records. */
    fun rerouteTarget7940(
        lane: String,
        own: com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.CellStat?,
        alternatives: Map<String, com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.CellStat?>,
    ): String {
        if (!com.lifecyclebot.engine.truth.LiveEdgeGate7877.laneProvenLosing7938(own)) return lane
        val best = alternatives.entries
            .filter { (l, st) -> l != lane && st != null && st.n60 >= REROUTE_MIN_N_7940 &&
                st.stderr60Pct.isFinite() && st.meanNet60Pct - st.stderr60Pct > 0.0 }
            .maxByOrNull { (_, st) -> st!!.meanNet60Pct - st.stderr60Pct }
        return best?.key ?: lane
    }

    private const val REROUTE_MIN_N_7940 = 50
    private val TRUNK_LANES_7940 = setOf("STANDARD", "CORE", "V3")

    private fun preferredPrimaryLaneByStage(ts: TokenState, fallback: String): String {
        val s = snapshot(ts)
        // V5.0.4091 — ESTABLISHED-TOKEN BLUECHIP OVERRIDE (operator P0: intake
        // starvation. BLUECHIP/DIP_HUNTER/MANIPULATED/CYCLIC show 0 lane evals
        // because the stage-only routing never elevated real established tokens
        // — BLUECHIP fired only on MID_ACCUMULATION + liq>=\$20K which fresh
        // pump.fun-heavy scanners never produce). Pre-check: if a token has
        // mcap>=\$5M AND liq>=\$50K AND age>=60min, it IS an established asset
        // regardless of metric stage classification. Route to BLUECHIP so the
        // established-token half of the universe starts generating samples for
        // the 2x-5x daily wallet-growth target. Memes don't accidentally hit
        // this gate because pump.fun launches rarely have \$5M mcap + \$50K liq
        // + 60min age all at once.
        if (isEstablished7306(ts, s.marketCapUsd, s.liquidityUsd, s.ageMin) &&
            s.stage != Stage.RUG_PRONE && s.stage != Stage.PEAK_EXHAUSTION && s.stage != Stage.DUMPING) {
            // Dip-buyable established tokens go to DIP_HUNTER, otherwise BLUECHIP.
            return if (s.drawdownFromPeakPct >= 15.0 && s.buyPressurePct >= 50.0) "DIP_HUNTER" else "BLUECHIP"
        }
        return when (s.stage) {
            Stage.FRESH_LAUNCH -> {
                // V5.0.7401 — ownership follows launch timing, not chart maturity.
                // IGNITION belongs to the sniper/runner desks before the crowd;
                // once it is merely EXPANDING, prefer MOONSHOT only when demand
                // is still strong. POST_PUMP_FADE never reaches FRESH_LAUNCH.
                val lp = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null }
                when {
                    lp?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION -> "PROJECT_SNIPER"
                    lp?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.PRE_IGNITION &&
                        s.liquidityUsd >= 1_500.0 -> "PROJECT_SNIPER"
                    lp?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.EXPANDING &&
                        s.liquidityUsd >= 5_000.0 && s.buyPressurePct >= 56.0 -> "MOONSHOT"
                    s.liquidityUsd >= 2_500.0 -> "PROJECT_SNIPER"
                    s.liquidityUsd >= 1_500.0 -> "EXPRESS"
                    else -> "SHITCOIN"
                }
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
        // V5.0.7425 — DIP_HUNTER is a reclaim desk, not a falling-knife desk.
        // A post-pump/dumping token needs an actual turn in the local tape before
        // any non-MANIPULATED lane can convert the observation into live capital.
        val recent7425 = try { ts.history.toList().map { it.priceUsd }.filter { it.isFinite() && it > 0.0 }.takeLast(5) } catch (_: Throwable) { emptyList() }
        val reclaimConfirmed7425 = recent7425.size >= 3 &&
            recent7425.last() > recent7425.minOrNull()!! * 1.06 &&
            recent7425.takeLast(3).zipWithNext().all { (a, b) -> b >= a * 0.985 } &&
            ts.lastPriceChange5m > 0.0 &&
            s.buyPressurePct >= 56.0 && s.sellPressurePct <= 50.0
        // V5.0.4091 — established-token lane-fit override mirrors the primary
        // routing override above. Without this, the primary picks BLUECHIP but
        // the per-stage laneFit allowlist rejects it (e.g. CONTROLLED_MARKUP
        // only allows MOONSHOT/QUALITY/STANDARD/CORE/V3) and the trade gets
        // re-routed back to memes, defeating the purpose. An established asset
        // is fit for BLUECHIP/DIP_HUNTER/QUALITY/TREASURY at any non-toxic
        // stage.
        // V5.0.7607 — use the SAME established-token predicate as primary routing.
        // 7306 removed the false first-hour starvation from preferredPrimaryLane(),
        // but laneFit() kept the obsolete watchAge>=60 requirement and could reject
        // BLUECHIP/DIP_HUNTER/QUALITY/TREASURY immediately after routing selected them.
        val isEstablished = isEstablished7306(ts, s.marketCapUsd, s.liquidityUsd, s.ageMin)
        if (isEstablished && s.stage != Stage.RUG_PRONE && s.stage != Stage.PEAK_EXHAUSTION) {
            if (lane in setOf("BLUECHIP", "DIP_HUNTER", "QUALITY", "TREASURY", "STANDARD", "CORE", "V3")) {
                return LaneFit(true, lane, s.stage, s.compact)
            }
        }
        val allowed = when (s.stage) {
            Stage.RUG_PRONE -> false
            Stage.PEAK_EXHAUSTION -> reclaimConfirmed7425 &&
                lane in setOf("DIP_HUNTER", "QUALITY") && s.drawdownFromPeakPct >= 12.0
            Stage.DUMPING -> reclaimConfirmed7425 &&
                lane == "DIP_HUNTER" && s.drawdownFromPeakPct >= 25.0
            Stage.FRESH_LAUNCH, Stage.BASE_START, Stage.MID_ACCUMULATION, Stage.CONTROLLED_MARKUP ->
                lane in LANE_STAGE_SHEET_7928.getValue(s.stage)
            Stage.UNKNOWN -> lane in setOf("QUALITY", "BLUECHIP", "TREASURY", "STANDARD", "CORE", "V3") && s.liquidityUsd >= 12_000.0 && s.mcapToLiq <= 65.0 && s.buyPressurePct >= 52.0
        }
        return LaneFit(allowed, lane, s.stage, s.compact)
    }

    /**
     * V5.0.7928 — the stage cheat sheet: which lanes play each clean lifecycle
     * stage. Launch desks buy the launch and the base; the accumulation desks buy
     * the pullback band; MOONSHOT/QUALITY ride a controlled markup. Peak
     * exhaustion and dumping need a confirmed reclaim (DIP_HUNTER/QUALITY only),
     * rug-prone never. This is the prior; [liveStageRefusal7928] lets each lane's
     * own forward labels at each stage overrule it in either direction.
     */
    val LANE_STAGE_SHEET_7928: Map<Stage, Set<String>> = mapOf(
        Stage.FRESH_LAUNCH to setOf("SHITCOIN", "PROJECT_SNIPER", "EXPRESS", "MOONSHOT", "STANDARD", "CORE", "V3"),
        Stage.BASE_START to setOf("SHITCOIN", "PROJECT_SNIPER", "EXPRESS", "STANDARD", "CORE", "V3"),
        Stage.MID_ACCUMULATION to setOf("QUALITY", "BLUECHIP", "TREASURY", "DIP_HUNTER", "STANDARD", "CORE", "V3"),
        Stage.CONTROLLED_MARKUP to setOf("MOONSHOT", "QUALITY", "STANDARD", "CORE", "V3"),
    )

    private const val STAGE_MIN_N_7928 = 30

    /**
     * Pure. V5.0.7928 — the lane x stage verdict. A measured record (n60 >= 30)
     * decides: mean above zero by a standard error (or a positive 4-hour mean) admits
     * even off the sheet; mean below -2% by a standard error refuses even on it. An
     * unmeasured pair follows the sheet. UNKNOWN stage is no stage evidence at all.
     * Returns null to admit, else the refusal reason.
     */
    fun judgeStage7928(lane: String, stage: Stage, sheetAllows: Boolean,
                       stat: com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.CellStat?): String? {
        if (stage == Stage.UNKNOWN) return null
        // Rug-prone (top-heavy / liquidity air) is safety, not a learnable stage.
        if (stage == Stage.RUG_PRONE) return "STAGE_RUG_PRONE_7928_$lane"
        // A lane the sheet does not cover has no stage prior; only its own record can refuse it.
        val covered = LANE_STAGE_SHEET_7928.values.any { lane in it }
        if (stat != null && stat.n60 >= STAGE_MIN_N_7928) {
            val se = if (stat.stderr60Pct.isFinite()) stat.stderr60Pct else 0.0
            val late = stat.n240 >= STAGE_MIN_N_7928 && stat.meanNet240Pct > 0.0
            if (stat.meanNet60Pct - se > 0.0 || late) return null
            if (stat.meanNet60Pct + se < -2.0) return "STAGE_PROVEN_LOSING_7928_${lane}_${stage.name}"
        }
        return if (sheetAllows || !covered) null else "STAGE_LANE_MISFIT_7928_${lane}_${stage.name}"
    }

    /** V5.0.7928 — LIVE: is this the right point in the token's life for this lane's play? */
    fun liveStageRefusal7928(ts: TokenState, lane: String): String? = try {
        val fit = laneFit(ts, lane)
        val why = judgeStage7928(fit.lane, fit.stage, fit.allowed,
            com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.stageStatFor7928(fit.lane, fit.stage.name))
        try { PipelineHealthCollector.labelInc(if (why == null) "STAGE_FIT_ADMIT_7928_${fit.stage.name}" else "STAGE_FIT_REFUSED_7928_${fit.stage.name}") } catch (_: Throwable) {}
        why
    } catch (_: Throwable) { null }

    fun reasonFor(stage: Stage, rug: Boolean, peak: Boolean, dump: Boolean, base: Boolean, mid: Boolean, markup: Boolean, fresh: Boolean = false): String = when (stage) {
        Stage.RUG_PRONE -> "rugProne=$rug topHeavy/liquidityAir/thinRunup"
        Stage.PEAK_EXHAUSTION -> "peakExhaustion=$peak nearHigh+extended+sellPressure"
        Stage.DUMPING -> "dumping=$dump drawdown_or_acute5m_cascade_reclaim_required"
        Stage.FRESH_LAUNCH -> "freshLaunch=$fresh trueLaunchAge+ignitionOrExpansion+notPostPumpFade"
        Stage.BASE_START -> "baseStart=$base early+notExtended+buyPressure"
        Stage.MID_ACCUMULATION -> "midAccum=$mid pullbackBand+liq+controlledSP"
        Stage.CONTROLLED_MARKUP -> "markup=$markup controlledRunup+bp+liq"
        Stage.UNKNOWN -> "unknown=noCleanMetricStage"
    }
}
