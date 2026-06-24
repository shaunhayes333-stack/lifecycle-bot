package com.lifecyclebot.engine

/**
 * V5.9.1273 — LaneExpectancyDamper
 *
 * DOCTRINE-CLEAN bleeder control. Reads the LIVE per-lane expectancy from
 * StrategyTelemetry (the same data shown in the pipeline "Strategy expectancy"
 * block) and returns a SIZE multiplier only — it NEVER vetoes a candidate.
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
 *   learning loop (fixed in 1272) keep sampling and the good sub-slices survive.
 *
 * SELF-HEALING: the multiplier is recomputed from live telemetry every call. The
 * moment a lane's mean expectancy climbs back above the floor (because 1272's
 * clean labels let the scorer actually learn), the haircut releases automatically.
 * No persisted state, no manual re-enable.
 */
object LaneExpectancyDamper {

    // Only act on lanes with enough closed trades to be real signal, not noise.
    private const val MIN_TRADES = 8
    private const val WINNER_MIN_TRADES = 8

    // A lane is a "bleeder" once its mean PnL% is this negative (below the -15%
    // hard-floor magnitude is deep red; -12% is a confirmed structural loss).
    private const val BLEEDER_MEAN_PCT = -12.0

    // Damper never sizes below this for a NORMAL bleeder (keep a probe alive so
    // the lane can recover and the learning loop keeps getting samples —
    // throughput-before-cleverness).
    private const val MIN_MULT = 0.18

    // V5.9.1298 — CATASTROPHIC tier. A lane that is BOTH deep-negative EV AND
    // near-zero WR with a solid sample size is not "a bit weak", it's a confirmed
    // capital incinerator (3264 snapshot: TREASURY n=83 WR 3.6% EV -23.5% PnL
    // -1.01 SOL; Stocks n=55 WR 9.1% EV -12.7% -0.53 SOL). The 0.50 floor still
    // lets these print at half size and keep bleeding. For PROVEN catastrophes we
    // allow a deeper haircut floor — still a PROBE (never 0, never a veto), still
    // self-healing the instant EV/WR recover. Doctrine: soft-shape > veto; this
    // only shrinks size on statistically-overwhelming evidence.
    private const val CATASTROPHIC_MEAN_PCT = -20.0   // mean PnL% this deep …
    private const val CATASTROPHIC_WR_PCT   = 8.0     // … AND WR below this …
    private const val CATASTROPHIC_MIN_TRADES = 20    // … AND this many closes.
    private const val CATASTROPHIC_MIN_MULT = 0.08    // deeper probe floor

    // Worst-case mean PnL% that maps to MIN_MULT. Between BLEEDER_MEAN_PCT and
    // this, the haircut scales linearly.
    private const val FLOOR_MEAN_PCT = -30.0

    // V5.9.1489 — pf-edge bleeder tunables (net-negative-SOL lanes that the
    // mean-based check misses). A lane losing money with non-positive per-trade
    // edge starts at PF_START_MULT and scales toward MIN_MULT as the edge
    // (pp/trade) gets more negative, reaching MIN_MULT at -PF_FLOOR_PP.
    private const val PF_START_MULT = 0.65   // capital allocator first touch — still a probe
    private const val PF_FLOOR_PP = 8.0      // edge this negative ⇒ full haircut

    private const val WINNER_START_MULT = 1.12
    private const val WINNER_MAX_MULT = 1.45

    // V5.0.4082 — RUNNER threshold for asymmetric-strategy exemption.
    // Any lane with mean-PnL/trade at or above this value is treated as an
    // asymmetric runner and never damped, regardless of total SOL pnl or
    // WR — the variance is the cost of the upside.
    private const val RUNNER_MEAN_PCT = 20.0

    // V5.0.4085/4086 — WR-based RUNNER exemption gate. ops snapshot @ 5.0.4085
    // showed MOONSHOT n=176 WR=35%(rounded) still getting damped to ×0.18 — my
    // 4085 threshold of 35 missed because tuner saw ~34.x% which rounds to 35
    // for display only. Lower to 30 to keep the asymmetric runner exempt with
    // headroom; the operator mandate is "meme trader must never choke once
    // learnt".
    private const val WR_RUNNER_MIN_TRADES = 30
    private const val WR_RUNNER_MIN_PCT = 30.0

