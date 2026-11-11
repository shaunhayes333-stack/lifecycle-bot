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
    /** V5.0.8681 — one ticket is never more than this share of live equity, whatever the budget floor allows. */
    private const val MAX_SINGLE_TICKET_FRAC_8681 = 0.40
    /** V5.0.8681 — the lane whose learned pockets judge a runner ticket: a ticket is a runner bet held on runner exits. */
    const val POCKET_LANE_8681 = "MOONSHOT"
    private val pocketKind8681 = AtomicLong(0)

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

    /**
     * Pure (V5.0.8681): the budget with a one-ticket floor. 8680 live: equity 0.106 SOL put the 10 % budget at 0.0106 SOL, under
     * the smallest executable ticket (~0.027-0.037 SOL), so no ticket could ever issue (tickets8073 issued=0) while every other
     * live buy was already at that minimum. A wallet may hold ONE ticket at the minimum when it is at most
     * [MAX_SINGLE_TICKET_FRAC_8681] of equity; above that floor the 10 % budget governs as before.
     */
    fun fitsFluid8681(openTicketSol: Double, ticketSol: Double, equitySol: Double): Boolean {
        if (!equitySol.isFinite() || equitySol <= 0.0 || !ticketSol.isFinite() || ticketSol <= 0.0) return false
        if (ticketSol > equitySol * MAX_SINGLE_TICKET_FRAC_8681) return false
        return openTicketSol + ticketSol <= maxOf(equitySol * BUDGET_FRAC, ticketSol)
    }

    /**
     * Pure (V5.0.8681): is the coin in the ticket group? The fixed 8072 group (graduated PumpSwap, $10k-$100k), or a coin state
     * the pocket ledger (PocketLedger8610, learned from every forward-labelled decision) currently calls PAYING on the runner
     * lane: the group follows where the money is instead of one hard-coded band.
     */
    fun groupFluid8681(legacyGroup: Boolean, pocket: com.lifecyclebot.engine.truth.PocketLedger8610.Call?, cohortPays8695: Boolean = false): Boolean =
        legacyGroup || pocket == com.lifecyclebot.engine.truth.PocketLedger8610.Call.PAYS || cohortPays8695

    /**
     * V5.0.8695 — the coin's own forward-label cohort on the runner lane passes the runner bar (LiveEdgeGate7877
     * .runnerCohortAllows: 30+ graded, mean - SE > 0 net of cost, or a positive mean with a 10 %+ runner rate): its
     * source x lane x cap x age cell, or the lane's cap band while that cell is still thin. 8689: 83 of 84 ticket asks
     * were refused for kind (only graduated PumpSwap $10k-$100k or a PAYING pocket qualified) and no ticket issued, while
     * the brief's own fix queue read "pays only as many small entries held to the runner exit (RunnerTicket8073)".
     */
    private fun cohortPays8695(ts: TokenState, nowMs: Long): Boolean = try {
        val gate = com.lifecyclebot.engine.truth.LiveEdgeGate7877
        val labels = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
        val cell = labels.cellStatFor(ts, POCKET_LANE_8681, nowMs)
        if (cell != null && cell.n60 >= COHORT_MIN_N_8695) gate.runnerCohortAllows(cell)
        else gate.runnerCohortAllows(labels.bandStatFor8019(ts, POCKET_LANE_8681))
    } catch (_: Throwable) { false }
    private const val COHORT_MIN_N_8695 = 30
    private val cohortKind8695 = AtomicLong(0)

    private fun openTicketSol(): Double = try {
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
            .filter { it.mode.equals("live", true) && tickets.containsKey(it.mint) }
            .sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
    } catch (_: Throwable) { Double.POSITIVE_INFINITY }

    /** LiveEdgeGate7877: may [ts] take a runner ticket in place of proven edge? Issues the ticket when it may. */
    fun admits8073(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean = admitsOrIssues8538(ts, nowMs, issue = true)

    /** V5.0.8538 — would [admits8073] admit [ts] now? The same checks, no ticket issued and no refusal counted. */
    fun wouldAdmit8538(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean = admitsOrIssues8538(ts, nowMs, issue = false)

    private fun admitsOrIssues8538(ts: TokenState, nowMs: Long, issue: Boolean): Boolean {
        ensureSubscribed()
        if (ts.mint.isBlank()) return false
        if (isTicket8073(ts.mint)) return true
        val curve = try { com.lifecyclebot.engine.truth.CurveReserves7951.realSolIfActive8046(ts.mint) != null } catch (_: Throwable) { false }
        val dex = ts.lastPriceDex.ifBlank { try { ts.tokenMap.dexId } catch (_: Throwable) { "" } }
        val venue = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.memeVenue8063(ts.mint, dex, curve)
        val mcap = try { com.lifecyclebot.engine.truth.TrustedMcap8019.mcap8019(ts) } catch (_: Throwable) { 0.0 }
        val legacyGroup = inGroup8073(venue, mcap)
        val pocket8681 = if (legacyGroup) null
            else try { com.lifecyclebot.engine.truth.PocketLedger8610.verdict8610(ts, POCKET_LANE_8681, nowMs) } catch (_: Throwable) { null }
        val cohort8695 = !legacyGroup && pocket8681 != com.lifecyclebot.engine.truth.PocketLedger8610.Call.PAYS && cohortPays8695(ts, nowMs)
        if (!groupFluid8681(legacyGroup, pocket8681, cohort8695)) { if (issue) refusedKind.incrementAndGet(); return false }
        if (nowMs - lastIssuedMs < TICKET_GAP_MS) { if (issue) refusedBudget.incrementAndGet(); return false }
        val walletSol = try { WalletManager.cachedSolBalance() } catch (_: Throwable) { 0.0 }
        val equity = try { com.lifecyclebot.engine.truth.LiveRiskPolicy7807.liveEquitySol(walletSol) } catch (_: Throwable) { walletSol }
        val ticketSol = try { com.lifecyclebot.engine.truth.NetEdge8030.DEFAULT_TICKET_SOL } catch (_: Throwable) { 0.03 }
        if (!fitsFluid8681(openTicketSol(), ticketSol, equity)) { if (issue) refusedBudget.incrementAndGet(); return false }
        // V5.0.8310 — the ticket door reads its own verified live record (SelectionDoors8310 D1): suspended while it loses.
        if (!com.lifecyclebot.engine.truth.SelectionDoors8310.ticketMayIssue8310(nowMs)) { if (issue) refusedRecord8310.incrementAndGet(); return false }
        if (!issue) return true
        tickets[ts.mint] = nowMs
        lastIssuedMs = nowMs
        issued.incrementAndGet()
        if (cohort8695) cohortKind8695.incrementAndGet() else if (!legacyGroup) pocketKind8681.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RUNNER_TICKET_ATTEMPT_8092") } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc("RUNNER_TICKET_8073_ISSUED")
            ForensicLogger.lifecycle("RUNNER_TICKET_8073", "mint=${ts.mint.take(10)} sym=${ts.symbol} venue=$venue mcap=${mcap.toLong()} equity=${"%.4f".format(equity)} group=${if (legacyGroup) "PUMPSWAP_10K_100K" else if (cohort8695) "COHORT_PAYS_8695" else "POCKET_PAYS_8681"}")
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
            "pocketGroup8681=${pocketKind8681.get()} cohortGroup8695=${cohortKind8695.get()} rule=PumpSwap \$10k-\$100k or a pocket PocketLedger8610 calls PAYS ($POCKET_LANE_8681), <= ${(BUDGET_FRAC * 100).toInt()}% of equity (one ticket floor <= ${(MAX_SINGLE_TICKET_FRAC_8681 * 100).toInt()}% of equity), 1 per 2 min, held on lifecycle rules"
}
