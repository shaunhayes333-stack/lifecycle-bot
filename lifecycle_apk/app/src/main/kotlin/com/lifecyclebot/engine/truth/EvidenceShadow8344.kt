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

    /** Entry8114Mode8326.decide8326: the live decision admitted [ts] in [lane], the hold (or the live-loss shut) refused. */
    fun open8344(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            val mint = ts.mint
            if (mint.isBlank() || open.containsKey(mint)) return
            val key = "$mint|$lane"
            lastOpenAt[key]?.let { if (nowMs - it in 0L until REOPEN_GAP_MS_8344) return }
            if (open.size >= MAX_OPEN_8344) { skippedFull.incrementAndGet(); return }
            val px = ts.lastPrice.takeIf { it.isFinite() && it > 0.0 } ?: return
            if (ts.lastPriceUpdate <= 0L || nowMs - ts.lastPriceUpdate > 45_000L) return      // a stale price is not an entry
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
            PipelineHealthCollector.labelInc("EVIDENCE_SHADOW_OPEN_8344_$lane")
        } catch (_: Throwable) {}
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

    fun statusLine8344(sinceMs: Long): String {
        val lanes = closes.keys.sorted().joinToString(" · ") { l ->
            val p = proof8344(l, sinceMs)
            "$l n${p.n} mean${"%+.1f".format(p.meanPct)}% pf${"%.2f".format(p.pf)}${if (p.proven) " PROVEN" else ""}"
        }.ifBlank { "no shadow closes yet" }
        val openByLane = open.values.groupingBy { it.lane }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }
        return "evidenceShadow8344[open=${open.size}/$MAX_OPEN_8344 ($openByLane) opened=${opened.get()} closed=${closedN.get()} full=${skippedFull.get()} " +
            "exits=lane stop+spike tiers+give-back floor+timeout cost=NetEdge8030 @${TICKET_SOL_8344}SOL | $lanes]"
    }
}
