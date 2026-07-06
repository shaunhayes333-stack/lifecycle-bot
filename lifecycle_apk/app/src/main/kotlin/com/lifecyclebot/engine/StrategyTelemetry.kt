package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade

/**
 * V5.9.806 — Operator-only telemetry: per-strategy expectancy aggregator.
 *
 * Read-only summary of which `tradingMode` strategies are profitable and
 * which are bleeding. Reads exclusively from bounded TradeHistoryStore close snapshots
 * — no new hooks in hot trading paths, no new fields on trade records,
 * nothing that can affect entry/exit decisions or trading volume.
 *
 * Aggregation is performed lazily on each call (no background tasks, no
 * memoised cache that could go stale and silently lie). At journal sizes
 * of <10k trades the full sort takes well under 20ms on-device.
 *
 * Output is plugged into [PipelineHealthCollector.dumpText] so the operator
 * can see, at a glance:
 *   • the top 5 profitable strategies (positive EV)
 *   • the bottom 5 bleeding strategies (negative EV)
 *   • absolute trade counts so a "good EV on 3 trades" doesn't fool anyone
 *
 * NO trading decision consults this object. It exists purely so the
 * operator can answer "which of my strategies is making me money?" without
 * having to dump the journal CSV and pivot it manually.
 */
object StrategyTelemetry {

    data class StrategyMetric(
        val strategy: String,
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val scratches: Int,
        val sumPnlPct: Double,
        val meanPnlPct: Double,
        val winRatePct: Double,
        val totalSolPnl: Double,
        val avgWinPct: Double = 0.0,   // V5.9.1489 mean of winning trades' pnl%
        val avgLossPct: Double = 0.0,  // V5.9.1489 mean of losing trades' pnl% (negative)
    ) {
        val isStatisticallyMeaningful: Boolean get() = trades >= 5

        // V5.9.1489 — DOCTRINE PROFIT-FACTOR EDGE. The pf rule is
        // avg_win*WR must exceed avg_loss*(1-WR). Expectancy here is the
        // per-trade SOL-edge proxy in pp; <=0 means the lane loses money in
        // expectation REGARDLESS of a skewed mean%. Self-contained, no I/O.
        val pfExpectancyPp: Double get() {
            val w = (winRatePct / 100.0).coerceIn(0.0, 1.0)
            return avgWinPct * w - kotlin.math.abs(avgLossPct) * (1.0 - w)
        }
    }

    // V5.0.6131 — LIVE STYLE EDGE KEY. Lane-only StrategyTruth hides the
    // actual tactic/style that reached capital. Live rows already persist routed
    // style in tradingModeEmoji as `style|emoji`; group it as lane|style without
    // mutating canonical lane tags used by legacy learners.
    private fun cleanStyleToken(raw: String): String {
        val first = raw.substringBefore("|").trim()
        val u = first.uppercase().replace('-', '_').replace(' ', '_')
        return u.takeIf { it.any { ch -> ch in 'A'..'Z' } && it.length >= 3 }?.take(48).orEmpty()
    }

    fun styleEdgeKey(t: Trade): String {
        val laneRaw = t.tradingMode.ifBlank { "STANDARD" }
        val lane = try { TradeHistoryStore.normalizeTradeModeName(laneRaw) } catch (_: Throwable) { laneRaw }
        val style = cleanStyleToken(t.tradingModeEmoji)
        return if (style.isNotBlank() && !style.equals(lane, true)) "${lane.uppercase()}|$style" else lane.uppercase()
    }

