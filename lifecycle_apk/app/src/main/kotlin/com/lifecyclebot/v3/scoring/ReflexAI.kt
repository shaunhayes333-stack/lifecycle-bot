package com.lifecyclebot.v3.scoring

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ErrorLogger

/**
 * V5.9.123 — ReflexAI
 *
 * Sub-second reaction layer. Most of the bot's exit logic runs on
 * multi-second scan ticks. Meme snipers die in the gap. This module
 * offers three stateless "reflex checks" that can be called from any
 * tight loop (price tick, volume tick, liquidity tick) to force
 * immediate action when a catastrophic event is in progress.
 *
 * Returns one of:
 *   • null                — no reflex
 *   • REFLEX_ABORT        — catastrophic red candle in first 30s
 *   • REFLEX_PARTIAL_LOCK — 2x+ pump within 2 min; lock 50%
 *   • REFLEX_LIQ_DRAIN    — liquidity just dropped >25% in 30s
 *
 * Caller routes these to Executor sell/partial-sell paths. The actual
 * fast-loop caller is added to BotService (price tick handler) in a
 * separate commit; this file defines the decision logic only so it can
 * be unit-tested without touching any live pipeline.
 */
object ReflexAI {

    private const val TAG = "Reflex"

    enum class Reflex { ABORT, PARTIAL_LOCK, LIQ_DRAIN }

    /**
     * Evaluate the 3 reflexes for an open position. All inputs are
     * already tracked in TokenState; caller passes the current tick.
     */
    fun evaluate(
        ts: TokenState,
        currentPriceUsd: Double,
        currentLiqUsd: Double,
    ): Reflex? {
        val pos = ts.position
        if (!pos.isOpen) return null
        if (pos.entryTime <= 0 || pos.entryPrice <= 0) return null

        val heldSec = (System.currentTimeMillis() - pos.entryTime) / 1000L
        val gainPct = if (pos.entryPrice > 0) {
            (currentPriceUsd - pos.entryPrice) / pos.entryPrice * 100.0
        } else 0.0

        // Reflex 1 — abort-fast on catastrophic red candle in first 30s
        if (heldSec in 0..30 && gainPct <= -15.0) {
            ErrorLogger.warn(TAG, "⚡ REFLEX ABORT: ${ts.symbol} gain=${gainPct.toInt()}% at ${heldSec}s")
            return Reflex.ABORT
        }

        // Reflex 2 — immediate 2x pump → partial lock 50%
        if (heldSec in 0..120 && gainPct >= 100.0 && pos.partialSoldPct < 0.25) {
            ErrorLogger.info(TAG, "⚡ REFLEX PARTIAL: ${ts.symbol} +${gainPct.toInt()}% at ${heldSec}s → lock 50%")
            return Reflex.PARTIAL_LOCK
        }

        // V5.9.868 — operator instruction (active rules):
        //   "Maintain liquidity drain heuristic thresholds:
        //    >=2 hits required + (>=40% drop over 60s OR <$800 total liquidity)"
        //
        // Previous logic fired on a single tick when entry→current liq drop
        // crossed 25%, which is both (a) too eager (single-tick noise can
        // trigger) and (b) missing the absolute <$800 floor. Replaced with
        // operator spec:
        //   1. Track liquidity samples in a rolling 60-second window.
        //   2. Compute the max→current drop pct within that window.
        //   3. Hit condition = (drop >= 40% over 60s) OR (current < $800).
        //   4. Need >=2 consecutive hits to fire LIQ_DRAIN.
        // The first hit just records and observes; the second confirms.
        val entryLiq = pos.entryLiquidityUsd
        if (entryLiq > 0 && currentLiqUsd > 0) {
            val now = System.currentTimeMillis()
            val mint = ts.mint   // V5.9.868 — Position has no mint field; use TokenState
            recordLiquiditySample(mint, currentLiqUsd, now)
            val dropPct60s = computeMaxDrop60s(mint, currentLiqUsd, now)
            val isBelowFloor = currentLiqUsd < 800.0
            val isBigDrop = dropPct60s >= 40.0

            if (isBigDrop || isBelowFloor) {
                val hits = bumpDrainHit(mint, now)
                if (hits >= 2) {
                    ErrorLogger.warn(
                        TAG,
                        "⚡ REFLEX LIQ_DRAIN [${hits} hits]: ${ts.symbol} " +
                        "drop60s=${"%.0f".format(dropPct60s)}% liq=$${currentLiqUsd.toInt()} " +
                        "(trigger: ${if (isBelowFloor) "ABS_FLOOR<\$800 " else ""}${if (isBigDrop) ">=40%/60s" else ""})"
                    )
                    clearDrainHit(mint)
                    return Reflex.LIQ_DRAIN
                } else {
                    ErrorLogger.info(
                        TAG,
                        "👀 REFLEX LIQ_DRAIN watch [hit ${hits}/2]: ${ts.symbol} " +
                        "drop60s=${"%.0f".format(dropPct60s)}% liq=$${currentLiqUsd.toInt()}"
                    )
                }
            } else {
                // Decay drain hits on a normal tick so noise doesn't stick.
                decayDrainHit(mint, now)
            }
        }

        return null
    }

    // ── V5.9.868 — operator-spec drain bookkeeping ──────────────────────
    private data class LiqSample(val ts: Long, val liqUsd: Double)
    private val liquiditySamples = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<LiqSample>>()
    private data class DrainHit(@Volatile var count: Int, @Volatile var lastTs: Long)
    private val drainHits = java.util.concurrent.ConcurrentHashMap<String, DrainHit>()
    private const val DRAIN_WINDOW_MS = 60_000L      // 60-second rolling window
    private const val HIT_DECAY_MS    = 90_000L      // after 90s with no hit, reset

    private fun recordLiquiditySample(mint: String, liq: Double, now: Long) {
        if (mint.isBlank()) return
        val q = liquiditySamples.getOrPut(mint) { java.util.ArrayDeque() }
        synchronized(q) {
            q.addLast(LiqSample(now, liq))
            // Evict samples older than 60s + 5s grace
            while (q.isNotEmpty() && (now - q.peekFirst().ts) > (DRAIN_WINDOW_MS + 5_000L)) {
                q.pollFirst()
            }
        }
    }

    private fun computeMaxDrop60s(mint: String, currentLiq: Double, now: Long): Double {
        if (mint.isBlank() || currentLiq <= 0.0) return 0.0
        val q = liquiditySamples[mint] ?: return 0.0
        var maxLiq = currentLiq
        synchronized(q) {
            for (s in q) {
                if ((now - s.ts) <= DRAIN_WINDOW_MS && s.liqUsd > maxLiq) {
                    maxLiq = s.liqUsd
                }
            }
        }
        if (maxLiq <= 0.0) return 0.0
        return ((maxLiq - currentLiq) / maxLiq) * 100.0
    }

    private fun bumpDrainHit(mint: String, now: Long): Int {
        if (mint.isBlank()) return 0
        val h = drainHits.getOrPut(mint) { DrainHit(0, now) }
        synchronized(h) {
            h.count += 1
            h.lastTs = now
            return h.count
        }
    }

    private fun decayDrainHit(mint: String, now: Long) {
        val h = drainHits[mint] ?: return
        synchronized(h) {
            if ((now - h.lastTs) > HIT_DECAY_MS && h.count > 0) {
                h.count = 0
            }
        }
    }

    private fun clearDrainHit(mint: String) {
        drainHits.remove(mint)
    }
}
