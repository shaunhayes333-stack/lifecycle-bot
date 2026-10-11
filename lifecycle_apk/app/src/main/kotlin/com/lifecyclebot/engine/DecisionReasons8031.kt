package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8031 — a reason for every verdict, graded by what the price did next.
 *
 * Owner: "we have to have a reason recorded as to why it refuses, is neutral or allows any trade, so the Cortex and
 * the stack can learn from it. Same as buying, holding and selling."
 *
 * ENTRY — every lane's native scorer verdict ([nativeVerdicts8031], SpecialistBrainBridge7542). A refusal ("MCAP too
 *   low", "NO_CONFIRMED_SCALP_SETUP", "PRICE_NOT_FRESH", ...) was a counter and was gone: no learner could ever ask
 *   whether that refusal turned away winners. It is now a forward label in the lane's own book with reason
 *   NATIVE_<reason>, so the Cortex veto audit grades it like every FDG refusal, and the lane's pre-intent row names
 *   it. READY verdicts are named too (the FDG labels the admit). Labels back off when the labeller is half full.
 * HOLD / SELL — every exit the bot holds back (net-profit floor, broken basis, runner hold, missing mark ...) and every
 *   close is booked here ([hold8031], [sell8031]) at the mark of the decision and graded 15 minutes later (60 for a
 *   runner lane): after a HOLD, up = holding paid; after a SELL, up = the sale left money behind. Per gate and reason,
 *   live and paper apart.
 */
object DecisionReasons8031 {
    private const val GRADE_MS = 15L * 60_000L
    private const val GRADE_RUNNER_MS = 60L * 60_000L
    private const val HOLD_DEDUP_MS = 3L * 60_000L
    private const val MAX_PENDING = 3_000
    private const val LABEL_LOAD_CAP = 0.3  // V5.0.8033 — 0.5 held ~3,000 pending labels in a 1.5 MB prefs file; native labels yield sooner

    data class Pending(val mint: String, val key: String, val px: Double, val dueMs: Long)
    class Agg { var n = 0; var sum = 0.0; var up = 0
        fun add(v: Double) { n++; sum += v; if (v > 0.0) up++ }
        fun line(k: String) = "$k n$n/${"%+.1f".format(if (n > 0) sum / n else 0.0)}%/up${if (n > 0) up * 100 / n else 0}%" }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val book = ConcurrentHashMap<String, Agg>()
    private val lastHold = ConcurrentHashMap<String, Long>()
    private val nativeLabelled = AtomicLong(0)
    private val nativeNamed = AtomicLong(0)
    private val nativeBackedOff = AtomicLong(0)

    // ── pure ──

    /** The reason's rule: its leading upper-case words (MCAP_TOO_LOW_$2670 -> MCAP_TOO_LOW). */
    fun rule8031(reason: String): String =
        reason.uppercase().replace(Regex("[^A-Z0-9_]+"), "_").split('_')
            .takeWhile { w -> w.isNotEmpty() && w.none { it.isDigit() } }.take(4).joinToString("_").ifBlank { "UNKNOWN" }

    /** The book key of a decision: KIND:GATE:RULE@LANE (mode prefixed). */
    fun key8031(live: Boolean, kind: String, gate: String, reason: String, lane: String): String =
        "${if (live) "LIVE" else "PAPER"}|$kind:$gate:${rule8031(reason)}@${lane.uppercase().ifBlank { "UNKNOWN" }}"

    // ── entry ──