    private fun aggregateRows(rows: List<Trade>, keyFor: (Trade) -> String, pctCtx: String): List<StrategyMetric> {
        if (rows.isEmpty()) return emptyList()
        return rows.groupBy { keyFor(it).ifBlank { "STANDARD" } }.map { (strategy, trades) ->
            fun rowPnlSol(t: Trade): Double = t.netPnlSol.takeIf { it != 0.0 } ?: t.pnlSol
            fun sanePct(p: Double): Double = LearningPnlSanitizer.inspectPct(p, pctCtx, emit = false).takeIf { it.ok }?.pnlPct ?: 0.0
            val wins = trades.count { rowPnlSol(it) > 0.0 }
            val losses = trades.count { rowPnlSol(it) < 0.0 }
            val scratches = trades.size - wins - losses
            val sumPnlPct = trades.sumOf { sanePct(it.pnlPct) }
            val meanPnlPct = if (trades.isNotEmpty()) sumPnlPct / trades.size else 0.0
            val wlDenom = wins + losses
            val wr = if (wlDenom > 0) wins * 100.0 / wlDenom else 0.0
            val totalSol = trades.sumOf { rowPnlSol(it) }
            val winPcts = trades.asSequence().map { sanePct(it.pnlPct) }.filter { it >= 0.5 }.toList()
            val lossPcts = trades.asSequence().map { sanePct(it.pnlPct) }.filter { it <= -2.0 }.toList()
            StrategyMetric(strategy, trades.size, wins, losses, scratches, sumPnlPct, meanPnlPct, wr, totalSol,
                if (winPcts.isNotEmpty()) winPcts.sum() / winPcts.size else 0.0,
                if (lossPcts.isNotEmpty()) lossPcts.sum() / lossPcts.size else 0.0)
        }
    }

    /**
     * Aggregate close trades by their `tradingMode` field. BUYs are excluded — they
     * have pnlPct=0.0 by definition and would skew EV.
     *
     * V5.0.3974 — PAPER/LIVE BOUNDARY CONTRACT.
     * Default behavior remains report-compatible (all environments + partials), but
     * any decision-facing caller must use computeLiveTerminalLeaderboard(). Paper is
     * hypothesis data only; it must never directly authorize live expectancy, live
     * size dampening, or live runner/exit shaping.
     */
    fun computeLeaderboard(
        environment: String? = null,
        includePartials: Boolean = true,
        limit: Int = 2_500,
    ): List<StrategyMetric> {
        val all = try { TradeHistoryStore.getRecentValidClosedTrades(limit = limit, includePartials = includePartials) } catch (_: Throwable) { emptyList() }
        if (all.isEmpty()) return emptyList()
        val env = environment?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

        val sellsByStrategy: Map<String, List<Trade>> = all
            .asSequence()
            .filter { env == null || it.mode.equals(env, ignoreCase = true) }
            .filter { LearningPnlSanitizer.inspectTrade(it, "StrategyTelemetry", emit = false).ok }
            // V5.9.1043 — also collapse legacy bin names at read time so
            // historical trades recorded before V5.9.1038's choke-point
            // normalization (e.g. BLUE_CHIP) merge with the canonical
            // current names (BLUECHIP). Without this, the leaderboard
            // still shows ghost duplicate bins from old persisted data.
            .groupBy {
                // V5.9.1331 — a blank tradingMode is an unclassified-lane trade, not a
                // distinct "UNKNOWN" strategy. Bin it as STANDARD (the convention used at
                // every other lane-name site) so historical blank-mode rows stop forming a
                // phantom bucket the honest backtest misreads as a losing lane to retire.
                val raw = it.tradingMode.ifBlank { "STANDARD" }
                val norm = try { TradeHistoryStore.normalizeTradeModeName(raw) } catch (_: Throwable) { raw }
                if (norm.isBlank()) "STANDARD" else norm
            }

        return sellsByStrategy.map { (strategy, trades) ->
            val wins = trades.count { it.pnlPct >= 0.5 }
            val losses = trades.count { it.pnlPct <= -2.0 }
            val scratches = trades.size - wins - losses
            // V5.9.1357 — clamp per-trade pnl% for the EV/mean view so a single
            // feed-artifact outlier (e.g. +1,340,125% from a glitched price tick)
            // can't make a bleeding lane read as a megawinner (the "lie of
            // averages"). totalSolPnl below stays RAW so real SOL accounting is
            // untouched — this only sanitizes the percentage expectancy display.
            fun sanePct(p: Double): Double = LearningPnlSanitizer.inspectPct(p, "StrategyTelemetry.sanePct", emit = false).takeIf { it.ok }?.pnlPct ?: 0.0
            val sumPnl = trades.sumOf { sanePct(it.pnlPct) }
            val mean = if (trades.isNotEmpty()) sumPnl / trades.size else 0.0
            val wlDenom = wins + losses
            val wr = if (wlDenom > 0) (wins.toDouble() / wlDenom) * 100.0 else 0.0
            // V5.9.1360 P0.4 — SOL ACCOUNTING SANITY. totalSolPnl was a RAW sum, so
            // a single feed-artifact close (glitched near-zero price → astronomical
            // pnl) produced impossible values like +62,218 SOL that then poisoned
            // tuning/readiness/live-gating. A real meme close cannot net more than
            // ~50x its own deployed size (that is already a +5000% move). Any trade
            // whose |pnlSol| exceeds 50x its position size — or whose size is
            // missing while pnlSol is huge — is excluded from the SOL total used for
            // learning. Counters surface how often this fires.
            fun saneSol(t: com.lifecyclebot.data.Trade): Double? {
                val p = t.pnlSol
                if (p.isNaN() || p.isInfinite()) {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PNL_UNIT_MISMATCH_REJECTED") } catch (_: Throwable) {}
                    return null
                }
                val size = t.sol.takeIf { it > 0.0 } ?: 0.0
                // V5.9.1454 — DUAL-CEILING. The relative size*50 cap is defeated when
                // the SIZE field itself is poisoned (e.g. foreign-domain rows like
                // tokenized Stocks book a USD notional into t.sol, so cap=notional*50
                // is astronomical and a glitched per-trade pnl slips through — that is
                // how Stocks accumulated -12,381 SOL across 82 closes while each row
                // individually passed the relative cap). Add an ABSOLUTE per-close SOL
                // ceiling that does NOT depend on the size field: no single paper/live
                // close in this app deploys more than ~5 SOL, so a >25 SOL single-close
                // swing is physically impossible and must be a feed/unit artifact.
                // A row must pass BOTH ceilings to enter the SOL total.
                val relCap = if (size > 0.0) size * 50.0 else 5.0
                val ABS_CAP_SOL = 25.0
                if (kotlin.math.abs(p) > relCap || kotlin.math.abs(p) > ABS_CAP_SOL) {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ACCOUNTING_OUTLIER_NOT_TRAINED") } catch (_: Throwable) {}
                    return null
                }
                return p
            }
            val totalSol = trades.mapNotNull { saneSol(it) }.sum()
            // V5.9.1489 — avg win/loss in % (sanitized like the mean) so the
            // damper can apply the true profit-factor rule instead of raw mean,
            // which a fat take-profit tail skews positive on real bleeders.
            val winPcts = trades.asSequence().map { sanePct(it.pnlPct) }.filter { it >= 0.5 }.toList()
            val lossPcts = trades.asSequence().map { sanePct(it.pnlPct) }.filter { it <= -2.0 }.toList()
            val avgWinPct = if (winPcts.isNotEmpty()) winPcts.sum() / winPcts.size else 0.0
            val avgLossPct = if (lossPcts.isNotEmpty()) lossPcts.sum() / lossPcts.size else 0.0
            StrategyMetric(
                strategy   = strategy,
                trades     = trades.size,
                wins       = wins,
                losses     = losses,
                scratches  = scratches,
                sumPnlPct  = sumPnl,
                meanPnlPct = mean,
                winRatePct = wr,
                totalSolPnl = totalSol,
                avgWinPct = avgWinPct,
                avgLossPct = avgLossPct,
            )
        }
    }



