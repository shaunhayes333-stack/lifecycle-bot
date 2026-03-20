package com.lifecyclebot.engine

import com.lifecyclebot.data.*
import com.lifecyclebot.util.*
import com.lifecyclebot.engine.BondingCurveTracker
import com.lifecyclebot.engine.WhaleDetector

/**
 * LifecycleStrategy v4 — Chart-Tuned
 * ════════════════════════════════════════════════════════════════════
 *
 * Changes vs v3 (driven by live chart analysis):
 *
 * 1. EMA FAN SCORING in calcHoldExtension
 *    Charts like MOONDO (+6960%) and Optimistic (+10590%) show EMA5 > EMA10 > EMA20
 *    fanned upward is the single best "keep holding" signal on a grinder.
 *    +25 hold points when fan is aligned and spread is widening.
 *
 * 2. STOCHRSI COLLAPSE DETECTION in exit scoring
 *    Nigel chart: StochRSI dropped from overbought (>70) to <30 while
 *    price was still near ATH — classic distribution warning.
 *    Collapse from overbought = up to +22 exit urgency.
 *
 * 3. IMPROVED RECLAIM DETECTION (strong_reclaim phase)
 *    táng zǐ bīng fǎ chart: double-bottom reclaim with volume expansion
 *    on second leg + RSI holding >50 = high-probability re-entry.
 *    New "strong_reclaim" phase gets lower entry threshold (38 vs 45)
 *    and bypasses cooldown automatically.
 *
 * 4. RANGE TRADE BASE WINDOW 25 → 45 MIN
 *    MOONDO ran 5h, Optimistic ran 11h. The old 25 min window was cutting
 *    winners. New base: 45 min, extends to 150 min on healthy signals.
 *
 * 5. MICRO-CAP LAUNCH GATE: holders < 150 → block entry
 *    beecat chart: $10K MC, 101 holders = noise. New "micro_cap_wait"
 *    phase blocks until sufficient distribution exists.
 *
 * 6. VOLUME DIVERGENCE DETECTION
 *    Rosie chart: StochRSI 96 but volume flat/declining on recovery = dead-cat.
 *    Price rising + volume falling = bearish divergence → block entry, +20 exit.
 *
 * 7. ATH PROXIMITY EXIT BOOST
 *    Nigel, Optimistic: both rejected hard at prior ATH.
 *    Within 8% of ATH = +10 exit urgency. Within 2% = +30.
 *
 * 8. TIGHTENED STALE-LOSER CUT on LAUNCH_SNIPE
 *    90 sec / -1.5% (was 120 sec / -3%). Fast memes don't recover.
 *    Range trades unchanged (slower price discovery).
 *
 * 9. COOLING ENTRY REQUIRES EMA FAN
 *    Only enter cooling dips when EMA5 > EMA10 > EMA20 aligned.
 *    Prevents buying cooling dips on downtrending tokens.
 *
 * 10. PUNCH-TYPE CHOP FILTER
 *     Oscillating range, no expansion, EMAs flat = "choppy_range".
 *     Block entry, signal WAIT_CHOP on UI.
 */
