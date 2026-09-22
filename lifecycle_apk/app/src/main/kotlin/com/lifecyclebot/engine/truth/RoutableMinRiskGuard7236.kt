package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7236 §ROUTABLE_MIN_RISK_GUARD — operator diagnosis 5.0.7234:
 *
 *   "Risk sizing being overridden by routable-minimum lifting.
 *    CHOP says size×0.35, yet weak score 6–16 entries were lifted back
 *    to ~0.044–0.049 SOL. If risk-sized amount is below economic/
 *    routable minimum, SKIP the trade. Do not inflate a rejected/dust
 *    risk size into a large live position."
 *
 * PURPOSE — this authority is consulted by Executor.kt at the
 * LIVE_LAST_MILE_LIFTED_TO_ROUTABLE_MIN_7226 site. When the risk-sizer
 * has already shrunk an order below routableMin, the historical
 * response was to LIFT the order back to routableMin (because the
 * wallet CAN carry it under the concentration guard). Operator
 * evidence shows that lift path became the dominant loss producer:
 *
 *   • score=6 candidate lifted to 0.04445 SOL → closed −64.44%
 *   • score=6–16 candidates lifted to 0.044–0.049 SOL → most losses
 *
 * The regime multiplier / pending-proof clamp / low-score down-shift
 * are all forms of "the system does not trust this candidate". Lifting
 * a distrusted candidate back to routableMin destroys the risk-based
 * sizing entirely and turns weak signals into full-notional live
 * losses.
 *
 * VERDICT — REFUSE the lift and let the LIVE_LAST_MILE_SUB_ROUTABLE_
 * DUST_REFUSED_7227 branch take over whenever the caller reports a
 * "weak" candidate. ALLOW the lift only for candidates the system
 * actively trusts (high score, no pending-proof penalty, no aggressive
 * regime shrink).
 *
 * OPERATOR INTENT (chat 5.0.7234): "it needs to lift all round. its
 * not making live trades if it does the trade quality is shithouse. it
 * only loses."  Interpretation: LIFT quality across the board.
 * Concretely: refuse dust lifts on weak candidates — that is the
 * observed loss producer — and let strong candidates lift naturally.
 */
object RoutableMinRiskGuard7236 {

    /** Below this score, the candidate is considered weak and the lift
     *  is refused. 5.0.7234 evidence shows lifts on score 6, 14, 16
     *  producing −27%, −26%, −64% closes. A conservative threshold of
     *  30 puts the routable-minimum lift firmly in the "system-trusts-
     *  this-signal" regime. */
    private const val WEAK_SCORE_CEILING: Int = 30

    /** Below this regime multiplier, the size clamp itself is signalling
     *  environment distrust; lifting past that is a policy contradiction. */
    private const val WEAK_REGIME_CEILING: Double = 0.60

    enum class Verdict {
        ALLOW_LIFT,          // strong candidate, lift back to routableMin
        REFUSE_LIFT_WEAK,    // weak score/regime/pending-proof — refuse
    }

    data class Decision(
        val verdict: Verdict,
        val reason7236: String,
    )

    private val allowed = AtomicLong(0L)
    private val refusedWeakScore = AtomicLong(0L)
    private val refusedWeakRegime = AtomicLong(0L)
    private val refusedPendingProof = AtomicLong(0L)
    private val refusedComposite = AtomicLong(0L)

