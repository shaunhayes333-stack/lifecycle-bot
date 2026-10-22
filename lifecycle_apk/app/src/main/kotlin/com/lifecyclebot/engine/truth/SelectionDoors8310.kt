package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8310 §SELECTION_ON_THE_REAL_RECORD. Owner, live 5.0.8306: "It's still buying rubbish mostly. Where have the 500%+
 * winners gone?" 42 runners in 24 h, 7 held, 35 missed; live buys mostly losers. SHITCOIN labels read +38.8 % while its
 * live closes read -6.0 %; the paying pond's edge is entirely its runner tail (non-runners <= +7.5 %, runners >= +687 %).
 * The doors below admitted live money on label records the bank contradicts. Each now checks the real record first:
 *
 *  D1 RUNNER TICKET (RunnerTicket8073): an UNPROVEN live buy of any graduated PumpSwap coin at $10k-$100k (2bNrYy,
 *     MOONSHOT POST_MIGRATION_HOLD at $23k, -85.7 % in 95 s, 85 % of the session's loss). Its "paid / died" record was
 *     in-memory, reset on restart, and never consulted. It is now persisted from verified live closes, and once
 *     [TICKET_MIN_N_8310]+ tickets have closed with the window's net SOL <= 0 the door is suspended, opening for one probe
 *     per [TICKET_PROBE_MS_8310] so the record can recover.
 *  D2 SETUP LIVE RECORD NEGATIVE: a setup whose own verified live closes (Cortex7885.setupRealised8187: 2+ closes, mean
 *     below zero) lose is not bought again on its labels. LiveTruth8064's 5-close / 6-hour refusal never saw a
 *     handful-a-day lane. (The same threshold already revokes its compounding, 8296.)
 *  D3 TAIL CELL, UNCONFIRMED, LOSING LANE: in a lane whose recent verified live closes are losing (LiveConfidence8296),
 *     a coin whose label cell pays only through its runners (CellTail8303 TAIL_DEPENDENT: median <= 0, non-runners under a
 *     tenth of the mean) is not bought before its run confirms — the lane's bank shows the early lottery does not pay
 *     after costs. The run is bought when it confirms (RunnerPlay8018 ticket / RunnerGrab7967 grab).
 *  D4 CHASE ON A LABEL PROOF: in such a losing lane the spike-chase rule (8065) no longer stands down for a label-only
 *     cell / kind proof (cellOrKindProven8094); runner grabs and the chase rule's own tail audit still stand it down.
 *
 * Runner-confirmed entries (an active RunnerPlay8018 re-entry ticket or a RunnerGrab7967 grab) are their own evidence with
 * their own closed loops and are exempt from D2/D3. Nothing here admits a coin or touches hard safety; every door
 * reopens on its own as the real record turns.
 */
object SelectionDoors8310 {
    private const val TICKET_MIN_N_8310 = 3
    const val TICKET_WINDOW_8310 = 10
    const val TICKET_PROBE_MS_8310 = 6L * 3_600_000L
    private const val SETUP_MIN_CLOSES_8310 = 2.0
    private const val PERSIST_KEY_8310 = "RUNNER_TICKET_RECORD_8310"
    private const val MAX_IDS_8310 = 200

    enum class TicketDoor8310 { OPEN, PROBE, SUSPENDED }

    private val ticketCloses8310 = ArrayDeque<DoubleArray>()   // [atMs, netSol], oldest first
    private val ticketIds8310 = LinkedHashSet<String>()
    @Volatile private var lastProbeMs8310 = 0L
    @Volatile private var loaded8310 = false
    private val refusals8310 = ConcurrentHashMap<String, AtomicLong>()

    // ── pure ──

    /** Pure: the runner-ticket door given its verified closes' net SOL (newest first) and the last probe time. */
    fun ticketDoor8310(netSolNewestFirst: List<Double>, lastProbeMs: Long, nowMs: Long): TicketDoor8310 {
        val xs = netSolNewestFirst.filter { it.isFinite() }.take(TICKET_WINDOW_8310)
        if (xs.size < TICKET_MIN_N_8310) return TicketDoor8310.OPEN
        if (xs.sum() > 0.0) return TicketDoor8310.OPEN
        return if (nowMs - lastProbeMs >= TICKET_PROBE_MS_8310) TicketDoor8310.PROBE else TicketDoor8310.SUSPENDED
    }

    /** Pure: is a setup's own realised live record ([closes], [meanPct]) losing? */
    fun setupLiveLosing8310(closes: Double, meanPct: Double): Boolean =
        closes.isFinite() && closes >= SETUP_MIN_CLOSES_8310 && meanPct.isFinite() && meanPct < 0.0

    /** Pure: does a label cell pay only through its runner tail (CellTail8303)? */
    fun tailOnly8310(meanPct: Double, winRate: Double, runnerRate: Double): Boolean =
        CellTail8303.tail8303(meanPct, winRate, runnerRate).tailDependent

    // ── reads ──

    private fun runnerConfirmed8310(mint: String, nowMs: Long): Boolean =
        (try { com.lifecyclebot.engine.RunnerPlay8018.ticketActive8018(mint, nowMs) } catch (_: Throwable) { false }) ||
            (try { com.lifecyclebot.engine.RunnerGrab7967.isGrabbed7980(mint) } catch (_: Throwable) { false })

    private fun laneLosing8310(lane: String): Boolean =
        try { LiveConfidence8296.losing8296(LiveConfidence8296.recentFor8296(lane)) } catch (_: Throwable) { false }

    private fun refuse8310(door: String, lane: String): String {
        refusals8310.computeIfAbsent("$door@$lane") { AtomicLong(0) }.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SELECTION_DOOR_8310_${door}_$lane") } catch (_: Throwable) {}
        return door
    }

    /**
     * LiveEdgeGate7877, after the proven-only door admitted [ts] in [lane] as [setup]: the real-record refusal (D2 / D3), or
     * null. A RunnerTicket8073 admission is governed by its own record (D1), so D3 does not apply to it.
     */
    fun admitRefusal8310(ts: TokenState, lane: String, setup: String?, nowMs: Long = System.currentTimeMillis()): String? {
        if (runnerConfirmed8310(ts.mint, nowMs)) return null
        val ticket = try { com.lifecyclebot.engine.RunnerTicket8073.isTicket8073(ts.mint, nowMs) } catch (_: Throwable) { false }
        if (!setup.isNullOrBlank()) {
            val rr = try { com.lifecyclebot.engine.cortex.Cortex7885.setupRealised8187(lane, setup) } catch (_: Throwable) { null }
            if (rr != null && rr.size >= 2 && setupLiveLosing8310(rr[0], rr[1])) return refuse8310("SETUP_LIVE_RECORD_NEGATIVE_8310", lane)
        }
        if (!ticket && laneLosing8310(lane)) {
            val st = try { ForwardReturnLabeler7731.cellStatFor(ts, lane, nowMs) } catch (_: Throwable) { null }
            if (st != null && tailOnly8310(st.meanNet60Pct, st.winRate60, st.runnerRate60)) return refuse8310("TAIL_CELL_UNCONFIRMED_LOSING_LANE_8310", lane)
        }
        return null
    }

    /** D4 — may a label-only cell / kind proof stand the spike-chase rule down in [lane]? Not while its live closes lose. */
    fun labelStandsDownChase8310(lane: String): Boolean {
        if (!laneLosing8310(lane)) return true
        refuse8310("CHASE_LABEL_STANDDOWN_REFUSED_8310", lane)
        return false
    }

    // ── D1: the runner-ticket record ──

    /** RunnerTicket8073.admits8073, last check before issuing: may a ticket be issued now? Consumes the probe slot. */
    @Synchronized
    fun ticketMayIssue8310(nowMs: Long = System.currentTimeMillis()): Boolean {
        ensureLoaded8310()
        val door = ticketDoor8310(ticketCloses8310.toList().reversed().map { it[1] }, lastProbeMs8310, nowMs)
        return when (door) {
            TicketDoor8310.OPEN -> true
            TicketDoor8310.PROBE -> {
                lastProbeMs8310 = nowMs
                persist8310()
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("RUNNER_TICKET_PROBE_8310") } catch (_: Throwable) {}
                true
            }
            TicketDoor8310.SUSPENDED -> {
                refuse8310("RUNNER_TICKET_RECORD_LOSING_8310", "ANY")
                false
            }
        }
    }

    /** RunnerTicket8073: a ticket position closed. Only a verified live close counts (ExactStrategyPerformance7429). */
    @Synchronized
    fun noteTicketClose8310(positionId: String, mode: String, finality: String, netSol: Double, atMs: Long): Boolean {
        ensureLoaded8310()
        if (!mode.equals("LIVE", true) || !ExactStrategyPerformance7429.terminalEconomicsEligible8081(mode, finality) ||
            positionId.isBlank() || !netSol.isFinite()) return false
        if (!ticketIds8310.add(positionId)) return false
        while (ticketIds8310.size > MAX_IDS_8310) ticketIds8310.remove(ticketIds8310.first())
        ticketCloses8310.addLast(doubleArrayOf(atMs.toDouble(), netSol))
        while (ticketCloses8310.size > TICKET_WINDOW_8310) ticketCloses8310.removeFirst()
        persist8310()
        return true
    }

    fun statusLine8310(): String {
        val body = synchronized(this) {
            ensureLoaded8310()
            val xs = ticketCloses8310.toList().reversed().map { it[1] }
            "tickets[n${xs.size} net${"%+.4f".format(xs.sum())} door=${ticketDoor8310(xs, lastProbeMs8310, System.currentTimeMillis())}]"
        }
        return "selection8310[$body refused=${refusals8310.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "0" }}]"
    }

    // ── persistence (never reset) ──

    private fun ensureLoaded8310() {
        if (loaded8310) return
        if (!(try { com.lifecyclebot.engine.LearningPersistence.ready() } catch (_: Throwable) { false })) return
        loaded8310 = true
        try {
            val raw = com.lifecyclebot.engine.LearningPersistence.load(PERSIST_KEY_8310) ?: return
            val o = org.json.JSONObject(raw)
            val a = o.optJSONArray("closes")
            val rows = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
                a.optJSONArray(i)?.takeIf { it.length() >= 2 }?.let { r -> doubleArrayOf(r.optDouble(0), r.optDouble(1)) }
            }.filter { r -> r.all { it.isFinite() } }
            val merged = (rows + ticketCloses8310).sortedBy { it[0] }.takeLast(TICKET_WINDOW_8310)
            ticketCloses8310.clear(); ticketCloses8310.addAll(merged)
            lastProbeMs8310 = maxOf(lastProbeMs8310, o.optLong("lastProbeMs", 0L))
            o.optJSONArray("ids")?.let { ids -> for (i in 0 until ids.length()) ids.optString(i).takeIf { it.isNotBlank() }?.let { ticketIds8310.add(it) } }
        } catch (_: Throwable) {}
    }

    private fun persist8310() {
        if (!loaded8310) return
        try {
            val json = org.json.JSONObject()
                .put("closes", org.json.JSONArray(ticketCloses8310.map { r -> org.json.JSONArray(r.toList()) }))
                .put("ids", org.json.JSONArray(ticketIds8310.toList().takeLast(MAX_IDS_8310)))
                .put("lastProbeMs", lastProbeMs8310)
                .toString()
            com.lifecyclebot.engine.LearningPersistence.save(PERSIST_KEY_8310, json)
        } catch (_: Throwable) {}
    }
}
