package com.lifecyclebot.engine.truth

import android.content.Context
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8344 §SHADOW_BEHIND_THE_LIVE_TRADER — owner: "its meant to run in shadow mode behind the live trader to bank the
 * knowledge it needs to unlock."
 *
 * 5.0.8341 (13:37): 2.5 h in LIVE, EvidenceHold8336 refused ~22,000 meme entries and the lanes could never unlock —
 * the hold read only PAPER closes, and a LIVE runtime opens no meme paper. The executor's generic shadow book
 * (Executor.checkShadowPositions) is not a stand-in for the live trader: no lane, one fixed stop / take / timeout, no cost,
 * 60 slots for everything.
 *
 * This book trades every candidate the LIVE decision ADMITTED but the evidence hold (or the lane's live-loss shut)
 * refused, in that lane, as the live trader would:
 *   - entry at the decision mark, charged the round trip NetEdge8030.costPct8030 prices for a route-minimum ticket
 *     (curve-aware since 8338);
 *   - exits on the live doctrine's levers: the lane mandate's invalidation stop (FieldManual7715.mandateFor), the
 *     SpikeCapture7943 tiers (+40 sells 60 %, +120 / +400 sell 70 % of what is left), the give-back floor once the peak
 *     clears the lane's arm (RunnerExitProfile7277.giveBackArmPct7791; the live 8338 floor: max(break-even, 40 % of peak)), and a
 *     timeout at the last mark (runner lanes 120 min, others 30 min);
 *   - one position per lane and mint, [MAX_OPEN_8344] open at most; a mark missing for [LOST_MARK_MS_8344] closes at the last mark.
 * Every close is booked per lane (persisted, since the hold's start) and EvidenceHold8336 unlocks a lane on this record
 * with the same bar it applies to paper (n >= 30, mean - SE > 2 %, PF >= 1.3). Spends nothing, opens no position, takes
 * no slot; paper and the live ledger never see it.
 * Known gaps (it is a model, not a fill): no landing failures, no sell latency, fills at the mark plus the modelled cost.
 * (superseded by V5.0.8366 — entries land at the route's measured rate, exits fill after the live sell latency, the cost is
 * the lane's measured round trip once measured, and the exits add live's fluid-stop level and the lifecycle hold: ShadowAlign8366.)
 * V5.0.8364 — the entry is booked at the mark plus the lane's decision-to-fill chase (ShadowFill8364: EntryChase7961's live
 * median once measured, the model chase before); the sell-side slippage is already inside the NetEdge8030 round trip.
 */
object EvidenceShadow8344 {
    const val MAX_OPEN_8344 = 120
    const val TICKET_SOL_8344 = 0.03
    private const val LOST_MARK_MS_8344 = 10 * 60_000L
    // V5.0.8392 — the shadow runs the hold the Cortex grades (CortexLabelHygiene8144 executable horizon: 6 h runner, 60 min
    // other). 5.0.8390: TREASURY coins were labelled +229 % over the 60-min hold while the shadow clocked out at 30 min.
    private val RUNNER_TIMEOUT_MS_8344 = com.lifecyclebot.engine.cortex.CortexLabelHygiene8144.executableHorizonMs8392(true)
    private val OTHER_TIMEOUT_MS_8344 = com.lifecyclebot.engine.cortex.CortexLabelHygiene8144.executableHorizonMs8392(false)
    private const val REOPEN_GAP_MS_8344 = 15 * 60_000L
    private const val MAX_CLOSES_PER_LANE_8344 = 300
    private const val PREFS_8344 = "aate_evidence_shadow_8344"

    private class Pos8344(
        val mint: String, val lane: String, val entryPx: Double, val atMs: Long, val costPct: Double,
        val stopPct: Double, val armPct: Double, val timeoutMs: Long,
        val advisoryEntry8393: Boolean = false,
        val contractVersion8394: Int = ShadowPersist8355.TRADE_CONTRACT_8394,
    ) {
        // Keep restored storage keys so upgrades retire the original row without losing an episode.
        var storageKey = episodeKey8417(mint, lane)
        var held = 1.0                 // fraction of the ticket still held
        var bankedGrossPct = 0.0        // sum of (fraction sold x gross % at the sale)
        var tiersHit = 0
        var peakPct = 0.0
        var lastPx = entryPx
        var lastPxAtMs = atMs
        // V5.0.8355 — persistence state: changed since the last write, when that write was, and (restored) no mark seen yet
        var dirty8355 = false
        var lastWriteMs8355 = 0L
        var noMarkSinceRestore8355 = false
        // V5.0.8418 — when this position was restored (0 = opened in this process): the lost-mark clock restarts there
        var restoredAtMs8418 = 0L
        // V5.0.8366 — the lifecycle hold (persisted) and a triggered exit waiting out the live sell latency (not persisted)
        var hold8366 = false
        var taken8366 = 0
        var peakAt8366 = 0L
        var exitAt8366 = 0L
        var exitWhy8366: String? = null
        var sellFraction8394 = 1.0
        var sellTier8394 = 0
        var sellTaken8394 = 0
    }

    // ── V5.0.8355 §SHADOW_EVIDENCE_SURVIVES_RESTARTS — the open book is persisted (its own prefs, the close book untouched):
    // written on open, coalesced on tier / peak change (ShadowPersist8355.writeDue8355: at most one write per position per
    // 10 s), removed on close; restored in init8344 with its real entry mark and clock, never re-priced. A restored position
    // resumes on the next tick; with no mark within LOST_MARK of its last one it closes LOST_MARK at its last mark, as it would have. ──
    private const val PREFS_OPEN_8355 = "aate_evidence_shadow_open_8355"
    @Volatile private var openPrefs8355: ShadowColdStore8397? = null
    private val initLock8372 = Any()
    @Volatile private var openRestored8372 = false
    @Volatile private var booksRestored8372 = false
    private val restored8355 = AtomicLong(0)
    private val lostOnRestore8355 = AtomicLong(0)
    private val expiredOnRestore8355 = AtomicLong(0)

    private fun persistOpen8355(p: Pos8344, nowMs: Long) {
        val enc = ShadowPersist8355.encodePos8355(ShadowPersist8355.PosRec8355(
            p.mint, p.lane, p.entryPx, p.atMs, p.costPct, p.stopPct, p.armPct, p.timeoutMs,
            p.held, p.bankedGrossPct, p.tiersHit, p.peakPct, p.lastPx, p.lastPxAtMs,
            hold8366 = p.hold8366, taken8366 = p.taken8366, peakAt8366 = p.peakAt8366,   // V5.0.8366
            advisoryEntry8393 = p.advisoryEntry8393, contractVersion8394 = p.contractVersion8394,
            exitAt8394 = p.exitAt8366, exitWhy8394 = p.exitWhy8366 ?: "",
            sellFraction8394 = p.sellFraction8394, sellTier8394 = p.sellTier8394, sellTaken8394 = p.sellTaken8394,
        ))
        val written = try { openPrefs8355?.write(mapOf(p.storageKey to enc)) == true } catch (_: Exception) { false }
        p.dirty8355 = !written
        if (written) p.lastWriteMs8355 = nowMs
    }

    private fun forgetOpen8355(mint: String) {
        try { openPrefs8355?.write(mapOf(mint to null)) } catch (_: Exception) {}
    }

    private fun restoreOpen8355(ctx: Context, nowMs: Long): Boolean = try {
        restoreOpen8417(ShadowColdStore8397.open(ctx, PREFS_OPEN_8355), nowMs)
    } catch (_: Throwable) { false }

    /** Restores legacy mint keys and lane/mint keys without repricing either episode. */
    @Synchronized internal fun restoreOpen8417(p: ShadowColdStore8397, nowMs: Long): Boolean = try {
            openPrefs8355 = p
            val drop = ArrayList<String>()
            var n = 0L
            for ((k, v) in p.entries()) {
                val r = ShadowPersist8355.decodePos8355(v as? String)
                if (r == null) { drop += k; continue }
                val episodeKey = episodeKey8417(r.mint, r.lane)
                if (open.containsKey(episodeKey)) continue   // partial-init retry never erases the live in-memory episode
                // V5.0.8366 — a position in the lifecycle hold has no clock: it keeps the hold's window
                if (r.hold8366 && ShadowPersist8355.expiredOnRestore8355(r.atMs, ShadowAlign8366.holdWindowMs8366(), nowMs)) { drop += k; expiredOnRestore8355.incrementAndGet(); continue }
                if (!r.hold8366 && ShadowPersist8355.expiredOnRestore8355(r.atMs, r.timeoutMs, nowMs)) { drop += k; expiredOnRestore8355.incrementAndGet(); continue }
                if (open.size >= MAX_OPEN_8344) { drop += k; continue }
                val pos = Pos8344(r.mint, r.lane, r.entryPx, r.atMs, r.costPct, r.stopPct, r.armPct, r.timeoutMs, r.advisoryEntry8393, r.contractVersion8394)
                pos.storageKey = k
                pos.held = r.held; pos.bankedGrossPct = r.bankedGrossPct; pos.tiersHit = r.tiersHit; pos.peakPct = r.peakPct
                pos.lastPx = r.lastPx; pos.lastPxAtMs = r.lastPxAtMs
                pos.exitAt8366 = r.exitAt8394; pos.exitWhy8366 = r.exitWhy8394.takeIf { it.isNotBlank() }
                pos.sellFraction8394 = r.sellFraction8394; pos.sellTier8394 = r.sellTier8394; pos.sellTaken8394 = r.sellTaken8394
                pos.hold8366 = r.hold8366; pos.taken8366 = r.taken8366; pos.peakAt8366 = r.peakAt8366   // V5.0.8366
                pos.noMarkSinceRestore8355 = true; pos.lastWriteMs8355 = nowMs
                pos.restoredAtMs8418 = nowMs
                wantMark8418(r.mint, nowMs)   // V5.0.8418 — a restored mint is in no token map yet: ask the keyless feed to price it
                if (open.putIfAbsent(episodeKey, pos) == null) {
                    lastOpenAt[episodeKey] = r.atMs
                    n++
                }
            }
            if (drop.isNotEmpty() && !p.write(drop.filter { open.values.none { pos -> pos.storageKey == it } }.associateWith { null as String? })) throw ShadowStorageUnavailable8397()
            restored8355.addAndGet(n)
            true
        } catch (_: Throwable) { false }

    /** V5.0.8355 — restored / lost-on-restore / expired-on-restore counts (tests and the status line). */
    fun restoreCounts8355(): Triple<Long, Long, Long> = Triple(restored8355.get(), lostOnRestore8355.get(), expiredOnRestore8355.get())

    internal fun episodeKey8417(mint: String, lane: String): String = "${CanonicalLaneIdentity6506.desk8392(lane)}|$mint"

    private val open = ConcurrentHashMap<String, Pos8344>()                 // canonical lane|mint -> position
    private val lastOpenAt = ConcurrentHashMap<String, Long>()              // mint|lane -> last open
    private val closes = ShadowCohortBook8397(fields = 5, hotCapacity = 32) // lane -> [atMs, netPct]
    private val opened = AtomicLong(0)
    private val closedN = AtomicLong(0)
    private val skippedFull = AtomicLong(0)


    // ── V5.0.8345 §THE_HOLD_TO_SHADOW_GAP_IS_EXPLAINED — 5.0.8344 audit (9 min LIVE): 288 hold refusals, 7 shadow opens, and
    // nothing said where the other 281 went. Every hold refusal is counted per lane against this book, and every candidate
    // the book did not open is counted by the reason it was skipped ([SKIP_*]), so the next report reads the gap off one line.
    const val SOURCE_DECISION_8345 = "DECISION"
    const val SOURCE_GRAB_8345 = "GRAB"
    const val SKIP_LEGACY_REFUSED_8345 = "CANONICAL_ENTRY_REFUSED_8392"     // the canonical entry verdict refused too: not an admitted candidate
    const val SKIP_HARD_SAFETY_8345 = "HARD_SAFETY_BINDS"
    const val SKIP_ALREADY_OPEN_8345 = "ALREADY_OPEN"
    const val SKIP_REOPEN_GAP_8345 = "REOPEN_GAP_15M"
    const val SKIP_FULL_8345 = "FULL"
    const val SKIP_NO_PRICE_8345 = "NO_PRICE"
    const val SKIP_STALE_PRICE_8345 = "STALE_PRICE_NO_TAPE_MARK"
    const val OPEN_FROM_TAPE_MARK_8345 = "OPENED_ON_TAPE_MARK"
    const val PRICE_FRESH_MS_8345 = 45_000L
    private const val MAX_SKIP_KEYS_8345 = 128

    private val holdRefused8345 = ConcurrentHashMap<String, AtomicLong>()     // lane -> hold refusals seen by the decision
    private val skipped8345 = ConcurrentHashMap<String, AtomicLong>()         // lane:why -> count
    private val openedBySource8345 = ConcurrentHashMap<String, AtomicLong>()  // source -> opens
    private val markSrc8360 = ConcurrentHashMap<String, AtomicLong>()         // V5.0.8360 — entry-mark source -> opens

    /** LiveEdgeGate7877.liveRefusal: the hold refused [lane]'s candidate; the decision's other verdicts say whether the book may take it. */
    fun noteHoldRefusal8345(lane: String, legacyRefused: Boolean, hardSafetyBinds: Boolean) {
        holdRefused8345.computeIfAbsent(lane) { AtomicLong(0) }.incrementAndGet()
        if (hardSafetyBinds) skip8345(lane, SKIP_HARD_SAFETY_8345) else if (legacyRefused) skip8345(lane, SKIP_LEGACY_REFUSED_8345)
    }

    private fun skip8345(lane: String, why: String) {
        val key = "$lane:$why"
        if (skipped8345.size >= MAX_SKIP_KEYS_8345 && !skipped8345.containsKey(key)) return
        skipped8345.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }

    /** Pure: the entry mark — the tick price when fresh ([freshMs]), else the tape / curve mark [tapeMark] (null = no entry). */
    fun entryMark8345(lastPrice: Double, lastPriceUpdateMs: Long, nowMs: Long, tapeMark: Double?, freshMs: Long = PRICE_FRESH_MS_8345): Pair<Double, Boolean>? {
        val tickFresh = lastPriceUpdateMs > 0L && nowMs - lastPriceUpdateMs in 0L..freshMs
        if (tickFresh && lastPrice.isFinite() && lastPrice > 0.0) return lastPrice to false
        if (tapeMark != null && tapeMark.isFinite() && tapeMark > 0.0) return tapeMark to true
        return null
    }

    /** Pure: shadow closes per hour over the hold's age ([ageMs]); 0 when nothing closed or no age. */
    fun closesPerHour8345(closes: Int, ageMs: Long): Double =
        if (closes <= 0 || ageMs <= 0L) 0.0 else closes.toDouble() * 3_600_000.0 / ageMs.toDouble()

    /** Pure: minutes until the lane reaches the bar ([bar] closes) at [closesPerHour]; null (printed "-") at a zero rate; 0 when at the bar. */
    fun etaToBarMin8345(closes: Int, closesPerHour: Double, bar: Int = EvidenceHold8336.MIN_N_8336): Long? {
        if (closes >= bar) return 0L
        if (!(closesPerHour > 0.0) || !closesPerHour.isFinite()) return null
        return kotlin.math.ceil((bar - closes).toDouble() / closesPerHour * 60.0).toLong()
    }

    /** Pure: "KEY=n,..." sorted by count (desc) then key, "-" when empty. */
    fun countLine8345(m: Map<String, Long>): String =
        m.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }

    /** " recovery8345{LANE n/30 closesPerHour=x.xx etaToBarMin=N · ...}" — the bounded path to the bar, per lane with any closes or hold refusals. */
    fun recoveryLine8345(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val lanes = (closes.keys + holdRefused8345.keys).toSortedSet()
        if (lanes.isEmpty()) return " recovery8345{-}"
        return " recovery8345{" + lanes.joinToString(" · ") { l ->
            val n = proof8344(l, sinceMs).n
            // V5.0.8351 — the rate is read over the lane's own shadow activity (its first close in the window to now), not
            // the hold's whole age: 8345 divided 13 shadow closes in 11 min by 511 min and printed a 767-minute ETA.
            val first = closes[l]?.let { q -> synchronized(q) { q.firstOrNull { it[0] >= sinceMs }?.get(0)?.toLong() } }
            val age = if (first != null && first > 0L) (nowMs - first).coerceAtLeast(60_000L) else if (sinceMs > 0L) nowMs - sinceMs else 0L
            val cph = closesPerHour8345(n, age)
            val bar = EvidenceHold8336.minN8399(l)
            "$l n$n/${bar} closesPerHour=${"%.2f".format(cph)} etaToBarMin=${etaToBarMin8345(n, cph, bar)?.toString() ?: "-"}"
        } + "}"
    }

    fun initReady8372(): Boolean = openRestored8372 && booksRestored8372

    fun init8344(ctx: Context, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(initLock8372) {
        if (!openRestored8372) openRestored8372 = restoreOpen8355(ctx, nowMs)
        if (!booksRestored8372) booksRestored8372 = try {
            closes.attach(ShadowColdStore8397.open(ctx, PREFS_8344))
            true
        } catch (_: Throwable) { false }
        initReady8372()
    }

    // ── pure ──

    /** Pure: the give-back floor (gross %) once the peak cleared [armPct] — the live 8338 rule (FieldManual7715
     *  runnerAwareStop7935): max(break-even capped at half the peak, 40 % of the peak); null below the arm. */
    fun giveBackFloor8344(peakPct: Double, armPct: Double, costPct: Double): Double? =
        if (peakPct.isFinite() && peakPct >= armPct)
            maxOf((if (costPct.isFinite()) costPct else 0.0).coerceAtMost(peakPct * 0.5), peakPct * 0.4) else null

    /** Pure: the round trip's net % from the banked slices, the remainder's exit gross and the charged cost. */
    fun netPct8344(bankedGrossPct: Double, held: Double, exitGrossPct: Double, costPct: Double): Double =
        bankedGrossPct + held * exitGrossPct - (if (costPct.isFinite()) costPct else 0.0)

    // ── open ──

    /**
     * LiveEdgeGate7877.liveRefusal: the live decision admitted [ts] in [lane], the hold (or the live-loss shut) refused.
     * V5.0.8345 — also RunnerGrabLane8343 ([source] = [SOURCE_GRAB_8345]): the grab lane's candidate refused in LIVE by the
     * hold alone is traded here on its HOST lane, so the hold can unlock that lane on it. Every skip is counted by reason;
     * a tick price older than [PRICE_FRESH_MS_8345] falls back to the mark the tick reads (ForwardReturnLabeler7731.markFor7948:
     * the curve / tape mark of coins whose price never reaches ts.lastPriceUpdate) instead of refusing the entry.
     */
    @Synchronized fun open8344(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis(), source: String = SOURCE_DECISION_8345, advisoryEntry8393: Boolean = false) {
        try {
            val pressure8397 = com.lifecyclebot.engine.MemoryGuard7977.pressure8144()
            if (pressure8397 == com.lifecyclebot.engine.RetentionPolicy8136.Pressure.HARD ||
                pressure8397 == com.lifecyclebot.engine.RetentionPolicy8136.Pressure.CRITICAL) {
                skip8345(lane, "SHADOW_CAPACITY_PRESSURE_8397"); return
            }
            val mint = ts.mint
            if (mint.isBlank()) return
            val key = episodeKey8417(mint, lane)
            if (open.containsKey(key)) { skip8345(lane, SKIP_ALREADY_OPEN_8345); return }
            val lastAt = lastOpenAt[key]
            if (lastAt != null && nowMs - lastAt in 0L until REOPEN_GAP_MS_8344) { skip8345(lane, SKIP_REOPEN_GAP_8345); return }
            if (open.size >= MAX_OPEN_8344) { skippedFull.incrementAndGet(); skip8345(lane, SKIP_FULL_8345); return }
            // V5.0.8360 — the fallback reads every observed mark the tick can (ShadowMark8360: the tick price under 60 s, the
            // labeler's registry / off-watch / curve mark, an active curve's reserves, the tape's last print), recording which
            val lab8360 = try { ForwardReturnLabeler7731.markFor7948(mint, { null }, nowMs) } catch (_: Throwable) { null }
            val obs8360 = if (lab8360 != null && lab8360.isFinite() && lab8360 > 0.0) Pair(lab8360, ShadowMark8360.SRC_LABELER_8360)
                else (try { ShadowMark8360.observed8360(ts, mint, nowMs) } catch (_: Throwable) { null })
            val tape = obs8360?.first
            val mark = entryMark8345(ts.lastPrice, ts.lastPriceUpdate, nowMs, tape)
            if (mark == null) {
                skip8345(lane, if (ts.lastPrice.isFinite() && ts.lastPrice > 0.0 && ts.lastPriceUpdate > 0L) SKIP_STALE_PRICE_8345 else SKIP_NO_PRICE_8345)
                return
            }
            val px = mark.first
            if (mark.second) try { PipelineHealthCollector.labelInc("EVIDENCE_SHADOW_${OPEN_FROM_TAPE_MARK_8345}_8345") } catch (_: Throwable) {}
            markSrc8360.computeIfAbsent(if (mark.second) (obs8360?.second ?: "?") else ShadowMark8360.SRC_TICK_8360) { AtomicLong(0) }.incrementAndGet()   // V5.0.8360
            val liq = try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }
            // V5.0.8366 — the lane's measured round trip (CostLedger7962, n >= 8) once measured, the NetEdge8030 model before
            val cost = try { ShadowAlign8366.laneCostPct8366(lane, liq, mint, ts.lastMcap) } catch (_: Throwable) { Double.NaN }
            val stop = try { FieldManual7715.stopPct8392(lane, cost) } catch (_: Throwable) { 10.0 }   // V5.0.8392 — never inside the round trip
            val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
            val arm = try { com.lifecyclebot.engine.RunnerExitProfile7277.giveBackArmPct7791(lane) } catch (_: Throwable) { 20.0 }
            // V5.0.8364 — booked at the decision mark plus the lane's decision-to-fill chase (ShadowFill8364), as a live buy fills
            val fillPx8364 = ShadowFill8364.laneEntryPx8364(px, lane)
            val pos8355 = Pos8344(mint, CanonicalLaneIdentity6506.desk8392(lane), fillPx8364, nowMs, if (cost.isFinite()) cost else 7.0, stop.coerceIn(3.0, 40.0), arm,
                if (runner) RUNNER_TIMEOUT_MS_8344 else OTHER_TIMEOUT_MS_8344, advisoryEntry8393)
            // V5.0.8366 — the live buy lands at its route's measured rate (deterministic per mint x lane x decision time); an
            // unlanded entry is no trade, booked as the failed-tx fee alone
            val unlanded8366 = try { ShadowAlign8366.unlandedEntryFeePct8366("$mint|$lane|$nowMs|EVIDENCE", mint, nowMs) } catch (_: Throwable) { null }
            if (unlanded8366 != null) {
                book8344(pos8355, -unlanded8366, ShadowAlign8366.UNLANDED_WHY_8366, nowMs)
                lastOpenAt[key] = nowMs
                return
            }
            open[key] = pos8355
            persistOpen8355(pos8355, nowMs)   // V5.0.8355 — written on open
            lastOpenAt[key] = nowMs
            if (lastOpenAt.size > 5_000) lastOpenAt.entries.removeIf { nowMs - it.value > REOPEN_GAP_MS_8344 }
            opened.incrementAndGet()
            openedBySource8345.computeIfAbsent(source) { AtomicLong(0) }.incrementAndGet()
            PipelineHealthCollector.labelInc("EVIDENCE_SHADOW_OPEN_8344_$lane")
            if (source == SOURCE_GRAB_8345) PipelineHealthCollector.labelInc("EVIDENCE_SHADOW_OPEN_8345_GRAB_$lane")  // V5.0.8345
        } catch (_: Throwable) {}
    }

    /** V5.0.8345 — the per-lane hold-refusal / open / skip counters (tests and the status line). */
    fun gapCounts8345(): Triple<Map<String, Long>, Map<String, Long>, Map<String, Long>> = Triple(
        holdRefused8345.mapValues { it.value.get() }, skipped8345.mapValues { it.value.get() }, openedBySource8345.mapValues { it.value.get() },
    )

    internal fun resetForTests8345() {
        open.clear(); lastOpenAt.clear(); closes.clear(); opened.set(0); closedN.set(0); skippedFull.set(0)
        holdRefused8345.clear(); skipped8345.clear(); openedBySource8345.clear(); markSrc8360.clear()   // V5.0.8360
        restored8355.set(0); lostOnRestore8355.set(0); expiredOnRestore8355.set(0)   // V5.0.8355
        wantedAt8418.clear(); wantCallsN8418.set(0)   // V5.0.8418
    }

    // ── tick ──

    /** BotService shadow tick (every loop): mark, ladder, floor, stop, timeout. */
    @Synchronized fun tick8344(tokens: Map<String, TokenState>, nowMs: Long = System.currentTimeMillis()) {
        if (open.isEmpty()) return
        val done = ArrayList<String>()
        for ((key, p) in open) {
            val mint = p.mint
            try {
                val ts = tokens[mint]
                wantMark8418(mint, nowMs)   // V5.0.8418 — keep the data side pricing every open evidence position
                val observed8394 = ShadowMark8360.observedTimed8372(ts, mint, nowMs)
                val fresh = observed8394?.priceUsd
                if (observed8394 != null) { p.lastPx = observed8394.priceUsd; p.lastPxAtMs = observed8394.observedAtMs; p.noMarkSinceRestore8355 = false }
                val g = (p.lastPx / p.entryPx - 1.0) * 100.0
                if (!g.isFinite()) { done += key; continue }
                if (g > p.peakPct) p.peakAt8366 = nowMs   // V5.0.8366 — when the peak was set (the hold's dead-money clock)
                if (g > p.peakPct) { p.peakPct = g; p.dirty8355 = true }   // V5.0.8355 — a peak change is a persisted change
                val why = why8366(p, g, fresh, nowMs)
                if (why == null) {
                    // V5.0.8355 — coalesced: a changed position is written at most once per 10 s; an unchanged one never
                    if (ShadowPersist8355.writeDue8355(p.dirty8355, p.lastWriteMs8355, nowMs)) persistOpen8355(p, nowMs)
                    continue
                }
                if (why == WAIT_8366) { if (ShadowPersist8355.writeDue8355(p.dirty8355, p.lastWriteMs8355, nowMs)) persistOpen8355(p, nowMs); continue }   // V5.0.8366 — waiting out the live sell latency
                book8344(p, netPct8344(p.bankedGrossPct, p.held, ShadowFill8364.exitGrossPct8364(g), p.costPct), why, nowMs)
                if (why == "LOST_MARK" && p.noMarkSinceRestore8355) lostOnRestore8355.incrementAndGet()   // V5.0.8355
                done += key
            } catch (_: ShadowStorageUnavailable8397) { p.dirty8355 = true }
            catch (_: Throwable) {
                p.dirty8355 = true
                try { PipelineHealthCollector.labelInc("EVIDENCE_SHADOW_TICK_RETRY_8417") } catch (_: Throwable) {}
            }
        }
        done.forEach { key -> open.remove(key)?.let { forgetOpen8355(it.storageKey) } }   // V5.0.8355 — a closed (or dropped) position leaves the open prefs
    }

    private const val WAIT_8366 = "WAIT_8366"

    // ── V5.0.8418 §RESTORED_SHADOW_KEEPS_ITS_MARK — runtime 8416: restored8355=45 lostOnRestore8355=27. The open book was
    // persisted and restored with its entry price, clock and checkpoints, but nothing asked the data side to price a restored
    // mint (after a restart it is in no token map, and only ExploreShadow8347 registered shadow mark demand), and its
    // lost-mark clock ran from the pre-restart mark, so most restored episodes closed LOST_MARK unpriced. Every open evidence
    // position now registers keyless mark demand (at most once per mint per 30 s) and restored ones get a full lost-mark window.
    private const val WANT_EVERY_MS_8418 = 30_000L
    private val wantedAt8418 = ConcurrentHashMap<String, Long>()
    private val wantCallsN8418 = AtomicLong(0)

    private fun wantMark8418(mint: String, nowMs: Long) {
        val last = wantedAt8418[mint]
        if (last != null && nowMs - last in 0L until WANT_EVERY_MS_8418) return
        wantedAt8418[mint] = nowMs
        if (wantedAt8418.size > 2_000) wantedAt8418.entries.removeIf { nowMs - it.value > WANT_EVERY_MS_8418 }
        try { KeylessMarks8359.noteWanted8359(mint, nowMs + LOST_MARK_MS_8344) } catch (_: Throwable) {}
        wantCallsN8418.incrementAndGet()
    }

    /** V5.0.8418 — mark-demand registrations (tests and the status line). */
    fun wantCalls8418(): Long = wantCallsN8418.get()

    /**
     * V5.0.8366 — the position's exit on live's levers (null = hold, [WAIT_8366] = triggered, waiting out the live sell
     * latency): SpikeCapture7943 tiers, the lane stop, the 8338 give-back floor, the level live's fluid stop holds
     * (ShadowAlign8366.liveStopLevel8366), the clock; at +100 % the lifecycle hold's own exits and no clock. A triggered full
     * exit fills at the first observed mark once PaperExitLatency8223's latency has passed since the trigger.
     */
    private fun requestSlice8394(p: Pos8344, fraction: Double, why: String, nowMs: Long, tier: Int = 0, taken: Int = 0) {
        p.sellFraction8394 = fraction; p.sellTier8394 = tier; p.sellTaken8394 = taken
        p.exitWhy8366 = why; p.exitAt8366 = nowMs; p.dirty8355 = true
        persistOpen8355(p, nowMs)
    }

    private fun why8366(p: Pos8344, g: Double, fresh: Double?, nowMs: Long): String? {
        // superseded by V5.0.8418 — a restored position's lost-mark clock restarts at the restore (the process was down, not the mark)
        val lost = ShadowPersist8355.lostMark8418(p.lastPxAtMs, p.restoredAtMs8418, nowMs, LOST_MARK_MS_8344)
        val pending = p.exitWhy8366
        if (pending != null) {
            if (lost) return "LOST_MARK"
            if (fresh == null || !ShadowPersist8355.exitFillObserved8372(p.lastPxAtMs, p.exitAt8366,
                    ShadowAlign8366.sellLatencyMs8366(false), nowMs)) return WAIT_8366
            if (p.sellTier8394 > 0 || p.sellTaken8394 > 0) {
                val sold = p.held * p.sellFraction8394.coerceIn(0.0, 1.0)
                p.bankedGrossPct += sold * ShadowFill8364.exitGrossPct8364(g); p.held -= sold
                p.tiersHit = maxOf(p.tiersHit, p.sellTier8394); p.taken8366 = maxOf(p.taken8366, p.sellTaken8394)
                p.exitWhy8366 = null; p.exitAt8366 = 0L; p.sellFraction8394 = 1.0; p.sellTier8394 = 0; p.sellTaken8394 = 0
                p.dirty8355 = true
                persistOpen8355(p, nowMs)
                return if (p.held <= 0.0001) "SOLD_OUT" else WAIT_8366
            }
            return pending
        }
        val runner = p.timeoutMs >= RUNNER_TIMEOUT_MS_8344
        if (!p.hold8366 && fresh != null && ShadowAlign8366.holdPromotes8366(g, p.peakPct)) { p.hold8366 = true; p.dirty8355 = true }
        val why: String? = if (p.hold8366) {
            val d = if (fresh != null) ShadowAlign8366.holdExit8366(g, p.peakPct, p.taken8366, nowMs - p.atMs, nowMs - p.peakAt8366, p.costPct) else null
            var full: String? = null
            if (d != null) {
                val n = ShadowAlign8366.takeOf8366(d.first)
                if (n < 0) full = d.first
                else { requestSlice8394(p, d.second, d.first, nowMs, taken = n); return WAIT_8366 }
            }
            when {
                full != null -> full
                p.held <= 0.0001 -> "SOLD_OUT"
                lost -> "LOST_MARK"
                else -> null
            }
        } else {
            // spike tiers, in order, each sells its share of what is held
            if (p.tiersHit < com.lifecyclebot.engine.SpikeCapture7943.TIERS.size && fresh != null) {
                val (at, frac) = com.lifecyclebot.engine.SpikeCapture7943.TIERS[p.tiersHit]
                if (g >= at) { requestSlice8394(p, frac, "SPIKE_PARTIAL_8394", nowMs, tier = p.tiersHit + 1); return WAIT_8366 }
            }
            val floor = giveBackFloor8344(p.peakPct, p.armPct, p.costPct)
            val level = ShadowAlign8366.liveStopLevel8366(runner, p.stopPct, p.peakPct, p.costPct)
            when {
                p.held <= 0.0001 -> "SOLD_OUT"
                fresh != null && g <= -p.stopPct -> "STOP"
                fresh != null && floor != null && g <= floor -> "GIVEBACK_FLOOR"
                fresh != null && level > -p.stopPct && g <= level -> "LIVE_FLOOR_8366"
                nowMs - p.atMs >= p.timeoutMs -> "TIMEOUT"
                lost -> "LOST_MARK"
                else -> null
            }
        }
        if (why == null || why == "SOLD_OUT" || why == "LOST_MARK" || fresh == null) return why
        requestSlice8394(p, 1.0, why, nowMs)
        return WAIT_8366
    }

    private fun book8344(p: Pos8344, net: Double, why: String, nowMs: Long) {
        val booked8397 = closes.append(listOf(p.lane), doubleArrayOf(nowMs.toDouble(), net.coerceIn(-100.0, 1_000.0),
            if (p.advisoryEntry8393 && why != "LOST_MARK" && why != ShadowAlign8366.UNLANDED_WHY_8366) 1.0 else 0.0,
            if (why != "LOST_MARK" && why != ShadowAlign8366.UNLANDED_WHY_8366) p.contractVersion8394.toDouble() else 0.0,
            p.atMs.toDouble()), "${p.mint}|${p.lane}|${p.atMs}")
        if (!booked8397.inserted) return
        closedN.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EVIDENCE_SHADOW_CLOSE_8344_${why}") } catch (_: Throwable) {}
        // V5.0.8366 — every landed close models the live position on this mint: the alignment pairs read it
        if (why != ShadowAlign8366.UNLANDED_WHY_8366) try { ShadowAlign8366.noteShadow8366("EVIDENCE", p.lane, p.mint, p.atMs, net, nowMs) } catch (_: Throwable) {}
    }

    fun memoryCount8397(): Int = open.size + closes.hotCount()
    fun memoryBytes8397(): Long = open.size * 1024L + closes.bytes()
    fun trimCold8397(): Int = closes.trim()

    // ── proof ──

    /** The lane's shadow record since [sinceMs], on EvidenceHold8336's bar (PnL in SOL at the route-minimum ticket). */
    fun proof8344(lane: String, sinceMs: Long): EvidenceHold8336.Proof8336 {
        val l = CanonicalLaneIdentity6506.desk8392(lane)
        val q = closes[l] ?: return EvidenceHold8336.proof8336(emptyList(), emptyList(), l)
        val nets = synchronized(q) { q.filter { it[0] >= sinceMs && ShadowPersist8355.proofEligible8394(it.getOrNull(3), it.getOrNull(4), sinceMs) }.map { it[1] } }
        return EvidenceHold8336.proof8336(nets, nets.map { it * TICKET_SOL_8344 / 100.0 }, l)
    }

    // New policy's proof is isolated from legacy gate-filtered outcomes. Historical proof is untouched.
    internal fun advisoryNets8393(rows: List<DoubleArray>, sinceMs: Long): List<Double> =
        rows.filter { it.size >= 3 && it[0] >= sinceMs && it[2] == 1.0 && it[1].isFinite() && ShadowPersist8355.proofEligible8394(it.getOrNull(3), it.getOrNull(4), sinceMs) }.map { it[1] }

    fun advisoryProof8393(lane: String, sinceMs: Long): EvidenceHold8336.Proof8336 {
        val l = CanonicalLaneIdentity6506.desk8392(lane)
        val q = closes[l]
        val nets = if (q == null) emptyList() else synchronized(q) { advisoryNets8393(q.toList(), sinceMs) }
        return EvidenceHold8336.proof8336(nets, nets.map { it * TICKET_SOL_8344 / 100.0 }, l)
    }

    /** Tests only: a booked close for [lane] at [atMs] (no position, no prefs). */
    internal fun noteCloseForTests8345(lane: String, netPct: Double, atMs: Long) {
        closes.append(listOf(lane), doubleArrayOf(atMs.toDouble(), netPct, 0.0,
            ShadowPersist8355.TRADE_CONTRACT_8394.toDouble(), atMs.toDouble()), "test|$lane|$atMs|${closedN.get()}")
        closedN.incrementAndGet()
    }

    fun statusLine8344(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val lanes = closes.keys.sorted().joinToString(" · ") { l ->
            val p = proof8344(l, sinceMs)
            val advisory = advisoryProof8393(l, sinceMs)
            "$l advisory8393[n=${advisory.n},proven=${advisory.proven}] n${p.n} mean${"%+.1f".format(p.meanPct)}% se${"%.1f".format(p.sePct)}% lower${"%+.1f".format(p.meanPct - p.sePct)}% need>${EvidenceHold8336.MARGIN_PCT_8336}% pf${"%.2f".format(p.pf)}${if (p.proven) " PROVEN" else ""}"
        }.ifBlank { "no shadow closes yet" }
        val openByLane = open.values.groupingBy { it.lane }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }
        val (held, skipped, bySource) = gapCounts8345()  // V5.0.8345
        return "evidenceShadow8344[open=${open.size}/$MAX_OPEN_8344 ($openByLane) opened=${opened.get()} closed=${closedN.get()} full=${skippedFull.get()} " +
            "restored8355=${restored8355.get()} lostOnRestore8355=${lostOnRestore8355.get()} expiredOnRestore8355=${expiredOnRestore8355.get()} markWants8418=${wantCallsN8418.get()} " +
            "exits=lane stop+spike tiers+give-back floor+timeout cost=NetEdge8030 @${TICKET_SOL_8344}SOL | $lanes" +
            " holdRefused{${countLine8345(held)}} openedBy{${countLine8345(bySource)}} skipped{${countLine8345(skipped)}}" +
            " entryMark8360{${countLine8345(markSrc8360.mapValues { it.value.get() })}}" + recoveryLine8345(sinceMs, nowMs) + "]"
    }
}
