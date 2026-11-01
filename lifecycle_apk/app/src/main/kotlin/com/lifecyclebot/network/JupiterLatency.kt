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

    /**
     * V5.0.8493 — what a failed quote says about Jupiter. 5.0.8492 live: jupiterLatency8489[n=12 fail=12 slow=true] while the
     * Jupiter health probe was HTTP 200 and QUOTE_TRY->FAILED averaged 1 ms. Those were the bot's OWN skips (backoff lockout,
     * observation yield) and Jupiter's fast "no route" answers, booked as Jupiter failures; the yield message even contains
     * "429", so every local skip re-armed the 30 s rate-limit window. Jupiter read slow forever and PumpPortal went first,
     * against the owner's Jupiter-first routing. Now: a local skip is no sample; a quote Jupiter answered (no route,
     * outAmount 0) is an answered sample (Jupiter is reachable and fast); only a real HTTP/timeout failure counts, and only
     * a real 429 from Jupiter arms the rate-limit window.
     */
    enum class Kind8493 { LOCAL_SKIP, ANSWERED, FAILED }

    /** Pure (V5.0.8493): classify a quote outcome. */
    fun kindOf8493(success: Boolean, error: String?): Kind8493 {
        if (success) return Kind8493.ANSWERED
        val e = error.orEmpty()
        if (e.contains("yielded after 429") || e.contains("GET skipped") || e.contains("backoff lockout")) return Kind8493.LOCAL_SKIP
        if (e.contains("quote error") || e.contains("order error") || e.contains("outAmount=0")) return Kind8493.ANSWERED
        return Kind8493.FAILED
    }

    /** Pure (V5.0.8493): a genuine rate limit from Jupiter (never the bot's own yield message). */
    fun realRateLimit8493(error: String?): Boolean {
        val e = error.orEmpty()
        return !e.contains("yielded after 429") && (e.contains("429") || e.contains("Too Many", ignoreCase = true))
    }

    @Synchronized fun record(elapsedMs: Long, success: Boolean, error: String? = null, nowMs: Long = System.currentTimeMillis()) {
        val kind = kindOf8493(success, error)
        if (kind == Kind8493.LOCAL_SKIP) return
        ms[head] = elapsedMs.coerceAtLeast(0L); ok[head] = kind == Kind8493.ANSWERED; at[head] = nowMs
        head = (head + 1) % RING
        if (n < RING) n++
        if (kind == Kind8493.FAILED && realRateLimit8493(error)) last429AtMs = nowMs
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
