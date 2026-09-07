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

    private const val MIN_TRADES = 8
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

    fun sizeMultiplier(lane: String?): Double {
        if (lane.isNullOrBlank()) return 1.0
        return try {
            val key = lane.trim().uppercase()
            snapshot()[key] ?: 1.0
        } catch (_: Throwable) {
            1.0
        }
    }

    fun statusLine(): String = try {
        val map = snapshot()
        val env6679 = try { if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE" } catch (_: Throwable) { "LIVE" }
        if (map.isEmpty()) "LaneExpectancyDamper[$env6679]: no shaped lanes (all lanes ≥ ${BLEEDER_MEAN_PCT}% or < $MIN_TRADES trades)"
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
            return emptyMap()
        }
        val out = HashMap<String, Double>()
        for (m in board) {
            if (m.trades < MIN_TRADES) continue

            // Proven profitable asymmetric runners may be pressed, but only when
            // the same-mode terminal ledger is actually net positive.
            if (isRunnerLane(m.strategy) && m.totalSolPnl > 0.0 && m.winRatePct >= EARLY_WINNER_MIN_WR_PCT) {
                val earlyEdge = ((m.winRatePct - EARLY_WINNER_MIN_WR_PCT) / 45.0).coerceIn(0.0, 1.0)
                val solEdge = (m.totalSolPnl / 0.08).coerceIn(0.0, 1.0)
                val boost = (1.08 + earlyEdge * 0.14 + solEdge * 0.13).coerceIn(1.08, 1.35)
                out[m.strategy.trim().uppercase()] = maxOf(out[m.strategy.trim().uppercase()] ?: 1.0, boost)
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
                out[m.strategy.trim().uppercase()] = maxOf(out[m.strategy.trim().uppercase()] ?: 1.0, boost)
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
            out[m.strategy.trim().uppercase()] = mult
        }
        return out
    }
}
