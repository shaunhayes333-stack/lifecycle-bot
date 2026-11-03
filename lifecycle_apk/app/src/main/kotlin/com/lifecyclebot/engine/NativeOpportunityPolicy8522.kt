package com.lifecyclebot.engine

import kotlin.math.abs

/** Operator-selected opportunity policy. Raw native/Cortex predictions and learned authority stay unchanged. */
object NativeOpportunityPolicy8522 {
    enum class Action { TAKE_CANDIDATE, REFUSE, ABSTAIN }

    data class Decision(
        val mint: String,
        val lane: String,
        val mode: String,
        val candidateVersion: Long,
        val featureRevision: Long,
        val snapshotAtMs: Long,
        val assessmentAtMs: Long,
        val inputAtMs: Long,
        val action: Action,
        val originalEligible: Boolean,
        val originalReason: String,
        val selectionPriority: Double?,
        val source8522: com.lifecyclebot.engine.cortex.CortexOpportunityPolarity8522.Source? = null,
    ) {
        val reason: String get() = "OPERATOR_INVERSE_8522_${action.name}:$originalReason"
    }

    /** These are late opportunity judgements, after the named lane's physical/mandate checks. */
    fun opportunityReason8522(laneRaw: String, reason: String): Boolean {
        val lane = laneRaw.trim().uppercase().replace("BLUE_CHIP", "BLUECHIP")
        val r = reason.trim().uppercase().replace(' ', '_')
        if (lane == "CORE" && r == "CORE_YIELD_CLEAR_SPECIALIST_OWNER") return true
        if (NativeLaneConstraints8521.kind8521(reason) != null) return false
        return when (lane) {
            "QUALITY" -> r.startsWith("QUALITY_SCORE_TOO_LOW:")
            "BLUECHIP" -> r.startsWith("THRESHOLD_FAIL:")
            "SHITCOIN" -> r.startsWith("THRESHOLD_FAIL:") || r.startsWith("WR_RECOVERY_SCORE_FLOOR:")
            "EXPRESS" -> r.startsWith("SCORE_TOO_LOW:") || r == "NO_QUALIFIED_RIDE" ||
                r.startsWith("NO_CONTINUATION:") || r.startsWith("CHASE_EXTENDED:") || r.startsWith("WEAK_BUYS:")
            "MANIPULATED" -> r.startsWith("SCORE_TOO_LOW_")
            "DIP_HUNTER" -> r.startsWith("CONFIDENCE_LOW:") || r == "NO_QUALITY_DIP"
            "PROJECT_SNIPER" -> r.startsWith("LOW_CONFIDENCE:")
            "CASHGEN" -> cashflowScoreReason8522(r)
            "TREASURY" -> cashflowScoreReason8522(r) || (r.startsWith("NEUTRAL_MOMENTUM;") && r.endsWith("NO_CONFIRMED_SCALP_SETUP_7547") &&
                r.split(';').all { it in setOf("NEUTRAL_MOMENTUM", "STEADY_VELOCITY", "NO_CONFIRMED_SCALP_SETUP_7547") ||
                    it.matches(Regex("(?:BALANCED_BOOK|POSITIVE_PRESSURE|STRONG_BUY_PRESSURE)_[0-9]+")) })
            "CORE" -> r == RunnerGrabLane8343.CORE_OWN_BAR_8343
            else -> false
        }
    }

    private fun cashflowScoreReason8522(reason: String): Boolean = reason.startsWith("REJECTED:_") &&
        reason.removePrefix("REJECTED:_").split(',').all { component ->
            val r = component.trim('_')
            r.matches(Regex("(?:SCORE|CONF)=-?[0-9]+<-?[0-9]+")) ||
                r.matches(Regex("WR_RECOVERY_SCORE_FLOOR=-?[0-9]+<-?[0-9]+_\\(BASE=[0-9]+\\+[0-9]+_BAND=(?:AGGRESSIVE|MODERATE)\\)"))
        }

