package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8144 — the live doors of ExitTruthArbiter8144 (the pure ranking, ticket and capture books live there).
 *
 *  - [Tick8144]          SpikeCapture7943.rapidMark: every overlay of one mark tick (lifecycle hold, spike / runner
 *                        ladder, chart reader, runner structure exits, structure tracker) OFFERS its exit; the one
 *                        authority picks one and the tick's ticket is consumed once. A lifecycle hold still runs only
 *                        its own exits, except that a tier-1 exit offered beside it is never held.
 *  - [admitTranche8144]  RunnerExitLive8136.budget8136 / recoveryTranche8136 (Executor.requestPartialSell, the
 *                        Executor.checkProfitLock profit lock / capital recovery / wallet-growth harvest, the spike
 *                        ladder): a discretionary tranche from any path passes only while no OTHER chosen exit holds
 *                        the position's ticket.
 *  - [claimProtective8144] RunnerGrab7967.heldExit7967: a structure / peak / hard-stop exit takes the ticket, so a
 *                        ladder rung on the same print waits for it.
 */
object ExitTruthArbiterLive8144 {
    private const val MAX_COUNTER_KEYS_8144 = 48

    /** A ticket outlives its claim grace only while a sale for the mint is actually in flight (lock / close lease held). */
    private val tickets8144 = ExitTruthArbiter8144.TicketBook8144(inFlight = { mint ->
        com.lifecyclebot.engine.sell.SellExecutionLocks.isLocked(mint) || com.lifecyclebot.engine.sell.CloseLease.sellInFlight7948(mint)
    })
    private val captures8144 = ExitTruthArbiter8144.CaptureBook8144()
    private val tickSeq = AtomicLong(0L)
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    private fun count8144(label: String) {
        if (counts.size < MAX_COUNTER_KEYS_8144 || counts.containsKey(label)) counts.computeIfAbsent(label) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
    }

    private fun key8144(ts: TokenState): String = "${ts.mint}|${ts.position.entryTime}"

    private fun gross8144(ts: TokenState, px: Double?): Double {
        val e = ts.position.entryPrice
        val p = px ?: ts.lastPrice
        return if (e > 0.0 && p > 0.0) (p / e - 1.0) * 100.0 else Double.NaN
    }

    private fun capitalRecovered8144(ts: TokenState): Boolean =
        if (ts.position.isPaperPosition) ts.position.capitalRecovered
        else try { TailBag8034.capitalRecovered8090(ts.position.positionId, ts.mint) } catch (_: Throwable) { false }

    /** What the authority knows about [ts]: its sealed strategy and entry style, the verified peak, confirmed recovery. */
    fun inputsFor8144(ts: TokenState, px: Double?): ExitTruthArbiter8144.Inputs8144 {
        val pos = ts.position
        val snap = try {
            pos.positionId.takeIf { it.isNotBlank() }?.let { com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.snapshot(it) }
        } catch (_: Throwable) { null }
        val strategy = snap?.takeIf { it.mint == ts.mint }?.let { s -> s.entryTactic.ifBlank { s.entryStrategyPid } }.orEmpty()
        val style = try { TradeExitContract8077.sealedStyle8136(ts) } catch (_: Throwable) { "" }
        // Paper decides as live will: the lane question ignores the paper flag (paper never sells anything real).
        val hosts = try { TradeExitContract8077.runnerFromEntryLane8104(pos.tradingMode, false) } catch (_: Throwable) { false }
        val sellCost = try { RunnerExitLive8136.sellCostPct8136(ts) } catch (_: Throwable) { 3.0 }
        return ExitTruthArbiter8144.Inputs8144(
            sealedStrategy = strategy,
            entryStyle = style,
            laneHostsRunners = hosts,
            confirmedPeakPct = pos.peakGainPct,
            capitalRecoveredConfirmed = capitalRecovered8144(ts),
            pnlPct = gross8144(ts, px),
            sellCostPct = sellCost,
            remainingShare = ((100.0 - pos.partialSoldPct) / 100.0).coerceIn(0.0, 1.0),
        )
    }

    /** RunnerExitLive8136: may this tranche sell now? (true for full exits and protective ranks, which take the ticket). */
    fun admitTranche8144(ts: TokenState, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val pos = ts.position
        if (ts.mint.isBlank() || !pos.isOpen) return true
        val ok = tickets8144.admit8144(ts.mint, pos.entryTime, reason, pos.partialSoldPct, nowMs)
        if (!ok) count8144("EXIT_TICKET_HELD_TRANCHE_WAITS_8144")
        return ok
    }

