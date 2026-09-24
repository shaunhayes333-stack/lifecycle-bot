package com.lifecyclebot.engine

/**
 * V5.9.1273 — LaneExpectancyDamper
 *
 * DOCTRINE-CLEAN bleeder control. Reads the mode-matched clean per-lane expectancy
 * from StrategyTelemetry and returns a SIZE multiplier only — it NEVER vetoes a
 * candidate. PAPER reads clean PAPER terminal truth; LIVE reads clean LIVE terminal
 * truth. The two environments are never blended, so paper evidence cannot authorize
 * live sizing while paper mode can still learn and self-adjust from its own closes.
 *
 * WALLET GROWTH ALLOCATOR: profitable clean same-mode lanes may be pressed while
 * proven bleeders are reduced. This is the V5.0.4580 contract and remains sizing
 * only; it never disables a lane and never lets PAPER evidence authorize LIVE.
 *
 * Per operator doctrine #86 ("help don't hinder") and the PERFORMANCE_DOCTRINE
 * soft-shape rule, only the original veto whitelist may kill a candidate; this
 * organ may only shrink size on a PROVEN, statistically-meaningful bleeder.
 *
 * Why this exists:
 *   The 3240 snapshot showed two pure capital incinerators that survive purely
 *   because nothing sizes them down:
 *     TREASURY     n=32  WR 6.3%  meanPnl -22.3%  PnL -2.39 SOL
 *     MANIPULATED  n=21  WR 0.0%  meanPnl -12.9%  PnL -0.05 SOL
 *   Meanwhile their +EV sub-contexts (e.g. MANIPULATED|DUMP) still print — so we
 *   shrink the LANE's average exposure rather than blocking it, letting the
 *   learning loop keep sampling and the good sub-slices survive.
 *
 * V5.0.6679 — MODE_MATCHED_EXPECTANCY_AUTHORITY.
 * A V5.0.3974 guard deliberately made this object LIVE-only to stop PAPER history
 * authorizing LIVE sizing. That contract remained correct, but after the later
 * clean-paper authority was introduced it became patch rot: in PAPER mode this
 * object saw zero LIVE closes, so severe paper bleeders printed as "no bleeders"
 * and the capital allocator never reacted. The correct boundary is same-mode clean
 * terminal truth, not permanently-live truth.
 *
 * SELF-HEALING: the multiplier is recomputed from current same-mode clean telemetry.
 * The moment a lane's expectancy recovers, the haircut releases automatically.
 * No persisted state, no manual re-enable.
 */
object LaneExpectancyDamper {

    // V5.0.7277 — one evidence bar for every size-moving learner; see
    // EvidenceMaturity7277. Was 8.
    private const val MIN_TRADES = com.lifecyclebot.engine.truth.EvidenceMaturity7277.LANE_OPINION_CLOSES
    private const val WINNER_MIN_TRADES = 8
    private const val EARLY_WINNER_MIN_TRADES = 2
    private const val EARLY_WINNER_MIN_WR_PCT = 30.0

    private const val BLEEDER_MEAN_PCT = -12.0
    private const val MIN_MULT = 0.18

    private const val CATASTROPHIC_MEAN_PCT = -20.0
    private const val CATASTROPHIC_WR_PCT   = 8.0
    private const val CATASTROPHIC_MIN_TRADES = 20
    private const val CATASTROPHIC_MIN_MULT = 0.08

    private const val MODERATE_CATASTROPHIC_MEAN_PCT   = -8.0
    private const val MODERATE_CATASTROPHIC_WR_PCT     = 30.0
    private const val MODERATE_CATASTROPHIC_MIN_TRADES = 25
    private const val MODERATE_CATASTROPHIC_MIN_MULT   = 0.15

    private const val HEALTHY_MEAN_PCT = 5.0
    private const val HEALTHY_WR_PCT = 45.0
    private const val HEALTHY_WR_MIN_TRADES = 15
    private const val FLOOR_MEAN_PCT = -30.0

    private const val PF_START_MULT = 0.65
    private const val PF_FLOOR_PP = 8.0

    private const val WINNER_START_MULT = 1.12
    private const val WINNER_MAX_MULT = 1.45
    private const val RUNNER_MEAN_PCT = 20.0
    private const val WR_RUNNER_MIN_TRADES = 30
    private const val WR_RUNNER_MIN_PCT = 30.0

    private val RUNNER_LANE_KEYS = arrayOf(
        "MOONSHOT", "SHITCOIN", "MEME", "EXPRESS",
        "MANIPULATED", "MANIP", "PRESALE", "PROJECT_SNIPER", "DIP_HUNTER",
    )

