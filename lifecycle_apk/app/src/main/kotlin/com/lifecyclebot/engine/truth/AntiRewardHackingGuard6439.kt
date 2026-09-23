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
     * V5.0.7267 §A_2%_BAND_ON_A_BOOK_THAT_BREATHES_10%_IS_A_PERMANENT_VETO.
     *
     * Operator: "make the rest fluid too." DRAWDOWN_TOLERANCE was a fixed 2%:
     * any equity reading under 98% of the 24h high vetoed every boost. A
     * memecoin book's equity basis moves several percent a day in the
     * ordinary course of business, so on 5.0.7263 the guard read
     * `vetoes=1914 allows=521` with the account 6.6% under a high set
     * during a mark spike. The rule was right; its width was not a
     * measurement of anything.
     *
     * The tolerance now widens with the book's own observed volatility: the
     * relative standard deviation of the equity basis over the rolling
     * window. A book that moves 1% a day keeps the 2% band; a book that
     * moves 8% a day earns a 12% band; nothing widens past 25%, so a real
     * drawdown still vetoes. Under twelve samples it is the old 2%.
     *
     * Separately, a boost the requesting LANE has earned is not the
     * rationalisation this guard exists to stop. "I lost, so size up" is
     * the hack; "this lane is net positive over >= 8 same-mode closes and
     * LaneExpectancyDamper already reads it above neutral" is the opposite
     * — evidence-backed expansion — and the doctrine says proven winners
     * may be pressed. That case allows through a portfolio-level drawdown
     * caused by other lanes, and is counted separately so it is auditable.
     */
    private const val SAMPLE_WINDOW_MAX = 720
    private const val MIN_SAMPLES_FOR_FLUID_7267 = 12
    private const val TOLERANCE_MIN_7267 = 0.75
    private const val VOL_TO_BAND_7267 = 1.5
    private val basisSamples7267 = java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, Double>>()
    private val laneEarnedAllows7267 = AtomicLong(0L)

    private fun recordSample7267(nowMs: Long, basis: Double) {
        try {
            basisSamples7267.addLast(nowMs to basis)
            while (basisSamples7267.size > SAMPLE_WINDOW_MAX) basisSamples7267.pollFirst()
            val cutoff = nowMs - WINDOW_MS
            while (true) {
                val head = basisSamples7267.peekFirst() ?: break
                if (head.first < cutoff) basisSamples7267.pollFirst() else break
            }
        } catch (_: Throwable) {}
    }

    /** Current drawdown tolerance ratio: fixed 2% until the book has shown its own range. */
    fun fluidTolerance7267(): Double = try {
        val vals = basisSamples7267.map { it.second }.filter { it.isFinite() && it > 0.0 }
        if (vals.size < MIN_SAMPLES_FOR_FLUID_7267) DRAWDOWN_TOLERANCE else {
            val mean = vals.average()
            val variance = vals.sumOf { (it - mean) * (it - mean) } / vals.size
            val relStd = if (mean > 0.0) kotlin.math.sqrt(variance) / mean else 0.0
            val band = (relStd * VOL_TO_BAND_7267).coerceIn(1.0 - DRAWDOWN_TOLERANCE, 1.0 - TOLERANCE_MIN_7267)
            (1.0 - band).coerceIn(TOLERANCE_MIN_7267, DRAWDOWN_TOLERANCE)
        }
    } catch (_: Throwable) { DRAWDOWN_TOLERANCE }

    private fun laneEarnedExpansion7267(lane: String?): Boolean {
        if (lane.isNullOrBlank()) return false
        return try {
            val mult = com.lifecyclebot.engine.LaneExpectancyDamper.sizeMultiplier(lane)
            val closes = com.lifecyclebot.engine.LaneExpectancyDamper.sameModeCloses7265(lane)
            mult.isFinite() && mult > 1.0 + 1e-9 &&
                closes >= com.lifecyclebot.engine.LaneExpectancyDamper.MATURE_EVIDENCE_CLOSES_7265
        } catch (_: Throwable) { false }
    }

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
        // V5.0.7200 §THE_GUARD_WENT_BLIND_WHEN_IT_MATTERED_MOST.
        //
        // This used to read `if (currentCashSol <= 0.0) return` — testing the
        // RAW input, one line before riskBasisSol7179 converts it into the
        // real equity basis. 7179 added that conversion precisely because the
        // raw figure is a wallet mirror and not the bot's risk, and then the
        // pre-existing guard clause above it made the conversion unreachable
        // in the one state that matters.
        //
        // When the bot is fully deployed, cash IS zero. On the 5.0.7197 run it
        // sat at 0.0000 SOL with 15.64 SOL of open market value, so every
        // observation returned here and the rolling high was never written:
        //
        //   Anti-reward-hack (§6439): high24hSol=0.00000
        //                             highAgeMin=29833135  (56 years — never set)
        //                             vetoes=0 allows=0
        //
        // canExpandRisk then reads `if (high <= 0.0 ...) return true` and
        // allows unconditionally. So the drawdown veto was inert exactly while
        // the account was 100% deployed — the condition it exists to police.
        // Both asymmetries built on it were silently unguarded: V5.0.7192's
        // trait-expansion gate and V5.0.6956's runner compounder.
        //
        // Same shape as V5.0.7199: a later authority made unreachable by an
        // earlier guard clause that predates it.
        //
        // Test the RESOLVED basis. riskBasisSol7179 already resolves paper
        // cash from PaperCapitalAuthority6577 internally and adds open cost
        // basis, so a fully-deployed account yields a true, non-zero equity
        // figure. A genuinely empty account still resolves to <= 0 and is
        // still skipped by the check below, which is retained unchanged.
        val currentSol = riskBasisSol7179(currentCashSol)
        if (currentSol <= 0.0) return
        val now = System.currentTimeMillis()
        recordSample7267(now, currentSol)
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
    fun canExpandRisk(currentCashSol: Double, lane: String? = null): Boolean {
        // V5.0.7179 — same basis as the observation half, by construction.
        val currentSol = riskBasisSol7179(currentCashSol)
        val high = highSol.get()
        if (high <= 0.0 || currentSol <= 0.0) return true
        val ratio = currentSol / high
        // V5.0.7267 — the band is the book's own observed range, not a fixed 2%.
        val tolerance7267 = fluidTolerance7267()
        if (tolerance7267 < DRAWDOWN_TOLERANCE - 1e-9) {
            try { PipelineHealthCollector.labelInc("ANTI_REWARD_HACK_TOLERANCE_FLUID_7267") } catch (_: Throwable) {}
        }
        var allow = ratio >= tolerance7267
        if (!allow && laneEarnedExpansion7267(lane)) {
            // V5.0.7267 — an expansion the lane earned with its own closes.
            allow = true
            laneEarnedAllows7267.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("ANTI_REWARD_HACK_LANE_EARNED_ALLOW_7267")
                PipelineHealthCollector.labelInc("ANTI_REWARD_HACK_LANE_EARNED_ALLOW_7267_${lane!!.trim().uppercase().take(20)}")
            } catch (_: Throwable) {}
        }
        if (!allow) {
            vetoCount.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "ANTI_REWARD_HACK_VETO_6439",
                    "walletSol=${"%.5f".format(currentSol)} highSol=${"%.5f".format(high)} " +
                        "ratio=${"%.3f".format(ratio)} toleranceMin=${"%.3f".format(tolerance7267)} " +
                        "fixedTolerance=${DRAWDOWN_TOLERANCE} lane=${lane ?: "-"}",
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
        return "high24hSol=${"%.5f".format(h)} highAgeMin=$ageMin vetoes=${vetoCount.get()} allows=${allowCount.get()} " +
            "tolerance7267=${"%.3f".format(fluidTolerance7267())} samples=${basisSamples7267.size} laneEarnedAllows=${laneEarnedAllows7267.get()}"
    }
}
