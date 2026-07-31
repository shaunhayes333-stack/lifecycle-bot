package com.lifecyclebot.engine.truth

/**
 * V5.0.6398 — CANONICAL FLUID ENTRY AUTHORITY MODELS.
 *
 * Immutable envelopes carried unchanged through the single live-entry
 * pipeline: SCORE_ENVELOPE_CREATED → FLOOR_ENVELOPE_CREATED →
 * ENTRY_AUTHORITY_DECISION → ENTRY_AUTHORITY_TICKET_CREATED.
 *
 * Downstream stages (Executor, LiveEntrySafetyHold_6312 residual, buy
 * lease acquisition) MUST NOT recalculate score or floor — they only
 * validate the ticket.
 */

// -------- enums shared across envelopes -------------------------------------

enum class TraderLane { SHITCOIN, MOONSHOT, QUALITY, BLUECHIP, TREASURY, PROJECT_SNIPER, WARMUP }
enum class TraderId { MEME, QUALITY, TREASURY, DEGEN, CONVICTION, SNIPER }
enum class EntryTactic { STANDARD, REACCUMULATION, MICRO_PROBE, ROTATION, DIPBUY, BREAKOUT }
enum class LifecycleStage { DISCOVERY, HYDRATION, LANE_EVAL, GATE, EXECUTION }
enum class DiscoverySource { PUMPFUN, RAYDIUM, JUPITER, HELIUS, DEXSCREENER, BIRDEYE, WATCHLIST, USER }
enum class EntryOutcome { ALLOW, ENTRY_GATE_BLOCK, HYDRATION_DEFERRED, LANE_EVAL_BLOCK, FDG_BLOCK, HARD_SAFETY_VETO, INTAKE_BLOCK }

// -------- score envelope ----------------------------------------------------

data class EntryScoreEnvelope6398(
    val evaluationId: String,
    val mint: String,
    val symbol: String,
    val lane: TraderLane,
    val trader: TraderId,
    val tactic: EntryTactic,
    val lifecycleStage: LifecycleStage,
    val sourceSet: Set<DiscoverySource>,
    val rawScore: Double,
    val effectiveScore: Double,
    val confidence: Double,
    val evidenceCompleteness: Double,
    val componentScores: Map<String, Double>,
    val softPenalties: Map<String, Double>,
    val hardSafetyPassed: Boolean,
    val hardSafetyReasons: List<String>,
    val dataVersion: Long,
    val scoreModelVersion: String,
    val calculatedAtMs: Long = System.currentTimeMillis(),
)

// -------- floor envelope ----------------------------------------------------

data class DynamicFloorEnvelope6398(
    val evaluationId: String,
    val lane: TraderLane,
    val trader: TraderId,
    val tactic: EntryTactic,
    val baseLaneFloor: Double,
    val governorDelta: Double,
    val lanePerformanceDelta: Double,
    val traderPerformanceDelta: Double,
    val tacticPerformanceDelta: Double,
    val lifecycleDelta: Double,
    val regimeDelta: Double,
    val personalityDelta: Double,
    val evidenceDelta: Double,
    val gateRelaxerDelta: Double,
    val warmupDelta: Double,
    val effectiveFloor: Double,
    val floorModelVersion: String,
    val calculatedAtMs: Long = System.currentTimeMillis(),
)

// -------- decision + ticket -------------------------------------------------

data class EntryAuthorityDecision6398(
    val evaluationId: String,
    val score: EntryScoreEnvelope6398,
    val floor: DynamicFloorEnvelope6398,
    val outcome: EntryOutcome,
    val reason: String,
    val reevaluateAfterMs: Long?,
    val decisionAtMs: Long = System.currentTimeMillis(),
)

data class EntryAuthorityTicket6398(
    val ticketId: String,
    val evaluationId: String,
    val mint: String,
    val lane: TraderLane,
    val trader: TraderId,
    val tactic: EntryTactic,
    val effectiveScore: Double,
    val effectiveFloor: Double,
    val sizingMultiplier: Double,
    val expiresAtMs: Long,
    val scoreModelVersion: String,
    val floorModelVersion: String,
    val dataVersion: Long,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}
