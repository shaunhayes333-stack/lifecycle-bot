package com.lifecyclebot.engine.truth

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * V5.0.6398 — CANONICAL FLUID ENTRY PIPELINE.
 *
 * Single live-entry path:
 *   SCANNER DISCOVERY
 *     → INTAKE IDENTITY + SOURCE-NATIVE PAIR/POOL CAPTURE
 *     → TOKEN MAP / ROUTE / MARKET HYDRATION
 *     → LANE CANDIDATE ASSIGNMENT
 *     → TRADER + TACTIC ASSIGNMENT
 *     → CANONICAL SCORE ENVELOPE
 *     → CANONICAL DYNAMIC FLOOR ENVELOPE
 *     → FINAL DECISION GATE
 *     → IMMUTABLE ENTRY AUTHORITY TICKET
 *     → BUY LEASE
 *     → EXECUTOR / QUOTE / TRANSACTION
 *     → FILL PROOF + CANONICAL JOURNAL
 *
 * The pipeline is a pure composition: same inputs → same output. Any
 * side effects (dedupe cache, telemetry emission) live in dedicated
 * modules called by the pipeline.
 */
object CanonicalEntryPipeline6398 {

    const val SCORE_MODEL_VERSION: String = "V5.0.6398_STACKWIDE_SCORE"
    const val FLOOR_MODEL_VERSION: String = "V5.0.6398_FLUID_FLOOR"
    const val TICKET_TTL_MS: Long = 12_000L

    // -------- score envelope construction ----------------------------------

    data class ScoreInputs(
        val mint: String, val symbol: String,
        val lane: TraderLane, val trader: TraderId, val tactic: EntryTactic,
        val lifecycleStage: LifecycleStage,
        val sourceSet: Set<DiscoverySource>,
        val rawScore: Double,
        val componentScores: Map<String, Double>,
        val softPenalties: Map<String, Double>,
        val hardSafetyPassed: Boolean,
        val hardSafetyReasons: List<String>,
        val evidenceCompleteness: Double,       // 0..1
        val confidence: Double,                  // 0..1
        val dataVersion: Long,
    )

    fun buildScoreEnvelope(evaluationId: String, inp: ScoreInputs): EntryScoreEnvelope6398 {
        // effectiveScore = rawScore + sum(components) - sum(softPenalties)
        //                  * confidence * evidenceCompleteness weighting
        val componentSum = inp.componentScores.values.sum()
        val penaltySum = inp.softPenalties.values.sum()
        val weight = (inp.confidence.coerceIn(0.0, 1.0) *
                      inp.evidenceCompleteness.coerceIn(0.0, 1.0))
                     .coerceAtLeast(0.5)   // never zero out on missing evidence
        val effective = (inp.rawScore + componentSum - penaltySum) * weight
        return EntryScoreEnvelope6398(
            evaluationId = evaluationId, mint = inp.mint, symbol = inp.symbol,
            lane = inp.lane, trader = inp.trader, tactic = inp.tactic,
            lifecycleStage = inp.lifecycleStage, sourceSet = inp.sourceSet,
            rawScore = inp.rawScore, effectiveScore = effective,
            confidence = inp.confidence, evidenceCompleteness = inp.evidenceCompleteness,
            componentScores = inp.componentScores.toMap(),
            softPenalties = inp.softPenalties.toMap(),
            hardSafetyPassed = inp.hardSafetyPassed,
            hardSafetyReasons = inp.hardSafetyReasons.toList(),
            dataVersion = inp.dataVersion,
            scoreModelVersion = SCORE_MODEL_VERSION,
        )
    }

    // -------- floor envelope construction ----------------------------------

    data class FloorInputs(
        val lane: TraderLane, val trader: TraderId, val tactic: EntryTactic,
        val baseLaneFloor: Double,
        val governorDelta: Double = 0.0,
        val lanePerformanceDelta: Double = 0.0,
        val traderPerformanceDelta: Double = 0.0,
        val tacticPerformanceDelta: Double = 0.0,
        val lifecycleDelta: Double = 0.0,
        val regimeDelta: Double = 0.0,
        val personalityDelta: Double = 0.0,
        val evidenceDelta: Double = 0.0,
        val gateRelaxerDelta: Double = 0.0,
        val warmupDelta: Double = 0.0,
    )

