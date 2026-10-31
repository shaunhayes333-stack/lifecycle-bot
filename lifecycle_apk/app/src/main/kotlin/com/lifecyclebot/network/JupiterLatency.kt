package com.lifecyclebot.network

/**
 * V5.0.8489 — how fast Jupiter is answering quotes right now. 8488 live: a normal exit's quote stage averaged 4.1 s
 * (max 11.1 s) and a buy's 2.0 s (max 8.7 s) while PumpPortal-built sells landed 6/6. Every JupiterApi.getQuote /
 * getQuoteWithTaker call is timed here; [slow] is true when the recent median quote took longer than [SLOW_MEDIAN_MS],
 * when most recent quotes failed, or a 429 was seen in the last [RATE_LIMIT_WINDOW_MS]. While slow, routes that build
 * locally for the coin's own venue (PumpPortal: curve / PumpSwap) go first; Jupiter returns first when it is fast again.
 */
object JupiterLatency {
    private const val SLOW_MEDIAN_MS = 1_500L
    private const val RATE_LIMIT_WINDOW_MS = 30_000L
    private const val RING = 12
    private const val MIN_SAMPLES = 4

    private val ms = LongArray(RING)
    private val ok = BooleanArray(RING)
    private val at = LongArray(RING)
    private var n = 0
    private var head = 0
    @Volatile private var last429AtMs = 0L

    @Synchronized fun record(elapsedMs: Long, success: Boolean, error: String? = null, nowMs: Long = System.currentTimeMillis()) {
        ms[head] = elapsedMs.coerceAtLeast(0L); ok[head] = success; at[head] = nowMs
        head = (head + 1) % RING
        if (n < RING) n++
        if (!success && error != null && (error.contains("429") || error.contains("Too Many", ignoreCase = true))) last429AtMs = nowMs
    }

    /** Pure: slow given recent [samples] (elapsed ms, success), and a 429 [msSince429] ago. */
    fun slowOf(samples: List<Pair<Long, Boolean>>, msSince429: Long): Boolean {
        if (msSince429 in 0L until RATE_LIMIT_WINDOW_MS) return true
        if (samples.size < MIN_SAMPLES) return false
        val failures = samples.count { !it.second }
        if (failures * 2 > samples.size) return true
        val okMs = samples.filter { it.second }.map { it.first }.sorted()
        if (okMs.isEmpty()) return true
        return okMs[okMs.size / 2] > SLOW_MEDIAN_MS
    }

    @Synchronized private fun recent(nowMs: Long): List<Pair<Long, Boolean>> =
        (0 until n).map { (head - 1 - it + RING) % RING }.filter { nowMs - at[it] <= 5 * 60_000L }.map { ms[it] to ok[it] }

    fun slow(nowMs: Long = System.currentTimeMillis()): Boolean =
        slowOf(recent(nowMs), if (last429AtMs > 0L) nowMs - last429AtMs else Long.MAX_VALUE)

    fun statusLine(nowMs: Long = System.currentTimeMillis()): String {
        val r = recent(nowMs)
        val okMs = r.filter { it.second }.map { it.first }.sorted()
        return "jupiterLatency8489[n=${r.size} fail=${r.count { !it.second }} medianOkMs=${if (okMs.isEmpty()) "-" else okMs[okMs.size / 2]} slow=${slow(nowMs)}]"
    }
}
