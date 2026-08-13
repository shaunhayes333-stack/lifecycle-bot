package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6439 — ANTI-REWARD-HACKING GUARD.
 *
 * OPERATOR DIRECTIVE:
 *   "Bad behaviour ... should NEVER be seen or recognised as good
 *    behaviour."
 *
 * Learners can rationalise expanding risk after a loss: "I lost because
 * my size was too small — next time bigger." That reward-hacking wipes
 * accounts. This guard vetoes ANY tuning move that would raise position
 * size, lower stop-loss tightness or raise re-entry appetite while the
 * wallet is below its 24-hour high.
 *
 * The guard is stateless-per-call — every learner asks:
 *   AntiRewardHackingGuard6439.canExpandRisk(currentWalletSol) → Boolean
 * and only proceeds with an expand-risk tune if the answer is true.
 *
 * Wallet-high tracking is a rolling 24h max via a single volatile pair
 * (highSol, highAtMs). Cheap. Zero allocation on the hot path.
 */
object AntiRewardHackingGuard6439 {

    private const val WINDOW_MS: Long = 24L * 60L * 60L * 1000L
    private const val DRAWDOWN_TOLERANCE: Double = 0.98    // allow risk expansion within 2% of high

    private val highSol = AtomicReference<Double>(0.0)
    private val highAtMs = AtomicLong(0L)
    private val vetoCount = AtomicLong(0L)
    private val allowCount = AtomicLong(0L)

    /**
     * Report the current wallet SOL balance. Called from BotService loop
     * (post-supervisor persist) so the rolling high is always fresh.
     */
    fun observeWalletBalance(currentSol: Double) {
        if (currentSol <= 0.0) return
        val now = System.currentTimeMillis()
        val expired = (now - highAtMs.get()) > WINDOW_MS
        if (expired || currentSol > highSol.get()) {
            highSol.set(currentSol)
            highAtMs.set(now)
        }
    }

    /**
     * Called by any learner BEFORE proposing an expand-risk tune. Returns
     * false to VETO the tune. When vetoed, learners must either propose
     * a shrink-risk tune or noop.
     */
    fun canExpandRisk(currentSol: Double): Boolean {
        val high = highSol.get()
        if (high <= 0.0 || currentSol <= 0.0) return true
        val ratio = currentSol / high
        val allow = ratio >= DRAWDOWN_TOLERANCE
        if (!allow) {
            vetoCount.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "ANTI_REWARD_HACK_VETO_6439",
                    "walletSol=${"%.5f".format(currentSol)} highSol=${"%.5f".format(high)} " +
                        "ratio=${"%.3f".format(ratio)} toleranceMin=${DRAWDOWN_TOLERANCE}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("ANTI_REWARD_HACK_VETO_6439") } catch (_: Throwable) {}
        } else {
            allowCount.incrementAndGet()
        }
        return allow
    }

    fun currentHigh(): Double = highSol.get()

    fun statusLine(): String {
        val h = highSol.get()
        val ageMin = ((System.currentTimeMillis() - highAtMs.get()) / 60_000L).coerceAtLeast(0L)
        return "high24hSol=${"%.5f".format(h)} highAgeMin=$ageMin vetoes=${vetoCount.get()} allows=${allowCount.get()}"
    }
}