    // V5.0.6001 — CLEAN LIVE LEADERBOARD CACHE (operator directive:
    // "flip the bot green"). LiveProbabilityEngine.forecast() calls this
    // method on every lane forecast (547 lane_evals per snapshot period
    // → 547 sort-and-dedupe rebuilds of the raw journal). As the journal
    // grows this dominates the bot-loop hot path — 07:17 dump showed
    // max cycle 34s, 15:04 dump showed max cycle 114s (linear-ish
    // growth). Cache with 10s TTL is decision-safe because probability
    // sizing is a slow-moving edge; the raw-journal reality clamp uses
    // its own 15s cache in LiveProbabilityEngine so the two windows
    // stay aligned.
    @Volatile private var cleanLiveLeaderboardCache: List<StrategyMetric> = emptyList()
    @Volatile private var cleanLiveLeaderboardCacheMs: Long = 0L
    @Volatile private var cleanLiveLeaderboardCacheLimit: Int = 0
    private const val CLEAN_LIVE_LEADERBOARD_TTL_MS = 10_000L
    // V5.0.6130 — paper mode is not toy training. Mirror the clean-live cache so
    // paper decision-facing edge can be used by tuners without rescanning the
    // journal on every 7.5s LiveStrategyTuner refresh.
    @Volatile private var cleanPaperLeaderboardCache: List<StrategyMetric> = emptyList()
    @Volatile private var cleanPaperLeaderboardCacheMs: Long = 0L
    @Volatile private var cleanPaperLeaderboardCacheLimit: Int = 0
    private const val CLEAN_PAPER_LEADERBOARD_TTL_MS = 10_000L
    @Volatile private var cleanLiveStyleLeaderboardCache: List<StrategyMetric> = emptyList()
    @Volatile private var cleanLiveStyleLeaderboardCacheMs: Long = 0L
    @Volatile private var cleanLiveStyleLeaderboardCacheLimit: Int = 0