    /** Bayesian shrinkage caps for performance deltas. */
    fun perfDeltaCap(sampleSize: Int): Double = when {
        sampleSize < 8 -> 2.0
        sampleSize <= 24 -> 5.0
        else -> 8.0
    }

    fun buildFloorEnvelope(evaluationId: String, inp: FloorInputs): DynamicFloorEnvelope6398 {
        val effective = (inp.baseLaneFloor
            + inp.governorDelta
            + inp.lanePerformanceDelta
            + inp.traderPerformanceDelta
            + inp.tacticPerformanceDelta
            + inp.lifecycleDelta
            + inp.regimeDelta
            + inp.personalityDelta
            + inp.evidenceDelta
            + inp.warmupDelta
            - inp.gateRelaxerDelta)
        // Clamp through the 6396 authority so no runtime path can restore
        // the legacy 55/56 anchor and no combination drops below ABSOLUTE_MIN.
        val clamped = LiveEntryThresholdAuthority6396.clampFloor(effective.roundToInt()).toDouble()
        return DynamicFloorEnvelope6398(
            evaluationId = evaluationId,
            lane = inp.lane, trader = inp.trader, tactic = inp.tactic,
            baseLaneFloor = inp.baseLaneFloor,
            governorDelta = inp.governorDelta,
            lanePerformanceDelta = inp.lanePerformanceDelta,
            traderPerformanceDelta = inp.traderPerformanceDelta,
            tacticPerformanceDelta = inp.tacticPerformanceDelta,
            lifecycleDelta = inp.lifecycleDelta,
            regimeDelta = inp.regimeDelta,
            personalityDelta = inp.personalityDelta,
            evidenceDelta = inp.evidenceDelta,
            gateRelaxerDelta = inp.gateRelaxerDelta,
            warmupDelta = inp.warmupDelta,
            effectiveFloor = clamped,
            floorModelVersion = FLOOR_MODEL_VERSION,
        )
    }

    // -------- decision + ticket --------------------------------------------

    fun decide(
        score: EntryScoreEnvelope6398,
        floor: DynamicFloorEnvelope6398,
        hydrationHardUnavailable: Boolean = false,
    ): EntryAuthorityDecision6398 {
        val (outcome, reason, ttl) = when {
            !score.hardSafetyPassed -> Triple(
                EntryOutcome.HARD_SAFETY_VETO,
                "HARD_SAFETY_VETO ${score.hardSafetyReasons.joinToString(",").take(120)}",
                null,
            )
            hydrationHardUnavailable -> Triple(
                EntryOutcome.INTAKE_BLOCK, "PAIR_HARD_UNAVAILABLE", null)
            score.evidenceCompleteness < 0.4 -> Triple(
                EntryOutcome.HYDRATION_DEFERRED, "EVIDENCE_INCOMPLETE", 6_000L)
            score.effectiveScore < floor.effectiveFloor -> Triple(
                EntryOutcome.ENTRY_GATE_BLOCK,
                "SCORE_BELOW_DYNAMIC_FLOOR score=${score.effectiveScore.roundToInt()} floor=${floor.effectiveFloor.roundToInt()}",
                30_000L,   // 30..60s bounded reeval TTL
            )
            else -> Triple(EntryOutcome.ALLOW, "ADMITTED", null)
        }
        return EntryAuthorityDecision6398(
            evaluationId = score.evaluationId, score = score, floor = floor,
            outcome = outcome, reason = reason, reevaluateAfterMs = ttl,
        )
    }

    fun mintTicket(
        decision: EntryAuthorityDecision6398, sizingMultiplier: Double = 1.0,
        ttlMs: Long = TICKET_TTL_MS, nowMs: Long = System.currentTimeMillis(),
    ): EntryAuthorityTicket6398? {
        if (decision.outcome != EntryOutcome.ALLOW) return null
        return EntryAuthorityTicket6398(
            ticketId = "TCK_${UUID.randomUUID().toString().take(12)}",
            evaluationId = decision.evaluationId,
            mint = decision.score.mint, lane = decision.score.lane,
            trader = decision.score.trader, tactic = decision.score.tactic,
            effectiveScore = decision.score.effectiveScore,
            effectiveFloor = decision.floor.effectiveFloor,
            sizingMultiplier = sizingMultiplier,
            expiresAtMs = nowMs + ttlMs,
            scoreModelVersion = decision.score.scoreModelVersion,
            floorModelVersion = decision.floor.floorModelVersion,
            dataVersion = decision.score.dataVersion,
        )
    }

