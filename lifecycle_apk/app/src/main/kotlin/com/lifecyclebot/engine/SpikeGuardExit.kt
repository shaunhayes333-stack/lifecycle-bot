package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.SolanaWallet
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6120 — SpikeGuardExit
 *
 * OPERATOR REPORT (Feb 2026): "The open positions panel showed ansem as a
 * 1000% gain. It sold at that price but then the result was tiny." Same
 * pattern seen on RUNNER (+3358% mark peak → collapsed to -87.3% while the
 * exit sat on Jupiter looking for a full-size fill route).
 *
 * ROOT CAUSE (traced end-to-end):
 *   1. Open Positions UI paints PnL from DEXSCREENER_WS wick ticks.
 *   2. Executor.getActualPricePublic() returns a Jupiter-verified full-bag
 *      price. On thin pools it is FAR below the wick.
 *   3. peakGainPct ratchets off the Jupiter-verified value, not the wick.
 *      So the +3358% mark peak never causes peakGainPct to climb past
 *      ~+50-100%, and QUICK_RUNNER / TICK_PROFIT_LOCK never fire on the
 *      spike. By the time the price collapses, the exit finally trips
 *      catastrophic at -30 to -90%.
 *   4. Even when an exit fires at peak, one full-size Jupiter sell into a
 *      thin pool routes at collapsed price. What CAN fill is a chain of
 *      smaller partials each of which takes a slice of the tiny liquidity.
 *
 * DESIGN — three complementary guards, all fire off the raw WS/mark tick:
 *
 *   A) MARK PEAK RATCHET (per-mint, independent of pos.peakGainPct).
 *      We keep our own peak based on the raw DexScreener price so a wick
 *      registers even when Jupiter disagrees.
 *
 *   B) SPIKE CAPTURE ARM. When mark peak crosses +500% we ARM the ladder.
 *      When mark peak >= +1000% we go straight to FULL EXIT via chunked
 *      partials, bypassing any beOk / style-hold / min-hold gate.
 *
 *   C) CHUNKED EXIT LADDER. Instead of one full-size sell that fails on
 *      thin pools, fire four partials with progressive slippage tolerance
 *      spaced 250 ms apart. Each partial goes through the executor's
 *      existing Jupiter Ultra → Metis → PumpPortal → PumpFunDirect stack.
 *      A 25% chunk fills where a 100% sell would price-impact-abort.
 *
 * Reason strings carry RISK / RECOVERY so requestPartialSell's below-BE
 * guard cannot block a mega-runner cash-out.
 *
 * Zero side effects on non-open positions. Fully re-entrant safe. Fires
 * at most once per mint per SPIKE_COOLDOWN_MS.
 */
object SpikeGuardExit {

    // ── Tunables ─────────────────────────────────────────────────────────
    /** Arm the ladder at this mark PnL%. */
    const val ARM_PEAK_PCT = 500.0

    /** Full-exit override — mark peak here bypasses any hold. */
    const val FULL_EXIT_PEAK_PCT = 1000.0

    /** Give-back from mark peak that triggers the ladder even before +1000%. */
    const val GIVE_BACK_TRIGGER_FRAC = 0.30

    /** Never fire more than once per mint inside this window. */
    private const val SPIKE_COOLDOWN_MS = 30_000L

    /** Chunk spacing so partial fills can bank between calls. */
    private const val CHUNK_SPACING_MS = 250L

    /** Chunk sizes — front-load the ladder so early fills bank the big % */
    private val CHUNK_FRACTIONS = listOf(0.30, 0.30, 0.25, 0.15)

    // ── State ────────────────────────────────────────────────────────────
    private val markPeakByMint = ConcurrentHashMap<String, Double>()
    private val lastFiredMs   = ConcurrentHashMap<String, Long>()