    // V5.0.4513 — decision-facing clean live authority. The legacy live
    // leaderboard reads raw close snapshots and sanitizes obvious outliers, but
    // it can still include historical duplicate terminal/recovered/forensic rows
    // that StrategyTruthLedger already knows are not strategy truth. Use this for
    // gate relaxer, live WR floors, and future tuner alignment.
    fun computeCleanLiveTerminalLeaderboard(limit: Int = 2_500): List<StrategyMetric> {
        val now = System.currentTimeMillis()
        val cached = cleanLiveLeaderboardCache
        // Cache hit: TTL fresh AND cached limit is >= current request (so a
        // smaller subsequent request never triggers a rebuild).
        if (cached.isNotEmpty() &&
            cleanLiveLeaderboardCacheLimit >= limit &&
            (now - cleanLiveLeaderboardCacheMs) < CLEAN_LIVE_LEADERBOARD_TTL_MS) {
            return cached
        }
        val raw = try { TradeHistoryStore.getRecentValidClosedTradesRaw(limit = limit, includePartials = true) } catch (_: Throwable) { emptyList() }
        val cleanRows = try { StrategyTruthLedger.clean(raw, limit).rows } catch (_: Throwable) { raw }
            .filter { it.mode.equals("live", true) && it.side.equals("SELL", true) }
        val result = if (cleanRows.isEmpty()) emptyList() else cleanRows
            .groupBy {
                val rawMode = it.tradingMode.ifBlank { "STANDARD" }
                val norm = try { TradeHistoryStore.normalizeTradeModeName(rawMode) } catch (_: Throwable) { rawMode }
                if (norm.isBlank()) "STANDARD" else norm
            }
            .map { (strategy, trades) ->
                fun rowPnlSol(t: Trade): Double = t.netPnlSol.takeIf { it != 0.0 } ?: t.pnlSol
                fun sanePct(p: Double): Double = LearningPnlSanitizer.inspectPct(p, "StrategyTelemetry.cleanLive.sanePct", emit = false).takeIf { it.ok }?.pnlPct ?: 0.0
                val wins = trades.count { rowPnlSol(it) > 0.0 }
                val losses = trades.count { rowPnlSol(it) < 0.0 }
                val scratches = trades.size - wins - losses
                val sumPnlPct = trades.sumOf { sanePct(it.pnlPct) }
                val meanPnlPct = if (trades.isNotEmpty()) sumPnlPct / trades.size else 0.0
                val wlDenom = wins + losses
                val wr = if (wlDenom > 0) wins * 100.0 / wlDenom else 0.0
                val totalSol = trades.sumOf { rowPnlSol(it) }
                val winPcts = trades.asSequence().map { sanePct(it.pnlPct) }.filter { it >= 0.5 }.toList()
                val lossPcts = trades.asSequence().map { sanePct(it.pnlPct) }.filter { it <= -2.0 }.toList()
                StrategyMetric(
                    strategy = strategy,
                    trades = trades.size,
                    wins = wins,
                    losses = losses,
                    scratches = scratches,
                    sumPnlPct = sumPnlPct,
                    meanPnlPct = meanPnlPct,
                    winRatePct = wr,
                    totalSolPnl = totalSol,
                    avgWinPct = if (winPcts.isNotEmpty()) winPcts.sum() / winPcts.size else 0.0,
                    avgLossPct = if (lossPcts.isNotEmpty()) lossPcts.sum() / lossPcts.size else 0.0,
                )
            }
        cleanLiveLeaderboardCache = result
        cleanLiveLeaderboardCacheMs = now
        cleanLiveLeaderboardCacheLimit = limit
        return result
    }

