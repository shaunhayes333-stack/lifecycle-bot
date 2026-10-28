package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8366 §THE_SHADOW_PROVES_WHAT_LIVE_WILL_DO — the shadow books (EvidenceShadow8344, ExploreShadow8347) unlock lanes and
 * pick live entries / exits; their record must be what a LIVE position would have realised. LIVE_SHADOW_ALIGNMENT_8366.md
 * (module root) lists every divergence found at 5.0.8363; this object holds the pure pieces both books now share:
 *
 *  - ENTRY LANDING: a live buy goes PumpPortal on a pump.fun mint (unless PumpLanding8288 routes it away) and Jupiter
 *    otherwise; 5.0.8341: BUY|PUMPPORTAL landed 3 of 17, BUY|JUPITER 18 of 18. The shadow assumed every buy lands. A shadow
 *    entry now lands with the route's measured rate (landing8169 counters, at [PumpLanding8288.MIN_RESOLVED_8288]+ resolved
 *    buys; 1.0 before — unmeasured, never guessed), drawn deterministically from the ticket key ([draw8366]) so a replay
 *    books the same outcome. An unlanded entry is no trade, booked as the failed-tx fee only (a leg that FAILED on chain paid
 *    its fee; an expired one paid nothing: fee x the route's failed share of its unlanded buys).
 *  - COST: the lane's measured round trip (CostLedger7962, n >= 8) is what the shadow charges once measured — the model
 *    (NetEdge8030.costPct8030) before. Both include the fixed legs (network + priority fee, Sender / Jito tip;
 *    CostMeter8151.roundTripFixedSol8151 in the model, the wallet's SOL in the measurement), floored here at that fixed
 *    share of the route-minimum ticket ([fixedPct8366]) so it is never missing.
 *  - SELL LATENCY: a live exit fills after trigger -> broadcast (PaperExitLatency8223: measured, 16 s / 6 s priors). A
 *    shadow full exit is now priced at the first observed mark at least that long after its trigger ([latencyElapsed8366]).
 *  - EXITS: the stop level live's fluid stop holds (FieldManual7715.runnerAwareStop7935 / costArmedStop7928 — net
 *    break-even once the peak cleared it), the green floor of a green hold (GreenFloor8363), the 8363 scalp stop with its
 *    60 s warmup (ScalpStop8363.decide8363), and the lifecycle hold a +100 % position is promoted into (LifecycleHold8065:
 *    -40 % floor, give-backs, 100 / 400 / 900 takes, dead money, no clock) — each by calling the live pure function.
 *  - ALIGNMENT: a mint traded by both a live position and a shadow leg (same lane, entries within [PAIR_WINDOW_MS_8366])
 *    records live net - shadow net ([statusLine8366]: align8366[pairs= meanGapPts= p50= worst= lane{...}]).
 * Paper / shadow never size or admit live: nothing here is read by sizing; the alignment line is a report.
 */
object ShadowAlign8366 {
    const val TICKET_SOL_8366 = 0.03
    const val ROUTE_PUMPPORTAL_8366 = "PUMPPORTAL"
    const val ROUTE_JUPITER_8366 = "JUPITER"
    const val UNLANDED_WHY_8366 = "UNLANDED_8366"
    const val PAIR_WINDOW_MS_8366 = 60_000L
    private const val LAND_CACHE_MS_8366 = 60_000L
    private const val RING_8366 = 200
    private const val GAPS_8366 = 300
    private const val REC_MAX_AGE_MS_8366 = 24L * 3_600_000L
    private const val PERSIST_KEY_8366 = "SHADOW_ALIGN_8366"
    private const val HOLD_WINDOW_MS_8366 = 7L * 24L * 3_600_000L

    // ── landing (pure) ──

    /** Pure: the route a live buy takes — PumpPortal for a pump.fun mint unless PumpLanding8288 routed it away, else Jupiter. */
    fun buyRoute8366(pumpMint: Boolean, pumpRoutedAway: Boolean): String =
        if (pumpMint && !pumpRoutedAway) ROUTE_PUMPPORTAL_8366 else ROUTE_JUPITER_8366

    /** Pure: the route's landing rate, landed / resolved, once [minResolved] buys resolved; 1.0 before (unmeasured). */
    fun landRate8366(landed: Int, expired: Int, failed: Int, minResolved: Int = com.lifecyclebot.engine.PumpLanding8288.MIN_RESOLVED_8288): Double {
        val l = landed.coerceAtLeast(0); val x = expired.coerceAtLeast(0); val f = failed.coerceAtLeast(0)
        val resolved = l + x + f
        if (resolved <= 0 || resolved < minResolved) return 1.0
        return (l.toDouble() / resolved.toDouble()).coerceIn(0.0, 1.0)
    }

    /** Pure: the share of unlanded buys that FAILED on chain (and so paid the fee); an expired buy paid nothing. */
    fun failedShare8366(expired: Int, failed: Int): Double {
        val x = expired.coerceAtLeast(0); val f = failed.coerceAtLeast(0)
        return if (x + f <= 0) 0.0 else f.toDouble() / (x + f).toDouble()
    }

    /** Pure: a deterministic draw in [0, 1) from [key] (a mixed String hash: the same ticket always draws the same). */
    fun draw8366(key: String): Double {
        var h = key.hashCode()
        h = h xor (h ushr 16)
        h *= 0x85ebca6bL.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35L.toInt()
        h = h xor (h ushr 16)
        return Math.floorMod(h, 10_000).toDouble() / 10_000.0
    }

    /** Pure: does the entry keyed [key] land at [rate]? Always at 1.0; never at 0.0. */
    fun lands8366(key: String, rate: Double): Boolean {
        if (!rate.isFinite() || rate >= 1.0) return true
        if (rate <= 0.0) return false
        return draw8366(key) < rate
    }

    /** Pure: the failed-tx fee (% of the ticket) an unlanded entry books: fee x the failed share of unlanded buys. */
    fun unlandedFeePct8366(failedShare: Double, feeSol: Double, ticketSol: Double = TICKET_SOL_8366): Double {
        if (!feeSol.isFinite() || feeSol <= 0.0 || !ticketSol.isFinite() || ticketSol <= 0.0) return 0.0
        return failedShare.coerceIn(0.0, 1.0) * feeSol / ticketSol * 100.0
    }

    // ── landing (live read, cached 60 s) ──

    data class Land8366(val route: String, val rate: Double, val failedShare: Double, val resolved: Int)

    private class LandCache8366(val atMs: Long, val table: Map<String, com.lifecyclebot.engine.CostLanding8169.Landing8169>, val pumpAway: Boolean)
    @Volatile private var landCache8366: LandCache8366? = null
    private val landed8366 = AtomicLong(0)
    private val unlanded8366 = AtomicLong(0)

    private fun landTable8366(nowMs: Long): LandCache8366 {
        val c = landCache8366
        if (c != null && nowMs - c.atMs in 0L until LAND_CACHE_MS_8366) return c
        val table = try {
            com.lifecyclebot.engine.CostLanding8169.landingByRoute8169(com.lifecyclebot.network.SignedTxLedger8085.landRows8169())
        } catch (_: Throwable) { emptyMap<String, com.lifecyclebot.engine.CostLanding8169.Landing8169>() }
        // V5.0.8412 — the same route-away verdict the live buy uses (PumpLanding8288.awayNow8412): a pump.fun shadow entry
        // is priced on the route live would actually take, not on a PumpPortal route live has already abandoned.
        val away = try { com.lifecyclebot.engine.PumpLanding8288.awayNow8412(nowMs) != null } catch (_: Throwable) { false }
        val n = LandCache8366(nowMs, table, away)
        landCache8366 = n
        return n
    }

    /** The measured landing of the route a live buy of [mint] would take now. */
    fun landFor8366(mint: String, nowMs: Long = System.currentTimeMillis()): Land8366 {
        val c = landTable8366(nowMs)
        val pump = try { com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(mint) } catch (_: Throwable) { false }
        val route = buyRoute8366(pump, c.pumpAway)
        val l = c.table["BUY|$route"]
        if (l == null) return Land8366(route, 1.0, 0.0, 0)
        return Land8366(route, landRate8366(l.landed, l.expired, l.failed), failedShare8366(l.expired, l.failed), l.landed + l.expired + l.failed)
    }

    /** The fee a failed buy leg paid (SOL): CostMeter8151's measured average, the fixed-leg fallback before. */
    private fun failedFeeSol8366(): Double = try { com.lifecyclebot.engine.CostMeter8151.failedLegFeeSol8366() } catch (_: Throwable) { Double.NaN }

    /**
     * A shadow entry of [mint] keyed [key]: null when it lands (trade it), else the failed-tx fee (% of the ticket) to book
     * as the unlanded entry's whole result. Counted either way.
     */
    fun unlandedEntryFeePct8366(key: String, mint: String, nowMs: Long = System.currentTimeMillis()): Double? {
        val land = landFor8366(mint, nowMs)
        if (lands8366(key, land.rate)) { landed8366.incrementAndGet(); return null }
        unlanded8366.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SHADOW_ENTRY_UNLANDED_8366_${land.route}") } catch (_: Throwable) {}
        return unlandedFeePct8366(land.failedShare, failedFeeSol8366())
    }

    // ── cost ──

    /** Pure: the fixed legs (network + priority fee, tips) of one round trip as % of [ticketSol]. */
    fun fixedPct8366(fixedRoundTripSol: Double, ticketSol: Double = TICKET_SOL_8366): Double =
        if (!fixedRoundTripSol.isFinite() || fixedRoundTripSol <= 0.0 || !ticketSol.isFinite() || ticketSol <= 0.0) 0.0
        else fixedRoundTripSol / ticketSol * 100.0

    /** Pure: the shadow's round trip — the lane's measured cost once measured (n >= 8), else the model, never under the fixed legs. */
    fun shadowCostPct8366(measuredPct: Double?, modelPct: Double, fixedPct: Double): Double {
        if (measuredPct != null && measuredPct.isFinite() && measuredPct >= 0.0) return measuredPct
        val model = if (modelPct.isFinite() && modelPct > 0.0) modelPct else 7.0
        return maxOf(model, if (fixedPct.isFinite()) fixedPct else 0.0)
    }

    /** The round trip a shadow ticket in [lane] is charged (route-minimum ticket, curve-aware model before measurement). */
    fun laneCostPct8366(lane: String, liquidityUsd: Double, mint: String, mcapUsd: Double): Double {
        val measured = try { CostLedger7962.measuredCostPct7962(lane, TICKET_SOL_8366) } catch (_: Throwable) { null }
        val model = try { NetEdge8030.costPct8030(lane, TICKET_SOL_8366, liquidityUsd, mint, mcapUsd) } catch (_: Throwable) { Double.NaN }
        val fixed = fixedPct8366(try { com.lifecyclebot.engine.CostMeter8151.roundTripFixedSol8151() } catch (_: Throwable) { Double.NaN })
        return shadowCostPct8366(measured, model, fixed)
    }

    // ── sell latency ──

    /** The live trigger -> broadcast latency (ms) a shadow exit waits: PaperExitLatency8223's measured class average. */
    fun sellLatencyMs8366(emergency: Boolean = false): Long =
        try { PaperExitLatency8223.latencyMs8223(emergency) } catch (_: Throwable) { if (emergency) 6_000L else 16_000L }

    /** Pure: has the live sell latency passed since the exit triggered at [triggerAtMs]? */
    fun latencyElapsed8366(triggerAtMs: Long, latencyMs: Long, nowMs: Long): Boolean =
        triggerAtMs > 0L && nowMs - triggerAtMs >= latencyMs.coerceAtLeast(0L)

    // ── exits (the live pure functions) ──

    /**
     * Pure: the level (gross %) live's fluid stop holds for a position at peak [peakPct]: a runner lane's runner-aware stop
     * (net break-even once the peak cleared it, 40 % of a +20 % peak) plus the green floor of a green hold; any other lane's
     * cost-armed stop (net break-even once the peak cleared it by 1.5). Before arming, the lane stop -[stopPct].
     */
    fun liveStopLevel8366(runner: Boolean, stopPct: Double, peakPct: Double, costPct: Double): Double {
        val raw = -stopPct
        val base = try {
            if (runner) FieldManual7715.runnerAwareStop7935(raw, peakPct, costPct, true) { raw }
            else FieldManual7715.costArmedStop7928(raw, peakPct, costPct) { raw }
        } catch (_: Throwable) { raw }
        val green = if (runner) com.lifecyclebot.engine.GreenFloor8363.floor8363(peakPct, costPct) else Double.NaN
        val b = if (base.isFinite()) base else raw
        return if (green.isFinite()) maxOf(b, green) else b
    }

    /** Pure: is a position at gross [g] / confirmed peak [peakPct] promoted into the lifecycle hold (LifecycleHold8065.onMark8065)? */
    fun holdPromotes8366(g: Double, peakPct: Double): Boolean =
        g.isFinite() && g >= com.lifecyclebot.engine.LifecycleHold8065.PROMOTE_PCT && peakPct >= com.lifecyclebot.engine.LifecycleHold8065.PROMOTE_PCT

    /**
     * Pure: a lifecycle hold's own exit, in LifecycleHold8065.onMark8065's order (the 15-minute base / trend bars are not
     * mirrored: the shadow keeps no bars): (reason, share of the CURRENT holding) or null. [taken] = takes done.
     */
    fun holdExit8366(g: Double, peakPct: Double, taken: Int, ageMs: Long, sinceHighMs: Long, costPct: Double): Pair<String, Double>? {
        if (!g.isFinite()) return null
        val take = com.lifecyclebot.engine.LifecycleHold8065.cumulativeTakeDue8079(g, taken)
        return when {
            g <= -com.lifecyclebot.engine.LifecycleHold8065.FLOOR_PCT -> "HOLD_FLOOR_8366" to 1.0
            com.lifecyclebot.engine.LifecycleHold8065.profitablePeakReversal8079(peakPct, g, true) -> "HOLD_PEAK_GIVEBACK_8366" to 1.0
            com.lifecyclebot.engine.GreenFloor8363.breached8363(g, peakPct, costPct) -> "HOLD_GREEN_FLOOR_8366" to 1.0
            take != null -> "HOLD_TAKE${take.first + 1}_8366" to take.second
            taken >= 1 && com.lifecyclebot.engine.LifecycleHold8065.peakGiveback8065(peakPct, g) -> "HOLD_TREND_BREAK_8366" to 1.0
            taken == 0 && com.lifecyclebot.engine.LifecycleHold8065.givebackDue8080(peakPct, g, taken) -> "HOLD_UNBANKED_GIVEBACK_8366" to 1.0
            com.lifecyclebot.engine.LifecycleHold8065.deadMoney8065(ageMs, g, sinceHighMs) -> "HOLD_DEAD_MONEY_8366" to 1.0
            else -> null
        }
    }

    /** Pure: the take index a HOLD_TAKE<n>_8366 reason completes (n), or -1. */
    fun takeOf8366(reason: String): Int =
        if (reason.startsWith("HOLD_TAKE")) reason.removePrefix("HOLD_TAKE").substringBefore('_').toIntOrNull() ?: -1 else -1

    /** The restore window of a ticket / position holding a lifecycle hold (the hold has no clock; TailBag's 7 days bound it). */
    fun holdWindowMs8366(): Long = HOLD_WINDOW_MS_8366

    // ── alignment: live vs shadow on the same mint ──

    private class Rec8366(val id: String, val lane: String, val mint: String, val entryAtMs: Long, val netPct: Double) { var used = false }
    private val liveRecs = ArrayDeque<Rec8366>()
    private val shadowRecs = ArrayDeque<Rec8366>()
    private val gaps = ArrayDeque<Pair<String, Double>>()   // lane -> live net - shadow net (pts)
    private val seenLive = LinkedHashSet<String>()
    @Volatile private var loaded8366 = false

    /** Pure: the partner of (lane, mint, entry) among [cands] (lane, mint, entryAtMs, used): same lane and mint, unused, entries within [windowMs], nearest; -1 = none. */
    fun pairIndex8366(lane: String, mint: String, entryAtMs: Long, cands: List<Triple<String, String, Long>>, used: List<Boolean>,
                      windowMs: Long = PAIR_WINDOW_MS_8366): Int {
        var best = -1
        var bestGap = Long.MAX_VALUE
        for (i in cands.indices) {
            val c = cands[i]
            if (used.getOrNull(i) == true || c.first != lane || c.second != mint) continue
            val d = kotlin.math.abs(c.third - entryAtMs)
            if (d <= windowMs && d < bestGap) { best = i; bestGap = d }
        }
        return best
    }

    data class Summary8366(val pairs: Int, val meanGapPts: Double, val p50: Double, val worst: Double, val byLane: Map<String, Pair<Int, Double>>)

    /** Pure: the summary of (lane, live - shadow) gaps; worst = the most negative gap (live furthest under its shadow). */
    fun summarize8366(gs: List<Pair<String, Double>>): Summary8366 {
        val xs = gs.map { it.second }.filter { it.isFinite() }
        if (xs.isEmpty()) return Summary8366(0, Double.NaN, Double.NaN, Double.NaN, emptyMap())
        val s = xs.sorted()
        val p50 = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        val by = gs.filter { it.second.isFinite() }.groupBy { it.first }.mapValues { (_, v) -> v.size to v.map { it.second }.average() }
        return Summary8366(xs.size, xs.average(), p50, s.first(), java.util.TreeMap(by))
    }

    /** Pure: align8366[pairs=n meanGapPts= p50= worst= lane{L n/gap,...}]. */
    fun line8366(s: Summary8366): String {
        fun f(v: Double) = if (v.isFinite()) "%+.1f".format(v) else "-"
        val lanes = s.byLane.entries.joinToString(",") { "${it.key} n${it.value.first}/${f(it.value.second)}" }.ifBlank { "-" }
        return "align8366[pairs=${s.pairs} meanGapPts=${f(s.meanGapPts)} p50=${f(s.p50)} worst=${f(s.worst)} lane{$lanes}]"
    }

    private fun canon8366(lane: String): String =
        (try { CanonicalLaneIdentity6506.canonical(lane) } catch (_: Throwable) { "" }).uppercase().ifBlank { lane.trim().uppercase() }

    private fun prune8366(q: ArrayDeque<Rec8366>, nowMs: Long) {
        while (q.size > RING_8366) q.removeFirst()
        q.removeAll { it.used || nowMs - it.entryAtMs > REC_MAX_AGE_MS_8366 }
    }

    private fun match8366(rec: Rec8366, others: ArrayDeque<Rec8366>, liveSide: Boolean): Boolean {
        val list = others.toList()
        val i = pairIndex8366(rec.lane, rec.mint, rec.entryAtMs, list.map { Triple(it.lane, it.mint, it.entryAtMs) }, list.map { it.used })
        if (i < 0) return false
        val o = list[i]
        o.used = true
        val gap = if (liveSide) rec.netPct - o.netPct else o.netPct - rec.netPct
        gaps.addLast(rec.lane to gap)
        while (gaps.size > GAPS_8366) gaps.removeFirst()
        try { PipelineHealthCollector.labelInc("SHADOW_ALIGN_PAIR_8366_${rec.lane}") } catch (_: Throwable) {}
        return true
    }

    /** CostLedger7962.onLiveFinalized: a terminal LIVE close (its realised net %), once per trade id. */
    @Synchronized fun noteLive8366(tradeId: String, lane: String, mint: String, entryAtMs: Long, netPct: Double, nowMs: Long = System.currentTimeMillis()) {
        if (tradeId.isBlank() || mint.isBlank() || entryAtMs <= 0L || !netPct.isFinite()) return
        ensureLoaded8366()
        if (!seenLive.add(tradeId)) return
        while (seenLive.size > 2_000) seenLive.remove(seenLive.first())
        val r = Rec8366(tradeId, canon8366(lane), mint, entryAtMs, netPct)
        if (!match8366(r, shadowRecs, true)) liveRecs.addLast(r)
        prune8366(liveRecs, nowMs); prune8366(shadowRecs, nowMs)
        persist8366()
    }

    /** A shadow close that models the live position (EvidenceShadow8344, or ExploreShadow8347's NOW leg on the live exit style). */
    @Synchronized fun noteShadow8366(src: String, lane: String, mint: String, entryAtMs: Long, netPct: Double, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || entryAtMs <= 0L || !netPct.isFinite()) return
        ensureLoaded8366()
        val r = Rec8366(src, canon8366(lane), mint, entryAtMs, netPct)
        if (!match8366(r, liveRecs, false)) shadowRecs.addLast(r)
        prune8366(liveRecs, nowMs); prune8366(shadowRecs, nowMs)
        persist8366()
    }

    @Synchronized fun summary8366(): Summary8366 { ensureLoaded8366(); return summarize8366(gaps.toList()) }

    @Synchronized internal fun resetForTests8366() {
        liveRecs.clear(); shadowRecs.clear(); gaps.clear(); seenLive.clear(); loaded8366 = true
        landed8366.set(0); unlanded8366.set(0); landCache8366 = null
    }

    private fun encRec(r: Rec8366): String = "${r.id.replace(',', '_').replace(';', '_')},${r.lane},${r.mint},${r.entryAtMs},${r.netPct}"

    private fun decRec(s: String): Rec8366? {
        val p = s.split(',')
        if (p.size < 5) return null
        val at = p[3].toLongOrNull() ?: return null
        val net = p[4].toDoubleOrNull() ?: return null
        if (p[2].isBlank() || at <= 0L || !net.isFinite()) return null
        return Rec8366(p[0], p[1], p[2], at, net)
    }

    private fun persist8366() {
        if (!loaded8366) return
        try {
            if (!LearningPersistenceGate8366.ready()) return
            val g = gaps.joinToString(";") { "${it.first},${it.second}" }
            val l = liveRecs.joinToString(";") { encRec(it) }
            val s = shadowRecs.joinToString(";") { encRec(it) }
            com.lifecyclebot.engine.LearningPersistence.save(PERSIST_KEY_8366, "$g|$l|$s")
        } catch (_: Throwable) {}
    }

    private fun ensureLoaded8366() {
        if (loaded8366) return
        if (!LearningPersistenceGate8366.ready()) return
        loaded8366 = true
        try {
            val raw = com.lifecyclebot.engine.LearningPersistence.load(PERSIST_KEY_8366) ?: return
            val parts = raw.split('|')
            parts.getOrNull(0)?.split(';')?.forEach { row ->
                val lane = row.substringBefore(',', ""); val v = row.substringAfter(',', "").toDoubleOrNull()
                if (lane.isNotBlank() && v != null && v.isFinite()) gaps.addLast(lane to v)
            }
            parts.getOrNull(1)?.split(';')?.forEach { row -> decRec(row)?.let { liveRecs.addLast(it) } }
            parts.getOrNull(2)?.split(';')?.forEach { row -> decRec(row)?.let { shadowRecs.addLast(it) } }
        } catch (_: Throwable) {}
    }

    /** The report line (PipelineHealthCollector, §8366). */
    fun statusLine8366(): String {
        val s = summary8366()
        val fixed = fixedPct8366(try { com.lifecyclebot.engine.CostMeter8151.roundTripFixedSol8151() } catch (_: Throwable) { Double.NaN })
        val jup = try { landFor8366("") } catch (_: Throwable) { null }
        return line8366(s) + " shadowEntries8366[landed=${landed8366.get()} unlanded=${unlanded8366.get()}" +
            " jupRate=${jup?.let { "%.0f".format(it.rate * 100.0) + "%" } ?: "-"}] fixedLegs8366=${"%.2f".format(fixed)}%@${TICKET_SOL_8366}SOL" +
            " sellLatency8366=${sellLatencyMs8366(false)}ms cost8366=measured(n>=${CostLedger7962.MIN_LIVE_N})|model" +
            " pending{live=${synchronized(this) { liveRecs.size }},shadow=${synchronized(this) { shadowRecs.size }}}"
    }
}

/** V5.0.8366 — LearningPersistence's readiness, read in one place (a test JVM has no database: nothing is persisted). */
internal object LearningPersistenceGate8366 {
    fun ready(): Boolean = try { com.lifecyclebot.engine.LearningPersistence.ready() } catch (_: Throwable) { false }
}
