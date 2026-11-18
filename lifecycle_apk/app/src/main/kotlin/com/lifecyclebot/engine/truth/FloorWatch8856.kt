package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8856 §PROBATION_WATCHES_THE_FLOOR — owner: "we need to watch them cross a 5k floor price and make a decision
 * earlier. thats what the probation lane is for. we are dropping tokens from the watchlist too quickly obviously and
 * not using at designed".
 *
 * What the code did (5.0.8853, LIVE, 5 minutes after start):
 *  - probation (GlobalTradeRegistry) is a 30-90 s timer. It promotes on +5 %, a second scanner, a rugcheck score or the
 *    timeout; nothing in it promotes a coin for becoming tradeable. Its price arrives only through a TokenState row
 *    (TokenFacts.writePrice), which a probation coin does not have, so it cannot see a cross. 7384 lowered the original
 *    "under $5k waits in probation" cut to $1.5k because "the $5k cut parked nearly every live launch in probation":
 *    the tier was a dead end, so launches were seated on the hot watchlist at birth instead;
 *  - the six latest missed runners (+325 .. +463 %): four were heard at their create event and given their lane verdict
 *    4.7 - 26.7 s later with $3 - $7 in the curve (every lane: liquidity below floor). Runner capture: 22 of the 102
 *    missed 4x runners were refused LANE_LIQ_BELOW_FLOOR and 9 MCAP_TOO_LOW — judged before they could be bought;
 *  - the 220-seat watchlist bounced 353 incoming coins to probation (every seat protected) and evicted 66.
 *
 * The floor watch is the tier as designed. Every pump.fun launch with a witnessed birth is followed on the MARKET-WIDE
 * trade feed (the prints LaunchSniper8748 already reads, no per-coin subscription and no TokenState) for its first
 * hour. The live print that takes its curve market cap to [FLOOR_CAP_USD_8856] (the owner's floor) is the cross: the coin
 * is seated on the watchlist at once ([GlobalTradeRegistry.seatFloorCross8856]: promoted from probation, or added; when
 * the bench is full an under-floor resident gives up its seat and goes back under this watch), asked of the curve tape,
 * and put at the head of the next decision cycle ([targets8856]). While it stays at or above the floor and keeps
 * printing it keeps its seat ([holding8856]). A launch under the floor is not timer-promoted out of probation
 * ([underFloor8856]); it waits here for the cross.
 *
 * It also measures what was never measured: the time from the cross to the bot's first verdict on the coin, how far the
 * price had moved by then, and which gate ruled first ([onDecision8856]).
 * Seating and order only: lanes, safety, structure, sellability, economics and every gate decide exactly as before.
 */
object FloorWatch8856 {
    /** The owner's floor: the curve market cap (price x 1e9 supply x SOL/USD) a launch must reach to be decided on. */
    const val FLOOR_CAP_USD_8856 = 5_000.0
    private const val HOUR_MS = 3_600_000L
    /** A crosser's head-of-cycle claim lasts this long, and is renewed no sooner than this after an evaluation. */
    private const val DUE_TTL_MS = 60_000L
    private const val REVISIT_MS = 10_000L
    private const val MAX_TRACKED = 30_000
    private const val MAX_SAMPLES = 512
    private const val SUPPLY = 1.0e9

    internal class W(val bornMs: Long) {
        var capUsd = 0.0; var lastMs = 0L; var liveBuys = 0
        var crossedAtMs = 0L; var crossCapUsd = 0.0; var dueUntilMs = 0L
        var firstDecisionAtMs = 0L; var firstGate: String? = null; var admittedAtMs = 0L
    }

    private val watch = ConcurrentHashMap<String, W>()
    private val unborn = ConcurrentHashMap<String, Long>()
    private val crossed = AtomicLong(); private val noBirth = AtomicLong(); private val admitted = AtomicLong()
    private val seats = ConcurrentHashMap<String, AtomicLong>()
    private val firstGates = ConcurrentHashMap<String, AtomicLong>()
    private val delaysMs = ArrayDeque<Long>(); private val driftsPct = ArrayDeque<Double>()

    // ── pure ──

    /** Pure: does a live print at [capUsd] cross the floor for a coin that had not crossed ([crossedAtMs] 0)? */
    fun crosses8856(crossedAtMs: Long, capUsd: Double, live: Boolean): Boolean =
        live && crossedAtMs == 0L && capUsd.isFinite() && capUsd >= FLOOR_CAP_USD_8856

    /**
     * Pure: does a crossed coin keep its watchlist seat at [nowMs]? Its last print left it at or above the floor and it has
     * printed within [idleMs] (the lane label's own horizon: a coin silent for a whole label cannot be graded or bought).
     */
    fun holds8856(crossedAtMs: Long, lastCapUsd: Double, lastPrintMs: Long, nowMs: Long,
                  idleMs: Long = TrajectoryGrades8851.FIFTEEN_MIN): Boolean =
        crossedAtMs > 0L && lastCapUsd >= FLOOR_CAP_USD_8856 && nowMs - lastPrintMs in 0L..idleMs

    /** Pure: the [q] quantile of [v] (nearest rank), or null when empty. */
    fun quantile8856(v: List<Double>, q: Double): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return s[((s.size - 1) * q.coerceIn(0.0, 1.0)).toInt()]
    }

    // ── the feed ──

    /**
     * LaunchSniper8748.onPrint8748: one decoded pump.fun print (chain time [eventMs], received [recvMs]). Live prints only;
     * a coin with no witnessed birth, or past its first hour, is not followed. Cheap; never throws.
     */
    fun onPrint8856(mint: String, eventMs: Long, recvMs: Long, priceSol: Double, isBuy: Boolean, live: Boolean) {
        try {
            if (!live || mint.isBlank() || !(priceSol > 0.0) || !priceSol.isFinite()) return
            var s = watch[mint]
            if (s == null) {
                val missed = unborn[mint]
                if (missed != null && recvMs - missed in 0L until DUE_TTL_MS) return
                val born = CanonicalTokenBirthTime7440.peekBirthMs8418(mint, eventMs)
                if (born == null) {
                    noBirth.incrementAndGet()
                    if (unborn.size > MAX_TRACKED) unborn.entries.removeIf { recvMs - it.value > DUE_TTL_MS }
                    unborn[mint] = recvMs
                    return
                }
                if (eventMs - born !in 0L..HOUR_MS) return
                if (watch.size >= MAX_TRACKED) watch.entries.removeIf { recvMs - it.value.bornMs > HOUR_MS + TrajectoryGrades8851.FIFTEEN_MIN }
                if (watch.size >= MAX_TRACKED) return
                unborn.remove(mint)
                s = watch.computeIfAbsent(mint) { W(born) }
            }
            if (eventMs - s.bornMs !in 0L..HOUR_MS) return
            val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            if (!(solUsd > 0.0)) return
            val cap = priceSol * SUPPLY * solUsd
            val cross = synchronized(s) {
                s.capUsd = cap; s.lastMs = recvMs
                if (isBuy) s.liveBuys++
                if (crosses8856(s.crossedAtMs, cap, live)) { s.crossedAtMs = recvMs; s.crossCapUsd = cap; s.dueUntilMs = recvMs + DUE_TTL_MS; true } else false
            }
            if (cross) seat(mint, cap, recvMs)
        } catch (_: Throwable) {}
    }

    private fun seat(mint: String, capUsd: Double, nowMs: Long) {
        crossed.incrementAndGet()
        val how = try { com.lifecyclebot.engine.GlobalTradeRegistry.seatFloorCross8856(mint, capUsd, nowMs) } catch (_: Throwable) { "ERROR" }
        seats.computeIfAbsent(how.substringBefore(':')) { AtomicLong() }.incrementAndGet()
        try { com.lifecyclebot.network.HeliusCurveTape8354.ask8354(mint, nowMs) } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("FLOOR_CROSS_8856_${how.substringBefore(':')}") } catch (_: Throwable) {}
    }

    // ── the readers ──

    /** GlobalTradeRegistry: a followed launch still under the floor (it waits here, not on a probation timer or a hot seat). */
    fun underFloor8856(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = watch[mint] ?: return false
        return synchronized(s) { s.crossedAtMs == 0L && nowMs - s.bornMs in 0L..HOUR_MS }
    }

    /** GlobalTradeRegistry (eviction): a crossed coin that keeps its seat ([holds8856]). */
    fun holding8856(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = watch[mint] ?: return false
        return synchronized(s) { holds8856(s.crossedAtMs, s.capUsd, s.lastMs, nowMs) }
    }

    /**
     * HunterWindow8293: the crossers among [present] that are due an evaluation — inside their claim, still holding, and not
     * evaluated in the last [REVISIT_MS] — newest cross first. Order only; a banned or quarantined mint is never returned.
     */
    fun targets8856(present: Collection<String>, nowMs: Long = System.currentTimeMillis(), limit: Int = 8,
                    lastProcessedAt: (String) -> Long = {
                        try { com.lifecyclebot.engine.GlobalTradeRegistry.getEntry(it)?.lastProcessedAt ?: 0L } catch (_: Throwable) { 0L }
                    }): List<String> {
        if (present.isEmpty() || limit <= 0 || watch.isEmpty()) return emptyList()
        val due = dueCrossers8856(present, nowMs)
        if (due.isEmpty()) return emptyList()
        return due
            .filter { m -> try { !com.lifecyclebot.engine.BannedTokens.isBanned(m) && !com.lifecyclebot.engine.QuarantineStore.isQuarantined(m) &&
                !com.lifecyclebot.engine.TokenBlacklist.isBlocked(m) } catch (_: Throwable) { false } }
            .filter { m -> nowMs - lastProcessedAt(m) >= REVISIT_MS }
            .take(limit)
    }

    /** The crossers among [present] inside their claim and still holding their seat, newest cross first (no ban or revisit filter). */
    internal fun dueCrossers8856(present: Collection<String>, nowMs: Long): List<String> {
        val out = ArrayList<Pair<String, Long>>()
        for (m in present) {
            val s = watch[m] ?: continue
            val at = synchronized(s) { if (s.dueUntilMs >= nowMs && holds8856(s.crossedAtMs, s.capUsd, s.lastMs, nowMs)) s.crossedAtMs else 0L }
            if (at > 0L) out.add(m to at)
        }
        return out.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * ForwardReturnLabeler7731.observe: a decision on [mint] ([admitted], [reason]). For a crossed coin the first one is timed
     * against the cross, the price move since the cross is read from the feed, and the first buy-path gate that ruled
     * (GateOutcome8418.gateOf8418; an execution admit is counted once) is filed.
     */
    fun onDecision8856(mint: String, admittedNow: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis()) {
        val s = watch[mint] ?: return
        var delay = -1L; var drift = Double.NaN
        var gate: String? = null; var admit = false
        synchronized(s) {
            if (s.crossedAtMs == 0L) return
            if (s.firstDecisionAtMs == 0L) {
                s.firstDecisionAtMs = nowMs
                delay = nowMs - s.crossedAtMs
                if (s.crossCapUsd > 0.0 && s.capUsd > 0.0) drift = (s.capUsd / s.crossCapUsd - 1.0) * 100.0
            }
            val g = try { GateOutcome8418.gateOf8418(admittedNow, reason) } catch (_: Throwable) { null }
            if (g == GateOutcome8418.ADMIT_GATE_8418) { if (s.admittedAtMs == 0L) { s.admittedAtMs = nowMs; admit = true } }
            else if (g != null && s.firstGate == null) { s.firstGate = g; gate = g }
            // a decision renews the claim while the coin still holds: it is re-read on its next prints, not left to the rotation
            if (holds8856(s.crossedAtMs, s.capUsd, s.lastMs, nowMs) && s.admittedAtMs == 0L) s.dueUntilMs = nowMs + DUE_TTL_MS
        }
        if (delay >= 0L) synchronized(delaysMs) { delaysMs.addLast(delay); while (delaysMs.size > MAX_SAMPLES) delaysMs.removeFirst() }
        if (drift.isFinite()) synchronized(driftsPct) { driftsPct.addLast(drift); while (driftsPct.size > MAX_SAMPLES) driftsPct.removeFirst() }
        if (admit) admitted.incrementAndGet()
        gate?.let { g -> if (firstGates.size < 200 || firstGates.containsKey(g)) firstGates.computeIfAbsent(g) { AtomicLong() }.incrementAndGet() }
    }

    private fun sec(ms: Double?) = if (ms == null) "-" else "%.1fs".format(java.util.Locale.US, ms / 1000.0)
    private fun pct(v: Double?) = if (v == null) "-" else "%+.1f%%".format(java.util.Locale.US, v)

    fun statusLine8856(nowMs: Long = System.currentTimeMillis()): String {
        var under = 0; var holding = 0
        for (s in watch.values) {
            val state = synchronized(s) {
                when {
                    s.crossedAtMs == 0L -> if (nowMs - s.bornMs in 0L..HOUR_MS) 1 else 0
                    holds8856(s.crossedAtMs, s.capUsd, s.lastMs, nowMs) -> 2
                    else -> 0
                }
            }
            if (state == 1) under++ else if (state == 2) holding++
        }
        val d = synchronized(delaysMs) { delaysMs.map { it.toDouble() } }
        val dr = synchronized(driftsPct) { driftsPct.toList() }
        val seated = seats.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val gates = firstGates.entries.sortedByDescending { it.value.get() }.take(10).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "floorWatch8856[floor=\$${FLOOR_CAP_USD_8856.toInt()} followed=${watch.size} underFloor=$under crossed=${crossed.get()} holdingSeat=$holding " +
            "seated{$seated} noBirth=${noBirth.get()} crossToFirstVerdict{n=${d.size} p50=${sec(quantile8856(d, 0.5))} p90=${sec(quantile8856(d, 0.9))} max=${sec(d.maxOrNull())}} " +
            "moveAtFirstVerdict{p50=${pct(quantile8856(dr, 0.5))} p90=${pct(quantile8856(dr, 0.9))}} admitted=${admitted.get()} firstRefusal{$gates} " +
            "rule=every witnessed pump.fun launch followed on the market-wide feed for its first hour; the live print at the floor seats it and heads the next cycle; " +
            "it keeps the seat while at or above the floor and printing; seating and order only]"
    }

    internal fun resetForTest8856() {
        watch.clear(); unborn.clear(); seats.clear(); firstGates.clear(); crossed.set(0); noBirth.set(0); admitted.set(0)
        synchronized(delaysMs) { delaysMs.clear() }; synchronized(driftsPct) { driftsPct.clear() }
    }

    /** Test seam: follow [mint] born at [bornMs] without the birth registry. */
    internal fun followForTest8856(mint: String, bornMs: Long, capUsd: Double, lastMs: Long, crossedAtMs: Long, dueUntilMs: Long = 0L) {
        watch[mint] = W(bornMs).also { it.capUsd = capUsd; it.lastMs = lastMs; it.crossedAtMs = crossedAtMs; it.crossCapUsd = capUsd; it.dueUntilMs = dueUntilMs }
    }
}
