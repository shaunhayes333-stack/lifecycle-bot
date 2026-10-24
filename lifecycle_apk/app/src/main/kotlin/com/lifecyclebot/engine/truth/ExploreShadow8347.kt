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
        // V5.0.8366 — the lifecycle hold a +100 % runner leg is promoted into (persisted), and a triggered full exit waiting
        // out the live sell latency (not persisted: after a restart the rule that triggered it triggers again)
        var hold8366 = false
        var taken8366 = 0
        var peakAt8366 = 0L
        var exitAt8366 = 0L
        var exitWhy8366: String? = null
    }

    private class Ticket8347(
        val mint: String, val symbol: String, val lane: String, val setup: String, val tactic: String, val scoreBand: String,
        val signalPx: Double, val atMs: Long, val costPct: Double, val stopPct: Double, val armPct: Double,
        val runner: Boolean, val nativeEligible: Boolean, val legs: List<Leg8347>,
        val band: String = "UNK",   // V5.0.8351 — market-cap band at open, a cohort dimension
        val kind8360: String = KIND_NATIVE_8360,   // V5.0.8360 — NATIVE / REJECT / SETUP
        val markSrc8360: String = "",              // V5.0.8360 — the observed source that priced the entry
    ) {
        var lastPx = signalPx
        var lastPxAtMs = atMs
        // V5.0.8355 — persistence state: a leg's tier / peak / trough moved (coalesced write); a leg armed or closed (forced write)
        var dirty8355 = false
        var force8355 = false
        var lastWriteMs8355 = 0L
    }

    private val tickets = ConcurrentHashMap<String, Ticket8347>()                // mint|lane|setup -> ticket (V5.0.8360; pre-8360: mint|lane)
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
        t.legs.map { l -> ShadowPersist8355.LegRec8355(l.entry.name, l.exit.name, l.active, l.dead, l.entryPx, l.entryAtMs, l.held, l.banked, l.tiers, l.peak, l.trough,
            hold8366 = l.hold8366, taken8366 = l.taken8366, peakAt8366 = l.peakAt8366) },   // V5.0.8366
        kind8360 = t.kind8360, markSrc8360 = t.markSrc8360,   // V5.0.8360
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
            leg.hold8366 = l.hold8366; leg.taken8366 = l.taken8366; leg.peakAt8366 = l.peakAt8366   // V5.0.8366
            legs += leg
        }
        if (legs.isEmpty() || legs.all { it.dead }) return null
        val kind8360 = r.kind8360.ifBlank { if (r.nativeEligible) KIND_NATIVE_8360 else KIND_REJECT_8360 }   // V5.0.8360 — pre-8360 records
        val t = Ticket8347(r.mint, r.symbol, r.lane, r.setup, r.tactic, r.scoreBand, r.signalPx, r.atMs, r.costPct, r.stopPct, r.armPct,
            r.runner, r.nativeEligible, legs, r.band, kind8360 = kind8360, markSrc8360 = r.markSrc8360)
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
                // V5.0.8366 — a leg in the lifecycle hold has no clock (LifecycleHold8065): its ticket keeps the hold's window
                val window = if (r.legs.any { it.hold8366 && !it.dead }) ShadowAlign8366.holdWindowMs8366()
                    else (if (r.runner) RUNNER_TIMEOUT_MS_8347 else OTHER_TIMEOUT_MS_8347) + PULLBACK_WAIT_MS_8347
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
        recountLanes8360()   // V5.0.8360
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

    // ── V5.0.8360 §EVERY_LANE_EVERY_TOKEN_EVERY_SETUP — kinds, skips, capacity ──

    const val KIND_NATIVE_8360 = "NATIVE"
    const val KIND_REJECT_8360 = "REJECT"
    const val KIND_SETUP_8360 = "SETUP"
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
    fun opens8360(s: String): Boolean = s == KIND_NATIVE_8360 || s == KIND_REJECT_8360 || s == KIND_SETUP_8360

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
        if (kind == KIND_REJECT_8360) listOf(Entry8347.NOW to Exit8347.RUNNER, Entry8347.NOW to Exit8347.SCALP)
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

    private fun recountLanes8360() {
        val m = HashMap<String, Int>()
        for (t in tickets.values) m[t.lane] = (m[t.lane] ?: 0) + 1
        for (l in laneCounts8360.keys) if (!m.containsKey(l)) laneCounts8360[l]?.set(0)
        for ((l, n) in m) laneCounts8360.computeIfAbsent(l) { java.util.concurrent.atomic.AtomicInteger(0) }.set(n)
    }

    /** When full: evict the oldest ticket (in [lane], or anywhere when null) with no active leg; false = none evictable. */
    private fun evict8360(lane: String?, nowMs: Long): Boolean {
        val scope = lane ?: "*"
        val until = noEvictUntil8360[scope]
        if (until != null && nowMs < until) return false
        val cands = ArrayList<Triple<String, Long, Boolean>>()
        for ((k, t) in tickets) {
            if (lane != null && t.lane != lane) continue
            cands += Triple(k, t.atMs, t.legs.any { it.active && !it.dead })
        }
        val k = evictKey8360(cands)
        if (k == null) { noEvictUntil8360[scope] = nowMs + NO_EVICT_RETRY_MS_8360; return false }
        val t = tickets.remove(k) ?: return false
        forgetTicket8355(k)
        laneCounts8360[t.lane]?.decrementAndGet()
        evicted8360.incrementAndGet()
        return true
    }

    private fun wanted8360(mint: String, untilMs: Long) {
        // V5.0.8360 — the keyless mark feed (8359) prices shadow-wanted mints right after held positions; near-decision keeps the Helius focus.
        try { KeylessMarks8359.noteWanted8359(mint, untilMs) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.DataPipeline8191.noteNearDecision8191(mint, System.currentTimeMillis()) } catch (_: Throwable) {}
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

    private fun open8347(
        ts: TokenState, lane: String, o: com.lifecyclebot.engine.SpecialistBrainBridge7542.Opinion, px: Double, nowMs: Long,
        kind: String, setup: String, markSrc: String,
    ) {
        val key = ticketKey8360(ts.mint, lane, setup)
        if (tickets.containsKey(key)) { skip8360(SKIP_ALREADY_OPEN_8360); return }
        val lastAt = lastOpenAt[key]
        if (lastAt != null && nowMs - lastAt in 0L until REOPEN_GAP_MS_8347) { skip8360(SKIP_REOPEN_GAP_8360); return }
        if (tickets.size >= MAX_TICKETS_8347 && !evict8360(null, nowMs)) { full.incrementAndGet(); skip8360(SKIP_FULL_8360); return }
        if (laneCount8360(lane) >= MAX_TICKETS_PER_LANE_8351 && !evict8360(lane, nowMs)) { full.incrementAndGet(); skip8360(SKIP_LANE_FULL_8360); return }
        val liq = try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }
        // V5.0.8366 — the lane's measured round trip (CostLedger7962, n >= 8) once measured, the NetEdge8030 model before (ShadowAlign8366)
        val cost = try { ShadowAlign8366.laneCostPct8366(lane, liq, ts.mint, ts.lastMcap) } catch (_: Throwable) { Double.NaN }
        val stop = try { FieldManual7715.mandateFor(lane).invalidationPct } catch (_: Throwable) { 10.0 }
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        val arm = try { com.lifecyclebot.engine.RunnerExitProfile7277.giveBackArmPct7791(lane) } catch (_: Throwable) { 20.0 }
        val tactic = try { com.lifecyclebot.engine.learning.TacticSwitcher.tacticForToken8050(ts, lane, o.score).name } catch (_: Throwable) { "MOMENTUM" }
        val band = try { com.lifecyclebot.engine.LosingPatternMemory.scoreBand(o.score) } catch (_: Throwable) { "" }
        val legs = legsFor8360(kind).map { (e, x) -> Leg8347(e, x) }
        val mcapBand8351 = try { ForwardReturnLabeler7731.mcapBand(TrustedMcap8019.mcap8019(ts)) } catch (_: Throwable) { "UNK" }
        val t = Ticket8347(ts.mint, ts.symbol, lane, setup, tactic, band, px, nowMs,
            if (cost.isFinite()) cost else 7.0, stop.coerceIn(3.0, 40.0), arm, runner, o.eligible, legs, mcapBand8351,
            kind8360 = kind, markSrc8360 = markSrc)
        // V5.0.8364 — the fill is booked at the signal mark plus the lane's decision-to-fill chase (ShadowFill8364), as live pays it
        val fillPx8364 = ShadowFill8364.laneEntryPx8364(px, lane)
        t.legs.filter { it.entry == Entry8347.NOW }.forEach { it.active = true; it.entryPx = fillPx8364; it.entryAtMs = nowMs }
        t.legs.filter { it.entry == Entry8347.NOW && it.active }.forEach { landed8366(t, it, nowMs) }   // V5.0.8366 — the buy lands at the route's measured rate
        tickets[key] = t
        laneCounts8360.computeIfAbsent(lane) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        persistTicket8355(key, t, nowMs)   // V5.0.8355 — written on open
        lastOpenAt[key] = nowMs
        if (lastOpenAt.size > 30_000) lastOpenAt.entries.removeIf { nowMs - it.value > REOPEN_GAP_MS_8347 }
        opened.incrementAndGet()
        openedByKind8360.computeIfAbsent(kind) { AtomicLong(0) }.incrementAndGet()
        openedBySrc8360.computeIfAbsent(markSrc.ifBlank { "?" }) { AtomicLong(0) }.incrementAndGet()
        want8360(ts.mint, nowMs)   // V5.0.8360 — the data side keeps pricing what the shadow holds
        try { PipelineHealthCollector.labelInc("EXPLORE_SHADOW_OPEN_8347_${lane}_$kind") } catch (_: Throwable) {}
    }

    /** V5.0.8360 — opened by kind / skipped by reason / opened by mark source (tests and the status line). */
    private fun counts8360(): Triple<Map<String, Long>, Map<String, Long>, Map<String, Long>> = Triple(
        openedByKind8360.mapValues { it.value.get() }, skips8360.mapValues { it.value.get() }, openedBySrc8360.mapValues { it.value.get() },
    )

    // ── tick (BotService shadow loop) ──

    fun tick8347(tokens: Map<String, TokenState>, nowMs: Long = System.currentTimeMillis()) {
        flushWaits8355(nowMs)   // V5.0.8355 — the waits map, when it changed (independent of the tickets)
        if (tickets.isEmpty()) { lastTicked8360 = 0; return }
        val done = ArrayList<String>()
        // V5.0.8360 — at most TICK_PER_LOOP_8360 tickets per loop, round-robin; one observed-mark read per mint per loop
        val keys = ArrayList<String>(tickets.keys)
        val slice = rrSlice8360(keys.size, rrCursor8360)
        rrCursor8360 = slice.second
        lastTicked8360 = slice.first.size
        val marks = HashMap<String, Double?>()
        for (i in slice.first) {
            val key = keys[i]
            val t = tickets[key] ?: continue
            try {
                if (!marks.containsKey(t.mint)) marks[t.mint] = ShadowMark8360.observed8360(tokens[t.mint], t.mint, nowMs)?.first
                val fresh: Double? = marks[t.mint]
                if (fresh != null) { t.lastPx = fresh; t.lastPxAtMs = nowMs }
                want8360(t.mint, nowMs)   // V5.0.8360 — keep the data side pricing an open ticket
                val lost = nowMs - t.lastPxAtMs >= LOST_MARK_MS_8347
                for (leg in t.legs) {
                    if (leg.dead) continue
                    if (!leg.active) { arm8347(t, leg, fresh, nowMs); continue }
                    step8347(t, leg, fresh, lost, nowMs, if (leg.exit == Exit8347.SCALP) scalpIn8366(key, t, tokens[t.mint]) else null)
                }
                if (t.legs.all { it.dead }) {
                    done += key
                } else if (ShadowPersist8355.writeDue8355(t.dirty8355, t.lastWriteMs8355, nowMs, t.force8355)) {
                    persistTicket8355(key, t, nowMs)   // V5.0.8355 — a leg armed / closed writes now; a tier / peak move waits for the 10 s coalesce; nothing else writes
                }
            } catch (_: Throwable) { done += key }
        }
        done.forEach { tickets.remove(it); forgetTicket8355(it) }   // V5.0.8355 — every leg dead: the ticket leaves the open prefs
        done.forEach { scalpInMap8366.remove(it) }   // V5.0.8366 — its scalp inputs go with it
        recountLanes8360()   // V5.0.8360 — the per-lane counts the open path reads, exact once per loop
    }

    private fun arm8347(t: Ticket8347, leg: Leg8347, px: Double?, nowMs: Long) {
        val age = nowMs - t.atMs
        when (leg.entry) {
            // V5.0.8364 — a fill is booked at the trigger mark plus the lane's chase (ShadowFill8364); an expired leg is a
            // "no trade" signal (net 0, never a loss) counted in the arm's fill rate (unfilled8364), so arms compare per signal.
            Entry8347.PULLBACK -> {
                if (px != null && px <= t.signalPx * (1.0 - pullbackPct8347(t.stopPct) / 100.0)) { leg.active = true; leg.entryPx = ShadowFill8364.laneEntryPx8364(px, t.lane); leg.entryAtMs = nowMs; t.force8355 = true; landed8366(t, leg, nowMs) }
                else if (age >= PULLBACK_WAIT_MS_8347) { leg.dead = true; t.force8355 = true; unfilled8364(t, leg, nowMs) }   // never filled: no trade, counted unfilled
            }
            Entry8347.CONFIRM -> {
                if (px != null && age >= CONFIRM_AFTER_MS_8347 && px >= t.signalPx * (1.0 + CONFIRM_RISE_PCT_8347 / 100.0)) { leg.active = true; leg.entryPx = ShadowFill8364.laneEntryPx8364(px, t.lane); leg.entryAtMs = nowMs; t.force8355 = true; landed8366(t, leg, nowMs) }
                else if (age >= CONFIRM_WAIT_MS_8347) { leg.dead = true; t.force8355 = true; unfilled8364(t, leg, nowMs) }
            }
            Entry8347.NOW -> { leg.active = true; leg.entryPx = ShadowFill8364.laneEntryPx8364(t.signalPx, t.lane); leg.entryAtMs = t.atMs; t.force8355 = true; landed8366(t, leg, nowMs) }
        }
    }

    private fun step8347(t: Ticket8347, leg: Leg8347, px: Double?, lost: Boolean, nowMs: Long, scalp8366: DoubleArray? = null) {
        val g = (t.lastPx / leg.entryPx - 1.0) * 100.0
        if (!g.isFinite()) { leg.dead = true; t.force8355 = true; return }
        if (g > leg.peak) leg.peakAt8366 = nowMs   // V5.0.8366 — when the peak was set (the hold's dead-money clock)
        if (g > leg.peak) { leg.peak = g; t.dirty8355 = true }       // V5.0.8355 — peak / trough / tier moves are persisted changes
        if (g < leg.trough) { leg.trough = g; t.dirty8355 = true }
        // V5.0.8366 — a triggered full exit fills at the first observed mark once the live sell latency (PaperExitLatency8223)
        // has passed since its trigger, not at the trigger tick; a mark lost meanwhile closes at the last mark.
        val pending8366 = leg.exitWhy8366
        if (pending8366 != null) {
            if (lost || (px != null && ShadowAlign8366.latencyElapsed8366(leg.exitAt8366, ShadowAlign8366.sellLatencyMs8366(false), nowMs))) closeAt8366(t, leg, g, pending8366, nowMs)
            return
        }
        val why: String? = when (leg.exit) {
            Exit8347.RUNNER -> runnerWhy8366(t, leg, g, px, lost, nowMs)
            Exit8347.SCALP -> scalpWhy8366(t, leg, g, px, lost, nowMs, scalp8366)
        }
        if (why == null) return
        // nothing left to sell, or no observed mark to wait for: booked now at the last mark
        if (why == "SOLD_OUT" || why == "LOST_MARK" || px == null) { closeAt8366(t, leg, g, why, nowMs); return }
        leg.exitWhy8366 = why; leg.exitAt8366 = nowMs
    }

    /** V5.0.8366 — the leg's close at gross [g] (banked slices + the remainder, less the round trip). */
    private fun closeAt8366(t: Ticket8347, leg: Leg8347, g: Double, why: String, nowMs: Long) =
        close8347(t, leg, EvidenceShadow8344.netPct8344(leg.banked, leg.held, ShadowFill8364.exitGrossPct8364(g), t.costPct), why, nowMs)   // V5.0.8364 — exit slippage not in costPct8030 (none today)

    /**
     * V5.0.8366 — the RUNNER leg on live's levers: SpikeCapture7943 tiers, the lane stop, the 8338 give-back floor, the level
     * live's fluid stop holds (ShadowAlign8366.liveStopLevel8366: net break-even once cleared, the green floor on a runner
     * lane), the clock; and once at +100 % (LifecycleHold8065's promotion) the lifecycle hold's own exits, no clock.
     */
    private fun runnerWhy8366(t: Ticket8347, leg: Leg8347, g: Double, px: Double?, lost: Boolean, nowMs: Long): String? {
        if (!leg.hold8366 && px != null && ShadowAlign8366.holdPromotes8366(g, leg.peak)) { leg.hold8366 = true; t.force8355 = true }
        if (leg.hold8366) {
            val d = if (px != null) ShadowAlign8366.holdExit8366(g, leg.peak, leg.taken8366, nowMs - leg.entryAtMs, nowMs - leg.peakAt8366, t.costPct) else null
            if (d != null) {
                val n = ShadowAlign8366.takeOf8366(d.first)
                if (n < 0) return d.first
                val sold = leg.held * d.second; leg.banked += sold * ShadowFill8364.exitGrossPct8364(g); leg.held -= sold; leg.taken8366 = n; t.dirty8355 = true
            }
            return when {
                leg.held <= 0.0001 -> "SOLD_OUT"
                lost -> "LOST_MARK"
                else -> null
            }
        }
        if (px != null) while (leg.tiers < com.lifecyclebot.engine.SpikeCapture7943.TIERS.size) {
            val (at, frac) = com.lifecyclebot.engine.SpikeCapture7943.TIERS[leg.tiers]
            if (g < at) break
            val sold = leg.held * frac; leg.banked += sold * ShadowFill8364.exitGrossPct8364(g); leg.held -= sold; leg.tiers++; t.dirty8355 = true
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
            while (guard++ < 3 && leg.held > 0.0001) {
                val d = com.lifecyclebot.engine.ScalpStop8363.decide8363(g, maxOf(leg.peak, g), nowMs - leg.entryAtMs, leg.tiers, t.stopPct, t.costPct, noise, chase) ?: break
                if (d.second.endsWith("_T1")) { val s = leg.held * d.first; leg.banked += s * ShadowFill8364.exitGrossPct8364(g); leg.held -= s; leg.tiers = 1; t.dirty8355 = true }
                else if (d.second.endsWith("_T2")) { leg.banked += leg.held * ShadowFill8364.exitGrossPct8364(g); leg.held = 0.0; leg.tiers = 2; t.dirty8355 = true }
                else return d.second
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
        close8347(t, leg, -fee, ShadowAlign8366.UNLANDED_WHY_8366, nowMs)
        return false
    }

    private fun close8347(t: Ticket8347, leg: Leg8347, net: Double, why: String, nowMs: Long) {
        leg.dead = true
        t.force8355 = true   // V5.0.8355 — a leg close is written now (the ticket itself leaves the prefs once every leg is dead)
        val v = "${t.lane}|${leg.entry}|${leg.exit}"
        // V5.0.8351 — the variant book, plus three cohort books (setup, market-cap band, native yes / sampled no).
        // V5.0.8360 — by kind: a NATIVE ticket feeds the variant book (live adoption) and S: / M: / R:NATIVE, plus T:<tactic> on
        // NOW/RUNNER; a REJECT ticket only R:REJECT (the FILTER_INVERTED comparison; now every reject, so it stays out of the
        // live proof); a SETUP ticket only its own S:<setup> (SR:<setup> when the lane refused) — it shares the coin's price
        // path with the native ticket, so it never re-enters the variant book as a second sample.
        when (t.kind8360) {
            KIND_SETUP_8360 -> bookKey8351("C|$v|${if (t.nativeEligible) "S" else "SR"}:${t.setup}", net, nowMs)
            KIND_REJECT_8360 -> bookKey8351("C|$v|R:REJECT", net, nowMs)
            else -> {
                bookKey8351(v, net, nowMs)
                bookKey8351("C|$v|S:${t.setup}", net, nowMs)
                bookKey8351("C|$v|M:${t.band}", net, nowMs)
                bookKey8351("C|$v|R:${if (t.nativeEligible) "NATIVE" else "REJECT"}", net, nowMs)
                if (leg.entry == Entry8347.NOW && leg.exit == Exit8347.RUNNER) bookKey8351("C|$v|T:${t.tactic}", net, nowMs)
            }
        }
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
    data class Choice8351(
        val entry: Entry8347, val exit: Exit8347, val dim: String?, val value: String?, val proof: EvidenceHold8336.Proof8336,
        val fillRate: Double = 1.0,   // V5.0.8364 — filled / (filled + expired-unfilled) signals; 1.0 for NOW
    ) {
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
            for (k in books.keys) {
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
            KIND_SETUP_8360 -> listOf("C|$v|${if (t.nativeEligible) "S" else "SR"}:${t.setup}")
            KIND_REJECT_8360 -> emptyList()
            else -> listOf(v, "C|$v|S:${t.setup}", "C|$v|M:${t.band}")
        }
    }

    /** An expired PULLBACK / CONFIRM leg: a "no trade" signal (net 0) under U|<key>, read only by the fill rate. */
    private fun unfilled8364(t: Ticket8347, leg: Leg8347, nowMs: Long) {
        try {
            for (k in unfilledKeys8364(t, leg)) bookKey8351(UNFILLED_PREFIX_8364 + k, 0.0, nowMs)
            unfilledN8364.incrementAndGet()
        } catch (_: Throwable) {}
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
        "S" -> setup != null && Entry8114Gate8326.namedSetup8364(setup) && setup.trim().equals(c.value, ignoreCase = true)
        "M" -> band != null && band.equals(c.value, ignoreCase = true) && Entry8114Gate8326.namedSetup8364(setup)
        else -> true
    }

    /** Pure (V5.0.8364): [c]'s niche against the coin's own [fired] setups — an M: band needs one named fired setup. */
    fun inNiche8364(c: Choice8351, setup: String?, band: String?, fired: List<String>): Boolean = when (c.dim) {
        null -> true
        "S" -> inNiche8351(c, setup, band) || fired.any { inNiche8351(c, it, band) }
        "M" -> {
            val named = Entry8114Gate8326.firedSetup8364(setup, fired)
            named != null && inNiche8351(c, named, band)
        }
        else -> false
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

    fun statusLine8347(sinceMs: Long): String {
        // V5.0.8360 — every lane with any book (native variant or a cohort), so a lane trading only rejects / setups shows too
        val laneSet = books.keys.map { it.removePrefix(UNFILLED_PREFIX_8364) }   // V5.0.8364 — U|<key> fill-rate rows name their key's lane
            .map { if (it.startsWith("C|")) it.removePrefix("C|").substringBefore('|') else it.substringBefore('|') }.toSortedSet()
        val lanes = laneSet.joinToString(" · ") { l ->
            val cells = Entry8347.values().flatMap { e -> Exit8347.values().map { x -> Triple(e, x, variantRecord8347(l, e, x, sinceMs)) } }
                .filter { it.third.n > 0 }
                .joinToString(",") { (e, x, p) -> "${e.name.take(4)}/${x.name.take(4)} n${p.n} ${"%+.1f".format(p.meanPct)}%${if (p.proven) "*" else ""}" +
                    (if (e != Entry8347.NOW) " fill${"%.0f".format(variantFillRate8364(l, e, x, sinceMs) * 100.0)}%" else "") }   // V5.0.8364
            val best = bestFor8351(l, sinceMs)?.let { c -> c.label + " fill${"%.0f".format(c.fillRate * 100.0)}% perSignal${"%+.1f".format(perSignal8364(c))}" } ?: "-"
            val inv = if (inverted8351(l, sinceMs)) " FILTER_INVERTED" else ""
            val cohorts = listOf("S", "T", "M").mapNotNull { bestCohort8360(l, it, sinceMs) }.joinToString(" ; ").ifBlank { "-" }
            "$l adopt=$best$inv [$cells] best{$cohorts} chase8364=${"%.1f".format(ShadowFill8364.laneChasePct8364(l))}%"
        }.ifBlank { "no exploration closes yet" }
        val (byKind, skipped, bySrc) = counts8360()
        return "exploreShadow8347[tickets=${tickets.size}/$MAX_TICKETS_8347 opened=${opened.get()} " +
            "byKind{native=${byKind[KIND_NATIVE_8360] ?: 0},reject=${byKind[KIND_REJECT_8360] ?: 0},setup=${byKind[KIND_SETUP_8360] ?: 0}} " +
            "skipped{${countLine8360(skipped)}} evicted=${evicted8360.get()} ticked/loop=$lastTicked8360/$TICK_PER_LOOP_8360 " +
            "markSrc{${countLine8360(bySrc)}} wanted8360=${wantedCalls8360.get()} legsClosed=${closed.get()} legsUnfilled8364=${unfilledN8364.get()} full=${full.get()} " +
            "restored8355=${restored8355.get()} expiredOnRestore8355=${expiredOnRestore8355.get()} waitsRestored8355=${waitsRestored8355.get()} " +
            "rejectSample=$REJECT_SAMPLE_PCT_8347% perLane<=$MAX_TICKETS_PER_LANE_8351 bar=n>=$MIN_N_8347,mean-1.5se>$MARGIN_PCT_8347%,pf>=$MIN_PF_8347 (* = proven) adopt8364=fill x (mean-2se), M:+${M_MARGIN_PT_8364}pt over other style, chase=${ShadowFill8364.MODEL_CHASE_PCT_8364}% model | $lanes]"
    }
}
