package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8488 — live buy timing per step. The owner's target is a buy in about 2 s; 8484 reported only decision -> fill
 * (EntryChase7961, 3-21 s by lane), so the slow step was unknown. Every LIVE BUY phase LiveTradeLogStore logs is stamped
 * here; one buy's steps are timed from its first phase, and the time between consecutive phases is kept per transition
 * ("QUOTE_TRY->QUOTE_OK", ...) with start -> broadcast and broadcast -> confirmed. Measurement only: nothing is gated.
 */
object BuyStageTiming {
    private const val MAX_AGE_MS = 120_000L
    private const val MAX_TRACKS = 256

    private class Track(val startAtMs: Long) { var lastPhase = ""; var lastAtMs = startAtMs; var broadcastAtMs = 0L }
    private class Agg { var n = 0; var sumMs = 0L; var maxMs = 0L
        fun add(ms: Long) { n++; sumMs += ms; if (ms > maxMs) maxMs = ms } }

    private val tracks = ConcurrentHashMap<String, Track>()
    private val aggs = ConcurrentHashMap<String, Agg>()

    private val END = setOf("BUY_VERIFIED_LANDED", "BUY_FAILED", "BUY_PHANTOM", "BUY_RECONCILE_LANDED")

    /** LiveTradeLogStore.log, LIVE side=BUY only. */
    fun onPhase(mint: String, phase: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || !phase.startsWith("BUY_")) return
        val t0 = tracks[mint]
        val t = if (t0 == null || nowMs - t0.startAtMs > MAX_AGE_MS) {
            if (tracks.size > MAX_TRACKS) tracks.entries.removeIf { nowMs - it.value.startAtMs > MAX_AGE_MS }
            Track(nowMs).also { tracks[mint] = it }
        } else t0
        synchronized(t) {
            if (t.lastPhase.isNotEmpty() && t.lastPhase != phase) add("${short(t.lastPhase)}->${short(phase)}", nowMs - t.lastAtMs)
            if (phase == "BUY_BROADCAST" && t.broadcastAtMs == 0L) {
                t.broadcastAtMs = nowMs
                add("start->broadcast", nowMs - t.startAtMs)
            }
            if (phase == "BUY_CONFIRMED" && t.broadcastAtMs > 0L) add("broadcast->confirmed", nowMs - t.broadcastAtMs)
            t.lastPhase = phase; t.lastAtMs = nowMs
        }
        if (phase in END) tracks.remove(mint, t)
    }

    private fun short(p: String) = p.removePrefix("BUY_")

    private fun add(key: String, ms: Long) {
        if (ms < 0L) return
        val a = aggs.computeIfAbsent(key) { Agg() }
        synchronized(a) { a.add(ms) }
        if (aggs.size > 64) aggs.keys.take(8).forEach { k -> if (k != "start->broadcast" && k != "broadcast->confirmed") aggs.remove(k) }
    }

    /** (n, avg ms, max ms) of start -> broadcast; n = 0 when no live buy reached broadcast. */
    fun startToBroadcast(): Triple<Int, Long, Long> = aggs["start->broadcast"]?.let { a ->
        synchronized(a) { Triple(a.n, if (a.n > 0) a.sumMs / a.n else 0L, a.maxMs) } } ?: Triple(0, 0L, 0L)

    /** The slowest step by average (excluding the totals), e.g. "QUOTE_TRY->QUOTE_OK avg=1840ms". */
    fun slowestStep(): String {
        val e = aggs.entries.filter { it.key != "start->broadcast" && it.key != "broadcast->confirmed" && it.value.n > 0 }
            .maxByOrNull { it.value.sumMs / it.value.n } ?: return "-"
        return "${e.key} avg=${e.value.sumMs / e.value.n}ms"
    }

    /** Pure: a buy quote failure that more slippage cannot fix (quota / transport), so the slippage ladder stops. */
    fun quoteUnreachable(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("429") || m.contains("rate limit") || m.contains("too many requests") || m.contains("timeout") ||
            m.contains("timed out") || m.contains("unknownhost") || m.contains("connection reset")
    }

    fun statusLine(): String {
        val parts = aggs.entries.filter { it.value.n > 0 }.sortedByDescending { it.value.sumMs / it.value.n }.take(10)
            .joinToString(" ") { "${it.key}[n=${it.value.n} avg=${it.value.sumMs / it.value.n} max=${it.value.maxMs}]" }
        return "buyStages[${parts.ifBlank { "-" }}]"
    }
}
