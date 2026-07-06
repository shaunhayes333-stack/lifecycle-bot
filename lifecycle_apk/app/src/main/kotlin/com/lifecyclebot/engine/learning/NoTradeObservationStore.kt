package com.lifecyclebot.engine.learning

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1322 — NoTradeObservation forward-outcome writer (Build B).
 *
 * Operator directive §5: every FDG block / route-to-shadow / route-to-train
 * should still create a NO_TRADE_OBSERVATION row if the token has enough
 * market data. Forward-outcome windows: 30s / 60s / 180s / 300s / 900s.
 *
 * Stored per row:
 *   - signal context (mint, lane, scoreBand, score, conf, liqUsd, mcap, source, blockReason, verdictTag, signalTs, entryPrice)
 *   - forward samples per window: peakMovePct, maxDrawdownPct, liquidityChange,
 *     wouldHaveHitStop, wouldHaveHitTP, wouldHaveRugged, wouldHaveMigrated,
 *     wouldHaveBeenUntradable
 *
 * Rows are SHADOW/NO_TRADE — never enter realized PnL counters. They feed
 * the hypothesis engine (Build D) and the §12 acceptance test counters.
 *
 * Persistence is best-effort: latest N rows are kept in memory; periodic
 * compact JSON dumps go to LearningPersistence under key
 * "no_trade_observations_recent". Older rows fall off the ring.
 */
object NoTradeObservationStore {

    data class Row(
        val mint: String,
        val symbol: String,
        val lane: String,
        val scoreBand: String,
        val score: Int,
        val confidence: Int,
        val entryLiqUsd: Double,
        val entryMcapUsd: Double,
        val entryPrice: Double,
        val source: String,
        val blockReason: String,
        val verdictTag: String,
        val signalTs: Long,
        val samples: ConcurrentHashMap<Int, ForwardSample>,
    ) {
        fun ageMs(now: Long): Long = now - signalTs
        fun isComplete(): Boolean = samples.keys.containsAll(SAMPLE_WINDOWS_MS)
    }

    data class ForwardSample(
        val tsMs: Long,
        val price: Double,
        val liquidityUsd: Double,
        val peakMovePct: Double,
        val maxDrawdownPct: Double,
        val liquidityChange: Double,
        val wouldHaveHitStop: Boolean,
        val wouldHaveHitTP: Boolean,
        val wouldHaveRugged: Boolean,
        val wouldHaveMigrated: Boolean,
        val wouldHaveBeenUntradable: Boolean,
    )

    private const val RING_CAP = 5000
    private val ring = ConcurrentLinkedDeque<Row>()
    private val ringSize = AtomicInteger(0)
    private val rowsByMint = ConcurrentHashMap<String, Row>()
    private val totalRowsWritten = AtomicLong(0L)
    private val totalSamplesAttached = AtomicLong(0L)

    /** Sample windows in milliseconds. */
    private val SAMPLE_WINDOWS_MS = listOf(30_000, 60_000, 180_000, 300_000, 900_000)
    private const val ASSUMED_STOP_PCT = -8.0
    private const val ASSUMED_TP_PCT   = 12.0
    private const val RUG_THRESHOLD_PCT = -60.0
    private const val MIGRATED_LIQ_DROP_PCT = -90.0
    private const val UNTRADABLE_LIQ_USD = 500.0