    /**
     * V5.0.6131 — live-only style/tactic edge surface. Paper/shadow can propose
     * strategies, but LIVE sizing consults only clean LIVE terminal SELL style truth.
     */
    fun computeCleanLiveStyleTerminalLeaderboard(limit: Int = 2_500): List<StrategyMetric> {
        val now = System.currentTimeMillis()
        val cached = cleanLiveStyleLeaderboardCache
        if (cached.isNotEmpty() && cleanLiveStyleLeaderboardCacheLimit >= limit &&
            (now - cleanLiveStyleLeaderboardCacheMs) < CLEAN_LIVE_LEADERBOARD_TTL_MS) return cached
        val raw = try { TradeHistoryStore.getRecentValidClosedTradesRaw(limit = limit, includePartials = true) } catch (_: Throwable) { emptyList() }
        val cleanRows = try { StrategyTruthLedger.clean(raw, limit).rows } catch (_: Throwable) { raw }
            .filter { it.mode.equals("live", true) && it.side.equals("SELL", true) }
            .filter { styleEdgeKey(it).contains("|") }
        val result = aggregateRows(cleanRows, ::styleEdgeKey, "StrategyTelemetry.cleanLiveStyle.sanePct")
        cleanLiveStyleLeaderboardCache = result
        cleanLiveStyleLeaderboardCacheMs = now
        cleanLiveStyleLeaderboardCacheLimit = limit
        return result
    }

    fun liveStyleSizeMultiplier(lane: String, style: String, limit: Int = 1_500): Double {
        val safeLane = try { TradeHistoryStore.normalizeTradeModeName(lane).ifBlank { lane.uppercase() } } catch (_: Throwable) { lane.uppercase() }
        val safeStyle = cleanStyleToken(style).ifBlank { return 1.0 }
        val key = "${safeLane.uppercase()}|$safeStyle"
        val m = try { computeCleanLiveStyleTerminalLeaderboard(limit).firstOrNull { it.strategy.equals(key, true) } } catch (_: Throwable) { null } ?: return 1.0
        if (m.trades < 3) return 1.0
        // V5.0.6134 — CLEAN-LIVE COMPOUNDING BRAIN.
        // The first 6131 style edge was intentionally timid (max 1.24x). That
        // helped attribution but did not press live SOL hard enough for the 2x-5x
        // daily wallet mandate. This is still LIVE-only, terminal-SELL-only,
        // StrategyTruth-clean evidence. It does not let paper/shadow authorize live
        // risk, but it lets proven +SOL lane|style cells compound materially while
        // toxic cells shrink harder inside the lane instead of amputating the lane.
        val pfEdge6134 = m.pfExpectancyPp
        val green = m.totalSolPnl > 0.0 && pfEdge6134 > 0.0 && (m.winRatePct >= 40.0 || m.meanPnlPct >= 8.0 || m.avgWinPct >= 25.0)
        val elite = green && m.trades >= 5 && (m.totalSolPnl >= 0.20 || m.meanPnlPct >= 25.0 || m.avgWinPct >= 60.0)
        val toxic = m.trades >= 5 && (m.totalSolPnl < 0.0 || pfEdge6134 <= -4.0) && (m.winRatePct < 35.0 || m.meanPnlPct <= -7.0)
        return when {
            elite -> (1.18 + (m.totalSolPnl / 0.30).coerceIn(0.0, 0.27)).coerceIn(1.18, 1.45)
            green -> (1.08 + (m.totalSolPnl / 0.25).coerceIn(0.0, 0.22)).coerceIn(1.08, 1.30)
            toxic -> (0.78 - ((-m.totalSolPnl) / 0.25).coerceIn(0.0, 0.26)).coerceIn(0.52, 0.78)
            else -> 1.0
        }
    }

    /** Decision-facing live authority: LIVE terminal SELL rows only, no partials, no paper. */
    fun computeLiveTerminalLeaderboard(limit: Int = 2_500): List<StrategyMetric> =
        computeLeaderboard(environment = "live", includePartials = false, limit = limit)