    // ── Public entry ─────────────────────────────────────────────────────
    /**
     * Called by BotService.openPositionTickLoop for every fresh WS tick on
     * an open position, right after the position's own peak ratchet block.
     */
    fun processTick(
        ts: TokenState,
        executor: Executor,
        wallet: SolanaWallet?,
        walletSol: Double,
        priceUsdMark: Double,
    ) {
        try {
            val pos = ts.position
            if (!pos.isOpen || pos.entryPrice <= 0.0 || priceUsdMark <= 0.0) return

            // Compute raw mark-based PnL — do NOT use Jupiter-verified price
            // here. This is the spike detector; we WANT the wick.
            val markPnlPct = (priceUsdMark - pos.entryPrice) / pos.entryPrice * 100.0
            if (!markPnlPct.isFinite()) return

            // Ratchet our private mark peak.
            val prevMarkPeak = markPeakByMint[ts.mint] ?: 0.0
            val markPeak = if (markPnlPct > prevMarkPeak) {
                markPeakByMint[ts.mint] = markPnlPct
                markPnlPct
            } else prevMarkPeak

            // Not on a runner yet — bail.
            if (markPeak < ARM_PEAK_PCT) return

            // Cooldown — one ladder per mint per window (chunks run inside).
            val nowMs = System.currentTimeMillis()
            val lastFired = lastFiredMs[ts.mint] ?: 0L
            if (nowMs - lastFired < SPIKE_COOLDOWN_MS) return

            // Decide the trigger reason.
            val fullExit = markPeak >= FULL_EXIT_PEAK_PCT
            val giveBack = markPeak - markPnlPct
            val giveBackFrac = if (markPeak > 0.0) giveBack / markPeak else 0.0
            val giveBackTrigger = giveBackFrac >= GIVE_BACK_TRIGGER_FRAC

            if (!fullExit && !giveBackTrigger) {
                // Still ratcheting up under the +1000% threshold. Nothing
                // to do this tick.
                return
            }

            lastFiredMs[ts.mint] = nowMs

            val trigger = when {
                fullExit    -> "FULL_EXIT_PEAK_${markPeak.toInt()}"
                giveBackTrigger -> "GIVE_BACK_${(giveBackFrac * 100).toInt()}PCT_PEAK_${markPeak.toInt()}"
                else        -> "ARM_${markPeak.toInt()}"
            }

            try {
                ForensicLogger.lifecycle(
                    "SPIKE_GUARD_FIRE_6120",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} markPeak=${"%.1f".format(markPeak)}%" +
                        " markNow=${"%.1f".format(markPnlPct)}%" +
                        " posPeak=${"%.1f".format(pos.peakGainPct)}%" +
                        " giveBackFrac=${"%.2f".format(giveBackFrac)}" +
                        " trigger=$trigger" +
                        " markSrc=${ts.lastPriceSource}",
                )
                PipelineHealthCollector.labelInc("SPIKE_GUARD_FIRE_6120")
                if (fullExit)         PipelineHealthCollector.labelInc("SPIKE_GUARD_FULL_EXIT_6120")
                if (giveBackTrigger)  PipelineHealthCollector.labelInc("SPIKE_GUARD_GIVEBACK_6120")
            } catch (_: Throwable) {}

            fireChunkedLadder(ts, executor, wallet, walletSol, markPeak, markPnlPct, trigger)
        } catch (_: Throwable) {
            // Never break the tick loop.
        }
    }

    /**
     * Fire the 4-chunk ladder on a background thread so we don't stall the
     * 1Hz tick loop. Each chunk uses executor.requestPartialSell which
     * already handles Jupiter Ultra → Metis → PumpPortal → PumpFunDirect
     * escalation internally and applies the executor's own progressive
     * slippage ladder inside each call.
     *
     * Reason strings include RECOVERY so requestPartialSell's below-BE
     * partial-block guard does not veto the ladder if the mark collapses
     * mid-ladder (we still want to bank whatever fills).
     */
    private fun fireChunkedLadder(
        ts: TokenState,
        executor: Executor,
        wallet: SolanaWallet?,
        walletSol: Double,
        markPeak: Double,
        markNow: Double,
        trigger: String,
    ) {
        Thread({
            try {
                var i = 1
                for (frac in CHUNK_FRACTIONS) {
                    // If earlier chunks already closed the position out we
                    // just stop; requestPartialSell no-ops on qtyToken<=0.
                    if (!ts.position.isOpen) break

                    val reason = "SPIKE_GUARD_CHUNK${i}_OF_${CHUNK_FRACTIONS.size}_" +
                        "PEAK${markPeak.toInt()}_NOW${markNow.toInt()}_" +
                        trigger + "_RECOVERY"
                    try {
                        ForensicLogger.lifecycle(
                            "SPIKE_GUARD_CHUNK_FIRE_6120",
                            "mint=${ts.mint.take(10)} sym=${ts.symbol} chunk=$i/${CHUNK_FRACTIONS.size}" +
                                " frac=${"%.2f".format(frac)} reason=$reason",
                        )
                        PipelineHealthCollector.labelInc("SPIKE_GUARD_CHUNK_FIRE_6120")
                    } catch (_: Throwable) {}

                    try {
                        executor.requestPartialSell(ts, frac, reason, wallet, walletSol)
                    } catch (e: Throwable) {
                        try {
                            ForensicLogger.lifecycle(
                                "SPIKE_GUARD_CHUNK_ERR_6120",
                                "mint=${ts.mint.take(10)} chunk=$i err=${e.message?.take(80) ?: "?"}",
                            )
                            PipelineHealthCollector.labelInc("SPIKE_GUARD_CHUNK_ERR_6120")
                        } catch (_: Throwable) {}
                    }

                    // Space chunks so a partial fill can bank between calls.
                    try { Thread.sleep(CHUNK_SPACING_MS) } catch (_: InterruptedException) { break }
                    i++
                }
            } catch (_: Throwable) {
                // Never propagate; ladder is fire-and-forget.
            }
        }, "SpikeGuardLadder-${ts.mint.take(6)}").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Diagnostic accessor for the UI — allows the Open Positions panel to
     * render "PEAK +XXXX% (mark) vs +YYY% (exec)" so operator sees the
     * divergence at a glance.
     */
    fun markPeakFor(mint: String): Double = markPeakByMint[mint] ?: 0.0

    /**
     * Called by BotService when a position closes so state doesn't leak.
     */
    fun clearMint(mint: String) {
        markPeakByMint.remove(mint)
        lastFiredMs.remove(mint)
    }
}
