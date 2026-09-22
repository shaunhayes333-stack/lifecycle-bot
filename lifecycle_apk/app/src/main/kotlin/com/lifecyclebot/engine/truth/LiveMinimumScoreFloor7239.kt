package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7239 §LIVE_MINIMUM_SCORE_FLOOR — operator screenshot 5.0.7237
 * and diagnosis 5.0.7234:
 *
 *   "It can trade perfectly fine in paper — find winners easily, no
 *    problem, moonshots 500% wins. Live: it only loses."
 *
 *   Live loss cluster (5.0.7234 CSV):
 *     3dihkgvU  score=6   →  −64.44%
 *     CHHsJq     score≤16 →  −27.79%
 *     FMpbX9     score≤16 →  −26.25%
 *     1Esrod     score≤16 →  −27.78%
 *
 * ROOT CAUSE — paper wins at score 50+ moonshots; live is firing at
 * score 6-16 because those are the candidates that finished hydrating
 * their safety proofs (Rugcheck / Birdeye / holder distribution) FIRST.
 * The live gate accepts "first fully-safe candidate in the queue" rather
 * than "highest-score candidate that later proved safe". So every
 * observed loss is on the bottom of the score distribution while paper
 * successes are on the top. Paper WR is not predictive because paper is
 * scoring the moonshots at 60-95 while live is only executing the
 * boring low-score tail.
 *
 * FIX — LIVE-only minimum score floor. If score < 30, refuse the buy.
 * No score-6 catastrophes possible. Paper untouched (this authority is
 * consulted only from the live path).
 *
 * OPERATOR EXPLICIT INTENT — "if it does the trade quality is
 * shithouse, it only loses". Operator has authorized fewer live trades
 * in exchange for higher quality. A live throughput drop is the
 * correct economic outcome.
 *
 * CONFIG — the floor is intentionally a constant (not tunable at
 * runtime) so downstream adaptive layers cannot lower it in response
 * to reduced sample volume. This is a safety invariant, not a
 * throughput knob.
 */
object LiveMinimumScoreFloor7239 {

    /**
     * Live score floor. All observed live losses in 5.0.7234 were at
     * score 16 or below; the deepest catastrophe was at score 6. 30
     * gives an 87.5% margin above the observed loss ceiling.
     */
    private const val LIVE_MIN_SCORE: Double = 30.0

    enum class Verdict { ALLOW, BLOCK_BELOW_FLOOR }

    data class Decision(
        val verdict: Verdict,
        val score: Double,
        val floor: Double,
        val reason7239: String,
    )

    private val allowed = AtomicLong(0L)
    private val blocked = AtomicLong(0L)

    /**
     * Called at the top of Executor.liveBuy — before PreTradeHardGate,
     * before sizing, before any wallet spend path.
     */
    fun evaluate(ts: TokenState, score: Double): Decision {
        if (!score.isFinite() || score < LIVE_MIN_SCORE) {
            blocked.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("LIVE_MIN_SCORE_FLOOR_BLOCKED_7239")
                val band = (score.toInt().coerceIn(0, 100) / 10) * 10
                PipelineHealthCollector.labelInc("LIVE_MIN_SCORE_FLOOR_BLOCKED_7239_S${band.toString().padStart(2,'0')}")
                if (blocked.get() % 25L == 1L) {
                    ForensicLogger.lifecycle(
                        "LIVE_MIN_SCORE_FLOOR_BLOCKED_7239",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} " +
                            "score=${"%.2f".format(score)} floor=${"%.2f".format(LIVE_MIN_SCORE)} " +
                            "action=refuse_live_buy_below_score_floor " +
                            "note=operator_5_0_7234_all_live_losses_were_score_6_to_16",
                    )
                }
            } catch (_: Throwable) {}
            return Decision(Verdict.BLOCK_BELOW_FLOOR, score, LIVE_MIN_SCORE,
                "SCORE=${"%.2f".format(score)}<${"%.2f".format(LIVE_MIN_SCORE)}")
        }
        allowed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIVE_MIN_SCORE_FLOOR_ALLOWED_7239") } catch (_: Throwable) {}
        return Decision(Verdict.ALLOW, score, LIVE_MIN_SCORE, "ABOVE_FLOOR")
    }

    data class Summary(val allowed: Long, val blocked: Long, val floor: Double)

    fun summary(): Summary = Summary(allowed.get(), blocked.get(), LIVE_MIN_SCORE)

    fun statusLine(): String {
        val s = summary()
        return "LiveMinimumScoreFloor7239 floor=${s.floor} " +
            "allowed=${s.allowed} blocked=${s.blocked}"
    }

    internal fun clearForTest() { allowed.set(0L); blocked.set(0L) }
}
