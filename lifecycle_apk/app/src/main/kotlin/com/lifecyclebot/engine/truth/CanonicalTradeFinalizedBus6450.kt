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
        /**
         * V5.0.7103 §PHASE_0_AN_EPOCH_MUST_BE_NAMEABLE.
         *
         * Which build produced this terminal. Nothing can be retracted that
         * cannot first be identified, and a poisoned cohort is always of the
         * form "everything settled while defect X was live" — which, before
         * this field, could only be named by wall-clock time. Time is a proxy:
         * it does not survive a reinstall, it says nothing about which code
         * wrote the row, and it cannot distinguish a device that sat on an old
         * build for a day from one that updated an hour in.
         *
         * Stamped once at the producer, carried into
         * CanonicalFinalityPersistence6486 and readable from the durable record
         * forever. Rows written before 7103 decode as "pre_7103" and are
         * addressable as exactly that, rather than as an empty string that
         * could mean anything.
         *
         * See docs/AATE_UNLEARNING_SCOPE_7103.md. This field enables Phase 1;
         * it does not retract anything on its own and nothing reads it as a
         * gate.
         */
        val producedByVersion7103: String =
            CanonicalTradeFinalizedBus6450.currentBuildVersion7103(),
    )

    /** V5.0.7103 — the producing build, resolved once and never thrown. */
    private val buildVersion7103: String by lazy {
        try { com.lifecyclebot.BuildConfig.VERSION_NAME.ifBlank { "unknown" } }
        catch (_: Throwable) { "unknown" }
    }

    internal fun currentBuildVersion7103(): String = buildVersion7103

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

    /**
     * V5.0.7776 — pure check: does a signed-buy basis agree with itself? The entry
     * price in SOL (via EconomicUnitInvariant7061) must be within 2x of cost / qty.
     */
    internal fun signedBasisConsistent7776(pos: CanonicalPositionAuthority6441.Position?): Boolean {
        if (pos == null) return false
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        return basisAgrees7776(pos.entryCostSol, pos.originalQtyRaw, pos.tokenDecimals, pos.entryPriceUsd, solUsd)
    }

    internal fun basisAgrees7776(costSol: Double, qtyRaw: java.math.BigInteger, decimals: Int, entryPriceUsd: Double, solUsd: Double): Boolean {
        if (!costSol.isFinite() || costSol <= 0.0 || qtyRaw.signum() <= 0 || decimals !in 0..24) return false
        val qtyUi = java.math.BigDecimal(qtyRaw).movePointLeft(decimals).toDouble()
        if (!qtyUi.isFinite() || qtyUi <= 0.0) return false
        val entrySol = EconomicUnitInvariant7061.usdToSol(entryPriceUsd, solUsd)
        if (!entrySol.isFinite() || entrySol <= 0.0) return false
        val ratio = (costSol / qtyUi) / entrySol
        return ratio in 0.5..2.0
    }

    fun publish(event: Event): Boolean {
        if (event.positionId.isBlank()) return false
        try { CanonicalRewardBootstrap6453.ensureBootstrapped() } catch (_: Throwable) {}
        val prior = finalized.putIfAbsent(event.positionId, event.settledAtMs)
        val richDuplicate7473 = prior != null
        if (richDuplicate7473) {
            duplicates.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_TRADE_FINALIZE_DUPLICATE_6450",
                    "positionId=${event.positionId.take(12)} priorAtMs=$prior newAtMs=${event.settledAtMs}",
                )
                PipelineHealthCollector.labelInc("CANONICAL_TRADE_FINALIZE_DUPLICATE_6450")
                PipelineHealthCollector.labelInc("CANONICAL_FINALITY_DUPLICATE_REDRIVE_7473")
            } catch (_: Throwable) {}
            // Keep rich persistence/subscribers exactly-once, but continue into
            // the canonical 6464 projection below in case the first projection
            // failed after the rich finalization had already been claimed.
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
        if (economicInvalid6495 == null && !richDuplicate7473) {
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
            // V5.0.7722 — a close whose entry basis was inferred (adopted from the
            // wallet at an observed mark, rebuilt from a signed buy, recovered with
            // no receipt) has a real exit and a fictional cost. 5.0.7720 sold an
            // adopted CRYPTO_SPOT row at TICK_CATASTROPHIC_CONFIRMED_-54PCT against
            // entry=183.80 cost=0.0984 qty=0.02906 (cost x SOL / qty = $406, a price
            // the asset never traded at) and booked -0.055 SOL into canonical
            // performance. The sale is real money; the P&L is not evidence about
            // the entry. Such rows stay on the bus for the audit and the dashboard
            // and are excluded from every learner, exactly like malformed
            // economics (7097).
            val inferredBasis7722: String? = try {
                val pos7776 = CanonicalPositionAuthority6441.getPosition(event.positionId)
                val src7722 = pos7776?.entryPriceSource?.uppercase() ?: ""
                when {
                    src7722.contains("OBSERVED_MARK_ADOPTION_7706") -> "OBSERVED_MARK_ADOPTION_7706"
                    // V5.0.7776 — the bot's own signed buy (and the fill-registry rebuild)
                    // carries a real cost; it teaches when that cost, the quantity and the
                    // entry price agree. The 7720 row that motivated 7722 did not (implied
                    // $406 against a real price far below), and still would not pass.
                    (src7722.contains("HOST_TRACKER_SIGNED_BUY_7708") || src7722.contains("CANONICAL_BUY_FILL_RECOVERY_6686")) &&
                        signedBasisConsistent7776(pos7776) -> null
                    src7722.contains("HOST_TRACKER_SIGNED_BUY_7708") -> "HOST_TRACKER_SIGNED_BUY_7708"
                    src7722.startsWith("WALLET_RECOVERY") || src7722.startsWith("WALLET_ADOPT") -> "WALLET_RECOVERY"
                    src7722.contains("BASIS_UNKNOWN") -> "BASIS_UNKNOWN"
                    src7722.contains("RECOVERY_6686") -> "RECOVERY_6686"
                    else -> null
                }
            } catch (_: Throwable) { null }
            if (event.mode.equals("LIVE", ignoreCase = true)) try { LiveEducationAudit7776.onBusLive(inferredBasis7722) } catch (_: Throwable) {}
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
                entryTradeType = entrySnap6567?.entryTradeType ?: "",
                entrySetup = entrySnap6567?.entrySetup ?: "",
                entryStyle = entrySnap6567?.entryStyle ?: "",
                entryEntryStyle = entrySnap6567?.entryEntryStyle ?: "",
                entryExitStyle = entrySnap6567?.entryExitStyle ?: "",
                entryStrategyVariantId = entrySnap6567?.entryStrategyVariantId ?: "",
                entrySource = entrySnap6567?.entrySource ?: "",
                marketRegime = entrySnap6567?.entryMarketRegime ?: "",
                scoreBand = com.lifecyclebot.engine.LosingPatternMemory.scoreBand(entryScore6567),
                terminal = true,
                // V5.0.7097 — malformed settled economics can never be trainable,
                // whatever PaperLearningEligibility6519 thinks of the position.
                learningEligible = learningEligibility6519.eligible && economicInvalid6495 == null && inferredBasis7722 == null,
                learningEligibilityReason = if (economicInvalid6495 != null)
                    "ECONOMICS_QUARANTINED_6495:$economicInvalid6495"
                else if (inferredBasis7722 != null)
                    "INFERRED_BASIS_7722:$inferredBasis7722"
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
            if (economicInvalid6495 == null && inferredBasis7722 != null) {
                try {
                    CanonicalFinalizedTradeBus6464.excludeForAllCanonicalConsumers7097(
                        env.tradeId, "INFERRED_BASIS_7722:$inferredBasis7722",
                    )
                    PipelineHealthCollector.labelInc("FINALIZED_BUS_PUBLISHED_EXCLUDED_INFERRED_BASIS_7722")
                    PipelineHealthCollector.labelInc("FINALIZED_BUS_PUBLISHED_EXCLUDED_INFERRED_BASIS_7722_${event.entryLane.trim().uppercase().take(20)}")
                    ForensicLogger.lifecycle(
                        "FINALIZED_LEARNING_EXCLUDED_INFERRED_BASIS_7722",
                        "positionId=${event.positionId.take(16)} lane=${event.entryLane} basis=$inferredBasis7722 " +
                            "pnlSol=${"%.5f".format(event.netRealizedPnlSol)} retPct=${"%.1f".format(event.netReturnPct)} " +
                            "action=row_kept_for_audit_and_dashboard_excluded_from_learners",
                    )
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
        return !richDuplicate7473
    }

    fun statusLine(): String = "subs=${subscribers.size} published=${published.get()} " +
        "duplicates=${duplicates.get()} subFail=${subscriberFailures.get()} quarantinedEconomics6495=${quarantinedEconomics6495.get()}"
}
