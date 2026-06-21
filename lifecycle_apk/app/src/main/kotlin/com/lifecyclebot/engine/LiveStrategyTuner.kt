package com.lifecyclebot.engine

/**
 * V5.0.4023 — LIVE STRATEGY TUNING.
 *
 * Decision-facing, cached, live-only tuning layer. It reads the same clean
 * StrategyTelemetry live-terminal SELL leaderboard used by the reports and
 * returns bounded multipliers for capital envelope + runner patience.
 *
 * Doctrine:
 *  - LIVE terminal closes only; no paper, shadow, partial, recovered-accounting
 *    rows can authorize live tuning.
 *  - Soft-shape only: never veto, never zero-size, never call LLM/API.
 *  - Press proven live winners by letting them ride and giving more envelope.
 *  - Bleeders become faster/smaller probes while hard SL/catastrophe safety stays
 *    terminal and unconditional.
 */
object LiveStrategyTuner {
    const val VERSION = "V5.0.4023_LIVE_STRATEGY_TUNER"

    data class Adjustment(
        val lane: String,
        val trades: Int,
        val winRatePct: Double,
        val totalSolPnl: Double,
        val pfExpectancyPp: Double,
        val meanPnlPct: Double,
        val sizeMult: Double,
        val tpMult: Double,
        val holdMult: Double,
        val maxWalletMult: Double,
        val liquidityImpactMult: Double,
        val partialTriggerMult: Double,
        val label: String,
    ) {
        val isNeutral: Boolean get() = label == "neutral"
        val compact: String get() =
            "$VERSION lane=$lane label=$label n=$trades wr=${"%.1f".format(winRatePct)} pnl=${"%+.4f".format(totalSolPnl)} pf=${"%+.2f".format(pfExpectancyPp)} mean=${"%+.1f".format(meanPnlPct)} size×=${"%.2f".format(sizeMult)} tp×=${"%.2f".format(tpMult)} hold×=${"%.2f".format(holdMult)} partial×=${"%.2f".format(partialTriggerMult)}"
    }

    private const val CACHE_MS = 7_500L
    private const val MIN_TUNE_TRADES = 5
    private const val MIN_WINNER_TRADES = 8
    private const val LIVE_PARTIAL_PROFIT_FLOOR_PCT = 8.0

    @Volatile private var cacheAtMs: Long = 0L
    @Volatile private var cached: Map<String, Adjustment> = emptyMap()

    fun adjustment(rawLane: String?): Adjustment {
        val lane = canonical(rawLane)
        return try { snapshot()[lane] ?: neutral(lane) } catch (_: Throwable) { neutral(lane) }
    }

    fun sizeMultiplier(rawLane: String?): Double = adjustment(rawLane).sizeMult
    fun tpMultiplier(rawLane: String?): Double = adjustment(rawLane).tpMult
    fun holdMultiplier(rawLane: String?): Double = adjustment(rawLane).holdMult
    fun partialTriggerMultiplier(rawLane: String?): Double = adjustment(rawLane).partialTriggerMult

    fun livePartialProfitFloorPct(): Double = LIVE_PARTIAL_PROFIT_FLOOR_PCT

    fun statusLine(): String = try {
        val tuned = snapshot().values
            .distinctBy { it.lane }
            .filter { !it.isNeutral }
            .sortedWith(compareBy<Adjustment> { it.label }.thenBy { it.lane })
        if (tuned.isEmpty()) {
            "LiveStrategyTuner: neutral (live-terminal only; no lane ≥$MIN_TUNE_TRADES actionable closes)"
        } else {
            "LiveStrategyTuner: " + tuned.joinToString(" · ") {
                "${it.lane}:${it.label} n=${it.trades} WR=${"%.0f".format(it.winRatePct)}% PnL=${"%+.3f".format(it.totalSolPnl)} size×=${"%.2f".format(it.sizeMult)} tp×=${"%.2f".format(it.tpMult)} hold×=${"%.2f".format(it.holdMult)} partial×=${"%.2f".format(it.partialTriggerMult)}"
            }
        }
    } catch (_: Throwable) { "LiveStrategyTuner: unavailable" }

    private fun snapshot(): Map<String, Adjustment> {
        val now = System.currentTimeMillis()
        val c = cached
        if (now - cacheAtMs < CACHE_MS && c.isNotEmpty()) return c
        val fresh = compute()
        cached = fresh
        cacheAtMs = now
        return fresh
    }

