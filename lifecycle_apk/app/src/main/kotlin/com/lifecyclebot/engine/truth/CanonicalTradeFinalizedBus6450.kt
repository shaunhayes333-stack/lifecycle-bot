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
        val assetClassTag: String = "",
        val economicEventId: String = "",
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
            val learningEligibility6519 = PaperLearningEligibility6519.decision(event.positionId, event.mint)
            val entrySnap6567 = EntryStrategySnapshot6450.snapshot(event.positionId)
            val entryScore6567 = entrySnap6567?.entryScore ?: 0
            // V5.0.6813 §UNIT_INVALID_LEARNING_QUARANTINE — operator diagnosis Feb 2026:
            //   "A quarantined absurd mark must NEVER be used to rebase entry
            //    basis, calculate realised PNL, classify W/L, update EV, WR,
            //    tactic μ, or trigger losing-streak learning." Canonical
            //   position authority is intentionally untouched — this is a
            //   pure learning-input filter. When the entry snapshot carries
            //   an obviously unit-invalid price/mcap (e.g. entryPrice≈1109
            //   on a token that trades near 1e-5, or entryMarketCapUsd
            //   equal to Int.MAX_VALUE saturation 2147483647), we override
            //   learningEligible → false with an explicit reason so every
            //   downstream learner (EV, WR, tactic μ, losing-streak,
            //   UnifiedPolicyHead) excludes this trade from training.
            var unitInvariant6813Eligible = learningEligibility6519.eligible
            var unitInvariant6813Reason = learningEligibility6519.reason
            if (unitInvariant6813Eligible && entrySnap6567 != null) {
                val entryPrice6813 = entrySnap6567.entryPriceUsd
                val entryMcap6813 = entrySnap6567.entryMarketCapUsd
                val mcapSaturated6813 = entryMcap6813 >= 2_147_483_646.0 &&
                    entryMcap6813 <= 2_147_483_648.0
                // Memecoin-class tokens trade far below $1; a persisted entry
                // price above $1000 with a sub-$500k mcap is a decimal/unit
                // corruption (real per-token price would be <$0.01). Blue
                // chip tokens (SOL, BTC, ETH) legitimately trade above $1000
                // but always with mcap >= $100M, so the joint predicate
                // avoids false positives.
                val decimalCorrupted6813 = entryPrice6813 > 1_000.0 &&
                    entryMcap6813 in 1.0..500_000.0
                if (mcapSaturated6813 || decimalCorrupted6813) {
                    unitInvariant6813Eligible = false
                    unitInvariant6813Reason = "UNIT_INVALID_QUARANTINE_6813" +
                        (if (mcapSaturated6813) ":MCAP_INT_SATURATION" else "") +
                        (if (decimalCorrupted6813) ":ENTRY_PRICE_DECIMAL_SKEW" else "")
                    try {
                        PipelineHealthCollector.labelInc("UNIT_INVALID_QUARANTINE_6813")
                        if (mcapSaturated6813) PipelineHealthCollector.labelInc("UNIT_INVALID_QUARANTINE_6813_MCAP_INT_SATURATION")
                        if (decimalCorrupted6813) PipelineHealthCollector.labelInc("UNIT_INVALID_QUARANTINE_6813_ENTRY_PRICE_DECIMAL_SKEW")
                        ForensicLogger.lifecycle(
                            "UNIT_INVALID_QUARANTINE_6813",
                            "positionId=${event.positionId.take(16)} mint=${event.mint.take(10)} " +
                                "lane=${event.entryLane} entryPriceUsd=$entryPrice6813 " +
                                "entryMarketCapUsd=$entryMcap6813 mcapSaturated=$mcapSaturated6813 " +
                                "decimalCorrupted=$decimalCorrupted6813 " +
                                "action=exclude_from_learning_only_no_canonical_mutation",
                        )
                    } catch (_: Throwable) {}
                }
            }
            // V5.0.6818 §STALE_MARK_SCRATCH_NON_TRAINABLE — operator directive
            //   Feb 2026 item #1/#6: "PAPER_STALE_PRICE_TIMEOUT_SCRATCH must
            //   not train entry/exit learners unless exitPriceAuthority ==
            //   VALIDATED_MARK." Consult the V5.0.6817 gates: if the position
            //   was routed through the stale-mark exit gate as non-trainable,
            //   or its owner is quarantined, override the envelope's
            //   `learningEligible` to FALSE and stamp the reason so
            //   RewardPurityAdmission6817 / consumers observe the exclusion.
            val staleGateTrainable6818 = try {
                com.lifecyclebot.engine.truth.StaleMarkExitGate6817.isTrainable(event.positionId)
            } catch (_: Throwable) { true }
            val ownerQuarantined6818 = try {
                com.lifecyclebot.engine.truth.UnresolvedOwnerLearningQuarantine6817
                    .isQuarantined(event.positionId)
            } catch (_: Throwable) { false }
            val staleExitReasonTag6818 = event.exitReason.contains("STALE_PRICE_TIMEOUT", ignoreCase = true) ||
                event.exitReason.contains("STALE_ZOMBIE_SCRATCH", ignoreCase = true)
            if (!staleGateTrainable6818 || ownerQuarantined6818 || staleExitReasonTag6818) {
                unitInvariant6813Eligible = false
                val reasons6818 = buildList {
                    if (!staleGateTrainable6818) add("STALE_MARK_NON_TRAINABLE_6818")
                    if (ownerQuarantined6818) add("UNRESOLVED_OWNER_QUARANTINED_6817")
                    if (staleExitReasonTag6818) add("STALE_EXIT_REASON_6818:${event.exitReason.take(40)}")
                }
                unitInvariant6813Reason = if (unitInvariant6813Reason == "ELIGIBLE")
                    reasons6818.joinToString("|")
                else "$unitInvariant6813Reason|${reasons6818.joinToString("|")}"
                try {
                    PipelineHealthCollector.labelInc("FINALIZED_LEARNING_EXCLUDED_STALE_6818")
                    if (staleExitReasonTag6818) PipelineHealthCollector.labelInc("FINALIZED_LEARNING_EXCLUDED_STALE_EXIT_REASON_6818")
                    if (!staleGateTrainable6818) PipelineHealthCollector.labelInc("FINALIZED_LEARNING_EXCLUDED_STALE_GATE_6818")
                    if (ownerQuarantined6818) PipelineHealthCollector.labelInc("FINALIZED_LEARNING_EXCLUDED_UNRESOLVED_OWNER_6818")
                    ForensicLogger.lifecycle(
                        "FINALIZED_LEARNING_EXCLUDED_STALE_6818",
                        "positionId=${event.positionId.take(24)} mint=${event.mint.take(10)} " +
                            "exitReason=${event.exitReason.take(60)} reasons=${reasons6818.joinToString(",")} " +
                            "action=diagnostic_visible_learners_excluded",
                    )
                } catch (_: Throwable) {}
            }
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
                entryScore = entryScore6567,
                entryTactic = event.entryTactic,
                entrySource = entrySnap6567?.entrySource ?: "",
                marketRegime = entrySnap6567?.entryMarketRegime ?: "",
                scoreBand = com.lifecyclebot.engine.LosingPatternMemory.scoreBand(entryScore6567),
                terminal = true,
                learningEligible = unitInvariant6813Eligible,
                learningEligibilityReason = unitInvariant6813Reason,
                assetClassTag = event.assetClassTag.ifBlank { entrySnap6567?.assetClassTag ?: AssetClass.fromLane(event.entryLane).tag },
                economicEventId = event.economicEventId,
                exitReason = event.exitReason,
            )
            if (CanonicalFinalizedTradeBus6464.publish(env)) {
                // The rich event is published while the journal durability
                // commit may still be in flight. Redeliver exactly when the
                // matching canonical economic event reaches COMMITTED; this
                // removes timing-based reward loss and keeps delivery idempotent.
                if (event.economicEventId.isNotBlank()) {
                    CanonicalEconomicEvent6635.afterCommitted(event.economicEventId) {
                        CanonicalFinalizedTradeBus6464.redeliverPending6486()
                    }
                }
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
