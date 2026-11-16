package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8821 §EVERY_PAPER_COIN_KEEPS_A_PRICE — owner: "the setups were built off data with bugs". Report bc4c42e1 (5.0.8820,
 * after the clean rebuild) shows the biggest one still live: the paper book (MemePaperTrader8544) loses the price of most
 * of the coins it holds and closes them LOST_MARK — the remainder valued flat, outside every lane record:
 *   CASHGEN  LOST_MARK n254 of 483 closes   EXPRESS LOST_MARK n374 of 834 (3 of them >= +100 % before the mark was lost)
 *   CORE n63, FAM_GRADUATION_RETEST n16, ...
 * The book's marks are the tick price (watchlist coins only), the labeler, an active bonding curve, the tape and a
 * constant-product pool read (OnChainDepth8038: "concentrated venues never price"). A coin that graduated off its curve,
 * or trades on a concentrated pool (Meteora DLMM, Raydium CLMM, Orca), or left the watchlist, had none of them. The lane
 * proof counts PRICED closes only, so every lane was judged on its stopped losers while its runners and its flat holds were
 * set aside: the setups were proven, and refused, on a biased sample.
 *
 * This asks Jupiter's price surface (KeylessPriceSources6996.jupiterBatch: the app's key, then keyless; 50 mints a call)
 * for every coin a paper or shadow book wants priced and has no observation under [STALE_MS] for, on one daemon worker,
 * at most one batch per [PASS_MS]. The result is one more observation in ShadowMark8360.observedTimed8372 (source JUP),
 * aged like every other one; the book's jump guard (acceptMark8559) still applies.
 */
object BookPrice8821 {
    const val SRC_JUP_8821 = "JUP"
    internal const val STALE_MS = 30_000L
    internal const val PASS_MS = 20_000L
    private const val MAX_WANTED = 400
    private const val MAX_PER_PASS = 100

    private class Mark(val priceUsd: Double, val atMs: Long)
    private val marks = ConcurrentHashMap<String, Mark>()
    private val wanted = ConcurrentHashMap<String, Long>()       // mint -> wanted until
    private val passes = AtomicLong(0L)
    private val asked = AtomicLong(0L)
    private val priced = AtomicLong(0L)
    private val unpriced = AtomicLong(0L)
    @Volatile private var started = false
    /** Test seam: the batch fetcher (mints -> USD prices). */
    @Volatile internal var fetch8821: (List<String>) -> Map<String, Double> = { com.lifecyclebot.network.KeylessPriceSources6996.jupiterBatch(it) }

    /** A book wants [mint] priced until [untilMs]. */
    fun want8821(mint: String, untilMs: Long) {
        if (mint.isBlank()) return
        if (wanted.size >= MAX_WANTED && !wanted.containsKey(mint)) return
        wanted[mint] = maxOf(untilMs, wanted[mint] ?: 0L)
        ensureStarted()
    }

    /** The Jupiter mark of [mint] observed within [maxAgeMs] of [nowMs], or null. */
    fun observation8821(mint: String, nowMs: Long, maxAgeMs: Long): ShadowMark8360.Observation8372? {
        val m = marks[mint] ?: return null
        return if (nowMs - m.atMs in 0L..maxAgeMs) ShadowMark8360.Observation8372(m.priceUsd, m.atMs, SRC_JUP_8821) else null
    }

    /**
     * One pass: the wanted coins with no observation under [STALE_MS] (other sources included, via [hasFresh]) are asked in
     * one batch; expired wants are dropped. Returns how many were asked.
     */
    internal fun pass8821(nowMs: Long, hasFresh: (String) -> Boolean): Int {
        passes.incrementAndGet()
        wanted.entries.removeIf { it.value < nowMs }
        marks.entries.removeIf { nowMs - it.value.atMs > 10 * 60_000L && !wanted.containsKey(it.key) }
        val ask = wanted.keys.filter { m -> !hasFresh(m) && (marks[m]?.let { nowMs - it.atMs >= STALE_MS } ?: true) }.take(MAX_PER_PASS)
        if (ask.isEmpty()) return 0
        asked.addAndGet(ask.size.toLong())
        val got = try { fetch8821(ask) } catch (_: Throwable) { emptyMap() }
        for (m in ask) {
            val px = got[m]
            if (px != null && px.isFinite() && px > 0.0) { marks[m] = Mark(px, nowMs); priced.incrementAndGet() } else unpriced.incrementAndGet()
        }
        return ask.size
    }

    private fun ensureStarted() {
        if (started || com.lifecyclebot.AATEApp.appContextOrNull() == null) return
        synchronized(this) { if (started) return; started = true }
        try {
            Thread({
                while (true) {
                    try {
                        val now = System.currentTimeMillis()
                        pass8821(now) { m -> ShadowMark8360.observedOtherThan8821(m, now, STALE_MS) }
                        Thread.sleep(PASS_MS)
                    } catch (_: InterruptedException) { return@Thread } catch (_: Throwable) {
                        try { Thread.sleep(PASS_MS) } catch (_: InterruptedException) { return@Thread }
                    }
                }
            }, "book-price-8821").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        } catch (_: Throwable) {}
    }

    fun statusLine8821(): String = "bookPrice8821[wanted=${wanted.size} marks=${marks.size} passes=${passes.get()} asked=${asked.get()} " +
        "priced=${priced.get()} unpriced=${unpriced.get()} rule=Jupiter price for book coins with no observation under ${STALE_MS / 1000}s]"

    internal fun resetForTest8821() { marks.clear(); wanted.clear(); passes.set(0); asked.set(0); priced.set(0); unpriced.set(0) }
}
