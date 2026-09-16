package com.lifecyclebot.engine

/**
 * V5.9.722 — WR-Recovery Partial Sell Manager
 *
 * When current session WR is meaningfully below the phase target, the first
 * partial-sell milestone is lowered so the bot locks a win sooner.  The rest
 * of the position still rides with normal trail/SL logic — this is purely
 * an earlier first-lock, not a choke on runners.
 *
 * Recovery mode activates when:
 *   currentWR < phaseTargetWR * WR_RECOVERY_THRESHOLD  (default 0.85)
 *   AND partialLevel == 0 (first sell only — don't interfere with later rungs)
 *   AND gainPct >= MIN_PARTIAL_GAIN_PCT (never micro-lock at noise levels)
 *   AND position is NOT already profit-locked (already safe — let it ride)
 *
 * The override trigger is clamped to:
 *   max(MIN_PARTIAL_GAIN_PCT, normalTrigger * RECOVERY_TRIGGER_SCALE)
 * e.g. normal trigger = 15% → recovery trigger = max(5%, 15%*0.60) = 9%
 */
object WrRecoveryPartial {
    // V5.9.797 — operator audit: 'win rate recovery should basically be
    // running in a FLUID STATE. it's not preemptively working to help itself.'
    //
    // Pre-V5.9.797 behaviour: recovery flipped ON below target × 0.85, OFF
    // otherwise. That left a 15-point gap (between 0.85×target and target)
    // where the bot ran with the +200% / +500% / +2000% config defaults —
    // which on real meme trajectories NEVER fire. So whenever WR was just
    // *slightly* under target, the bot had ZERO partial protection.
    //
    // V5.9.797 — three intensity bands, no gap. Recovery is active anytime
    // current WR < target. The deeper the deficit, the tighter the ladder:
    //
    //   FLUID    — currentWR ≤ target            : light protection (always-on)
    //              rungs +30/+60/+120%, sell 20% each
    //   MODERATE — currentWR < target × 0.95     : closer in
    //              rungs +25/+45/+80%, sell 25% each
    //   AGGRESSIVE — currentWR < target × 0.85   : deep deficit
    //              rungs +18/+35/+60%, sell 25% each
    //
    // Pre-emptive band (PREDICTIVE) — rolling-50 WR < target × 0.90 even
    // while lifetime WR is on target. The "see it coming and pre-empt" piece
    // operator asked for. Kicks the ladder one band tighter than the
    // lifetime-based decision would have produced.
    private const val DEEP_THRESHOLD     = 0.85
    private const val MODERATE_THRESHOLD = 0.95
    private const val FLUID_THRESHOLD    = 1.00
    private const val PREDICTIVE_THRESHOLD = 0.90
    // V5.0.3963 — learned live-profit floor. The old 9% WR-recovery rung
    // created sub-fee scrap exits (+0.2%/+1%/+9%) that do not compound the
    // live wallet and amputate runners. This is only a floor: actual bands
    // below are fluid and expectancy/MFE-driven per lane.
    private const val MIN_PARTIAL_GAIN_PCT = 50.0

    // V5.9.1473 — PERFORMING: the bot is AT or ABOVE its phase WR target.
    // Pre-1473, this state mapped to Band.OFF, which reverted the partial
    // ladder to the +200%/+500%/+2000% config defaults — meaning a WINNING
    // bot took ZERO partials and rode every +10-30% winner naked until it
    // either hit +200% or round-tripped to zero (operator screenshot
    // 2026-06-10: MUSK +42926%, TOESCOIN +274%, plus 4 winners +10-18% all
    // sitting open with no partials). PERFORMING gives a sane baseline
    // winner-lock ladder so realized P&L accrues on normal winners while
    // runners still ride the bulk into Capital Recovery / Profit Lock /
    // Fluid Trail. Distinct from recovery bands: this is profit CAPTURE on
    // a healthy bot, not loss DEFENSE on a sick one.
    enum class Band { OFF, PERFORMING, FLUID, MODERATE, AGGRESSIVE }

