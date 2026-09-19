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
            // V5.0.7097 §A_QUARANTINE_IS_NOT_A_DISAPPEARANCE — this used to
            // `return true` here, reporting success to the caller while the
            // closed trade never reached the 6464 finalized bus at all. The
            // position stays CLOSED in CanonicalPositionAuthority6441 forever,
            // so the acceptance audit's reward parity can never be satisfied:
            // 5.0.7091 reports reward_pop_mismatch closed=63 bus=52
            // missingFromBus=11, and no later pass can ever recover those 11.
            //
            // The bus was designed for exactly this case and it is not
            // suppression. AcceptanceInvariantAudit6441's own contract (§4,
            // written for 6697) is:
            //     canonical CLOSED == finalized bus canonical population
            //     canonical CLOSED == RewardPurity processed + excluded
            // An ineligible terminal belongs ON the bus, marked excluded — that
            // is what consumerExcludedUnique counts and why `exclude()` exists.
            // Withholding publication breaks the very invariant 6697 built.
            //
            // Malformed economics still train nothing: the envelope publishes
            // with learningEligible=false carrying the 6495 reason, and it is
            // explicitly excluded for every canonical consumer before delivery
            // is attempted. Nothing reads it as a win, a loss or expectancy.
            // What changes is that the trade is accounted for instead of lost.
        }
        // Analytics/learner fanout stays exactly as before: a 6495-quarantined
        // event is not `published`, is not persisted for replay, and is not
        // handed to any 6450 subscriber. Only the 6464 parity fanout below runs
        // for it, and there it lands excluded.
        if (economicInvalid6495 == null) {
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
        }
        // V5.0.6476 — one terminal identity across the rich 6450 event and
        // the 6464 parity fanout. PositionId is the dedup key; mode/proof
        // travel with the immutable event instead of being inferred later.
        try {
            CanonicalFinalizedTradeBus6464.ensureCanonicalConsumers6485()
            val learningEligibility6519 = PaperLearningEligibility6519.decision(event.positionId, event.mint)
            val entrySnap6567 = EntryStrategySnapshot6450.snapshot(event.positionId)
            val entryScore6567 = entrySnap6567?.entryScore ?: 0
            // V5.0.6831 §EXPRESS_EXIT_PRICE_INTEGRITY — for EXPRESS lane
            //   terminals, run the price-integrity validator against the
            //   settled economic event. If the exit price / proceeds /
            //   quote provenance is malformed, stamp positionId as
            //   non-trainable so the finalized bus's downstream guard
            //   overrides learningEligible=false. All other lanes pass
            //   through the existing PaperLearningEligibility6519 flow.
            try {
                val laneKey6831 = event.entryLane.trim().uppercase()
                if ("EXPRESS" in laneKey6831) {
                    // V5.0.6840 §EXPRESS_INTEGRITY_GATE_EXCLUDED_EVERYTHING — the old
                    // predicate tested proof-state STRINGS against a whitelist of
                    // VALID / VALIDATED_MARK / GOOD / CLEAN. No real producer emits any
                    // of those: canonical paper terminals are stamped
                    // priceIntegrity = dataQuality = "canonical_paper_fill"
                    // (CanonicalPaperTerminalBridge6469:410) and live ones
                    // "confirmed_signature" (SellFinalizationCoordinator:289). So the
                    // whitelist never matched and the gate fired on 100% of EXPRESS
                    // closes — operator 5.0.6835 showed finalized=26 against
                    // FINALIZED_LEARNING_EXCLUDED_EXPRESS_EXIT_INTEGRITY_6831=26, an
                    // exact 1:1. That is not "every quote was bad", it is a vocabulary
                    // mismatch excluding the entire lane.
                    //
                    // The consequence lands on SelectionQualityAuthority6829, the one
                    // consumer of the resulting flag: with every EXPRESS terminal
                    // dropped its rolling WR for the lane stayed empty, so
                    // scoreFloorDelta("EXPRESS") returned 0.0 at FinalDecisionGate:1492
                    // and the quality floor could not raise the bar on the worst lane in
                    // the book (3.8% WR, -62% avg). The gate meant to protect learning
                    // was disarming the gate meant to protect admission.
                    //
                    // Gate on genuine economic corruption instead of proof vocabulary:
                    // a non-finite return/PnL, or a total-loss return reported with
                    // exactly zero realised PnL, which is the "sol=0.000 sell" shape
                    // V5.0.6831 was written to catch.
                    val corruptExit6840 = !event.netReturnPct.isFinite() ||
                        !event.netRealizedPnlSol.isFinite() ||
                        (event.netRealizedPnlSol == 0.0 && event.netReturnPct <= -99.9)
                    if (corruptExit6840) {
                        ExpressExitPriceIntegrity6831.evaluate(
                            ExpressExitPriceIntegrity6831.AuditInputs(
                                positionId = event.positionId,
                                mint = event.mint,
                                lane = event.entryLane,
                                entryRaw = 0.0, entryNormalized = 0.0,
                                // V5.0.6844 §EXIT_INVALID_BY_DEFINITION — we are only
                                //   inside this branch because corruptExit6840 already
                                //   proved the settled economic event is malformed
                                //   (non-finite return/PnL, or -100% at zero SOL).
                                //   The old boolean `exitPriceValid` was deleted with
                                //   6840's whitelist rewrite; here the exit is by
                                //   definition not price-valid, so pass 0.0 through.
                                exitRaw = 0.0,
                                exitNormalized = 0.0,
                                triggerReason = event.exitReason,
                                triggerPct = event.netReturnPct,
                                proceeds = 0.0,
                                quoteSource = event.priceIntegrity,
                            )
                        )
                    }
                }
            } catch (_: Throwable) {}
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
                // V5.0.7097 — malformed settled economics can never be trainable,
                // whatever PaperLearningEligibility6519 thinks of the position.
                learningEligible = learningEligibility6519.eligible && economicInvalid6495 == null,
                learningEligibilityReason = if (economicInvalid6495 != null)
                    "ECONOMICS_QUARANTINED_6495:$economicInvalid6495"
                else learningEligibility6519.reason,
                assetClassTag = event.assetClassTag.ifBlank { entrySnap6567?.assetClassTag ?: AssetClass.fromLane(event.entryLane).tag },
                economicEventId = event.economicEventId,
                exitReason = event.exitReason,
            )
            // V5.0.7097 — exclude BEFORE delivery is attempted, so no canonical
            // consumer ever sees a malformed-economics terminal. deliverOne6734
            // returns early on an exclusion, and redeliverPending6486 skips it,
            // so the row is on the bus and reachable by the audit while being
            // unreachable by every learner.
            if (economicInvalid6495 != null) {
                try {
                    CanonicalFinalizedTradeBus6464.excludeForAllCanonicalConsumers7097(
                        env.tradeId, "ECONOMICS_QUARANTINED_6495:$economicInvalid6495",
                    )
                    PipelineHealthCollector.labelInc("FINALIZED_BUS_PUBLISHED_EXCLUDED_ECONOMICS_7097")
                } catch (_: Throwable) {}
            }
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