    // V5.0.4086 — HARD RUNNER-LANE EXEMPTION (operator P0: stop the choke).
    // These lanes are asymmetric by design — frequent small losses paid by
    // rare huge winners — and have their own per-lane tuners (LiveStrategyTuner
    // + LaneExitTuner) that already control risk. The global LaneExpectancyDamper
    // was originally built for BLUECHIP/STANDARD-style mean-stable bleeders and
    // is structurally wrong for runner profiles. Skip these entirely so the
    // damper cannot stack-damp the lanes the operator depends on for upside.
    private val RUNNER_LANE_KEYS = arrayOf(
        "MOONSHOT", "SHITCOIN", "MEME", "EXPRESS",
        "MANIPULATED", "MANIP", "PRESALE", "PROJECT_SNIPER", "DIP_HUNTER",
    )

    private fun isRunnerLane(strategy: String?): Boolean {
        val s = strategy?.trim()?.uppercase() ?: return false
        for (k in RUNNER_LANE_KEYS) if (s.contains(k)) return true
        return false
    }

    // Cheap cache so we don't recompute the leaderboard on every single entry in
    // a hot scan burst. Refresh window keeps it live without thrashing.
    private const val CACHE_MS = 5_000L
    @Volatile private var cacheAtMs = 0L
    @Volatile private var cached: Map<String, Double> = emptyMap()

    /**
     * Returns a capital-allocation multiplier for the given lane.
     * <1.0 = shrink proven bleeders to probes; >1.0 = press proven winners.
     * 1.0 = no change (unknown or too few samples).
     * Fail-open: any error → 1.0.
     */
    fun sizeMultiplier(lane: String?): Double {
        if (lane.isNullOrBlank()) return 1.0
        return try {
            val key = lane.trim().uppercase()
            val map = snapshot()
            map[key] ?: 1.0
        } catch (_: Throwable) {
            1.0
        }
    }

    /** Human-readable line for the pipeline dump (operator visibility). */
    fun statusLine(): String = try {
        val map = snapshot()
        if (map.isEmpty()) "LaneExpectancyDamper: no bleeders (all lanes ≥ ${BLEEDER_MEAN_PCT}% or < $MIN_TRADES trades)"
        else "LaneExpectancyDamper: " + map.entries.sortedBy { it.value }
            .joinToString(" · ") { "${it.key}×${"%.2f".format(it.value)}" }
    } catch (_: Throwable) {
        "LaneExpectancyDamper: unavailable"
    }

    private fun snapshot(): Map<String, Double> {
        val now = System.currentTimeMillis()
        val c = cached
        if (now - cacheAtMs < CACHE_MS && c.isNotEmpty()) return c
        val fresh = compute()
        cached = fresh
        cacheAtMs = now
        return fresh
    }

