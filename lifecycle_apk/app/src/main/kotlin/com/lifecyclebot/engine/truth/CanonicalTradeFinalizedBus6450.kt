package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P0 — ONE CANONICAL REWARD EVENT.
 *
 * OPERATOR MANDATE:
 *   Learning systems currently disagree radically:
 *     RewardPurity: W=4  L=77
 *     GrowthShaper: W=13 L=45 BE=35
 *     RewardBridge: W=22 L=125
 *     TacticSwitcher contains 25/0 and 9/0 cohorts.
 *
 *   "Eliminate parallel definitions of 'win'.
 *    Generate exactly ONE CanonicalTradeFinalizedEvent only after
 *    canonical terminal settlement. All learning consumers subscribe to
 *    this SAME event. One PositionId contributes exactly ONE terminal
 *    W/L/BE observation."
 *
 * DESIGN
 * ──────
 * Dedup by positionId. First subscriber wins any race. Publish is
 * idempotent — second publish for same positionId returns false and
 * emits DUPLICATE_FINALIZE_6450.
 */
object CanonicalTradeFinalizedBus6450 {

    enum class Outcome { WIN, LOSS, BREAKEVEN }

    data class Event(
        val positionId: String,
        val mint: String,
        val outcome: Outcome,
        val netRealizedPnlSol: Double,   // net of ALL fees (gross - buyFee - sellFee)
        val grossRealizedPnlSol: Double, // gross of fees (proceeds - basis)
        val returnFraction: Double,      // netPnl / entryCost (e.g. 0.15 = +15%)
        val netReturnPct: Double,        // legacy: 100 * returnFraction (percent)
        val feesSol: Double,
        val entryLane: String,
        val entryStrategyPid: String,
        val entryTactic: String,
        val exitReason: String,
        val holdingTimeMs: Long,
        val dataQuality: String,
        val priceIntegrity: String,
        val mode: String,
        val settledAtMs: Long,
    )

    fun interface Subscriber { fun onEvent(event: Event) }

    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val finalized = ConcurrentHashMap<String, Long>() // positionId -> settledAtMs
    private val published = AtomicLong(0L)
    private val duplicates = AtomicLong(0L)
    private val subscriberFailures = AtomicLong(0L)
    private val quarantinedEconomics6495 = AtomicLong(0L)

    private fun economicInvalidReason6495(event: Event): String? {
        val values = listOf(event.netRealizedPnlSol, event.grossRealizedPnlSol, event.returnFraction, event.netReturnPct, event.feesSol)
        if (values.any { !it.isFinite() }) return "NON_FINITE_ECONOMICS"
        if (event.feesSol < -0.0000001) return "NEGATIVE_FEES"
        val expectedPct = event.returnFraction * 100.0
        val pctTolerance = maxOf(0.05, kotlin.math.abs(expectedPct) * 0.01)
        if (kotlin.math.abs(event.netReturnPct - expectedPct) > pctTolerance) return "RETURN_FRACTION_PCT_MISMATCH"
        val feeTolerance = maxOf(0.000001, kotlin.math.abs(event.grossRealizedPnlSol) * 0.01)
        if (kotlin.math.abs((event.grossRealizedPnlSol - event.feesSol) - event.netRealizedPnlSol) > feeTolerance) return "NET_GROSS_FEE_MISMATCH"
        if (event.netReturnPct < com.lifecyclebot.engine.LearningPnlSanitizer.MIN_TRAINABLE_PNL_PCT ||
            event.netReturnPct > com.lifecyclebot.engine.LearningPnlSanitizer.MAX_TRAINABLE_PNL_PCT) return "RETURN_OUTSIDE_TRAINABLE_RANGE"
        if (kotlin.math.abs(event.returnFraction) > 0.000000001) {
            val impliedBasis = event.netRealizedPnlSol / event.returnFraction
            if (!impliedBasis.isFinite() || impliedBasis <= 0.0) return "IMPLIED_BASIS_INVALID"
            val impliedProceeds = impliedBasis + event.grossRealizedPnlSol
            if (!impliedProceeds.isFinite() || impliedProceeds < -0.0000001) return "IMPLIED_PROCEEDS_INVALID"
            // Same deliberately huge fabrication ceiling as TradeHistoryStore.
            if (kotlin.math.abs(impliedProceeds) > 5_000.0) return "IMPLIED_PROCEEDS_ABOVE_5000_SOL"
        } else if (kotlin.math.abs(event.netRealizedPnlSol) > 0.000001) {
            return "ZERO_RETURN_WITH_NONZERO_PNL"
        }
        return null
    }

