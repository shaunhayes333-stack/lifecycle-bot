package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.8236 §SHAPE_FROM_CURVE_READS — a fresh pump coin's price shape comes from its bonding curve, not from
 * waiting on scanner polls.
 *
 * The setup classifier needs three time-separated price points (shapeObs8084); ts.history only grows one candle per
 * DexScreener poll, and a pre-graduation coin has no provider OHLCV, so MOONSHOT / SHITCOIN / EXPRESS candidates sat in
 * DATA_INCOMPLETE_DEFER (8233: 1,328 FDG deferrals, `shape` the dominant gap). The curve account IS the price: Helius
 * accountSubscribe (CurveTicks7968, free, already wired for HELD curves) delivers the reserves on every trade, and the
 * fan-out's curve RPC read (PUMP_CURVE_RPC) states the same price every hydration.
 *
 * Both are ONE pricing function of one account (8094 withdrew mixed-source ticks; this never mixes): they feed
 * TradeTape8100 as curve price-only prints, where the same minute-bar / 15-second young-shape readers that serve
 * executed prints state run-up / peak / drawdown. Never overrides a provider reading (LanePlaybook7907 fills shape
 * from the tape only when the history cannot state it).
 */
object CurveShapeFeed8236 {
    private const val MAX_CANDIDATE_SUBS_8236 = 32
    private const val CANDIDATE_MAX_AGE_MS_8236 = 90L * 60_000L

    /**
     * Pure: the curve-tick subscription set — every held curve mint first, then the freshest pump candidates on an
     * active (not graduated) curve the bot is currently watching, newest watchlist entries first, bounded.
     */
    fun subscriptionSet8236(
        held: Set<String>,
        candidates: Collection<TokenState>,
        nowMs: Long,
        hasCurveKey: (String) -> Boolean,
        graduated: (String) -> Boolean,
        maxCandidates: Int = MAX_CANDIDATE_SUBS_8236,
    ): Set<String> {
        val out = LinkedHashSet<String>(held.filter { it.isNotBlank() })
        val fresh = candidates.asSequence()
            .filter { it.mint.isNotBlank() && it.mint !in out }
            .filter { nowMs - it.addedToWatchlistAt in 0L..CANDIDATE_MAX_AGE_MS_8236 }
            .filter { hasCurveKey(it.mint) && !graduated(it.mint) }
            .sortedByDescending { it.addedToWatchlistAt }
            .take(maxCandidates)
        for (ts in fresh) out += ts.mint
        return out
    }

    /** BotService open-position tick: the live subscription set for CurveTicks7968. */
    fun subscriptionSetNow8236(held: Set<String>, candidates: Collection<TokenState>, nowMs: Long): Set<String> {
        val set = subscriptionSet8236(
            held, candidates, nowMs,
            hasCurveKey = { m -> try { com.lifecyclebot.network.PumpCurveKeys7269.canonicalCurveKey7392(m) != null } catch (_: Throwable) { false } },
            graduated = { m -> try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(m) } catch (_: Throwable) { false } },
        )
        if (set.size > held.size) try { PipelineHealthCollector.labelInc("CURVE_CANDIDATE_SUBSCRIBED_8236") } catch (_: Throwable) {}
        return set
    }

    /** ParallelMarkFanout7088: one curve RPC read (SOL per token) of an active curve reaches the tape as a curve print. */
    fun onCurveRead8236(mint: String, priceSol: Double, atMs: Long) {
        if (mint.isBlank() || !priceSol.isFinite() || priceSol <= 0.0) return
        try { TradeTape8100.notePrice8169(mint, priceSol, TradeTape8100.SRC_CURVE_8169, atMs) } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("CURVE_READ_TO_TAPE_8236") } catch (_: Throwable) {}
    }
}