    private fun compute(): Map<String, Double> {
        val board = try {
            StrategyTelemetry.computeLiveTerminalLeaderboard()
        } catch (_: Throwable) {
            return emptyMap()
        }
        val out = HashMap<String, Double>()
        for (m in board) {
            if (m.trades < MIN_TRADES) continue

            // V5.0.4124 — DATA-DRIVEN RUNNER EXEMPTION. The blanket isRunnerLane
            // exemption was letting MOONSHOT (n=166, WR=22%, SOL=-0.69) bypass the
            // bleeder math entirely, forcing laneEvMult=1.0. This cascaded to
            // laneSizeCap=1.0 (Executor line 7701 checks laneEvMult>=1.0) and
            // regimeMult=1.0, meaning THREE sizing slots did nothing for a
            // catastrophically bleeding lane. Gate the exemption: only exempt
            // runner lanes that are actually profitable (net SOL > 0) or have
            // decent hit rate (WR >= 35%). Bleeding runners get the damper.
            if (isRunnerLane(m.strategy) && (m.totalSolPnl > 0.0 || m.winRatePct >= 35.0)) continue

            // V5.0.3956 — WALLET GROWTH ALLOCATOR.
            // Before this, live execution bypassed this organ and, even when enabled,
            // it could only shrink to ~half-size. That is incompatible with the
            // operator's 2–5x/day wallet-growth target: negative-SOL lanes must become
            // cheap learning probes, while positive PF/WR lanes get more capital.
            // V5.0.4082 — ASYMMETRIC-RUNNER EXEMPTION (operator P0: "we win
            // when we hold"). A lane with high mean-PnL but low WR is the
            // exact signature of meme-runner strategy — frequent small losses
            // paid for by a few asymmetric 50×+ winners. The pre-V5.0.4082
            // damper was EV-blind: it saw MOONSHOT (EV=+80%/trade, WR=45%,
            // net SOL barely positive) and damped to ×0.18, strangling the
            // exact lane that pays. The runner doctrine: any lane with
            // mean-PnL >= +20% over a meaningful sample is NEVER damped.
            // Variance is the price of the asymmetric upside, not a defect.
            if (m.trades >= MIN_TRADES && m.meanPnlPct >= RUNNER_MEAN_PCT) {
                out[m.strategy.trim().uppercase()] = 1.0
                continue
            }

            // V5.0.4085 — WR-BASED RUNNER EXEMPTION (operator P0: MOONSHOT
            // n=141 WR=36% had gross EV +80%/trade but realized mean ≈ flat
            // due to TP cuts + slippage. The mean-only gate above never
            // fires; switch to WR + sample-count which actually reflects
            // the strategy's profitability profile. Exempt entirely.
            if (m.trades >= WR_RUNNER_MIN_TRADES && m.winRatePct >= WR_RUNNER_MIN_PCT) {
                out[m.strategy.trim().uppercase()] = 1.0
                continue
            }

            val winner = m.trades >= WINNER_MIN_TRADES &&
                m.totalSolPnl > 0.0 && m.winRatePct >= 50.0 && m.pfExpectancyPp > 0.0
            if (winner) {
                val edge = (m.pfExpectancyPp / 30.0).coerceIn(0.0, 1.0)
                val wrEdge = ((m.winRatePct - 50.0) / 35.0).coerceIn(0.0, 1.0)
                val solEdge = (m.totalSolPnl / 1.0).coerceIn(0.0, 1.0)
                val boost = (WINNER_START_MULT + (edge * 0.18) + (wrEdge * 0.10) + (solEdge * 0.05)).coerceIn(1.0, WINNER_MAX_MULT)
                out[m.strategy.trim().uppercase()] = maxOf(out[m.strategy.trim().uppercase()] ?: 1.0, boost)
                continue
            }

            // V5.9.1489 — TWO-SIGNAL BLEEDER DETECTION (source fix for the
            // skew-masked bleeder). The old check used meanPnlPct alone, but a
            // lane with a fat take-profit tail (e.g. MANIPULATED μ=+86% yet
            // net-negative SOL, WR 24%) reads as "not a bleeder" and never gets
            // sized down — even though it loses money in expectation. We now also
            // catch lanes that are NET-NEGATIVE in real SOL AND fail the doctrine
            // profit-factor edge (avg_win*WR ≤ avg_loss*(1-WR)). Either signal
            // qualifies a lane as a bleeder; both stay fully self-healing (recompute
            // every call) and size-only (never a veto, per doctrine #86).
            val meanBleeder = m.meanPnlPct < BLEEDER_MEAN_PCT
            // pf-edge bleeder: real net loss over a meaningful sample AND the
            // per-trade expectancy edge is non-positive. The net-SOL gate prevents
            // flagging a high-variance lane that is actually net-positive.
            val pfEdge = m.pfExpectancyPp
            val pfBleeder = m.totalSolPnl < 0.0 && pfEdge <= 0.0
            if (!meanBleeder && !pfBleeder) continue

            // Is this a PROVEN catastrophe (deep -EV + near-zero WR + big sample)?
            val catastrophic = m.trades >= CATASTROPHIC_MIN_TRADES &&
                m.meanPnlPct <= CATASTROPHIC_MEAN_PCT &&
                m.winRatePct <= CATASTROPHIC_WR_PCT
            // Floor depends on tier: catastrophic lanes may be cut deeper (still a probe).
            val floorMult = if (catastrophic) CATASTROPHIC_MIN_MULT else MIN_MULT

            // Haircut depth. For a mean-bleeder use the existing linear mean map.
            // For a pf-edge-only bleeder (positive/near-zero mean but losing money),
            // scale by how negative the pf edge is so a marginal lane is barely
            // trimmed while a deep -edge lane is trimmed toward the floor.
            val mult: Double = if (meanBleeder) {
                val span = (BLEEDER_MEAN_PCT - FLOOR_MEAN_PCT).coerceAtLeast(1.0)
                val depth = (BLEEDER_MEAN_PCT - m.meanPnlPct).coerceIn(0.0, span)
                val frac = depth / span
                (1.0 - frac * (1.0 - floorMult)).coerceIn(floorMult, 1.0)
            } else {
                // pf-edge bleeder: map edge 0 → 0.85 (gentle), edge ≤ -PF_FLOOR_PP → MIN_MULT.
                val edgeDepth = (-pfEdge).coerceIn(0.0, PF_FLOOR_PP)
                val frac = edgeDepth / PF_FLOOR_PP
                (PF_START_MULT - frac * (PF_START_MULT - MIN_MULT)).coerceIn(MIN_MULT, 1.0)
            }
            out[m.strategy.trim().uppercase()] = mult
        }
        return out
    }
}
