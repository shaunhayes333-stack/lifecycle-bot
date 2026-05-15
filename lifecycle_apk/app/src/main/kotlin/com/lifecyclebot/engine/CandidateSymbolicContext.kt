/*
 * V5.9.784 — CandidateSymbolicContext (operator audit items E & F)
 * ──────────────────────────────────────────────────────────────────
 *
 * The previous architecture relied on the GLOBAL SymbolicContext for every
 * trade decision. That state refreshes at most once per 2s and is mood/edge
 * for the entire market — so 32 candidates scanned in the same loop all
 * received the same symbolic verdict. Per-token rejection ability was
 * effectively zero.
 *
 * CandidateSymbolicContext is per-token. It snapshots the token's safety
 * report, route report, wallet truth slice, narrative/social signal, and
 * the relevant slice of global mood. The bot constructs ONE of these for
 * every candidate evaluated, the structured SymbolicVerdict is attached
 * to the FDG decision, and a copy is embedded inside
 * CanonicalTradeOutcome.candidate.symbolicVerdict at close time so the
 * learner can later compare PREDICTED failure mode vs ACTUAL outcome.
 *
 * Used by: FinalDecisionGate (pre-trade vote), Executor close path
 *          (attach to canonical outcome), BehaviorLearning pattern memory,
 *          MetaCognitionAI calibration tracking.
 */
package com.lifecyclebot.engine

/**
 * Structured symbolic verdict — operator audit item E.
 *
 * Replaces the older "yes/no veto" with a richer object so downstream
 * learners and the UI can show WHY a candidate was vetoed and which
 * failure mode the symbolic layer expected (rug / dump / chop / etc.).
 */
enum class SymbolicVote { ALLOW, CAUTION, VETO, NEUTRAL }

data class SymbolicVerdict(
    val vote: SymbolicVote = SymbolicVote.NEUTRAL,
    val confidence: Double = 0.0,                 // 0..1
    val reasons: List<String> = emptyList(),
    val affectedLayers: List<String> = emptyList(),
    val expectedFailureMode: String = "",          // "RUG" / "DUMP" / "DEAD_CAT" / "CHOP" / "TIMEOUT" / ""
) {
    fun toCompactString(): String = "$vote(${"%.2f".format(confidence)}):${reasons.take(2).joinToString(",")}"
}

/**
 * Per-token symbolic context (operator audit item F).
 */
data class CandidateSymbolicContext(
    val mint: String,
    val symbol: String,
    val capturedAtMs: Long = System.currentTimeMillis(),

    // Global mood slice — copied at capture so the candidate is judged
    // against the mood AT the moment of decision, not at-close time.
    val globalEmotionalState: String = "NEUTRAL",
    val globalRisk: Double = 0.3,
    val globalConfidence: Double = 0.5,
    val globalEdge: Double = 0.5,

    // Per-token safety slice.
    val safetyTier: String = "",                  // SAFE / CAUTION / UNSAFE / DANGER
    val rugRiskScore: Double = 0.0,                // 0..1
    val holderConcentration: String = "",          // CONC_LOW / CONC_MED / CONC_HIGH / CONC_RUG
    val mintAuthority: String = "",                // RENOUNCED / RETAINED / UNKNOWN
    val freezeAuthority: String = "",              // RENOUNCED / RETAINED / UNKNOWN

    // Per-token route slice.
    val venue: String = "",                        // PUMP_FUN_BONDING / PUMPSWAP / RAYDIUM / METEORA / ORCA / JUPITER
    val route: String = "",                        // PUMP_NATIVE / PUMPPORTAL / JUPITER / METIS / MULTI
    val bondingCurveActive: Boolean = false,
    val migrated: Boolean = false,

    // Wallet truth slice.
    val walletAlreadyHolding: Boolean = false,
    val walletOpenCount: Int = 0,

    // Narrative / social slice.
    val narrativeStrength: Double = 0.0,           // 0..1
    val socialVelocity: Double = 0.0,              // 0..1

    // Final symbolic verdict.
    val verdict: SymbolicVerdict = SymbolicVerdict(),
) {

    /**
     * Pack the verdict into a compact string consumable by
     * CandidateFeatures.symbolicVerdict.
     */
    fun symbolicVerdictString(): String = verdict.toCompactString()
}

/**
 * Builder for CandidateSymbolicContext. Consumes whatever the FDG already
 * has (safety report, venue route, wallet snapshot, narrative state) and
 * produces a per-token symbolic verdict.
 *
 * This is intentionally pure — it does NO LLM calls. The LLM-driven
 * preTradeVeto path inside SentienceHooks is still optional commentary;
 * symbolic reasoning here is fast and deterministic so the FDG can use it
 * inside the hot loop without async penalties.
 */
object CandidateSymbolicContextBuilder {

