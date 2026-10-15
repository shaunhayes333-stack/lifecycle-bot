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
    const val EARLY_LIVE_N = 3
    private const val RING = 120
    private const val ENTRY_MARK_LAG_MS = 3_000L
    private const val MAX_SANE_COST_PCT = 60.0
    private const val PERSIST_KEY = "COST_LEDGER_7962"
    internal fun recoveryExpired8091(eventAtMs: Long, nowMs: Long, evidenceReady: Boolean = true): Boolean =
        evidenceReady && eventAtMs > 0L && nowMs - eventAtMs > 180_000L

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
        return median7962(use.map { it[0] }).takeIf { it.isFinite() }?.coerceAtLeast(0.0)
    }

    /** Pure: refuse when the measured cost exceeds the expected gross move (never on an unmeasured cost). */
    /**
     * Pure (8094 entry repair): before [MIN_LIVE_N], the CHEAPEST of [EARLY_LIVE_N]+ measured round trips. 8094 measured
     * SHITCOIN at 26% median cost on n=2 and still admitted setups expecting less (COST_EXCEEDS_EDGE refused 0). The
     * floor only refuses a setup whose gross is below even the cheapest real trip; labels and cards keep the median rule.
     */
    fun earlyCostFloor7962(costsPct: List<Double>): Double? =
        costsPct.filter { it.isFinite() }.takeIf { it.size >= EARLY_LIVE_N }?.minOrNull()?.coerceAtLeast(0.0)

    fun refuses7962(expectedGrossPct: Double, measuredCostPct: Double?): Boolean =
        measuredCostPct != null && measuredCostPct.isFinite() && expectedGrossPct.isFinite() && expectedGrossPct < measuredCostPct

    // ── capture ──

    private fun lane(raw: String?): String {
        val c = try { CanonicalLaneIdentity6506.canonical(raw).uppercase() } catch (_: Throwable) { "" }
        return c.ifBlank { raw?.trim()?.uppercase().orEmpty() }.ifBlank { "UNKNOWN" }
    }

    private fun ensureSubscribed() {
        CanonicalFinalizedTradeBus6464.registerConsumer("CostLedger7962")
    }

    /** EntryStrategySnapshot6450.setEntry: the market price at this live fill (before our own print). */
    @Synchronized fun onEntry7962(positionId: String, mint: String, fillAtMs: Long, paperMode: Boolean) {
        ensureSubscribed()
        ensureLoaded()
        if (paperMode || positionId.isBlank() || mint.isBlank() || fillAtMs <= 0L) return
        val mark = try { com.lifecyclebot.engine.chart.StructureTracker7962.markAt7962(mint, fillAtMs - ENTRY_MARK_LAG_MS) } catch (_: Throwable) { null }
        if (mark == null) { noEntryMark.incrementAndGet(); return }
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        entryMarks.putIfAbsent(positionId, EntryMark(mint, mark, solUsd, fillAtMs))
        persistMaybe(force = true)
    }

    /** Pure (V5.0.8094): sold within dust of the fill — at most max(1 raw, 0.5%) apart, never more sold than bought+dust. */
    fun quantityMatches8094(filled: java.math.BigInteger, sold: java.math.BigInteger): Boolean {
        if (filled.signum() <= 0 || sold.signum() <= 0) return false
        val tol = filled.divide(java.math.BigInteger.valueOf(200L)).max(java.math.BigInteger.ONE)
        return (filled - sold).abs() <= tol
    }

    /** Pure (V5.0.8094): the share of the fill's cost attributable to the quantity actually sold (<= 1). */
    fun soldShare8094(filled: java.math.BigInteger, sold: java.math.BigInteger): Double =
        if (filled.signum() <= 0) 1.0 else (java.math.BigDecimal(sold).divide(java.math.BigDecimal(filled), java.math.MathContext.DECIMAL64)).toDouble().coerceIn(0.0, 1.0)

    /** Called by the durable finality bridge. False leaves the same economic event retryable. */
    @Synchronized fun onLiveFinalized(e: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        ensureLoaded()
        if (!loaded || !e.terminal) return false
        if (!e.mode.equals("live", true)) {
            CanonicalFinalizedTradeBus6464.exclude("CostLedger7962", e.tradeId, "PAPER_NOT_LIVE_COST_EVIDENCE")
            return true
        }
        if (!e.learningEligible || !ExactStrategyPerformance7429.terminalEconomicsEligible8081(e.mode, e.proofState)) return false
        val em = entryMarks[e.positionId] ?: run {
            noEntryMark.incrementAndGet()
            CanonicalFinalizedTradeBus6464.exclude("CostLedger7962", e.tradeId, "ENTRY_MARK_UNOBSERVED")
            return true
        }
        fun missing8091(reason: String): Boolean =
            if (recoveryExpired8091(e.atMs, System.currentTimeMillis()))
                commitMeasurement8090(e, em, null, 0.0, reason)
            else false
        val rows = try { EconomicEventSchema6464.snapshot() } catch (_: Throwable) { return false }
        // V5.0.8094 — a journal-backfilled copy of the same fill (same quantity, same cost) is one buy, not two.
        val buys = rows.filterIsInstance<EconomicEventSchema6464.Buy>().filter {
            it.positionId == e.positionId && it.mode.equals("live", true) && it.mint == em.mint
        }.distinctBy { it.idempotencyKey }.distinctBy { it.filledQty to kotlin.math.round(it.executedCostSol * 1e9).toLong() }
        val sells = rows.filterIsInstance<EconomicEventSchema6464.Sell>().filter {
            it.positionId == e.positionId && it.mode.equals("live", true) && it.mint == em.mint
        }.distinctBy { it.idempotencyKey }
        if (buys.isEmpty() || sells.isEmpty()) { noLegs.incrementAndGet(); return missing8091("HISTORICAL_LEGS_UNOBSERVED_8091") }
        // A single entry mark cannot price additional purchases at different times.
        if (buys.size != 1) return commitMeasurement8090(e, em, null, 0.0, "MULTIPLE_BUYS_REQUIRE_PER_FILL_MARKS")
        val buy = buys.single()
        val soldRaw = sells.fold(java.math.BigInteger.ZERO) { n, leg -> n + leg.soldQty }
        // V5.0.8094 — a fully closed position whose sold quantity is within dust of the fill (rounding, a wallet-clamped
        // final sell) is measured on the quantity actually sold, its cost scaled to that share. Exact match was required:
        // 8093 noLegs=6 / insane=5 with zero measured live round trips.
        val fullyClosed8094 = sells.any { !it.partial && it.remainingQty.signum() == 0 }
        if (!fullyClosed8094 || !quantityMatches8094(buy.filledQty, soldRaw)) {
            noLegs.incrementAndGet(); return missing8091("HISTORICAL_QUANTITY_INCOMPLETE_8091")
        }
        val legs = ArrayList<TradeCostMeasurement8090.Sale>()
        for (s in sells) {
            val mark = try { com.lifecyclebot.engine.chart.StructureTracker7962.markAt7962(em.mint, s.atMs - ENTRY_MARK_LAG_MS) } catch (_: Throwable) { null }
            if (mark == null) { noExitMark.incrementAndGet(); return missing8091("HISTORICAL_EXIT_MARK_UNOBSERVED_8091") }
            if (!s.solUsdAtExit.isFinite() || s.solUsdAtExit <= 0.0) {
                return commitMeasurement8090(e, em, null, 0.0, "EXIT_SOL_USD_UNOBSERVED")
            }
            legs += TradeCostMeasurement8090.Sale(s.soldQty, s.netProceedsSol, mark, s.solUsdAtExit)
        }
        val spent = (buy.executedCostSol + buy.entryFeesSol) * soldShare8094(buy.filledQty, soldRaw)
        val measurement = TradeCostMeasurement8090.measure(spent, soldRaw, buy.tokenDecimals, em.markUsd, em.solUsd, legs)
        return commitMeasurement8090(e, em, measurement, spent, if (measurement == null) "INVALID_FRICTION" else "MEASURED")
    }

    private fun commitMeasurement8090(e: CanonicalFinalizedTradeBus6464.Envelope, em: EntryMark,
        measurement: TradeCostMeasurement8090.Measurement?, spent: Double, reason: String): Boolean {
        val l = lane(e.lane)
        val next = ArrayDeque(byLane[l].orEmpty())
        if (measurement != null) {
            next.addLast(doubleArrayOf(measurement.costPct, spent, measurement.buyPct))
            while (next.size > RING) next.removeFirst()
        }
        val state = encodeState8090(e.positionId, if (measurement != null) l to next else null)
        val key = "cost_measurement_8090_${e.positionId}"
        val evidence = org.json.JSONObject().put("positionId", e.positionId).put("eventId", e.economicEventId)
            .put("proof", e.proofState).put("reason", reason).put("entryMarkUsd", em.markUsd)
            .put("entrySolUsd", em.solUsd).put("entryAtMs", em.atMs)
            .put("costPct", measurement?.costPct ?: org.json.JSONObject.NULL)
            .put("buyPct", measurement?.buyPct ?: org.json.JSONObject.NULL).toString()
        when (LearningPersistence.commitOnce8090("${key}_receipt", mapOf(PERSIST_KEY to state, key to evidence))) {
            LearningPersistence.Commit8090.RETRY -> return false
            LearningPersistence.Commit8090.ALREADY_COMMITTED -> return true
            LearningPersistence.Commit8090.COMMITTED -> {
                if (measurement != null) { byLane[l] = next; measured.incrementAndGet() } else insane.incrementAndGet()
                entryMarks.remove(e.positionId, em)
                if (measurement == null) CanonicalFinalizedTradeBus6464.exclude("CostLedger7962", e.tradeId, reason)
                return true
            }
        }
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
        val measuredCost = measuredCostPct7962(l, laneTicketSol(l)) ?: earlyCostFloor7962(rowsOf(l).map { it[0] })
        if (measuredCost == null) {
            priorOnly.incrementAndGet()
            null
        } else {
            val exp = com.lifecyclebot.engine.cortex.LanePlaybook7907.classifiedExpected7962(ts, l)
            if (exp == null) null else {
                // V5.0.8094 — labels rebased at the measured lane latency carry no chase charge; adding it back would
                // overstate the gross edge (never looser: older labels still net of the chase read more negative).
                val labelCost = costOr7962(l, Double.NaN, FieldManual7715.BASE_ROUND_TRIP_COST_PCT_7715) +
                    (if (ForwardReturnLabeler7731.rebaseClearsChase8094(EntryChase7961.laneLatencyMs8094(l))) 0.0
                    else try { EntryChase7961.lanePenaltyPct7961(l) } catch (_: Throwable) { 0.0 })
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

    private var excludedLegacy8090 = org.json.JSONObject()

    private fun ensureLoaded() {
        if (loaded) return
        if (!LearningPersistence.ready()) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY) ?: return)
                excludedLegacy8090 = o.optJSONObject("excludedLegacy8090") ?: org.json.JSONObject()
                o.optJSONObject("lanes")?.let { j ->
                    for (k in j.keys()) {
                        val a = j.optJSONArray(k) ?: continue
                        // Preserve invalid historical samples verbatim outside the cost learner.
                        val excluded = org.json.JSONArray()
                        for (i in 0 until a.length()) {
                            val row = a.optJSONArray(i)
                            if (row == null || !row.optDouble(0).isFinite() || row.optDouble(0) !in -MAX_SANE_COST_PCT..MAX_SANE_COST_PCT ||
                                !row.optDouble(2).isFinite() || row.optDouble(2) !in -MAX_SANE_COST_PCT..MAX_SANE_COST_PCT) excluded.put(a.opt(i))
                        }
                        if (excluded.length() > 0) excludedLegacy8090.put(k, excluded)
                        byLane[k] = ArrayDeque((0 until a.length()).mapNotNull { i ->
                            a.optJSONArray(i)?.let { r -> doubleArrayOf(r.optDouble(0), r.optDouble(1), r.optDouble(2)) }
                        }.filter { it[0].isFinite() && it[0] in -MAX_SANE_COST_PCT..MAX_SANE_COST_PCT && it[2].isFinite() && it[2] in -MAX_SANE_COST_PCT..MAX_SANE_COST_PCT }.takeLast(RING))
                    }
                }
                o.optJSONObject("marks")?.let { j ->
                    for (k in j.keys()) {
                        val r = j.optJSONArray(k) ?: continue
                        if (!entryMarks.containsKey(k)) entryMarks[k] = EntryMark(r.optString(0), r.optDouble(1), r.optDouble(2), r.optLong(3))
                    }
                }
            } catch (t: Throwable) { LearningPersistence.decodeFailed8086(PERSIST_KEY, t); loaded = false }
            if (LearningPersistence.readFailed8086(PERSIST_KEY)) loaded = false
        }
    }

    private fun persistMaybe(force: Boolean) {
        if (!loaded) return
        if (!force && sincePersist.incrementAndGet() < 3) return
        sincePersist.set(0)
        try {
            val json = synchronized(this) { encodeState8090() }
            LearningPersistence.save(PERSIST_KEY, json)
        } catch (_: Throwable) {}
    }

    private fun encodeState8090(removePosition: String = "", replacement: Pair<String, ArrayDeque<DoubleArray>>? = null): String {
        val lanes = org.json.JSONObject()
        val all = byLane.toMutableMap()
        replacement?.let { all[it.first] = it.second }
        all.forEach { (k, v) -> lanes.put(k, org.json.JSONArray(v.map { r -> org.json.JSONArray(r.map { x ->
            if (x.isFinite()) x else org.json.JSONObject.NULL
        }) })) }
        val marks = org.json.JSONObject()
        entryMarks.forEach { (k, m) -> if (k != removePosition) marks.put(k, org.json.JSONArray(listOf(m.mint, m.markUsd, m.solUsd, m.atMs))) }
        return org.json.JSONObject().put("lanes", lanes).put("marks", marks)
            .put("excludedLegacy8090", excludedLegacy8090).toString()
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