    /** Snapshot of the WR-recovery state for the current call. */
    data class State(
        val band: Band,
        val currentWr: Double,
        val targetWr: Double,
        val totalSettled: Int,
        val rollingWr: Double,        // last 50 settled
        val predictive: Boolean,      // rolling-50 below target × 0.90
        val rollingCollapse: Boolean = false, // rolling-50 is catastrophically below target
    ) {
        val active: Boolean get() = band != Band.OFF
    }

    /**
     * V5.9.797 — compute & cache the WR recovery state. Reads the SQLite
     * lifetime stats AND samples the rolling-50 short-term WR from the
     * in-memory trade history so predictive recovery can fire before the
     * lifetime number degrades.
     */
    fun stateNow(): State {
        val stats = try {
            com.lifecyclebot.engine.TradeHistoryStore.getLifetimeStats()
        } catch (_: Throwable) { null }
        val wins   = (stats?.totalWins ?: 0).toDouble()
        val losses = (stats?.totalLosses ?: 0).toDouble()
        val total  = wins + losses

        // V5.9.799 — operator audit: 'win rates / ratio meant to start at
        // around 20% or better from trade ONE. to ever improve over 50% we
        // need to be working from trade 1.'
        //
        // Pre-V5.9.799 returned Band.OFF for the first 50 trades, which
        // meant the bootstrap window — the riskiest part of the bot's
        // career — had ZERO partial protection and ZERO entry quality
        // gating. The bot could C/D-grade entries with +200% defaults until
        // 50 trades had piled up at sub-20% WR, then activate recovery
        // AFTER the damage. The operator wants the opposite: training
        // wheels FROM trade 1, then graduate to lifetime-WR logic once we
        // have a meaningful sample.
        //
        // New bootstrap policy still selects the protection band early, but
        // V5.0.3963 no longer maps that to tiny +9/+35/+60% scraps. The band
        // feeds learnedExitRungs(), which applies the same StrategyTelemetry
        // expectancy/MFE table shown in reports and floors first profit at +50%.
        if (total < 25.0) {
            return State(Band.AGGRESSIVE, 0.0, 0.0, total.toInt(), -1.0, false)
        }
        if (total < 50.0) {
            return State(Band.MODERATE, 0.0, 0.0, total.toInt(), -1.0, false)
        }

        val currentWR = if (total > 0) (wins / total) * 100.0 else 0.0
        val targetWRRaw  = try {
            com.lifecyclebot.engine.FreeRangeMode.phaseTargetWr(total.toInt())
        } catch (_: Exception) { 30.0 }
        // V5.9.802 — operator audit Fix D rescue:
        // FreeRangeMode.phaseTargetWr returns 0.0 for the entire trades<500
        // range (PHASE1_START). That silently disables WR Recovery for the
        // 50–500 trade band — exactly the window V5.9.801 was designed to
        // protect via Fix A's quality floor and Fix D's size dampener.
        // Operator forensics: at 179 trades the Live Readiness pill showed
        // "target 0.0% · size×1.00" → band=OFF → all V5.9.801 logic dead.
        // Apply a sensible 25% default through the 50–500 range so the
        // band can engage. Beyond 500 the lerped FreeRangeMode targets
        // take over unchanged.
        val targetWR = if (targetWRRaw <= 0.0 && total in 50.0..499.0) 25.0 else targetWRRaw
        if (targetWR <= 0.0) return State(Band.OFF, currentWR, 0.0, total.toInt(), 0.0, false)

        val rollingWr = try {
            com.lifecyclebot.engine.TradeHistoryStore.rollingWinRatePct(50)
        } catch (_: Throwable) { -1.0 }
        val predictive = rollingWr in 0.0..(targetWR * PREDICTIVE_THRESHOLD)
        // V5.9.1221 — rolling collapse guard. Operator screenshot at 1139
        // trades showed lifetime WR 24% masking roll50=4%. That is not
        // acceptable bootstrap noise; it means the CURRENT policy is broken.
        // Treat roll50 <= min(10%, target*0.35) as emergency probe mode.
        // It must NOT disable learning lanes; it only tells FDG/sub-traders
        // to shrink size and tighten exit protection.
        val rollingCollapse = rollingWr in 0.0..minOf(10.0, targetWR * 0.35)

        val lifetimeBand = when {
            currentWR < targetWR * DEEP_THRESHOLD     -> Band.AGGRESSIVE
            currentWR < targetWR * MODERATE_THRESHOLD -> Band.MODERATE
            currentWR < targetWR * FLUID_THRESHOLD    -> Band.FLUID
            // V5.9.1473 — at/above target: still lock winners, just lighter.
            else -> Band.PERFORMING
        }
        // Predictive escalation: when rolling-50 is bleeding, tighten by one band.
        // Collapse escalation: roll50 catastrophically low forces AGGRESSIVE
        // regardless of lifetime WR so the bot doesn't hide behind old wins.
        val band = when {
            rollingCollapse -> Band.AGGRESSIVE
            predictive -> escalate(lifetimeBand)
            else -> lifetimeBand
        }
        return State(band, currentWR, targetWR, total.toInt(), rollingWr, predictive, rollingCollapse)
    }