    private fun isRunnerLane(strategy: String?): Boolean {
        val s = strategy?.trim()?.uppercase() ?: return false
        for (k in RUNNER_LANE_KEYS) if (s.contains(k)) return true
        return false
    }

    private const val CACHE_MS = 5_000L
    @Volatile private var cacheAtMs = 0L
    @Volatile private var cached: Map<String, Double> = emptyMap()
    @Volatile private var cachedMode6679: String = ""

    /**
     * V5.0.7265 — the same-mode clean close count behind each multiplier,
     * captured from the same leaderboard read that produced it.
     *
     * V5.0.6715 made the multiplier non-neutral from trade ONE ("evidence is
     * continuous from trade one"), which is right for a size nudge. But
     * Executor.costExceedsEdge7162 reads "multiplier != 1.0" as "this lane has
     * enough closes to hold an opinion" and then treats a zero forecast edge
     * as a measurement — a refusal. Operator 5.0.7263, 27 minutes, 17 closes:
     *
     *   LaneExpectancyDamper[PAPER]: CRYPTO_ALT×0.57 · CORE×0.67 · QUALITY×0.70
     *                                · CYCLIC×0.87 · MOONSHOT×0.97 · PROJECT_SNIPER×0.98
     *   Tactic Switcher: CYCLIC n=1, MOONSHOT n=1..2, CORE n=2, PROJECT_SNIPER n=1
     *   COST_EXCEEDS_EDGE_REFUSED_7162 = 1971      EXEC = 57
     *
     * One close made every lane "evidenced", and a cold scorer (9–32) gives a
     * zero score prior, so 7162 refused nearly every non-dust entry in the
     * session. A lane that cannot enter cannot produce the closes that would
     * lift its own multiplier: the same cold-start deadlock as 7259/7262, one
     * gate further down. This exposes the count so 7162 can apply the bar its
     * own comment states — MIN_TRADES closes — instead of inferring it from a
     * multiplier that no longer implies it.
     */
    const val MATURE_EVIDENCE_CLOSES_7265 = MIN_TRADES
    @Volatile private var cachedCloses7265: Map<String, Int> = emptyMap()

    /** Same-mode clean terminal closes for [lane]; 0 when unknown. */
    fun sameModeCloses7265(lane: String?): Int {
        if (lane.isNullOrBlank()) return 0
        return try {
            snapshot()
            cachedCloses7265[lane.trim().uppercase()] ?: 0
        } catch (_: Throwable) { 0 }
    }

