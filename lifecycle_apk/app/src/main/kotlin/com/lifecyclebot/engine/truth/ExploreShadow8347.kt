package com.lifecyclebot.engine.truth

import android.content.Context
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8347 §FREE_EXPLORATION_IN_SHADOW — owner: "release all the specialists and traders and lanes free in shadow mode.
 * they need to be openly exploring, switching tactics, entry times, learning the full stack. no real money at risk,
 * learn correctly. the poorly performing lanes should then self correct."
 *
 * EvidenceShadow8344 mirrors the LIVE decision (what live would have bought). This book explores what the lanes COULD do:
 *  - every specialist's own native opinion (SpecialistBrainBridge7542.evaluate) that is ELIGIBLE opens a ticket in that
 *    lane, whatever any downstream gate (8114 decision, admission, FDG, hold) says; a [REJECT_SAMPLE_PCT_8347] % sample of
 *    native REJECTS (never a hard-safety reject) opens too, so the lanes learn whether their own refusals are right;
 *  - each ticket runs [Entry8347] x [Exit8347] variants side by side (6): enter NOW / on a PULLBACK / on a CONFIRMed push,
 *    exit as a RUNNER (live doctrine: lane stop, spike tiers, give-back floor) or a SCALP (tight stop, +15 / +30 takes);
 *  - every variant pays the NetEdge8030 round trip at the route-minimum ticket (curve-aware), on observed marks only;
 *  - closes are booked per lane x variant (persisted since the evidence-hold start) and the NOW/RUNNER close of each
 *    eligible ticket is fed to TacticSwitcher with the lane's tactic at open, so tactics rotate on shadow results.
 * Self-correction: [bestVariant8347] is a lane's best PROVEN variant (n >= [MIN_N_8347], mean - 1.5 SE above
 * [MARGIN_PCT_8347], PF >= [MIN_PF_8347]; stricter than 8336's bar because six variants are tried per lane).
 *  - EvidenceHold8336 unlocks the lane on it;
 *  - Entry8114Mode8326 makes the LIVE entry wait for that variant's entry condition ([liveEntryWait8347]).
 * Spends nothing, opens no position, takes no slot or ledger row. Model limits: every fill lands, marks are the fill.
 */
object ExploreShadow8347 {
    enum class Entry8347 { NOW, PULLBACK, CONFIRM }
    enum class Exit8347 { RUNNER, SCALP }

    const val MAX_TICKETS_8347 = 250
    const val TICKET_SOL_8347 = 0.03
    const val REJECT_SAMPLE_PCT_8347 = 10
    const val MIN_N_8347 = 40
    const val MARGIN_PCT_8347 = 2.0
    const val MIN_PF_8347 = 1.3
    private const val REOPEN_GAP_MS_8347 = 15 * 60_000L
    private const val PULLBACK_WAIT_MS_8347 = 5 * 60_000L
    private const val CONFIRM_AFTER_MS_8347 = 60_000L
    private const val CONFIRM_WAIT_MS_8347 = 3 * 60_000L
    private const val CONFIRM_RISE_PCT_8347 = 2.0
    private const val LOST_MARK_MS_8347 = 10 * 60_000L
    private const val RUNNER_TIMEOUT_MS_8347 = 120 * 60_000L
    private const val OTHER_TIMEOUT_MS_8347 = 30 * 60_000L
    private const val SCALP_TIMEOUT_MS_8347 = 10 * 60_000L
    private const val MAX_CLOSES_8347 = 300
    private const val PREFS_8347 = "aate_explore_shadow_8347"

    private class Leg8347(val entry: Entry8347, val exit: Exit8347) {
        var active = false
        var dead = false
        var entryPx = 0.0
        var entryAtMs = 0L
        var held = 1.0
        var banked = 0.0
        var tiers = 0
        var peak = 0.0
        var trough = 0.0
    }

    private class Ticket8347(
        val mint: String, val symbol: String, val lane: String, val setup: String, val tactic: String, val scoreBand: String,
        val signalPx: Double, val atMs: Long, val costPct: Double, val stopPct: Double, val armPct: Double,
        val runner: Boolean, val nativeEligible: Boolean, val legs: List<Leg8347>,
    ) {
        var lastPx = signalPx
        var lastPxAtMs = atMs
    }

