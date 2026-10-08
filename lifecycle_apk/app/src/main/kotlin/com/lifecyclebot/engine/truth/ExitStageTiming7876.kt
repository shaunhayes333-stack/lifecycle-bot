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

    /** Stamp the trigger (earliest per mint wins while the track is live). */
    fun onTrigger(mint: String, cls: StopLatencyClasses6464.Class, atMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || atMs <= 0L) return
        val cur = tracks[mint]
        if (cur != null && nowMs - cur.triggerAtMs <= MAX_AGE_MS && cur.broadcastAtMs == 0L) return
        tracks[mint] = Track(cls, atMs.coerceAtMost(nowMs))
        if (tracks.size > 500) tracks.entries.removeIf { nowMs - it.value.triggerAtMs > MAX_AGE_MS }
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
