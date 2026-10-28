package com.lifecyclebot.engine.truth

import android.content.Context
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 *  - retired compatibility mode makes the LIVE entry wait for that variant's entry condition ([liveEntryWait8347]).
 * Spends nothing, opens no position, takes no slot or ledger row. Model limits: every fill lands, marks are the fill.
 * (superseded by V5.0.8366 — a fill lands at the route's measured rate, an exit fills after the live sell latency, the
 * charged cost is the lane's measured round trip once measured, and the exits are live's own (ShadowAlign8366).)
 *
 * V5.0.8360 §EVERY_LANE_EVERY_TOKEN_EVERY_SETUP — owner: "we cant block every trade, especially on the shadow trader. its
 * meant to be trying to name as many profitable trades as possible across as many specialist lanes and traders across
 * their full strategy and trading stacks." Only hard safety ([shadowSafety8360], or the token's HARD_BLOCK tier) and "no
 * observed mark" ([ShadowMark8360], any observed mark under 60 s) stop a ticket now:
 *  - every NON-safety native reject opens ([REJECT_SAMPLE_PCT_8347] = 100; legs NOW/RUNNER + NOW/SCALP), booked R:REJECT;
 *  - every OTHER setup the lane's playbook fired on the coin (LanePlaybook7907.matchedSetups8083, up to
 *    [MAX_SETUP_TICKETS_8360]) opens its own SETUP ticket (six legs), keyed mint|lane|setup, booked S:<setup>
 *    (SR:<setup> when the lane itself refused);
 *  - the NOW/RUNNER close of a native ticket is also booked T:<tactic> (the tactic TacticSwitcher picked at open);
 *  - live adoption still reads only native evidence: the variant book and S:/M: niches; R:, SR: and T: are diagnostics.
 * Capacity [MAX_TICKETS_8347] / [MAX_TICKETS_PER_LANE_8351]; when full the OLDEST ticket with no active leg is evicted
 * ([evictKey8360]); the tick marks at most [TICK_PER_LOOP_8360] tickets per loop, round-robin ([rrSlice8360]).
 */
object ExploreShadow8347 {
    enum class Entry8347 { NOW, PULLBACK, CONFIRM }
    enum class Exit8347 { RUNNER, SCALP }