    /** SpikeCapture7943.onMark: does a chosen exit other than [reason]'s tranche hold [ts]'s ticket right now? */
    fun ticketHeldByOther8144(ts: TokenState, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        ts.position.isOpen && tickets8144.heldByOther8144(ts.mint, ts.position.entryTime, reason, ts.position.partialSoldPct, nowMs)

    /** SpikeCapture7943.rearm: a tranche whose sale did not land gives the ticket back (only if it still holds it). */
    fun releaseTranche8144(mint: String, reason: String) {
        if (mint.isNotBlank()) tickets8144.release8144(mint, reason)
    }

    /** RunnerGrab7967.heldExit7967: a protective exit takes the ticket before it is dispatched. */
    fun claimProtective8144(ts: TokenState, reason: String, nowMs: Long = System.currentTimeMillis()) {
        if (!ts.position.isOpen || !ExitTruthArbiter8144.protective8144(reason)) return
        tickets8144.admit8144(ts.mint, ts.position.entryTime, reason, ts.position.partialSoldPct, nowMs)
    }

    /** SpikeCapture7943: a print of a mint the bot no longer holds (post-exit opportunity of its last full exit). */
    fun notePostExit8144(mint: String, px: Double, nowMs: Long = System.currentTimeMillis()) {
        try { captures8144.notePostExitPx8144(mint, px, nowMs) } catch (_: Throwable) {}
    }

    /** One mark tick of one held position: overlays offer, [dispatch8144] decides once. */
    class Tick8144(private val ts: TokenState, private val dispatch: (TokenState, Double, String) -> Unit) {
        private val tick = ExitTruthArbiterLive8144.tickSeq.incrementAndGet()
        private val startedMs = System.currentTimeMillis()
        private val offered = ArrayList<ExitTruthArbiter8144.Intent8144>(4)

        /** The overlays' sell door for this tick: nothing is sold until [dispatch8144]. */
        val offer8144: (TokenState, Double, String) -> Unit = { _, fraction, reason ->
            synchronized(offered) { offered += ExitTruthArbiter8144.Intent8144(reason, fraction) }
        }

        /** Beside a lifecycle hold only a tier-1 exit is offered (the hold runs its own exits; tier 1 is never held). */
        val tier1Only8144: (TokenState, Double, String) -> Unit = { t, fraction, reason ->
            if (RunnerExitContract8136.emergency8136(reason)) offer8144(t, fraction, reason)
        }

        /** Decide once and dispatch the winner (null = hold this tick). */
        fun dispatch8144(px: Double?): ExitTruthArbiter8144.Intent8144? {
            val intents = synchronized(offered) { offered.toList() }
            try { noteMark8144(px) } catch (_: Throwable) {}
            if (intents.isEmpty()) return null
            return ExitTruthArbiterLive8144.settle8144(ts, tick, startedMs, intents, px, dispatch)
        }

        private fun noteMark8144(px: Double?) {
            val pos = ts.position
            if (!pos.isOpen) return
            val key = ExitTruthArbiterLive8144.key8144(ts)
            ExitTruthArbiterLive8144.captures8144.noteMark8144(key, ExitTruthArbiterLive8144.gross8144(ts, px), pos.peakGainPct)
            if (pos.isPaperPosition || pos.positionId.isBlank()) return
            val canon = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.getPosition(pos.positionId) ?: return
            if (canon.mint != ts.mint) return
            val proceeds = RecoveryFallbackLedger8141.effectiveProceeds8141(canon.realizedProceedsSol,
                RecoveryFallbackLedger8141.confirmedFallbackSol8141(pos.positionId)) - canon.feesSol.coerceAtLeast(0.0)
            ExitTruthArbiterLive8144.captures8144.noteRealised8144(key, proceeds.coerceAtLeast(0.0), canon.soldCostBasisSol)
        }
    }

    private fun spikeRung8144(reason: String): Boolean = reason.startsWith("SPIKE_CAPTURE_7943_T")

    private fun settle8144(
        ts: TokenState, tick: Long, startedMs: Long, intents: List<ExitTruthArbiter8144.Intent8144>, px: Double?,
        dispatch: (TokenState, Double, String) -> Unit,
    ): ExitTruthArbiter8144.Intent8144? {
        val pos = ts.position
        val nowMs = System.currentTimeMillis()
        val d = ExitTruthArbiter8144.decide8144(intents, inputsFor8144(ts, px))
        for (r in d.refused) count8144("EXIT_ARBITER_REFUSED_" + r.substringBefore(':'))
        val act = d.act
        val actFull = act != null && act.fraction >= 1.0 - 1e-9
        // A rung the decision beat or refused is not lost: it waits for the next print (no re-arm spent) and hands back
        // any ticket it claimed at its budget door during this tick.
        for (i in intents) {
            if (act != null && i.reason == act.reason) continue
            tickets8144.releaseIfClaimedSince8144(ts.mint, i.reason, startedMs)
            if (!actFull && spikeRung8144(i.reason)) SpikeCapture7943.restoreTier8144(ts)
        }
        if (intents.size > 1) count8144("EXIT_ARBITER_ONE_OF_MANY_8144")
        if (act == null) { count8144("EXIT_ARBITER_HELD_8144"); return null }
        val rank = d.rank ?: return null
        if (!tickets8144.consumeTick8144(ts.mint, pos.entryTime, tick, act.reason, pos.partialSoldPct, nowMs)) {
            count8144("EXIT_TICKET_REFUSED_8144_${rank.name}")
            if (spikeRung8144(act.reason)) SpikeCapture7943.restoreTier8144(ts)
            return null
        }
        if (actFull) captures8144.noteExit8144(ts.mint, key8144(ts), gross8144(ts, px), pos.entryPrice, nowMs)
        try {
            dispatch(ts, act.fraction, act.reason)
        } catch (failure: Exception) {
            tickets8144.release8144(ts.mint, act.reason)
            if (spikeRung8144(act.reason)) SpikeCapture7943.restoreTier8144(ts)
            count8144("EXIT_ARBITER_DISPATCH_FAILED_8144")
            return null
        }
        count8144("EXIT_ARBITER_ACT_8144_${rank.name}")
        return act
    }

    fun statusLine8144(): String {
        val caps = captures8144.all8144().values
        val mfe = caps.mapNotNull { c -> c.mfePct.takeIf { it.isFinite() } }
        val realised = caps.mapNotNull { c -> c.realisedCapturePct.takeIf { it.isFinite() } }
        val missed = caps.mapNotNull { c -> c.postExitOpportunityPp.takeIf { it.isFinite() } }
        fun mean(xs: List<Double>): String = if (xs.isEmpty()) "-" else "%.1f".format(xs.average())
        val top = counts.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "tickets=${tickets8144.size8144()} positions=${caps.size} meanMfe=${mean(mfe)}% meanRealisedCapture=${mean(realised)}% " +
            "meanPostExitPp=${mean(missed)} · [$top]"
    }
}
