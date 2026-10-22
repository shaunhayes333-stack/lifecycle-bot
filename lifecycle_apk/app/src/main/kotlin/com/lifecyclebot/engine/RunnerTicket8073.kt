package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8072 — RUNNER TICKETS: a small, capped budget of unproven buys where the 1000x lives.
 *
 * Owner: "yes. what ever makes the most sense to integrate with whats there and our growth mentality" — to the
 * question whether to fund tiny unproven buys in the best honest-label group before the data has proven it.
 * 5.0.8064's honest books: graduated pump.fun coins on PumpSwap at $10k-$100k read +28.7% (SHITCOIN n34) and +30.7%
 * (CORE n32) at 5 minutes, 15% of them winning big; coins still on the curve under $10k read -36% to -41%. Live
 * money otherwise waits for proof (LIVE_PROVEN_SETUP_ONLY_8059), and a 1000x is over before a cell proves itself.
 *
 * A ticket is a live buy, without proven edge, of a coin that is:
 *   - graduated to PumpSwap (ForwardReturnLabeler7731.memeVenue8063 == PUMPSWAP), not on the curve;
 *   - at a trusted market cap of $10k-$100k (TrustedMcap8019);
 *   - and passes every other gate after the proof gate (no chasing a spike 8065, dead launch, Cortex, net edge,
 *     lane fit, the buy door).
 * The budget: open tickets' cost basis at most [BUDGET_FRAC] of live equity, at most one new ticket per
 * [TICKET_GAP_MS]. A ticket is held under the lifecycle rules (LifecycleHold8065: -40% floor, half at 2x, 30% at 5x
 * and 10x, then the trend break) — so most tickets lose 40% and the few that run pay for all of them.
 * The ticket record (paid / died) is reported so the budget is judged on outcomes, not hope.
 */
object RunnerTicket8073 {
    private const val BUDGET_FRAC = 0.10
    private const val MCAP_LO = 10_000.0
    private const val MCAP_HI = 100_000.0
    private const val TICKET_GAP_MS = 2L * 60_000L
    private const val PENDING_MS = 10L * 60_000L

    /** mint -> when its ticket was issued. */
    private val tickets = ConcurrentHashMap<String, Long>()
    @Volatile private var lastIssuedMs = 0L
    private val issued = AtomicLong(0)
    /** V5.0.8092 — tickets that actually CONFIRMED a live fill (attempts that never filled are not economic tickets). */
    internal val confirmed8092 = AtomicLong(0)
    private val refusedBudget = AtomicLong(0)
    private val refusedKind = AtomicLong(0)
    private val refusedRecord8310 = AtomicLong(0)
    private val paid = AtomicLong(0)
    private val died = AtomicLong(0)

    /** Pure: is a coin of [venue] at [mcapUsd] in the ticket group? */
    fun inGroup8073(venue: String, mcapUsd: Double): Boolean =
        venue == "PUMPSWAP" && mcapUsd.isFinite() && mcapUsd in MCAP_LO..MCAP_HI

    /** Pure: does one more ticket of [ticketSol] fit, with [openTicketSol] already open, inside [equitySol]'s budget? */
    fun fits8073(openTicketSol: Double, ticketSol: Double, equitySol: Double): Boolean =
        equitySol.isFinite() && equitySol > 0.0 && openTicketSol + ticketSol.coerceAtLeast(0.0) <= equitySol * BUDGET_FRAC

    private fun openTicketSol(): Double = try {
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
            .filter { it.mode.equals("live", true) && tickets.containsKey(it.mint) }
            .sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
    } catch (_: Throwable) { Double.POSITIVE_INFINITY }