    const val MAX_TICKETS_8347 = 3_000   // V5.0.8360 — 1,000 (8351); every lane x token x setup now opens
    const val MAX_TICKETS_PER_LANE_8351 = 300   // V5.0.8360 — 120 (8351); one busy lane still cannot starve the rest
    const val TICKET_SOL_8347 = 0.03
    const val REJECT_SAMPLE_PCT_8347 = 100   // V5.0.8360 — 10 % (8347); every non-safety reject with an observed mark opens
    const val TICK_PER_LOOP_8360 = 600   // V5.0.8360 — tickets marked per shadow loop, round-robin (bounded CPU at 3,000 tickets)
    private const val MAX_SETUP_TICKETS_8360 = 3   // V5.0.8360 — extra fired-setup tickets per lane per coin
    private const val WANTED_EVERY_MS_8360 = 30_000L
    private const val SETUP_SCAN_MS_8360 = 60_000L
    private const val NO_EVICT_RETRY_MS_8360 = 5_000L
    private const val MIN_COHORT_SHOW_N_8360 = 10
    const val MIN_N_8347 = 40
    const val MARGIN_PCT_8347 = 2.0
    const val MIN_PF_8347 = 1.3
    private const val REOPEN_GAP_MS_8347 = 15 * 60_000L
    private const val PULLBACK_WAIT_MS_8347 = 5 * 60_000L
    private const val CONFIRM_AFTER_MS_8347 = 60_000L
    private const val CONFIRM_WAIT_MS_8347 = 3 * 60_000L
    private const val CONFIRM_RISE_PCT_8347 = 2.0
    private const val LOST_MARK_MS_8347 = 10 * 60_000L
    // V5.0.8392 — the explore legs run the hold the Cortex grades (CortexLabelHygiene8144 executable horizon), as EvidenceShadow8344
    private val RUNNER_TIMEOUT_MS_8347 = com.lifecyclebot.engine.cortex.CortexLabelHygiene8144.executableHorizonMs8392(true)
    private val OTHER_TIMEOUT_MS_8347 = com.lifecyclebot.engine.cortex.CortexLabelHygiene8144.executableHorizonMs8392(false)
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
        // V5.0.8366 — the lifecycle hold a +100 % runner leg is promoted into, and a triggered full exit waiting
        // out the live sell latency. V5.0.8372 persists both: restart cannot forget an already-triggered sell.
        var hold8366 = false
        var taken8366 = 0
        var peakAt8366 = 0L
        var exitAt8366 = 0L
        var exitWhy8366: String? = null
        var sellFraction8394 = 1.0
        var sellTier8394 = 0
        var sellTaken8394 = 0
    }

    private class Ticket8347(
        val mint: String, val symbol: String, val lane: String, val setup: String, val tactic: String, val scoreBand: String,
        val signalPx: Double, val atMs: Long, val costPct: Double, val stopPct: Double, val armPct: Double,
        val runner: Boolean, val nativeEligible: Boolean, val legs: List<Leg8347>,
        val band: String = "UNK",   // V5.0.8351 — market-cap band at open, a cohort dimension
        val kind8360: String = KIND_NATIVE_8360,   // V5.0.8360 — NATIVE / REJECT / SETUP
        val markSrc8360: String = "",              // V5.0.8360 — the observed source that priced the entry
        var cortex8372: ShadowPersist8355.CortexShadowOutcome8372 = ShadowPersist8355.CortexShadowOutcome8372(),
        val entryPullbackPct8394: Double = pullbackPct8347(FieldManual7715.mandateFor(lane).invalidationPct),
        val contractVersion8394: Int = ShadowPersist8355.TRADE_CONTRACT_8394,
    ) {
        var lastPx = signalPx
        var lastPxAtMs = atMs
        var restoredAtMs8418 = 0L   // V5.0.8418 — restore time (0 = opened in this process): the lost-mark clock restarts there
        // V5.0.8355 — persistence state: a leg's tier / peak / trough moved (coalesced write); a leg armed or closed (forced write)
        var dirty8355 = false
        var force8355 = false
        var lastWriteMs8355 = 0L
        var receiptDurable8372 = false
        // The assessment caller registers its immutable prediction before opening. Do not tick a partially-built ticket.
        @Volatile var ready8372 = false
        @Volatile var ticking8372 = false
    }

    private val tickets = ConcurrentHashMap<String, Ticket8347>()                // mint|lane|setup -> ticket (V5.0.8360; pre-8360: mint|lane)
    private val ticketReservation8372 = Any()   // capacity reservation/retirement only; no learner/provider calls
    private val lastOpenAt = ConcurrentHashMap<String, Long>()
    private val books = ShadowCohortBook8397(fields = 4)     // lane|ENTRY|EXIT -> [atMs, net%]
    private val opened = AtomicLong(0)
    private val closed = AtomicLong(0)
    private val full = AtomicLong(0)
    private val tickActive8372 = AtomicBoolean(false)
    private val cortexOutcomeAck8372 = AtomicLong(0)
    private val cortexUnavailable8372 = AtomicLong(0)
    private val cortexRetry8372 = AtomicLong(0)
    private val waits = ConcurrentHashMap<String, LongArray>()                   // mint|lane -> [firstAtMs, signalPx bits]


    // ── V5.0.8355 §SHADOW_EVIDENCE_SURVIVES_RESTARTS — open tickets (every leg's state) and the liveEntryWait8347 waits are
    // persisted in their own prefs (the close books untouched): a ticket is written on open, when a leg arms or closes, and
    // coalesced (at most once per 10 s) when a leg's tier / peak / trough moves; removed once every leg is dead. init8347
    // restores them with their real entry marks and clocks, never re-priced; a leg still waiting re-arms on the real signal. ──
    private const val PREFS_OPEN_8355 = "aate_explore_shadow_open_8355"
    private const val TICKET_KEY_PREFIX_8355 = "T:"
    private const val WAITS_KEY_8355 = "W"
    @Volatile private var openPrefs8355: ShadowColdStore8397? = null
    private val initLock8372 = Any()
    @Volatile private var openRestored8372 = false
    @Volatile private var booksRestored8372 = false
    @Volatile private var waitsDirty8355 = false
    @Volatile private var waitsWrittenAtMs8355 = 0L
    private val restored8355 = AtomicLong(0)
    private val expiredOnRestore8355 = AtomicLong(0)
    private val waitsRestored8355 = AtomicLong(0)

    private fun ticketRec8355(t: Ticket8347): ShadowPersist8355.TicketRec8355 = ShadowPersist8355.TicketRec8355(
        t.mint, t.symbol, t.lane, t.setup, t.tactic, t.scoreBand, t.band, t.signalPx, t.atMs, t.costPct, t.stopPct, t.armPct,
        t.runner, t.nativeEligible, t.lastPx, t.lastPxAtMs,
        t.legs.map { l -> ShadowPersist8355.LegRec8355(l.entry.name, l.exit.name, l.active, l.dead, l.entryPx, l.entryAtMs, l.held, l.banked, l.tiers, l.peak, l.trough,
            hold8366 = l.hold8366, taken8366 = l.taken8366, peakAt8366 = l.peakAt8366,
            exitAt8366 = l.exitAt8366, exitWhy8366 = l.exitWhy8366 ?: "",
            sellFraction8394 = l.sellFraction8394, sellTier8394 = l.sellTier8394, sellTaken8394 = l.sellTaken8394) },   // V5.0.8372
        kind8360 = t.kind8360, markSrc8360 = t.markSrc8360,   // V5.0.8360
        cortex8372 = t.cortex8372, entryPullbackPct8394 = t.entryPullbackPct8394, contractVersion8394 = t.contractVersion8394,
    )

    /** An ordinary open / tier / peak write: asynchronous. Durable learning receipts commit in the tick slice's one batch (8377). */
    private val openWrites8386 = ShadowWrites8386()
    @Volatile private var openFlushedAt8386 = 0L
    private val openFlushes8386 = AtomicLong(0)
    private val openRetry8386 = AtomicLong(0)

    private fun persistTicket8355(key: String, t: Ticket8347, nowMs: Long): Boolean {
        if (openPrefs8355 == null) { t.force8355 = true; return false }
        val queued = try { synchronized(ticketReservation8372) {
            tickets[key] === t && openWrites8386.stage(TICKET_KEY_PREFIX_8355 + key, t.atMs,
                ShadowPersist8355.encodeTicket8355(ticketRec8355(t)))
        } } catch (_: Exception) { false }
        if (queued) { t.dirty8355 = false; t.force8355 = false; t.lastWriteMs8355 = nowMs } else t.force8355 = true
        return queued
    }

    private fun flushOpen8386(nowMs: Long, durable: Boolean = false): Boolean {
        val p = openPrefs8355 ?: return false
        if (!durable && nowMs - openFlushedAt8386 < 10_000L) return true
        val ok = openWrites8386.flush { batch ->
            try {
                // A transaction updates only the staged keys; no whole-file mirror or asynchronous apply queue.
                p.write(batch.mapValues { it.value.value })
            } catch (_: Exception) { false }
        }
        if (ok) { openFlushedAt8386 = nowMs; openFlushes8386.incrementAndGet() } else openRetry8386.incrementAndGet()
        return ok
    }

    // V5.0.8377 — 8376 died OOM at the 512 MiB heap after 3 h 47 m. Every SharedPreferences write re-serialises the WHOLE
    // file (the open-ticket file is ~9.6 MB at 3,000 tickets) and every queued apply() holds a full map copy. 8376 wrote
    // one ticket per write (a synchronous commit() per pending learning receipt, up to 600 per slice) and the cohort books
    // one key per write (five-plus per closed leg). Writes are now staged into ONE editor per tick slice (one commit when any
    // receipt needs durability, otherwise one apply), and the books flush dirty keys at most every BOOK_FLUSH_MS_8377.
    // Durability order is unchanged: a receipt is committed before it is delivered to Cortex.
    internal const val BOOK_FLUSH_MS_8377 = 15_000L

    @Volatile private var booksFlushedAtMs8377 = 0L
    private val sliceWrites8377 = AtomicLong(0)
    private val sliceTickets8377 = AtomicLong(0)
    private val bookFlushes8377 = AtomicLong(0)
    private val bookKeysWritten8377 = AtomicLong(0)

    /** Encodes [t] into [edit] without writing; false when the record cannot be encoded. */
    private fun stageTicket8377(@Suppress("UNUSED_PARAMETER") edit: ShadowColdStore8397, key: String, t: Ticket8347): Boolean =
        persistTicket8355(key, t, System.currentTimeMillis())

    /** Closed cohort rows already commit atomically with their event receipt; retained for tick/shutdown callers. */
    internal fun flushBooks8377(nowMs: Long, force: Boolean = false) {
        booksFlushedAtMs8377 = nowMs
    }

    fun memoryCount8397(): Int = tickets.size + books.hotCount()
    fun memoryBytes8397(): Long = tickets.size * 4096L + books.bytes() + openWrites8386.bytes()
    fun trimCold8397(): Int = books.trim()

    fun persistStatus8377(): String =
        "openWrites8386[queued=${openWrites8386.size()} flushes=${openFlushes8386.get()} retry=${openRetry8386.get()}] persist8377[sliceWrites=${sliceWrites8377.get()} ticketsStaged=${sliceTickets8377.get()} bookFlushes=${bookFlushes8377.get()} " +
            "bookKeys=${bookKeysWritten8377.get()} hotCohorts=${books.hotCount()} queuedBytes=${openWrites8386.bytes()}]"

    private fun forgetTicket8355(key: String): Boolean {
        val epoch = tickets[key]?.atMs ?: lastOpenAt[key] ?: return false
        return openWrites8386.stage(TICKET_KEY_PREFIX_8355 + key, epoch, null)
    }

    /** The waits map, whole (it is small), at most once per 10 s and only after a change. */
    private fun flushWaits8355(nowMs: Long) {
        if (!ShadowPersist8355.writeDue8355(waitsDirty8355, waitsWrittenAtMs8355, nowMs)) return
        waitsDirty8355 = false; waitsWrittenAtMs8355 = nowMs
        val ws = waits.entries.map { (k, w) -> ShadowPersist8355.WaitRec8355(k, w[0], java.lang.Double.longBitsToDouble(w[1]), w.getOrNull(2)?.let { java.lang.Double.longBitsToDouble(it) } ?: Double.NaN, w.getOrNull(3)?.toInt() ?: 0) }
        try { if (!openWrites8386.stage(WAITS_KEY_8355, nowMs, ShadowPersist8355.encodeWaits8355(ws))) waitsDirty8355 = true } catch (_: Exception) { waitsDirty8355 = true }
    }

    private fun ticketFromRec8355(r: ShadowPersist8355.TicketRec8355): Ticket8347? {
        val legs = ArrayList<Leg8347>()
        for (l in r.legs) {
            val e = Entry8347.values().firstOrNull { it.name == l.entry } ?: return null
            val x = Exit8347.values().firstOrNull { it.name == l.exit } ?: return null
            val leg = Leg8347(e, x)
            leg.active = l.active; leg.dead = l.dead; leg.entryPx = l.entryPx; leg.entryAtMs = l.entryAtMs
            leg.held = l.held; leg.banked = l.banked; leg.tiers = l.tiers; leg.peak = l.peak; leg.trough = l.trough
            leg.hold8366 = l.hold8366; leg.taken8366 = l.taken8366; leg.peakAt8366 = l.peakAt8366   // V5.0.8366
            leg.exitAt8366 = l.exitAt8366; leg.exitWhy8366 = l.exitWhy8366.takeIf { it.isNotBlank() }
            leg.sellFraction8394 = l.sellFraction8394; leg.sellTier8394 = l.sellTier8394; leg.sellTaken8394 = l.sellTaken8394
            legs += leg
        }
        if (legs.isEmpty() || (legs.all { it.dead } && !r.cortex8372.awaiting8372())) return null
        val kind8360 = r.kind8360.ifBlank { if (r.nativeEligible) KIND_NATIVE_8360 else KIND_REJECT_8360 }   // V5.0.8360 — pre-8360 records
        val t = Ticket8347(r.mint, r.symbol, r.lane, r.setup, r.tactic, r.scoreBand, r.signalPx, r.atMs, r.costPct, r.stopPct, r.armPct,
            r.runner, r.nativeEligible, legs, r.band, kind8360 = kind8360, markSrc8360 = r.markSrc8360,
            cortex8372 = r.cortex8372, entryPullbackPct8394 = r.entryPullbackPct8394.takeIf { it.isFinite() } ?: pullbackPct8347(r.stopPct),
            contractVersion8394 = r.contractVersion8394)
        t.lastPx = r.lastPx; t.lastPxAtMs = r.lastPxAtMs
        t.ready8372 = true
        return t
    }

    private fun restoreOpen8355(ctx: Context, nowMs: Long): Boolean {
        val restored = try {
            val p = ShadowColdStore8397.open(ctx, PREFS_OPEN_8355)
            openPrefs8355 = p
            val drop = ArrayList<String>()
            var n = 0L
            for ((k, v) in p.entries()) {
                val s = v as? String
                if (k == WAITS_KEY_8355) {
                    for (w in ShadowPersist8355.decodeWaits8355(s)) {
                        if (nowMs - w.firstAtMs !in 0L until PULLBACK_WAIT_MS_8347) continue
                        if (waits.putIfAbsent(w.key, longArrayOf(w.firstAtMs, java.lang.Double.doubleToRawLongBits(w.signalPx), java.lang.Double.doubleToRawLongBits(w.entryPullbackPct8394), w.contractVersion8394.toLong())) == null)
                            waitsRestored8355.incrementAndGet()
                    }
                    continue
                }
                if (!k.startsWith(TICKET_KEY_PREFIX_8355)) { drop += k; continue }
                val key = k.substring(TICKET_KEY_PREFIX_8355.length)
                if (tickets.containsKey(key)) continue   // partial-init retry must not delete a newer in-memory receipt
                var r = ShadowPersist8355.decodeTicket8355(s)
                if (r == null) { drop += k; continue }
                // the ticket's own window: the longest leg timeout plus the longest entry wait, then the lost-mark grace
                // V5.0.8366 — a leg in the lifecycle hold has no clock (LifecycleHold8065): its ticket keeps the hold's window
                val window = if (r.legs.any { it.hold8366 && !it.dead }) ShadowAlign8366.holdWindowMs8366()
                    else (if (r.runner) RUNNER_TIMEOUT_MS_8347 else OTHER_TIMEOUT_MS_8347) + PULLBACK_WAIT_MS_8347
                if (ShadowPersist8355.expiredOnRestore8355(r.atMs, window, nowMs, LOST_MARK_MS_8347)) {
                    expiredOnRestore8355.incrementAndGet()
                    if (!r.cortex8372.awaiting8372()) { drop += k; continue }
                    // Keep a known terminal receipt for redelivery, or release an unpriced expired prediction. Never
                    // reconstruct a fill at restart time or leak an enrolled Cortex trace when a ticket expires.
                    r = r.copy(legs = r.legs.map { it.copy(dead = true) },
                        cortex8372 = r.cortex8372.unavailable8372("EXPIRED_ON_RESTORE_8372", nowMs))
                }
                val t = ticketFromRec8355(r)
                if (t == null) { drop += k; continue }
                t.restoredAtMs8418 = nowMs
                val added = synchronized(ticketReservation8372) {
                    if (tickets.containsKey(key)) return@synchronized false
                    if (!t.cortex8372.pending8372()) {
                        if (!t.cortex8372.awaiting8372()) {
                            if (tickets.size >= MAX_TICKETS_8347 ||
                                laneCount8360(t.lane) >= MAX_TICKETS_PER_LANE_8351) { drop += k; return@synchronized false }
                        } else if (tickets.size >= MAX_TICKETS_8347 || laneCount8360(t.lane) >= MAX_TICKETS_PER_LANE_8351) {
                            // Capacity may change across builds. Release an enrolled decision explicitly before pruning it.
                            t.cortex8372 = t.cortex8372.unavailable8372("RESTORE_CAPACITY_8372", nowMs)
                            t.legs.forEach { it.dead = true }
                        }
                    }
                    t.force8355 = t.cortex8372.pending8372()
                    t.lastWriteMs8355 = nowMs
                    tickets[key] = t
                    laneCounts8360.computeIfAbsent(t.lane) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
                    lastOpenAt[key] = r.atMs
                    true
                }
                if (added) n++
            }
            if (drop.isNotEmpty()) synchronized(ticketReservation8372) {
                val removals = drop.filter { k -> !k.startsWith(TICKET_KEY_PREFIX_8355) ||
                    !tickets.containsKey(k.substring(TICKET_KEY_PREFIX_8355.length)) }.associateWith { null as String? }
                if (!p.write(removals)) throw ShadowStorageUnavailable8397()
            }
            restored8355.addAndGet(n)
            true
        } catch (_: Throwable) { false }
        recountLanes8360()   // V5.0.8360
        return restored
    }

    /** V5.0.8355 — restored tickets / expired-on-restore / restored waits (tests and the status line). */
    fun restoreCounts8355(): Triple<Long, Long, Long> = Triple(restored8355.get(), expiredOnRestore8355.get(), waitsRestored8355.get())

    fun initReady8372(): Boolean = openRestored8372 && booksRestored8372

    fun init8347(ctx: Context, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(initLock8372) {
        if (!openRestored8372) openRestored8372 = restoreOpen8355(ctx, nowMs)
        if (!booksRestored8372) booksRestored8372 = try {
            books.attach(ShadowColdStore8397.open(ctx, PREFS_8347))
            true
        } catch (_: Throwable) { false }
        initReady8372()
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

    // ── V5.0.8360 §EVERY_LANE_EVERY_TOKEN_EVERY_SETUP — kinds, skips, capacity ──

    const val KIND_NATIVE_8360 = "NATIVE"
    const val KIND_REJECT_8360 = "REJECT"
    const val KIND_SETUP_8360 = "SETUP"
    // V5.0.8368: the decision made by Cortex itself, not the independent native brain opinion.
    // Separate kinds and keys keep a rejected Cortex trade from being counted as a native winner.
    const val KIND_CORTEX_YES_8368 = "CORTEX_YES"
    const val KIND_CORTEX_NO_8368 = "CORTEX_NO"
    const val KIND_CORTEX_HOLD_8368 = "CORTEX_HOLD"
    private val cortexSeen8368 = AtomicLong(0)
    private val cortexSeenByKind8368 = ConcurrentHashMap<String, AtomicLong>()
    private val cortexClosesByKind8368 = ConcurrentHashMap<String, AtomicLong>()
    fun cortexKind8368(bucket: String): String = when (bucket.uppercase()) {
        "STRONG" -> KIND_CORTEX_YES_8368
        "REFUSE" -> KIND_CORTEX_NO_8368
        else -> KIND_CORTEX_HOLD_8368
    }
    /** Modelled, post-cost shadow outcomes. Exactly one selected exit leg per decision is counted in the report. */
    data class CortexVerdictStats8368(
        val n: Int, val wins: Int, val losses: Int, val meanPct: Double, val profitFactor: Double,
    )

    fun cortexStats8368(nets: List<Double>): CortexVerdictStats8368 {
        val xs = nets.filter { it.isFinite() }
        val wins = xs.count { it > 0.0 }
        val losses = xs.count { it < 0.0 }
        val gain = xs.filter { it > 0.0 }.sum()
        val loss = -xs.filter { it < 0.0 }.sum()
        val pf = if (loss > 0.0) gain / loss else if (gain > 0.0) Double.POSITIVE_INFINITY else 0.0
        return CortexVerdictStats8368(xs.size, wins, losses, if (xs.isEmpty()) 0.0 else xs.average(), pf)
    }

    private fun isCortexKind8368(kind: String): Boolean =
        kind == KIND_CORTEX_YES_8368 || kind == KIND_CORTEX_NO_8368 || kind == KIND_CORTEX_HOLD_8368
    /** A native and a Cortex assessment of the same mint/lane/setup are different experiments. */
    fun cortexTicketKey8368(nativeKey: String, kind: String): String =
        if (isCortexKind8368(kind)) "$nativeKey|$kind" else nativeKey
    const val SKIP_SAFETY_TOKEN_8360 = "HARD_SAFETY_TOKEN"
    const val SKIP_SAFETY_REASON_8360 = "HARD_SAFETY_REASON"
    const val SKIP_LANE_ERROR_8360 = "LANE_ERROR_NO_OPINION"
    const val SKIP_NOT_SAMPLED_8360 = "NOT_SAMPLED"
    const val SKIP_NO_MARK_8360 = "NO_OBSERVED_MARK"
    private const val SKIP_ALREADY_OPEN_8360 = "ALREADY_OPEN"
    private const val SKIP_REOPEN_GAP_8360 = "REOPEN_GAP_15M"
    private const val SKIP_FULL_8360 = "FULL_NO_EVICTABLE"
    private const val SKIP_LANE_FULL_8360 = "LANE_FULL_NO_EVICTABLE"

    /** The refusals that stop a shadow ticket: the token cannot be traded or priced honestly (not selection, not economics). */
    private val SAFETY_MARKERS_8360 = listOf(
        "SAFETY_REFUSED", "STRUCTURAL_RISK", "RUG", "FREEZE", "MINT_AUTH", "AUTHORITY", "TOKEN_2022", "TOKEN2022", "T22_",
        "HOSTILE", "HONEYPOT", "SELLAB", "UNSELLABLE", "MAYHEM", "BLACKLIST", "HARD_BLOCK", "LIQUIDITY_COLLAPSE", "EXIT_SAFE",
        "IDENTITY", "PRICE_BASIS", "BASIS_PROOF", "DEAD_LAUNCH",
    )

    private val skips8360 = ConcurrentHashMap<String, AtomicLong>()
    private val openedByKind8360 = ConcurrentHashMap<String, AtomicLong>()
    private val openedBySrc8360 = ConcurrentHashMap<String, AtomicLong>()
    private val evicted8360 = AtomicLong(0)
    private val wantedCalls8360 = AtomicLong(0)
    private val laneCounts8360 = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val wantedAt8360 = ConcurrentHashMap<String, Long>()
    private val setupScanAt8360 = ConcurrentHashMap<String, Long>()
    private val noEvictUntil8360 = ConcurrentHashMap<String, Long>()
    @Volatile private var rrCursor8360 = 0
    @Volatile private var lastTicked8360 = 0

    /** Pure: a refusal that stops a shadow ticket — rug, honeypot, freeze / mint authority, hostile Token-2022, unsellable, identity / price basis. */
    private fun shadowSafety8360(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.uppercase()
        return SAFETY_MARKERS_8360.any { r.contains(it) }
    }

    /** Native soft strategy NOs are learnable; physical hard-safety/sellability NOs are not hypothetical fills. */
    fun cortexNativeHardStop8368(reason: String?): Boolean = shadowSafety8360(reason)

    /**
     * Pure: what a lane's native opinion opens — [KIND_NATIVE_8360] (eligible), [KIND_REJECT_8360] (a mandate / fit /
     * threshold / momentum / data-class refusal), or the skip reason: the token's hard-safety tier, a hard-safety refusal,
     * a lane that errored (no opinion at all), or a reject outside the sample.
     */
    fun openKind8360(eligible: Boolean, reason: String?, tokenHardBlocked: Boolean, sampled: Boolean = true): String = when {
        tokenHardBlocked -> SKIP_SAFETY_TOKEN_8360
        eligible -> KIND_NATIVE_8360
        reason != null && reason.startsWith("BRAIN_ERROR_") -> SKIP_LANE_ERROR_8360
        shadowSafety8360(reason) -> SKIP_SAFETY_REASON_8360
        !sampled -> SKIP_NOT_SAMPLED_8360
        else -> KIND_REJECT_8360
    }

    /** Pure: is [s] a ticket kind (rather than a skip reason)? */
    fun opens8360(s: String): Boolean = s == KIND_NATIVE_8360 || s == KIND_REJECT_8360 || s == KIND_SETUP_8360 || isCortexKind8368(s)

    /** Pure: the lane's extra SETUP tickets — every other fired setup (not the opinion's own, not NO_TRIGGER / NONE), distinct, at most [max]. */
    fun setupTickets8360(primary: String?, fired: List<String>, max: Int = MAX_SETUP_TICKETS_8360): List<String> {
        val out = ArrayList<String>()
        val own = primary?.trim() ?: ""
        for (raw in fired) {
            if (out.size >= max) break
            val id = raw.trim()
            if (id.isEmpty() || id.equals("NO_TRIGGER", ignoreCase = true) || id.equals("NONE", ignoreCase = true)) continue
            if (own.isNotEmpty() && id.equals(own, ignoreCase = true)) continue
            if (out.any { it.equals(id, ignoreCase = true) }) continue
            out += id
        }
        return out
    }

    /** Pure: the ticket (and reopen-gap) key — a different setup on the same coin and lane is a different ticket. */
    fun ticketKey8360(mint: String, lane: String, setup: String): String = "$mint|$lane|$setup"

    /** Pure: the legs a kind runs — six Entry x Exit variants, or NOW/RUNNER + NOW/SCALP for a reject. */
    fun legsFor8360(kind: String): List<Pair<Entry8347, Exit8347>> =
        if (kind == KIND_REJECT_8360 || isCortexKind8368(kind)) listOf(Entry8347.NOW to Exit8347.RUNNER, Entry8347.NOW to Exit8347.SCALP)
        else Entry8347.values().flatMap { e -> Exit8347.values().map { x -> e to x } }

    /** Pure: the ticket to evict when full — the OLDEST (key, openedAtMs, anyActiveLeg) with no active leg; null = none. */
    fun evictKey8360(cands: List<Triple<String, Long, Boolean>>): String? =
        cands.filter { !it.third }.minByOrNull { it.second }?.first

    /** Pure: this loop's round-robin slice — the ticket indexes to mark (at most [budget]) and the next cursor. */
    fun rrSlice8360(size: Int, cursor: Int, budget: Int = TICK_PER_LOOP_8360): Pair<List<Int>, Int> {
        if (size <= 0 || budget <= 0) return Pair(emptyList<Int>(), 0)
        val start = Math.floorMod(cursor, size)
        val n = minOf(size, budget)
        val idx = ArrayList<Int>(n)
        for (i in 0 until n) idx += (start + i) % size
        return Pair(idx, (start + n) % size)
    }

    private fun skip8360(why: String, n: Long = 1L) {
        skips8360.computeIfAbsent(why) { AtomicLong(0) }.addAndGet(n)
    }

    private fun laneCount8360(lane: String): Int = laneCounts8360[lane]?.get() ?: 0

    private fun recountLanes8360() = synchronized(ticketReservation8372) {
        val m = HashMap<String, Int>()
        for (t in tickets.values) m[t.lane] = (m[t.lane] ?: 0) + 1
        for (l in laneCounts8360.keys) if (!m.containsKey(l)) laneCounts8360[l]?.set(0)
        for ((l, n) in m) laneCounts8360.computeIfAbsent(l) { java.util.concurrent.atomic.AtomicInteger(0) }.set(n)
    }

    /** When full: evict the oldest ticket (in [lane], or anywhere when null) with no active leg; false = none evictable. */
    private fun evict8360(lane: String?, nowMs: Long): Boolean = synchronized(ticketReservation8372) {
        val scope = lane ?: "*"
        val until = noEvictUntil8360[scope]
        if (until != null && nowMs < until) return@synchronized false
        val cands = ArrayList<Triple<String, Long, Boolean>>()
        for ((k, t) in tickets) {
            if (lane != null && t.lane != lane) continue
            // An undelivered Cortex receipt is learning still owed, never an evictable inactive ticket.
            cands += Triple(k, t.atMs, !t.ready8372 || t.ticking8372 || t.cortex8372.awaiting8372() || t.legs.any { it.active && !it.dead })
        }
        val k = evictKey8360(cands)
        if (k == null) { noEvictUntil8360[scope] = nowMs + NO_EVICT_RETRY_MS_8360; return@synchronized false }
        var removed: Ticket8347? = null
        // Reserve/remove and preference cleanup share this key's CHM lock. A
        // concurrent re-open cannot persist a new ticket just before we erase it.
        tickets.computeIfPresent(k) { _, current ->
            if (!current.ready8372 || current.ticking8372 || current.cortex8372.awaiting8372() || current.legs.any { it.active && !it.dead }) current
            else {
                if (!forgetTicket8355(k)) return@computeIfPresent current
                scalpInMap8366.remove(k)
                removed = current
                null
            }
        }
        val t = removed ?: return@synchronized false
        laneCounts8360[t.lane]?.decrementAndGet()
        evicted8360.incrementAndGet()
        true
    }

    private fun wanted8360(mint: String, untilMs: Long) {
        // V5.0.8360 — the keyless mark feed (8359) prices shadow-wanted mints right after held positions; near-decision keeps the Helius focus.
        try { KeylessMarks8359.noteWanted8359(mint, untilMs) } catch (_: Throwable) {}
        // Shadow-only mark demand stays keyless; it must not promote optional exploration into the paid decision reserve.
    }

    /** Ask the data side to keep pricing [mint] (once per mint per 30 s at most), for the lost-mark window. */
    private fun want8360(mint: String, nowMs: Long) {
        val last = wantedAt8360[mint]
        if (last != null && nowMs - last in 0L until WANTED_EVERY_MS_8360) return
        wantedAt8360[mint] = nowMs
        if (wantedAt8360.size > 10_000) wantedAt8360.entries.removeIf { nowMs - it.value > WANTED_EVERY_MS_8360 }
        wanted8360(mint, nowMs + LOST_MARK_MS_8347)
        wantedCalls8360.incrementAndGet()
    }

    /** The lane's other fired setups for [ts] (read at most once per mint|lane per minute). */
    private fun firedSetups8360(ts: TokenState, lane: String, primary: String, nowMs: Long): List<String> {
        val k = "${ts.mint}|$lane"
        val last = setupScanAt8360[k]
        if (last != null && nowMs - last in 0L until SETUP_SCAN_MS_8360) return emptyList()
        setupScanAt8360[k] = nowMs
        if (setupScanAt8360.size > 20_000) setupScanAt8360.entries.removeIf { nowMs - it.value > SETUP_SCAN_MS_8360 }
        val fired = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.matchedSetups8083(ts, lane, nowMs) } catch (_: Throwable) { emptyList<String>() }
        return setupTickets8360(primary, fired)
    }

    /**
     * Every native opinion for [ts]. V5.0.8360: every lane that is eligible OR refused for a non-safety reason opens a ticket,
     * plus one SETUP ticket per other setup its playbook fired (up to 3); only the token's hard-safety tier, a hard-safety
     * refusal, a lane error, or no observed mark under 60 s stop it — each counted by reason.
     */
    fun onOpinions8347(ts: TokenState, opinions: Map<String, com.lifecyclebot.engine.SpecialistBrainBridge7542.Opinion>, nowMs: Long = System.currentTimeMillis()) {
        try {
            if (opinions.isEmpty()) return
            if (com.lifecyclebot.engine.NativeDecisionEnrollment8372.isCryptoDesk(ts.lastPriceSource) ||
                !com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(ts.mint)) {
                skip8360("NATIVE_CROSS_ASSET_SHADOW_UNSUPPORTED_8372", opinions.size.toLong()); return
            }
            val mark = ShadowMark8360.observed8360(ts, ts.mint, nowMs)   // V5.0.8360 — tick, labeler, curve or tape, observed < 60 s
            if (mark == null) { skip8360(SKIP_NO_MARK_8360, opinions.size.toLong()); return }
            val blocked = try { ts.safety.isBlocked } catch (_: Throwable) { false }
            for ((laneRaw, o) in opinions) {
                val lane = canon(laneRaw)
                val kind = openKind8360(o.eligible, o.reason, blocked, o.eligible || sampled8347(ts.mint, lane))
                if (!opens8360(kind)) { skip8360(kind); continue }
                val primary = o.setup.ifBlank { "NONE" }
                open8347(ts, lane, o, mark.first, nowMs, kind, primary, mark.second)
                for (setup in firedSetups8360(ts, lane, primary, nowMs)) open8347(ts, lane, o, mark.first, nowMs, KIND_SETUP_8360, setup, mark.second)
            }
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.8368: a Cortex prediction attempts its OWN executable-shadow episode for this exact
     * mint/lane/setup. All STRONG (YES), REFUSE (NO) and NEUTRAL (HOLD) assessments arrive here,
     * independently of native lane eligibility, FDG/admission verdict and live wallet slots.
     * Each is model-filled and marked through the same live-aligned shadow exits; invalid safety
     * or unpriced candidates are counted, NEVER assigned a fabricated win or loss.
     */
    fun onCortexAssessment8368(
        ts: TokenState, laneRaw: String, bucket: String, pWin: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): Long? {
        val kind = cortexKind8368(bucket)
        cortexSeen8368.incrementAndGet()
        cortexSeenByKind8368.computeIfAbsent(kind) { AtomicLong(0) }.incrementAndGet()
        try {
            if (ts.mint.isBlank() || laneRaw.isBlank() || nowMs <= 0L) { skip8360("CORTEX_BAD_IDENTITY_8368"); return null }
            // Cross-asset traders use different venues and costs: do not invent SOL transactions for them.
            // Their Cortex forward-label training remains active; this is the Solana execution shadow.
            if (com.lifecyclebot.engine.cortex.CrossAssetCortex7931.isCrossAssetLane(laneRaw) ||
                ShadowPersist8355.crossAssetShadowLane8372(laneRaw)) {
                skip8360("CORTEX_UNSUPPORTED_VENUE_SHADOW_8372"); return null
            }
            if (!com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(ts.mint)) {
                skip8360("CORTEX_NON_SOLANA_SHADOW_UNROUTABLE_8368"); return null
            }
            if (ts.safety.isBlocked) { skip8360("CORTEX_HARD_SAFETY_8368"); return null }
            val lane = canon(laneRaw)
            // Native strategy refusals belong in the Cortex NO counterfactual, except physical
            // impossibility: never teach a simulated profit on a known unsellable/unsafe mint.
            // Use the existing short-lived verdict cache; no extra trader/provider call on Cortex's hot path.
            val native = com.lifecyclebot.engine.SpecialistBrainBridge7542.cachedSnapshot7650(ts.mint)
                ?.opinions?.get(lane)
            if (native != null && !native.eligible && cortexNativeHardStop8368(native.reason)) {
                skip8360("CORTEX_NATIVE_HARD_UNROUTABLE_8368"); return null
            }
            val mark = ShadowMark8360.observed8360(ts, ts.mint, nowMs)
            if (mark == null) { skip8360("CORTEX_NO_OBSERVED_MARK_8368"); want8360(ts.mint, nowMs); return null }
            val setup = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, lane, nowMs) }
                catch (_: Exception) { null }
            val primary = setup?.takeIf { it.isNotBlank() } ?: "NO_TRIGGER"
            val score = ts.entryScore.toInt().coerceIn(0, 100)
            val confidence = if (pWin.isFinite()) (pWin * 100.0).toInt().coerceIn(0, 100) else 0
            val o = com.lifecyclebot.engine.SpecialistBrainBridge7542.Opinion(
                lane = lane, eligible = kind == KIND_CORTEX_YES_8368, score = score,
                confidence = confidence, requestedSizeSol = TICKET_SOL_8347,
                reason = "CORTEX_${bucket.uppercase()}_SHADOW_ONLY_8368", setup = primary,
                entryStyle = "NOW", exitStyle = if (com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane)) "RUNNER" else "SCALP",
                authoritative = false, tools = setOf("CORTEX_SHADOW_8368"),
            )
            return open8347(ts, lane, o, mark.first, nowMs, kind, primary, mark.second)
        } catch (_: Exception) { skip8360("CORTEX_SHADOW_ERROR_8368") }
        return null
    }

    private fun open8347(
        ts: TokenState, lane: String, o: com.lifecyclebot.engine.SpecialistBrainBridge7542.Opinion, px: Double, nowMs: Long,
        kind: String, setup: String, markSrc: String,
    ): Long? {
        val pressure8397 = com.lifecyclebot.engine.MemoryGuard7977.pressure8144()
        if (pressure8397 == com.lifecyclebot.engine.RetentionPolicy8136.Pressure.HARD ||
            pressure8397 == com.lifecyclebot.engine.RetentionPolicy8136.Pressure.CRITICAL || openWrites8386.bytes() > 1_500_000L) {
            skip8360("SHADOW_CAPACITY_PRESSURE_8397"); return null
        }
        val key = ticketKey8360(ts.mint, lane, setup)
        val ticketKey8368 = cortexTicketKey8368(key, kind)
        if (tickets.containsKey(ticketKey8368)) { skip8360(SKIP_ALREADY_OPEN_8360); return null }
        val lastAt = lastOpenAt[ticketKey8368]
        if (lastAt != null && nowMs - lastAt in 0L until REOPEN_GAP_MS_8347) { skip8360(SKIP_REOPEN_GAP_8360); return null }
        val liq = try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }
        // V5.0.8366 — the lane's measured round trip (CostLedger7962, n >= 8) once measured, the NetEdge8030 model before (ShadowAlign8366)
        val cost = try { ShadowAlign8366.laneCostPct8366(lane, liq, ts.mint, ts.lastMcap) } catch (_: Throwable) { Double.NaN }
        val stop = try { FieldManual7715.stopPct8392(lane, cost) } catch (_: Throwable) { 10.0 }   // V5.0.8392 — never inside the round trip
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        val arm = try { com.lifecyclebot.engine.RunnerExitProfile7277.giveBackArmPct7791(lane) } catch (_: Throwable) { 20.0 }
        val tactic = try { com.lifecyclebot.engine.learning.TacticSwitcher.tacticForToken8050(ts, lane, o.score).name } catch (_: Throwable) { "MOMENTUM" }
        val band = try { com.lifecyclebot.engine.LosingPatternMemory.scoreBand(o.score) } catch (_: Throwable) { "" }
        val legs = legsFor8360(kind).map { (e, x) -> Leg8347(e, x) }
        val mcapBand8351 = try { ForwardReturnLabeler7731.mcapBand(TrustedMcap8019.mcap8019(ts)) } catch (_: Throwable) { "UNK" }
        val t = Ticket8347(ts.mint, ts.symbol, lane, setup, tactic, band, px, nowMs,
            if (cost.isFinite()) cost else 7.0, stop.coerceIn(3.0, 40.0), arm, runner, o.eligible, legs, mcapBand8351,
            kind8360 = kind, markSrc8360 = markSrc)
        if (isCortexKind8368(kind)) {
            val selectedExit = (try { liveExitStyle8351(lane, EvidenceHold8336.startMs8336) } catch (_: Throwable) { null })
                ?: if (runner) Exit8347.RUNNER else Exit8347.SCALP
            t.cortex8372 = ShadowPersist8355.CortexShadowOutcome8372(exit = selectedExit.name)
            com.lifecyclebot.engine.cortex.Cortex7885.shadowMaturity8394(t.mint, t.lane, t.atMs,
                if (selectedExit == Exit8347.SCALP) "TERMINAL_SCALP" else "TERMINAL_RUNNER",
                t.atMs + if (selectedExit == Exit8347.SCALP) SCALP_TIMEOUT_MS_8347 else if (t.runner) RUNNER_TIMEOUT_MS_8347 else OTHER_TIMEOUT_MS_8347)
        }
        // V5.0.8364 — the fill is booked at the signal mark plus the lane's decision-to-fill chase (ShadowFill8364), as live pays it
        val fillPx8364 = ShadowFill8364.laneEntryPx8364(px, lane)
        t.legs.filter { it.entry == Entry8347.NOW }.forEach { it.active = true; it.entryPx = fillPx8364; it.entryAtMs = nowMs }
        // Same-key deduplication and distinct-key capacity reservation are atomic.
        // No pricing, learning or book serialization runs under this lock.
        synchronized(ticketReservation8372) {
            if (tickets.containsKey(ticketKey8368)) { skip8360(SKIP_ALREADY_OPEN_8360); return null }
            val recent = lastOpenAt[ticketKey8368]
            if (recent != null && nowMs - recent in 0L until REOPEN_GAP_MS_8347) { skip8360(SKIP_REOPEN_GAP_8360); return null }
            if (tickets.size >= MAX_TICKETS_8347 && !evict8360(null, nowMs)) { full.incrementAndGet(); skip8360(SKIP_FULL_8360); return null }
            if (laneCount8360(lane) >= MAX_TICKETS_PER_LANE_8351 && !evict8360(lane, nowMs)) { full.incrementAndGet(); skip8360(SKIP_LANE_FULL_8360); return null }
            if (tickets.putIfAbsent(ticketKey8368, t) != null) { skip8360(SKIP_ALREADY_OPEN_8360); return null }
            laneCounts8360.computeIfAbsent(lane) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
            lastOpenAt[ticketKey8368] = nowMs
        }
        try {
            t.legs.filter { it.entry == Entry8347.NOW && it.active }.forEach { landed8366(t, it, nowMs) }   // V5.0.8366 — the buy lands at the route's measured rate
            persistTicket8355(ticketKey8368, t, nowMs)   // V5.0.8355 — written on open
            lastOpenAt[ticketKey8368] = nowMs
            if (lastOpenAt.size > 30_000) lastOpenAt.entries.removeIf { nowMs - it.value > REOPEN_GAP_MS_8347 }
            opened.incrementAndGet()
            openedByKind8360.computeIfAbsent(kind) { AtomicLong(0) }.incrementAndGet()
            openedBySrc8360.computeIfAbsent(markSrc.ifBlank { "?" }) { AtomicLong(0) }.incrementAndGet()
            want8360(ts.mint, nowMs)   // V5.0.8360 — the data side keeps pricing what the shadow holds
            try { PipelineHealthCollector.labelInc("EXPLORE_SHADOW_OPEN_8347_${lane}_$kind") } catch (_: Throwable) {}
        } catch (_: Throwable) {
            // The reservation already succeeded, so report its timestamp and subsequently release its prediction.
            // Returning null here would leave a real shadow ticket whose original assessment had been discarded.
            t.cortex8372 = t.cortex8372.unavailable8372("SHADOW_OPEN_ERROR_8372", nowMs)
            t.legs.forEach { it.dead = true }
            t.force8355 = true
            persistTicket8355(ticketKey8368, t, nowMs)
            skip8360("SHADOW_OPEN_ERROR_8372")
        } finally { t.ready8372 = true }
        return t.atMs
    }

    /** V5.0.8360 — opened by kind / skipped by reason / opened by mark source (tests and the status line). */
    private fun counts8360(): Triple<Map<String, Long>, Map<String, Long>, Map<String, Long>> = Triple(
        openedByKind8360.mapValues { it.value.get() }, skips8360.mapValues { it.value.get() }, openedBySrc8360.mapValues { it.value.get() },
    )

    // ── tick (BotService shadow loop) ──

    fun tick8347(tokens: Map<String, TokenState>, nowMs: Long = System.currentTimeMillis()) {
        if (!tickActive8372.compareAndSet(false, true)) return
        try { tickSingle8372(tokens, nowMs) } finally {
            // One checkpoint for all ready terminal receipts, not one full Cortex snapshot per ticket.
            // A false/failed flush keeps every unacknowledged ticket; the next tick sees durable receipts and ACKs.
            try { com.lifecyclebot.engine.cortex.Cortex7885.flushShadowOutcomes8372() } catch (_: Throwable) {
                skip8360("SHADOW_CHECKPOINT_RETRY_8372")
            }
            try { flushOpen8386(nowMs) } catch (_: Exception) { openRetry8386.incrementAndGet() }
            tickActive8372.set(false)
        }
    }

    private fun tickSingle8372(tokens: Map<String, TokenState>, nowMs: Long) {
        flushWaits8355(nowMs)   // V5.0.8355 — the waits map, when it changed (independent of the tickets)
        if (tickets.isEmpty()) { lastTicked8360 = 0; return }
        val done = ArrayList<Pair<String, Ticket8347>>()
        // V5.0.8360 — at most TICK_PER_LOOP_8360 tickets per loop, round-robin; one observed-mark read per mint per loop
        val keys = ArrayList<String>(tickets.keys)
        val slice = rrSlice8360(keys.size, rrCursor8360)
        rrCursor8360 = slice.second
        lastTicked8360 = slice.first.size
        val marks = HashMap<String, ShadowMark8360.Observation8372?>()
        val batch8377 = openPrefs8355
        val staged8377 = ArrayList<Pair<String, Ticket8347>>()
        val receipts8377 = ArrayList<Pair<String, Ticket8347>>()
        for (i in slice.first) {
            val key = keys[i]
            val t = synchronized(ticketReservation8372) {
                tickets[key]?.takeIf { it.ready8372 }?.also { it.ticking8372 = true }
            } ?: continue
            try {
                try {
                    if (!marks.containsKey(t.mint)) marks[t.mint] = ShadowMark8360.observedTimed8372(tokens[t.mint], t.mint, nowMs)
                    // A cache/source handoff must not move this episode's price path backwards in time.
                    val observation = marks[t.mint]?.takeIf { it.observedAtMs >= t.lastPxAtMs }
                    val fresh: Double? = observation?.priceUsd
                    if (observation != null) { t.lastPx = observation.priceUsd; t.lastPxAtMs = observation.observedAtMs }
                    want8360(t.mint, nowMs)   // V5.0.8360 — keep the data side pricing an open ticket
                    // superseded by V5.0.8418 — a restored ticket gets a full lost-mark window after the restart (ShadowPersist8355.lostMark8418)
                    val lost = ShadowPersist8355.lostMark8418(t.lastPxAtMs, t.restoredAtMs8418, nowMs, LOST_MARK_MS_8347)
                    for (leg in t.legs) {
                        if (leg.dead) continue
                        if (!leg.active) { arm8347(t, leg, fresh, nowMs); continue }
                        step8347(t, leg, fresh, lost, nowMs, if (leg.exit == Exit8347.SCALP) scalpIn8366(key, t, tokens[t.mint]) else null,
                            observation?.observedAtMs ?: 0L)
                    }
                } catch (_: ShadowStorageUnavailable8397) {
                    // Storage backpressure cannot turn a valid pending outcome into unavailable evidence.
                    t.force8355 = true; cortexRetry8372.incrementAndGet(); continue
                } catch (_: Throwable) {
                    // A simulator error is missing evidence, not a token loss and not a silently discarded prediction.
                    t.cortex8372 = t.cortex8372.unavailable8372("SHADOW_TICK_ERROR_8372", nowMs)
                    t.legs.forEach { it.dead = true }
                    t.force8355 = true
                    skip8360("SHADOW_TICK_ERROR_8372")
                }
                if (t.legs.all { it.dead } && t.cortex8372.awaiting8372() && !t.cortex8372.pending8372()) {
                    t.cortex8372 = t.cortex8372.unavailable8372("NO_SELECTED_EXIT_OUTCOME_8372", nowMs)
                    t.force8355 = true
                }
                // Persist a completed learning receipt before delivery. A restart may replay it; the learner owns the
                // exact decision-id receipt and acknowledges only after handling it. No Cortex call holds a ticket lock.
                if (t.cortex8372.pending8372()) {
                    if (!t.receiptDurable8372) {
                        // V5.0.8377 — staged; committed once for the whole slice, then delivered (below)
                        if (batch8377 == null || !stageTicket8377(batch8377, key, t)) {
                            t.force8355 = true; cortexRetry8372.incrementAndGet(); continue
                        }
                        receipts8377 += key to t
                        continue
                    }
                    deliverCortex8372(t)
                }
                if (t.legs.all { it.dead } && !t.cortex8372.awaiting8372()) {
                    done += key to t
                } else if (ShadowPersist8355.writeDue8355(t.dirty8355, t.lastWriteMs8355, nowMs, t.force8355)) {
                    // V5.0.8355 — a leg armed / closed writes now; a tier / peak move waits for the 10 s coalesce.
                    // V5.0.8377 — staged into the slice's one editor instead of one full-file write per ticket.
                    if (batch8377 != null && stageTicket8377(batch8377, key, t)) staged8377 += key to t
                    else persistTicket8355(key, t, nowMs)
                }
            } finally { t.ticking8372 = false }
        }
        if (batch8377 != null && (staged8377.isNotEmpty() || receipts8377.isNotEmpty())) {
            // The one terminal receipt must survive a process death before Cortex ACKs it: commit when any is staged.
            val written = flushOpen8386(nowMs, durable = receipts8377.isNotEmpty())
            sliceWrites8377.incrementAndGet(); sliceTickets8377.addAndGet((staged8377.size + receipts8377.size).toLong())
            for ((_, t) in staged8377) {
                if (written) { t.dirty8355 = false; t.force8355 = false; t.lastWriteMs8355 = nowMs } else t.force8355 = true
            }
            for ((key, t) in receipts8377) {
                if (!written) { t.force8355 = true; cortexRetry8372.incrementAndGet(); continue }
                t.dirty8355 = false; t.force8355 = false; t.lastWriteMs8355 = nowMs; t.receiptDurable8372 = true
                val claimed = synchronized(ticketReservation8372) {
                    (tickets[key] === t && !t.ticking8372).also { if (it) t.ticking8372 = true }
                }
                if (!claimed) continue   // replaced / busy: the next visit delivers the durable receipt
                try { deliverCortex8372(t) } catch (_: Throwable) {} finally { t.ticking8372 = false }
            }
        }
        flushBooks8377(nowMs)
        synchronized(ticketReservation8372) {
            for ((key, completed) in done) tickets.computeIfPresent(key) { _, current ->
                // Entry-side eviction/re-open can replace a completed ticket between
                // stepping and cleanup. Delete only this exact episode, with its
                // preferences removed before a new reservation may take this key.
                if (current !== completed || !current.legs.all { it.dead } || current.cortex8372.awaiting8372()) current
                else {
                    if (!forgetTicket8355(key)) return@computeIfPresent current
                    scalpInMap8366.remove(key)
                    null
                }
            }
            recountLanes8360()   // exact snapshot while distinct-key reservations cannot change the counts
        }
    }

    /** Only this tick-side delivery crosses into the learner; an assessment/open never performs training or price I/O. */
    private fun deliverCortex8372(t: Ticket8347) {
        val outcome = t.cortex8372
        if (!outcome.pending8372()) return
        val learned = outcome.unavailable.isBlank() && outcome.netPct.isFinite() && outcome.grossPct.isFinite()
        val acknowledged = try {
            if (learned) com.lifecyclebot.engine.cortex.Cortex7885.onShadowOutcome8372(
                t.mint, t.lane, t.atMs, outcome.netPct, outcome.grossPct, t.setup, outcome.atMs, executable = true,
            ) else com.lifecyclebot.engine.cortex.Cortex7885.onShadowUnavailable8372(
                t.mint, t.lane, t.atMs, outcome.unavailable.ifBlank { "INVALID_TERMINAL_MARK_8372" },
            )
        } catch (_: Throwable) { false }
        if (!acknowledged) { cortexRetry8372.incrementAndGet(); return }
        t.cortex8372 = outcome.acknowledged8372()
        t.force8355 = true
        if (learned) cortexOutcomeAck8372.incrementAndGet() else cortexUnavailable8372.incrementAndGet()
    }

    internal fun entryReady8394(entry: Entry8347, px: Double, signal: Double, ageMs: Long, pullbackPct: Double): Boolean {
        if (!px.isFinite() || px <= 0.0 || !signal.isFinite() || signal <= 0.0) return false
        return when (entry) {
            Entry8347.NOW -> true
            Entry8347.PULLBACK -> pullbackPct.isFinite() && px <= signal * (1.0 - pullbackPct / 100.0)
            Entry8347.CONFIRM -> ageMs >= CONFIRM_AFTER_MS_8347 && px >= signal * (1.0 + CONFIRM_RISE_PCT_8347 / 100.0)
        }
    }

    private fun arm8347(t: Ticket8347, leg: Leg8347, px: Double?, nowMs: Long) {
        val age = nowMs - t.atMs
        when (leg.entry) {
            // V5.0.8364 — a fill is booked at the trigger mark plus the lane's chase (ShadowFill8364); an expired leg is a
            // "no trade" signal (net 0, never a loss) counted in the arm's fill rate (unfilled8364), so arms compare per signal.
            Entry8347.PULLBACK -> {
                if (px != null && entryReady8394(Entry8347.PULLBACK, px, t.signalPx, age, t.entryPullbackPct8394)) { leg.active = true; leg.entryPx = ShadowFill8364.laneEntryPx8364(px, t.lane); leg.entryAtMs = nowMs; t.force8355 = true; landed8366(t, leg, nowMs) }
                else if (age >= PULLBACK_WAIT_MS_8347) { unfilled8364(t, leg, nowMs); leg.dead = true; t.force8355 = true }   // never filled: no trade, counted unfilled
            }
            Entry8347.CONFIRM -> {
                if (px != null && entryReady8394(Entry8347.CONFIRM, px, t.signalPx, age, t.entryPullbackPct8394)) { leg.active = true; leg.entryPx = ShadowFill8364.laneEntryPx8364(px, t.lane); leg.entryAtMs = nowMs; t.force8355 = true; landed8366(t, leg, nowMs) }
                else if (age >= CONFIRM_WAIT_MS_8347) { unfilled8364(t, leg, nowMs); leg.dead = true; t.force8355 = true }
            }
            Entry8347.NOW -> { leg.active = true; leg.entryPx = ShadowFill8364.laneEntryPx8364(t.signalPx, t.lane); leg.entryAtMs = t.atMs; t.force8355 = true; landed8366(t, leg, nowMs) }
        }
    }

    private fun step8347(t: Ticket8347, leg: Leg8347, px: Double?, lost: Boolean, nowMs: Long,
                         scalp8366: DoubleArray? = null, observedAtMs8372: Long = 0L) {
        if (t.cortex8372.owns8372(leg.entry.name, leg.exit.name)) {
            val horizon = if (leg.hold8366) ShadowAlign8366.holdWindowMs8366() else if (leg.exit == Exit8347.SCALP) SCALP_TIMEOUT_MS_8347 else if (t.runner) RUNNER_TIMEOUT_MS_8347 else OTHER_TIMEOUT_MS_8347
            com.lifecyclebot.engine.cortex.Cortex7885.shadowMaturity8394(t.mint, t.lane, t.atMs,
                if (leg.hold8366) "TERMINAL_HOLD" else if (leg.exit == Exit8347.SCALP) "TERMINAL_SCALP" else "TERMINAL_RUNNER",
                leg.entryAtMs + horizon)
        }
        val g = (t.lastPx / leg.entryPx - 1.0) * 100.0
        if (!g.isFinite()) {
            leg.dead = true; t.force8355 = true
            t.cortex8372 = t.cortex8372.closed8372(leg.entry.name, leg.exit.name, Double.NaN, Double.NaN,
                "INVALID_EXIT_MARK_8372", nowMs, executable = false)
            return
        }
        if (g > leg.peak) leg.peakAt8366 = nowMs   // V5.0.8366 — when the peak was set (the hold's dead-money clock)
        if (g > leg.peak) { leg.peak = g; t.dirty8355 = true }       // V5.0.8355 — peak / trough / tier moves are persisted changes
        if (g < leg.trough) { leg.trough = g; t.dirty8355 = true }
        // V5.0.8372 — a triggered full exit needs a NEW observation after the live sell latency. Rereading the same
        // still-fresh cached mark does not make a fill. A lost mark ends the experiment as unavailable to Cortex.
        val pending8366 = leg.exitWhy8366
        if (pending8366 != null) {
            if (lost) { closeAt8366(t, leg, g, "LOST_MARK", nowMs, executable = false); return }
            if (px == null || !ShadowPersist8355.exitFillObserved8372(observedAtMs8372, leg.exitAt8366,
                    ShadowAlign8366.sellLatencyMs8366(false), nowMs)) return
            if (leg.sellTier8394 > 0 || leg.sellTaken8394 > 0) {
                val sold = leg.held * leg.sellFraction8394.coerceIn(0.0, 1.0)
                leg.banked += sold * ShadowFill8364.exitGrossPct8364(g); leg.held -= sold
                leg.tiers = maxOf(leg.tiers, leg.sellTier8394); leg.taken8366 = maxOf(leg.taken8366, leg.sellTaken8394)
                leg.exitWhy8366 = null; leg.exitAt8366 = 0L; leg.sellFraction8394 = 1.0; leg.sellTier8394 = 0; leg.sellTaken8394 = 0
                t.force8355 = true
                if (leg.held <= 0.0001) closeAt8366(t, leg, g, "SOLD_OUT", nowMs)
            } else closeAt8366(t, leg, g, pending8366, nowMs)
            return
        }
        val why: String? = when (leg.exit) {
            Exit8347.RUNNER -> runnerWhy8366(t, leg, g, px, lost, nowMs)
            Exit8347.SCALP -> scalpWhy8366(t, leg, g, px, lost, nowMs, scalp8366)
        }
        if (why == null) return
        // nothing left to sell, or no observed mark to wait for: booked now at the last mark
        if (why == "SOLD_OUT" || why == "LOST_MARK" || px == null) {
            closeAt8366(t, leg, g, why, nowMs, executable = why == "SOLD_OUT" || (!lost && px != null))
            return
        }
        leg.exitWhy8366 = why; leg.exitAt8366 = nowMs; t.force8355 = true
    }

    /** V5.0.8366 — the leg's close at gross [g] (banked slices + the remainder, less the round trip). */
    private fun closeAt8366(t: Ticket8347, leg: Leg8347, g: Double, why: String, nowMs: Long, executable: Boolean = true) =
        close8347(t, leg, EvidenceShadow8344.netPct8344(leg.banked, leg.held, ShadowFill8364.exitGrossPct8364(g), t.costPct), why, nowMs, executable)   // V5.0.8364 — exit slippage not in costPct8030 (none today)

    /**
     * V5.0.8366 — the RUNNER leg on live's levers: SpikeCapture7943 tiers, the lane stop, the 8338 give-back floor, the level
     * live's fluid stop holds (ShadowAlign8366.liveStopLevel8366: net break-even once cleared, the green floor on a runner
     * lane), the clock; and once at +100 % (LifecycleHold8065's promotion) the lifecycle hold's own exits, no clock.
     */
    private fun requestSlice8394(t: Ticket8347, leg: Leg8347, fraction: Double, why: String, nowMs: Long, tier: Int = 0, taken: Int = 0) {
        leg.sellFraction8394 = fraction; leg.sellTier8394 = tier; leg.sellTaken8394 = taken
        leg.exitWhy8366 = why; leg.exitAt8366 = nowMs; t.force8355 = true
    }

    private fun runnerWhy8366(t: Ticket8347, leg: Leg8347, g: Double, px: Double?, lost: Boolean, nowMs: Long): String? {
        if (!leg.hold8366 && px != null && ShadowAlign8366.holdPromotes8366(g, leg.peak)) { leg.hold8366 = true; t.force8355 = true }
        if (leg.hold8366) {
            val d = if (px != null) ShadowAlign8366.holdExit8366(g, leg.peak, leg.taken8366, nowMs - leg.entryAtMs, nowMs - leg.peakAt8366, t.costPct) else null
            if (d != null) {
                val n = ShadowAlign8366.takeOf8366(d.first)
                if (n < 0) return d.first
                requestSlice8394(t, leg, d.second, d.first, nowMs, taken = n)
                return null
            }
            return when {
                leg.held <= 0.0001 -> "SOLD_OUT"
                lost -> "LOST_MARK"
                else -> null
            }
        }
        if (px != null && leg.tiers < com.lifecyclebot.engine.SpikeCapture7943.TIERS.size) {
            val (at, frac) = com.lifecyclebot.engine.SpikeCapture7943.TIERS[leg.tiers]
            if (g >= at) { requestSlice8394(t, leg, frac, "SPIKE_PARTIAL_8394", nowMs, tier = leg.tiers + 1); return null }
        }
        val floor = EvidenceShadow8344.giveBackFloor8344(leg.peak, t.armPct, t.costPct)
        val level = ShadowAlign8366.liveStopLevel8366(t.runner, t.stopPct, leg.peak, t.costPct)
        return when {
            leg.held <= 0.0001 -> "SOLD_OUT"
            px != null && g <= -t.stopPct -> "STOP"
            px != null && floor != null && g <= floor -> "GIVEBACK_FLOOR"
            px != null && level > -t.stopPct && g <= level -> "LIVE_FLOOR_8366"
            nowMs - leg.entryAtMs >= (if (t.runner) RUNNER_TIMEOUT_MS_8347 else OTHER_TIMEOUT_MS_8347) -> "TIMEOUT"
            lost -> "LOST_MARK"
            else -> null
        }
    }

    /**
     * V5.0.8366 — the SCALP leg on the live scalp exit itself (ScalpStop8363.decide8363 over ScalpExit8351.decide8351's
     * ladder): +15 % sells half, +30 % the rest, the 8363 stop max(0.6 x lane stop, 2 x 1-minute range + chase, 6 %) with
     * no stop in the first 60 s, the give-back floor, the 10-minute clock.
     */
    private fun scalpWhy8366(t: Ticket8347, leg: Leg8347, g: Double, px: Double?, lost: Boolean, nowMs: Long, scalpIn: DoubleArray?): String? {
        if (px != null) {
            val noise = scalpIn?.getOrNull(0) ?: Double.NaN
            val chase = scalpIn?.getOrNull(1) ?: 0.0
            var guard = 0
            val d = com.lifecyclebot.engine.ScalpStop8363.decide8363(g, maxOf(leg.peak, g), nowMs - leg.entryAtMs, leg.tiers, t.stopPct, t.costPct, noise, chase)
            if (d != null) {
                val tier = if (d.second.endsWith("_T1")) 1 else if (d.second.endsWith("_T2")) 2 else 0
                if (tier == 0) return d.second
                requestSlice8394(t, leg, d.first, d.second, nowMs, tier = tier)
                return null
            }
        }

        return when {
            leg.held <= 0.0001 -> "SOLD_OUT"
            nowMs - leg.entryAtMs >= SCALP_TIMEOUT_MS_8347 -> "TIMEOUT"
            lost -> "LOST_MARK"
            else -> null
        }
    }

    // V5.0.8366 — per ticket: the coin's 1-minute range and the lane's entry chase, read once at its first scalp mark (as live does)
    private val scalpInMap8366 = ConcurrentHashMap<String, DoubleArray>()

    private fun scalpIn8366(key: String, t: Ticket8347, ts: TokenState?): DoubleArray {
        scalpInMap8366[key]?.let { return it }
        val noise = if (ts == null) Double.NaN else (try { com.lifecyclebot.engine.ScalpStop8363.noise1mPct8363(ts) } catch (_: Throwable) { Double.NaN })
        val chase = try { com.lifecyclebot.engine.ScalpStop8363.chasePct8363(t.lane) } catch (_: Throwable) { 0.0 }
        val v = doubleArrayOf(noise, chase)
        if (scalpInMap8366.size > MAX_TICKETS_8347 * 2) scalpInMap8366.clear()
        scalpInMap8366[key] = v
        return v
    }

    /** V5.0.8366 — does this leg's buy land (the route's measured rate, deterministic per ticket x entry)? Unlanded: closed on the failed-tx fee. */
    private fun landed8366(t: Ticket8347, leg: Leg8347, nowMs: Long): Boolean {
        val fee = (try { ShadowAlign8366.unlandedEntryFeePct8366("${t.mint}|${t.lane}|${t.setup}|${t.atMs}|${leg.entry}", t.mint, nowMs) } catch (_: Throwable) { null }) ?: return true
        close8347(t, leg, -fee, ShadowAlign8366.UNLANDED_WHY_8366, nowMs, executable = false)
        return false
    }

    private fun close8347(t: Ticket8347, leg: Leg8347, net: Double, why: String, nowMs: Long, executable: Boolean = true) {
        if (leg.dead) return
        val cohortKeys8397 = ArrayList<String>()
        var outcome8397 = t.cortex8372
        val v = "${t.lane}|${leg.entry}|${leg.exit}"
        // V5.0.8351 — the variant book, plus three cohort books (setup, market-cap band, native yes / sampled no).
        // V5.0.8360 — by kind: a NATIVE ticket feeds the variant book (live adoption) and S: / M: / R:NATIVE, plus T:<tactic> on
        // NOW/RUNNER; a REJECT ticket only R:REJECT (the FILTER_INVERTED comparison; now every reject, so it stays out of the
        // live proof); a SETUP ticket only its own S:<setup> (SR:<setup> when the lane refused) — it shares the coin's price
        // path with the native ticket, so it never re-enters the variant book as a second sample.
        if (!executable) {
            leg.dead = true; t.force8355 = true
            t.cortex8372 = t.cortex8372.closed8372(leg.entry.name, leg.exit.name, net, net + t.costPct, why, nowMs, false)
            try { PipelineHealthCollector.labelInc("SHADOW_OUTCOME_UNAVAILABLE_8394_$why") } catch (_: Throwable) {}
            return
        }
        when (t.kind8360) {
            KIND_CORTEX_YES_8368, KIND_CORTEX_NO_8368, KIND_CORTEX_HOLD_8368 -> {
                // Shadow-only realised result for the Cortex's own verdict in its own lane.
                // Do NOT mingle with native winner evidence, live fills or the canonical Cortex close bus.
                // Outcomes also teach the exact setup and tactic within this lane/verdict.
                // Kept in Cortex-only books so a rejected trade never earns native LIVE authority.
                for (k in ShadowPersist8355.cortexPopulationKeys8372(t.lane, leg.entry.name, leg.exit.name,
                    t.kind8360, t.setup, t.tactic)) cohortKeys8397 += k
                // The prediction learns the actual modelled position return (banked partials plus its final exit),
                // never raw token mark return, the best of two exits, or two samples for one assessment.
                outcome8397 = t.cortex8372.closed8372(leg.entry.name, leg.exit.name, net, net + t.costPct,
                    why, nowMs, executable && ShadowPersist8355.proofEligible8394(t.contractVersion8394.toDouble(), t.atMs.toDouble(), 0L))
            }
            KIND_SETUP_8360 -> cohortKeys8397 += "C|$v|${if (t.nativeEligible) "S" else "SR"}:${t.setup}"
            KIND_REJECT_8360 -> cohortKeys8397 += "C|$v|R:REJECT"
            else -> {
                cohortKeys8397 += v
                cohortKeys8397 += "C|$v|S:${t.setup}"
                cohortKeys8397 += "C|$v|M:${t.band}"
                cohortKeys8397 += "C|$v|R:${if (t.nativeEligible) "NATIVE" else "REJECT"}"
                if (leg.entry == Entry8347.NOW && leg.exit == Exit8347.RUNNER) cohortKeys8397 += "C|$v|T:${t.tactic}"
            }
        }
        val booked8397 = bookKeys8397(t, cohortKeys8397, net, nowMs, "${t.mint}|${t.lane}|${t.setup}|${t.kind8360}|${t.atMs}|${leg.entry}|${leg.exit}|CLOSE")
        if (!booked8397.inserted && isCortexKind8368(t.kind8360)) {
            val originalNet = booked8397.terminal[1]
            outcome8397 = t.cortex8372.closed8372(leg.entry.name, leg.exit.name, originalNet, originalNet + t.costPct,
                why, booked8397.terminal[0].toLong(), executable && ShadowPersist8355.proofEligible8394(t.contractVersion8394.toDouble(), t.atMs.toDouble(), 0L))
        }
        leg.dead = true; t.force8355 = true; t.cortex8372 = outcome8397
        if (!booked8397.inserted) return // crash replay completes the ticket; external learning side effects are not doubled
        if (isCortexKind8368(t.kind8360)) cortexClosesByKind8368.computeIfAbsent(t.kind8360) { AtomicLong(0) }.incrementAndGet()
        closed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EXPLORE_SHADOW_CLOSE_8347_${leg.exit}_$why") } catch (_: Throwable) {}
        // V5.0.8366 — the leg that models a live position (native, entered NOW, on the lane's live exit style) feeds the alignment pairs
        if (t.kind8360 == KIND_NATIVE_8360 && leg.entry == Entry8347.NOW && why != ShadowAlign8366.UNLANDED_WHY_8366) try {
            val liveExit = liveExitStyle8351(t.lane, EvidenceHold8336.startMs8336) ?: Exit8347.RUNNER
            if (leg.exit == liveExit) ShadowAlign8366.noteShadow8366("EXPLORE", t.lane, t.mint, leg.entryAtMs, net, nowMs)
        } catch (_: Throwable) {}
        // Tactics learn from the variant closest to how live enters and exits; native rejects do not attribute a tactic.
        if (t.kind8360 == KIND_NATIVE_8360 && t.nativeEligible && leg.entry == Entry8347.NOW && leg.exit == Exit8347.RUNNER && t.scoreBand.isNotBlank() &&
            why != ShadowAlign8366.UNLANDED_WHY_8366) try {   // V5.0.8366 — an unlanded buy is no trade for the tactic
            com.lifecyclebot.engine.learning.TacticSwitcher.onCanonicalTradeClosed6486(t.lane, t.scoreBand, t.tactic, net)
        } catch (_: Throwable) {}
    }

    private fun bookKeys8397(t: Ticket8347, keys: Collection<String>, net: Double, nowMs: Long, event: String): ShadowCohortBook8397.AppendResult {
        val terminal = doubleArrayOf(nowMs.toDouble(), net, t.contractVersion8394.toDouble(), t.atMs.toDouble())
        val result = books.append(keys, terminal.copyOf().also { it[1] = net.coerceIn(-100.0, 1_000.0) }, event, terminal)
        if (result.inserted) { bookFlushes8377.incrementAndGet(); bookKeysWritten8377.addAndGet(keys.size.toLong()) }
        return result
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
    data class Choice8351(
        val entry: Entry8347, val exit: Exit8347, val dim: String?, val value: String?, val proof: EvidenceHold8336.Proof8336,
        val fillRate: Double = 1.0,   // V5.0.8364 — filled / (filled + expired-unfilled) signals; 1.0 for NOW
    ) {
        val label: String get() = "$entry/$exit" + (if (dim != null) " $dim:$value" else "")
    }

    private fun netsSince(key: String, sinceMs: Long): List<Double> {
        // V5.0.8398 — inside one status-line walk each (key, since) is read from the cold store once, not once per cohort scan.
        val memo = reportMemo8398.get()
        if (memo != null) return memo.getOrPut("$key|$sinceMs") { netsSinceUncached8398(key, sinceMs) }
        return netsSinceUncached8398(key, sinceMs)
    }

    private fun netsSinceUncached8398(key: String, sinceMs: Long): List<Double> {
        val q = books[key] ?: return emptyList()
        return synchronized(q) { q.filter { it[0] >= sinceMs && ShadowPersist8355.proofEligible8394(it.getOrNull(2), it.getOrNull(3), sinceMs) }.map { it[1] } }
    }

    private val reportMemo8398 = ThreadLocal<HashMap<String, List<Double>>?>()

    // superseded by V5.0.8418 — runtime 8416 choice8399[staleServed=35569 cached=24]: the cache was only time-stamped, so every
    // read after 30 s was "stale" whether or not a close had landed, all 24 keys expired together, and each refresh walked the
    // whole 22k-key book index per variant on one MIN_PRIORITY thread (cold SQLite reads), so refreshes never kept up and an
    // arbitrarily old choice was served. A cached choice now carries the lane's book revision: unchanged rows = the same
    // answer (served, no recompute); changed rows = stale, recomputed in the background and served only within the grace
    // window, after which the lane has no adopted choice (the cold state) until the fresh one lands.
    private class CachedChoice8418(val atMs: Long, val revision: Long, val choice: Choice8351?)
    private val choiceCache8351 = ConcurrentHashMap<String, CachedChoice8418>()
    private const val CHOICE_CACHE_MS_8351 = 30_000L

    enum class ChoiceFreshness8418 { FRESH, STALE_GRACE, STALE_EXPIRED, COLD }

    /** Pure: is a cached choice current? Same book revision = FRESH at any age; otherwise stale, servable only inside [graceMs]. */
    fun choiceFreshness8418(cachedRevision: Long?, cachedAtMs: Long?, laneRevision: Long, nowMs: Long,
                            graceMs: Long = CHOICE_CACHE_MS_8351): ChoiceFreshness8418 {
        if (cachedRevision == null || cachedAtMs == null) return ChoiceFreshness8418.COLD
        if (cachedRevision == laneRevision) return ChoiceFreshness8418.FRESH
        val age = nowMs - cachedAtMs
        return if (age in 0L until graceMs) ChoiceFreshness8418.STALE_GRACE else ChoiceFreshness8418.STALE_EXPIRED
    }

    /** Pure: what a read serves for [f] — the cached choice when fresh or inside grace, else none (no adopted choice). */
    fun <T> choiceServed8418(f: ChoiceFreshness8418, cached: T?): T? =
        if (f == ChoiceFreshness8418.FRESH || f == ChoiceFreshness8418.STALE_GRACE) cached else null

    /** The lane's best proven way to trade (a whole variant, or a setup / market-cap-band cohort of one), or null. */
    fun bestFor8351(lane: String, sinceMs: Long): Choice8351? {
        val l = canon(lane)
        val now = System.currentTimeMillis()
        val cacheKey8394 = "$l|$sinceMs|${LearningEpoch8164.epochMs8164()}|${ShadowPersist8355.TRADE_CONTRACT_8394}"
        val hit = choiceCache8351[cacheKey8394]
        val laneRev = try { books.laneRevision8418(l) } catch (_: Throwable) { -1L }
        val fresh = choiceFreshness8418(hit?.revision, hit?.atMs, laneRev, now)
        if (fresh == ChoiceFreshness8418.FRESH) { choiceFresh8418.incrementAndGet(); return hit?.choice }
        // Cold and stale choices refresh in the background (never a scan on the caller's thread).
        if (fresh == ChoiceFreshness8418.COLD) choiceColdMiss8399.incrementAndGet()
        if (choiceRefreshing8399.add(cacheKey8394)) {
            try {
                choiceExecutor8399.execute {
                    try {
                        val rev = books.laneRevision8418(l)   // read BEFORE the scan: a close landing mid-scan leaves it stale
                        choiceCache8351[cacheKey8394] = CachedChoice8418(System.currentTimeMillis(), rev, computeBest8351(l, sinceMs))
                    }
                    catch (_: Throwable) {}
                    finally { choiceRefreshing8399.remove(cacheKey8394) }
                }
            } catch (_: Throwable) { choiceRefreshing8399.remove(cacheKey8394) }
        }
        if (fresh == ChoiceFreshness8418.STALE_GRACE) choiceServedStale8399.incrementAndGet()
        if (fresh == ChoiceFreshness8418.STALE_EXPIRED) choiceStaleWithheld8418.incrementAndGet()
        return choiceServed8418(fresh, hit?.choice)
    }

    private val choiceFresh8418 = AtomicLong(0L)
    private val choiceStaleWithheld8418 = AtomicLong(0L)
    private val choiceRefreshing8399 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val choiceServedStale8399 = AtomicLong(0L)
    private val choiceColdMiss8399 = AtomicLong(0L)
    private val choiceExecutor8399: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "shadow-choice-8399").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    fun choiceStats8399(): String = "choice8399[staleServed=${choiceServedStale8399.get()} coldMiss=${choiceColdMiss8399.get()} refreshing=${choiceRefreshing8399.size} cached=${choiceCache8351.size} " +
        "fresh8418=${choiceFresh8418.get()} staleWithheld8418=${choiceStaleWithheld8418.get()}]"

    private fun computeBest8351(l: String, sinceMs: Long): Choice8351? {
        // V5.0.8418 — the lane's cohort keys once (was the whole book index copied and walked once per variant)
        val laneCohortKeys8418 = books.keys.filter { it.startsWith("C|$l|") }
        val out = ArrayList<Choice8351>()
        val rivals8364 = ArrayList<Choice8351>()   // V5.0.8364 — every PROVEN (starred) variant / setup cohort, for the M: margin rule
        for (e in Entry8347.values()) for (x in Exit8347.values()) {
            val v = "$l|$e|$x"
            // V5.0.8364 — a variant is adopted only when its lower bound clears the margin at the SAME 2 SE the cohorts use
            variantProof8347(netsSince(v, sinceMs)).takeIf { it.proven }?.let {
                val c = Choice8351(e, x, null, null, it, fillRateFor8364(v, sinceMs))
                rivals8364 += c
                if (variantAdoptable8364(it)) out += c
            }
            val prefix = "C|$v|"
            for (k in laneCohortKeys8418) {
                if (!k.startsWith(prefix)) continue
                val tail = k.removePrefix(prefix)
                val dim = tail.substringBefore(':'); val value = tail.substringAfter(':')
                if (dim != "S" && dim != "M") continue        // V5.0.8360 — R: (yes / no), SR: (setup on a refusal) and T: (tactic) are diagnostics, never a live niche
                if (value == "NONE" || value == "UNK" || value == "DATA_INCOMPLETE" || value == "NO_TRIGGER") continue
                cohortProof8351(netsSince(k, sinceMs)).takeIf { it.proven }?.let {
                    val c = Choice8351(e, x, dim, value, it, fillRateFor8364(k, sinceMs))
                    out += c
                    if (dim == "S") rivals8364 += c
                }
            }
        }
        // superseded by V5.0.8364 — ranked per SIGNAL (fill rate x (mean - 2 SE), the same SE for every arm); an M: band cohort
        // must beat the best proven arm of the other entry style by M_MARGIN_PT_8364 (pickBest8364). A whole-variant proof
        // still beats a niche of equal strength (it trades more).
        return pickBest8364(out, rivals8364)
    }

    // ── V5.0.8364 §HONEST_ADOPTER — audit 8360 item 2: a big-n "NOW, $10k-100k" market-cap-band cohort out-ranked the starred
    // pullback arms because an unfilled pullback was never booked (smaller n, a looser-looking bound) and a band is not a
    // chart setup. Every arm is now compared on the same footing: lower bound mean - 2 SE (COHORT_SE_MULT_8351) for variants
    // and cohorts alike, weighted by its fill rate (an expired pullback / confirm leg is a "no trade" signal: net 0, never a
    // loss, but it counts), and an M: cohort is adopted only when it beats the best proven arm of the OTHER entry style
    // (waiting PULLBACK / CONFIRM versus NOW) by M_MARGIN_PT_8364 points per signal. ──

    const val M_MARGIN_PT_8364 = 1.0
    private const val UNFILLED_PREFIX_8364 = "U|"
    private val unfilledN8364 = AtomicLong(0)

    /** Pure: filled / (filled + unfilled) signals; 1.0 with no signal yet. */
    fun fillRate8364(filled: Int, unfilled: Int): Double {
        val f = filled.coerceAtLeast(0); val u = unfilled.coerceAtLeast(0)
        return if (f + u <= 0) 1.0 else f.toDouble() / (f + u).toDouble()
    }

    /** Pure: the lower bound every arm is compared on — mean - 2 SE (the cohorts' stricter multiplier, for all). */
    fun lowerBound8364(meanPct: Double, sePct: Double): Double = meanPct - COHORT_SE_MULT_8351 * sePct

    /** Pure: the arm's edge per SIGNAL — fill rate x lower bound (an unfilled signal earns 0). */
    fun perSignal8364(c: Choice8351): Double = c.fillRate.coerceIn(0.0, 1.0) * lowerBound8364(c.proof.meanPct, c.proof.sePct)

    /** Pure: a proven variant is adoptable only when mean - 2 SE clears the margin too (never looser than variantProof8347). */
    fun variantAdoptable8364(p: EvidenceHold8336.Proof8336): Boolean =
        p.proven && lowerBound8364(p.meanPct, p.sePct) > MARGIN_PCT_8347

    /** Pure: may [c] be adopted against [rivals]? An M: cohort must beat the best rival of the other entry style by the margin. */
    fun mCohortAdmissible8364(c: Choice8351, rivals: List<Choice8351>): Boolean {
        if (c.dim != "M") return true
        val waits = c.entry != Entry8347.NOW
        var best = Double.NEGATIVE_INFINITY
        for (r in rivals) {
            if (r.dim == "M" || (r.entry != Entry8347.NOW) == waits) continue
            val s = perSignal8364(r)
            if (s.isFinite() && s > best) best = s
        }
        if (best == Double.NEGATIVE_INFINITY) return true
        return perSignal8364(c) > best + M_MARGIN_PT_8364
    }

    /** Pure: the adopted arm among [cands] — M: margin rule first, then the best per-signal lower bound (+0.5 for a whole variant). */
    fun pickBest8364(cands: List<Choice8351>, rivals: List<Choice8351>): Choice8351? =
        cands.filter { mCohortAdmissible8364(it, rivals) }
            .maxByOrNull { perSignal8364(it) + (if (it.dim == null) 0.5 else 0.0) }

    /** The book keys an expired leg of [t] is counted unfilled under (the keys its close would have fed; none for a reject). */
    private fun unfilledKeys8364(t: Ticket8347, leg: Leg8347): List<String> {
        val v = "${t.lane}|${leg.entry}|${leg.exit}"
        return when (t.kind8360) {
            KIND_CORTEX_YES_8368, KIND_CORTEX_NO_8368, KIND_CORTEX_HOLD_8368 ->
                ShadowPersist8355.cortexPopulationKeys8372(t.lane, leg.entry.name, leg.exit.name,
                    t.kind8360, t.setup, t.tactic)
            KIND_SETUP_8360 -> listOf("C|$v|${if (t.nativeEligible) "S" else "SR"}:${t.setup}")
            KIND_REJECT_8360 -> emptyList()
            else -> listOf(v, "C|$v|S:${t.setup}", "C|$v|M:${t.band}")
        }
    }

    /** An expired PULLBACK / CONFIRM leg: a "no trade" signal (net 0) under U|<key>, read only by the fill rate. */
    private fun unfilled8364(t: Ticket8347, leg: Leg8347, nowMs: Long) {
        try {
            bookKeys8397(t, unfilledKeys8364(t, leg).map { UNFILLED_PREFIX_8364 + it }, 0.0, nowMs, "${t.mint}|${t.lane}|${t.setup}|${t.kind8360}|${t.atMs}|${leg.entry}|${leg.exit}|UNFILLED")
            unfilledN8364.incrementAndGet()
        } catch (failure: ShadowStorageUnavailable8397) { throw failure } catch (_: Throwable) {}
    }

    /** Where a full ring of [key] starts (0 while the ring is not full): fill rates read the window both rings cover. */
    private fun ringStart8364(key: String): Long {
        val q = books[key] ?: return 0L
        return synchronized(q) { if (q.size >= MAX_CLOSES_8347) (q.firstOrNull()?.get(0)?.toLong() ?: 0L) else 0L }
    }

    /** The fill rate of the arm booked under [key] since [sinceMs] (filled closes vs expired-unfilled signals). */
    private fun fillRateFor8364(key: String, sinceMs: Long): Double {
        val u = UNFILLED_PREFIX_8364 + key
        if (!books.containsKey(u)) return 1.0
        val from = maxOf(sinceMs, ringStart8364(key), ringStart8364(u))
        return fillRate8364(netsSince(key, from).size, netsSince(u, from).size)
    }

    /** V5.0.8364 — the fill rate of [lane]'s variant (status line). */
    private fun variantFillRate8364(lane: String, entry: Entry8347, exit: Exit8347, sinceMs: Long): Double =
        fillRateFor8364("${canon(lane)}|$entry|$exit", sinceMs)

    /** Pure: does a candidate (its setup, its market-cap band) sit inside [c]'s niche? A whole-variant choice admits all. */
    // V5.0.8364 — a setup niche needs a NAMED setup (never null / NO_TRIGGER / UNK), and a band niche admits only a candidate
    // that also carries a named setup: a market-cap band alone is not a chart setup.
    fun inNiche8351(c: Choice8351, setup: String?, band: String?): Boolean = when (c.dim) {
        null -> true
        "S" -> setup != null && com.lifecyclebot.engine.cortex.LanePlaybook7907.namedSetup8392(setup) && setup.trim().equals(c.value, ignoreCase = true)
        // V5.0.8370 — owner chose "No fired-setup rule" (as 8114): an M: band niche is the band; it needs no named setup.
        "M" -> band != null && band.equals(c.value, ignoreCase = true)
        else -> true
    }

    /** Pure (V5.0.8364): [c]'s niche against the coin's own [fired] setups — an M: band needs one named fired setup. */
    fun inNiche8364(c: Choice8351, setup: String?, band: String?, fired: List<String>): Boolean = when (c.dim) {
        null -> true
        "S" -> inNiche8351(c, setup, band) || fired.any { inNiche8351(c, it, band) }
        "M" -> inNiche8351(c, setup, band)   // V5.0.8370 — the band alone (an S: niche still needs that setup)
        else -> false
    }

    /**
     * LiveEdgeGate7877.liveRefusal, after a LIVE admit: when the lane is unlocked by a NICHE, a candidate outside it is
     * refused (NICHE_ONLY_8351_<lane>). The shadow keeps exploring every candidate either way.
     */
    fun nicheRefusal8351(ts: TokenState, lane: String, sinceMs: Long): String? {
        val c = bestFor8351(lane, sinceMs) ?: return null
        if (c.dim == null) return null
        val setup = try { com.lifecyclebot.engine.SpecialistBrainBridge7542.evaluate(ts).opinions.entries
            .firstOrNull { canon(it.key) == canon(lane) }?.value?.setup } catch (_: Throwable) { null }
        val band = try { ForwardReturnLabeler7731.mcapBand(TrustedMcap8019.mcap8019(ts)) } catch (_: Throwable) { null }
        // V5.0.8360 — a setup niche proven on SETUP tickets names a playbook setup: a coin whose playbook fired it is inside.
        // V5.0.8364 — the fired setups decide both dims: an M: band admits only a coin that also fired a named setup.
        val fired = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.matchedSetups8083(ts, lane) } catch (_: Throwable) { emptyList<String>() }
        if (inNiche8364(c, setup, band, fired)) return null
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
        val nets = synchronized(q) { q.filter { it[0] >= sinceMs && ShadowPersist8355.proofEligible8394(it.getOrNull(2), it.getOrNull(3), sinceMs) }.map { it[1] } }
        return variantProof8347(nets)
    }

    /** The lane's best PROVEN variant (highest mean - 1.5 SE), or null while none has proven. */
    fun bestVariant8347(lane: String, sinceMs: Long): Pair<Pair<Entry8347, Exit8347>, EvidenceHold8336.Proof8336>? =
        Entry8347.values().flatMap { e -> Exit8347.values().map { x -> (e to x) to variantRecord8347(lane, e, x, sinceMs) } }
            .filter { it.second.proven }
            .maxByOrNull { it.second.meanPct - 1.5 * it.second.sePct }

    /**
     * LiveEdgeGate7877.liveRefusal, after a LIVE admit: when the lane's best proven variant enters on a PULLBACK or a
     * CONFIRM, the live entry waits for that condition (the first admit's mark is the signal). Null = buy now.
     */
    fun liveEntryWait8347(ts: TokenState, lane: String, sinceMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
        // V5.0.8351 — the adopted choice (a variant or a niche) sets the entry; outside a niche nicheRefusal8351 already refused.
        val entry = bestFor8351(lane, sinceMs)?.entry ?: return null
        if (entry == Entry8347.NOW) return null
        val px = ts.lastPrice.takeIf { it.isFinite() && it > 0.0 } ?: return "ENTRY_TIMING_NO_MARK_8347"
        val key = "${ts.mint}|${canon(lane)}"
        waits[key]?.let { old ->
            if (old[0] < maxOf(sinceMs, LearningEpoch8164.epochMs8164()) || old.size < 4 || old[3] != ShadowPersist8355.TRADE_CONTRACT_8394.toLong()) {
                waits.remove(key, old); waitsDirty8355 = true
            }
        }
        val depth8394 = pullbackPct8347(FieldManual7715.mandateFor(lane).invalidationPct)
        val w = waits[key] ?: longArrayOf(nowMs, java.lang.Double.doubleToRawLongBits(px), java.lang.Double.doubleToRawLongBits(depth8394), ShadowPersist8355.TRADE_CONTRACT_8394.toLong()).also { waits[key] = it; waitsDirty8355 = true }   // V5.0.8355 — a new wait is a change
        val signal = java.lang.Double.longBitsToDouble(w[1])
        val age = nowMs - w[0]
        val stop = try { FieldManual7715.mandateFor(lane).invalidationPct } catch (_: Throwable) { 10.0 }   // V5.0.8392 — the pullback depth is an ENTRY read: mandate, not the cost-widened exit stop
        val ready = entryReady8394(entry, px, signal, age, java.lang.Double.longBitsToDouble(w[2]))
        val limit = if (entry == Entry8347.PULLBACK) PULLBACK_WAIT_MS_8347 else CONFIRM_WAIT_MS_8347
        if (ready) { waits.remove(key); waitsDirty8355 = true; return null }
        if (age >= limit) { waits.remove(key); waitsDirty8355 = true; return "ENTRY_TIMING_EXPIRED_8347_$entry" }
        if (waits.size > 2_000) { waits.entries.removeIf { nowMs - it.value[0] > PULLBACK_WAIT_MS_8347 }; waitsDirty8355 = true }
        try { PipelineHealthCollector.labelInc("ENTRY_TIMING_WAIT_8347_${canon(lane)}_$entry") } catch (_: Throwable) {}
        return "ENTRY_TIMING_WAIT_8347_$entry"
    }

    /** Pure: "KEY=n,..." sorted by count (desc) then key, "-" when empty. */
    fun countLine8360(m: Map<String, Long>): String =
        m.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }

    /** V5.0.8360 — the lane's best [dim] cohort (highest mean, n >= MIN_COHORT_SHOW_N_8360) as "S:x@NOW/RUNN n12 +3.4%", or null. */
    private fun bestCohort8360(lane: String, dim: String, sinceMs: Long): String? {
        val prefix = "C|$lane|"
        var best: String? = null
        var bestMean = Double.NEGATIVE_INFINITY
        for (k in books.keys) {
            if (!k.startsWith(prefix)) continue
            val rest = k.removePrefix(prefix)                 // ENTRY|EXIT|DIM:VALUE
            val parts = rest.split('|')
            if (parts.size < 3 || parts[2].substringBefore(':') != dim) continue
            val nets = netsSince(k, sinceMs)
            if (nets.size < MIN_COHORT_SHOW_N_8360) continue
            val mean = nets.average()
            if (mean.isFinite() && mean > bestMean) {
                bestMean = mean
                best = "${parts[2]}@${parts[0].take(4)}/${parts[1].take(4)} n${nets.size} ${"%+.1f".format(mean)}%"
            }
        }
        return best
    }

    private data class Report8401(val sinceMs: Long, val epoch: Long, val atMs: Long, val text: String)
    @Volatile private var report8401: Report8401? = null
    private val reportBusy8401 = java.util.concurrent.atomic.AtomicBoolean(false)
    private val reportExecutor8401 = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "shadow-report-8401").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    fun statusLine8347(sinceMs: Long): String {
        val now = System.currentTimeMillis()
        val epoch = LearningEpoch8164.epochMs8164()
        val hit = report8401?.takeIf { it.sinceMs == sinceMs && it.epoch == epoch }
        if ((hit == null || now - hit.atMs >= 30_000L) && reportBusy8401.compareAndSet(false, true)) {
            try { reportExecutor8401.execute {
                try {
                    // The report retains only aggregate rows; no unbounded map of every cohort's outcomes.
                    val text = statusLineUnmemoised8398(sinceMs)
                    report8401 = Report8401(sinceMs, epoch, System.currentTimeMillis(), text)
                    progress8398 = "complete"
                } catch (_: Exception) { progress8398 = "refresh failed; prior snapshot retained" }
                finally { reportBusy8401.set(false) }
            } } catch (_: Exception) { reportBusy8401.set(false) }
        }
        return hit?.let { "snapshotAgeMs=${now - it.atMs} ${it.text}" }
            ?: "exploreShadow8347[summary pending; tickets=${tickets.size} opened=${opened.get()} closed=${closed.get()}]"
    }

    @Volatile private var progress8398: String = "idle"
    private fun mark8398(lane: String, step: String) { progress8398 = "lane=$lane step=$step" }
    /** V5.0.8398b — where the status walk is right now, plus the cohort book's read/lock/append accounting (watchdog partial). */
    fun progress8398(): String = "exploreShadowStatus[$progress8398] books[${books.stats8398()}]"

    private fun statusLineUnmemoised8398(sinceMs: Long): String {
        // V5.0.8360 — every lane with any book (native variant or a cohort), so a lane trading only rejects / setups shows too
        val laneSet = books.keys.map { it.removePrefix(UNFILLED_PREFIX_8364) }   // V5.0.8364 — U|<key> fill-rate rows name their key's lane
            .map { if (it.startsWith("C|")) it.removePrefix("C|").substringBefore('|') else it.substringBefore('|') }.toSortedSet()
        val lanes = laneSet.joinToString(" · ") { l ->
            mark8398(l, "cells")
            val cells = Entry8347.values().flatMap { e -> Exit8347.values().map { x -> Triple(e, x, variantRecord8347(l, e, x, sinceMs)) } }
                .filter { it.third.n > 0 }
                .joinToString(",") { (e, x, p) -> "${e.name.take(4)}/${x.name.take(4)} n${p.n} ${"%+.1f".format(p.meanPct)}%${if (p.proven) "*" else ""}" +
                    (if (e != Entry8347.NOW) " fill${"%.0f".format(variantFillRate8364(l, e, x, sinceMs) * 100.0)}%" else "") }   // V5.0.8364
            mark8398(l, "best")
            val best = bestFor8351(l, sinceMs)?.let { c -> c.label + " fill${"%.0f".format(c.fillRate * 100.0)}% perSignal${"%+.1f".format(perSignal8364(c))}" } ?: "-"
            mark8398(l, "inverted")
            val inv = if (inverted8351(l, sinceMs)) " FILTER_INVERTED" else ""
            mark8398(l, "cohorts")
            val cohorts = listOf("S", "T", "M", "CX", "CXS", "CXT").mapNotNull { bestCohort8360(l, it, sinceMs) }.joinToString(" ; ").ifBlank { "-" }
            mark8398(l, "cortex")
            // Use this specialist's live exit doctrine for one outcome per decision.
            // A second modelled exit style is a counterfactual, NOT an independent trade.
            val cortexLiveExit8368 = try {
                if (com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(l)) Exit8347.RUNNER else Exit8347.SCALP
            } catch (_: Throwable) { Exit8347.SCALP }
            val cortex8368 = listOf("YES", "NO", "HOLD").mapNotNull { side ->
                val stat = cortexStats8368(netsSince("C|$l|NOW|$cortexLiveExit8368|CX:$side", sinceMs))
                if (stat.n == 0) null else
                    "$side:n${stat.n} wins${stat.wins}/losses${stat.losses} mean${"%+.1f".format(stat.meanPct)}% pf${"%.2f".format(stat.profitFactor)}"
            }.joinToString(",").ifBlank { "-" }
            "$l cortex{$cortex8368} adopt=$best$inv [$cells] best{$cohorts} chase8364=${"%.1f".format(ShadowFill8364.laneChasePct8364(l))}%"
        }.ifBlank { "no exploration closes yet" }
        mark8398("-", "counts")
        val (byKind, skipped, bySrc) = counts8360()
        return "exploreShadow8347[tickets=${tickets.size}/$MAX_TICKETS_8347 opened=${opened.get()} ${choiceStats8399()} " +
            "byKind{native=${byKind[KIND_NATIVE_8360] ?: 0},reject=${byKind[KIND_REJECT_8360] ?: 0},setup=${byKind[KIND_SETUP_8360] ?: 0}} " +
            "cortex8368[seen=${cortexSeen8368.get()} yes=${cortexSeenByKind8368[KIND_CORTEX_YES_8368]?.get() ?: 0} no=${cortexSeenByKind8368[KIND_CORTEX_NO_8368]?.get() ?: 0} hold=${cortexSeenByKind8368[KIND_CORTEX_HOLD_8368]?.get() ?: 0} " +
            "opened=${listOf(KIND_CORTEX_YES_8368, KIND_CORTEX_NO_8368, KIND_CORTEX_HOLD_8368).sumOf { byKind[it] ?: 0 }} " +
            "legsClosed=${listOf(KIND_CORTEX_YES_8368, KIND_CORTEX_NO_8368, KIND_CORTEX_HOLD_8368).sumOf { cortexClosesByKind8368[it]?.get() ?: 0 }}] " +
            "learning8372[outcomesAck=${cortexOutcomeAck8372.get()} unavailableAck=${cortexUnavailable8372.get()} " +
            "retry=${cortexRetry8372.get()} pending=${tickets.values.count { it.cortex8372.pending8372() }}] ${persistStatus8377()} " +
            "skipped{${countLine8360(skipped)}} evicted=${evicted8360.get()} ticked/loop=$lastTicked8360/$TICK_PER_LOOP_8360 " +
            "markSrc{${countLine8360(bySrc)}} wanted8360=${wantedCalls8360.get()} legsClosed=${closed.get()} legsUnfilled8364=${unfilledN8364.get()} full=${full.get()} " +
            "restored8355=${restored8355.get()} expiredOnRestore8355=${expiredOnRestore8355.get()} waitsRestored8355=${waitsRestored8355.get()} " +
            "rejectSample=$REJECT_SAMPLE_PCT_8347% perLane<=$MAX_TICKETS_PER_LANE_8351 bar=n>=$MIN_N_8347,mean-1.5se>$MARGIN_PCT_8347%,pf>=$MIN_PF_8347 (* = proven) adopt8364=fill x (mean-2se), M:+${M_MARGIN_PT_8364}pt over other style, chase=${ShadowFill8364.MODEL_CHASE_PCT_8364}% model | $lanes]"
    }
}