class LifecycleStrategy(
    private val cfg: () -> com.lifecyclebot.data.BotConfig,
    private val brain: () -> BotBrain? = { null },   // injected — avoids BotService.instance
) {

    private val trending = com.lifecyclebot.network.CoinGeckoTrending()

    // ─────────────────────────────────────────────────────────────────
    // Public interface
    // ─────────────────────────────────────────────────────────────────

    fun evaluate(ts: TokenState, modeConf: AutoModeEngine.ModeConfig? = null): StrategyResult {
        val hist = ts.history.toList()

        if (hist.size < 3) {
            return StrategyResult("bootstrap", "WAIT", 0.0, 0.0, StrategyMeta())
        }
        if (!passesGates(hist)) {
            return StrategyResult("thin_market", "WAIT", 0.0, 0.0, StrategyMeta())
        }

        val tokenAgeMs   = System.currentTimeMillis() - (hist.first().ts)
        val tokenAgeMins = tokenAgeMs / 60_000.0

        // ── Timeframe scaling ──────────────────────────────────────────
        // tfScale = minutes per candle. All minute-based thresholds
        // multiply by tfScale so the strategy is timeframe-agnostic.
        //   1M candles: tfScale = 1   (no change — baseline)
        //   1H candles: tfScale = 60  (hold thresholds ×60)
        //   4H candles: tfScale = 240 (hold thresholds ×240)
        //   1D candles: tfScale = 1440
        // Launch snipe mode is only valid on 1M candles — if a higher
        // timeframe is detected, force RANGE_TRADE regardless of age.
        val tfScale = ts.candleTimeframeMinutes.toDouble().coerceAtLeast(1.0)
        val mode    = when {
            tfScale == 1.0 && tokenAgeMins <= 15.0 -> TradingMode.LAUNCH_SNIPE
            else                                    -> TradingMode.RANGE_TRADE
        }

        val prices = hist.map { it.ref }

        val curve = BondingCurveTracker.evaluate(ts)
        val whale = WhaleDetector.evaluate(ts.mint, ts)

        // Shared indicators
        val volScore   = volumeScore(hist)
        val pressScore = pressureScore(hist)
        val momScore   = momentumScore(prices)
        // On a bull-fan grinder with good gains, require stronger exhaustion confirmation
        val emafanQuick = calcEmaFan(prices)
        val inGrinder   = ts.position.isOpen
            && pct(ts.position.entryPrice, ts.ref) > 30.0
            && emafanQuick.alignment == EmaAlignment.BULL_FAN
        val exhaust    = volumeExhaustion(hist, requireStrong = inGrinder)
        val txnDrop    = txnCountCollapse(hist, ts)
        val accel      = volumeAcceleration(hist)
        val buyDom     = buyerDominance(hist)

        // New v4 indicators
        val emafan     = calcEmaFan(prices)
        val stochRsi   = calcStochRsiSignal(hist)
        val volDiv     = volumeDivergence(hist, prices)
        val athProx    = athProximity(prices)
        val mtf5m      = mtfTrend(ts.history5m)
        val mtf15m     = mtfTrend(ts.history15m)

        val (phase, pm) = when (mode) {
            TradingMode.LAUNCH_SNIPE -> detectLaunchPhase(hist, prices, tokenAgeMins)
            TradingMode.RANGE_TRADE  -> detectRangePhase(hist, prices)
        }

        var entryScore = when (mode) {
            TradingMode.LAUNCH_SNIPE -> calcLaunchEntryScore(
                phase, pm, volScore, pressScore, accel, buyDom, hist, emafan)
            TradingMode.RANGE_TRADE  -> calcRangeEntryScore(
                phase, pm, volScore, pressScore, momScore, accel, hist, emafan, volDiv)
        }

        // MTF entry filter
        if (!ts.position.isOpen) {
            when (mtf5m) {
                MtfTrend.BEAR    -> entryScore = (entryScore - 20.0).coerceAtLeast(0.0)
                MtfTrend.BULL    -> entryScore = (entryScore + 8.0).coerceIn(0.0, 100.0)
                MtfTrend.NEUTRAL -> Unit
            }
            // FIX 5: 15m is the macro trend — BEAR_FAN on 15m = hard block for range entries
            // This catches ROSIE/DOWNALD/AFK patterns before phase detection even runs.
            // Launch snipes are exempt — new tokens don't have 15m history.
            if (mode == TradingMode.RANGE_TRADE && mtf15m == MtfTrend.BEAR
                && ts.history15m.size >= 8) {
                entryScore = 0.0
            }
        }
        // BotBrain learning — phase boost AND bad behaviour suppression
        if (!ts.position.isOpen) {
            try {
                val brain = com.lifecyclebot.engine.BotService.instance?.botBrain

                // Good behaviour: phase boost from winning patterns
                val boost = brain?.getPhaseBoost(phase) ?: 0.0
                if (boost != 0.0) entryScore = (entryScore + boost).coerceIn(0.0, 100.0)

                // Bad behaviour: suppression penalty from confirmed losing patterns
                // This CANNOT be overridden by the LLM — it is a hard learned constraint.
                val fan = emafan.alignment.name  // e.g. "BULL_FAN"
                val penalty = brain?.getSuppressionPenalty(phase, fan, ts.source) ?: 0.0
                if (penalty > 0.0) {
                    entryScore = (entryScore - penalty).coerceAtLeast(0.0)
                }

                // Hard suppression — if the pattern is near-blocked, zero the score
                if (brain?.isHardSuppressed(phase, fan) == true) {
                    entryScore = 0.0
                }
            } catch (_: Exception) { /* brain not ready */ }
        }

        var exitScore = calcExitScore(
            ts, phase, mode, volScore, pressScore, momScore,
            exhaust, txnDrop, tokenAgeMins, stochRsi, volDiv, athProx, emafan)

        // MTF exit suppression — 5m bull fan suppresses 1m wobble exits (MOONDO fix)
        if (ts.position.isOpen && mtf5m == MtfTrend.BULL) {
            val gainMtf = if (ts.position.entryPrice > 0) pct(ts.position.entryPrice, ts.ref) else 0.0
            if (gainMtf > 10.0 && !exhaust) exitScore = (exitScore - 15.0).coerceAtLeast(0.0)
        }
        // 15m bear = macro trend against us — add urgency
        if (ts.position.isOpen && mtf15m == MtfTrend.BEAR) {
            exitScore = (exitScore + 12.0).coerceIn(0.0, 100.0)
        }

        // ── Bonding curve overlay ─────────────────────────────────────
        exitScore = (exitScore + curve.exitUrgencyBonus).coerceIn(0.0, 100.0)
        if (curve.stage == BondingCurveTracker.CurveStage.GRADUATING && ts.position.isOpen)
            exitScore = (exitScore - 5.0).coerceAtLeast(0.0)
        if (curve.stage == BondingCurveTracker.CurveStage.GRADUATED && ts.position.isOpen)
            exitScore = (exitScore + 20.0).coerceIn(0.0, 100.0)
        if (curve.stage == BondingCurveTracker.CurveStage.PRE_GRAD && ts.position.isOpen)
            exitScore = (exitScore - 12.0).coerceAtLeast(0.0)
        // CHANGE 1: Pre-graduation ENTRY boost — proven demand before Raydium listing
        if (!ts.position.isOpen) {
            val preGradBoost = when (curve.stage) {
                BondingCurveTracker.CurveStage.GRADUATING -> 30.0
                BondingCurveTracker.CurveStage.PRE_GRAD   -> 20.0
                else                                       -> 0.0
            }
            if (preGradBoost > 0)
                entryScore = (entryScore + preGradBoost).coerceIn(0.0, 100.0)
        }

        // ── Whale overlay (CHANGE 4: velocity as primary signal) ──────
        if (whale.hasWhaleActivity && !ts.position.isOpen)
            entryScore = (entryScore + whale.whaleScore * 0.25).coerceIn(0.0, 100.0)
        // Whale velocity = large wallet buying aggressively = the catalyst itself
        if (!ts.position.isOpen) {
            val velBoost = when {
                whale.velocityScore >= 70 -> 25.0   // strong whale accumulation
                whale.velocityScore >= 60 -> 15.0   // was flat +10
                whale.velocityScore >= 45 ->  8.0   // moderate interest
                else                      ->  0.0
            }
            if (velBoost > 0)
                entryScore = (entryScore + velBoost).coerceIn(0.0, 100.0)
        }
        if (whale.smartMoneyPresent && ts.position.isOpen)
            exitScore = (exitScore - 8.0).coerceAtLeast(0.0)

        // ── CoinGecko trending overlay ────────────────────────────────
        if (!ts.position.isOpen) {
            val trendBoost = trending.entryScoreBoost(ts.symbol)
            if (trendBoost > 0) {
                entryScore = (entryScore + trendBoost).coerceIn(0.0, 100.0)
                exitScore  = (exitScore - trendBoost * 0.3).coerceAtLeast(0.0)
            }
        }

        // ── Sentiment overlay ─────────────────────────────────────────
        applySentimentOverlay(ts, entryScore, exitScore, cfg()).let {
            entryScore = it.first; exitScore = it.second
        }
        if (modeConf != null && modeConf.entryScoreMultiplier > 0.0 &&
            modeConf.entryScoreMultiplier < 1.0 && !ts.position.isOpen) {
            entryScore = (entryScore / modeConf.entryScoreMultiplier).coerceIn(0.0, 100.0)
        }

        // ── Safety overlay ────────────────────────────────────────────
        val safety = ts.safety
        if (safety.isBlocked) {
            entryScore = 0.0
            if (ts.position.isOpen) exitScore = 100.0
        } else if (safety.tier == SafetyTier.CAUTION) {
            entryScore = (entryScore - safety.entryScorePenalty).coerceAtLeast(0.0)
        }

        val signal = decideSignal(
            ts, hist, prices, phase, mode,
            entryScore, exitScore, exhaust, pm, tokenAgeMins, emafan, volDiv, whale, curve)

        // Compute top-up readiness directly in strategy — full signal access here
        val gainPctNow   = if (ts.position.isOpen && ts.position.entryPrice > 0)
            pct(ts.position.entryPrice, ts.ref) else 0.0
        val spikeNow     = detectSpikeTop(ts.history.toList(), ts.history.toList().map { it.ref }, gainPctNow)
        val topUpReadyNow = ts.position.isOpen
            && gainPctNow > 0
            && !exhaust
            && !spikeNow.isSpike
            && !spikeNow.isPostSpike
            && exitScore < 35.0
            && emafan.alignment in listOf(EmaAlignment.BULL_FAN, EmaAlignment.BULL_FLAT)
            && volScore >= 35.0
            && pressScore >= 45.0
            && ts.position.partialSoldPct < 75.0
            && mtf5m != MtfTrend.BEAR

        return StrategyResult(
            phase, signal,
            entryScore.coerceIn(0.0, 100.0),
            exitScore.coerceIn(0.0, 100.0),
            pm.copy(
                volScore         = volScore,
                pressScore       = pressScore,
                momScore         = momScore,
                exhaustion       = exhaust,
                curveStage       = curve.stageLabel,
                curveProgress    = curve.progressPct,
                whaleSummary     = whale.summary,
                velocityScore    = whale.velocityScore,
                emafanAlignment  = emafan.alignment.name,
                spikeDetected    = spikeNow.isSpike || spikeNow.isPostSpike,
                protectMode      = spikeNow.protectMode,
                topUpReady       = topUpReadyNow,
            )
        )
    }

    private enum class TradingMode { LAUNCH_SNIPE, RANGE_TRADE }

    // ─────────────────────────────────────────────────────────────────
    // Gates
    // ─────────────────────────────────────────────────────────────────

    /**
     * Smart established token scoring — replaces the blunt 30-day age penalty.
     *
     * Age is extended to no cutoff — tokens of any age are evaluated on merit.
     * The scoring system awards bonuses for healthy signals and penalises
     * poor signals, regardless of how old the token is.
     *
     * HOLDER GROWTH (most important signal for established tokens):
     *   Growing:   +12  — community expanding, demand increasing
     *   Flat:        0  — stable, neither good nor bad
     *   Declining:  −18  — exit liquidity forming, smart money leaving
     *   Falling fast: −30 — dump in progress, strong avoid
     *
     * LIQUIDITY DEPTH (exit risk):
     *   >$500K liq: +8   — can exit large positions without slippage
     *   $100K–500K: +4   — comfortable for most position sizes
     *   $50K–100K:   0   — acceptable
     *   $10K–50K:   −8   — thin, slippage risk on exit
     *   <$10K:      −20  — extreme exit risk, essentially illiquid
     *
     * VOLUME CONSISTENCY (activity check):
     *   Recent vol ≥ 80% of baseline: +6  — still being traded actively
     *   Recent vol ≥ 50%:              0  — acceptable
     *   Recent vol 25–50%:            −8  — interest fading
     *   Recent vol <25%:             −18  — dead market
     *
     * EMA STRUCTURE (trend):
     *   BULL_FAN:   +10  — uptrend regardless of age
     *   BULL_FLAT:   +4
     *   FLAT:          0  — neutral
     *   BEAR_FLAT:  −12
     *   BEAR_FAN:   −25  — sustained downtrend, strong avoid
     *
     * HOLDER COUNT vs PEAK (health check):
     *   Still near peak (>90%):     +5   — community intact
     *   Moderate decline (70–90%):   0
     *   Heavy decline (<70%):       −10  — significant holder exit
     *   Near ATL (<50% of peak):    −20  — most holders have left
     *
     * AGE BONUS (stability premium — older survived tokens proved themselves):
     *   30–90d:   +5   — survived the initial rug window
     *   90–180d:  +10  — proven survival, growing community trust
     *   180d+:    +15  — long-term project, very high holder conviction
     *
     * Note: The age bonus only applies when holder growth is non-negative.
     * A 180d token that's losing holders gets no age bonus.
     */
    private fun applyEstablishedTokenScore(
        entryScore: Double,
        ts: TokenState,
        tokenAgeMins: Double,
        hist: List<Candle>,
    ): Double {
        var score = entryScore
        val tokenAgeDays = tokenAgeMins / (60.0 * 24.0)

        // Only apply this logic to tokens with meaningful age (> 3 days)
        // Fresh launches are handled by LAUNCH_SNIPE mode and micro-cap gate
        if (tokenAgeDays < 3.0) return score

        val fan          = calcEmaFan(hist.map { it.ref })
        val holderGrowth = ts.holderGrowthRate  // % change, positive = growing
        val liqUsd       = ts.lastLiquidityUsd
        val peakHolders  = ts.peakHolderCount.coerceAtLeast(1)
        val curHolders   = hist.lastOrNull()?.holderCount ?: 0
        val holderRatio  = if (peakHolders > 0) curHolders.toDouble() / peakHolders else 1.0

        // Recent volume vs baseline
        val recentVol  = if (hist.size >= 3) hist.takeLast(3).map { it.vol }.average() else 0.0
        val baseVol    = if (hist.size >= 20) hist.takeLast(20).map { it.vol }.average() else recentVol
        val volRatio   = if (baseVol > 0) recentVol / baseVol else 1.0

        // ── Holder growth adjustment ───────────────────────────────────
        score += when {
            holderGrowth >  5.0  -> 12.0   // actively growing community
            holderGrowth >  1.0  ->  6.0   // slowly growing
            holderGrowth > -1.0  ->  0.0   // stable
            holderGrowth > -5.0  -> -18.0  // slowly declining
            else                 -> -30.0  // fast decline — exit liquidity forming
        }

        // ── Liquidity depth ────────────────────────────────────────────
        score += when {
            liqUsd > 500_000 ->  8.0
            liqUsd > 100_000 ->  4.0
            liqUsd >  50_000 ->  0.0
            liqUsd >  10_000 -> -8.0
            liqUsd >      0  -> -20.0  // essentially illiquid
            else             ->  0.0   // unknown — don't penalise missing data
        }

        // ── Volume consistency ─────────────────────────────────────────
        score += when {
            volRatio >= 0.80 ->  6.0
            volRatio >= 0.50 ->  0.0
            volRatio >= 0.25 -> -8.0
            else             -> -18.0
        }

        // ── EMA structure ──────────────────────────────────────────────
        score += when (fan.alignment) {
            EmaAlignment.BULL_FAN   -> 10.0
            EmaAlignment.BULL_FLAT  ->  4.0
            EmaAlignment.FLAT       ->  0.0
            EmaAlignment.BEAR_FLAT  -> -12.0
            EmaAlignment.BEAR_FAN   -> -25.0
        }

        // ── Holder count vs peak ───────────────────────────────────────
        if (curHolders > 0 && peakHolders > 0) {
            score += when {
                holderRatio >= 0.90 ->  5.0
                holderRatio >= 0.70 ->  0.0
                holderRatio >= 0.50 -> -10.0
                else                -> -20.0
            }
        }

        // ── Age stability bonus (only when community is healthy) ───────
        if (holderGrowth >= -1.0) {  // not declining
            score += when {
                tokenAgeDays >= 180 -> 15.0
                tokenAgeDays >=  90 -> 10.0
                tokenAgeDays >=  30 ->  5.0
                else                ->  0.0
            }
        }

        return score.coerceIn(0.0, 100.0)
    }

    private fun passesGates(hist: List<Candle>): Boolean {
        val latest = hist.last()
        if (latest.priceUsd <= 0) return false
        if (latest.vol < 10.0 && hist.size > 5) return false

        // v4.4: Signal-based liquidity gate — replaces crude time filter
        // Thin markets = wide spreads, easy manipulation, rug risk
        val c = cfg()
        if (c.liquidityGateEnabled && hist.size > 8) {
            val recentVol = sma(hist.takeLast(3).map { it.vol }).coerceAtLeast(1.0)
            val baseVol   = sma(hist.takeLast(12).map { it.vol }).coerceAtLeast(1.0)
            // Absolute floor: if recent volume is negligible, skip
            if (recentVol < c.minLiquidityUsd / 100.0) return false
            // Relative floor: if volume has completely dried up vs baseline, skip
            if (hist.size > 10 && recentVol / baseVol < c.minVolLiqRatio) return false
        }
        // Hard gate: rapid holder decline + dying volume = slow bleed
        // (This is a hard block; applyEstablishedTokenScore handles graduated penalties)
        if (hist.size >= 20) {
            val rh = hist.takeLast(3).map { it.holderCount }.filter { it > 0 }
            val eh = hist.takeLast(10).take(5).map { it.holderCount }.filter { it > 0 }
            if (rh.isNotEmpty() && eh.isNotEmpty()) {
                val rv2 = sma(hist.takeLast(3).map { it.vol }).coerceAtLeast(1.0)
                val bv2 = sma(hist.takeLast(20).map { it.vol }).coerceAtLeast(1.0)
                // Only hard-block on severe decline: >15% holder drop AND vol <30% baseline
                if (rh.average() < eh.average() * 0.85 && rv2 < bv2 * 0.30) return false
            }
        }
        return true
    }

    // ─────────────────────────────────────────────────────────────────
    // Pullback entry detector
    // ─────────────────────────────────────────────────────────────────

    private fun isPullbackEntry(hist: List<Candle>, prices: List<Double>): Boolean {
        if (prices.size < 8 || hist.size < 8) return false
        val cur   = prices.last()
        val high5 = prices.takeLast(5).max()
        val move5 = pct(prices.getOrElse(prices.size - 6) { prices.first() }.coerceAtLeast(1e-12), high5)
        if (move5 < 15.0) return false
        val retrace = pct(high5, cur)
        if (retrace > -10.0 || retrace < -40.0) return false
        val recentBr = hist.takeLast(2).map { it.buyRatio }
        val brRecovering = recentBr.size >= 2 && recentBr.last() > recentBr.first()
        val pumpVol = hist.takeLast(6).take(3).sumOf { it.vol }
        val dipVol  = hist.takeLast(3).sumOf { it.vol }
        val volDecl = dipVol < pumpVol * 0.80
        val fanOk   = calcEmaFan(prices).alignment in listOf(EmaAlignment.BULL_FAN, EmaAlignment.BULL_FLAT)
        return brRecovering && volDecl && fanOk
    }

    // ─────────────────────────────────────────────────────────────────
    // MODE 1 — Launch phase detection (v4: micro-cap holder gate)
    // ─────────────────────────────────────────────────────────────────

    private fun detectLaunchPhase(
        hist: List<Candle>,
        prices: List<Double>,
        ageMins: Double,
    ): Pair<String, StrategyMeta> {
        val sz      = prices.size
        val current = prices.last()
        val prev    = prices[sz - 2]

        val w4    = prices.takeLast(4)
        val w8    = prices.takeLast(8)
        val move4 = if (w4.size >= 2) pct(w4.first(), current) else 0.0
        val move8 = if (w8.size >= 2) pct(w8.first(), current) else 0.0

        val ema8val         = ema(prices.takeLast(10), 8)
        val recentRed       = current < prev
        val recentBuyRatios = hist.takeLast(3).map { it.buyRatio }
        val avgBuyRatio     = if (recentBuyRatios.isNotEmpty()) recentBuyRatios.average() else 0.5
        val latestHolders   = hist.last().holderCount

        val meta = StrategyMeta(
            move3Pct   = move4,
            move8Pct   = move8,
            rangePct   = 0.0,
            posInRange = 50.0,
            lowerHighs = false,
            breakdown  = false,
            ema8       = ema8val,
        )

        val phase = when {
            // v4: block entry until meaningful distribution exists
            latestHolders in 1..149 -> "micro_cap_wait"

            move4 < 15.0 && avgBuyRatio > 0.62 && ageMins < 5.0 -> "pre_pump"
            move4 in 5.0..40.0 && avgBuyRatio > 0.55             -> "pumping"
            move8 > 20.0 && recentRed && avgBuyRatio > 0.48       -> "pump_pullback"
            move4 > 40.0                                           -> "overextended"
            avgBuyRatio < 0.42 && move4 < 0.0                     -> "dying"
            else                                                   -> "early_unknown"
        }

        return phase to meta
    }

    // ─────────────────────────────────────────────────────────────────
    // MODE 2 — Range phase detection (v4: chop filter + strong reclaim)
    // ─────────────────────────────────────────────────────────────────

    private fun detectRangePhase(
        hist: List<Candle>,
        prices: List<Double>,
    ): Pair<String, StrategyMeta> {
        val sz      = prices.size
        val current = prices.last()
        val prev    = if (sz >= 2) prices[sz - 2] else current

        val w8  = prices.takeLast(8)
        val w12 = prices.takeLast(12)
        val w20 = prices.takeLast(20)

        val move3    = if (sz >= 4) pct(prices[sz - 4], current) else 0.0
        val move8    = if (w8.size >= 2) pct(w8.first(), current) else 0.0
        val std12    = stddev(w12)
        val mean12   = sma(w12).coerceAtLeast(1e-12)
        val rangePct = (std12 / mean12) * 100.0
        val high12   = w12.max()
        val low12    = w12.min()
        val posRng   = if (high12 == low12) 50.0
                       else ((current - low12) / (high12 - low12)) * 100.0

        val lowerHighs = lowerHighsBy3rds(w20, 9)
        val recentRed  = current < prev
        val ema8val    = ema(prices.takeLast(12), 8)
        val ema21val   = ema(prices.takeLast(28), 21)
        val breakdown  = current < low12 * 0.975
        val emafan     = calcEmaFan(prices)

        // v4: Chop detection — Punch-type oscillating range
        val isChoppy = rangePct in 8.0..15.0
            && posRng in 30.0..70.0
            && emafan.alignment == EmaAlignment.FLAT
            && hist.takeLast(6).let { recent ->
                val vols = recent.map { it.vol }
                vols.isNotEmpty() && vols.max() < vols.average() * 1.3
            }

        // v4: Strong reclaim detection — táng zǐ bīng fǎ pattern
        val isStrongReclaim = detectStrongReclaim(hist, prices, ema8val, ema21val, current)

        val meta = StrategyMeta(
            move3Pct   = move3,
            move8Pct   = move8,
            rangePct   = rangePct,
            posInRange = posRng,
            lowerHighs = lowerHighs,
            breakdown  = breakdown,
            ema8       = ema8val,
        )

        var phase = "unknown"
        when {
            isChoppy                                                    -> phase = "choppy_range"
            rangePct < 12.0 && w12.size >= 10 && emafan.score >= 0.0  -> phase = "range"
            move8 in 10.0..35.0 && rangePct in 8.0..18.0               -> phase = "cooling"
            lowerHighs && posRng > 50                                   -> phase = "distribution"
            move3 > 20.0 && posRng > 80.0 && recentRed                 -> phase = "expansion_peak"
        }

        if (breakdown || (lowerHighs && current < ema8val * 0.93)) phase = "breakdown"
        if (phase == "breakdown" && current > ema8val && current > ema21val && !recentRed)
            phase = "reclaim_attempt"

        // v4: Upgrade to strong_reclaim if pattern confirmed
        if (phase == "reclaim_attempt" && isStrongReclaim) phase = "strong_reclaim"

        return phase to meta
    }

    /**
     * v4: High-quality reclaim setup detector (táng zǐ bīng fǎ pattern).
     * Requires: meaningful crash → double-bottom or mid-reclaim →
     * volume expanding on recovery → buyers in control → EMA positive.
     */
    private fun detectStrongReclaim(
        hist: List<Candle>,
        prices: List<Double>,
        ema8: Double,
        ema21: Double,
        current: Double,
    ): Boolean {
        if (prices.size < 16) return false
        val w16 = prices.takeLast(16)

        val crashLow  = w16.min()
        val precrash  = w16.take(4).average()
        val crashMag  = pct(precrash, crashLow)
        if (crashMag > -15.0) return false  // need a real crash to reclaim from

        // Double-bottom: two lowest values within 3% of each other
        val sorted       = w16.sorted()
        val bottomRange  = if (sorted[0] > 0) (sorted[1] - sorted[0]) / sorted[0] * 100.0 else -99.0
        val hasDoubleBot = bottomRange < 3.0

        // Volume expanding on recovery vs crash
        val halfIdx     = hist.size - 8
        val crashVol    = if (halfIdx > 0) hist.subList(halfIdx, hist.size - 4).sumOf { it.vol } else 1.0
        val recovVol    = hist.takeLast(4).sumOf { it.vol }
        val volExpanding = recovVol > crashVol * 0.8

        // Price above midpoint of crash
        val precrashHigh = w16.take(6).max()
        val midpoint     = crashLow + (precrashHigh - crashLow) * 0.50
        val aboveMid     = current > midpoint

        // Buyers in control
        val buyControl = hist.takeLast(3).all { it.buyRatio > 0.50 }

        // EMA alignment not still broken
        val emaOk = current > ema8 && ema8 > ema21 * 0.98

        return (hasDoubleBot || aboveMid) && volExpanding && buyControl && emaOk
    }

    private fun lowerHighsBy3rds(values: List<Double>, n: Int = 9): Boolean {
        if (values.size < n) return false
        val seg   = values.takeLast(n)
        val third = n / 3
        val h1    = seg.subList(0,         third).max()
        val h2    = seg.subList(third,     third * 2).max()
        val h3    = seg.subList(third * 2, seg.size).max()
        return h3 < h2 * 0.97 && h2 < h1 * 0.97
    }

    // ─────────────────────────────────────────────────────────────────
    // v4 New Indicators
    // ─────────────────────────────────────────────────────────────────

    enum class EmaAlignment { BULL_FAN, BULL_FLAT, FLAT, BEAR_FLAT, BEAR_FAN }

    data class EmaFanSignal(
        val alignment: EmaAlignment,
        val score: Double,
        val spreadPct: Double,
        val widening: Boolean,
    )

    /**
     * v4: EMA fan — the core "keep riding" signal on multi-hour grinders.
     * MOONDO: EMA5 > EMA10 > EMA20 widening for 5 hours straight.
     * Optimistic: Same pattern for 11 hours.
     * Nigel: Fan inverted (EMA5 < EMA10 < EMA20) near top = exit.
     */
    private fun calcEmaFan(prices: List<Double>): EmaFanSignal {
        if (prices.size < 22) return EmaFanSignal(EmaAlignment.FLAT, 0.0, 0.0, false)

        val ema5prev  = ema(prices.takeLast(7),  5)
        val ema10prev = ema(prices.takeLast(13), 10)
        val ema20prev = ema(prices.takeLast(23), 20)

        val ema5  = ema(prices.takeLast(6),  5)
        val ema10 = ema(prices.takeLast(12), 10)
        val ema20 = ema(prices.takeLast(22), 20).coerceAtLeast(1e-12)

        val spreadPct     = (ema5 - ema20) / ema20 * 100.0
        val prevSpreadPct = (ema5prev - ema20prev) / ema20 * 100.0
        val widening      = spreadPct > prevSpreadPct + 0.1

        val bullFan   = ema5 > ema10 && ema10 > ema20
        val bearFan   = ema5 < ema10 && ema10 < ema20
        val tightBunch = Math.abs(spreadPct) < 0.5

        val alignment = when {
            tightBunch               -> EmaAlignment.FLAT
            bullFan && widening      -> EmaAlignment.BULL_FAN
            bullFan                  -> EmaAlignment.BULL_FLAT
            bearFan && widening      -> EmaAlignment.BEAR_FAN
            bearFan                  -> EmaAlignment.BEAR_FLAT
            else                     -> EmaAlignment.FLAT
        }

        val score = when (alignment) {
            EmaAlignment.BULL_FAN   -> 40.0 + (spreadPct.coerceIn(0.0, 5.0) * 2.0)
            EmaAlignment.BULL_FLAT  -> 15.0
            EmaAlignment.FLAT       -> 0.0
            EmaAlignment.BEAR_FLAT  -> -15.0
            EmaAlignment.BEAR_FAN   -> -40.0 - (Math.abs(spreadPct).coerceIn(0.0, 5.0) * 2.0)
        }

        return EmaFanSignal(alignment, score, spreadPct, widening)
    }

    /**
     * v4: StochRSI overbought collapse — Nigel chart pattern.
     * Uses buy ratio as a proxy for RSI since we don't store RSI in candles.
     * Early candles overbought (>65) and recent candles collapsing (<50) = sell signal.
     */
    private fun calcStochRsiSignal(hist: List<Candle>): Double {
        if (hist.size < 14) return 0.0

        val earlyRatioAvg  = hist.takeLast(14).take(7).map { it.buyRatio * 100.0 }.average()
        val recentRatioAvg = hist.takeLast(7).map { it.buyRatio * 100.0 }.average()
        val collapseMag    = (earlyRatioAvg - recentRatioAvg).coerceAtLeast(0.0)

        return when {
            earlyRatioAvg > 65 && recentRatioAvg < 50 && collapseMag > 20 -> 80.0
            earlyRatioAvg > 65 && recentRatioAvg < 50 && collapseMag > 10 -> 50.0
            earlyRatioAvg > 65 && recentRatioAvg < 58                     -> 25.0
            else                                                            -> 0.0
        }
    }

    /**
     * v4: Volume divergence — Rosie dead-cat pattern.
     * Price bouncing with declining volume = distribution, not accumulation.
     */
    private fun volumeDivergence(hist: List<Candle>, prices: List<Double>): Boolean {
        if (hist.size < 6 || prices.size < 6) return false
        val recentPrices = prices.takeLast(5)
        val recentVols   = hist.takeLast(5).map { it.vol }
        val recentBuys   = hist.takeLast(5).map { it.buyRatio }
        val priceRising  = recentPrices.last() > recentPrices.first() * 1.02
        val volFalling   = recentVols.last() < recentVols.first() * 0.75
        val buysFalling  = recentBuys.last() < recentBuys.first() - 0.08
        return priceRising && (volFalling || buysFalling)
    }

    /**
     * v4: ATH proximity — meme coins almost always reject at prior ATH.
     * Nigel: $527K ATH → immediate dump. Optimistic: $380K ATH → dump.
     */
    private fun athProximity(prices: List<Double>): Double {
        if (prices.size < 5) return 0.0
        val current     = prices.last()
        val athInWindow = prices.max()
        if (athInWindow <= 0) return 0.0
        val pctBelowAth = (athInWindow - current) / athInWindow * 100.0
        return when {
            pctBelowAth <= 2.0 -> 30.0
            pctBelowAth <= 5.0 -> 20.0
            pctBelowAth <= 8.0 -> 10.0
            else               -> 0.0
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Existing Indicators
    // ─────────────────────────────────────────────────────────────────

    private fun volumeScore(hist: List<Candle>): Double {
        val vols = hist.map { it.vol }.filter { it > 0 }
        if (vols.size < 3) return 50.0
        val recentAvg = sma(vols.takeLast(3))
        val baseAvg   = sma(vols.takeLast(minOf(vols.size, 12))).coerceAtLeast(1e-12)
        return ((recentAvg / baseAvg - 0.3) / 2.7 * 100.0).coerceIn(0.0, 100.0)
    }

    private fun pressureScore(hist: List<Candle>): Double {
        val recent  = hist.takeLast(5)
        if (recent.isEmpty()) return 50.0
        val weights = listOf(1.0, 2.0, 3.0, 4.0, 5.0).takeLast(recent.size)
        val wsum    = weights.zip(recent).sumOf { (w, c) -> w * c.buyRatio }
        return (wsum / weights.sum() * 100.0).coerceIn(0.0, 100.0)
    }

    private fun momentumScore(prices: List<Double>): Double {
        if (prices.size < 8) return 50.0
        val e4  = ema(prices.takeLast(5),  4)
        val e21 = ema(prices.takeLast(minOf(prices.size, 25)), 21).coerceAtLeast(1e-12)
        return ((e4 / e21 - 0.88) / 0.37 * 100.0).coerceIn(0.0, 100.0)
    }

    private fun volumeAcceleration(hist: List<Candle>): Double {
        val vols = hist.takeLast(4).map { it.vol }.filter { it > 0 }
        if (vols.size < 3) return 50.0
        var increases = 0
        for (i in 1 until vols.size) if (vols[i] > vols[i-1] * 1.05) increases++
        return (increases.toDouble() / (vols.size - 1) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun buyerDominance(hist: List<Candle>): Boolean {
        val recent = hist.takeLast(3)
        if (recent.size < 2) return false
        return recent.all { it.buyRatio >= 0.58 }
    }

    enum class MtfTrend { BULL, BEAR, NEUTRAL }

    private fun mtfTrend(history: ArrayDeque<Candle>): MtfTrend {
        val candles = history.toList()
        if (candles.size < 22) return MtfTrend.NEUTRAL
        val fan = calcEmaFan(candles.map { it.ref })
        return when (fan.alignment) {
            EmaAlignment.BULL_FAN, EmaAlignment.BULL_FLAT -> MtfTrend.BULL
            EmaAlignment.BEAR_FAN, EmaAlignment.BEAR_FLAT -> MtfTrend.BEAR
            else                                          -> MtfTrend.NEUTRAL
        }
    }

    private fun volumeExhaustion(hist: List<Candle>, requireStrong: Boolean = false): Boolean {
        // requireStrong: used when EMA fan is bullish + gain > 30%
        // In that case require 5-candle confirmation to avoid exiting healthy grinders
        val window = if (requireStrong) 5 else 4
        if (hist.size < window) return false
        val recent    = hist.takeLast(window)
        val vols      = recent.map { it.vol }
        val ratios    = recent.map { it.buyRatio }
        val drops     = (0 until vols.size - 1).count { vols[it] > vols[it + 1] }
        val ratioDrop = ratios.last() < ratios.first() - 0.06
        val dropThresh = if (requireStrong) window - 1 else 3  // need all-but-one declining
        return drops >= dropThresh && ratioDrop
    }

    private fun txnCountCollapse(hist: List<Candle>, ts: TokenState): Boolean {
        if (!ts.position.isOpen || hist.size < 6) return false
        val recent  = hist.takeLast(2).sumOf { it.buysH1 + it.sellsH1 }.toDouble()
        val earlier = hist.takeLast(6).take(3).sumOf { it.buysH1 + it.sellsH1 }.toDouble()
        if (earlier < 5) return false
        return recent < earlier * 0.40
    }

    // ─────────────────────────────────────────────────────────────────
    // Entry scoring
    // ─────────────────────────────────────────────────────────────────

    private fun calcLaunchEntryScore(
        phase: String, meta: StrategyMeta,
        vol: Double, press: Double,
        accel: Double, buyDom: Boolean,
        hist: List<Candle>,
        emafan: EmaFanSignal,
    ): Double {
        if (phase == "micro_cap_wait") return 0.0

        var s = 0.0
        when (phase) {
            "pre_pump"      -> s += 50.0
            "pumping"       -> s += 30.0
            "pump_pullback" -> s += 35.0
            "overextended"  -> s += 0.0
            "dying"         -> s += 0.0
            "early_unknown" -> s += 15.0
        }

        if (accel >= 66) s += 25.0 else if (accel >= 33) s += 10.0
        if (buyDom) s += 20.0

        s += when {
            press >= 65 -> 15.0
            press >= 55 -> 7.0
            press < 45  -> -20.0
            else        -> 0.0
        }

        if (vol >= 60) s += 10.0 else if (vol < 30) s -= 10.0
        if (hist.last().buyRatio < 0.45) s -= 15.0

        // v4: EMA fan on a fresh token = early momentum forming
        s += when (emafan.alignment) {
            EmaAlignment.BULL_FAN  -> 12.0
            EmaAlignment.BEAR_FAN  -> -20.0
            else                   -> 0.0
        }

        // FIX 5: Holder velocity — real launches have explosive holder growth
        val rH = hist.takeLast(3).map { it.holderCount }.filter { it > 0 }
        val eH = hist.takeLast(8).take(4).map { it.holderCount }.filter { it > 0 }
        if (rH.isNotEmpty() && eH.isNotEmpty() && eH.average() > 0) {
            val hGrowth = (rH.average() - eH.average()) / eH.average() * 100.0
            s += when {
                hGrowth >= 50.0 -> 18.0
                hGrowth >= 20.0 -> 10.0
                hGrowth >=  5.0 ->  5.0
                hGrowth < -5.0  -> -15.0
                else            ->  0.0
            }
        }

        return s.coerceIn(0.0, 100.0)
    }

    private fun calcRangeEntryScore(
        phase: String, meta: StrategyMeta,
        vol: Double, press: Double, mom: Double,
        accel: Double,
        hist: List<Candle>,
        emafan: EmaFanSignal,
        volDiv: Boolean,
    ): Double {
        if (volDiv) return 0.0            // v4: dead-cat bounce filter
        if (phase == "choppy_range") return 0.0  // v4: chop filter

        var s = 0.0
        when (phase) {
            "range" ->
                s += maxOf(0.0, 40.0 - meta.posInRange * 0.45).coerceAtMost(40.0)
            "strong_reclaim" -> s += 35.0   // v4: higher base than weak reclaim
            "reclaim_attempt" -> {
                // v5: penalise if StochRSI already overbought — exhausted recovery
                val stochNow = calcStochRsiSignal(hist)
                s += if (stochNow >= 25) 5.0 else 22.0
            }
            "cooling" -> {
                // v4: require EMA fan — don't buy cooling dips on downtrends
                val fanOk = emafan.alignment in listOf(EmaAlignment.BULL_FAN, EmaAlignment.BULL_FLAT)
                s += if (fanOk) 18.0 else 6.0
            }
        }

        s += when {
            vol >= 65 -> 20.0
            vol >= 45 -> (vol - 45) / 20.0 * 12.0
            vol < 25  -> -15.0
            else      -> 0.0
        }
        s += when {
            press >= 55 -> (press - 55) / 45.0 * 25.0
            press < 42  -> -20.0
            else        -> 0.0
        }
        s += when {
            mom >= 55 -> (mom - 55) / 45.0 * 13.0
            mom < 35  -> -10.0
            else      -> 0.0
        }

        // v4: EMA fan = entry confirmation
        s += when (emafan.alignment) {
            EmaAlignment.BULL_FAN   -> 18.0
            EmaAlignment.BULL_FLAT  -> 8.0
            EmaAlignment.FLAT       -> 0.0
            EmaAlignment.BEAR_FLAT  -> -10.0
            EmaAlignment.BEAR_FAN   -> -25.0
        }

        // FIX 4: Volume acceleration — breakout on established token confirmation
        s += when {
            accel >= 80 && emafan.alignment == EmaAlignment.BULL_FAN -> 20.0
            accel >= 66 -> 12.0
            accel >= 40 ->  5.0
            else        ->  0.0
        }

        return s.coerceIn(0.0, 100.0)
    }

    // ─────────────────────────────────────────────────────────────────
    // Exit scoring (v4: StochRSI + vol divergence + ATH proximity + EMA fan)
    // ─────────────────────────────────────────────────────────────────

    private fun calcExitScore(
        ts: TokenState, phase: String,
        mode: TradingMode,
        vol: Double, press: Double, mom: Double,
        exhaust: Boolean, txnDrop: Boolean,
        tokenAgeMins: Double,
        stochRsiCollapse: Double,
        volDiv: Boolean,
        athProx: Double,
        emafan: EmaFanSignal,
    ): Double {
        val pos = ts.position
        if (!pos.isOpen) return 0.0
        val ref = ts.ref
        if (ref == 0.0 || pos.entryPrice == 0.0) return 0.0

        val gainPct  = pct(pos.entryPrice, ref)
        val heldMins = (System.currentTimeMillis() - pos.entryTime) / 60_000.0
        var s        = 0.0

        // ── v4.1: MOMENTUM LOCK ───────────────────────────────────────
        // move3 > 40% during a pump flickers phase to overextended/early_unknown.
        // That means the pump is WORKING, not that we should exit.
        // If the pump is still genuinely alive, suppress all score-based exits.
        if (mode == TradingMode.LAUNCH_SNIPE && tokenAgeMins < 15.0 && !exhaust) {
            val hist2        = ts.history.toList()
            val latestBr     = hist2.lastOrNull()?.buyRatio ?: 0.0
            val heldCandles  = heldMins * (60.0 / cfg().pollSeconds.coerceAtLeast(1))
            val noLowerHigh  = run {
                val px = hist2.takeLast(8).map { it.ref }
                if (px.size < 4) true
                else px.takeLast(px.size / 2).max() >= px.take(px.size / 2).max() * 0.94
            }
            val fanOk    = emafan.alignment in listOf(EmaAlignment.BULL_FAN,
                               EmaAlignment.BULL_FLAT, EmaAlignment.FLAT)
            // First 8 candles of hold = pure pump phase, sells accumulate naturally
            // while price still rises — use looser br threshold (0.42 not 0.52)
            val brThresh  = if (heldCandles < 8) 0.42 else 0.52
            val pumpAlive = latestBr > brThresh && noLowerHigh && fanOk && heldCandles < 12
            if (pumpAlive && phase in listOf("overextended", "early_unknown",
                                              "pumping", "pump_pullback", "pre_pump")) {
                if (press >= 35.0) return 0.0
            }
        }

        if (exhaust)  s += 40.0
        if (txnDrop)  s += 25.0

        // v4.1: Spike top detection (SAAD pattern)
        val spike = detectSpikeTop(ts.history.toList(), ts.history.toList().map { it.ref }, gainPct)
        s += spike.urgency

        // v4: StochRSI overbought collapse (Nigel pattern)
        s += when {
            stochRsiCollapse >= 70 -> 22.0
            stochRsiCollapse >= 40 -> 12.0
            else                   -> 0.0
        }

        // v4: Volume divergence (Rosie dead-cat)
        if (volDiv) s += 20.0

        // v4: ATH proximity (Nigel/Optimistic top)
        s += athProx

        // v4: EMA fan direction
        s += when (emafan.alignment) {
            EmaAlignment.BEAR_FAN   -> 25.0
            EmaAlignment.BEAR_FLAT  -> 12.0
            EmaAlignment.BULL_FAN   -> -8.0   // healthy uptrend → hold longer
            EmaAlignment.BULL_FLAT  -> -4.0
            else                    -> 0.0
        }

        // Pressure collapse
        s += when {
            press < 30 -> 32.0
            press < 42 -> (42.0 - press) / 12.0 * 22.0
            else       -> 0.0
        }

        // ── v4.1: Phase urgency with confirmation gate ───────────────
        // During launch snipe window, overextended/early_unknown needs a
        // confirming signal — phase alone on a steep pump is not a real top.
        val phaseUrgency = when (phase) {
            "breakdown"      -> 60.0
            "dying"          -> 55.0
            "overextended"   -> 35.0
            "expansion_peak" -> 30.0
            "distribution"   -> 25.0
            "choppy_range"   -> 20.0
            "cooling"        -> if (gainPct > 0) 12.0 else 20.0
            else             -> 0.0
        }
        val needsConfirm  = mode == TradingMode.LAUNCH_SNIPE
            && tokenAgeMins < 15.0
            && phase in listOf("overextended", "early_unknown")
        val hasConfirm    = exhaust || txnDrop || press < 40.0
            || emafan.alignment in listOf(EmaAlignment.BEAR_FAN, EmaAlignment.BEAR_FLAT)
        s += if (needsConfirm && !hasConfirm) phaseUrgency * 0.20 else phaseUrgency

        // Momentum reversal
        s += when {
            mom < 25 -> 25.0
            mom < 42 -> (42.0 - mom) / 17.0 * 18.0
            else     -> 0.0
        }

        // Phase change from entry
        val goodPhases = listOf("range", "pumping", "pre_pump", "pump_pullback",
                                "cooling", "reclaim_attempt", "strong_reclaim")
        if (pos.entryPhase in goodPhases && phase !in goodPhases) s += 18.0

        // ── v4.1: Profit protection + dynamic trailing hold ──────────
        // For big winners (>50% gain) switch to trailing-stop logic.
        // Don't exit on score — only exit on trail breach or exhaustion.
        // This is how we ride the 10x instead of locking in the 2x.
        val highestSeen     = ts.position.highestPrice.coerceAtLeast(pos.entryPrice)
        val drawdownFromHigh = if (highestSeen > 0)
            (highestSeen - ref) / highestSeen * 100.0 else 0.0

        val spikeCheck = detectSpikeTop(ts.history.toList(), ts.history.toList().map { it.ref }, gainPct)

        // FIX 2: Unified trail — score proximity to trailingFloor, not duplicate calc
        if (gainPct > 50.0) {
            val base     = cfg().trailingStopBasePct
            val floorPct = when {
                spikeCheck.isSpike || spikeCheck.isPostSpike -> base * 0.32
                spikeCheck.wickRejection                     -> base * 0.35
                gainPct > 500.0                              -> base * 0.35
                gainPct > 200.0                              -> base * 0.40
                gainPct > 100.0                              -> base * 0.45
                else                                         -> base * 0.50
            }
            when {
                drawdownFromHigh > floorPct ->
                    s += if (mode == TradingMode.LAUNCH_SNIPE) 60.0 else 45.0
                drawdownFromHigh > floorPct * 0.70 -> s += 20.0
                !exhaust && press >= 40.0 && !spikeCheck.isPostSpike -> {
                    val cap = if (emafan.alignment == EmaAlignment.BULL_FAN) 15.0 else 25.0
                    s = s.coerceAtMost(cap)
                }
            }
        } else if (gainPct > 50.0 && mode == TradingMode.RANGE_TRADE) {
            val fanBad = emafan.alignment in listOf(EmaAlignment.BEAR_FAN, EmaAlignment.BEAR_FLAT)
            // CHANGE 2b: Range trade trailing stops — tighter
            val trailPct = when {
                spikeCheck.isSpike || spikeCheck.isPostSpike -> 3.5
                spikeCheck.wickRejection                     -> 4.0
                gainPct > 500                                -> 4.0
                gainPct > 200                                -> 5.5
                gainPct > 100                                -> 7.0
                else                                         -> 9.0  // was 10%
            }
            s += when {
                drawdownFromHigh > trailPct      -> 45.0
                gainPct > 80 && fanBad           -> 25.0
                gainPct > 80                     -> 8.0
                else                             -> 15.0
            }
        } else {
            s += when {
                gainPct > 30 -> minOf(20.0, (gainPct - 30) * 0.7)
                gainPct > 15 -> minOf(10.0, (gainPct - 15) * 0.5)
                else         -> 0.0
            }
        }

        // Dynamic hold time
        val holdExtension = calcHoldExtension(ts, vol, press)

        if (mode == TradingMode.LAUNCH_SNIPE) {
            // Launch snipe is always 1M — no tfScale needed here
            val effectiveWindow = 15.0 + (holdExtension / 100.0) * 105.0
            val hardMax = cfg().maxHoldMinsHard
            if (heldMins > minOf(effectiveWindow, hardMax)) {
                s += minOf(35.0, (heldMins - effectiveWindow).coerceAtLeast(0.0) * 2.5)
            }
        }

        if (mode == TradingMode.RANGE_TRADE) {
            val effectiveWindow = (45.0 + (holdExtension / 100.0) * 105.0) * tfScale
            val hardMax = effectiveMaxHoldMins(ts, tfScale, emafan, vol, press, gainPct)
            if (heldMins > minOf(effectiveWindow, hardMax) && gainPct > 3.0) {
                // Long-hold positions: very gentle time penalty (let the trend breathe)
                val rate = if (ts.position.isLongHold) 0.02 else 0.3
                s += minOf(15.0, (heldMins - effectiveWindow).coerceAtLeast(0.0) * rate)
            }
        }

        // CHANGE 5: Stronger BULL_FAN grinder exit suppression
        // MOONDO ran +6960% — we should ride the full trend, not exit at 500%.
        // Extended to gain>10% so gains are protected from early in the move.
        if (mode == TradingMode.RANGE_TRADE
            && emafan.alignment == EmaAlignment.BULL_FAN
            && gainPct > 10.0 && !exhaust && press >= 45.0) {
            val suppress = when {
                gainPct > 200 ->  5.0   // at 200%+ keep exits open — protect profits
                gainPct > 100 -> 12.0   // was 8
                gainPct > 60  -> 20.0   // was 14
                gainPct > 30  -> 28.0   // was 20 — biggest change
                else          -> 22.0   // new: protect 10-30% early gains
            }
            s = (s - suppress).coerceAtLeast(0.0)
        }
        // Apply for widening BULL_FAN on launch snipes too
        if (mode == TradingMode.LAUNCH_SNIPE
            && emafan.alignment == EmaAlignment.BULL_FAN
            && emafan.widening
            && gainPct > 50.0 && !exhaust && press >= 45.0) {
            s = (s - 15.0).coerceAtLeast(0.0)
        }

        // Stale-loser cut — scale to timeframe
        // 1M: cut at 1.5min stale / 8min losing (original)
        // 4H: cut at 1.5×240=360min stale / 8×240=1920min losing (= 32h)
        val staleMins   = if (mode == TradingMode.LAUNCH_SNIPE) 1.5 else 2.0 * tfScale
        val staleThresh = if (mode == TradingMode.LAUNCH_SNIPE) -1.5 else -3.0
        if (gainPct < staleThresh && heldMins > staleMins && press < 50) s += 25.0

        // Max losing hold — scale to timeframe
        val maxLosingMins = 8.0 * tfScale
        if (gainPct < 0 && heldMins > maxLosingMins) s += 30.0

        return s.coerceIn(0.0, 100.0)
    }

    // ─────────────────────────────────────────────────────────────────
    // Signal decision
    // ─────────────────────────────────────────────────────────────────

    private fun decideSignal(
        ts: TokenState,
        hist: List<Candle>,
        prices: List<Double>,
        phase: String,
        mode: TradingMode,
        entryScore: Double,
        exitScore: Double,
        exhaust: Boolean,
        meta: StrategyMeta,
        tokenAgeMins: Double,
        emafan: EmaFanSignal,
        volDiv: Boolean,
        whale: WhaleDetector.WhaleSignal,
        curve: BondingCurveTracker.CurveState,
    ): String {
        val c   = cfg()
        val pos = ts.position

        if (pos.isOpen) {
            if (phase in listOf("breakdown", "dying")) return "EXIT"
            // FIX 1c: Brain exit threshold — negative delta = tighter exits (take profit sooner)
            val brainExitAdj    = try { brain()?.exitThresholdDelta ?: 0.0 } catch (_: Exception) { 0.0 }
            val baseExitThresh  = modeConf?.exitScoreThreshold ?: c.exitScoreThreshold
            val effectiveExitThresh = (baseExitThresh + brainExitAdj).coerceIn(35.0, 75.0)
            if (exitScore >= effectiveExitThresh) return "EXIT"
            if (exhaust && exitScore >= effectiveExitThresh * 0.68) return "EXIT"

            // v4.1: Post-spike = always exit (SAAD lesson — don't ride the dump)
            val gainNow  = pct(ts.position.entryPrice, ts.ref)
            val spikeNow = detectSpikeTop(hist, prices, gainNow)
            if (spikeNow.isPostSpike) return "EXIT"
            if (spikeNow.protectMode && spikeNow.wickRejection) return "EXIT"

            // v4: Bear fan = strong exit signal
            if (emafan.alignment == EmaAlignment.BEAR_FAN && exitScore >= 35) return "EXIT"

            if (prices.size >= 4) {
                val tail = prices.takeLast(3)
                if (tail.all { it < meta.ema8 } && exitScore >= 25) return "EXIT"
            }

            val heldMins2 = (System.currentTimeMillis() - pos.entryTime) / 60_000.0
            val tfScale2  = ts.candleTimeframeMinutes.toDouble().coerceAtLeast(1.0)
            val gainPct   = pct(pos.entryPrice, ts.ref)

            val maxHold = effectiveMaxHoldMins(ts, tfScale2, emafan, vol, press, gainPct)
            if (heldMins2 > maxHold)                       return "EXIT"
            if (heldMins2 < cfg().minHoldMins * tfScale2) return "WAIT"

            // Losing hold: 48h grace period on long-hold, 8×tfScale otherwise
            val maxLosing = if (ts.position.isLongHold) 48.0 * 60.0
                            else 8.0 * tfScale2
            if (gainPct < 0 && heldMins2 > maxLosing) return "EXIT"

            if (phase == "distribution" && exitScore >= 35) return "SELL"
            if (phase == "choppy_range" && exitScore >= 40) return "SELL"
            return "WAIT"
        }

        // No position — check cooldown + post-win boost
        val msSinceExit   = System.currentTimeMillis() - ts.lastExitTs
        val inCooldown    = msSinceExit < cfg().entryCooldownSec * 1_000L

        // v4.4: Post-win re-entry boost — token proved itself, lower threshold temporarily
        val postWinActive = ts.lastExitWasWin
            && msSinceExit < (cfg().postWinReentryBoostMins * 60_000.0).toLong()
        if (postWinActive && !inCooldown) {
            entryScore = (entryScore + cfg().postWinEntryThresholdBoost).coerceIn(0.0, 100.0)
        }

        // CHANGE 7: Source quality propagates to entry score
        if (!ts.position.isOpen && ts.source.isNotBlank()) {
            val srcBoost = when {
                ts.source.contains("GRAD",    ignoreCase = true) ->  5.0
                ts.source.contains("TRENDING",ignoreCase = true) ->  3.0
                ts.source.contains("BOOSTED", ignoreCase = true) ->  2.0
                else -> 0.0
            }
            if (srcBoost > 0)
                entryScore = (entryScore + srcBoost).coerceIn(0.0, 100.0)
        }

        // v5.1: Smart established token scoring (replaces blunt 30d cutoff)
        // ═══════════════════════════════════════════════════════════════════
        // Age alone is NOT a disqualifier. WOJAK (135d, 13.3K holders, $336K
        // liquidity) is MORE stable than a 3-day token with 200 holders.
        //
        // What actually matters for older tokens:
        //   GOOD signs → holders still growing, liquidity healthy, vol consistent
        //   BAD signs  → holders declining, liquidity thin, vol dying, EMA bear fan
        //
        // The system applies adjustments (positive or negative) based on the
        // combination of these signals, not age alone.
        if (!ts.position.isOpen && mode == TradingMode.RANGE_TRADE) {
            entryScore = applyEstablishedTokenScore(
                entryScore = entryScore,
                ts         = ts,
                tokenAgeMins = tokenAgeMins,
                hist       = hist,
            )
        }

        if (inCooldown) {
            if (!smartReentry(ts, phase, hist, whale, curve)) return "WAIT"
        }

        // Phase blocks
        if (phase in listOf("breakdown", "distribution", "expansion_peak", "overextended",
                            "dying", "thin_market", "micro_cap_wait", "choppy_range")) return "WAIT"

        // v4: block on volume divergence
        if (volDiv) return "WAIT"

        // Pullback entry — lower thresholds when buying a confirmed dip
        val pb = isPullbackEntry(hist, prices)
        val adj = if (pb) 8 else 0  // reduce threshold by 8 on pullback

        // FIX 1b: Apply BotBrain learned threshold adjustments
        // brain.effectiveEntryThreshold(base) returns base + entryThresholdDelta
        // A negative delta means brain has learned to be MORE aggressive (lower bar)
        // A positive delta means brain learned caution (higher bar)
        val sTierTh   = if (cfg().scalingModeEnabled) ScalingMode.activeTier(TreasuryManager.treasurySol * WalletManager.lastKnownSolPrice) else ScalingMode.Tier.MICRO
        val tokenTh   = ScalingMode.tierForToken(ts.lastLiquidityUsd, ts.lastFdv)
        // FIX 8: Regime compounds with tier threshold adj
        // In BEAR regime, higher-tier entries need even stronger signals
        val regimeCompound = when {
            brain()?.regimeBullMult != null && (brain()?.regimeBullMult ?: 1.0) < 0.80 -> 5.0  // brain sees bear
            else -> 0.0
        }
        val tierThAdj = if (tokenTh.ordinal >= sTierTh.ordinal) when (sTierTh) {
            ScalingMode.Tier.INSTITUTIONAL -> 15.0 + regimeCompound
            ScalingMode.Tier.SCALED        -> 10.0 + regimeCompound
            ScalingMode.Tier.GROWTH        ->  5.0 + regimeCompound
            else                           ->  0.0 + regimeCompound
        } else regimeCompound

                val brainAdj = try { brain()?.entryThresholdDelta ?: 0.0 } catch (_: Exception) { 0.0 }

        when (mode) {
            TradingMode.LAUNCH_SNIPE -> {
                when (phase) {
                    "pre_pump"      -> if (entryScore >= (55 + brainAdj + tierThAdj) - adj) return "BUY"
                    "pumping"       -> if (entryScore >= (42 + brainAdj + tierThAdj) - adj) return "BUY"
                    "pump_pullback" -> if (entryScore >= (30 + brainAdj + tierThAdj))       return "BUY"
                    "early_unknown" -> if (entryScore >= (65 + brainAdj + tierThAdj) - adj) return "BUY"
                }
            }
            TradingMode.RANGE_TRADE -> {
                when (phase) {
                    "range" -> {
                        // Bottom 25% required + stability: 2+ of last 4 candles in bottom 30%
                        // Prevents momentary dips on oscillating bases (WOJAK pattern)
                        val recentPosInRange = run {
                            val w12 = prices.takeLast(12)
                            if (w12.size < 4) listOf(meta.posInRange)
                            else {
                                val hi = w12.max(); val lo = w12.min()
                                w12.takeLast(4).map { p ->
                                    if (hi != lo) (p - lo) / (hi - lo) * 100.0 else 50.0
                                }
                            }
                        }
                        val stableAtBottom = recentPosInRange.count { it < 30.0 } >= 2
                        if (meta.posInRange < 25.0 && stableAtBottom && entryScore >= 40 + tierThAdj - adj) return "BUY"
                    }
                    "strong_reclaim" -> {
                        val volOk = hist.takeLast(3).let {
                            it.size >= 2 && it.last().vol >= it.first().vol * 0.9
                        }
                        if (entryScore >= 38 - adj && volOk) return "BUY"
                    }
                    "reclaim_attempt" -> {
                        val volOk = hist.takeLast(3).let {
                            it.size >= 2 && it.last().vol >= it.first().vol
                        }
                        if (entryScore >= 45 - adj && volOk) return "BUY"
                    }
                    "cooling" -> {
                        val fanOk = emafan.alignment in listOf(
                            EmaAlignment.BULL_FAN, EmaAlignment.BULL_FLAT)
                        if (entryScore >= 50 - adj && meta.posInRange < 40.0 && fanOk) return "BUY"
                    }
                }
            }
        }

        return when (phase) {
            "pre_pump"        -> "WAIT_BUILDING"
            "pumping"         -> "WAIT_PULLBACK"
            "pump_pullback"   -> "WAIT_CONFIRM"
            "cooling"         -> "WAIT_COOLING"
            "reclaim_attempt" -> "WAIT_CONFIRM"
            "strong_reclaim"  -> "WAIT_CONFIRM"
            "micro_cap_wait"  -> "WAIT_HOLDERS"
            "choppy_range"    -> "WAIT_CHOP"
            else              -> "WAIT"
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Smart re-entry
    // ─────────────────────────────────────────────────────────────────

    private fun smartReentry(
        ts: TokenState,
        phase: String,
        hist: List<Candle>,
        whale: WhaleDetector.WhaleSignal,
        curve: BondingCurveTracker.CurveState,
    ): Boolean {
        val lastExitPrice = ts.lastExitPrice
        val currentPrice  = ts.lastPrice

        if (phase in listOf("breakdown", "dying", "distribution", "overextended",
                            "choppy_range", "micro_cap_wait")) return false

        if (lastExitPrice > 0 && currentPrice > 0) {
            val moveSinceExit = Math.abs((currentPrice - lastExitPrice) / lastExitPrice * 100.0)
            if (moveSinceExit < 5.0) return false
        }

        val bullishPhase = phase in listOf("pre_pump", "pumping", "pump_pullback",
                                            "range", "reclaim_attempt", "strong_reclaim")
        if (!bullishPhase) return false

        val whaleConfirm  = whale.hasWhaleActivity && whale.whaleScore >= 50
        val curveConfirm  = curve.stage == BondingCurveTracker.CurveStage.PRE_GRAD
                            || curve.stage == BondingCurveTracker.CurveStage.GRADUATING
        val prePumpFresh  = phase == "pre_pump"
        val strongReclaim = phase == "strong_reclaim"  // v4

        return whaleConfirm || curveConfirm || prePumpFresh || strongReclaim
    }

    // ─────────────────────────────────────────────────────────────────
    // Dynamic hold extension (v4: EMA fan scoring as primary signal)
    // ─────────────────────────────────────────────────────────────────

    private fun calcHoldExtension(
        ts: TokenState,
        volScore: Double,
        pressScore: Double,
    ): Double {
        val c   = cfg()
        var pts = 0.0

        // 1. Volume health
        pts += when {
            volScore >= 70 -> 40.0
            volScore >= c.holdExtendVolThreshold ->
                (volScore - c.holdExtendVolThreshold) /
                (70.0 - c.holdExtendVolThreshold) * 30.0 + 10.0
            volScore >= 40 -> 5.0
            else           -> 0.0
        }

        // 2. Buyer pressure
        pts += when {
            pressScore >= 65 -> 30.0
            pressScore >= c.holdExtendPressThreshold ->
                (pressScore - c.holdExtendPressThreshold) /
                (65.0 - c.holdExtendPressThreshold) * 20.0 + 10.0
            else -> 0.0
        }

        // 3. Holder count growing
        val hist = ts.history.toList()
        if (hist.size >= 5) {
            val recentHolders  = hist.takeLast(3).map { it.holderCount }.filter { it > 0 }
            val earlierHolders = hist.takeLast(8).take(4).map { it.holderCount }.filter { it > 0 }
            if (recentHolders.isNotEmpty() && earlierHolders.isNotEmpty()) {
                val recentAvg  = recentHolders.average()
                val earlierAvg = earlierHolders.average()
                pts += when {
                    recentAvg > earlierAvg * 1.15 -> 20.0
                    recentAvg > earlierAvg * 1.05 -> 10.0
                    recentAvg < earlierAvg * 0.95 -> -10.0
                    else                           -> 5.0
                }
            }
        }

        // 4. Higher highs forming
        if (hist.size >= 6) {
            val prices = hist.takeLast(6).map { it.ref }
            val high1  = prices.take(3).max()
            val high2  = prices.takeLast(3).max()
            pts += when {
                high2 > high1 * 1.05 -> 10.0
                high2 < high1 * 0.95 -> -5.0
                else                 -> 3.0
            }
        }

        // 5. v4: EMA fan — the key change for multi-hour runners
        // MOONDO ran 5h and Optimistic ran 11h with a consistent bull fan.
        // Without this, the 25 min base window was exiting these at 2-5x
        // instead of letting them run to 70-100x.
        val prices = hist.map { it.ref }
        val emafan = calcEmaFan(prices)
        pts += when (emafan.alignment) {
            EmaAlignment.BULL_FAN   -> 25.0 + if (emafan.widening) 5.0 else 0.0
            EmaAlignment.BULL_FLAT  -> 10.0
            EmaAlignment.FLAT       -> 0.0
            EmaAlignment.BEAR_FLAT  -> -15.0
            EmaAlignment.BEAR_FAN   -> -30.0
        }

        // Long-hold positions always get full extension — conviction was
        // already verified externally, keep exit score low while trend holds
        if (ts.position.isLongHold) return 100.0
        return pts.coerceIn(0.0, 100.0)
    }

    // ─────────────────────────────────────────────────────────────────
    // Long-hold conviction scoring
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns 0-100 conviction score for long-hold promotion.
     * Score >= 70 → position can hold for up to longHoldMaxDays.
     * Any non-negotiable failure → returns 0 immediately.
     *
     * Non-negotiables (instant fail):
     *   • EMA not BULL_FAN or BULL_FLAT
     *   • Position is losing (gainPct < longHoldMinGainPct)
     *   • Liquidity too thin to exit safely over days
     *   • Holder count below minimum (rug risk)
     *   • Treasury gate not met (no locked profits yet)
     *   • Wallet exposure cap would be exceeded
     *
     * Scored criteria (build to 100):
     *   • EMA fan quality and widening          (up to 30pts)
     *   • Holder growth velocity                (up to 25pts)
     *   • Liquidity depth                       (up to 20pts)
     *   • Volume consistency                    (up to 15pts)
     *   • Buyer pressure                        (up to 10pts)
     */
    private fun calcConviction(
        ts: TokenState,
        emafan: EmaFanSignal,
        vol: Double,
        press: Double,
        gainPct: Double,
        walletSol: Double = 0.0,           // passed in — avoids BotService.instance
        existingLHSol: Double = 0.0,       // current long-hold exposure
    ): Double {
        val c = cfg()
        if (!c.longHoldEnabled) return 0.0

        // Non-negotiables
        if (emafan.alignment !in listOf(EmaAlignment.BULL_FAN, EmaAlignment.BULL_FLAT)) return 0.0
        if (gainPct < c.longHoldMinGainPct) return 0.0
        if (ts.lastLiquidityUsd < c.longHoldMinLiquidityUsd) return 0.0
        if ((ts.history.lastOrNull()?.holderCount ?: 0) < c.longHoldMinHolders) return 0.0
        if (c.longHoldTreasuryGate && TreasuryManager.treasurySol < 0.01) return 0.0

        // Wallet headroom — values injected as params (no BotService.instance)
        val maxLongHoldSol = if (walletSol > 0) walletSol * c.longHoldWalletPct
                             else Double.MAX_VALUE
        if (walletSol > 0 && existingLHSol + ts.position.costSol > maxLongHoldSol) return 0.0

        // Scored criteria
        var score = 0.0
        score += when {
            emafan.alignment == EmaAlignment.BULL_FAN && emafan.widening -> 30.0
            emafan.alignment == EmaAlignment.BULL_FAN                    -> 22.0
            else                                                          -> 10.0
        }
        score += when {
            ts.holderGrowthRate >= 10.0                  -> 25.0
            ts.holderGrowthRate >= c.longHoldHolderGrowthMin -> 15.0
            ts.holderGrowthRate >= 0.0                   ->  5.0
            else                                         ->  0.0
        }
        score += when {
            ts.lastLiquidityUsd >= 200_000.0 -> 20.0
            ts.lastLiquidityUsd >= 100_000.0 -> 15.0
            ts.lastLiquidityUsd >= 50_000.0  -> 10.0
            else                             ->  5.0
        }
        score += when {
            vol >= 70 -> 15.0
            vol >= 55 -> 10.0
            vol >= 40 ->  5.0
            else      ->  0.0
        }
        score += when {
            press >= 60 -> 10.0
            press >= 50 ->  5.0
            else        ->  0.0
        }
        return score.coerceIn(0.0, 100.0)
    }

    /** Effective max hold in minutes — standard or long-hold extended */
    private fun effectiveMaxHoldMins(
        ts: TokenState,
        tfScale: Double,
        emafan: EmaFanSignal,
        vol: Double,
        press: Double,
        gainPct: Double,
        walletSol: Double = 0.0,
        existingLHSol: Double = 0.0,
    ): Double {
        val c        = cfg()
        val standard = c.maxHoldMinsHard * tfScale
        if (!c.longHoldEnabled) return standard

        if (ts.position.isLongHold) return c.longHoldMaxDays * 24.0 * 60.0

        val conviction = calcConviction(ts, emafan, vol, press, gainPct, walletSol, existingLHSol)
        return if (conviction >= 70.0) c.longHoldMaxDays * 24.0 * 60.0
               else                   standard
    }

    // ─────────────────────────────────────────────────────────────────
    // Sentiment overlay (unchanged)
    // ─────────────────────────────────────────────────────────────────

    private fun applySentimentOverlay(
        ts: TokenState,
        entryScore: Double,
        exitScore: Double,
        c: com.lifecyclebot.data.BotConfig,
    ): Pair<Double, Double> {
        var es = entryScore; var xs = exitScore
        val sentiment = ts.sentiment
        // v4.4: Only use sentiment when API keys are actually configured — avoids noise
        val sentimentActive = c.sentimentEnabled
            && (!c.sentimentRequiresKeys || c.groqApiKey.isNotBlank() || c.telegramBotToken.isNotBlank())
        if (!sentimentActive || sentiment.isStale || sentiment.confidence <= 20) return es to xs

        val decayed = sentiment.decayedScore
        when {
            sentiment.blocked             -> es = 0.0
            decayed < c.sentimentBlockThreshold -> es = (es - 30.0).coerceAtLeast(0.0)
            decayed > c.sentimentBoostThreshold -> {
                val boost = (decayed - c.sentimentBoostThreshold) /
                            (100.0 - c.sentimentBoostThreshold) * c.sentimentEntryBoost
                es = (es + boost).coerceAtMost(100.0)
                xs = (xs - c.sentimentExitBoost * 0.5).coerceAtLeast(0.0)
            }
        }
        if (sentiment.divergenceSignal && decayed > 0 && !ts.position.isOpen)
            es = (es + 15.0).coerceAtMost(100.0)
        if (sentiment.concentrationBlocked) es = 0.0

        return es to xs
    }
}
