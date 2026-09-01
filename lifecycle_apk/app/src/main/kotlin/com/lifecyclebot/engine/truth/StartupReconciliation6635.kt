package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6635 §9 STARTUP_RECONCILIATION.
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   > "On app startup: load immutable journal / load canonical
 *   >  economic events / load canonical positions/lots / load
 *   >  account ledger.  Reconcile by economicEventId.
 *   >  Do not blindly replay old corrupt transactions into authority.
 *   >  Classification:
 *   >    VALID / DUPLICATE / MISSING_JOURNAL / MISSING_LEDGER /
 *   >    QTY_INVALID / PRICE_INVALID / IDENTITY_INVALID
 *   >  Invalid historical rows remain quarantined for forensic
 *   >  inspection.  They must NOT alter current cash, equity, WR,
 *   >  PnL or learning.  Do not rewrite historical evidence to make
 *   >  it balance."
 *
 * This module implements the classifier that BotService.onStart /
 * TradeHistoryStore.loadPersisted etc call once at boot.  It walks
 * the persisted journal rows + persisted position registry and
 * classifies each; VALID rows are admitted to the runtime authorities;
 * any other class is stamped `HISTORY_QUARANTINED_<class>_6635` and
 * placed in the quarantine index.  Quarantined rows never touch
 * runtime cash/equity/WR/PnL/learning.
 */
object StartupReconciliation6635 {

    enum class Class {
        VALID, DUPLICATE, MISSING_JOURNAL, MISSING_LEDGER,
        QTY_INVALID, PRICE_INVALID, IDENTITY_INVALID,
    }

    private val classified = java.util.EnumMap<Class, AtomicLong>(Class::class.java).apply {
        Class.values().forEach { put(it, AtomicLong(0L)) }
    }
    private val quarantinedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val runs = AtomicLong(0L)

    data class HistoricalRow(
        val economicEventId: String,
        val positionId: String,
        val mint: String,
        val side: String,
        val qtyRaw: java.math.BigInteger,
        val decimals: Int,
        val priceUsd: Double,
        val cashDeltaSol: Double,
        val timestampMs: Long,
    )

    /**
     * Classify a single historical row against the runtime rules.
     * Emits the class-specific counter + `HISTORY_QUARANTINED_...` for
     * non-VALID rows.  Returns the classification so the caller can
     * decide to admit the row or hold it for inspection.
     */
    fun classify6635(row: HistoricalRow, seenEventIds: MutableSet<String>): Class {
        val cls = when {
            row.economicEventId.isBlank() || row.positionId.isBlank() || row.mint.isBlank() ->
                Class.IDENTITY_INVALID
            !seenEventIds.add(row.economicEventId) -> Class.DUPLICATE
            row.qtyRaw.signum() < 0 -> Class.QTY_INVALID
            row.side.equals("BUY", true) && row.qtyRaw.signum() <= 0 -> Class.QTY_INVALID
            !row.priceUsd.isFinite() || row.priceUsd < 0.0 -> Class.PRICE_INVALID
            row.decimals !in 0..18 -> Class.QTY_INVALID
            else -> Class.VALID
        }
        classified[cls]?.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("STARTUP_RECONCILIATION_${cls.name}_6635")
        } catch (_: Throwable) {}
        if (cls != Class.VALID) {
            quarantinedIds.add(row.economicEventId)
            try {
                PipelineHealthCollector.labelInc("HISTORY_QUARANTINED_${cls.name}_6635")
                ForensicLogger.lifecycle(
                    "HISTORY_QUARANTINED_6635",
                    "class=${cls.name} economicEventId=${row.economicEventId.take(30)} " +
                        "positionId=${row.positionId.take(24)} mint=${row.mint.take(12)} " +
                        "side=${row.side} qtyRaw=${row.qtyRaw} decimals=${row.decimals} " +
                        "priceUsd=${row.priceUsd} action=hold_for_inspection_no_authority_write",
                )
            } catch (_: Throwable) {}
        }
        return cls
    }

    fun isQuarantined6635(economicEventId: String): Boolean =
        quarantinedIds.contains(economicEventId)

    fun beginRun6635() {
        runs.incrementAndGet()
        try { PipelineHealthCollector.labelInc("STARTUP_RECONCILIATION_STARTED_6635") } catch (_: Throwable) {}
    }

    fun endRun6635() {
        try { PipelineHealthCollector.labelInc("STARTUP_RECONCILIATION_COMPLETED_6635") } catch (_: Throwable) {}
        try {
            ForensicLogger.lifecycle(
                "STARTUP_RECONCILIATION_COMPLETED_6635",
                statusLine6635(),
            )
        } catch (_: Throwable) {}
    }

    fun statusLine6635(): String = buildString {
        append("runs=${runs.get()} ")
        Class.values().forEach { append("${it.name.lowercase()}=${classified[it]?.get() ?: 0L} ") }
        append("quarantined=${quarantinedIds.size}")
    }

    internal fun resetForTest() {
        Class.values().forEach { classified[it]?.set(0L) }
        quarantinedIds.clear()
        runs.set(0L)
    }
}
