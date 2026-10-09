package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7962 — the all-in round trip, measured on every live trade.
 *
 * Cheat sheet: "calculate net EV after fees, spread, impact, slippage, priority
 * costs at the actual intended size". FieldManual7715.allInCostPct is a model
 * (platform fee + priority + slippage allowance + pool impact); this measures it.
 *
 * Per finalized LIVE trade (CanonicalTradeFinalizedBus6450): the frictionless
 * round trip — the market price (StructureTracker7962's tape) at the fill and at
 * every sell leg, weighted by the tokens each leg sold — minus what the wallet
 * actually realised (SOL spent incl. entry fees / priority / tips, against SOL
 * received net of exit fees, from EconomicEventSchema6464's Buy and Sell rows).
 * That gap is every friction the trade paid: buy and sell slippage against the
 * market, AMM / pump.fun fees, network + priority fees, Jito tips, exit latency.
 * The entry mark is the market at the FILL, not at the decision, so the
 * decision-to-fill chase (EntryChase7961) is not in it and is never charged twice.
 *
 * Rolling median per lane and per ticket-size band. Learned, soft:
 *  - labels (ForwardReturnLabeler7731) and plan cards (FieldManual7715) are graded
 *    on the measured cost once the lane has [MIN_LIVE_N] live trades, the model's
 *    estimate before that;
 *  - LIVE admission refuses COST_EXCEEDS_EDGE_7962 when the candidate's classified
 *    playbook setup expects a gross move below the measured cost at the lane's
 *    ticket size — only once measured; before that the estimate never refuses.
 */
object CostLedger7962 {
    const val MIN_LIVE_N = 8
    private const val RING = 120
    private const val ENTRY_MARK_LAG_MS = 3_000L
    private const val MAX_SANE_COST_PCT = 60.0
    private const val PERSIST_KEY = "COST_LEDGER_7962"

    /** One sell leg: raw tokens sold, SOL received net of exit fees, the market price at the sale (USD). */
    data class Leg7962(val qtyRaw: Double, val netSol: Double, val markUsd: Double)

    private class EntryMark(val mint: String, val markUsd: Double, val solUsd: Double, val atMs: Long)

    private val entryMarks = ConcurrentHashMap<String, EntryMark>()   // positionId
    private val byLane = HashMap<String, ArrayDeque<DoubleArray>>()    // [costPct, ticketSol, buyPct]
    private val measured = AtomicLong(0)
    private val noEntryMark = AtomicLong(0)
    private val noExitMark = AtomicLong(0)
    private val noLegs = AtomicLong(0)
    private val insane = AtomicLong(0)
    private val refusals = ConcurrentHashMap<String, AtomicLong>()
    private val priorOnly = AtomicLong(0)
    private val subscribed = AtomicBoolean(false)
    private val sincePersist = AtomicLong(0)
    @Volatile private var loaded = false

    // ── pure ──

    /** Pure: median of the finite values (NaN when none). */
    fun median7962(xs: List<Double>): Double {
        val s = xs.filter { it.isFinite() }.sorted()
        if (s.isEmpty()) return Double.NaN
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    /** Pure: the ticket-size band of a SOL size. */
    fun sizeBand7962(sizeSol: Double): String = when {
        !sizeSol.isFinite() || sizeSol <= 0.0 -> "UNK"
        sizeSol < 0.02 -> "LT0.02"
        sizeSol < 0.05 -> "0.02-0.05"
        sizeSol < 0.10 -> "0.05-0.1"
        sizeSol < 0.25 -> "0.1-0.25"
        else -> "GE0.25"
    }

    /**
     * Pure: the all-in round-trip cost (% of the ticket). [spentSol] = SOL out on
     * the buy incl. fees, [boughtRaw] = raw tokens received, [entryMarkUsd] the
     * market at the fill, [solRatio] = SOL/USD at entry over SOL/USD at exit.
     * Frictionless return (market marks, token-weighted) minus realised return.
     * Null when the inputs cannot be one trade.
     */
    fun roundTripCostPct7962(spentSol: Double, boughtRaw: Double, entryMarkUsd: Double, legs: List<Leg7962>, solRatio: Double = 1.0): Double? {
        if (!(spentSol > 0.0) || !(boughtRaw > 0.0) || !(entryMarkUsd > 0.0) || legs.isEmpty()) return null
        if (!solRatio.isFinite() || solRatio <= 0.0) return null
        var frictionless = 0.0
        var realised = 0.0
        for (g in legs) {
            if (!(g.qtyRaw >= 0.0) || !(g.markUsd > 0.0) || !g.netSol.isFinite()) return null
            frictionless += (g.qtyRaw / boughtRaw) * (g.markUsd / entryMarkUsd) * solRatio
            realised += g.netSol
        }
        val cost = (frictionless - realised / spentSol) * 100.0
        return if (cost.isFinite() && kotlin.math.abs(cost) <= MAX_SANE_COST_PCT) cost else null
    }

    /** Pure: the measured cost of a lane at a size band — the band's median at [MIN_LIVE_N]+, else the lane's, else null. */
    fun measuredFrom7962(rows: List<DoubleArray>, sizeSol: Double): Double? {
        if (rows.size < MIN_LIVE_N) return null
        val band = sizeBand7962(sizeSol)
        val inBand = rows.filter { sizeBand7962(it[1]) == band }
        val use = if (band != "UNK" && inBand.size >= MIN_LIVE_N) inBand else rows
        return median7962(use.map { it[0] }).takeIf { it.isFinite() }
    }

    /** Pure: refuse when the measured cost exceeds the expected gross move (never on an unmeasured cost). */
    fun refuses7962(expectedGrossPct: Double, measuredCostPct: Double?): Boolean =
        measuredCostPct != null && measuredCostPct.isFinite() && expectedGrossPct.isFinite() && expectedGrossPct < measuredCostPct

    // ── capture ──

    private fun lane(raw: String?): String {
        val c = try { CanonicalLaneIdentity6506.canonical(raw).uppercase() } catch (_: Throwable) { "" }
        return c.ifBlank { raw?.trim()?.uppercase().orEmpty() }.ifBlank { "UNKNOWN" }
    }

    private fun ensureSubscribed() {
        if (!subscribed.compareAndSet(false, true)) return
        try {
            CanonicalTradeFinalizedBus6450.subscribe { e -> if (e.mode.equals("live", ignoreCase = true)) onLiveFinalized(e) }
        } catch (_: Throwable) { subscribed.set(false) }
    }

    /** EntryStrategySnapshot6450.setEntry: the market price at this live fill (before our own print). */
    fun onEntry7962(positionId: String, mint: String, fillAtMs: Long, paperMode: Boolean) {
        ensureSubscribed()
        ensureLoaded()
        if (paperMode || positionId.isBlank() || mint.isBlank() || fillAtMs <= 0L) return
        val mark = try { com.lifecyclebot.engine.chart.StructureTracker7962.markAt7962(mint, fillAtMs - ENTRY_MARK_LAG_MS) } catch (_: Throwable) { null }
        if (mark == null) { noEntryMark.incrementAndGet(); return }
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        if (entryMarks.size > 500) entryMarks.entries.sortedBy { it.value.atMs }.take(100).forEach { entryMarks.remove(it.key) }
        entryMarks[positionId] = EntryMark(mint, mark, solUsd, fillAtMs)
        persistMaybe(force = true)
    }

    private fun onLiveFinalized(e: CanonicalTradeFinalizedBus6450.Event) {
        ensureLoaded()
        val em = entryMarks.remove(e.positionId) ?: run { noEntryMark.incrementAndGet(); return }
        val rows = try { EconomicEventSchema6464.snapshot() } catch (_: Throwable) { emptyList() }
        val buys = rows.filterIsInstance<EconomicEventSchema6464.Buy>().filter { it.positionId == e.positionId && it.mode == "live" }
        val sells = rows.filterIsInstance<EconomicEventSchema6464.Sell>().filter { it.positionId == e.positionId && it.mode == "live" }
        if (buys.isEmpty() || sells.isEmpty()) { noLegs.incrementAndGet(); return }
        val spent = buys.sumOf { it.executedCostSol + it.entryFeesSol }
        val boughtRaw = buys.sumOf { it.filledQty.toDouble() }
        val legs = ArrayList<Leg7962>()
        for (s in sells) {
            val m = try { com.lifecyclebot.engine.chart.StructureTracker7962.markAt7962(e.mint.ifBlank { em.mint }, s.atMs - ENTRY_MARK_LAG_MS) } catch (_: Throwable) { null }
            if (m == null) { noExitMark.incrementAndGet(); return }
            legs += Leg7962(s.soldQty.toDouble(), s.netProceedsSol, m)
        }
        val solNow = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val ratio = if (em.solUsd > 0.0 && solNow > 0.0) em.solUsd / solNow else 1.0
        val cost = roundTripCostPct7962(spent, boughtRaw, em.markUsd, legs, ratio)
        if (cost == null) { insane.incrementAndGet(); return }
        val dec = buys.first().tokenDecimals
        val buyPct = if (dec >= 0 && em.solUsd > 0.0) {
            val ui = boughtRaw / Math.pow(10.0, dec.toDouble())
            if (ui > 0.0) ((spent / ui) / (em.markUsd / em.solUsd) - 1.0) * 100.0 else Double.NaN
        } else Double.NaN
        val l = lane(e.entryLane)
        synchronized(this) {
            byLane.getOrPut(l) { ArrayDeque() }.apply { addLast(doubleArrayOf(cost, spent, buyPct)); while (size > RING) removeFirst() }
        }
        measured.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("COST_LEDGER_MEASURED_7962")
            ForensicLogger.lifecycle("COST_LEDGER_7962", "pid=${e.positionId.take(16)} lane=$l ticket=${"%.4f".format(spent)} allIn=${"%.2f".format(cost)}% buySide=${"%.2f".format(buyPct)}% legs=${legs.size}")
        } catch (_: Throwable) {}
        persistMaybe(force = false)
    }

    // ── reads ──

    private fun rowsOf(l: String): List<DoubleArray> = synchronized(this) { byLane[l]?.toList().orEmpty() }

    /** The lane's measured all-in cost (%) at [sizeSol], or null until [MIN_LIVE_N] live trades. */
    fun measuredCostPct7962(laneRaw: String?, sizeSol: Double = Double.NaN): Double? {
        ensureLoaded()
        return measuredFrom7962(rowsOf(lane(laneRaw)), sizeSol)
    }

    /** ForwardReturnLabeler7731 / FieldManual7715: the measured cost when the lane has it, else [estimatePct]. */
    fun costOr7962(laneRaw: String?, sizeSol: Double, estimatePct: Double): Double =
        measuredCostPct7962(laneRaw, sizeSol) ?: estimatePct

    /** StructureTracker7962: the lane's round-trip hurdle (measured, else the base estimate). */
    fun laneCostPct7962(laneRaw: String): Double =
        measuredCostPct7962(laneRaw) ?: FieldManual7715.BASE_ROUND_TRIP_COST_PCT_7715

    /** The lane's typical live ticket (median SOL spent), the size admission is judged at. */
    private fun laneTicketSol(l: String): Double = median7962(rowsOf(l).map { it[1] })

    /**
     * LiveEdgeGate7877 (LIVE only): COST_EXCEEDS_EDGE_7962 when the classified setup's
     * expected gross (its record net + the cost its labels were charged) is below the
     * measured all-in cost at the lane's ticket size. Null while unmeasured.
     */
    fun liveRefusal7962(ts: TokenState, laneRaw: String): String? = try {
        ensureSubscribed()
        val l = lane(laneRaw)
        val measuredCost = measuredCostPct7962(l, laneTicketSol(l))
        if (measuredCost == null) {
            priorOnly.incrementAndGet()
            null
        } else {
            val exp = com.lifecyclebot.engine.cortex.LanePlaybook7907.classifiedExpected7962(ts, l)
            if (exp == null) null else {
                val labelCost = costOr7962(l, Double.NaN, FieldManual7715.BASE_ROUND_TRIP_COST_PCT_7715) +
                    (try { EntryChase7961.lanePenaltyPct7961(l) } catch (_: Throwable) { 0.0 })
                val gross = exp + labelCost
                if (!refuses7962(gross, measuredCost)) null else {
                    refusals.computeIfAbsent(l) { AtomicLong(0) }.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("COST_EXCEEDS_EDGE_7962_$l")
                        if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("COST_7962", "$l|${ts.mint}")) {
                            ForensicLogger.lifecycle("COST_EXCEEDS_EDGE_7962", "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$l expGross=${"%.2f".format(gross)}% cost=${"%.2f".format(measuredCost)}%")
                        }
                    } catch (_: Throwable) {}
                    "COST_EXCEEDS_EDGE_7962_$l"
                }
            }
        }
    } catch (_: Throwable) { null }

    // ── persistence ──

    private fun ensureLoaded() {
        if (loaded) return
        if (!LearningPersistence.ready()) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY) ?: return)
                o.optJSONObject("lanes")?.let { j ->
                    for (k in j.keys()) {
                        val a = j.optJSONArray(k) ?: continue
                        byLane[k] = ArrayDeque((0 until a.length()).mapNotNull { i ->
                            a.optJSONArray(i)?.let { r -> doubleArrayOf(r.optDouble(0), r.optDouble(1), r.optDouble(2)) }
                        }.filter { it[0].isFinite() }.takeLast(RING))
                    }
                }
                o.optJSONObject("marks")?.let { j ->
                    for (k in j.keys()) {
                        val r = j.optJSONArray(k) ?: continue
                        if (!entryMarks.containsKey(k)) entryMarks[k] = EntryMark(r.optString(0), r.optDouble(1), r.optDouble(2), r.optLong(3))
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun persistMaybe(force: Boolean) {
        if (!loaded) return
        if (!force && sincePersist.incrementAndGet() < 3) return
        sincePersist.set(0)
        try {
            val json = synchronized(this) {
                val lanes = org.json.JSONObject().also { j ->
                    byLane.forEach { (k, v) -> j.put(k, org.json.JSONArray(v.map { r -> org.json.JSONArray(r.map { x -> if (x.isFinite()) kotlin.math.round(x * 1000.0) / 1000.0 else 0.0 }) })) }
                }
                val marks = org.json.JSONObject().also { j ->
                    entryMarks.entries.take(300).forEach { (k, m) -> j.put(k, org.json.JSONArray(listOf(m.mint, m.markUsd, m.solUsd, m.atMs))) }
                }
                org.json.JSONObject().put("lanes", lanes).put("marks", marks).toString()
            }
            LearningPersistence.save(PERSIST_KEY, json)
        } catch (_: Throwable) {}
    }

    fun statusLine7962(): String {
        ensureSubscribed()
        ensureLoaded()
        val lanes = synchronized(this) { byLane.keys.sorted() }
        val body = lanes.joinToString(" · ") { l ->
            val rows = rowsOf(l)
            val m = measuredFrom7962(rows, laneTicketSol(l))
            "$l n=${rows.size} median=${"%.2f".format(median7962(rows.map { it[0] }))}% buySide=${"%.2f".format(median7962(rows.map { it[2] }))}% " +
                "ticket=${"%.4f".format(laneTicketSol(l))} ${if (m != null) "BINDING" else "learning(n<$MIN_LIVE_N)"} refused=${refusals[l]?.get() ?: 0}"
        }.ifBlank { "no measured live round trips yet" }
        return "measured=${measured.get()} openMarks=${entryMarks.size} noEntryMark=${noEntryMark.get()} noExitMark=${noExitMark.get()} noLegs=${noLegs.get()} insane=${insane.get()} " +
            "unmeasuredPasses=${priorOnly.get()} rule=labels+cards use measured lane cost at n>=$MIN_LIVE_N (estimate before) · refuse COST_EXCEEDS_EDGE_7962 when setup gross < measured cost · $body"
    }
}