    internal fun economicInvalidReasonForTest6495(event: Event): String? = economicInvalidReason6495(event)

    fun subscribe(subscriber: Subscriber) {
        subscribers.addIfAbsent(subscriber)
    }

    fun publish(event: Event): Boolean {
        if (event.positionId.isBlank()) return false
        try { CanonicalRewardBootstrap6453.ensureBootstrapped() } catch (_: Throwable) {}
        val prior = finalized.putIfAbsent(event.positionId, event.settledAtMs)
        if (prior != null) {
            duplicates.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_TRADE_FINALIZE_DUPLICATE_6450",
                    "positionId=${event.positionId.take(12)} priorAtMs=$prior newAtMs=${event.settledAtMs}",
                )
                PipelineHealthCollector.labelInc("CANONICAL_TRADE_FINALIZE_DUPLICATE_6450")
            } catch (_: Throwable) {}
            return false
        }
        val economicInvalid6495 = economicInvalidReason6495(event)
        if (economicInvalid6495 != null) {
            quarantinedEconomics6495.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("CANONICAL_FINALIZED_ECONOMICS_QUARANTINED_6495")
                PipelineHealthCollector.labelInc("CANONICAL_FINALIZED_ECONOMICS_QUARANTINED_6495_$economicInvalid6495")
                ForensicLogger.lifecycle(
                    "CANONICAL_FINALIZED_ECONOMICS_QUARANTINED_6495",
                    "positionId=${event.positionId.take(16)} mint=${event.mint.take(10)} lane=${event.entryLane} mode=${event.mode} reason=$economicInvalid6495 pnlSol=${event.netRealizedPnlSol} returnPct=${event.netReturnPct} proof=${event.dataQuality}:${event.priceIntegrity}",
                )
            } catch (_: Throwable) {}
            return true
        }
        published.incrementAndGet()
        CanonicalFinalityPersistence6486.record(event)
        val quarantined6485 = try {
            LearningQuarantineGate6470.shouldDropForLearning(positionId = event.positionId, mint = event.mint)
        } catch (_: Throwable) { true }
        for (s in if (quarantined6485) emptyList() else subscribers.toList()) {
            try { s.onEvent(event) } catch (t: Throwable) {
                subscriberFailures.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "CANONICAL_TRADE_FINALIZE_SUB_FAIL_6450",
                        "positionId=${event.positionId.take(12)} err=${t.message?.take(80)}",
                    )
                } catch (_: Throwable) {}
            }
        }
        try {
            PipelineHealthCollector.labelInc("CANONICAL_TRADE_FINALIZED_6450_${event.outcome}")
        } catch (_: Throwable) {}
        // V5.0.6476 — one terminal identity across the rich 6450 event and
        // the 6464 parity fanout. PositionId is the dedup key; mode/proof
        // travel with the immutable event instead of being inferred later.
        try {
            CanonicalFinalizedTradeBus6464.ensureCanonicalConsumers6485()
            val env = CanonicalFinalizedTradeBus6464.Envelope(
                tradeId = event.positionId,
                atMs = event.settledAtMs,
                realizedPnlSol = event.netRealizedPnlSol,
                realizedReturnPct = event.netReturnPct,
                mint = event.mint,
                lane = event.entryLane,
                positionId = event.positionId,
                mode = event.mode,
                proofState = "${event.dataQuality}:${event.priceIntegrity}",
                holdingTimeMs = event.holdingTimeMs.coerceAtLeast(0L),
                entryScore = EntryStrategySnapshot6450.snapshot(event.positionId)?.entryScore ?: 0,
                entryTactic = event.entryTactic,
                terminal = true,
            )
            if (CanonicalFinalizedTradeBus6464.publish(env)) {
                CanonicalFinalizedTradeBus6464.deliverToConsumers(env) { name, e ->
                    FinalizedBusConsumerBridge6465.deliver(name, e)
                }
                CanonicalFinalizedTradeBus6464.requestRetry6486()
            }
        } catch (t: Throwable) {
            subscriberFailures.incrementAndGet()
            try {
                ForensicLogger.lifecycle("CANONICAL_FINALITY_FANOUT_FAILED_6486", "positionId=${event.positionId.take(16)} err=${t.message?.take(100)}")
                PipelineHealthCollector.labelInc("CANONICAL_FINALITY_FANOUT_FAILED_6486")
            } catch (_: Throwable) {}
        }
        return true
    }

    fun statusLine(): String = "subs=${subscribers.size} published=${published.get()} " +
        "duplicates=${duplicates.get()} subFail=${subscriberFailures.get()} quarantinedEconomics6495=${quarantinedEconomics6495.get()}"
}