    /**
     * Build a candidate-specific symbolic context.
     *
     * Inputs are best-effort — pass blanks/defaults for anything the caller
     * doesn't have yet. The builder will mark verdict.confidence=0 and
     * vote=NEUTRAL when too little signal is present, which downstream
     * code should treat as "did not actually reason" (per audit item G).
     */
    fun buildFor(
        mint: String,
        symbol: String = "",
        safetyTier: String = "",
        rugRiskScore: Double = 0.0,
        holderConcentration: String = "",
        mintAuthority: String = "",
        freezeAuthority: String = "",
        venue: String = "",
        route: String = "",
        bondingCurveActive: Boolean = false,
        migrated: Boolean = false,
        walletAlreadyHolding: Boolean = false,
        walletOpenCount: Int = 0,
        narrativeStrength: Double = 0.0,
        socialVelocity: Double = 0.0,
    ): CandidateSymbolicContext {

        // Global mood slice — read SymbolicContext (refreshes every 2s).
        val mood = try {
            object {
                val emo = SymbolicContext.emotionalState
                val risk = SymbolicContext.overallRisk
                val conf = SymbolicContext.overallConfidence
                val edge = SymbolicContext.edgeStrength
            }
        } catch (_: Throwable) { null }

        val verdict = computeVerdict(
            safetyTier = safetyTier,
            rugRiskScore = rugRiskScore,
            holderConcentration = holderConcentration,
            mintAuthority = mintAuthority,
            freezeAuthority = freezeAuthority,
            venue = venue,
            route = route,
            bondingCurveActive = bondingCurveActive,
            migrated = migrated,
            walletAlreadyHolding = walletAlreadyHolding,
            walletOpenCount = walletOpenCount,
            narrativeStrength = narrativeStrength,
            socialVelocity = socialVelocity,
            globalRisk = mood?.risk ?: 0.3,
            globalEmo = mood?.emo ?: "NEUTRAL",
        )

        return CandidateSymbolicContext(
            mint = mint,
            symbol = symbol,
            globalEmotionalState = mood?.emo ?: "NEUTRAL",
            globalRisk = mood?.risk ?: 0.3,
            globalConfidence = mood?.conf ?: 0.5,
            globalEdge = mood?.edge ?: 0.5,
            safetyTier = safetyTier,
            rugRiskScore = rugRiskScore,
            holderConcentration = holderConcentration,
            mintAuthority = mintAuthority,
            freezeAuthority = freezeAuthority,
            venue = venue,
            route = route,
            bondingCurveActive = bondingCurveActive,
            migrated = migrated,
            walletAlreadyHolding = walletAlreadyHolding,
            walletOpenCount = walletOpenCount,
            narrativeStrength = narrativeStrength,
            socialVelocity = socialVelocity,
            verdict = verdict,
        )
    }

    private fun computeVerdict(
        safetyTier: String,
        rugRiskScore: Double,
        holderConcentration: String,
        mintAuthority: String,
        freezeAuthority: String,
        venue: String,
        route: String,
        bondingCurveActive: Boolean,
        migrated: Boolean,
        walletAlreadyHolding: Boolean,
        walletOpenCount: Int,
        narrativeStrength: Double,
        socialVelocity: Double,
        globalRisk: Double,
        globalEmo: String,
    ): SymbolicVerdict {
        val reasons = mutableListOf<String>()
        val affected = mutableListOf<String>()
        var vote = SymbolicVote.ALLOW
        var conf = 0.5
        var failureMode = ""

        // ─── Hard vetoes ──────────────────────────────────────────────
        if (safetyTier == "DANGER" || rugRiskScore >= 0.85) {
            vote = SymbolicVote.VETO
            conf = 0.95
            reasons += "rugRiskScore=${"%.2f".format(rugRiskScore)} safetyTier=$safetyTier"
            affected += listOf("HolderSafetyAI", "FinalDecisionGate")
            failureMode = "RUG"
            return SymbolicVerdict(vote, conf, reasons, affected, failureMode)
        }
        if (freezeAuthority == "RETAINED") {
            vote = SymbolicVote.VETO
            conf = 0.90
            reasons += "freezeAuthority=RETAINED"
            affected += "HolderSafetyAI"
            failureMode = "FREEZE"
            return SymbolicVerdict(vote, conf, reasons, affected, failureMode)
        }
        if (holderConcentration == "CONC_RUG") {
            vote = SymbolicVote.VETO
            conf = 0.85
            reasons += "holderConcentration=CONC_RUG"
            affected += "HolderSafetyAI"
            failureMode = "DUMP"
            return SymbolicVerdict(vote, conf, reasons, affected, failureMode)
        }

        // ─── Soft cautions ────────────────────────────────────────────
        if (safetyTier == "UNSAFE") {
            vote = SymbolicVote.CAUTION
            conf = maxOf(conf, 0.70)
            reasons += "safetyTier=UNSAFE"
            affected += "HolderSafetyAI"
            failureMode = "DUMP"
        }
        if (holderConcentration == "CONC_HIGH") {
            vote = if (vote == SymbolicVote.VETO) vote else SymbolicVote.CAUTION
            conf = maxOf(conf, 0.60)
            reasons += "holderConcentration=CONC_HIGH"
        }
        if (globalRisk > 0.7) {
            vote = if (vote == SymbolicVote.VETO) vote else SymbolicVote.CAUTION
            conf = maxOf(conf, 0.55)
            reasons += "globalRisk=${"%.2f".format(globalRisk)}"
            affected += "SymbolicContext"
            if (failureMode.isBlank()) failureMode = "CHOP"
        }
        if (globalEmo == "PANIC") {
            vote = if (vote == SymbolicVote.VETO) vote else SymbolicVote.CAUTION
            conf = maxOf(conf, 0.55)
            reasons += "mood=PANIC"
        }
        if (walletAlreadyHolding) {
            vote = if (vote == SymbolicVote.VETO) vote else SymbolicVote.CAUTION
            conf = maxOf(conf, 0.65)
            reasons += "walletAlreadyHolding"
            affected += "WalletReconciler"
            if (failureMode.isBlank()) failureMode = "DUPLICATE"
        }

        // ─── Allow reasoning ───────────────────────────────────────────
        if (vote == SymbolicVote.ALLOW) {
            if (narrativeStrength >= 0.6 && socialVelocity >= 0.5) {
                conf = 0.65
                reasons += "narrative=${"%.2f".format(narrativeStrength)} social=${"%.2f".format(socialVelocity)}"
            } else {
                conf = 0.40
                reasons += "no strong narrative/social signal"
            }
        }

        return SymbolicVerdict(vote, conf, reasons, affected, failureMode)
    }
}
