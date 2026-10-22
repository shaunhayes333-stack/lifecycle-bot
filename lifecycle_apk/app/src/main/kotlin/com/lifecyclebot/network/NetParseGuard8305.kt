package com.lifecyclebot.network

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8305 §EXPENDABLE_PARSE — a market-data frame that cannot be afforded is dropped, never fatal.
 *
 * 5.0.8302 crashed (uptime 62 min) with OutOfMemoryError thrown on an OkHttp dispatcher thread inside
 * `JSONObject(String)` (JSONTokener.readObject recursion). OkHttp runs every websocket reader (and the SSE reader) on a
 * dispatcher thread; a listener that catches only Exception lets the Error escape, and an uncaught Error on any thread
 * kills the process — losing every open position's monitoring for the sake of one market frame.
 *
 * Every expendable stream (smart-money push, PumpPortal, DexScreener, curve / graduation / launch logs, Pyth prices)
 * parses through [parseObject8305]:
 *  - a frame longer than its stream's cap is dropped before parsing (counted; a JSON tree costs ~5-10x its text);
 *  - an OutOfMemoryError while parsing drops that frame (its half-built tree is garbage the moment we return), counts
 *    it and asks MemoryGuard7977 for an emergency hard trim, so the heap is freed before the next allocation fails.
 * Only market-data frames go through here. Nothing on a sell / fill / balance-proof path does: those keep their errors.
 */
object NetParseGuard8305 {

    /** Frame caps (characters). A transactionSubscribe jsonParsed full tx is ~5-60 KB; logs / account frames < 16 KB. */
    const val FRAME_CAP_TX_8305 = 512 * 1024
    const val FRAME_CAP_SMALL_8305 = 256 * 1024
    const val FRAME_CAP_WIDE_8305 = 2 * 1024 * 1024
    private const val STREAM_KEYS_MAX_8305 = 32

    private val oversize = AtomicLong(0L)
    private val parseOoms = AtomicLong(0L)
    private val oversizeBy = ConcurrentHashMap<String, AtomicLong>()
    private val oomBy = ConcurrentHashMap<String, AtomicLong>()

    /** Emergency trim hook (MemoryGuard7977 in the app; replaceable in tests). */
    @Volatile internal var onOom8305: (String) -> Unit = { stream ->
        try { com.lifecyclebot.engine.MemoryGuard7977.emergencyTrim8305("PARSE_OOM_$stream") } catch (_: Throwable) {}
    }

    /** Pure: may a frame of [length] characters be parsed under [cap]? */
    fun admit8305(length: Int, cap: Int): Boolean = length in 0..cap

    /**
     * [text] as a JSONObject, or null when it is over [cap], is not JSON, or could not be parsed for lack of heap
     * (the frame is dropped, counted, and an emergency trim requested).
     */
    fun parseObject8305(stream: String, text: String, cap: Int = FRAME_CAP_SMALL_8305): JSONObject? {
        if (!admit8305(text.length, cap)) { noteOversize8305(stream); return null }
        return try { guard8305(stream) { JSONObject(text) } } catch (_: org.json.JSONException) { null }
    }

    /** Run [block]; an OutOfMemoryError inside it is dropped, counted and answered with an emergency trim. */
    fun <T> guard8305(stream: String, block: () -> T): T? = try {
        block()
    } catch (_: OutOfMemoryError) {
        noteOom8305(stream)
        null
    }

    private fun noteOversize8305(stream: String) {
        oversize.incrementAndGet()
        counter8305(oversizeBy, stream).incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("NET_FRAME_OVERSIZE_DROPPED_8305") } catch (_: Throwable) {}
    }

    /** An expendable stream's handler caught OutOfMemoryError while processing a frame: count it and trim now. */
    fun dropOom8305(stream: String) = noteOom8305(stream)

    private fun noteOom8305(stream: String) {
        parseOoms.incrementAndGet()
        counter8305(oomBy, stream).incrementAndGet()
        try { onOom8305(stream) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("NET_PARSE_OOM_DROPPED_8305") } catch (_: Throwable) {}
    }

    private fun counter8305(m: ConcurrentHashMap<String, AtomicLong>, key: String): AtomicLong {
        m[key]?.let { return it }
        return m.computeIfAbsent(if (m.size >= STREAM_KEYS_MAX_8305) "other" else key) { AtomicLong(0L) }
    }

    fun parseOoms8305(): Long = parseOoms.get()
    fun oversize8305(): Long = oversize.get()

    fun line8305(): String {
        fun fmt(m: ConcurrentHashMap<String, AtomicLong>) = m.entries.sortedByDescending { it.value.get() }.take(4)
            .joinToString(",") { "${it.key}:${it.value.get()}" }
        return "frames8305[oversizeDropped=${oversize.get()}(${fmt(oversizeBy)}) parseOomDropped=${parseOoms.get()}(${fmt(oomBy)})]"
    }

    internal fun resetForTest8305() { oversize.set(0L); parseOoms.set(0L); oversizeBy.clear(); oomBy.clear() }
}