    /** SpecialistBrainBridge7542: every lane's native verdict on [ts] gets a name, and a refusal gets a forward label. */
    fun nativeVerdicts8031(ts: com.lifecyclebot.data.TokenState, verdicts: Collection<SpecialistBrainBridge7542.Opinion>) {
        val load = try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.pendingLoad8031() } catch (_: Throwable) { 1.0 }
        for (o in verdicts) {
            val reason = "NATIVE_${if (o.eligible) "READY" else rule8031(o.reason)}"
            try { SpecialistOwnership7951.notePreIntent7951(o.lane, reason) } catch (_: Throwable) {}
            nativeNamed.incrementAndGet()
            if (o.eligible || !o.gradeable) continue
            if (load >= LABEL_LOAD_CAP) { nativeBackedOff.incrementAndGet(); continue }
            try {
                com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.observe(ts, o.lane, false, reason)
                nativeLabelled.incrementAndGet()
            } catch (_: Throwable) {}
        }
    }

    // ── hold / sell ──

    private fun mark(ts: com.lifecyclebot.data.TokenState): Double = ts.lastPrice.takeIf { it.isFinite() && it > 0.0 } ?: Double.NaN

    private fun runnerLane(lane: String) = try { RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }

    /** Executor sell door: [gate] held an exit for [reason]. Deduped per position and gate. */
    fun hold8031(ts: com.lifecyclebot.data.TokenState, gate: String, reason: String, nowMs: Long = System.currentTimeMillis()) {
        val px = mark(ts); if (!px.isFinite()) return
        val pos = ts.position
        val dk = "${ts.mint}|${pos.entryTime}|$gate"
        val last = lastHold[dk]
        if (last != null && nowMs - last < HOLD_DEDUP_MS) return
        lastHold[dk] = nowMs
        if (lastHold.size > 4_000) lastHold.entries.removeIf { nowMs - it.value > HOLD_DEDUP_MS }
        add(ts.mint, key8031(!pos.isPaperPosition, "HOLD", gate, reason, pos.tradingMode), px, nowMs, runnerLane(pos.tradingMode))
    }

    /** CanonicalFinalizedTradeBus6464: a close (live or paper) — graded by the price after it. */
    fun sell8031(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope, nowMs: Long = System.currentTimeMillis()) {
        if (!env.terminal || env.mint.isBlank()) return
        val ts = try { BotService.status.tokens[env.mint] } catch (_: Throwable) { null } ?: return
        val px = mark(ts); if (!px.isFinite()) return
        add(env.mint, key8031(env.mode.equals("live", true), "SELL", "CLOSE", env.exitReason, env.lane), px, nowMs, runnerLane(env.lane))
    }

    private fun add(mint: String, key: String, px: Double, nowMs: Long, runner: Boolean) {
        if (pending.size >= MAX_PENDING) return
        pending["$key|$mint|$nowMs"] = Pending(mint, key, px, nowMs + if (runner) GRADE_RUNNER_MS else GRADE_MS)
    }

    /** ExitRegret7752.tick: grade the due decisions on the loop's fresh prices. */
    fun tick8031(priceFor: (String) -> Double?, nowMs: Long = System.currentTimeMillis()) {
        if (pending.isEmpty()) return
        for ((id, p) in pending.entries.toList()) {
            if (nowMs < p.dueMs) continue
            val px = (try { priceFor(p.mint) } catch (_: Throwable) { null })?.takeIf { it.isFinite() && it > 0.0 }
            if (px == null) { if (nowMs - p.dueMs > GRADE_MS) pending.remove(id); continue }
            pending.remove(id)
            if (com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.basisSuspect7738(p.px, px)) continue
            synchronized(book) { book.getOrPut(p.key) { Agg() }.add((px / p.px - 1.0) * 100.0) }
        }
        if (book.size > 2_000) synchronized(book) { book.entries.removeIf { it.value.n < 3 } }
    }

    /** The graded move after a decision (mean %, n), or null. */
    fun gradeFor8031(key: String): Pair<Double, Int>? = book[key]?.let { if (it.n > 0) (it.sum / it.n) to it.n else null }

    fun statusLine(): String {
        val top = synchronized(book) { book.entries.sortedByDescending { it.value.n }.take(10).joinToString(" · ") { it.value.line(it.key) } }
        return "native[named=${nativeNamed.get()} labelled=${nativeLabelled.get()} backedOff=${nativeBackedOff.get()}] decisions[pending=${pending.size} graded=${book.values.sumOf { it.n }}] " +
            "read=after HOLD up=holding paid, after SELL up=left money | ${top.ifBlank { "-" }}"
    }
}
