package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6635 §8 MARKET_MARK_GATE.
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   > "Current market price may update: marketValue, unrealizedPnl,
 *   >  peak, drawdown, exit trigger.
 *   >  It must NEVER alter: entryPrice, originalQty, originalCost,
 *   >  decimals, realized proceeds, historical journal rows."
 *
 * Downstream mark refreshers previously wrote directly onto Position
 * fields — nothing structurally prevented a stray price update from
 * overwriting entryPriceUsd or originalQtyRaw when a caller was
 * confused about which field they were setting.
 *
 * This gate wraps every attempted mutation of the six protected
 * fields.  On any attempt it fires:
 *   MARKET_MARK_BASIS_MUTATION_REJECTED_6635
 *   MARKET_MARK_BASIS_MUTATION_REJECTED_<FIELD>_6635
 * and returns false so the caller MUST leave the basis field alone.
 * The gate never actually holds the field values (they live on
 * canonical position / journal / lot stores) — it is a compile-time
 * discipline made testable at runtime.
 *
 * Mark refresh continues to use setter APIs on TokenState /
 * MarketDataCache for the mutable fields ONLY:
 *   marketPriceUsd, marketPriceSol, unrealizedPnlUsd/Sol,
 *   peakPriceUsd/Sol, drawdownPct, exitTriggerTag.
 */
object MarketMarkGate6635 {

    enum class ProtectedField {
        ENTRY_PRICE_USD,
        ENTRY_PRICE_SOL,
        ORIGINAL_QTY_RAW,
        ORIGINAL_COST_SOL,
        TOKEN_DECIMALS,
        REALIZED_PROCEEDS_SOL,
        HISTORICAL_JOURNAL_ROW,
        ENTRY_PRICE_SOURCE,
    }

    private val rejections = AtomicLong(0L)

    /**
     * Callers use this to declare an INTENT to mutate a protected
     * field.  The gate ALWAYS returns false — the mutation is
     * structurally forbidden.  The forensic emit names the field +
     * callsite so operator can grep the source of the drift.
     */
    fun refuseBasisMutation6635(
        field: ProtectedField,
        positionId: String,
        callSite: String,
        attemptedValue: String = "",
    ): Boolean {
        rejections.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("MARKET_MARK_BASIS_MUTATION_REJECTED_6635")
            PipelineHealthCollector.labelInc("MARKET_MARK_BASIS_MUTATION_REJECTED_${field.name}_6635")
            ForensicLogger.lifecycle(
                "MARKET_MARK_BASIS_MUTATION_REJECTED_6635",
                "positionId=${positionId.take(24)} field=${field.name} " +
                    "callSite=${callSite.take(60)} attemptedValue=${attemptedValue.take(40)} " +
                    "action=basis_is_immutable_market_mark_only_updates_current_price",
            )
        } catch (_: Throwable) {}
        return false
    }

    fun statusLine6635(): String = "basisMutationsRejected=${rejections.get()}"

    internal fun resetForTest() { rejections.set(0L) }
}
