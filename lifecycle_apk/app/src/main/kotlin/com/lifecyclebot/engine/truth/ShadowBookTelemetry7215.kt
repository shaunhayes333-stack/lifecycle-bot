package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7215 §THE_PAPER_BOOK_THAT_RUNS_IN_LIVE_HAD_NO_TELEMETRY.
 *
 * Operator directive #4 asks to "restore paper execution independently of the
 * live governor HOLD", and acceptance test J is "run >=20 clean paper closes
 * before judging strategy quality".
 *
 * Both are already architecturally provided for, and neither was measurable.
 * ExecutionRouteGuard hard-blocks paperBuy() while the runtime is LIVE —
 * deliberately, so a paper fill can never touch the canonical ledger, the
 * paper wallet or EXEC_PAPER_BUY_OK — and routes shadow learning to
 * Executor.runShadowPaperBuy() instead, which V5.0.6073 made ALWAYS-ON with no
 * toggle. So a paper book DOES run alongside live, and it is independent of
 * the governor: the parallel shadow open sits after liveBuy() returns, so it
 * still fires on every one of the 69 entries the governor's HOLD aborted in
 * the operator's 5.0.7212 snapshot.
 *
 * What was missing is any way to know that. That snapshot's PAPER execution
 * block reads FDG 0/0, BUY ok 0, SELL ok 0, paperRows=0 — all correct, and all
 * about the BLOCKED normal-paper route — while the word "shadow" appears
 * nowhere in the report in connection with this book. No opens, no closes, no
 * win rate, no P&L. Acceptance J could not be evaluated because the number it
 * asks for was never recorded.
 *
 * Worse, three of the book's exits destroyed evidence silently:
 *   - the MAX_SHADOW_POSITIONS eviction removes the OLDEST open shadow
 *     position WITHOUT closing it, so that trade never reaches
 *     brain.learnFromTrade and its outcome is lost outright;
 *   - a same-mint open is skipped;
 *   - a zero price is skipped.
 * None had a counter, so a book that opens twenty and then evicts one per new
 * candidate forever read exactly like a healthy one.
 *
 * This is a counter surface only. It makes no decision, gates nothing, and
 * holds no position state — the book itself stays in Executor, where
 * ShadowPosition lives. It is a separate object rather than fields on Executor
 * because PipelineHealthCollector has no handle on the service's Executor
 * instance, and reaching for one would couple the report to the runtime's
 * lifecycle for the sake of printing six integers.
 */
object ShadowBookTelemetry7215 {

    private val opens = AtomicLong(0L)
    private val skipDuplicate = AtomicLong(0L)
    private val skipNoPrice = AtomicLong(0L)
    private val evictedUnclosed = AtomicLong(0L)
    private val closes = AtomicLong(0L)
    private val wins = AtomicLong(0L)
    private val losses = AtomicLong(0L)
    private val pnlPctSumMilli = AtomicLong(0L)

    /** Current open shadow slots, published by the book so the report can read it. */
    @Volatile private var openNow: Int = 0

    /** Capacity, published so `open=N/cap` is readable without hard-coding it here. */
    @Volatile private var capacity: Int = 0

    fun onOpen7215(reason: String, openCount: Int, cap: Int) {
        opens.incrementAndGet()
        openNow = openCount
        capacity = cap
        try {
            PipelineHealthCollector.labelInc("SHADOW_OPEN_7215_${reason.substringBefore(':').take(24)}")
        } catch (_: Throwable) {}
    }

    fun onSkipDuplicate7215() {
        skipDuplicate.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SHADOW_OPEN_SKIPPED_DUPLICATE_MINT_7215") } catch (_: Throwable) {}
    }

    fun onSkipNoPrice7215() {
        skipNoPrice.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SHADOW_OPEN_SKIPPED_NO_PRICE_7215") } catch (_: Throwable) {}
    }

    /**
     * An open shadow position was dropped to make room, WITHOUT being closed.
     * brain.learnFromTrade never sees it, so this is learning thrown away and
     * it directly competes with acceptance J's close count.
     */
    fun onEvictedUnclosed7215(mint: String, symbol: String, ageMs: Long, cap: Int) {
        evictedUnclosed.incrementAndGet()
        capacity = cap
        try {
            PipelineHealthCollector.labelInc("SHADOW_POSITION_EVICTED_UNCLOSED_7215")
            ForensicLogger.lifecycle(
                "SHADOW_POSITION_EVICTED_UNCLOSED_7215",
                "mint=${mint.take(10)} sym=$symbol ageMs=$ageMs cap=$cap " +
                    "note=no_learnFromTrade_this_outcome_is_lost",
            )
        } catch (_: Throwable) {}
    }

    /**
     * A complete shadow round trip on observed prices, with the learner already
     * fed. This is what "a clean paper close" means, and what acceptance J
     * counts.
     */
    fun onClose7215(exitReason: String, isWin: Boolean, pnlPct: Double, openCount: Int) {
        closes.incrementAndGet()
        if (isWin) wins.incrementAndGet() else losses.incrementAndGet()
        if (pnlPct.isFinite()) pnlPctSumMilli.addAndGet((pnlPct * 1000.0).toLong())
        openNow = openCount
        try {
            PipelineHealthCollector.labelInc("SHADOW_CLOSE_7215_${exitReason.take(24)}")
            PipelineHealthCollector.labelInc(if (isWin) "SHADOW_CLOSE_WIN_7215" else "SHADOW_CLOSE_LOSS_7215")
        } catch (_: Throwable) {}
    }

    fun statusLine7215(): String {
        val c = closes.get()
        val w = wins.get()
        val wr = if (c > 0L) w * 100.0 / c else 0.0
        val avg = if (c > 0L) (pnlPctSumMilli.get() / 1000.0) / c else 0.0
        val capTxt = if (capacity > 0) capacity.toString() else "?"
        return "open=$openNow/$capTxt opens=${opens.get()} closes=$c wins=$w losses=${losses.get()} " +
            "wr=${"%.1f".format(wr)}% avgPnlPct=${"%.2f".format(avg)} " +
            "skipDup=${skipDuplicate.get()} skipNoPrice=${skipNoPrice.get()} " +
            "evictedUnclosed=${evictedUnclosed.get()} " +
            "acceptanceJ=${if (c >= 20L) "MET" else "NOT_MET_needs_20_closes"}"
    }

    internal fun resetForTest7215() {
        opens.set(0L); skipDuplicate.set(0L); skipNoPrice.set(0L); evictedUnclosed.set(0L)
        closes.set(0L); wins.set(0L); losses.set(0L); pnlPctSumMilli.set(0L)
        openNow = 0; capacity = 0
    }
}
