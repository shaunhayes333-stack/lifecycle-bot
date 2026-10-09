package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7876 — per-stage live exit timing.
 *
 * 5.0.7875 measured trigger -> broadcast only end to end: NORMAL 4,134 ms,
 * HARD_STOP 21,559 ms, CATASTROPHIC 34,314 ms (SLA 3 s), with no way to say
 * whether the time went to scheduling (trigger -> first SELL_START), to
 * re-dispatch after a failed attempt, or to balance / quote / build inside an
 * attempt. Every live sell route already logs its phases through
 * LiveTradeLogStore; this records, per stop class, the time from the trigger to
 * the first SELL_START (queue), the time between consecutive phases inside each
 * attempt, the gap between attempts, the attempts needed to reach a broadcast,
 * and broadcast -> chain confirmation.
 * Measurement only: no exit is admitted, refused, delayed or re-routed here.
 */
object ExitStageTiming7876 {
    private class Agg { var n = 0L; var sumMs = 0L; var maxMs = 0L
        fun add(ms: Long) { n++; sumMs += ms; if (ms > maxMs) maxMs = ms }
        fun avg() = if (n > 0) sumMs / n else 0L
    }

    private class Track(val cls: StopLatencyClasses6464.Class, val triggerAtMs: Long) {
        var lastPhase: String = "TRIGGER"
        var lastAtMs: Long = triggerAtMs
        var attempts = 0
        var lastStartAtMs = 0L
        var broadcastAtMs = 0L
    }

    private const val MAX_AGE_MS = 10L * 60_000L
    private val tracks = ConcurrentHashMap<String, Track>()
    private val aggs = ConcurrentHashMap<String, Agg>()   // "CLASS|stage" -> agg

    // V5.0.7948 — a sell that already broadcast is awaiting its chain outcome for
    // up to this long (verifySell 60 s + wallet polling). Re-requests in that
    // window are the hot loop / sweeps re-asking an exit already on the wire,
    // not a new condition waiting to be sold: they must not open a new queue
    // sample (5.0.7947 TRAILING_STOP queue max 204,037 ms).
    private const val AWAIT_OUTCOME_MS_7948 = 90_000L

    /**
     * Stamp the trigger (earliest per mint wins while the track is live).
     * V5.0.7948 — returns false when the stamp was ignored because this mint's
     * sell is already broadcast and awaiting its outcome.
     */
    fun onTrigger(mint: String, cls: StopLatencyClasses6464.Class, atMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank() || atMs <= 0L) return false
        val cur = tracks[mint]
        if (cur != null && nowMs - cur.triggerAtMs <= MAX_AGE_MS && cur.broadcastAtMs == 0L) return true
        if (cur != null && cur.broadcastAtMs > 0L && nowMs - cur.broadcastAtMs <= AWAIT_OUTCOME_MS_7948) return false
        tracks[mint] = Track(cls, atMs.coerceAtMost(nowMs))
        if (tracks.size > 500) tracks.entries.removeIf { nowMs - it.value.triggerAtMs > MAX_AGE_MS }
        return true
    }

    /**
     * V5.0.7948 — a deliberate deferral / hold / veto withdrew the trigger: the
     * exit was not waiting to sell, so its track must not survive to time the
     * hold as queue. Only a track that has not reached SELL_START is dropped.
     */
    fun withdrawUndispatched7948(mint: String) {
        val t = tracks[mint] ?: return
        if (synchronized(t) { t.attempts == 0 }) tracks.remove(mint, t)
    }

    /**
     * V5.0.7948 — the mint was answered without (another) broadcast: position
     * closed, refused as fatal, or terminally confirmed. Any track not awaiting a
     * broadcast outcome is dropped so the next position on this mint starts clean.
     */
    fun closeUnbroadcast7948(mint: String) {
        val t = tracks[mint] ?: return
        if (synchronized(t) { t.broadcastAtMs == 0L }) tracks.remove(mint, t)
    }

    private fun add(cls: StopLatencyClasses6464.Class, stage: String, ms: Long) {
        if (ms < 0L) return
        val a = aggs.computeIfAbsent("${cls.name}|$stage") { Agg() }
        synchronized(a) { a.add(ms) }
    }

    /** A live SELL phase for [mint]. */
    fun onPhase(mint: String, phase: String, nowMs: Long = System.currentTimeMillis()) {
        val t = tracks[mint] ?: return
        if (nowMs - t.triggerAtMs > MAX_AGE_MS) { tracks.remove(mint, t); return }
        synchronized(t) {
            when (phase) {
                "SELL_START" -> {
                    if (t.attempts == 0) add(t.cls, "queue(trigger->start)", nowMs - t.triggerAtMs)
                    else add(t.cls, "redispatchGap", nowMs - t.lastStartAtMs)
                    t.attempts++
                    t.lastStartAtMs = nowMs
                }
                "SELL_CONFIRMED", "SELL_TX_CONFIRMED" -> {
                    if (t.broadcastAtMs > 0L) {
                        add(t.cls, "broadcast->confirm", nowMs - t.broadcastAtMs)
                        tracks.remove(mint, t)
                    }
                    return
                }
                else -> if (t.broadcastAtMs == 0L && t.attempts > 0) add(t.cls, "${t.lastPhase}->$phase", nowMs - t.lastAtMs)
            }
            if (phase == "SELL_BROADCAST" && t.broadcastAtMs == 0L) {
                t.broadcastAtMs = nowMs
                add(t.cls, "attemptsToBroadcast", t.attempts.toLong())
            }
            t.lastPhase = phase
            t.lastAtMs = nowMs
        }
    }

    /** Per class: queue, re-dispatch gap, attempts, and the three slowest in-attempt stages. */
    fun statusLine(): String {
        val byClass = aggs.entries.groupBy { it.key.substringBefore('|') }
        if (byClass.isEmpty()) return "none"
        return byClass.entries.sortedBy { it.key }.joinToString(" ; ") { (cls, rows) ->
            fun one(stage: String) = rows.firstOrNull { it.key.endsWith("|$stage") }?.value
            val queue = one("queue(trigger->start)")
            val gap = one("redispatchGap")
            val att = one("attemptsToBroadcast")
            val conf = one("broadcast->confirm")
            val inner = rows.filter { it.key.substringAfter('|').contains("->") &&
                    !it.key.endsWith("queue(trigger->start)") && !it.key.endsWith("broadcast->confirm") }
                .sortedByDescending { synchronized(it.value) { it.value.sumMs } }.take(3)
                .joinToString(",") { "${it.key.substringAfter('|')}=avg${it.value.avg()}/max${it.value.maxMs}ms" }
            "$cls[queue=avg${queue?.avg() ?: 0}/max${queue?.maxMs ?: 0}ms redispatchGap=avg${gap?.avg() ?: 0}ms(n=${gap?.n ?: 0}) " +
                "attempts=avg${att?.avg() ?: 0}/max${att?.maxMs ?: 0} slowest={${inner.ifBlank { "-" }}} " +
                "broadcast->confirm=avg${conf?.avg() ?: 0}/max${conf?.maxMs ?: 0}ms]"
        }
    }
}
