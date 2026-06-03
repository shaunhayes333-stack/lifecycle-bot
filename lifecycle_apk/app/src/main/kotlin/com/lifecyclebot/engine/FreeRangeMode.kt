package com.lifecyclebot.engine

/**
 * V5.9.716 — GRADUATED AIR CONTROL (Wide-Open redesign)
 *
 * ─── PROBLEM WITH OLD WIDE-OPEN (0-3000) ────────────────────────────────────
 * Holding wide-open through 3000 trades let the bot accumulate 2000+ losses
 * at 21% WR before any quality sifting engaged. By that point:
 *   • FluidLearningAI.earnedCeiling = 0.50  (WR < 30%)
 *   • All lerp() thresholds tighten to their midpoint values
 *   • Less-selective trading at midpoint creates MORE losses
 *   • Death spiral: low WR → tighter gates → fewer trades → WR stays low
 *   • QualityLadder stays at tier 0 until 5000 trades → coaching is silent
 *
 * ─── NEW DOCTRINE ────────────────────────────────────────────────────────────
 * Phase 0  (  0– 500 trades): WIDE-OPEN. Bot sees everything. No filters.
 *             Pure exploration to establish the signal baseline.
 *
 * Phase 1  (500–1000 trades): SOFT SIFT. Graduated WR-sensitivity begins.
 *             Loss-streak brakes engage. Size scales with per-lane WR so
 *             bad lanes shrink but NEVER die (floor 0.50× size).
 *             Target WR ramp: 20% → 30%.  Below target → more selection.
 *             Above target → stay loose. Minimum trade floor always honoured.
 *
 * Phase 2  (1000–3000 trades): MID SIFT. Full loss-streak + re-entry
 *             cooldowns + volume floor rise. Per-lane quality scores gain
 *             weight. Target WR 30% → 45%.
 *
 * Phase 3  (3000–5000 trades): FULL SIFT. Rug filter, trust gates, tight
 *             thresholds. Target WR 45% → 65%.
 *
 * Phase 4  (5000+, WR < 50%): DEEP LEARNING. All guards max. WR still
 *             sub-target — system continues refining.
 *
 * Phase 5  (5000+, WR ≥ 50%): FULL AIR CONTROL. Tightest thresholds,
 *             maximum sizing discipline.
 *
 * ─── INVIOLABLE CONSTRAINTS ──────────────────────────────────────────────────
 * 1. No lane is ever completely stopped. Every lane keeps a minimum trade
 *    floor (laneMinTradeFloor()). The WR-sensitive size multiplier floors
 *    at 0.50× — it never goes below.
 * 2. Size multiplier for a lane can only scale DOWN relative to the base
 *    (never up beyond 1.0×). The base size is already set by SmartSizer.
 * 3. AntiChoke override: if AntiChoke is softening, immediately drop to
 *    level 0 so every guard relaxes — starvation prevention takes priority.
 * 4. Operator overrides (forceOn / forceOff) are still honoured.
 *
 * ─── GUARDIAN LEVEL MAPPING ──────────────────────────────────────────────────
 * Level 0 — pure wide-open, zero guards
 * Level 1 — MemeLossStreakGuard + loss-streak cooldowns
 * Level 2 — Level 1 + ReentryGuard cooldowns + volume floor rises
 * Level 3 — Level 2 + HardRugPreFilter in paper mode
 * Level 4 — Level 3 + StrategyTrustAI distrust-pause respected
 * Level 5 — Level 4 + strict live thresholds across every guard
 */
object FreeRangeMode {

    private const val TAG = "FreeRangeMode"

    // ── Phase boundaries ─────────────────────────────────────────────
    private const val PHASE1_START  = 500    // wide-open ends here
    private const val PHASE2_START  = 1000   // mid-sift begins
    private const val PHASE3_START  = 3000   // full-sift begins
    private const val PHASE4_START  = 5000   // deep-learning / full-air-ctrl
    private const val FULL_CTRL_WR  = 50.0   // WR needed to reach level 5

    // Minimum trade floor per lane: even at max tightness, always allow
    // at least this proportion of candidates through (prevents kill).
    // 0.50 = a lane under maximum quality pressure still fires on at
    // least 50% of its normal qualifying candidates.
    const val LANE_SIZE_FLOOR = 0.50

    // LLM tuner ramp
    private const val TUNER_RAMP_START = 50
    private const val TUNER_RAMP_END   = 1000  // V5.9.716: reach full tuner power at 1000 (was 3000)
                                                 // Earlier quality feedback = earlier WR repair

    // Emergency-graduate kept for backwards-compat callers
    private const val EMERGENCY_MIN_TRADES = 500
    private const val EMERGENCY_MAX_WR_PCT = 15.0

    // ── Operator overrides ───────────────────────────────────────────
    @Volatile private var operatorForceOff: Boolean = false
    @Volatile private var operatorForceOn:  Boolean = false

    fun forceOff()       { operatorForceOff = true;  operatorForceOn  = false }
    fun forceOn()        { operatorForceOn  = true;  operatorForceOff = false }
    fun clearOverride()  { operatorForceOff = false; operatorForceOn  = false }

