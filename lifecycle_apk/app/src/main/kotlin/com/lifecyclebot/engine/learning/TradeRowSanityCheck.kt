package com.lifecyclebot.engine.learning

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.data.Trade
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1325 — Corrupt Row Quarantine (Build E).
 *
 * Operator directive §8: do NOT block learning because a trade lost.
 * Quarantine rows only if data is invalid. Bad-but-valid trades remain
 * valuable training data.
 *
 * Quarantine reasons (per operator's explicit list):
 *   DUPLICATE_CLOSE, IMPOSSIBLE_PNL, MISSING_ENTRY_PRICE,
 *   MISSING_EXIT_PRICE, BAD_DECIMALS, MISMATCHED_TRADE_ID,
 *   FAKE_DUPLICATE_SELL, SL_LABEL_POSITIVE_PNL_MISMATCH,
 *   PAPER_PRICE_CLAMP_UNMARKED, MODE_MISMATCH, BOT_ID_MISMATCH.
 *
 * Quarantined rows STAY in the journal — they just don't enter
 * AdaptiveLearningEngine / EducationSubLayerAI / LayerVoteStore /
 * StrategyVariantStore aggregations.
 */
object TradeRowSanityCheck {

    enum class QuarantineReason(val tag: String) {
        DUPLICATE_CLOSE("DUPLICATE_CLOSE"),
        IMPOSSIBLE_PNL("IMPOSSIBLE_PNL"),
        MISSING_ENTRY_PRICE("MISSING_ENTRY_PRICE"),
        MISSING_EXIT_PRICE("MISSING_EXIT_PRICE"),
        BAD_DECIMALS("BAD_DECIMALS"),
        MISMATCHED_TRADE_ID("MISMATCHED_TRADE_ID"),
        FAKE_DUPLICATE_SELL("FAKE_DUPLICATE_SELL"),
        SL_LABEL_POSITIVE_PNL_MISMATCH("SL_LABEL_POSITIVE_PNL_MISMATCH"),
        PAPER_PRICE_CLAMP_UNMARKED("PAPER_PRICE_CLAMP_UNMARKED"),
        MODE_MISMATCH("MODE_MISMATCH"),
        BOT_ID_MISMATCH("BOT_ID_MISMATCH"),
        PAPER_ROW_CORRUPT("PAPER_ROW_CORRUPT"),
        OK("OK"),
    }

    private val quarantineCounts = ConcurrentHashMap<String, AtomicLong>()
    private val seenTradeIds = ConcurrentHashMap<String, Long>()
    private val passedCount = AtomicLong(0L)

    /**
     * Inspect a trade row before it enters learning aggregations.
     * Returns OK to admit; any other reason quarantines the row.
     *
     * Trade shape:
     *   - side: BUY | SELL
     *   - mode: paper | live
     *   - sol:  position size in SOL
     *   - price: per-row price (entry on BUY, exit on SELL)
     *   - pnlSol: realized PnL for SELL rows
     *   - pnlPct: realized PnL pct for SELL rows
     *   - reason: exit/entry reason string
     *   - mint:  token mint
     */
    fun inspect(t: Trade): QuarantineReason {
        val paperVerdict = com.lifecyclebot.engine.PaperLearningSanity.inspect(t)
        if (!paperVerdict.ok) return record(QuarantineReason.PAPER_ROW_CORRUPT, t, paperVerdict.reason)

        // 1. Duplicate row id (within recent window).
        val key = "${t.mint}|${t.ts}|${t.side}"
        val prev = seenTradeIds.put(key, System.currentTimeMillis())
        if (prev != null && System.currentTimeMillis() - prev < 30_000L) {
            return record(QuarantineReason.FAKE_DUPLICATE_SELL, t)
        }

        // 2. Missing fields on a SELL row.
        if (t.side.equals("SELL", ignoreCase = true)) {
            if (t.price <= 0.0) return record(QuarantineReason.MISSING_EXIT_PRICE, t)
            if (t.sol <= 0.0) return record(QuarantineReason.MISSING_ENTRY_PRICE, t)
        }

        // 3. Impossible PnL: declared pnlPct vs implied from pnlSol/sol.
        if (t.side.equals("SELL", ignoreCase = true) && t.sol > 0.0) {
            val impliedPct = t.pnlSol / t.sol * 100.0
            val declared = t.pnlPct
            // Allow paper-mode clamps within tolerance; outside +/- 50 absolute pts is impossible.
            if (kotlin.math.abs(impliedPct - declared) > 50.0) {
                return record(QuarantineReason.IMPOSSIBLE_PNL, t)
            }
            // 3b. Catastrophic SL labels with positive PnL.
            val isStopLossExit = t.reason.contains("STOP", ignoreCase = true) ||
                                  t.reason.contains("SL", ignoreCase = true) ||
                                  t.reason.contains("HARD_FLOOR", ignoreCase = true)
            if (isStopLossExit && declared >= 10.0) {
                return record(QuarantineReason.SL_LABEL_POSITIVE_PNL_MISMATCH, t)
            }
        }

        // 4. Bad decimals — sanity check pnlSol vs (sol × pnlPct/100).
        if (t.side.equals("SELL", ignoreCase = true) && t.sol > 0.0) {
            val expectedPnl = t.sol * (t.pnlPct / 100.0)
            val declared = t.pnlSol
            // Allow up to 50% slip on fees/slippage, but anything wilder is a decimal bug.
            if (kotlin.math.abs(declared) > kotlin.math.abs(expectedPnl) * 5.0 + 5.0 ||
                kotlin.math.abs(declared) > 1000.0) {
                return record(QuarantineReason.BAD_DECIMALS, t)
            }
        }

        passedCount.incrementAndGet()
        try { PipelineHealthCollector.labelInc("TRADE_SANITY_OK") } catch (_: Throwable) {}
        return QuarantineReason.OK
    }

    private fun record(reason: QuarantineReason, t: Trade, detail: String = ""): QuarantineReason {
        bump(quarantineCounts, reason.tag)
        try {
            PipelineHealthCollector.labelInc("TRADE_QUARANTINED_${reason.tag}")
            if (reason == QuarantineReason.PAPER_ROW_CORRUPT) {
                com.lifecyclebot.engine.PaperLearningSanity.emitQuarantine(t, detail.ifBlank { reason.tag })
            }
            ErrorLogger.warn("TradeSanity", "🧪 QUARANTINED ${reason.tag} detail=$detail mint=${t.mint.take(10)} ${t.side} price=${t.price} sol=${t.sol} pnlPct=${t.pnlPct} pnlSol=${t.pnlSol} reason=${t.reason.take(40)}")
        } catch (_: Throwable) {}
        return reason
    }

    fun snapshot(): Map<String, Long> = quarantineCounts.mapValues { it.value.get() } + ("OK" to passedCount.get())

    fun totalQuarantined(): Long = quarantineCounts.values.sumOf { it.get() }
    fun totalAdmitted(): Long = passedCount.get()

    private fun bump(map: ConcurrentHashMap<String, AtomicLong>, key: String) {
        var c = map[key]
        if (c == null) c = map.computeIfAbsent(key) { AtomicLong(0L) }
        c.incrementAndGet()
    }
}

/**
 * V5.9.1325 — Paper vs Live confidence split (operator §9).
 *
 * Paper-rows enter learning aggregations at a reduced weight until
 * validated by ≥N live samples on the same bucket. This keeps the
 * model trainable in paper without making live reckless.
 */
object PaperLiveConfidenceWeights {

    private const val PAPER_BOOTSTRAP_WEIGHT = 0.40
    private const val PAPER_VALIDATED_WEIGHT = 0.85
    private const val LIVE_WEIGHT            = 1.00
    private const val LIVE_VALIDATION_MIN_SAMPLES = 25

    private val liveSamplesByBucket = ConcurrentHashMap<String, AtomicLong>()

    fun noteLiveSample(lane: String, scoreBand: String) {
        val key = "${lane.uppercase().take(24)}|${scoreBand.uppercase().take(12)}"
        liveSamplesByBucket.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
    }

    fun weight(lane: String, scoreBand: String, isPaper: Boolean): Double {
        if (!isPaper) return LIVE_WEIGHT
        val key = "${lane.uppercase().take(24)}|${scoreBand.uppercase().take(12)}"
        val live = liveSamplesByBucket[key]?.get() ?: 0L
        return if (live >= LIVE_VALIDATION_MIN_SAMPLES) PAPER_VALIDATED_WEIGHT else PAPER_BOOTSTRAP_WEIGHT
    }

    fun snapshot(): Map<String, Long> = liveSamplesByBucket.mapValues { it.value.get() }
}