    /**
     * @param mint                   asset mint (short-hashed for logs)
     * @param symbol                 human-readable symbol for logs
     * @param lane                   canonical routed lane
     * @param score                  entry score at sizing time
     * @param regimeSizeMult         regime-derived size multiplier
     *                               (1.0 == neutral; <1.0 == regime-shrink)
     * @param livePendingProofPenalty true when the live oracle penalty
     *                                path is dampening size (5.0.6293)
     * @param riskSizedSol           the risk-shaped size before the
     *                                routable-min lift
     * @param routableMinSol         the executor's routable minimum
     */
    fun evaluate(
        mint: String,
        symbol: String,
        lane: String,
        score: Int,
        regimeSizeMult: Double,
        livePendingProofPenalty: Boolean,
        riskSizedSol: Double,
        routableMinSol: Double,
    ): Decision {
        val weakScore = score < WEAK_SCORE_CEILING
        val weakRegime = regimeSizeMult.isFinite() && regimeSizeMult > 0.0 && regimeSizeMult < WEAK_REGIME_CEILING
        val pendingProof = livePendingProofPenalty

        if (!weakScore && !weakRegime && !pendingProof) {
            allowed.incrementAndGet()
            try { PipelineHealthCollector.labelInc("ROUTABLE_MIN_LIFT_ALLOWED_STRONG_7236") } catch (_: Throwable) {}
            return Decision(Verdict.ALLOW_LIFT, "STRONG_CANDIDATE score=$score regime=${"%.2f".format(regimeSizeMult)}")
        }

        // Refuse the lift and let the DUST_REFUSED path emit the terminal.
        val reasons = buildList {
            if (weakScore) add("SCORE=${score}<${WEAK_SCORE_CEILING}")
            if (weakRegime) add("REGIME=${"%.2f".format(regimeSizeMult)}<${"%.2f".format(WEAK_REGIME_CEILING)}")
            if (pendingProof) add("PENDING_PROOF_PENALTY")
        }
        val composite = reasons.size >= 2
        when {
            composite -> refusedComposite.incrementAndGet()
            weakScore -> refusedWeakScore.incrementAndGet()
            weakRegime -> refusedWeakRegime.incrementAndGet()
            pendingProof -> refusedPendingProof.incrementAndGet()
        }
        try {
            PipelineHealthCollector.labelInc("ROUTABLE_MIN_LIFT_REFUSED_WEAK_7236")
            if (composite) PipelineHealthCollector.labelInc("ROUTABLE_MIN_LIFT_REFUSED_WEAK_7236_COMPOSITE")
            else if (weakScore) PipelineHealthCollector.labelInc("ROUTABLE_MIN_LIFT_REFUSED_WEAK_7236_SCORE")
            else if (weakRegime) PipelineHealthCollector.labelInc("ROUTABLE_MIN_LIFT_REFUSED_WEAK_7236_REGIME")
            else if (pendingProof) PipelineHealthCollector.labelInc("ROUTABLE_MIN_LIFT_REFUSED_WEAK_7236_PROOF")
            // Emit forensic sample line at low rate.
            val total = refusedComposite.get() + refusedWeakScore.get() +
                refusedWeakRegime.get() + refusedPendingProof.get()
            if (total % 20L == 1L) {
                ForensicLogger.lifecycle(
                    "ROUTABLE_MIN_LIFT_REFUSED_WEAK_7236",
                    "mint=${mint.take(10)} symbol=$symbol lane=$lane " +
                        "score=$score regime=${"%.2f".format(regimeSizeMult)} " +
                        "pendingProof=$pendingProof riskSized=${"%.5f".format(riskSizedSol)} " +
                        "routableMin=${"%.5f".format(routableMinSol)} " +
                        "reasons=${reasons.joinToString(",")} " +
                        "action=refuse_lift_defer_to_dust_refused_path",
                )
            }
        } catch (_: Throwable) {}
        return Decision(Verdict.REFUSE_LIFT_WEAK, reasons.joinToString(","))
    }

    data class Summary(
        val liftAllowed: Long,
        val refusedWeakScore: Long,
        val refusedWeakRegime: Long,
        val refusedPendingProof: Long,
        val refusedComposite: Long,
    ) {
        val totalRefused: Long get() = refusedWeakScore + refusedWeakRegime + refusedPendingProof + refusedComposite
    }

    fun summary(): Summary = Summary(
        liftAllowed = allowed.get(),
        refusedWeakScore = refusedWeakScore.get(),
        refusedWeakRegime = refusedWeakRegime.get(),
        refusedPendingProof = refusedPendingProof.get(),
        refusedComposite = refusedComposite.get(),
    )

    fun statusLine(): String {
        val s = summary()
        return "RoutableMinRiskGuard7236 lifted=${s.liftAllowed} " +
            "refused=${s.totalRefused} " +
            "(score=${s.refusedWeakScore},regime=${s.refusedWeakRegime}," +
            "proof=${s.refusedPendingProof},composite=${s.refusedComposite})"
    }

    internal fun clearForTest() {
        allowed.set(0L); refusedWeakScore.set(0L); refusedWeakRegime.set(0L)
        refusedPendingProof.set(0L); refusedComposite.set(0L)
    }
}