    // ── Core guard level ─────────────────────────────────────────────
    /**
     * Returns 0–5 indicating how many guard layers are active.
     * 0 = wide-open, 5 = full air control. Fail-open: returns 0 on any error.
     */
    fun guardLevel(): Int {
        if (operatorForceOn)  return 0
        if (operatorForceOff) return 5
        return try {
            val snap   = TradeHistoryStore.getLifetimeStats()
            val trades = snap.totalSells
            val wr     = snap.winRate
            // V5.9.1333 — FROZEN-WR FIX. Previously AntiChokeManager.isSoftening()
            // short-circuited the WHOLE maturity ladder back to guardLevel 0
            // (wide-open: every entry gate bypassed, SKIP verdicts ignored, conf
            // floor ignored). AntiChoke softens whenever 24h throughput is under the
            // 500/day target — which is ~always in low-volume sessions — so the bot
            // got PINNED in pure-exploration forever and WR could never climb past
            // bootstrap regardless of how many trades accumulated. That is exactly
            // the "WR stuck, figures won't move" symptom.
            //
            // FIX: AntiChoke may only collapse the ladder to wide-open DURING the
            // genuine exploration phase (<500 lifetime trades). Past 500 the quality
            // ladder HOLDS even when throughput is soft. AntiChoke still does its real
            // job upstream (relaxing scanner/intake gates to keep the pipeline fed) —
            // it just no longer forces the bot to buy unfiltered noise once it has
            // enough samples to start filtering. Below 500 we're wide-open anyway, so
            // the softener is a no-op there. Net: volume protection preserved, but the
            // maturity curve can finally progress (Doctrine: WR must climb; throughput
            // before cleverness — but NOT throughput INSTEAD of ever learning).
            if (trades < PHASE1_START && AntiChokeManager.isSoftening()) return 0
            when {
                trades < PHASE1_START  -> 0  // pure exploration
                trades < PHASE2_START  -> 1  // soft: loss-streak brakes only
                trades < PHASE3_START  -> 2  // + re-entry cooldowns, vol floor
                trades < PHASE4_START  -> 3  // + rug filter in paper
                wr >= FULL_CTRL_WR     -> 5  // earned full air control
                else                   -> 4  // 5000+ still learning
            }
        } catch (_: Throwable) { 0 }
    }

    /**
     * True only when the bot is in pure exploration mode (< 500 trades).
     * All existing isWideOpen() callers get the correct semantics.
     */
    fun isWideOpen(): Boolean = guardLevel() == 0

    // ── Per-lane WR-sensitive size multiplier ────────────────────────
    /**
     * V5.9.716 — WR-sensitive lane size multiplier.
     *
     * Returns a value in [LANE_SIZE_FLOOR, 1.0] that callers should
     * multiply against the proposed entry size for a given lane.
     *
     * During Phase 0 (wide-open): always 1.0 — no reduction.
     * During Phase 1+: scales with how far the lane's rolling WR is
     *   below its phase target. If the lane is at or above target → 1.0.
     *   If the lane is at 0% WR → LANE_SIZE_FLOOR.
     *
     * laneWinRate: rolling WR for this specific lane (0-100).
     *   Pass -1.0 to opt out of WR-adjustment (new lane, no data yet).
     *   When no data exists, returns 1.0 during Phase 0-1, 0.80 during
     *   Phase 2+, ensuring new lanes stay active but earn their way up.
     */
    fun laneSizeMultiplier(laneWinRate: Double): Double {
        val level = guardLevel()
        if (level == 0) return 1.0   // wide-open: no size adjustment

        if (laneWinRate < 0.0) {
            // No WR data for this lane yet
            return if (level <= 1) 1.0 else 0.80
        }

        val targetWr = phaseTargetWr(
            try { TradeHistoryStore.getLifetimeStats().totalSells } catch (_: Throwable) { 0 }
        )
        if (laneWinRate >= targetWr) return 1.0   // lane is on target → no reduction

        // Scale: 0% WR → FLOOR, targetWr → 1.0
        val gap      = (targetWr - laneWinRate).coerceAtLeast(0.0)
        val maxGap   = targetWr.coerceAtLeast(1.0)
        val reduction = (gap / maxGap) * (1.0 - LANE_SIZE_FLOOR)
        return (1.0 - reduction).coerceIn(LANE_SIZE_FLOOR, 1.0)
    }