    // V5.0.6079 — decision-facing PAPER authority for paper mode only.
    // Paper must compound and learn while the operator is testing. This mirrors
    // clean-live StrategyTruthLedger hygiene, but keeps the environment boundary
    // explicit so paper evidence never authorizes LIVE sizing.
    fun computeCleanPaperTerminalLeaderboard(limit: Int = 2_500): List<StrategyMetric> {
        val now = System.currentTimeMillis()
        val cached = cleanPaperLeaderboardCache
        if (cached.isNotEmpty() &&
            cleanPaperLeaderboardCacheLimit >= limit &&
            (now - cleanPaperLeaderboardCacheMs) < CLEAN_PAPER_LEADERBOARD_TTL_MS) {
            return cached
        }
        val raw = try { TradeHistoryStore.getRecentValidClosedTradesRaw(limit = limit, includePartials = true) } catch (_: Throwable) { emptyList() }
        val cleanRows = try { StrategyTruthLedger.clean(raw, limit).rows } catch (_: Throwable) { raw }
            .filter { it.mode.equals("paper", true) && it.side.equals("SELL", true) }
        val result = if (cleanRows.isEmpty()) emptyList() else cleanRows
            .groupBy {
                val rawMode = it.tradingMode.ifBlank { "STANDARD" }
                val norm = try { TradeHistoryStore.normalizeTradeModeName(rawMode) } catch (_: Throwable) { rawMode }
                if (norm.isBlank()) "STANDARD" else norm
            }
            .map { (strategy, trades) ->
                fun rowPnlSol(t: Trade): Double = t.netPnlSol.takeIf { it != 0.0 } ?: t.pnlSol
                fun sanePct(p: Double): Double = LearningPnlSanitizer.inspectPct(p, "StrategyTelemetry.cleanPaper.sanePct", emit = false).takeIf { it.ok }?.pnlPct ?: 0.0
                val wins = trades.count { rowPnlSol(it) > 0.0 }
                val losses = trades.count { rowPnlSol(it) < 0.0 }
                val scratches = trades.size - wins - losses
                val sumPnlPct = trades.sumOf { sanePct(it.pnlPct) }
                val meanPnlPct = if (trades.isNotEmpty()) sumPnlPct / trades.size else 0.0
                val wlDenom = wins + losses
                val wr = if (wlDenom > 0) wins * 100.0 / wlDenom else 0.0
                val totalSol = trades.sumOf { rowPnlSol(it) }
                val winPcts = trades.asSequence().map { sanePct(it.pnlPct) }.filter { it >= 0.5 }.toList()
                val lossPcts = trades.asSequence().map { sanePct(it.pnlPct) }.filter { it <= -2.0 }.toList()
                StrategyMetric(
                    strategy = strategy,
                    trades = trades.size,
                    wins = wins,
                    losses = losses,
                    scratches = scratches,
                    sumPnlPct = sumPnlPct,
                    meanPnlPct = meanPnlPct,
                    winRatePct = wr,
                    totalSolPnl = totalSol,
                    avgWinPct = if (winPcts.isNotEmpty()) winPcts.sum() / winPcts.size else 0.0,
                    avgLossPct = if (lossPcts.isNotEmpty()) lossPcts.sum() / lossPcts.size else 0.0,
                )
            }
        cleanPaperLeaderboardCache = result
        cleanPaperLeaderboardCacheMs = now
        cleanPaperLeaderboardCacheLimit = limit
        return result
    }

    /** Paper-only report/audit view. Paper may propose hypotheses, not live authority. */
    fun computePaperTerminalLeaderboard(limit: Int = 2_500): List<StrategyMetric> =
        computeLeaderboard(environment = "paper", includePartials = false, limit = limit)

    /** Live terminal close count used by LiveMaturityAuthority; never counts paper or partials. */
    fun liveTerminalCloseCount(limit: Int = 10_000): Int = LiveMaturityAuthority.liveTerminalCloseCount(limit)

    /** Top-N by mean PnL%, restricted to strategies with ≥5 trades (avoids
     *  "+47% EV on 1 trade" noise dominating the leaderboard). */
    fun winners(n: Int = 5): List<StrategyMetric> =
        computeLeaderboard()
            .filter { it.isStatisticallyMeaningful }
            .sortedByDescending { it.meanPnlPct }
            .take(n)