    fun sizeMultiplier(lane: String?): Double {
        if (lane.isNullOrBlank()) return 1.0
        return try {
            val key = lane.trim().uppercase()
            val base = snapshot()[key] ?: 1.0
            // V5.0.6724 §COHORT_LOSER_ADVISORY_CONSUMER — chronic-losing
            // cohorts surfaced by CausalFeedbackAuthority6715 (>=8 closes at
            // <20% winrate on a band scope) impose a multiplicative floor
            // overlay. The overlay never inflates the multiplier (it is
            // min()'d with the base) and never reads any threshold local to
            // this file — it purely consumes an already-computed advisory.
            // Non-meme lanes and lanes without a chronic band return null
            // from the authority so `base` is returned unchanged.
            val mode6724 = try { if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE" } catch (_: Throwable) { "LIVE" }
            val advisory = try {
                com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715.cohortLoserAdvisoryForLane(mode6724, key)
            } catch (_: Throwable) { null }
            if (advisory != null) {
                try {
                    PipelineHealthCollector.labelInc("LANE_DAMPER_COHORT_ADVISORY_APPLIED_6724")
                } catch (_: Throwable) {}
                minOf(base, advisory.sizeMultiplier)
            } else base
        } catch (_: Throwable) {
            1.0
        }
    }

    /**
     * V5.0.6838 §LEARNED_EXPECTANCY_MUST_GATE_ADMISSION — extra confidence points a
     * lane must clear to be admitted, derived entirely from this damper's OWN
     * already-computed multiplier. No new thresholds, no new data source.
     *
     * Operator diagnostic on 5.0.6835 showed the defect this closes: the canonical
     * entry authority reported gates=3506 allows=3506 denies=0 while, at the same
     * moment, this damper had concluded EXPRESS x0.18 and LosingPatternMemory had
     * EXPRESS|S41-60 at losses=21 wins=1 meanPnl=-67%. EXPRESS was simultaneously
     * the most heavily damped lane and one of the most heavily executed (31 execs,
     * 3.8% WR). The learning was correct and simply had no way to refuse a trade —
     * it could only make the same bad bet smaller.
     *
     * Shrinking alone is actively harmful: it drives the ticket under the executable
     * minimum, so the whole candidate/FDG/mark/sizing pipeline is spent before the
     * order is rejected (BELOW_MIN_NOTIONAL=124, SHITCOIN buyIntent=39 ->
     * sizedExecutable=2). Raising the bar instead rejects the cohort early.
     *
     * Deliberately a score floor rather than a refusal. A hard refusal removes the
     * lane's ability to gather non-probe evidence, so its win rate can never recover
     * and the damper can never lift — the failure mode already documented in
     * BleederLaneProbation6747. A floor still admits genuinely strong setups, so the
     * lane keeps earning the evidence that un-damps it.
     *
     * Multiplier tiers map straight onto the constants above:
     *   <= CATASTROPHIC_MIN_MULT (0.08) -> +25  (>=20 closes, <=8% WR, <=-20% mean)
     *   <= MIN_MULT (0.18)              -> +15  (bleeder: <=-12% mean over >=8 closes)
     *   <  0.36                         -> +8   (moderate shaping)
     *   otherwise                       ->  0   (healthy / unshaped lanes unchanged)
     */
    fun admissionScoreFloorDelta(lane: String?): Double {
        if (lane.isNullOrBlank()) return 0.0
        val m = try { sizeMultiplier(lane) } catch (_: Throwable) { 1.0 }
        if (!m.isFinite() || m >= 0.36) return 0.0
        return when {
            m <= CATASTROPHIC_MIN_MULT + 0.01 -> 25.0
            m <= MIN_MULT + 0.01              -> 15.0
            else                              -> 8.0
        }
    }

    fun statusLine(): String = try {
        val map = snapshot()
        val env6679 = try { if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE" } catch (_: Throwable) { "LIVE" }
        if (map.isEmpty()) "LaneExpectancyDamper[$env6679]: no shaped lanes (no same-mode terminal edge requiring a soft shape)"
        else "LaneExpectancyDamper[$env6679]: " + map.entries.sortedBy { it.value }
            .joinToString(" · ") { "${it.key}×${"%.2f".format(it.value)}" }
    } catch (_: Throwable) {
        "LaneExpectancyDamper: unavailable"
    }

    private fun snapshot(): Map<String, Double> {
        val now = System.currentTimeMillis()
        val mode6679 = try { if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE" } catch (_: Throwable) { "LIVE" }
        val c = cached
        // Cache EMPTY snapshots too. In a cold/live-zero-terminal run the old
        // `c.isNotEmpty()` condition caused every hot sizing query to rebuild the
        // same empty board. Mode is part of the cache key so a PAPER→LIVE switch
        // can never carry a paper multiplier across the boundary even for 5s.
        if (now - cacheAtMs < CACHE_MS && cachedMode6679 == mode6679) return c
        val fresh = compute(paperRuntime6679 = mode6679 == "PAPER")
        cached = fresh
        cachedMode6679 = mode6679
        cacheAtMs = now
        return fresh
    }

    private fun compute(paperRuntime6679: Boolean): Map<String, Double> {
        val board = try {
            if (paperRuntime6679) StrategyTelemetry.computeCleanPaperTerminalLeaderboard()
            else StrategyTelemetry.computeCleanLiveTerminalLeaderboard()
        } catch (_: Throwable) {
            cachedCloses7265 = emptyMap()
            return emptyMap()
        }
        // V5.0.7265 — captured from the same read as the multipliers below, so a
        // consumer comparing the two can never see a count from one board and a
        // multiplier from another.
        cachedCloses7265 = try {
            board.associate { it.strategy.trim().uppercase() to it.trades }
        } catch (_: Throwable) { emptyMap() }
        val out = HashMap<String, Double>()
        for (m in board) {
            if (m.trades < 1) continue
            // V5.0.6715 — evidence is continuous from trade one. One outcome may
            // nudge size, never dominate it; confidence grows smoothly instead of
            // being exactly zero until the old n=8 cliff.
            // V5.0.7277 — the curve is 6715's; k moves from 3 to the shared
            // LANE_OPINION_CLOSES so one close nudges, thirty closes opine.
            val evidence6715 = com.lifecyclebot.engine.truth.EvidenceMaturity7277.weight(m.trades)
            fun blend6715(raw: Double): Double = (1.0 + (raw - 1.0) * evidence6715).coerceIn(0.05, 1.60)

            // Proven profitable asymmetric runners may be pressed, but only when
            // the same-mode terminal ledger is actually net positive.
            if (isRunnerLane(m.strategy) && m.totalSolPnl > 0.0 && m.winRatePct >= EARLY_WINNER_MIN_WR_PCT) {
                val earlyEdge = ((m.winRatePct - EARLY_WINNER_MIN_WR_PCT) / 45.0).coerceIn(0.0, 1.0)
                val solEdge = (m.totalSolPnl / 0.08).coerceIn(0.0, 1.0)
                val boost = (1.08 + earlyEdge * 0.14 + solEdge * 0.13).coerceIn(1.08, 1.35)
                out[m.strategy.trim().uppercase()] = maxOf(out[m.strategy.trim().uppercase()] ?: 1.0, blend6715(boost))
                continue
            }

            if (m.trades >= MIN_TRADES && m.meanPnlPct >= RUNNER_MEAN_PCT) {
                out[m.strategy.trim().uppercase()] = 1.0
                continue
            }

            if (m.trades >= WR_RUNNER_MIN_TRADES && m.winRatePct >= WR_RUNNER_MIN_PCT && m.totalSolPnl >= 0.0) {
                out[m.strategy.trim().uppercase()] = 1.0
                continue
            }

            val earlyWinner = m.trades >= EARLY_WINNER_MIN_TRADES &&
                m.totalSolPnl > 0.0 && m.winRatePct >= EARLY_WINNER_MIN_WR_PCT
            val winner = m.trades >= WINNER_MIN_TRADES &&
                m.totalSolPnl > 0.0 && m.winRatePct >= 50.0 && m.pfExpectancyPp > 0.0
            if (earlyWinner || winner) {
                val edge = (m.pfExpectancyPp / 30.0).coerceIn(0.0, 1.0)
                val wrBase = if (winner) 50.0 else EARLY_WINNER_MIN_WR_PCT
                val wrEdge = ((m.winRatePct - wrBase) / 35.0).coerceIn(0.0, 1.0)
                val solEdge = (m.totalSolPnl / if (winner) 1.0 else 0.08).coerceIn(0.0, 1.0)
                val base = if (winner) WINNER_START_MULT else 1.08
                val cap = if (winner) WINNER_MAX_MULT else 1.28
                val boost = (base + (edge * 0.18) + (wrEdge * 0.10) + (solEdge * 0.10)).coerceIn(1.0, cap)
                out[m.strategy.trim().uppercase()] = maxOf(out[m.strategy.trim().uppercase()] ?: 1.0, blend6715(boost))
                continue
            }

            val meanBleeder = m.meanPnlPct < BLEEDER_MEAN_PCT
            val healthyMean = m.meanPnlPct >= HEALTHY_MEAN_PCT
            val healthyWr = m.trades >= HEALTHY_WR_MIN_TRADES && m.winRatePct >= HEALTHY_WR_PCT
            val healthyLane = healthyMean || healthyWr
            val pfEdge = m.pfExpectancyPp
            val pfBleeder = !healthyLane && m.totalSolPnl < 0.0 && pfEdge <= 0.0
            if (!meanBleeder && !pfBleeder) continue

            val moderateCatastrophic = m.trades >= MODERATE_CATASTROPHIC_MIN_TRADES &&
                m.meanPnlPct <= MODERATE_CATASTROPHIC_MEAN_PCT &&
                m.winRatePct <= MODERATE_CATASTROPHIC_WR_PCT &&
                m.totalSolPnl < 0.0
            val catastrophic = m.trades >= CATASTROPHIC_MIN_TRADES &&
                m.meanPnlPct <= CATASTROPHIC_MEAN_PCT &&
                m.winRatePct <= CATASTROPHIC_WR_PCT
            val floorMult = when {
                catastrophic         -> CATASTROPHIC_MIN_MULT
                moderateCatastrophic -> MODERATE_CATASTROPHIC_MIN_MULT
                else                 -> MIN_MULT
            }

            val mult: Double = if (meanBleeder) {
                val span = (BLEEDER_MEAN_PCT - FLOOR_MEAN_PCT).coerceAtLeast(1.0)
                val depth = (BLEEDER_MEAN_PCT - m.meanPnlPct).coerceIn(0.0, span)
                val frac = depth / span
                (1.0 - frac * (1.0 - floorMult)).coerceIn(floorMult, 1.0)
            } else {
                val edgeDepth = (-pfEdge).coerceIn(0.0, PF_FLOOR_PP)
                val frac = edgeDepth / PF_FLOOR_PP
                (PF_START_MULT - frac * (PF_START_MULT - MIN_MULT)).coerceIn(MIN_MULT, 1.0)
            }
            out[m.strategy.trim().uppercase()] = blend6715(mult)
        }
        return out
    }
}
