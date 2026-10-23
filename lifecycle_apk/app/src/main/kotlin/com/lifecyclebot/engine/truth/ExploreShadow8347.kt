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

    const val MAX_TICKETS_8347 = 1_000   // V5.0.8351 — 250 filled in 4 min and skipped ~29,500 (8350)
    const val MAX_TICKETS_PER_LANE_8351 = 120   // V5.0.8351 — one busy lane cannot starve the rest
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
        val band: String = "UNK",   // V5.0.8351 — market-cap band at open, a cohort dimension
    ) {
        var lastPx = signalPx
        var lastPxAtMs = atMs
        // V5.0.8355 — persistence state: a leg's tier / peak / trough moved (coalesced write); a leg armed or closed (forced write)
        var dirty8355 = false
        var force8355 = false
        var lastWriteMs8355 = 0L
    }

    private val tickets = ConcurrentHashMap<String, Ticket8347>()                // mint|lane -> ticket
    private val lastOpenAt = ConcurrentHashMap<String, Long>()
    private val books = ConcurrentHashMap<String, ArrayDeque<DoubleArray>>()     // lane|ENTRY|EXIT -> [atMs, net%]
    private val opened = AtomicLong(0)
    private val closed = AtomicLong(0)
    private val full = AtomicLong(0)
    private val waits = ConcurrentHashMap<String, LongArray>()                   // mint|lane -> [firstAtMs, signalPx bits]
    @Volatile private var prefs: android.content.SharedPreferences? = null

    // ── V5.0.8355 §SHADOW_EVIDENCE_SURVIVES_RESTARTS — open tickets (every leg's state) and the liveEntryWait8347 waits are
    // persisted in their own prefs (the close books untouched): a ticket is written on open, when a leg arms or closes, and
    // coalesced (at most once per 10 s) when a leg's tier / peak / trough moves; removed once every leg is dead. init8347
    // restores them with their real entry marks and clocks, never re-priced; a leg still waiting re-arms on the real signal. ──
    private const val PREFS_OPEN_8355 = "aate_explore_shadow_open_8355"
    private const val TICKET_KEY_PREFIX_8355 = "T:"
    private const val WAITS_KEY_8355 = "W"
    @Volatile private var openPrefs8355: android.content.SharedPreferences? = null
    @Volatile private var waitsDirty8355 = false
    @Volatile private var waitsWrittenAtMs8355 = 0L
    private val restored8355 = AtomicLong(0)
    private val expiredOnRestore8355 = AtomicLong(0)
    private val waitsRestored8355 = AtomicLong(0)

    private fun ticketRec8355(t: Ticket8347): ShadowPersist8355.TicketRec8355 = ShadowPersist8355.TicketRec8355(
        t.mint, t.symbol, t.lane, t.setup, t.tactic, t.scoreBand, t.band, t.signalPx, t.atMs, t.costPct, t.stopPct, t.armPct,
        t.runner, t.nativeEligible, t.lastPx, t.lastPxAtMs,
        t.legs.map { l -> ShadowPersist8355.LegRec8355(l.entry.name, l.exit.name, l.active, l.dead, l.entryPx, l.entryAtMs, l.held, l.banked, l.tiers, l.peak, l.trough) },
    )

    private fun persistTicket8355(key: String, t: Ticket8347, nowMs: Long) {
        t.dirty8355 = false; t.force8355 = false; t.lastWriteMs8355 = nowMs
        val enc = ShadowPersist8355.encodeTicket8355(ticketRec8355(t))
        try { openPrefs8355?.edit()?.putString(TICKET_KEY_PREFIX_8355 + key, enc)?.apply() } catch (_: Throwable) {}
    }

    private fun forgetTicket8355(key: String) {
        try { openPrefs8355?.edit()?.remove(TICKET_KEY_PREFIX_8355 + key)?.apply() } catch (_: Throwable) {}
    }

    /** The waits map, whole (it is small), at most once per 10 s and only after a change. */
    private fun flushWaits8355(nowMs: Long) {
        if (!ShadowPersist8355.writeDue8355(waitsDirty8355, waitsWrittenAtMs8355, nowMs)) return
        waitsDirty8355 = false; waitsWrittenAtMs8355 = nowMs
        val ws = waits.entries.map { (k, w) -> ShadowPersist8355.WaitRec8355(k, w[0], java.lang.Double.longBitsToDouble(w[1])) }
        try { openPrefs8355?.edit()?.putString(WAITS_KEY_8355, ShadowPersist8355.encodeWaits8355(ws))?.apply() } catch (_: Throwable) {}
    }

    private fun ticketFromRec8355(r: ShadowPersist8355.TicketRec8355): Ticket8347? {
        val legs = ArrayList<Leg8347>()
        for (l in r.legs) {
            val e = Entry8347.values().firstOrNull { it.name == l.entry } ?: return null
            val x = Exit8347.values().firstOrNull { it.name == l.exit } ?: return null
            val leg = Leg8347(e, x)
            leg.active = l.active; leg.dead = l.dead; leg.entryPx = l.entryPx; leg.entryAtMs = l.entryAtMs
            leg.held = l.held; leg.banked = l.banked; leg.tiers = l.tiers; leg.peak = l.peak; leg.trough = l.trough
            legs += leg
        }
        if (legs.isEmpty() || legs.all { it.dead }) return null
        val t = Ticket8347(r.mint, r.symbol, r.lane, r.setup, r.tactic, r.scoreBand, r.signalPx, r.atMs, r.costPct, r.stopPct, r.armPct,
            r.runner, r.nativeEligible, legs, r.band)
        t.lastPx = r.lastPx; t.lastPxAtMs = r.lastPxAtMs
        return t
    }

    private fun restoreOpen8355(ctx: Context, nowMs: Long) {
        try {
            val p = ctx.getSharedPreferences(PREFS_OPEN_8355, Context.MODE_PRIVATE)
            openPrefs8355 = p
            val drop = ArrayList<String>()
            val perLane = HashMap<String, Int>()
            var n = 0L
            for ((k, v) in p.all) {
                val s = v as? String
                if (k == WAITS_KEY_8355) {
                    for (w in ShadowPersist8355.decodeWaits8355(s)) {
                        if (nowMs - w.firstAtMs !in 0L until PULLBACK_WAIT_MS_8347) continue
                        waits[w.key] = longArrayOf(w.firstAtMs, java.lang.Double.doubleToRawLongBits(w.signalPx))
                        waitsRestored8355.incrementAndGet()
                    }
                    continue
                }
                if (!k.startsWith(TICKET_KEY_PREFIX_8355)) { drop += k; continue }
                val key = k.substring(TICKET_KEY_PREFIX_8355.length)
                val r = ShadowPersist8355.decodeTicket8355(s)
                if (r == null) { drop += k; continue }
                // the ticket's own window: the longest leg timeout plus the longest entry wait, then the lost-mark grace
                val window = (if (r.runner) RUNNER_TIMEOUT_MS_8347 else OTHER_TIMEOUT_MS_8347) + PULLBACK_WAIT_MS_8347
                if (ShadowPersist8355.expiredOnRestore8355(r.atMs, window, nowMs, LOST_MARK_MS_8347)) { drop += k; expiredOnRestore8355.incrementAndGet(); continue }
                val t = ticketFromRec8355(r)
                if (t == null || tickets.containsKey(key) || tickets.size >= MAX_TICKETS_8347 ||
                    (perLane[t.lane] ?: 0) >= MAX_TICKETS_PER_LANE_8351) { drop += k; continue }
                t.lastWriteMs8355 = nowMs
                tickets[key] = t
                perLane[t.lane] = (perLane[t.lane] ?: 0) + 1
                lastOpenAt[key] = r.atMs
                n++
            }
            if (drop.isNotEmpty()) { val e = p.edit(); for (k in drop) e.remove(k); e.apply() }
            restored8355.addAndGet(n)
        } catch (_: Throwable) {}
    }

    /** V5.0.8355 — restored tickets / expired-on-restore / restored waits (tests and the status line). */
    fun restoreCounts8355(): Triple<Long, Long, Long> = Triple(restored8355.get(), expiredOnRestore8355.get(), waitsRestored8355.get())

    fun init8347(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        restoreOpen8355(ctx, nowMs)   // V5.0.8355
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
        if (tickets.values.count { it.lane == lane } >= MAX_TICKETS_PER_LANE_8351) { full.incrementAndGet(); return }
        val liq = try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }
        val cost = try { NetEdge8030.costPct8030(lane, TICKET_SOL_8347, liq, ts.mint, ts.lastMcap) } catch (_: Throwable) { Double.NaN }
        val stop = try { FieldManual7715.mandateFor(lane).invalidationPct } catch (_: Throwable) { 10.0 }
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        val arm = try { com.lifecyclebot.engine.RunnerExitProfile7277.giveBackArmPct7791(lane) } catch (_: Throwable) { 20.0 }
        val tactic = try { com.lifecyclebot.engine.learning.TacticSwitcher.tacticForToken8050(ts, lane, o.score).name } catch (_: Throwable) { "MOMENTUM" }
        val band = try { com.lifecyclebot.engine.LosingPatternMemory.scoreBand(o.score) } catch (_: Throwable) { "" }
        val legs = if (o.eligible) Entry8347.values().flatMap { e -> Exit8347.values().map { x -> Leg8347(e, x) } }
            else listOf(Leg8347(Entry8347.NOW, Exit8347.RUNNER))
        val mcapBand8351 = try { ForwardReturnLabeler7731.mcapBand(TrustedMcap8019.mcap8019(ts)) } catch (_: Throwable) { "UNK" }
        val t = Ticket8347(ts.mint, ts.symbol, lane, o.setup.ifBlank { "NONE" }, tactic, band, px, nowMs,
            if (cost.isFinite()) cost else 7.0, stop.coerceIn(3.0, 40.0), arm, runner, o.eligible, legs, mcapBand8351)
        t.legs.filter { it.entry == Entry8347.NOW }.forEach { it.active = true; it.entryPx = px; it.entryAtMs = nowMs }
        tickets[key] = t
        persistTicket8355(key, t, nowMs)   // V5.0.8355 — written on open
        lastOpenAt[key] = nowMs
        if (lastOpenAt.size > 10_000) lastOpenAt.entries.removeIf { nowMs - it.value > REOPEN_GAP_MS_8347 }
        opened.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EXPLORE_SHADOW_OPEN_8347_${lane}_${if (o.eligible) "NATIVE" else "REJECT_SAMPLE"}") } catch (_: Throwable) {}
    }

    // ── tick (BotService shadow loop) ──

    fun tick8347(tokens: Map<String, TokenState>, nowMs: Long = System.currentTimeMillis()) {
        flushWaits8355(nowMs)   // V5.0.8355 — the waits map, when it changed (independent of the tickets)
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
                if (t.legs.all { it.dead }) {
                    done += key
                } else if (ShadowPersist8355.writeDue8355(t.dirty8355, t.lastWriteMs8355, nowMs, t.force8355)) {
                    persistTicket8355(key, t, nowMs)   // V5.0.8355 — a leg armed / closed writes now; a tier / peak move waits for the 10 s coalesce; nothing else writes
                }
            } catch (_: Throwable) { done += key }
        }
        done.forEach { tickets.remove(it); forgetTicket8355(it) }   // V5.0.8355 — every leg dead: the ticket leaves the open prefs
    }

    private fun arm8347(t: Ticket8347, leg: Leg8347, px: Double?, nowMs: Long) {
        val age = nowMs - t.atMs
        when (leg.entry) {
            Entry8347.PULLBACK -> {
                if (px != null && px <= t.signalPx * (1.0 - pullbackPct8347(t.stopPct) / 100.0)) { leg.active = true; leg.entryPx = px; leg.entryAtMs = nowMs; t.force8355 = true }
                else if (age >= PULLBACK_WAIT_MS_8347) { leg.dead = true; t.force8355 = true }   // never filled: no trade, nothing booked
            }
            Entry8347.CONFIRM -> {
                if (px != null && age >= CONFIRM_AFTER_MS_8347 && px >= t.signalPx * (1.0 + CONFIRM_RISE_PCT_8347 / 100.0)) { leg.active = true; leg.entryPx = px; leg.entryAtMs = nowMs; t.force8355 = true }
                else if (age >= CONFIRM_WAIT_MS_8347) { leg.dead = true; t.force8355 = true }
            }
            Entry8347.NOW -> { leg.active = true; leg.entryPx = t.signalPx; leg.entryAtMs = t.atMs; t.force8355 = true }
        }
    }

    private fun step8347(t: Ticket8347, leg: Leg8347, px: Double?, lost: Boolean, nowMs: Long) {
        val g = (t.lastPx / leg.entryPx - 1.0) * 100.0
        if (!g.isFinite()) { leg.dead = true; t.force8355 = true; return }
        if (g > leg.peak) { leg.peak = g; t.dirty8355 = true }       // V5.0.8355 — peak / trough / tier moves are persisted changes
        if (g < leg.trough) { leg.trough = g; t.dirty8355 = true }
        val why: String? = when (leg.exit) {
            Exit8347.RUNNER -> {
                if (px != null) while (leg.tiers < com.lifecyclebot.engine.SpikeCapture7943.TIERS.size) {
                    val (at, frac) = com.lifecyclebot.engine.SpikeCapture7943.TIERS[leg.tiers]
                    if (g < at) break
                    val sold = leg.held * frac; leg.banked += sold * g; leg.held -= sold; leg.tiers++; t.dirty8355 = true
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
                if (px != null && leg.tiers == 0 && g >= 15.0) { val s = leg.held * 0.5; leg.banked += s * g; leg.held -= s; leg.tiers = 1; t.dirty8355 = true }
                if (px != null && leg.tiers == 1 && g >= 30.0) { leg.banked += leg.held * g; leg.held = 0.0; leg.tiers = 2; t.dirty8355 = true }
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
        t.force8355 = true   // V5.0.8355 — a leg close is written now (the ticket itself leaves the prefs once every leg is dead)
        val v = "${t.lane}|${leg.entry}|${leg.exit}"
        // V5.0.8351 — the variant book, plus three cohort books (setup, market-cap band, native yes / sampled no).
        bookKey8351(v, net, nowMs)
        bookKey8351("C|$v|S:${t.setup}", net, nowMs)
        bookKey8351("C|$v|M:${t.band}", net, nowMs)
        bookKey8351("C|$v|R:${if (t.nativeEligible) "NATIVE" else "REJECT"}", net, nowMs)
        closed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EXPLORE_SHADOW_CLOSE_8347_${leg.exit}_$why") } catch (_: Throwable) {}
        // Tactics learn from the variant closest to how live enters and exits; native rejects do not attribute a tactic.
        if (t.nativeEligible && leg.entry == Entry8347.NOW && leg.exit == Exit8347.RUNNER && t.scoreBand.isNotBlank()) try {
            com.lifecyclebot.engine.learning.TacticSwitcher.onCanonicalTradeClosed6486(t.lane, t.scoreBand, t.tactic, net)
        } catch (_: Throwable) {}
    }

    private fun bookKey8351(key: String, net: Double, nowMs: Long) {
        val q = books.computeIfAbsent(key) { ArrayDeque() }
        synchronized(q) {
            q.addLast(doubleArrayOf(nowMs.toDouble(), net.coerceIn(-100.0, 1_000.0)))
            while (q.size > MAX_CLOSES_8347) q.removeFirst()
            try { prefs?.edit()?.putString(key, q.joinToString(";") { "${it[0].toLong()},${"%.3f".format(it[1])}" })?.apply() } catch (_: Throwable) {}
        }
    }

    // ── V5.0.8351 §TUNE_THE_FAILING_SPECIALISTS_INTO_GREEN — a red lane is usually green somewhere: in one setup, one
    // market-cap band, one entry / exit. The cohort books find that niche; live then trades the lane ONLY there, with that
    // niche's entry and exit, while the shadow keeps exploring everything. A cohort needs more proof than a whole variant:
    // n >= MIN_N_8347, mean - 2 SE above the margin, PF >= 1.4, and BOTH halves of its record (older / newer) positive,
    // so a burst of luck in one hour cannot open a lane. ──

    const val COHORT_SE_MULT_8351 = 2.0
    const val COHORT_MIN_PF_8351 = 1.4

    /** Pure: a cohort's proof — the variant bar made stricter, plus older-half and newer-half means both above zero. */
    fun cohortProof8351(netsInOrder: List<Double>): EvidenceHold8336.Proof8336 {
        val base = EvidenceHold8336.proof8336(netsInOrder, netsInOrder.map { it * TICKET_SOL_8347 / 100.0 })
        val half = netsInOrder.size / 2
        val older = netsInOrder.take(half).filter { it.isFinite() }
        val newer = netsInOrder.drop(half).filter { it.isFinite() }
        val consistent = older.isNotEmpty() && newer.isNotEmpty() && older.average() > 0.0 && newer.average() > 0.0
        val proven = base.n >= MIN_N_8347 && base.meanPct - COHORT_SE_MULT_8351 * base.sePct > MARGIN_PCT_8347 &&
            base.pf >= COHORT_MIN_PF_8351 && consistent
        return base.copy(proven = proven)
    }

    /** A lane's adopted way to trade: entry, exit and (when the proof is a cohort) the niche it is limited to. */
    data class Choice8351(val entry: Entry8347, val exit: Exit8347, val dim: String?, val value: String?, val proof: EvidenceHold8336.Proof8336) {
        val label: String get() = "$entry/$exit" + (if (dim != null) " $dim:$value" else "")
    }

    private fun netsSince(key: String, sinceMs: Long): List<Double> {
        val q = books[key] ?: return emptyList()
        return synchronized(q) { q.filter { it[0] >= sinceMs }.map { it[1] } }
    }

    private val choiceCache8351 = ConcurrentHashMap<String, Pair<Long, Choice8351?>>()
    private const val CHOICE_CACHE_MS_8351 = 30_000L

    /** The lane's best proven way to trade (a whole variant, or a setup / market-cap-band cohort of one), or null. Cached 30 s. */
    fun bestFor8351(lane: String, sinceMs: Long): Choice8351? {
        val l = canon(lane)
        val now = System.currentTimeMillis()
        choiceCache8351[l]?.let { (at, c) -> if (now - at in 0L until CHOICE_CACHE_MS_8351) return c }
        val c = computeBest8351(l, sinceMs)
        choiceCache8351[l] = now to c
        return c
    }

    private fun computeBest8351(l: String, sinceMs: Long): Choice8351? {
        val out = ArrayList<Choice8351>()
        for (e in Entry8347.values()) for (x in Exit8347.values()) {
            val v = "$l|$e|$x"
            variantProof8347(netsSince(v, sinceMs)).takeIf { it.proven }?.let { out += Choice8351(e, x, null, null, it) }
            val prefix = "C|$v|"
            for (k in books.keys) {
                if (!k.startsWith(prefix)) continue
                val tail = k.removePrefix(prefix)
                val dim = tail.substringBefore(':'); val value = tail.substringAfter(':')
                if (dim == "R") continue                      // native yes / sampled no is a diagnostic, never a live niche
                if (value == "NONE" || value == "UNK" || value == "DATA_INCOMPLETE" || value == "NO_TRIGGER") continue
                cohortProof8351(netsSince(k, sinceMs)).takeIf { it.proven }?.let { out += Choice8351(e, x, dim, value, it) }
            }
        }
        // A whole-variant proof beats a niche of equal strength (it trades more); otherwise the strongest lower bound wins.
        return out.maxByOrNull { (it.proof.meanPct - COHORT_SE_MULT_8351 * it.proof.sePct) + (if (it.dim == null) 0.5 else 0.0) }
    }

    /** Pure: does a candidate (its setup, its market-cap band) sit inside [c]'s niche? A whole-variant choice admits all. */
    fun inNiche8351(c: Choice8351, setup: String?, band: String?): Boolean = when (c.dim) {
        null -> true
        "S" -> setup != null && setup.equals(c.value, ignoreCase = true)
        "M" -> band != null && band.equals(c.value, ignoreCase = true)
        else -> true
    }

    /**
     * Entry8114Mode8326.decide8326, after a LIVE admit: when the lane is unlocked by a NICHE, a candidate outside it is
     * refused (NICHE_ONLY_8351_<lane>). The shadow keeps exploring every candidate either way.
     */
    fun nicheRefusal8351(ts: TokenState, lane: String, sinceMs: Long): String? {
        val c = bestFor8351(lane, sinceMs) ?: return null
        if (c.dim == null) return null
        val setup = try { com.lifecyclebot.engine.SpecialistBrainBridge7542.evaluate(ts).opinions.entries
            .firstOrNull { canon(it.key) == canon(lane) }?.value?.setup } catch (_: Throwable) { null }
        val band = try { ForwardReturnLabeler7731.mcapBand(TrustedMcap8019.mcap8019(ts)) } catch (_: Throwable) { null }
        if (inNiche8351(c, setup, band)) return null
        try { PipelineHealthCollector.labelInc("NICHE_ONLY_8351_${canon(lane)}") } catch (_: Throwable) {}
        return "NICHE_ONLY_8351_${canon(lane)}_${c.dim}:${c.value}"
    }

    /** The exit style live adopts for [lane] (SCALP only when a SCALP choice has proven), else null = the lane doctrine. */
    fun liveExitStyle8351(lane: String, sinceMs: Long): Exit8347? = bestFor8351(lane, sinceMs)?.exit?.takeIf { it == Exit8347.SCALP }

    /**
     * Pure: is the lane's own native filter inverted? Its sampled NOs (R:REJECT) beat its YESes (R:NATIVE) on the
     * NOW / RUNNER variant by more than [gapPct] with at least [minN] closes each.
     */
    fun filterInverted8351(nativeNets: List<Double>, rejectNets: List<Double>, minN: Int = 20, gapPct: Double = 5.0): Boolean =
        nativeNets.size >= minN && rejectNets.size >= minN && rejectNets.average() - nativeNets.average() > gapPct

    fun inverted8351(lane: String, sinceMs: Long): Boolean {
        val v = "${canon(lane)}|${Entry8347.NOW}|${Exit8347.RUNNER}"
        return filterInverted8351(netsSince("C|$v|R:NATIVE", sinceMs), netsSince("C|$v|R:REJECT", sinceMs))
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
        // V5.0.8351 — the adopted choice (a variant or a niche) sets the entry; outside a niche nicheRefusal8351 already refused.
        val entry = bestFor8351(lane, sinceMs)?.entry ?: return null
        if (entry == Entry8347.NOW) return null
        val px = ts.lastPrice.takeIf { it.isFinite() && it > 0.0 } ?: return "ENTRY_TIMING_NO_MARK_8347"
        val key = "${ts.mint}|${canon(lane)}"
        val w = waits[key] ?: longArrayOf(nowMs, java.lang.Double.doubleToRawLongBits(px)).also { waits[key] = it; waitsDirty8355 = true }   // V5.0.8355 — a new wait is a change
        val signal = java.lang.Double.longBitsToDouble(w[1])
        val age = nowMs - w[0]
        val stop = try { FieldManual7715.mandateFor(lane).invalidationPct } catch (_: Throwable) { 10.0 }
        val ready = when (entry) {
            Entry8347.PULLBACK -> px <= signal * (1.0 - pullbackPct8347(stop) / 100.0)
            Entry8347.CONFIRM -> age >= CONFIRM_AFTER_MS_8347 && px >= signal * (1.0 + CONFIRM_RISE_PCT_8347 / 100.0)
            Entry8347.NOW -> true
        }
        val limit = if (entry == Entry8347.PULLBACK) PULLBACK_WAIT_MS_8347 else CONFIRM_WAIT_MS_8347
        if (ready) { waits.remove(key); waitsDirty8355 = true; return null }
        if (age >= limit) { waits.remove(key); waitsDirty8355 = true; return "ENTRY_TIMING_EXPIRED_8347_$entry" }
        if (waits.size > 2_000) { waits.entries.removeIf { nowMs - it.value[0] > PULLBACK_WAIT_MS_8347 }; waitsDirty8355 = true }
        try { PipelineHealthCollector.labelInc("ENTRY_TIMING_WAIT_8347_${canon(lane)}_$entry") } catch (_: Throwable) {}
        return "ENTRY_TIMING_WAIT_8347_$entry"
    }

    fun statusLine8347(sinceMs: Long): String {
        val lanes = books.keys.filter { !it.startsWith("C|") }.map { it.substringBefore('|') }.toSortedSet().joinToString(" · ") { l ->
            val cells = Entry8347.values().flatMap { e -> Exit8347.values().map { x -> Triple(e, x, variantRecord8347(l, e, x, sinceMs)) } }
                .filter { it.third.n > 0 }
                .joinToString(",") { (e, x, p) -> "${e.name.take(4)}/${x.name.take(4)} n${p.n} ${"%+.1f".format(p.meanPct)}%${if (p.proven) "*" else ""}" }
            val best = bestFor8351(l, sinceMs)?.label ?: "-"
            val inv = if (inverted8351(l, sinceMs)) " FILTER_INVERTED" else ""
            "$l adopt=$best$inv [$cells]"
        }.ifBlank { "no exploration closes yet" }
        return "exploreShadow8347[tickets=${tickets.size}/$MAX_TICKETS_8347 opened=${opened.get()} legsClosed=${closed.get()} full=${full.get()} " +
            "restored8355=${restored8355.get()} expiredOnRestore8355=${expiredOnRestore8355.get()} waitsRestored8355=${waitsRestored8355.get()} " +
            "rejectSample=$REJECT_SAMPLE_PCT_8347% bar=n>=$MIN_N_8347,mean-1.5se>$MARGIN_PCT_8347%,pf>=$MIN_PF_8347 (* = proven) | $lanes]"
    }
}