    private val tickets = ConcurrentHashMap<String, Ticket8347>()                // mint|lane -> ticket
    private val lastOpenAt = ConcurrentHashMap<String, Long>()
    private val books = ConcurrentHashMap<String, ArrayDeque<DoubleArray>>()     // lane|ENTRY|EXIT -> [atMs, net%]
    private val opened = AtomicLong(0)
    private val closed = AtomicLong(0)
    private val full = AtomicLong(0)
    private val waits = ConcurrentHashMap<String, LongArray>()                   // mint|lane -> [firstAtMs, signalPx bits]
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init8347(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences(PREFS_8347, Context.MODE_PRIVATE)
            prefs = p
            for ((k, v) in p.all) {
                val q = ArrayDeque<DoubleArray>()
                (v as? String)?.split(';')?.forEach { row ->
                    val parts = row.split(',')
                    val at = parts.getOrNull(0)?.toLongOrNull(); val net = parts.getOrNull(1)?.toDoubleOrNull()
                    if (at != null && net != null && net.isFinite()) q.addLast(doubleArrayOf(at.toDouble(), net))
                }
                if (q.isNotEmpty()) books[k] = q
            }
        } catch (_: Throwable) {}
    }

    // ── pure ──

    /** Pure: the pullback depth (%) an entry waits for — half the lane stop, between 4 and 12. */
    fun pullbackPct8347(stopPct: Double): Double = (stopPct * 0.5).coerceIn(4.0, 12.0)

    /** Pure: is a native reject sampled for exploration? Deterministic on the mint, [pct] of them. */
    fun sampled8347(mint: String, lane: String, pct: Int = REJECT_SAMPLE_PCT_8347): Boolean =
        Math.floorMod((mint + lane).hashCode(), 100) < pct

    /** Pure: the variant proof, stricter than one-shot (six variants per lane are tried). */
    fun variantProof8347(nets: List<Double>): EvidenceHold8336.Proof8336 {
        val base = EvidenceHold8336.proof8336(nets, nets.map { it * TICKET_SOL_8347 / 100.0 })
        val proven = base.n >= MIN_N_8347 && base.meanPct - 1.5 * base.sePct > MARGIN_PCT_8347 && base.pf >= MIN_PF_8347
        return base.copy(proven = proven)
    }

    // ── open (SpecialistBrainBridge7542.evaluate) ──

    /** Every native opinion for [ts]: eligible ones open a ticket in their lane, a sample of non-safety rejects too. */
    fun onOpinions8347(ts: TokenState, opinions: Map<String, com.lifecyclebot.engine.SpecialistBrainBridge7542.Opinion>, nowMs: Long = System.currentTimeMillis()) {
        try {
            val px = ts.lastPrice.takeIf { it.isFinite() && it > 0.0 } ?: return
            if (ts.lastPriceUpdate <= 0L || nowMs - ts.lastPriceUpdate > 45_000L) return
            for ((laneRaw, o) in opinions) {
                val lane = canon(laneRaw)
                if (!o.eligible) {
                    if (Entry8114Mode8326.hardSafety8326(o.reason) || !o.gradeable || !sampled8347(ts.mint, lane)) continue
                }
                open8347(ts, lane, o, px, nowMs)
            }
        } catch (_: Throwable) {}
    }

    private fun open8347(ts: TokenState, lane: String, o: com.lifecyclebot.engine.SpecialistBrainBridge7542.Opinion, px: Double, nowMs: Long) {
        val key = "${ts.mint}|$lane"
        if (tickets.containsKey(key)) return
        lastOpenAt[key]?.let { if (nowMs - it in 0L until REOPEN_GAP_MS_8347) return }
        if (tickets.size >= MAX_TICKETS_8347) { full.incrementAndGet(); return }
        val liq = try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }
        val cost = try { NetEdge8030.costPct8030(lane, TICKET_SOL_8347, liq, ts.mint, ts.lastMcap) } catch (_: Throwable) { Double.NaN }
        val stop = try { FieldManual7715.mandateFor(lane).invalidationPct } catch (_: Throwable) { 10.0 }
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        val arm = try { com.lifecyclebot.engine.RunnerExitProfile7277.giveBackArmPct7791(lane) } catch (_: Throwable) { 20.0 }
        val tactic = try { com.lifecyclebot.engine.learning.TacticSwitcher.tacticForToken8050(ts, lane, o.score).name } catch (_: Throwable) { "MOMENTUM" }
        val band = try { com.lifecyclebot.engine.LosingPatternMemory.scoreBand(o.score) } catch (_: Throwable) { "" }
        val legs = if (o.eligible) Entry8347.values().flatMap { e -> Exit8347.values().map { x -> Leg8347(e, x) } }
            else listOf(Leg8347(Entry8347.NOW, Exit8347.RUNNER))
        val t = Ticket8347(ts.mint, ts.symbol, lane, o.setup.ifBlank { "NONE" }, tactic, band, px, nowMs,
            if (cost.isFinite()) cost else 7.0, stop.coerceIn(3.0, 40.0), arm, runner, o.eligible, legs)
        t.legs.filter { it.entry == Entry8347.NOW }.forEach { it.active = true; it.entryPx = px; it.entryAtMs = nowMs }
        tickets[key] = t
        lastOpenAt[key] = nowMs
        if (lastOpenAt.size > 10_000) lastOpenAt.entries.removeIf { nowMs - it.value > REOPEN_GAP_MS_8347 }
        opened.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EXPLORE_SHADOW_OPEN_8347_${lane}_${if (o.eligible) "NATIVE" else "REJECT_SAMPLE"}") } catch (_: Throwable) {}
    }

    // ── tick (BotService shadow loop) ──

    fun tick8347(tokens: Map<String, TokenState>, nowMs: Long = System.currentTimeMillis()) {
        if (tickets.isEmpty()) return
        val done = ArrayList<String>()
        for ((key, t) in tickets) {
            try {
                val ts = tokens[t.mint]
                val fresh = ts?.takeIf { it.lastPriceUpdate > 0L && nowMs - it.lastPriceUpdate <= 45_000L }?.lastPrice?.takeIf { it > 0.0 }
                    ?: ForwardReturnLabeler7731.markFor7948(t.mint, { null }, nowMs)?.takeIf { it > 0.0 }
                if (fresh != null) { t.lastPx = fresh; t.lastPxAtMs = nowMs }
                val lost = nowMs - t.lastPxAtMs >= LOST_MARK_MS_8347
                for (leg in t.legs) {
                    if (leg.dead) continue
                    if (!leg.active) { arm8347(t, leg, fresh, nowMs); continue }
                    step8347(t, leg, fresh, lost, nowMs)
                }
                if (t.legs.all { it.dead }) done += key
            } catch (_: Throwable) { done += key }
        }
        done.forEach { tickets.remove(it) }
    }

    private fun arm8347(t: Ticket8347, leg: Leg8347, px: Double?, nowMs: Long) {
        val age = nowMs - t.atMs
        when (leg.entry) {
            Entry8347.PULLBACK -> {
                if (px != null && px <= t.signalPx * (1.0 - pullbackPct8347(t.stopPct) / 100.0)) { leg.active = true; leg.entryPx = px; leg.entryAtMs = nowMs }
                else if (age >= PULLBACK_WAIT_MS_8347) leg.dead = true   // never filled: no trade, nothing booked
            }
            Entry8347.CONFIRM -> {
                if (px != null && age >= CONFIRM_AFTER_MS_8347 && px >= t.signalPx * (1.0 + CONFIRM_RISE_PCT_8347 / 100.0)) { leg.active = true; leg.entryPx = px; leg.entryAtMs = nowMs }
                else if (age >= CONFIRM_WAIT_MS_8347) leg.dead = true
            }
            Entry8347.NOW -> { leg.active = true; leg.entryPx = t.signalPx; leg.entryAtMs = t.atMs }
        }
    }

    private fun step8347(t: Ticket8347, leg: Leg8347, px: Double?, lost: Boolean, nowMs: Long) {
        val g = (t.lastPx / leg.entryPx - 1.0) * 100.0
        if (!g.isFinite()) { leg.dead = true; return }
        if (g > leg.peak) leg.peak = g
        if (g < leg.trough) leg.trough = g
        val why: String? = when (leg.exit) {
            Exit8347.RUNNER -> {
                if (px != null) while (leg.tiers < com.lifecyclebot.engine.SpikeCapture7943.TIERS.size) {
                    val (at, frac) = com.lifecyclebot.engine.SpikeCapture7943.TIERS[leg.tiers]
                    if (g < at) break
                    val sold = leg.held * frac; leg.banked += sold * g; leg.held -= sold; leg.tiers++
                }
                val floor = EvidenceShadow8344.giveBackFloor8344(leg.peak, t.armPct, t.costPct)
                when {
                    leg.held <= 0.0001 -> "SOLD_OUT"
                    px != null && g <= -t.stopPct -> "STOP"
                    px != null && floor != null && g <= floor -> "GIVEBACK_FLOOR"
                    nowMs - leg.entryAtMs >= (if (t.runner) RUNNER_TIMEOUT_MS_8347 else OTHER_TIMEOUT_MS_8347) -> "TIMEOUT"
                    lost -> "LOST_MARK"
                    else -> null
                }
            }
            Exit8347.SCALP -> {
                if (px != null && leg.tiers == 0 && g >= 15.0) { val s = leg.held * 0.5; leg.banked += s * g; leg.held -= s; leg.tiers = 1 }
                if (px != null && leg.tiers == 1 && g >= 30.0) { leg.banked += leg.held * g; leg.held = 0.0; leg.tiers = 2 }
                val floor = if (leg.peak >= 10.0) maxOf(t.costPct.coerceAtMost(leg.peak * 0.5), leg.peak * 0.5) else null
                when {
                    leg.held <= 0.0001 -> "SOLD_OUT"
                    px != null && g <= -(t.stopPct * 0.6).coerceAtLeast(4.0) -> "STOP"
                    px != null && floor != null && g <= floor -> "GIVEBACK_FLOOR"
                    nowMs - leg.entryAtMs >= SCALP_TIMEOUT_MS_8347 -> "TIMEOUT"
                    lost -> "LOST_MARK"
                    else -> null
                }
            }
        }
        if (why != null) close8347(t, leg, EvidenceShadow8344.netPct8344(leg.banked, leg.held, g, t.costPct), why, nowMs)
    }

    private fun close8347(t: Ticket8347, leg: Leg8347, net: Double, why: String, nowMs: Long) {
        leg.dead = true
        val v = "${t.lane}|${leg.entry}|${leg.exit}"
        val q = books.computeIfAbsent(v) { ArrayDeque() }
        synchronized(q) {
            q.addLast(doubleArrayOf(nowMs.toDouble(), net.coerceIn(-100.0, 1_000.0)))
            while (q.size > MAX_CLOSES_8347) q.removeFirst()
            try { prefs?.edit()?.putString(v, q.joinToString(";") { "${it[0].toLong()},${"%.3f".format(it[1])}" })?.apply() } catch (_: Throwable) {}
        }
        closed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EXPLORE_SHADOW_CLOSE_8347_${leg.exit}_$why") } catch (_: Throwable) {}
        // Tactics learn from the variant closest to how live enters and exits; native rejects do not attribute a tactic.
        if (t.nativeEligible && leg.entry == Entry8347.NOW && leg.exit == Exit8347.RUNNER && t.scoreBand.isNotBlank()) try {
            com.lifecyclebot.engine.learning.TacticSwitcher.onCanonicalTradeClosed6486(t.lane, t.scoreBand, t.tactic, net)
        } catch (_: Throwable) {}
    }

    // ── self-correction ──

    private fun canon(lane: String): String =
        (try { CanonicalLaneIdentity6506.canonical(lane) } catch (_: Throwable) { "" }).uppercase().ifBlank { lane.trim().uppercase() }

    fun variantRecord8347(lane: String, entry: Entry8347, exit: Exit8347, sinceMs: Long): EvidenceHold8336.Proof8336 {
        val q = books["${canon(lane)}|$entry|$exit"] ?: return variantProof8347(emptyList())
        val nets = synchronized(q) { q.filter { it[0] >= sinceMs }.map { it[1] } }
        return variantProof8347(nets)
    }

    /** The lane's best PROVEN variant (highest mean - 1.5 SE), or null while none has proven. */
    fun bestVariant8347(lane: String, sinceMs: Long): Pair<Pair<Entry8347, Exit8347>, EvidenceHold8336.Proof8336>? =
        Entry8347.values().flatMap { e -> Exit8347.values().map { x -> (e to x) to variantRecord8347(lane, e, x, sinceMs) } }
            .filter { it.second.proven }
            .maxByOrNull { it.second.meanPct - 1.5 * it.second.sePct }

    /**
     * Entry8114Mode8326.decide8326, after a LIVE admit: when the lane's best proven variant enters on a PULLBACK or a
     * CONFIRM, the live entry waits for that condition (the first admit's mark is the signal). Null = buy now.
     */
    fun liveEntryWait8347(ts: TokenState, lane: String, sinceMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
        val best = bestVariant8347(lane, sinceMs) ?: return null
        val entry = best.first.first
        if (entry == Entry8347.NOW) return null
        val px = ts.lastPrice.takeIf { it.isFinite() && it > 0.0 } ?: return "ENTRY_TIMING_NO_MARK_8347"
        val key = "${ts.mint}|${canon(lane)}"
        val w = waits.computeIfAbsent(key) { longArrayOf(nowMs, java.lang.Double.doubleToRawLongBits(px)) }
        val signal = java.lang.Double.longBitsToDouble(w[1])
        val age = nowMs - w[0]
        val stop = try { FieldManual7715.mandateFor(lane).invalidationPct } catch (_: Throwable) { 10.0 }
        val ready = when (entry) {
            Entry8347.PULLBACK -> px <= signal * (1.0 - pullbackPct8347(stop) / 100.0)
            Entry8347.CONFIRM -> age >= CONFIRM_AFTER_MS_8347 && px >= signal * (1.0 + CONFIRM_RISE_PCT_8347 / 100.0)
            Entry8347.NOW -> true
        }
        val limit = if (entry == Entry8347.PULLBACK) PULLBACK_WAIT_MS_8347 else CONFIRM_WAIT_MS_8347
        if (ready) { waits.remove(key); return null }
        if (age >= limit) { waits.remove(key); return "ENTRY_TIMING_EXPIRED_8347_$entry" }
        if (waits.size > 2_000) waits.entries.removeIf { nowMs - it.value[0] > PULLBACK_WAIT_MS_8347 }
        try { PipelineHealthCollector.labelInc("ENTRY_TIMING_WAIT_8347_${canon(lane)}_$entry") } catch (_: Throwable) {}
        return "ENTRY_TIMING_WAIT_8347_$entry"
    }

    fun statusLine8347(sinceMs: Long): String {
        val lanes = books.keys.map { it.substringBefore('|') }.toSortedSet().joinToString(" · ") { l ->
            val cells = Entry8347.values().flatMap { e -> Exit8347.values().map { x -> Triple(e, x, variantRecord8347(l, e, x, sinceMs)) } }
                .filter { it.third.n > 0 }
                .joinToString(",") { (e, x, p) -> "${e.name.take(4)}/${x.name.take(4)} n${p.n} ${"%+.1f".format(p.meanPct)}%${if (p.proven) "*" else ""}" }
            val best = bestVariant8347(l, sinceMs)?.first?.let { "${it.first}/${it.second}" } ?: "-"
            "$l best=$best [$cells]"
        }.ifBlank { "no exploration closes yet" }
        return "exploreShadow8347[tickets=${tickets.size}/$MAX_TICKETS_8347 opened=${opened.get()} legsClosed=${closed.get()} full=${full.get()} " +
            "rejectSample=$REJECT_SAMPLE_PCT_8347% bar=n>=$MIN_N_8347,mean-1.5se>$MARGIN_PCT_8347%,pf>=$MIN_PF_8347 (* = proven) | $lanes]"
    }
}
