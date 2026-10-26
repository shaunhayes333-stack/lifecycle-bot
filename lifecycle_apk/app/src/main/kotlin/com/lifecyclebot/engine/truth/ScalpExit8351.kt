package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8351 §LIVE_SCALP_WHERE_SCALPS_PROVED — a lane whose adopted choice (ExploreShadow8347.bestFor8351) proved on the
 * SCALP exit trades its LIVE positions the same way the shadow did: +15 % sells half, +30 % sells the rest, a stop at
 * 60 % of the lane stop (V5.0.8363: never under 2 x the 1-minute range + entry chase, nor 6 %, and not in the first 60 s), a give-back floor once the peak passes +10 % (half the peak, never under the
 * round trip), and a 10-minute clock. Every other lane keeps the lane doctrine. The decision is fixed per position at its
 * first mark (a lane that changes its choice mid-hold does not change this position's exit).
 * Offered through the per-tick arbitration (ExitTruthArbiterLive8144.Tick8144), so protective exits still outrank it.
 */
object ScalpExit8351 {
    const val TP1_PCT_8351 = 15.0
    const val TP2_PCT_8351 = 30.0
    const val FLOOR_ARM_PCT_8351 = 10.0
    const val CLOCK_MS_8351 = 10 * 60_000L

    private class State8351(val scalp: Boolean, val costPct: Double, val stopPct: Double,
                            val noise1mPct8363: Double = Double.NaN, val chasePct8363: Double = 0.0)
    private val states = ConcurrentHashMap<String, State8351>()

    /** Pure: the scalp exit for gross [g] at peak [peak] and position age [ageMs]: (fraction of the holding, reason) or null. */
    fun decide8351(g: Double, peak: Double, ageMs: Long, tier: Int, stopPct: Double, costPct: Double): Pair<Double, String>? {
        if (!g.isFinite()) return null
        val stop = (stopPct * 0.6).coerceAtLeast(4.0)
        if (g <= -stop) return 1.0 to "SCALP_HARD_STOP_8351"
        if (tier == 0 && g >= TP1_PCT_8351) return 0.5 to "SCALP_TAKE_PROFIT_8351_T1"
        if (tier <= 1 && g >= TP2_PCT_8351) return 1.0 to "SCALP_TAKE_PROFIT_8351_T2"
        if (peak >= FLOOR_ARM_PCT_8351) {
            val floor = maxOf((if (costPct.isFinite()) costPct else 0.0).coerceAtMost(peak * 0.5), peak * 0.5)
            if (g <= floor) return 1.0 to "SCALP_PROFIT_LOCK_8351"
        }
        if (ageMs >= CLOCK_MS_8351) return 1.0 to "SCALP_TIME_8351"
        return null
    }

    /** SpikeCapture7943's per-tick offers: a live position in a SCALP lane offers its scalp exit. */
    fun offer8351(ts: TokenState, px: Double?, nowMs: Long, offer: (TokenState, Double, String) -> Unit) {
        val pos = ts.position
        if (!pos.isOpen || pos.isPaperPosition || pos.entryTime <= 0L || !(pos.entryPrice > 0.0)) return
        if (px == null || !(px > 0.0)) return
        val key = "${ts.mint}|${pos.entryTime}"
        val st = states.getOrPut(key) {
            val lane = pos.tradingMode.ifBlank { "UNKNOWN" }
            val scalp = try { ExploreShadow8347.liveExitStyle8351(lane, EvidenceHold8336.startMs8336) == ExploreShadow8347.Exit8347.SCALP } catch (_: Throwable) { false }
            val liq = try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }
            val cost = try { NetEdge8030.costPct8030(lane, pos.costSol, liq, ts.mint, ts.lastMcap) } catch (_: Throwable) { 7.0 }
            val stop = try { FieldManual7715.stopPct8392(lane, cost) } catch (_: Throwable) { 10.0 }   // V5.0.8392 — never inside the round trip
            if (scalp) try { PipelineHealthCollector.labelInc("SCALP_EXIT_ADOPTED_8351_${lane.uppercase()}") } catch (_: Throwable) {}
            // V5.0.8363 — the coin's measured 1-minute range and the lane's measured entry chase, fixed at the first mark.
            val noise8363 = if (scalp) com.lifecyclebot.engine.ScalpStop8363.noise1mPct8363(ts) else Double.NaN
            val chase8363 = if (scalp) com.lifecyclebot.engine.ScalpStop8363.chasePct8363(lane) else 0.0
            State8351(scalp, cost, stop, noise8363, chase8363)
        }
        if (states.size > 2_000) states.clear()   // positions are few; a cleared state re-reads its lane choice next tick
        if (!st.scalp) return
        val g = (px / pos.entryPrice - 1.0) * 100.0
        val peak = if (pos.highestPrice > 0.0) (pos.highestPrice / pos.entryPrice - 1.0) * 100.0 else g
        // superseded by V5.0.8363 — decide8351's stop (0.6 x lane stop from the first tick) sat inside entry noise: the stop is
        // max(0.6 x lane stop, 2 x 1m range + chase, 6 %) with a 60 s warmup (ScalpStop8363); the ladder is decide8351's.
        val tier = com.lifecyclebot.engine.RunnerLive8153.confirmedTier8153(ts, com.lifecyclebot.engine.RunnerRungBook8153.LADDER_SCALP_EXIT_8394)
        val d = com.lifecyclebot.engine.ScalpStop8363.decide8363(g, maxOf(peak, g), nowMs - pos.entryTime, tier, st.stopPct, st.costPct,
            st.noise1mPct8363, st.chasePct8363) ?: return
        val rung = com.lifecyclebot.engine.RunnerLive8153.rungOf8153(d.second)
        if (rung > 0) {
            val inFlight = com.lifecyclebot.engine.sell.SellExecutionLocks.isLocked(ts.mint) ||
                com.lifecyclebot.engine.sell.CloseLease.sellInFlight7948(ts.mint)
            if (!com.lifecyclebot.engine.RunnerLive8153.settlePending8153(ts, inFlight, nowMs)) return
            if (!com.lifecyclebot.engine.RunnerLive8153.request8153(ts,
                    com.lifecyclebot.engine.RunnerRungBook8153.LADDER_SCALP_EXIT_8394, rung, d.second, nowMs)) return
        }
        // Safety/time exits still reach arbitration while a take-profit request is outstanding.
        offer(ts, d.first, d.second)
    }
}