    // ── EXPLORATION-PHASE SIZE RAMP (V5.9.1334) ──────────────────────
    /**
     * THE BLACK-HOLE FIX.
     *
     * Operator insight: "wide-open early creates an unrecoverable black hole of
     * losses we cannot win our way back out of." Correct, and the cause was
     * structural: during Phase 0 (wide-open, <500 trades) the bot DELIBERATELY
     * buys unfiltered noise — yet laneSizeMultiplier() returned 1.0 in Phase 0,
     * so it bet those random trades at FULL SIZE. Biggest bets placed exactly
     * when the bot knows the LEAST. A mature 55-65% WR on normal-sized bets then
     * can't out-earn a pile of full-sized exploration losses → unrecoverable P&L
     * hole. (The learned expectancy memory self-heals via its 200-trade rolling
     * window, but spent balance does not come back.)
     *
     * FIX: bet SMALLEST when we know least, ramping up as we earn samples — basic
     * low-edge bankroll discipline (fractional-Kelly-flavoured). This is a SOFT
     * SHAPE (never a veto), preserves throughput (same number of trades, just
     * smaller), and does NOT touch the scanner/intake pool. It only caps how much
     * capital the learning phase can burn.
     *
     * Curve over lifetime sells (0 → PHASE1_START=500):
     *   trade   0  → EXPLORE_SIZE_FLOOR (0.20×)  — minimum conviction, minimum stake
     *   trade 250  → ~0.60×
     *   trade 500  → 1.00×  (exploration earned out; guards take over from here)
     * Past 500 → 1.0 (this scalar disengages; laneSizeMultiplier's WR-sift owns it).
     *
     * Fail-open: any error → 1.0 (never starve sizing).
     */
    const val EXPLORE_SIZE_FLOOR = 0.20
    fun explorationSizeMultiplier(): Double = try {
        if (operatorForceOn) 1.0 else {
            val trades = TradeHistoryStore.getLifetimeStats().totalSells
            if (trades >= PHASE1_START) 1.0
            else {
                val frac = (trades.toDouble() / PHASE1_START).coerceIn(0.0, 1.0)
                (EXPLORE_SIZE_FLOOR + (1.0 - EXPLORE_SIZE_FLOOR) * frac).coerceIn(EXPLORE_SIZE_FLOOR, 1.0)
            }
        }
    } catch (_: Throwable) { 1.0 }

    /**
     * Phase-specific WR target used by laneSizeMultiplier and QualityLadder.
     */
    fun phaseTargetWr(trades: Int): Double = when {
        trades < PHASE1_START  ->  0.0  // pure exploration — no WR target
        trades < PHASE2_START  -> lerp(20.0, 30.0, norm(trades, PHASE1_START, PHASE2_START))
        trades < PHASE3_START  -> lerp(30.0, 45.0, norm(trades, PHASE2_START, PHASE3_START))
        trades < PHASE4_START  -> lerp(45.0, 65.0, norm(trades, PHASE3_START, PHASE4_START))
        else                   -> 65.0
    }

    // ── Backwards-compat helpers ─────────────────────────────────────
    /**
     * adjustmentStrength: LLM/Sentience tuner ramp. 0.0 = no tuning,
     * 1.0 = full authority. V5.9.716: reaches 1.0 at 1000 trades (not 3000)
     * so quality feedback kicks in much earlier.
     */
    fun adjustmentStrength(): Double {
        return try {
            val trades = TradeHistoryStore.getLifetimeStats().totalSells
            when {
                trades < TUNER_RAMP_START -> 0.0
                trades >= TUNER_RAMP_END  -> 1.0
                else -> {
                    val span = (TUNER_RAMP_END - TUNER_RAMP_START).toDouble()
                    val progress = (trades - TUNER_RAMP_START).coerceAtLeast(0) / span
                    (0.05 + progress * 0.95).coerceIn(0.0, 1.0)
                }
            }
        } catch (_: Throwable) { 0.0 }
    }

    fun emergencyGraduated(): Boolean {
        if (operatorForceOn || operatorForceOff) return false
        return try { QualityLadder.tier() >= 1 } catch (_: Throwable) { false }
    }

    fun statusLine(): String {
        return try {
            val snap   = try { TradeHistoryStore.getLifetimeStats() } catch (_: Throwable) { null }
            val trades = snap?.totalSells ?: 0
            val wr     = snap?.winRate ?: 0.0
            val level  = guardLevel()
            val phase  = when {
                level == 0 -> "WIDE-OPEN"
                level == 1 -> "SOFT-SIFT"
                level == 2 -> "MID-SIFT"
                level == 3 -> "FULL-SIFT"
                level == 4 -> "DEEP-LEARN"
                else       -> "AIR-CTRL"
            }
            val icon = when (level) {
                0    -> "🔓"
                1, 2 -> "🟡"
                3, 4 -> "🟠"
                else -> "🔴"
            }
            val target = phaseTargetWr(trades)
            val ladder = try { QualityLadder.statusLine() } catch (_: Throwable) { "" }
            val base = "$icon $phase · T$trades · WR=${"%.1f".format(wr)}% (tgt ${"%.0f".format(target)}%)"
            if (ladder.isNotBlank()) "$base · $ladder" else base
        } catch (_: Throwable) {
            "🔓 WIDE-OPEN · (history unavailable)"
        }
    }

    // ── Private helpers ───────────────────────────────────────────────
    private fun norm(v: Int, lo: Int, hi: Int): Double =
        ((v - lo).toDouble() / (hi - lo).toDouble()).coerceIn(0.0, 1.0)

    private fun lerp(a: Double, b: Double, t: Double): Double =
        a + (b - a) * t.coerceIn(0.0, 1.0)
}