    /** Bottom-N by mean PnL%, same statistical-meaning filter. */
    fun bleeders(n: Int = 5): List<StrategyMetric> =
        computeLeaderboard()
            .filter { it.isStatisticallyMeaningful }
            .sortedBy { it.meanPnlPct }
            .take(n)

    // ───────────────────────────────────────────────────────────────────
    // V5.9.1053 — NO AUTO-DISABLE. Operator directive:
    // "no strategies should be permanently disabled. we are meant to help
    // anything lagging to learn better. any issue like this is meant to
    // be self healed."
    //
    // isDisabled() always returns false. BrainConsensusGate may still
    // emit a SOFT advisory when a strategy is bleeding, but it can never
    // produce a HARD_BLOCK via this path. The strategy keeps running,
    // keeps collecting samples, and self-heals as WR improves.
    //
    // clearDisabled() and clearDisabledIfInsufficient() are kept as no-ops
    // so existing call sites compile without changes.
    // ───────────────────────────────────────────────────────────────────

    /** Always returns false — no strategy is ever auto-disabled. */
    fun isDisabled(strategy: String): Boolean = false

    /** Always returns empty — nothing is disabled. */
    fun getDisabled(): Set<String> = emptySet()

    /** No-op — nothing to clear. */
    fun clearDisabled() {}

    /** No-op — nothing to clear. */
    fun clearDisabledIfInsufficient(minTrades: Int) {}


    /**
     * Format a compact human-readable block for embedding in the pipeline
     * health dump. Returns an empty string when there isn't enough data
     * to be useful (so the dump doesn't sprout an empty header at boot).
     */
    fun formatForPipelineDump(): String {
        val all = computeLeaderboard()
        val meaningful = all.filter { it.isStatisticallyMeaningful }
        if (meaningful.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n===== Strategy expectancy (V5.9.806) =====\n")
        sb.append("  (SELL+PARTIAL_SELL with ≥5 trainable closes, sorted by mean PnL%)\n\n")

        val winners = meaningful.sortedByDescending { it.meanPnlPct }.take(5)
        sb.append("  Top 5 winners:\n")
        for (m in winners) {
            sb.append(
                "    %-22s n=%-4d W/L/S=%d/%d/%d  WR=%5.1f%%  EV=%+6.2f%%/trade  PnL=%+.4f SOL\n".format(
                    m.strategy.take(22), m.trades, m.wins, m.losses, m.scratches,
                    m.winRatePct, m.meanPnlPct, m.totalSolPnl,
                )
            )
        }

        val bleeders = meaningful.sortedBy { it.meanPnlPct }.take(5)
        sb.append("\n  Bottom 5 bleeders:\n")
        for (m in bleeders) {
            sb.append(
                "    %-22s n=%-4d W/L/S=%d/%d/%d  WR=%5.1f%%  EV=%+6.2f%%/trade  PnL=%+.4f SOL\n".format(
                    m.strategy.take(22), m.trades, m.wins, m.losses, m.scratches,
                    m.winRatePct, m.meanPnlPct, m.totalSolPnl,
                )
            )
        }

        sb.append("\n  Note: read-only telemetry. No strategy is auto-disabled — operator decides what to retire.\n")
        // V5.9.1048 — bin glossary clarifier. Operator V5.9.1047 dump
        // flagged STANDARD lane (n=32 WR=100% +207%/trade) as
        // suspicious-good. STANDARD is the default tradingMode assigned
        // when a trade has no specific lane affinity (TokenMemory.kt
        // fallback) — typically high-quality v3 entries that don't fit
        // a curated bucket. The 100% WR is partly survivor-bias: many
        // STANDARD candidates get auto-promoted to a specific lane mid-
        // trade and get re-classified, leaving only the cleanest setups
        // in the bin. Treat it as a "clean V3" tier, not a separate
        // strategy to scale.
        sb.append("  Bin glossary: STANDARD=V3 default (no lane affinity)  ·  BLUECHIP/SHITCOIN/MOONSHOT/etc=lane-specific  ·  CASHGEN/TREASURY=lifecycle exits\n")

        // V5.9.1053: no auto-disable — strategies self-heal via learning

        return sb.toString()
    }
}
