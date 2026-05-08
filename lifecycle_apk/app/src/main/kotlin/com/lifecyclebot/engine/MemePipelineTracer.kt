package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.9.495z49 — operator P0 (live meme execution audit).
 *
 * The operator's 17:03 forensic export proved candidates pass V3|ELIGIBILITY
 * and reach the live pipeline but never produce a buy. There is no single
 * structured tag to follow a meme candidate from V3 PASS → live signature,
 * so silent kills are invisible.
 *
 * MemePipelineTracer emits exactly one structured line per stage transition
 * for a single mint/symbol pair, with a stage tag and a rejection reason
 * when applicable. Every line is logged at INFO so it shows up in the
 * operator's exported log feed.
 *
 * Usage:
 *   MemePipelineTracer.stage("CANDIDATE_FOUND", mint, symbol, "scanner=...")
 *   MemePipelineTracer.blocked(mint, symbol, reason = "FDG_SCORE_FLOOR_28")
 *   MemePipelineTracer.stage("LIVE_QUEUE_ACCEPTED", mint, symbol, "size=0.05")
 *
 * Tag taxonomy (from operator spec):
 *   MEME_CANDIDATE_FOUND
 *   MEME_MODE_CLASSIFIED
 *   MEME_V3_ELIGIBLE
 *   MEME_FDG_START / MEME_FDG_PASS / MEME_FDG_REJECT
 *   MEME_LIVE_QUEUE_ATTEMPT / MEME_LIVE_QUEUE_ACCEPTED / MEME_LIVE_QUEUE_REJECTED
 *   MEME_EXECUTOR_START
 *   MEME_QUOTE_START / MEME_QUOTE_OK / MEME_QUOTE_FAILED
 *   MEME_TX_BUILD_START / MEME_TX_BUILD_OK / MEME_TX_BUILD_FAILED
 *   MEME_TX_SEND_OK_SIGNATURE
 *   MEME_CONFIRM_OK / MEME_CONFIRM_FAILED
 *   MEME_WALLET_DELTA_OK / MEME_WALLET_DELTA_FAILED
 *   MEME_POSITION_OPEN_TRACKING
 *   MEME_ENTRY_BLOCKED  (used for any silent kill below the V3 layer)
 *   MEME_LIVE_POSITION_COUNT / MEME_PAPER_POSITION_COUNT /
 *   MEME_SHADOW_POSITION_COUNT / MEME_POSITION_CAP_CHECK
 */
object MemePipelineTracer {

    private const val TAG = "MEME_PIPELINE"

    /** Per-mint counters so we can prove handoff is reaching each stage. */
    private val stageCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val blockReasonCounts = ConcurrentHashMap<String, AtomicInteger>()

    fun stage(tag: String, mint: String, symbol: String, detail: String = "") {
        stageCounts.computeIfAbsent(tag) { AtomicInteger(0) }.incrementAndGet()
        ErrorLogger.info(TAG,
            "MEME_$tag | $symbol | mint=${mint.take(8)}…${if (detail.isNotBlank()) " | $detail" else ""}")
    }

    fun blocked(mint: String, symbol: String, reason: String, detail: String = "") {
        stageCounts.computeIfAbsent("ENTRY_BLOCKED") { AtomicInteger(0) }.incrementAndGet()
        blockReasonCounts.computeIfAbsent(reason) { AtomicInteger(0) }.incrementAndGet()
        ErrorLogger.info(TAG,
            "MEME_ENTRY_BLOCKED | $symbol | mint=${mint.take(8)}… | reason=$reason${if (detail.isNotBlank()) " | $detail" else ""}")
    }

    /** Position-cap audit (operator spec item 4 — paper/shadow contamination). */
    fun positionCapCheck(
        mint: String,
        symbol: String,
        liveCount: Int,
        paperCount: Int,
        shadowCount: Int,
        liveCap: Int,
    ) {
        ErrorLogger.info(TAG,
            "MEME_POSITION_CAP_CHECK | $symbol | mint=${mint.take(8)}… | " +
            "live=$liveCount paper=$paperCount shadow=$shadowCount cap=$liveCap")
    }

    fun snapshot(): Map<String, Int> = stageCounts.mapValues { it.value.get() }
    fun blockReasons(): Map<String, Int> = blockReasonCounts.mapValues { it.value.get() }

    /** Test reset hook — never call from production code paths. */
    fun resetForTest() {
        stageCounts.clear()
        blockReasonCounts.clear()
    }
}
