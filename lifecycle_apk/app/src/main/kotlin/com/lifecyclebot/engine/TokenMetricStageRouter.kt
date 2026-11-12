package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.market.EntryFlow8506
import com.lifecyclebot.engine.truth.SetupFeatureResolver8169
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

    enum class Stage { FRESH_LAUNCH, BASE_START, MID_ACCUMULATION, CONTROLLED_MARKUP, PEAK_EXHAUSTION, DUMPING, RUG_PRONE, UNKNOWN, NO_SETUP }

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
            "$VERSION stage=$stage age=${ageMin.takeIf { it.isFinite() }?.let { "${it.toInt()}m" } ?: "unresolved"} peakPos=${"%.2f".format(currentVsPeak)} dd=${drawdownFromPeakPct.toInt()}% runup=${runupFromLocalLowPct.toInt()}% bp=${buyPressurePct.toInt()} sp=${sellPressurePct.toInt()} liq=${liquidityUsd.toInt()} mcapLiq=${"%.1f".format(mcapToLiq)} top=${topHolderPct.toInt()} $reason"
    }

    data class LaneFit(val allowed: Boolean, val lane: String, val stage: Stage, val reason: String)

    /** Freshest observed unit price, never a market-cap surrogate. Stale chart
     * history remains useful for support/peak but cannot become a current mark.
     */
    fun freshestStagePrice8171(historyPrice: Double, historyAtMs: Long, tickPrice: Double, tickAtMs: Long): Double {
        val histOk = historyPrice.isFinite() && historyPrice > 0.0
        val tickOk = tickPrice.isFinite() && tickPrice > 0.0
        return when {
            tickOk && (tickAtMs > historyAtMs || !histOk) -> tickPrice
            histOk -> historyPrice
            tickOk -> tickPrice
            else -> 0.0
        }
    }

    fun snapshot(ts: TokenState): Snapshot {
        val now = System.currentTimeMillis()
        return snapshot(ts, now, EntryFlow8506.provider(ts.mint, now))
    }

    internal fun snapshot(ts: TokenState, now: Long, flow: EntryFlow8506?): Snapshot = try {
        val hist = synchronized(ts.history) { ts.history.filter { it.priceUsd.isFinite() && it.priceUsd > 0.0 } }
        // V5.0.7401 — token age is launch age, not "time since AATE noticed it".
        // A trending token discovered after its pump used to reset to age=0 and
        // was routed back into PROJECT_SNIPER as FRESH_LAUNCH.
        val launch7401 = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts, now) } catch (_: Throwable) { null }
        val ageMin = try { com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, now) ?: Double.NaN } catch (_: Throwable) { Double.NaN }
        val prices = hist.map { it.priceUsd }
        // V5.0.8171: a stale last candle must not displace the latest verified
        // token tick when staging an active breakout. 8169 had 9k+ stage
        // mismatches and insufficient OHLCV while fresh pump trades arrived.
        val lastHistory = hist.lastOrNull()
        val current = freshestStagePrice8171(lastHistory?.priceUsd ?: Double.NaN,
            lastHistory?.ts ?: 0L, ts.lastPrice, ts.lastPriceUpdate)
        val newestTick = ts.lastPriceUpdate > 0L && ts.lastPriceUpdate > (lastHistory?.ts ?: 0L) && current > 0.0
        val local = (prices.takeLast(24) + if (newestTick) listOf(current) else emptyList()).takeLast(24)
        val closePeak = local.maxOrNull()?.takeIf { it > 0.0 } ?: current.coerceAtLeast(0.0)
        // A second observed bar corroborates a wick. A single outlier cannot manufacture a dump.
        var highest = 0.0
        var corroborated = 0.0
        for (bar in hist.takeLast(24)) {
            if (bar.synthetic || !bar.highUsd.isFinite() || bar.highUsd < bar.priceUsd) continue
            if (bar.highUsd >= highest) { corroborated = highest; highest = bar.highUsd }
            else if (bar.highUsd > corroborated) corroborated = bar.highUsd
        }
        val peak = maxOf(closePeak, corroborated)
        val low = local.minOrNull()?.takeIf { it > 0.0 } ?: current.takeIf { it > 0.0 } ?: 0.0
        val currentVsPeak = if (peak > 0.0 && current > 0.0) (current / peak).coerceIn(0.0, 2.0) else 0.0
        val dd = if (peak > 0.0 && current > 0.0) ((peak - current) / peak * 100.0).coerceAtLeast(0.0) else 0.0
        val runup = if (low > 0.0 && current > 0.0) ((current - low) / low * 100.0).coerceAtLeast(0.0) else 0.0
        val freshLocalBook = LaneInputTruth8236.fresh8491(ts.buyPressureObservedAtMs8084, now)
        val bp = flow?.buyPct?.takeIf { it.isFinite() } ?: if (freshLocalBook) ts.lastBuyPressurePct else 50.0
        val sp = if (flow?.buyPct?.isFinite() == true) 100.0 - bp else if (freshLocalBook) ts.lastSellPressurePct else 50.0
        // V5.0.8712 — the pool depth every other owner reads (LiquidityTruth8037: the venue's own reserves when it has them),
        // before the raw last field: a graduated coin still carrying its curve-era figure read "thin" for life.
        val liq = (try { com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { Double.NaN }).takeIf { it.isFinite() && it > 0.0 }
            ?: ts.lastLiquidityUsd.takeIf { it.isFinite() && it > 0 }
            ?: SetupFeatureResolver8169.observedLiquidity8170(ts.mint, ageMin, now) ?: 0.0
        val mcap = ts.lastMcap.takeIf { it.isFinite() && it > 0.0 } ?: ts.lastFdv.coerceAtLeast(0.0)
        val mcapToLiq = if (liq > 0.0) (mcap / liq).coerceIn(0.0, 10_000.0) else 9999.0
        // V5.0.8568 — then the one store's reading (any source that measured it: safety, rugcheck holders, DAS), never a default
        val top = (ts.safety.topHolderPct.takeIf { it > 0.0 } ?: ts.topHolderPct
            ?: (try { com.lifecyclebot.engine.truth.TokenFacts.topHolderPct(ts) } catch (_: Throwable) { null }) ?: -1.0)
        val liqThin = liq in 1.0..2_500.0
        // V5.0.7306 — a single pool's depth against a large cap's whole
        // valuation is normally hundreds of x (BONK read mcapLiq=780 on one
        // $428K pool and was staged RUG_PRONE, so it could never be BLUECHIP).
        // Valuation air is a thin-meme test; it does not apply to an
        // established asset.
        // V5.0.8464 §A_DEFAULT_IS_NOT_A_READING (8236) — valuation air needs a READ pool and an observed book. An unread pool
        // (liq 0 -> mcapToLiq 9999) with the default 50 / 50 buy pressure (bp < 52) staged every non-established $150k+ coin
        // RUG_PRONE: refused STRUCTURAL_RISK_8136, routed observation-only and blocked live (8457: STRUCTURAL_RISK n47 with a
        // 21 % runner share, 28 of the 65 wrongly refused runners). A read pool and a real book still stage it RUG_PRONE;
        // the thin-liquidity-runup test is unchanged.
        val bookObserved8464 = freshLocalBook || flow?.buyPct?.isFinite() == true
        val valuationAir = liq > 0.0 && mcapToLiq >= 85.0 && mcap >= 150_000.0 && !isEstablished7306(ts, mcap, liq, ageMin)
        val airRug8500 = valuationAir && bookObserved8464 && (sp >= 55.0 || bp < 52.0)
        // V5.0.8698 §A_BONDING_CURVE_IS_NOT_A_THIN_POOL — on an ACTIVE pump.fun curve [liq] is the real SOL raised, and price
        // is a function of it: a coin at ~10-15 SOL raised reads "liquidity <= $2,500 and up >= 80 % from its low" by
        // construction, so the thin-liquidity run-up test staged ordinary curve progress RUG_PRONE. 8689 runner trace: 41 of
        // the 64 wrongly refused runners were lane-qualified coins stopped by STRUCTURAL_RISK_8136. A curve trades against
        // its VIRTUAL reserve (30 + real SOL — the depth FieldManual7715.depthUsdForImpact8337 already prices impact on), so
        // the thin-POOL test applies to pools only. A curve coin is still judged by its own cell / stage / setup records.
        val onActiveCurve8698 = try { com.lifecyclebot.engine.truth.CurveReserves7951.realSolIfActive8046(ts.mint, now) != null } catch (_: Throwable) { false }
        // V5.0.8712 §A_RUN_IS_NOT_A_LIFE_SENTENCE — "up >= 80 % from its low" stays true for every coin that ever ran and kept
        // half of it, so a thin pool that ran was RUG_PRONE for good: through the pullback, through the base, through the
        // second leg. Runner funnel, two captures running: STRUCTURAL_RISK_8136 refused 13 of 40 and 15 of 46 of the coins that
        // went 4x in 24 h - the largest single cause - while its own 5-minute record (n21 -27.1 %) kept it "protecting", because
        // the refusals it is graded on are the ones made at the top. The test is a chase test: a thin pool that has run AND
        // is still at its high. Off the high the coin is staged by what it is doing (pullback, base, dump) and each of those
        // stages answers to its own record on LIVE (LaneAuthority8577.stageLoses8626).
        val thinRug8500 = thinPoolRunup8698(liqThin, runup, onActiveCurve8698, currentVsPeak)
        // V5.0.8696 — top-10 concentration no longer stages a coin RUG_PRONE. On 13,321 labelled meme decisions the coins it
        // refused (top-10 >= 60 %, n366) did BETTER than the rest in both halves (-0.4 % / -6.0 % against -6.1 % / -8.2 %):
        // it was not protecting. Concentration stays with its owner: TokenSafetyChecker penalises it from 40 % and refuses
        // hard over 80 %. Thin-liquidity run-ups (median -35 .. -47 %) and valuation air (no runners refused) still stage.
        val rugProne = airRug8500 || thinRug8500
        // V5.0.8500 — which structural test staged it, so a refusal names its trigger (was "topHeavy/liquidityAir/thinRunup").
        val rugWhy8500 = rugWhy8500(airRug8500, thinRug8500)
        val acute5m7425 = flow?.move ?: ts.lastPriceChange5m.takeIf { LaneInputTruth8236.fresh8491(ts.priceChangeObservedAtMs8084, now) } ?: Double.NaN
        val hour = ts.lastPriceChange1h.takeIf { LaneInputTruth8236.fresh8491(ts.hourChangeObservedAtMs8506, now) }
            ?: com.lifecyclebot.engine.truth.PairFlow8169.peek8169(ts.mint)?.takeIf { LaneInputTruth8236.fresh8491(it.atMs, now) }?.chg1h ?: Double.NaN
        val pumpThenFade7425 = hour >= 80.0 && acute5m7425 <= -8.0
        val peakExhaustion = (currentVsPeak >= 0.88 && runup >= 70.0 &&
            (sp >= 52.0 || bp < 52.0 || hour >= 80.0)) || pumpThenFade7425
        val dumping = acute5m7425 <= -18.0 || (dd >= 24.0 && (bp < 52.0 || sp >= 55.0))
        val baseStart = (flow == null || flow.active) && ageMin <= 12.0 && runup <= 45.0 && dd <= 18.0 && bp >= 54.0 && sp <= 50.0 &&
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
        // V5.0.8473 §THE_CHART_NOT_THE_CLOCK — owner: "its not buying or discovering tokens in the right part of the chart /
        // token life cycle". The bands above left the two best entries without a stage: a coin AT its high early in a move
        // (markup needs 8 % under the high, exhaustion +70 %) and a tight base after the first 12 minutes or with an unread
        // launch age (8471: 55,765 UNKNOWN stagings; age=0m peakPos 0.96-1.00 runup 1-31 % bp 66-100 all UNKNOWN), so the
        // launch / markup desks were never routed them and no stage record was ever booked for them. The chart's own
        // position names them now — on real history (5+ prints) and an observed book (bp / sp read, not the 50 / 50 default).
        val chart8473 = chartStage8473(hist.count { !it.synthetic && now - it.ts in 0L..FlatChart8027.FLAT_WINDOW_MS }, bookObserved8464, ageMin, currentVsPeak, runup, dd, bp, sp,
            acute5m7425, liq, tapeLive = !(try { FlatChart8027.deadTape8476(ts.mint, now) } catch (_: Throwable) { true }))
        // A reported intrawindow rise is a lower bound on run-up, even before five local candles arrive.
        val flowMarkup = current > 0.0 && flow != null && flow.active && flow.buys >= 3 && flow.buyPct >= 55.0 && flow.move > 15.0 && flow.move < 70.0 &&
            currentVsPeak >= 0.92 && liq >= 6_000.0 && runup < 70.0
        val baseAnyAge8473 = chart8473 == Stage.BASE_START
        val breakout8473 = chart8473 == Stage.CONTROLLED_MARKUP || flowMarkup
        // V5.0.8316 — no clean metric stage (bp / chg / shape never arrived): a young coin's OWN executed prints name the
        // stage (TapeStage8316: dumping / exhausted on a sell-dominated tape off its peak, a build on early buying), so the
        // sheet's reclaim rule can stop a buy into a dump and the launch lanes buy builds. No prints: UNKNOWN as before.
        val tape8316 = if (rugProne || lifecycleFade7401 || peakExhaustion || dumping || baseStart || midAccum || markup || freshLaunch ||
            baseAnyAge8473 || breakout8473) null
            else com.lifecyclebot.engine.truth.TapeStage8316.stageFromTape8316(ts.mint, ageMin, now)
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
            breakout8473 -> Stage.CONTROLLED_MARKUP   // V5.0.8473 — an early breakout at its high
            baseAnyAge8473 -> Stage.BASE_START        // V5.0.8473 — a tight base near its high, buyers in control, any age
            tape8316 == null && flow != null && current > 0.0 && liq > 0.0 &&
                hist.count { !it.synthetic && now - it.ts in 0L..FlatChart8027.FLAT_WINDOW_MS } >= CHART_MIN_PRINTS_8473 -> Stage.NO_SETUP
            else -> tape8316?.stage ?: Stage.UNKNOWN
        }
        Snapshot(stage, ageMin, currentVsPeak, if (tape8316 != null) maxOf(dd, tape8316.offPeakPct) else dd, runup, bp, sp, liq, mcap, mcapToLiq, top,
            reasonFor(stage, rugProne, peakExhaustion, dumping, baseStart || baseAnyAge8473, midAccum, markup || breakout8473, freshLaunch) +
                (if (stage == Stage.RUG_PRONE) " why8500=$rugWhy8500" else "") +
                (if (breakout8473 && stage == Stage.CONTROLLED_MARKUP && !markup) " breakoutAtHigh8473" else "") +
                (if (baseAnyAge8473 && stage == Stage.BASE_START && !baseStart) " baseAnyAge8473" else "") +
                (tape8316?.let { " ${it.compact}" } ?: ""))
    } catch (t: Throwable) {
        Snapshot(Stage.UNKNOWN, Double.NaN, 0.0, 0.0, 0.0, 50.0, 50.0, 0.0, 0.0, 9999.0, -1.0, "stage_error=${t.javaClass.simpleName}")
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
        return try { evidenceReroute7940(lane, snapshot(ts).stage, ts) } catch (_: Throwable) { lane }
    }

    /**
     * V5.0.7940 — feed each lane the tokens it is paid for. The stage sheet picks the
     * owner by shape; the lanes' own forward labels then say whether that owner pays
     * at this stage. When the chosen lane is proven losing (LiveEdgeGate7877 bar) and
     * another lane that plays this stage has a measured positive record (n >= 50,
     * mean - se > 0), the token goes to the paying lane. 5.0.7937: FRESH_LAUNCH went
     * to MOONSHOT (labels n=251 -8.2%) while EXPRESS read n=85 +12.7% run 14%.
     */
    private fun evidenceReroute7940(lane: String, stage: Stage, ts: TokenState): String {
        // The evidence reroute must not reintroduce an age/phase-ineligible sniper after the stage owner rejected it.
        val launch = if (stage == Stage.FRESH_LAUNCH || stage == Stage.BASE_START)
            try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null } else null
        val stats = evidenceAlternativeLanes8527(
            stage, launch?.takeIf { it.birthResolved }?.ageMs, launch?.lifecycle8136?.stage,
        ).associateWith { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.laneStatFor7737(it) }
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
                st.stderr60Pct.isFinite() && st.meanNet60Pct - st.stderr60Pct > 0.0 &&
                // V5.0.7944 — vanished marks are booked in the mean; the rest must be mostly read.
                st.resolvedShare >= com.lifecyclebot.engine.truth.LiveEdgeGate7877.MIN_RESOLVED_SHARE_7944 }
            .maxByOrNull { (_, st) -> st!!.meanNet60Pct - st.stderr60Pct }
        return best?.key ?: lane
    }

    private const val REROUTE_MIN_N_7940 = 50
    /** V5.0.8473 — the history prints a chart-position stage needs (fewer reads every coin as "at its high, flat"). */
    private const val CHART_MIN_PRINTS_8473 = 5

    /**
     * Pure (V5.0.8473): the stage a coin's chart position names when the age-gated bands do not — an early breakout AT its
     * high (+15..70 % off the local low, within 8 % of the high: CONTROLLED_MARKUP) or a tight base near its high past the
     * launch window or of unread age (<= 15 % range, <= 12 % off the high: BASE_START). Both need real history
     * ([prints] >= CHART_MIN_PRINTS_8473), an observed book ([bookObserved]; a 50 / 50 default is no reading) with buyers in
     * control, no acute 5-minute drop and survivable liquidity. Null: no chart stage.
     */
    fun chartStage8473(prints: Int, bookObserved: Boolean, ageMin: Double, currentVsPeak: Double, runupPct: Double, ddPct: Double,
                       bp: Double, sp: Double, change5mPct: Double, liqUsd: Double, tapeLive: Boolean = true): Stage? {
        // [tapeLive]: trades across the last minutes (FlatChart8027.deadTape8476). A coin frozen for hours is "near its high
        // with no drawdown" by construction: 8483 staged COLORS / ANON (flat since 16:00, $19-21k) BASE_START and MOONSHOT bought.
        if (prints < CHART_MIN_PRINTS_8473 || !bookObserved || !(change5mPct > -8.0) || !tapeLive) return null
        if (currentVsPeak > 0.92 && runupPct > 15.0 && runupPct < 70.0 && bp >= 55.0 && sp <= 50.0 && liqUsd >= 6_000.0)
            return Stage.CONTROLLED_MARKUP
        val pastLaunch = !(ageMin.isFinite() && ageMin <= 12.0)
        if (pastLaunch && runupPct <= 15.0 && ddPct <= 12.0 && currentVsPeak >= 0.88 && bp >= 54.0 && sp <= 50.0 && liqUsd >= 3_000.0)
            return Stage.BASE_START
        return null
    }
    private val TRUNK_LANES_7940 = setOf("STANDARD", "CORE", "V3")

    /** Older liquid tokens with an UNKNOWN chart stage must not inherit a
     * launch-lane fallback merely because their full candles have not hydrated.
     * This ONLY chooses which native specialist evaluates; its stage, safety,
     * sellability, admission and FDG checks still apply.
     */
    fun unknownStageOwner8171(stage: Stage, ageMin: Double, mcapUsd: Double,
                               liqUsd: Double, buyPressure: Double, sellPressure: Double): String? {
        if (stage != Stage.UNKNOWN || !ageMin.isFinite() || ageMin < 60.0 ||
            !mcapUsd.isFinite() || !liqUsd.isFinite() || liqUsd < 12_000.0 ||
            !buyPressure.isFinite() || buyPressure < 52.0 || sellPressure >= 55.0 ||
            mcapUsd / liqUsd > 65.0) return null
        return when {
            mcapUsd >= 1_000_000.0 && liqUsd >= 20_000.0 -> "BLUECHIP"
            mcapUsd in 75_000.0..1_000_000.0 -> "QUALITY"
            else -> null
        }
    }

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
        unknownStageOwner8171(s.stage, s.ageMin, s.marketCapUsd, s.liquidityUsd,
            s.buyPressurePct, s.sellPressurePct)?.let { owner ->
            try { PipelineHealthCollector.labelInc("UNKNOWN_ESTABLISHED_HANDOFF_8171_$owner") } catch (_: Throwable) {}
            return owner
        }
        return when (s.stage) {
            Stage.FRESH_LAUNCH, Stage.BASE_START -> {
                val launch = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null }
                launchStageOwner8527(
                    s.stage, s.ageMin, s.liquidityUsd, s.buyPressurePct, s.marketCapUsd,
                    launch?.takeIf { it.birthResolved }?.ageMs, launch?.phase, launch?.lifecycle8136?.stage,
                ) ?: fallback.uppercase().ifBlank { "STANDARD" }
            }
            Stage.MID_ACCUMULATION -> if (s.liquidityUsd >= 20_000.0) "BLUECHIP" else "QUALITY"
            Stage.CONTROLLED_MARKUP -> "MOONSHOT"
            Stage.DUMPING -> "DIP_HUNTER"
            Stage.PEAK_EXHAUSTION, Stage.RUG_PRONE -> "QUALITY" // observation/defensive owner; laneFit blocks live buy exposure
            Stage.NO_SETUP, Stage.UNKNOWN -> fallback.uppercase().ifBlank { "STANDARD" }
        }
    }

    /**
     * V5.0.8527 — stage routing is advisory but must respect the sniper's real clock and ordered lifecycle.
     * A 3–12 minute chart base is not a fresh launch. A legacy IGNITION read cannot rewind a launch which
     * has already reached EXPANDING/MIGRATION/CONTINUATION. Unknown birth/phase stays unknown; this does
     * not replace the native sniper's setup, swap-evidence, liquidity or physical-safety checks.
     */
    internal fun sniperTimingFits8527(
        launchAgeMs: Long?,
        orderedStage: com.lifecyclebot.engine.truth.LaunchLifecycle8136.Stage8136?,
    ): Boolean = launchAgeMs != null &&
        launchAgeMs in 0L..com.lifecyclebot.engine.truth.LaunchLifecycle8136.LAUNCH_WINDOW_MS &&
        (orderedStage == com.lifecyclebot.engine.truth.LaunchLifecycle8136.Stage8136.PRE_IGNITION ||
            orderedStage == com.lifecyclebot.engine.truth.LaunchLifecycle8136.Stage8136.IGNITION)

    internal fun evidenceAlternativeLanes8527(
        stage: Stage,
        launchAgeMs: Long?,
        orderedStage: com.lifecyclebot.engine.truth.LaunchLifecycle8136.Stage8136?,
    ): Set<String> = LANE_STAGE_SHEET_7928[stage].orEmpty().filterTo(linkedSetOf()) {
        it !in TRUNK_LANES_7940 && (it != "PROJECT_SNIPER" || sniperTimingFits8527(launchAgeMs, orderedStage))
    }

    internal fun launchStageOwner8527(
        stage: Stage,
        ageMin: Double,
        liquidityUsd: Double,
        buyPressurePct: Double,
        marketCapUsd: Double,
        launchAgeMs: Long?,
        legacyPhase: com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase?,
        orderedStage: com.lifecyclebot.engine.truth.LaunchLifecycle8136.Stage8136?,
    ): String? {
        val sniperWindow = sniperTimingFits8527(launchAgeMs, orderedStage)
        return when (stage) {
            Stage.FRESH_LAUNCH -> when {
                sniperWindow && legacyPhase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION -> "PROJECT_SNIPER"
                sniperWindow && legacyPhase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.PRE_IGNITION &&
                    liquidityUsd >= 1_500.0 -> "PROJECT_SNIPER"
                legacyPhase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.EXPANDING &&
                    liquidityUsd >= 5_000.0 && buyPressurePct >= 56.0 -> "MOONSHOT"
                sniperWindow && liquidityUsd >= 2_500.0 -> "PROJECT_SNIPER"
                liquidityUsd >= 1_500.0 -> "EXPRESS"
                else -> "SHITCOIN"
            }
            Stage.BASE_START -> when {
                ageMin.isFinite() && ageMin in 0.0..12.0 ->
                    if (sniperWindow && liquidityUsd >= 8_000.0 && buyPressurePct >= 58.0) "PROJECT_SNIPER" else "SHITCOIN"
                marketCapUsd >= 150_000.0 -> "MOONSHOT"
                else -> "SHITCOIN"
            }
            else -> null
        }
    }

    fun laneFit(ts: TokenState, laneRaw: String): LaneFit {
        val now = System.currentTimeMillis()
        val flow = EntryFlow8506.provider(ts.mint, now)
        return laneFit(ts, laneRaw, now, flow)
    }

    private fun laneFit(ts: TokenState, laneRaw: String, now: Long, flow: EntryFlow8506?): LaneFit {
        val lane = com.lifecyclebot.engine.cortex.LaneCheatSheet8323.key8323(laneRaw)
        return laneFit(ts, lane, now, flow, snapshot(ts, now, flow))
    }

    internal fun laneFit(ts: TokenState, lane: String, now: Long, flow: EntryFlow8506?, s: Snapshot): LaneFit {
        // V5.0.7425 — DIP_HUNTER is a reclaim desk, not a falling-knife desk.
        // A post-pump/dumping token needs an actual turn in the local tape before
        // any non-MANIPULATED lane can convert the observation into live capital.
        val recent7425 = try { ts.history.toList().map { it.priceUsd }.filter { it.isFinite() && it > 0.0 }.takeLast(5) } catch (_: Throwable) { emptyList() }
        val reclaimConfirmed7425 = recent7425.size >= 3 &&
            recent7425.last() > recent7425.minOrNull()!! * 1.06 &&
            recent7425.takeLast(3).zipWithNext().all { (a, b) -> b >= a * 0.985 } &&
            (flow?.move ?: ts.lastPriceChange5m.takeIf { LaneInputTruth8236.fresh8491(ts.priceChangeObservedAtMs8084, now) } ?: Double.NaN) > 0.0 &&
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
        if (isEstablished && s.stage != Stage.RUG_PRONE && s.stage != Stage.PEAK_EXHAUSTION && s.stage != Stage.DUMPING) {
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
            Stage.NO_SETUP -> false
            Stage.UNKNOWN -> lane in setOf("QUALITY", "BLUECHIP", "TREASURY", "STANDARD", "CORE", "V3") && s.liquidityUsd >= 12_000.0 && s.mcapToLiq <= 65.0 && s.buyPressurePct >= 52.0
        }
        return LaneFit(allowed, lane, s.stage, s.compact)
    }

    /** The live meme lane needs both a resolved phase and current participation at selection and again before sealing. */
    internal fun memeEntry8506(lane: String, mint: String): Boolean =
        lane in com.lifecyclebot.engine.cortex.LanePlaybook7907.menuLanes8383() &&
            com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(mint)

    internal fun entryRead8506(ts: TokenState, lane: String, nowMs: Long): Pair<LaneFit, String?> {
        val flow = EntryFlow8506.forMint(ts.mint, nowMs)
        val fit = laneFit(ts, lane, nowMs, flow)
        return fit to entryWait8506(fit, flow)
    }

    internal fun entryWait8506(fit: LaneFit, flow: EntryFlow8506?): String? = when {
        fit.stage == Stage.RUG_PRONE -> "RUG_PRONE"
        flow == null -> "FLOW_UNOBSERVED"
        fit.stage == Stage.UNKNOWN -> "STAGE_UNRESOLVED"
        !flow.active -> "NO_BUY_VOLUME"
        fit.stage == Stage.NO_SETUP -> "NO_PHASE_TRIGGER"
        fit.stage in setOf(Stage.DUMPING, Stage.PEAK_EXHAUSTION) && !fit.allowed -> "NO_RECLAIM"
        fit.lane in setOf("EXPRESS", "MOONSHOT", "SHITCOIN", "PROJECT_SNIPER", "MANIPULATED") &&
            flow.move <= 0.0 && flow.buyPct <= 50.0 -> "NO_IGNITION"
        else -> null
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
        // V5.0.8473 — MOONSHOT plays a re-accumulation base (the stage records still decide, judgeStage7928).
        Stage.BASE_START to setOf("SHITCOIN", "PROJECT_SNIPER", "EXPRESS", "MOONSHOT", "STANDARD", "CORE", "V3"),
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
            // V5.0.8198 — one late-read rule for both stage readers (CandidateAdmission8136 applies the same exemption).
            val late = com.lifecyclebot.engine.truth.CandidateAdmission8136.stageLatePays8198(
                com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136("STAGE", stage.name, stat.n60, stat.meanNet60Pct, se,
                    lateN = stat.n240, lateMeanPct = stat.meanNet240Pct))
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

    /**
     * V5.0.8051 — a descriptive stage for grading when no stage rule fires (5.0.8046: STAGE..._UNKNOWN 16,472). Decisions
     * keep UNKNOWN ("no stage evidence"); the forecast signature files the coin under its nearest shape, prefixed N_ so
     * it never mixes with a rule-clean stage. Pure.
     */
    fun nearestStage8051(currentVsPeak: Double, drawdownPct: Double, runupPct: Double, buyPressurePct: Double, sellPressurePct: Double): String = when {
        drawdownPct >= 18.0 -> if (buyPressurePct >= 52.0 && sellPressurePct <= 52.0) "N_PULLBACK" else "N_FALLING"
        currentVsPeak >= 0.88 && runupPct >= 25.0 -> "N_MARKUP"
        currentVsPeak >= 0.88 -> "N_FLAT_HIGH"
        buyPressurePct >= 50.0 -> "N_PULLBACK"
        else -> "N_FADING"
    }

    /** A price at or above this share of its recent peak is "at the high" (the peak-exhaustion test's own figure). */
    private const val AT_HIGH_SHARE_8712 = 0.88

    /**
     * Pure (V5.0.8698 / 8712): a thin POOL that has already run and is still at its high — never an active bonding curve
     * (its depth is its virtual reserve), and never a coin that has come off the high (that is a pullback or a base).
     */
    fun thinPoolRunup8698(liqThin: Boolean, runupPct: Double, onActiveCurve: Boolean, currentVsPeak: Double): Boolean =
        liqThin && runupPct >= 80.0 && !onActiveCurve && currentVsPeak.isFinite() && currentVsPeak >= AT_HIGH_SHARE_8712

    /** Pure (V5.0.8500): the structural test(s) that staged a coin RUG_PRONE. */
    fun rugWhy8500(valuationAir: Boolean, thinRunup: Boolean): String =
        listOfNotNull("VALUATION_AIR".takeIf { valuationAir }, "THIN_LIQ_RUNUP".takeIf { thinRunup })
            .joinToString("+").ifBlank { "NONE" }

    fun reasonFor(stage: Stage, rug: Boolean, peak: Boolean, dump: Boolean, base: Boolean, mid: Boolean, markup: Boolean, fresh: Boolean = false): String = when (stage) {
        Stage.RUG_PRONE -> "rugProne=$rug liquidityAir/thinRunup"
        Stage.PEAK_EXHAUSTION -> "peakExhaustion=$peak nearHigh+extended+sellPressure"
        Stage.DUMPING -> "dumping=$dump drawdown_or_acute5m_cascade_reclaim_required"
        Stage.FRESH_LAUNCH -> "freshLaunch=$fresh trueLaunchAge+ignitionOrExpansion+notPostPumpFade"
        Stage.BASE_START -> "baseStart=$base early+notExtended+buyPressure"
        Stage.MID_ACCUMULATION -> "midAccum=$mid pullbackBand+liq+controlledSP"
        Stage.CONTROLLED_MARKUP -> "markup=$markup controlledRunup+bp+liq"
        Stage.NO_SETUP -> "observed chart without a current lane entry phase"
        Stage.UNKNOWN -> "unknown=noCleanMetricStage"
    }
}