    // -------- ticket validation (used by executor / safety hold) ------------

    data class TicketValidation(val ok: Boolean, val reason: String)

    fun validateTicket(
        ticket: EntryAuthorityTicket6398?,
        decision: EntryAuthorityDecision6398?,
        currentDataVersion: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): TicketValidation {
        if (ticket == null) return TicketValidation(false, "AUTHORITY_TICKET_MISSING")
        if (decision == null) return TicketValidation(false, "AUTHORITY_DECISION_MISSING")
        if (ticket.evaluationId != decision.evaluationId)
            return TicketValidation(false, "EVAL_ID_MISMATCH")
        if (ticket.isExpired(nowMs)) return TicketValidation(false, "TICKET_EXPIRED")
        if (ticket.effectiveScore < ticket.effectiveFloor)
            return TicketValidation(false, "SCORE_BELOW_FLOOR_ON_TICKET")
        if (ticket.dataVersion != currentDataVersion)
            return TicketValidation(false, "STALE_DATA_VERSION")
        return TicketValidation(true, "TICKET_OK")
    }

    // -------- authority store (created tickets by mint) --------------------

    val ticketsCreated = AtomicLong(0L)
    val ticketsExpired = AtomicLong(0L)
    val ticketsInvalid = AtomicLong(0L)

    private val activeTickets = ConcurrentHashMap<String, EntryAuthorityTicket6398>()

    fun issueAndRegister(
        decision: EntryAuthorityDecision6398,
        sizingMultiplier: Double = 1.0,
        routeMode: RouteMode6399 = RouteMode6399.LIVE,
        isDenylisted: Boolean = false,
        isShadowOnly: Boolean = false,
    ): EntryAuthorityTicket6398? {
        // V5.0.6399 — HARD INVARIANT: ticket may only exist for
        // outcome=ALLOW && routeMode=LIVE && !denylisted && !shadowOnly &&
        // effectiveScore >= effectiveFloor. Any violation throws
        // AUTHORITY_INVARIANT_FAILURE_6399 and no ticket is minted.
        if (decision.outcome != EntryOutcome.ALLOW) return null
        try {
            AuthorityInvariants6399.assertAllowLiveBeforeTicket(
                outcome = FdgTerminalOutcome6399.FDG_ALLOW_LIVE,
                effectiveScore = decision.score.effectiveScore,
                effectiveFloor = decision.floor.effectiveFloor,
                routeMode = routeMode,
                isDenylisted = isDenylisted,
                isShadowOnly = isShadowOnly,
                mint = decision.score.mint,
            )
        } catch (_: IllegalStateException) {
            return null
        }
        val t = mintTicket(decision, sizingMultiplier) ?: return null
        activeTickets[t.mint] = t
        ticketsCreated.incrementAndGet()
        try {
            com.lifecyclebot.engine.truth.CounterParityLedger6399.recordLiveTicketIssued()
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ENTRY_AUTHORITY_TICKET_CREATED_6398")
        } catch (_: Throwable) {}
        return t
    }

    fun findTicketFor(mint: String, nowMs: Long = System.currentTimeMillis()): EntryAuthorityTicket6398? {
        val t = activeTickets[mint] ?: return null
        if (t.isExpired(nowMs)) {
            activeTickets.remove(mint); ticketsExpired.incrementAndGet(); return null
        }
        return t
    }

    fun revokeTicketFor(mint: String) {
        activeTickets.remove(mint)?.let { ticketsInvalid.incrementAndGet() }
    }

    internal fun clearAllForTest() {
        activeTickets.clear()
        ticketsCreated.set(0L); ticketsExpired.set(0L); ticketsInvalid.set(0L)
    }
}
