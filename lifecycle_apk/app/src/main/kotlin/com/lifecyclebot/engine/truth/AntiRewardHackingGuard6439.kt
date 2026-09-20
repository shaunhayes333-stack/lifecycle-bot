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
     * V5.0.7179 §DEPLOYING_CAPITAL_IS_NOT_A_DRAWDOWN.
     *
     * Operator 5.0.7176: `Anti-reward-hack: vetoes=543 allows=0`. Not one
     * allow in 425 seconds — a gate that has never once said yes is not
     * measuring anything.
     *
     * Both halves of this guard were being handed CASH. BotService:18902
     * passes `balanceSol` to observeWalletBalance and Executor:3277 passes
     * `walletSol` to canExpandRisk. Cash falls every time the bot opens a
     * position, because the money moved into inventory — that is DEPLOYMENT,
     * not loss. So the rolling "high" is really the high-water mark of IDLE
     * cash, which is necessarily set before the first buy and can only be
     * matched again by selling everything and sitting flat.
     *
     * The device numbers say it exactly:
     *
     *   high24hSol 11.7600     cash 6.6321     ratio 0.564
     *   DRAWDOWN_TOLERANCE 0.98  ->  veto, forever
     *   TOTAL EQUITY 16.7845    (cash 6.6321 + openCost 10.3662)
     *
     * The account was at an all-time high, up 43% on the number the guard
     * was comparing against, and the guard believed it was 44% underwater.
     * Every learner asking to expand risk was refused on that basis, 543
     * times, and because `high` only ratchets upward the refusal was
     * permanent. Another one-way latch.
     *
     * Drawdown is an EQUITY question. The basis is cash plus open COST
     * BASIS — deliberately not open market value, for the reason V5.0.6912
     * had to learn in LaneCapitalFairness6732: an equity figure containing
     * unrealised gains on unverified marks hands out the most permission
     * exactly when the price data is least trustworthy. Cost basis moves
     * only on real fills, so deploying capital is ratio-neutral while a
     * genuine realised loss still lowers it and still vetoes.
     *
     * Resolved in ONE place rather than at the two call sites, because the
     * observation and the decision drifting apart is precisely this defect;
     * if they share a function they cannot disagree.
     */
    private fun riskBasisSol7179(observedCashSol: Double): Double = try {
        val paper7179 = try {
            com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()
        } catch (_: Throwable) { true }
        val modeTag7179 = if (paper7179) "paper" else "live"
        // V5.0.7187 §THE_BASIS_WAS_RIGHT_AND_THE_CASH_WAS_FICTION.
        //
        // Operator: "its got the balance wrong its not reading paper balance.
        // I havent even connected a live wallet to this install."
        //
        // Both callers hand this a WALLET MIRROR — BotService's `balanceSol`
        // and Executor:3277's `walletSol`. On a paper-only install with no
        // wallet connected that reads ~0.06 SOL while the paper bankroll is
        // 9.30. So 7179's equity basis computed 0.06 + 6.08 open cost = 6.14
        // against a 11.76 high, ratio 0.52, and vetoed every risk expansion:
        // 533 vetoes and 0 allows on the 5.0.7186 run. The arithmetic was
        // correct and the input was fiction.
        //
        // Resolved HERE rather than at the two call sites, for the same reason
        // the basis itself is resolved here: if observation and decision are
        // fed from different places they drift apart, and that drift IS the
        // original defect. One function, one answer, both halves.
        //
        // PaperCapitalAuthority6577 is the same authority V5.0.6689 bound the
        // sizing bridge to, so the guard, the sizing bridge and the order
        // resolver now all price paper risk off one bankroll.
        val cashSol7187 = if (!paper7179) observedCashSol else {
            try {
                PaperCapitalAuthority6577.cashSol().coerceAtLeast(0.0)
                    .takeIf { it.isFinite() && it > 0.0 } ?: observedCashSol
            } catch (_: Throwable) { observedCashSol }
        }
        val openCost7179 = CanonicalPositionAuthority6441.openPositions()
            .filter { it.mode.equals(modeTag7179, true) }
            .sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
        val basis = cashSol7187 + openCost7179
        if (basis.isFinite() && basis > 0.0) basis else cashSol7187
    } catch (_: Throwable) { observedCashSol }

    /**
     * Report the current wallet SOL balance. Called from BotService loop
     * (post-supervisor persist) so the rolling high is always fresh.
     *
     * V5.0.7179 — the reported cash is converted to the equity basis before
     * it touches the rolling high. See riskBasisSol7179.
     */
    fun observeWalletBalance(currentCashSol: Double) {
        if (currentCashSol <= 0.0) return
        val currentSol = riskBasisSol7179(currentCashSol)
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
    fun canExpandRisk(currentCashSol: Double): Boolean {
        // V5.0.7179 — same basis as the observation half, by construction.
        val currentSol = riskBasisSol7179(currentCashSol)
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