    private fun escalate(b: Band): Band = when (b) {
        Band.OFF -> Band.FLUID
        Band.PERFORMING -> Band.FLUID
        Band.FLUID -> Band.MODERATE
        Band.MODERATE -> Band.AGGRESSIVE
        Band.AGGRESSIVE -> Band.AGGRESSIVE
    }

    /** Base three-rung trigger pcts before learned lane shaping. */
    private fun rungsFor(band: Band): Triple<Double, Double, Double> = when (band) {
        Band.AGGRESSIVE -> Triple(50.0, 150.0, 1000.0)
        Band.MODERATE   -> Triple(50.0, 250.0, 1500.0)
        Band.FLUID      -> Triple(50.0, 500.0, 3000.0)
        Band.PERFORMING -> Triple(50.0, 1000.0, 10000.0)
        Band.OFF        -> Triple(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
    }

    /**
     * V5.0.3963 — expectancy/MFE-linked profit bands.
     * Uses StrategyTelemetry (same expectancy table shown in reports) so exit
     * levels respond to what each lane actually realizes. Floor prevents scrap
     * exits; runner lanes expand toward 1000%/10000% instead of being hardcoded
     * for every lane. Fail-open to base bands.
     */
    fun learnedExitRungs(lane: String, band: Band = stateNow().band): Triple<Double, Double, Double> {
        val base = rungsFor(band)
        return try {
            val key = try { TradeHistoryStore.normalizeTradeModeName(lane).ifBlank { lane.uppercase() } } catch (_: Throwable) { lane.uppercase() }
            val m = StrategyTelemetry.computeLiveTerminalLeaderboard().firstOrNull { it.strategy.equals(key, true) }
            if (m == null || m.trades < 5) return base
            val pf = m.pfExpectancyPp
            val avgWin = m.avgWinPct.coerceAtLeast(0.0)
            val runner = m.totalSolPnl > 0.0 && (avgWin >= 75.0 || pf >= 15.0 || m.meanPnlPct >= 25.0)
            val tune = try { LiveStrategyTuner.adjustment(key) } catch (_: Throwable) { LiveStrategyTuner.adjustment("STANDARD") }
            // V5.0.4023 — let profitable live lanes ride. The old runner math
            // could still take first profit near +50%; reports showed the bot made
            // more SOL by holding MOONSHOT/BLUECHIP winners longer. Tune raises
            // partial rungs using cached live-terminal StrategyTelemetry only.
            val firstBase = if (runner) maxOf(base.first, avgWin * 0.35, 80.0) else base.first
            val first = maxOf(MIN_PARTIAL_GAIN_PCT, firstBase * tune.partialTriggerMult).coerceIn(50.0, 400.0)
            val secondBase = if (runner) maxOf(base.second, avgWin * 2.00, 1500.0) else maxOf(base.second, avgWin * 0.75)
            val thirdBase = if (runner) maxOf(base.third, avgWin * 8.0, 15000.0) else maxOf(base.third, avgWin * 2.5)
            Triple(first, (secondBase * tune.partialTriggerMult).coerceIn(first + 50.0, 7500.0), (thirdBase * tune.partialTriggerMult).coerceIn(secondBase + 250.0, 75000.0))
        } catch (_: Throwable) { base }
    }

    /**
     * Per-rung sell fraction for the active band.
     *
     * V5.9.811 — operator audit (2026-05-17): reduced from 25/25/20% to
     * 15/15/12%. Reasoning:
     *   • Old fractions sold ~58% of original qty by +60% (since each rung
     *     sells a % of REMAINING, not ABSOLUTE qty: 1 − 0.75³ ≈ 0.58).
     *     That was tuned for a 29% WR bot that needed capital recovery
     *     FAST, but it choked runners before they revealed themselves.
     *   • Capital Recovery (dynamic 1.3x–4x clamp, sells 25–70%) and
     *     Profit Lock (dynamic 2.5x–10x clamp, sells 50% of remaining)
     *     ALREADY handle the "absolute size de-risking" job after R3 —
     *     and they're factor-adaptive (liq/mcap/vol/phase/quality/time).
     *   • FluidLearningAI.getDynamicFluidStop() handles the runner tail
     *     via peakGainPct-aware trailing stop.
     *   • WR-Recovery R1-R3 should only LOCK SMALL WIN TICKS into the
     *     ledger, not aggressively reduce position. After R3 at 15% each,
     *     ~61% of original qty rides forward into the Capital Recovery
     *     / Profit Lock / Fluid Trail stack which knows what to do.
     *
     * Net effect:
     *   • Each R1/R2/R3 still adds a WIN tick to lifetime WR (same
     *     mechanical boost as before — partial sells count via
     *     TradeHistoryStore.bumpLifetimeFor).
     *   • Runner upside preserved — 61% rides into Capital Recovery
     *     (vs 42% before), so the moonshot tail compounds on a larger
     *     remainder.
     *   • Capital recovery shifts from ~+60% to ~+200% (where the
     *     dynamic Capital Recovery threshold kicks in for most setups).
     *     Trade-off: choppers that top at +60% then fade give back more.
     *     Mitigated by R1 at +18% (15%) still locking some profit early
     *     and -15% hard floor catching dumpers.
     */
    private fun fractionFor(band: Band): Double = when (band) {
        // Meaningful first split, but preserve moonbag. 15-20% sold leaves
        // 80%+ riding into learned 1000%/10000% bands.
        Band.AGGRESSIVE -> 0.20
        Band.MODERATE   -> 0.18
        Band.FLUID      -> 0.15
        Band.PERFORMING -> 0.15
        Band.OFF        -> 1.0  // pass-through; caller multiplies by config fraction
    }

    /**
     * Returns the effective first-partial trigger pct.
     * Returns [normalTrigger] unchanged when no band is active.
     *
     * V5.9.797 — caps at MIN_PARTIAL_GAIN_PCT on the floor (operator: 'we are
     * targeting THE NUMBER or BETTER, not lower') and at normalTrigger on the
     * ceiling (recovery can only LOWER a trigger, never raise it — operator's
     * Settings / LLM tuner remain authoritative).
     */
    fun effectiveTrigger(normalTrigger: Double, gainPct: Double, partialLevel: Int, profitLockTriggered: Boolean, lane: String = "STANDARD"): Double {
        if (partialLevel != 0) return normalTrigger
        if (profitLockTriggered) return normalTrigger

        val state = stateNow()
        if (!state.active) return normalTrigger

        val (rung1, _, _) = learnedExitRungs(lane, state.band)
        val effective = rung1.coerceAtLeast(MIN_PARTIAL_GAIN_PCT)
        if (gainPct >= effective) {
            ErrorLogger.info(
                "WrRecovery",
                "📉 ${state.band.name}${if (state.predictive) "*PREDICTIVE" else ""} R1 FIRING: WR=${"%.1f".format(state.currentWr)}% / rolling50=${"%.1f".format(state.rollingWr)}% / target=${state.targetWr.toInt()}% → first=${effective.toInt()}% (gain=${gainPct.toInt()}%)"
            )
        }
        return effective
    }

    fun effectiveSecondTrigger(normalTrigger: Double, lane: String = "STANDARD"): Double {
        val s = stateNow()
        if (!s.active) return normalTrigger
        return learnedExitRungs(lane, s.band).second
    }
    fun effectiveThirdTrigger(normalTrigger: Double, lane: String = "STANDARD"): Double {
        val s = stateNow()
        if (!s.active) return normalTrigger
        return learnedExitRungs(lane, s.band).third
    }
    fun effectiveSellFraction(baseFraction: Double): Double {
        val s = stateNow()
        if (!s.active) return baseFraction
        return maxOf(baseFraction, fractionFor(s.band)).coerceAtMost(0.25)
    }

    // ─────────────────────────────────────────────────────────────────────
    // V5.9.801 — operator audit Fix D + Fix A/B support
    // Single source of truth for two derived quantities used by sub-trader
    // entry paths and the buy-side sizing helper:
    //
    //   entrySizeMultiplier()  → multiplier applied to position size before
    //                            paperBuy()/liveBuy() commits it. AGGRESSIVE
    //                            band gets 0.5×, MODERATE 0.75×, anything
    //                            else 1.0× (no scaling).
    //
    //   minScoreFloor()        → score floor that sub-traders OR in with
    //                            their own (FluidLearningAI) thresholds.
    //                            AGGRESSIVE → 45 (only top-tier setups),
    //                            MODERATE   → 30 (mid),
    //                            FLUID/OFF  →  0 (no extra floor).
    //
    // Centralising these here means item-A (sub-trader entry gating) and
    // item-D (entry-size dampening) can never drift between lanes.
    // ─────────────────────────────────────────────────────────────────────
    fun entrySizeMultiplier(): Double {
        val s = stateNow()
        return when (s.band) {
            Band.AGGRESSIVE -> 0.5
            Band.MODERATE   -> 0.75
            else            -> 1.0
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // V5.9.805 — operator audit Fix (β): Score-distribution auto-fit.
    //
    // Problem context: operator dump build-5.0.2745 showed V3 scores
    // squashed in the 0-36 band across the entire pump.fun candidate
    // universe (market regime). WR Recovery AGGRESSIVE floor was 45 →
    // FDG rejected 232/237 candidates on WR_RECOVERY_QUALITY_FLOOR →
    // the bot literally could not collect samples in a thin market.
    //
    // Resolution: maintain a 200-tick ring buffer of V3 scores; compute
    // the running median. In thin regimes (median<15) the floor drops by
    // ~25pts so the bot can learn; in normal regimes the floor is the
    // band's nominal value; in rich regimes (median>50) the floor adds
    // 10pts so the bot tightens to keep only top-decile entries when
    // scores rip. minimumScoreFloor() composes the band base + auto-fit
    // delta in a single call so all sub-traders pick up the change
    // automatically.
    //
    // recordV3Score() is called from FinalDecisionGate.evaluate() — the
    // single FDG entry that sees every candidate's V3 score AFTER the
    // V3 engine has actually scored it (not pre-scoring zeros).
    // ─────────────────────────────────────────────────────────────────────
    private const val V3_RING_SIZE = 200
    private val v3ScoreRing = IntArray(V3_RING_SIZE)
    private val v3RingIdx = java.util.concurrent.atomic.AtomicInteger(0)
    private val v3RingCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun recordV3Score(score: Int) {
        if (score < 0) return
        val clamped = score.coerceAtMost(200)
        val slot = (v3RingIdx.getAndIncrement() % V3_RING_SIZE).let { if (it < 0) it + V3_RING_SIZE else it }
        synchronized(v3ScoreRing) {
            v3ScoreRing[slot] = clamped
        }
        v3RingCount.incrementAndGet()
    }

    private fun v3ScoreMedian(): Int? {
        val n = v3RingCount.get().coerceAtMost(V3_RING_SIZE)
        if (n < 30) return null  // not enough samples to trust the distribution
        val snapshot = synchronized(v3ScoreRing) {
            IntArray(n) { v3ScoreRing[it] }
        }
        snapshot.sort()
        return snapshot[n / 2]
    }

    /** Snapshot for the UI / pipeline-health pill. */
    data class V3DistSnapshot(val samples: Int, val median: Int, val mode: String)
    fun v3DistSnapshot(): V3DistSnapshot {
        val n = v3RingCount.get().coerceAtMost(V3_RING_SIZE)
        val median = v3ScoreMedian() ?: -1
        val mode = when {
            n < 30      -> "WARMUP"
            median < 15 -> "THIN"
            median > 50 -> "RICH"
            else        -> "NORMAL"
        }
        return V3DistSnapshot(samples = n, median = median, mode = mode)
    }

    fun minScoreFloor(): Int {
        val s = stateNow()
        val base = when {
            s.rollingCollapse -> 60
            s.band == Band.AGGRESSIVE -> 45
            s.band == Band.MODERATE   -> 30
            else            -> 0
        }
        if (base == 0) return 0
        val median = v3ScoreMedian() ?: return base
        // Auto-fit delta:
        //   THIN   (median<15) → -25  (drop one tier so the bot can collect samples)
        //   THIN-  (median<25) → -12  (half-tier drop)
        //   NORMAL                 0
        //   RICH   (median>50) → +10  (tighten to keep only top setups)
        // V5.0.6710 — catastrophic rolling collapse is quality mode.
        // Do not relax the score floor merely because the current candidate
        // distribution is thin: that is exactly how a 0%-WR cohort keeps
        // admitting more weak entries. Rich regimes may still tighten further.
        val delta = if (s.rollingCollapse) {
            if (median > 50) +10 else 0
        } else when {
            median < 15 -> -25
            median < 25 -> -12
            median > 50 -> +10
            else        -> 0
        }
        return (base + delta).coerceAtLeast(0)
    }

    /** Human-readable status for logs / UI badges. */
    fun statusTag(): String {
        val s = stateNow()
        if (!s.active) return "WR_RECOVERY:off"
        val (r1, r2, r3) = rungsFor(s.band)
        val predicate = when {
            s.rollingCollapse -> "*COLLAPSE"
            s.predictive -> "*PREDICTIVE"
            else -> ""
        }
        // V5.9.799 — bootstrap label so it's clear the band is from
        // the under-50-trades training-wheels policy, not a lifetime-WR
        // deficit. Stops the operator wondering 'why is recovery firing
        // when I have 12 trades?'
        val bootstrap = if (s.totalSettled < 50) "*BOOTSTRAP" else ""
        return "WR_RECOVERY:${s.band.name}${predicate}${bootstrap}(wr=${s.currentWr.toInt()}%,roll=${s.rollingWr.toInt()}%,target=${s.targetWr.toInt()}%,rungs=${r1.toInt()}/${r2.toInt()}/${r3.toInt()}%)"
    }

    /** Compact tag for the Memes Live-Readiness "🚑 WR recovery @X%" badge. */
    fun shortBadge(): String {
        val s = stateNow()
        if (!s.active) return "off"
        val (r1, _, _) = rungsFor(s.band)
        val pre = when {
            s.rollingCollapse -> "🛑"
            s.predictive -> "⚡"
            else -> ""
        }
        // V5.9.799 — boot prefix so the operator sees 'boot M@25%' rather
        // than just 'M@25%' during the first 50 trades.
        val boot = if (s.totalSettled < 50) "boot " else ""
        return "${boot}${pre}${s.band.name.first()}@${r1.toInt()}%"
    }
}