    /** LiveEdgeGate7877: may [ts] take a runner ticket in place of proven edge? Issues the ticket when it may. */
    fun admits8073(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean {
        ensureSubscribed()
        if (ts.mint.isBlank()) return false
        if (isTicket8073(ts.mint)) return true
        val curve = try { com.lifecyclebot.engine.truth.CurveReserves7951.realSolIfActive8046(ts.mint) != null } catch (_: Throwable) { false }
        val dex = ts.lastPriceDex.ifBlank { try { ts.tokenMap.dexId } catch (_: Throwable) { "" } }
        val venue = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.memeVenue8063(ts.mint, dex, curve)
        val mcap = try { com.lifecyclebot.engine.truth.TrustedMcap8019.mcap8019(ts) } catch (_: Throwable) { 0.0 }
        if (!inGroup8073(venue, mcap)) { refusedKind.incrementAndGet(); return false }
        if (nowMs - lastIssuedMs < TICKET_GAP_MS) { refusedBudget.incrementAndGet(); return false }
        val walletSol = try { WalletManager.cachedSolBalance() } catch (_: Throwable) { 0.0 }
        val equity = try { com.lifecyclebot.engine.truth.LiveRiskPolicy7807.liveEquitySol(walletSol) } catch (_: Throwable) { walletSol }
        val ticketSol = try { com.lifecyclebot.engine.truth.NetEdge8030.DEFAULT_TICKET_SOL } catch (_: Throwable) { 0.03 }
        if (!fits8073(openTicketSol(), ticketSol, equity)) { refusedBudget.incrementAndGet(); return false }
        // V5.0.8310 — the ticket door reads its own verified live record (SelectionDoors8310 D1): suspended while it loses.
        if (!com.lifecyclebot.engine.truth.Entry8114Mode8326.legacyScope8326() &&  // V5.0.8326 — no 8310 suspension at 8114
            !com.lifecyclebot.engine.truth.SelectionDoors8310.ticketMayIssue8310(nowMs)) { refusedRecord8310.incrementAndGet(); return false }
        tickets[ts.mint] = nowMs
        lastIssuedMs = nowMs
        issued.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RUNNER_TICKET_ATTEMPT_8092") } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc("RUNNER_TICKET_8073_ISSUED")
            ForensicLogger.lifecycle("RUNNER_TICKET_8073", "mint=${ts.mint.take(10)} sym=${ts.symbol} venue=$venue mcap=${mcap.toLong()} equity=${"%.4f".format(equity)}")
        } catch (_: Throwable) {}
        return true
    }

    /**
     * V5.0.8092 — a confirmed live fill of [positionId] for [mint]. Only a canonical LIVE position with a
     * positive entry cost is an economic ticket; an attempt that never filled stays pending and expires.
     * Wired from EntryStrategySnapshot6450.setEntry (the immutable position write boundary).
     */
    fun confirm8092(positionId: String, mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        val pos = try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.getPosition(positionId)
        } catch (_: Throwable) { null } ?: return
        if (pos.mint != mint || !pos.mode.equals("live", true) || !(pos.entryCostSol > 0.0)) return
        try {
            tickets.putIfAbsent(mint, nowMs)
            confirmed8092.incrementAndGet()
            PipelineHealthCollector.labelInc("RUNNER_TICKET_CONFIRMED_8092")
        } catch (_: Throwable) {}
    }

    /** Is [mint] a runner ticket (issued and either still pending its buy or now an open position)? */
    fun isTicket8073(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = tickets[mint] ?: return false
        if (nowMs - at <= PENDING_MS) return true
        val open = try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().any { it.mint == mint && it.mode.equals("live", true) }
        } catch (_: Throwable) { true }
        if (!open) tickets.remove(mint)
        return open
    }

    @Volatile private var subscribed = false

    private fun ensureSubscribed() {
        if (subscribed) return
        subscribed = true
        try {
            com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450.subscribe { e ->
                if (e.mode.equals("live", true) && onClose8073(e.mint, e.netRealizedPnlSol))
                    com.lifecyclebot.engine.truth.SelectionDoors8310.noteTicketClose8310(e.positionId, e.mode, e.economicFinality, e.netRealizedPnlSol, e.settledAtMs)  // V5.0.8310
            }
        } catch (_: Throwable) { subscribed = false }
    }

    /** A ticket's live position closed: was it paid or did it die? */
    private fun onClose8073(mint: String, realizedPnlSol: Double): Boolean {
        if (tickets.remove(mint) == null) return false
        if (realizedPnlSol.isFinite() && realizedPnlSol > 0.0) paid.incrementAndGet() else died.incrementAndGet()
        return true
    }

    fun statusLine(): String =
        "issued=${issued.get()} confirmed=${confirmed8092.get()} open=${tickets.size} record[paid=${paid.get()} died=${died.get()}] refused[kind=${refusedKind.get()} budget=${refusedBudget.get()} record8310=${refusedRecord8310.get()}] " +
            "rule=PumpSwap \$10k-\$100k, <= ${(BUDGET_FRAC * 100).toInt()}% of equity, 1 per 2 min, held on lifecycle rules"
}