    fun canConsider8522(opinion: SpecialistBrainBridge7542.Opinion): Boolean =
        opinion.authoritative && opinion.gradeable &&
            (opinion.eligible || opportunityReason8522(opinion.lane, opinion.reason))

    /** The Cortex API has already projected its original bucket; consume that action exactly once. */
    fun decision8522(opinion: SpecialistBrainBridge7542.Opinion, action: String, rawEdgePct: Double,
                     mint: String, mode: String, candidateVersion: Long, featureRevision: Long, snapshotAtMs: Long,
                     assessmentAtMs: Long, inputAtMs: Long, constraintsPassed: Boolean): Decision? {
        if (!constraintsPassed || !canConsider8522(opinion)) return null
        val projected = when (action) {
            "TAKE_CANDIDATE" -> Action.TAKE_CANDIDATE
            "REFUSE" -> Action.REFUSE
            else -> return null
        }
        if (!rawEdgePct.isFinite() || mint.isBlank() || candidateVersion <= 0L || featureRevision < 0L ||
            mode !in setOf("PAPER", "LIVE") || snapshotAtMs <= 0L ||
            snapshotAtMs - assessmentAtMs !in 0L..5_000L || snapshotAtMs - inputAtMs !in 0L..5_000L) return null
        // A separate operator priority selects inverse candidates without rewriting score or confidence.
        val priority = if (projected == Action.TAKE_CANDIDATE) 50.0 + abs(rawEdgePct).coerceAtMost(50.0) else null
        return Decision(mint, opinion.lane, mode, candidateVersion, featureRevision, snapshotAtMs, assessmentAtMs, inputAtMs,
            projected, opinion.eligible, opinion.reason, priority)
    }

    fun apply8522(original: SpecialistBrainBridge7542.Opinion, decision: Decision?): SpecialistBrainBridge7542.Opinion {
        if (decision == null || decision.lane != original.lane || decision.originalEligible != original.eligible ||
            decision.originalReason != original.reason || !canConsider8522(original)) return original
        return when (decision.action) {
            Action.TAKE_CANDIDATE -> original.copy(eligible = true, reason = decision.reason,
                selectionPriority8522 = decision.selectionPriority)
            Action.REFUSE -> if (original.eligible) original.copy(eligible = false, reason = decision.reason) else original
            Action.ABSTAIN -> original
        }
    }

    /** A policy result is a current candidate decision, never a reusable exemption for this mint/lane. */
    fun current8522(decision: Decision?, mint: String, lane: String, mode: String, candidateVersion: Long, featureRevision: Long,
                    nowMs: Long): Decision? = decision?.takeIf {
        it.mint == mint && it.lane == lane.trim().uppercase().replace("BLUE_CHIP", "BLUECHIP") &&
            it.mode == mode && it.candidateVersion == candidateVersion && it.featureRevision == featureRevision &&
            nowMs - it.snapshotAtMs in 0L..2_500L && nowMs - it.assessmentAtMs in 0L..5_000L &&
            nowMs - it.inputAtMs in 0L..5_000L
    }

    /** Re-use the frozen choice, while a new native physical/data/mandate refusal still stops the door. */
    fun doorEligible8522(nativeEligible: Boolean, nativeReason: String, decision: Decision?): Boolean = when {
        decision == null -> nativeEligible
        decision.action == Action.REFUSE -> false
        decision.action != Action.TAKE_CANDIDATE -> nativeEligible
        nativeEligible -> true
        else -> opportunityReason8522(decision.lane, nativeReason)
    }

    fun selectionPriority8522(opinion: SpecialistBrainBridge7542.Opinion): Double =
        opinion.selectionPriority8522?.takeIf { it.isFinite() }
            ?: opinion.ownershipConviction7948?.takeIf { it.isFinite() }
            ?: maxOf(opinion.score, opinion.confidence).toDouble()
}