    private fun compute(): Map<String, Adjustment> {
        val board = try { StrategyTelemetry.computeLiveTerminalLeaderboard(limit = 1_500) } catch (_: Throwable) { return emptyMap() }
        if (board.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Adjustment>()
        for (m in board) {
            if (m.trades < MIN_TUNE_TRADES) continue
            val lane = canonical(m.strategy)
            val adj = buildAdjustment(lane, m)
            out[lane] = adj
            val raw = m.strategy.trim().uppercase()
            if (raw.isNotBlank()) out[raw] = adj
        }
        return out
    }

    private fun buildAdjustment(lane: String, m: StrategyTelemetry.StrategyMetric): Adjustment {
        val wr = m.winRatePct.coerceIn(0.0, 100.0)
        val pf = m.pfExpectancyPp
        val sol = m.totalSolPnl
        val mean = m.meanPnlPct
        val n = m.trades

        // Positive live PF/PNL lanes are where the bot has actually made SOL.
        // Bias these toward patience: higher TP/partial triggers and longer hold.
        val winner = n >= MIN_WINNER_TRADES && sol > 0.0 && pf > 0.0 &&
            (wr >= 45.0 || pf >= 10.0 || mean >= 20.0)
        if (winner) {
            val wrEdge = ((wr - 40.0) / 35.0).coerceIn(0.0, 1.0)
            val pfEdge = (pf / 40.0).coerceIn(0.0, 1.0)
            val solEdge = (sol / 0.75).coerceIn(0.0, 1.0)
            val edge = (wrEdge * 0.35 + pfEdge * 0.45 + solEdge * 0.20).coerceIn(0.0, 1.0)
            return Adjustment(
                lane = lane,
                trades = n,
                winRatePct = wr,
                totalSolPnl = sol,
                pfExpectancyPp = pf,
                meanPnlPct = mean,
                sizeMult = (1.04 + edge * 0.18).coerceIn(1.00, 1.22),
                tpMult = (1.10 + edge * 0.30).coerceIn(1.08, 1.40),
                holdMult = (1.18 + edge * 0.42).coerceIn(1.12, 1.60),
                maxWalletMult = (1.04 + edge * 0.18).coerceIn(1.00, 1.22),
                liquidityImpactMult = (1.02 + edge * 0.10).coerceIn(1.00, 1.12),
                partialTriggerMult = (1.30 + edge * 0.70).coerceIn(1.25, 2.00),
                label = "runner_press",
            )
        }

        // Negative live expectancy: do not turn into vetoes; reduce posture and
        // bank faster when it finally has a real profit. LaneExpectancyDamper may
        // apply a deeper capital haircut at the executor, still non-zero.
        val pfBleed = sol < 0.0 && pf <= 0.0
        val wrBleed = n >= 8 && wr < 35.0
        val meanBleed = mean <= -8.0
        val toxicBleed = n >= 20 && wr <= 25.0 && sol < 0.0
        if (pfBleed || wrBleed || meanBleed || toxicBleed) {
            val wrDepth = ((35.0 - wr) / 35.0).coerceIn(0.0, 1.0)
            val pfDepth = ((-pf) / 16.0).coerceIn(0.0, 1.0)
            val solDepth = ((-sol) / 0.50).coerceIn(0.0, 1.0)
            val meanDepth = ((-mean) / 25.0).coerceIn(0.0, 1.0)
            val depth = maxOf(wrDepth, pfDepth, solDepth, meanDepth)
            val sizeFloor = if (toxicBleed) 0.48 else 0.62
            return Adjustment(
                lane = lane,
                trades = n,
                winRatePct = wr,
                totalSolPnl = sol,
                pfExpectancyPp = pf,
                meanPnlPct = mean,
                sizeMult = (0.94 - depth * 0.34).coerceIn(sizeFloor, 0.96),
                tpMult = (1.00 - depth * 0.12).coerceIn(0.86, 1.00),
                holdMult = (0.96 - depth * 0.30).coerceIn(0.56, 1.00),
                maxWalletMult = (0.96 - depth * 0.28).coerceIn(0.58, 1.00),
                liquidityImpactMult = (0.96 - depth * 0.20).coerceIn(0.70, 1.00),
                partialTriggerMult = (1.00 + depth * 0.20).coerceIn(1.00, 1.25),
                label = if (toxicBleed) "toxic_probe" else "bleeder_probe",
            )
        }

        return neutral(lane, n, wr, sol, pf, mean)
    }

    private fun canonical(raw: String?): String {
        val r = raw?.trim()?.takeIf { it.isNotBlank() } ?: return "STANDARD"
        return try { LiveGrowthDoctrine.canonicalLane(r) } catch (_: Throwable) { r.uppercase() }
    }

    private fun neutral(
        lane: String,
        trades: Int = 0,
        wr: Double = 0.0,
        sol: Double = 0.0,
        pf: Double = 0.0,
        mean: Double = 0.0,
    ): Adjustment = Adjustment(
        lane = lane,
        trades = trades,
        winRatePct = wr,
        totalSolPnl = sol,
        pfExpectancyPp = pf,
        meanPnlPct = mean,
        sizeMult = 1.0,
        tpMult = 1.0,
        holdMult = 1.0,
        maxWalletMult = 1.0,
        liquidityImpactMult = 1.0,
        partialTriggerMult = 1.0,
        label = "neutral",
    )
}
