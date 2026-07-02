package com.lifecyclebot.engine

/**
 * V5.0.6026 — FDG brain-chain agreeability layer.
 *
 * FDG should not be a pile of independent obstruction gates. This tiny brain-chain
 * reconciles the data path: lane/Toolkit score, UnifiedPolicyHead authority,
 * meta-cognition trust, orthogonal signal agreement, clean strategy performance,
 * common-sense mechanics, and AntiChoke state.
 *
 * Hard safety remains outside this class. A negative/common-sense objection is a
 * mechanic signal, not an outright obstruction unless the hard-safety layer already
 * blocked. When most of the chain agrees, a lone soft blocker should size-shape or
 * become a probe, not blind-block throughput.
 */
object FdgBrainChain {
    enum class Verdict { ALIGNED, CONFLICTED, BLOCKING }

    data class Report(
        val verdict: Verdict,
        val agreeVotes: Int,
        val disagreeVotes: Int,
        val score: Double,
        val reasons: List<String>,
        val softenSoftBlocks: Boolean,
        val sizeMultiplier: Double,
    ) {
        val compact: String
            get() = "verdict=$verdict agree=$agreeVotes disagree=$disagreeVotes score=${"%.2f".format(score)} soften=$softenSoftBlocks size×${"%.2f".format(sizeMultiplier)} reasons=${reasons.joinToString("|").take(220)}"
    }

    fun evaluate(
        lane: String,
        candidateScore: Double,
        laneScore: Double,
        policyAuthority: UnifiedPolicyHead.AuthorityTier,
        metaCogMult: Double,
        orthogonalAgreement: Double?,
        orthogonalComposite: Double?,
        cleanTrades: Int,
        cleanWinRate: Double,
        cleanPnlSol: Double,
        profitFactor: Double,
        commonSenseBlocked: Boolean,
        commonSenseReason: String?,
        antiChokeSoftening: Boolean,
    ): Report {
        val agree = mutableListOf<String>()
        val disagree = mutableListOf<String>()
        val raw = candidateScore.coerceIn(0.0, 100.0)
        val laneSig = laneScore.coerceIn(0.0, 100.0)

        if (laneSig >= raw + 8.0 && laneSig >= 35.0) agree += "lane_consensus:${laneSig.toInt()}gt_raw:${raw.toInt()}"
        else if (raw < 20.0 && laneSig < 25.0) disagree += "raw_and_lane_weak:${raw.toInt()}/${laneSig.toInt()}"

        when (policyAuthority) {
            UnifiedPolicyHead.AuthorityTier.AUTHORITATIVE -> agree += "policy_authoritative"
            UnifiedPolicyHead.AuthorityTier.LEARNED -> agree += "policy_learned"
            UnifiedPolicyHead.AuthorityTier.ADVISORY -> agree += "policy_advisory"
            UnifiedPolicyHead.AuthorityTier.BOOTSTRAP -> agree += "policy_bootstrap_trade1"
        }

        if (metaCogMult >= 1.02) agree += "metacog_trust:${"%.2f".format(metaCogMult)}"
        else if (metaCogMult <= 0.96) disagree += "metacog_damp:${"%.2f".format(metaCogMult)}"

        if (orthogonalAgreement != null) {
            when {
                orthogonalAgreement >= 0.70 && (orthogonalComposite ?: 0.0) >= -8.0 -> agree += "orthogonal_agree:${(orthogonalAgreement * 100).toInt()}"
                orthogonalAgreement < 0.45 && (orthogonalComposite ?: 0.0) < -10.0 -> disagree += "orthogonal_conflict:${(orthogonalAgreement * 100).toInt()}"
            }
        }

        val cleanPositive = cleanTrades < 5 || cleanPnlSol >= 0.0 || profitFactor >= 1.0 || cleanWinRate >= 35.0
        if (cleanPositive) agree += "clean_perf_ok:n=$cleanTrades wr=${cleanWinRate.toInt()} pnl=${"%.4f".format(cleanPnlSol)}"
        else disagree += "clean_perf_bad:n=$cleanTrades wr=${cleanWinRate.toInt()} pnl=${"%.4f".format(cleanPnlSol)}"

        if (commonSenseBlocked) {
            // Common sense is a mechanic/advisor unless it is backed by hard safety.
            // It should shape caution, not blind-obstruct an aligned chain.
            disagree += "common_sense:${commonSenseReason?.take(60) ?: "blocked"}"
        } else {
            agree += "common_sense_clear"
        }

        if (antiChokeSoftening) agree += "antichoke_softening"

        val agreeVotes = agree.size
        val disagreeVotes = disagree.size
        val score = (agreeVotes.toDouble() - disagreeVotes.toDouble()) / (agreeVotes + disagreeVotes).coerceAtLeast(1).toDouble()
        val aligned = agreeVotes >= 3 && score >= 0.20
        val blocking = disagreeVotes >= 4 && score <= -0.35 && !antiChokeSoftening
        val verdict = when {
            blocking -> Verdict.BLOCKING
            aligned -> Verdict.ALIGNED
            else -> Verdict.CONFLICTED
        }
        val soften = antiChokeSoftening || (verdict == Verdict.ALIGNED && disagreeVotes <= 2)
        val size = when (verdict) {
            Verdict.ALIGNED -> if (commonSenseBlocked) 0.72 else 1.0
            Verdict.CONFLICTED -> 0.55
            Verdict.BLOCKING -> 0.35
        }
        return Report(verdict, agreeVotes, disagreeVotes, score, agree + disagree, soften, size)
    }

    fun statusLine(report: Report?): String = report?.compact ?: "fdg_brain_chain=unavailable"
}