    /**
     * Record a no-trade observation row from a FDG route or block.
     * Returns true if a row was created. Returns false if essential
     * market-data fields are missing (entryPrice <= 0 or mint blank).
     */
    fun recordBlock(
        mint: String,
        symbol: String,
        lane: String,
        scoreBand: String,
        score: Int,
        confidence: Int,
        entryLiqUsd: Double,
        entryMcapUsd: Double,
        entryPrice: Double,
        source: String,
        blockReason: String,
        verdictTag: String,
    ): Boolean {
        if (mint.isBlank() || entryPrice <= 0.0) return false
        val row = Row(
            mint = mint,
            symbol = symbol.take(24),
            lane = lane.uppercase().take(24),
            scoreBand = scoreBand.uppercase().take(12),
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            entryLiqUsd = entryLiqUsd,
            entryMcapUsd = entryMcapUsd,
            entryPrice = entryPrice,
            source = source.take(24),
            blockReason = blockReason.take(80),
            verdictTag = verdictTag.take(32),
            signalTs = System.currentTimeMillis(),
            samples = ConcurrentHashMap(),
        )
        ring.addLast(row)
        rowsByMint[mint] = row
        if (ringSize.incrementAndGet() > RING_CAP) {
            val removed = ring.pollFirst()
            if (removed != null && rowsByMint[removed.mint] === removed) {
                rowsByMint.remove(removed.mint)
            }
            ringSize.decrementAndGet()
        }
        totalRowsWritten.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("NO_TRADE_OBSERVATION_WRITTEN")
            PipelineHealthCollector.labelInc("NO_TRADE_OBSERVATION_LANE|${row.lane}")
        } catch (_: Throwable) {}
        return true
    }

    /**
     * Best-effort forward-outcome attachment. Called from anywhere the
     * bot already has a fresh (price, liq) tick. Picks the matching
     * sample window based on `now - signalTs` and writes the row.
     *
     * Idempotent per (mint, window).
     */
    fun tick(mint: String, currentPrice: Double, currentLiqUsd: Double) {
        if (mint.isBlank() || currentPrice <= 0.0) return
        val row = rowsByMint[mint] ?: return
        val now = System.currentTimeMillis()
        val age = now - row.signalTs
        // Find the largest window we've crossed and haven't sampled yet.
        for (w in SAMPLE_WINDOWS_MS) {
            if (age >= w && !row.samples.containsKey(w)) {
                val movePct = com.lifecyclebot.engine.OpenPnlSanity.inspect(row.entryPrice, currentPrice, context = "NoTradeObservationStore_6038", emit = true).takeIf { it.ok }?.pnlPct ?: 0.0
                val priorSamples = row.samples.values
                val priorPeak = priorSamples.maxOfOrNull { it.peakMovePct } ?: 0.0
                val priorDD = priorSamples.minOfOrNull { it.maxDrawdownPct } ?: 0.0
                val peakMovePct = maxOf(priorPeak, movePct)
                val maxDrawdownPct = minOf(priorDD, movePct)
                val liqChange = if (row.entryLiqUsd > 0.0)
                    (currentLiqUsd - row.entryLiqUsd) / row.entryLiqUsd * 100.0 else 0.0
                val s = ForwardSample(
                    tsMs = now,
                    price = currentPrice,
                    liquidityUsd = currentLiqUsd,
                    peakMovePct = peakMovePct,
                    maxDrawdownPct = maxDrawdownPct,
                    liquidityChange = liqChange,
                    wouldHaveHitStop = maxDrawdownPct <= ASSUMED_STOP_PCT,
                    wouldHaveHitTP = peakMovePct >= ASSUMED_TP_PCT,
                    wouldHaveRugged = maxDrawdownPct <= RUG_THRESHOLD_PCT,
                    wouldHaveMigrated = liqChange <= MIGRATED_LIQ_DROP_PCT,
                    wouldHaveBeenUntradable = currentLiqUsd > 0.0 && currentLiqUsd < UNTRADABLE_LIQ_USD,
                )
                row.samples[w] = s
                totalSamplesAttached.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("NO_TRADE_FWD_SAMPLE_${w / 1000}s")
                } catch (_: Throwable) {}
                // V5.0.6129 — no-paid-probe reintroduction proof. If a blocked/retraining
                // candidate later shows a clean +TP move without assumed stop/rug/untradable
                // failure, feed that counterfactual proof back into LanePolicy. This lets
                // lanes pivot/recover from shadow evidence instead of buying the same toxic
                // setup smaller. It never opens the token retroactively and never bypasses
                // FDG hard safety; it only changes the future lane-local policy state.
                if (s.wouldHaveHitTP && !s.wouldHaveHitStop && !s.wouldHaveRugged && !s.wouldHaveBeenUntradable) {
                    try {
                        LanePolicy.noteImprovement(row.lane, row.scoreBand)
                        PipelineHealthCollector.labelInc("NO_TRADE_COUNTERFACTUAL_REINTRO_6129")
                        PipelineHealthCollector.labelInc("NO_TRADE_COUNTERFACTUAL_REINTRO_6129|${row.lane.uppercase().take(24)}")
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    fun recentRows(limit: Int = 200): List<Row> = ring.toList().takeLast(limit)
    fun completeRows(limit: Int = 200): List<Row> = ring.toList().filter { it.isComplete() }.takeLast(limit)
    fun totalRows(): Long = totalRowsWritten.get()
    fun totalSamples(): Long = totalSamplesAttached.get()

    fun snapshot(): Snapshot = Snapshot(
        totalRowsWritten = totalRowsWritten.get(),
        totalSamplesAttached = totalSamplesAttached.get(),
        ringSize = ringSize.get(),
    )

    data class Snapshot(
        val totalRowsWritten: Long,
        val totalSamplesAttached: Long,
        val ringSize: Int,
    )

    /**
     * Persist a coalesced JSON of the most recent N rows. Best-effort.
     * The hypothesis engine (Build D) reads this on startup to seed
     * its variant store with observed forward-outcome rates.
     */
    fun persistCoalescedRows() {
        try {
            val rows = recentRows(500)
            val sb = StringBuilder("[")
            for ((i, r) in rows.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append("""{"m":"${r.mint.take(16)}","s":"${r.symbol}","l":"${r.lane}","b":"${r.scoreBand}","sc":${r.score},"c":${r.confidence},"liq":${r.entryLiqUsd},"mc":${r.entryMcapUsd},"ep":${r.entryPrice},"src":"${r.source}","br":"${r.blockReason}","v":"${r.verdictTag}","t":${r.signalTs},"sn":${r.samples.size}}""")
            }
            sb.append("]")
            LearningPersistence.save("no_trade_observations_recent", sb.toString())
        } catch (e: Throwable) {
            ErrorLogger.debug("NoTradeObservation", "persist error: ${e.message}")
        }
    }
}
