package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6633 §P0-G/H/I — CROSS_ASSET_RAW_SIGNAL_RECEIPT.
 *
 * OPERATOR DIRECTIVE (verbatim, Feb 2026):
 *   > "Audit Forex normalization switch to ensure valid BUY/SELL/LONG/
 *   >  SHORT do not collapse to WAIT. Stop batch loops from discarding
 *   >  all Commodity actionable signals based on a single failure.
 *   >  Ensure Metals analyzer outputs reach the raw signal phase."
 *
 * Forensic problem: 100+ actionable Forex / Commodity / Metals signals
 * are dying before candidate creation, but the source doesn't emit a
 * per-symbol receipt at the analyzer entrypoint — so which normalization
 * branch is losing them is unobservable.
 *
 * DESIGN
 * ──────
 * `stamp(assetClass, symbol, stage, verdict, reason)` records a per-
 * (class, symbol) stage tick with a compact verdict.  Emits both a
 * global label and a class+symbol+stage-specific label so the operator
 * can grep the exact hop where a symbol vanished.
 *
 * Every specialist (ForexTrader, CommoditiesTrader, MetalsTrader,
 * TokenizedStockTrader) stamps its analyzer at:
 *   ANALYZE_ENTERED   — first tick of the market
 *   ANALYZE_STOOD_DOWN — strategy returned null (off-session / no edge)
 *   ANALYZE_RAW       — strategy produced a raw signal
 *   ANALYZE_ACTIONABLE — passed lane thresholds and reached the queue
 *
 * The counter-diff Analyze_Entered − Analyze_Actionable equals the
 * exact number of signals lost between the analyzer's front door and
 * the executable queue — the operator's missing observability.
 */
object CrossAssetRawSignalReceipt6633 {

    enum class Stage {
        ANALYZE_ENTERED,
        ANALYZE_STOOD_DOWN,
        ANALYZE_RAW,
        ANALYZE_ACTIONABLE,
        ANALYZE_SIZED,
        ANALYZE_TICKETED,
        ANALYZE_EXECUTED,
        ANALYZE_REJECTED,
    }

    enum class Verdict { OK, WAIT, REJECTED, EXCEPTION }

    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val stamps = AtomicLong(0L)
    private val perAssetClass = ConcurrentHashMap<String, AtomicLong>()

    fun stamp(
        assetClass: AssetClass,
        symbol: String,
        stage: Stage,
        verdict: Verdict = Verdict.OK,
        reason: String = "",
    ) {
        stamps.incrementAndGet()
        val tag = "${assetClass.tag}|${symbol.uppercase()}|${stage.name}|${verdict.name}"
        counts.computeIfAbsent(tag) { AtomicLong(0L) }.incrementAndGet()
        perAssetClass.computeIfAbsent(assetClass.tag) { AtomicLong(0L) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("CROSS_ASSET_SIGNAL_${stage.name}_6633")
            PipelineHealthCollector.labelInc("CROSS_ASSET_SIGNAL_${stage.name}_${assetClass.tag}_6633")
            if (verdict == Verdict.REJECTED || verdict == Verdict.EXCEPTION) {
                PipelineHealthCollector.labelInc("CROSS_ASSET_SIGNAL_LOST_${assetClass.tag}_${stage.name}_6633")
                ForensicLogger.lifecycle(
                    "CROSS_ASSET_SIGNAL_LOST_6633",
                    "class=${assetClass.tag} symbol=${symbol.take(16)} stage=${stage.name} " +
                        "verdict=${verdict.name} reason=${reason.take(80)}",
                )
            }
        } catch (_: Throwable) {}
    }

    /** Total signal stamps observed. */
    fun statusLine6633(): String {
        val top = perAssetClass.entries
            .sortedByDescending { it.value.get() }
            .take(6)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        return "stamps=${stamps.get()} tracked=${counts.size} byClass=[$top]"
    }

    internal fun resetForTest() {
        counts.clear(); perAssetClass.clear(); stamps.set(0L)
    }
}
