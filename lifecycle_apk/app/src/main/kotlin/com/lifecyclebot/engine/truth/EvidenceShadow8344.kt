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
 *   - one position per mint, [MAX_OPEN_8344] open at most; a mark missing for [LOST_MARK_MS_8344] closes at the last mark.
 * Every close is booked per lane (persisted, since the hold's start) and EvidenceHold8336 unlocks a lane on this record
 * with the same bar it applies to paper (n >= 30, mean - SE > 2 %, PF >= 1.3). Spends nothing, opens no position, takes
 * no slot; paper and the live ledger never see it.
 * Known gaps (it is a model, not a fill): no landing failures, no sell latency, fills at the mark plus the modelled cost.
 */
object EvidenceShadow8344 {
    const val MAX_OPEN_8344 = 120
    const val TICKET_SOL_8344 = 0.03
    private const val LOST_MARK_MS_8344 = 10 * 60_000L
    private const val RUNNER_TIMEOUT_MS_8344 = 120 * 60_000L
    private const val OTHER_TIMEOUT_MS_8344 = 30 * 60_000L
    private const val REOPEN_GAP_MS_8344 = 15 * 60_000L
    private const val MAX_CLOSES_PER_LANE_8344 = 300
    private const val PREFS_8344 = "aate_evidence_shadow_8344"

    private class Pos8344(
        val mint: String, val lane: String, val entryPx: Double, val atMs: Long, val costPct: Double,
        val stopPct: Double, val armPct: Double, val timeoutMs: Long,
    ) {
        var held = 1.0                 // fraction of the ticket still held
        var bankedGrossPct = 0.0        // sum of (fraction sold x gross % at the sale)
        var tiersHit = 0
        var peakPct = 0.0
        var lastPx = entryPx
        var lastPxAtMs = atMs
    }

    private val open = ConcurrentHashMap<String, Pos8344>()                 // mint -> position
    private val lastOpenAt = ConcurrentHashMap<String, Long>()              // mint|lane -> last open
    private val closes = ConcurrentHashMap<String, ArrayDeque<DoubleArray>>() // lane -> [atMs, netPct]
    private val opened = AtomicLong(0)
    private val closedN = AtomicLong(0)
    private val skippedFull = AtomicLong(0)
    @Volatile private var prefs: android.content.SharedPreferences? = null

    // ── V5.0.8345 §THE_HOLD_TO_SHADOW_GAP_IS_EXPLAINED — 5.0.8344 audit (9 min LIVE): 288 hold refusals, 7 shadow opens, and
    // nothing said where the other 281 went. Every hold refusal is counted per lane against this book, and every candidate
    // the book did not open is counted by the reason it was skipped ([SKIP_*]), so the next report reads the gap off one line.
    const val SOURCE_DECISION_8345 = "DECISION"
    const val SOURCE_GRAB_8345 = "GRAB"
    const val SKIP_LEGACY_REFUSED_8345 = "LEGACY_8114_REFUSED"     // the 8114 verdict refused too: not an admitted candidate
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

    /** Entry8114Mode8326.decide8326: the hold refused [lane]'s candidate; the decision's other verdicts say whether the book may take it. */
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
            "$l n$n/${EvidenceHold8336.MIN_N_8336} closesPerHour=${"%.2f".format(cph)} etaToBarMin=${etaToBarMin8345(n, cph)?.toString() ?: "-"}"
        } + "}"
    }

    fun init8344(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences(PREFS_8344, Context.MODE_PRIVATE)
            prefs = p
            for ((lane, v) in p.all) {
                val q = ArrayDeque<DoubleArray>()
                (v as? String)?.split(';')?.forEach { row ->
                    val parts = row.split(',')
                    val at = parts.getOrNull(0)?.toLongOrNull(); val net = parts.getOrNull(1)?.toDoubleOrNull()
                    if (at != null && net != null && net.isFinite()) q.addLast(doubleArrayOf(at.toDouble(), net))
                }
                if (q.isNotEmpty()) closes[lane] = q
            }
        } catch (_: Throwable) {}
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
     * Entry8114Mode8326.decide8326: the live decision admitted [ts] in [lane], the hold (or the live-loss shut) refused.
     * V5.0.8345 — also RunnerGrabLane8343 ([source] = [SOURCE_GRAB_8345]): the grab lane's candidate refused in LIVE by the
     * hold alone is traded here on its HOST lane, so the hold can unlock that lane on it. Every skip is counted by reason;
     * a tick price older than [PRICE_FRESH_MS_8345] falls back to the mark the tick reads (ForwardReturnLabeler7731.markFor7948:
     * the curve / tape mark of coins whose price never reaches ts.lastPriceUpdate) instead of refusing the entry.
     */
    fun open8344(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis(), source: String = SOURCE_DECISION_8345) {
        try {
            val mint = ts.mint
            if (mint.isBlank()) return
            if (open.containsKey(mint)) { skip8345(lane, SKIP_ALREADY_OPEN_8345); return }
            val key = "$mint|$lane"
            val lastAt = lastOpenAt[key]
            if (lastAt != null && nowMs - lastAt in 0L until REOPEN_GAP_MS_8344) { skip8345(lane, SKIP_REOPEN_GAP_8345); return }
            if (open.size >= MAX_OPEN_8344) { skippedFull.incrementAndGet(); skip8345(lane, SKIP_FULL_8345); return }
            val tape = try { ForwardReturnLabeler7731.markFor7948(mint, { null }, nowMs) } catch (_: Throwable) { null }
            val mark = entryMark8345(ts.lastPrice, ts.lastPriceUpdate, nowMs, tape)
            if (mark == null) {
                skip8345(lane, if (ts.lastPrice.isFinite() && ts.lastPrice > 0.0 && ts.lastPriceUpdate > 0L) SKIP_STALE_PRICE_8345 else SKIP_NO_PRICE_8345)
                return
            }
            val px = mark.first
            if (mark.second) try { PipelineHealthCollector.labelInc("EVIDENCE_SHADOW_${OPEN_FROM_TAPE_MARK_8345}_8345") } catch (_: Throwable) {}
            val liq = try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }
            val cost = try { NetEdge8030.costPct8030(lane, TICKET_SOL_8344, liq, mint, ts.lastMcap) } catch (_: Throwable) { Double.NaN }
            val stop = try { FieldManual7715.mandateFor(lane).invalidationPct } catch (_: Throwable) { 10.0 }
            val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
            val arm = try { com.lifecyclebot.engine.RunnerExitProfile7277.giveBackArmPct7791(lane) } catch (_: Throwable) { 20.0 }
            open[mint] = Pos8344(mint, lane, px, nowMs, if (cost.isFinite()) cost else 7.0, stop.coerceIn(3.0, 40.0), arm,
                if (runner) RUNNER_TIMEOUT_MS_8344 else OTHER_TIMEOUT_MS_8344)
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
        holdRefused8345.clear(); skipped8345.clear(); openedBySource8345.clear()
    }

    // ── tick ──

    /** BotService shadow tick (every loop): mark, ladder, floor, stop, timeout. */
    fun tick8344(tokens: Map<String, TokenState>, nowMs: Long = System.currentTimeMillis()) {
        if (open.isEmpty()) return
        val done = ArrayList<String>()
        for ((mint, p) in open) {
            try {
                val ts = tokens[mint]
                val fresh = ts?.takeIf { it.lastPriceUpdate > 0L && nowMs - it.lastPriceUpdate <= 45_000L }?.lastPrice?.takeIf { it > 0.0 }
                    ?: ForwardReturnLabeler7731.markFor7948(mint, { null }, nowMs)?.takeIf { it > 0.0 }
                if (fresh != null) { p.lastPx = fresh; p.lastPxAtMs = nowMs }
                val g = (p.lastPx / p.entryPx - 1.0) * 100.0
                if (!g.isFinite()) { done += mint; continue }
                if (g > p.peakPct) p.peakPct = g
                // spike tiers, in order, each sells its share of what is held
                while (p.tiersHit < com.lifecyclebot.engine.SpikeCapture7943.TIERS.size && fresh != null) {
                    val (at, frac) = com.lifecyclebot.engine.SpikeCapture7943.TIERS[p.tiersHit]
                    if (g < at) break
                    val sold = p.held * frac
                    p.bankedGrossPct += sold * g
                    p.held -= sold
                    p.tiersHit++
                }
                val floor = giveBackFloor8344(p.peakPct, p.armPct, p.costPct)
                val why = when {
                    p.held <= 0.0001 -> "SOLD_OUT"
                    fresh != null && g <= -p.stopPct -> "STOP"
                    fresh != null && floor != null && g <= floor -> "GIVEBACK_FLOOR"
                    nowMs - p.atMs >= p.timeoutMs -> "TIMEOUT"
                    nowMs - p.lastPxAtMs >= LOST_MARK_MS_8344 -> "LOST_MARK"
                    else -> null
                } ?: continue
                book8344(p, netPct8344(p.bankedGrossPct, p.held, g, p.costPct), why, nowMs)
                done += mint
            } catch (_: Throwable) { done += mint }
        }
        done.forEach { open.remove(it) }
    }

    private fun book8344(p: Pos8344, net: Double, why: String, nowMs: Long) {
        val q = closes.computeIfAbsent(p.lane) { ArrayDeque() }
        synchronized(q) {
            q.addLast(doubleArrayOf(nowMs.toDouble(), net.coerceIn(-100.0, 1_000.0)))
            while (q.size > MAX_CLOSES_PER_LANE_8344) q.removeFirst()
            try { prefs?.edit()?.putString(p.lane, q.joinToString(";") { "${it[0].toLong()},${"%.3f".format(it[1])}" })?.apply() } catch (_: Throwable) {}
        }
        closedN.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EVIDENCE_SHADOW_CLOSE_8344_${why}") } catch (_: Throwable) {}
    }

    // ── proof ──

    /** The lane's shadow record since [sinceMs], on EvidenceHold8336's bar (PnL in SOL at the route-minimum ticket). */
    fun proof8344(lane: String, sinceMs: Long): EvidenceHold8336.Proof8336 {
        val q = closes[lane] ?: return EvidenceHold8336.proof8336(emptyList(), emptyList())
        val nets = synchronized(q) { q.filter { it[0] >= sinceMs }.map { it[1] } }
        return EvidenceHold8336.proof8336(nets, nets.map { it * TICKET_SOL_8344 / 100.0 })
    }

    /** Tests only: a booked close for [lane] at [atMs] (no position, no prefs). */
    internal fun noteCloseForTests8345(lane: String, netPct: Double, atMs: Long) {
        val q = closes.computeIfAbsent(lane) { ArrayDeque() }
        synchronized(q) { q.addLast(doubleArrayOf(atMs.toDouble(), netPct)) }
        closedN.incrementAndGet()
    }

    fun statusLine8344(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val lanes = closes.keys.sorted().joinToString(" · ") { l ->
            val p = proof8344(l, sinceMs)
            "$l n${p.n} mean${"%+.1f".format(p.meanPct)}% pf${"%.2f".format(p.pf)}${if (p.proven) " PROVEN" else ""}"
        }.ifBlank { "no shadow closes yet" }
        val openByLane = open.values.groupingBy { it.lane }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }
        val (held, skipped, bySource) = gapCounts8345()  // V5.0.8345
        return "evidenceShadow8344[open=${open.size}/$MAX_OPEN_8344 ($openByLane) opened=${opened.get()} closed=${closedN.get()} full=${skippedFull.get()} " +
            "exits=lane stop+spike tiers+give-back floor+timeout cost=NetEdge8030 @${TICKET_SOL_8344}SOL | $lanes" +
            " holdRefused{${countLine8345(held)}} openedBy{${countLine8345(bySource)}} skipped{${countLine8345(skipped)}}" + recoveryLine8345(sinceMs, nowMs) + "]"
    }
}
