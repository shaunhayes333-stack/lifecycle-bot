package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6416 — LIVE GROWTH COMPOUNDER.
 *
 * OPERATOR DIRECTIVE (Feb 2026):
 * "wallet balance isnt increasing again on live or paper trading.
 *  it needs to be more growth centric especially live trading."
 *
 * DESIGN
 * ──────
 * Two additive lifts that compound wins into bigger next-trades:
 *
 * §A LANE_WIN_COMPOUND_BOOST
 *   When a live sell closes with pnl >= +30%, the NEXT live buy in
 *   the SAME LANE gets a 1.3× size bump. When two wins land back-to-
 *   back on the same lane, the bump stacks to 1.6× (with a hard ceiling
 *   of 2.0×). One-shot: consumed by the next buy or expired after 5min.
 *
 * §B WALLET_GROWTH_TIER_LIFT
 *   On boot, capture wallet SOL as baseline. Compute currentSol / baseline:
 *     >= 1.20  → tier 1  (1.10× additional size cap headroom)
 *     >= 1.50  → tier 2  (1.20× additional)
 *     >= 2.00  → tier 3  (1.35× additional)
 *   Applied on top of the runner boost / moonshot lift so the cap
 *   ladder climbs as the wallet climbs — literal compounding.
 *
 * Both are bounded by liquidityCapSol + spendable in the caller, so
 * they can never overspend the pool or the wallet.
 *
 * Emits:
 *   LIVE_GROWTH_COMPOUND_ARMED_6416     when a win primes the next-buy bump
 *   LIVE_GROWTH_COMPOUND_APPLIED_6416   when the bump lands on a buy
 *   LIVE_WALLET_GROWTH_TIER_6416        each tier promotion
 */
object LiveGrowthCompounder6416 {

    private const val WIN_THRESHOLD_PCT = 30.0
    private const val WIN_BUMP_PER_HIT = 0.30
    private const val WIN_BUMP_CEILING = 1.0 // added to base 1.0 → 2.0× total
    private const val PRIME_TTL_MS = 5L * 60_000L
    private const val TIER1_LIFT_THRESHOLD = 1.20
    private const val TIER2_LIFT_THRESHOLD = 1.50
    private const val TIER3_LIFT_THRESHOLD = 2.00

    private data class Prime(val bump: Double, val stampedAtMs: Long)

    private val primesByLane = ConcurrentHashMap<String, Prime>()
    private val baselineSol = AtomicReference<Double?>(null)
    private val currentTier = AtomicLong(0L)

    /**
     * Record a live win outcome. Bumps the next-buy multiplier for
     * this lane. Called from Executor sell terminal path.
     */
    fun onLiveWin(lane: String, mint: String, symbol: String, pnlPct: Double) {
        if (pnlPct < WIN_THRESHOLD_PCT) return
        val key = lane.uppercase().take(24)
        val now = System.currentTimeMillis()
        val prior = primesByLane[key]
        val priorBump = if (prior != null && now - prior.stampedAtMs < PRIME_TTL_MS) prior.bump else 0.0
        val newBump = (priorBump + WIN_BUMP_PER_HIT).coerceAtMost(WIN_BUMP_CEILING)
        primesByLane[key] = Prime(newBump, now)
        try {
            ForensicLogger.lifecycle(
                "LIVE_GROWTH_COMPOUND_ARMED_6416",
                "lane=$key mint=${mint.take(10)} sym=$symbol pnlPct=${"%.1f".format(pnlPct)} " +
                    "newBump=+${"%.2f".format(newBump)}× (next=${"%.2f".format(1.0 + newBump)}×) prior=+${"%.2f".format(priorBump)}",
            )
            PipelineHealthCollector.labelInc("LIVE_GROWTH_COMPOUND_ARMED_6416")
        } catch (_: Throwable) {}
    }

    /**
     * Consume any primed bump for this lane and return the resulting
     * size multiplier. Baseline 1.0 when no prime is active.
     */
    fun consumeNextBuyBump(lane: String, mint: String, symbol: String): Double {
        val key = lane.uppercase().take(24)
        val now = System.currentTimeMillis()
        val p = primesByLane[key] ?: return 1.0
        if (now - p.stampedAtMs > PRIME_TTL_MS) {
            primesByLane.remove(key, p)
            return 1.0
        }
        // One-shot consume.
        primesByLane.remove(key, p)
        val mult = 1.0 + p.bump
        try {
            ForensicLogger.lifecycle(
                "LIVE_GROWTH_COMPOUND_APPLIED_6416",
                "lane=$key mint=${mint.take(10)} sym=$symbol mult=${"%.2f".format(mult)} " +
                    "(bump=+${"%.2f".format(p.bump)} ageMs=${now - p.stampedAtMs})",
            )
            PipelineHealthCollector.labelInc("LIVE_GROWTH_COMPOUND_APPLIED_6416")
        } catch (_: Throwable) {}
        return mult
    }

    /**
     * Called once on boot to establish the wallet baseline. Subsequent
     * calls are no-ops so the baseline stays fixed across the session.
     */
    fun captureBaseline(walletSol: Double) {
        if (!walletSol.isFinite() || walletSol <= 0.0) return
        if (baselineSol.compareAndSet(null, walletSol)) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_WALLET_BASELINE_CAPTURED_6416",
                    "baselineSol=${"%.4f".format(walletSol)}",
                )
                PipelineHealthCollector.labelInc("LIVE_WALLET_BASELINE_CAPTURED_6416")
            } catch (_: Throwable) {}
        }
    }

    /**
     * Returns the wallet growth tier lift (1.0, 1.10, 1.20, or 1.35).
     * Emits a tier-promotion event when the tier climbs.
     */
    fun walletGrowthLift(currentWalletSol: Double): Double {
        val base = baselineSol.get() ?: return 1.0
        if (base <= 0.0) return 1.0
        if (!currentWalletSol.isFinite() || currentWalletSol <= 0.0) return 1.0
        val ratio = currentWalletSol / base
        val (tier, lift) = when {
            ratio >= TIER3_LIFT_THRESHOLD -> 3L to 1.35
            ratio >= TIER2_LIFT_THRESHOLD -> 2L to 1.20
            ratio >= TIER1_LIFT_THRESHOLD -> 1L to 1.10
            else -> 0L to 1.0
        }
        val priorTier = currentTier.get()
        if (tier > priorTier && currentTier.compareAndSet(priorTier, tier)) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_WALLET_GROWTH_TIER_6416",
                    "priorTier=$priorTier newTier=$tier ratio=${"%.2f".format(ratio)} lift=${"%.2f".format(lift)} " +
                        "baselineSol=${"%.4f".format(base)} currentSol=${"%.4f".format(currentWalletSol)}",
                )
                PipelineHealthCollector.labelInc("LIVE_WALLET_GROWTH_TIER_6416")
            } catch (_: Throwable) {}
        } else if (tier < priorTier) {
            // Give-back: demote silently (drawdown protection).
            currentTier.set(tier)
        }
        return lift
    }

    fun statusLine(): String {
        val activePrimes = primesByLane.entries.filter {
            System.currentTimeMillis() - it.value.stampedAtMs < PRIME_TTL_MS
        }
        val primeSummary = activePrimes.joinToString(",") { "${it.key}(+${"%.2f".format(it.value.bump)})" }
        val base = baselineSol.get()
        return "primes=[${primeSummary.take(80)}] baselineSol=${base?.let { "%.4f".format(it) } ?: "-"} tier=${currentTier.get()}"
    }

    /**
     * V5.0.6417 — PAPER PARITY.
     * Same shape as onLiveWin/consumeNextBuyBump but scoped to paper mode.
     * Kept in a separate lane map so paper wins don't bump live buys and
     * vice-versa.
     */
    private val paperPrimesByLane = ConcurrentHashMap<String, Prime>()
    private val paperBaselineSol = AtomicReference<Double?>(null)
    private val paperCurrentTier = AtomicLong(0L)

    fun onPaperWin(lane: String, mint: String, symbol: String, pnlPct: Double) {
        if (pnlPct < WIN_THRESHOLD_PCT) return
        val key = lane.uppercase().take(24)
        val now = System.currentTimeMillis()
        val prior = paperPrimesByLane[key]
        val priorBump = if (prior != null && now - prior.stampedAtMs < PRIME_TTL_MS) prior.bump else 0.0
        val newBump = (priorBump + WIN_BUMP_PER_HIT).coerceAtMost(WIN_BUMP_CEILING)
        paperPrimesByLane[key] = Prime(newBump, now)
        try {
            ForensicLogger.lifecycle(
                "PAPER_GROWTH_COMPOUND_ARMED_6417",
                "lane=$key mint=${mint.take(10)} sym=$symbol pnlPct=${"%.1f".format(pnlPct)} " +
                    "newBump=+${"%.2f".format(newBump)}× (next=${"%.2f".format(1.0 + newBump)}×) prior=+${"%.2f".format(priorBump)}",
            )
            PipelineHealthCollector.labelInc("PAPER_GROWTH_COMPOUND_ARMED_6417")
        } catch (_: Throwable) {}
    }

    fun consumeNextPaperBuyBump(lane: String, mint: String, symbol: String): Double {
        val key = lane.uppercase().take(24)
        val now = System.currentTimeMillis()
        val p = paperPrimesByLane[key] ?: return 1.0
        if (now - p.stampedAtMs > PRIME_TTL_MS) {
            paperPrimesByLane.remove(key, p); return 1.0
        }
        paperPrimesByLane.remove(key, p)
        val mult = 1.0 + p.bump
        try {
            ForensicLogger.lifecycle(
                "PAPER_GROWTH_COMPOUND_APPLIED_6417",
                "lane=$key mint=${mint.take(10)} sym=$symbol mult=${"%.2f".format(mult)}",
            )
            PipelineHealthCollector.labelInc("PAPER_GROWTH_COMPOUND_APPLIED_6417")
        } catch (_: Throwable) {}
        return mult
    }

    fun capturePaperBaseline(paperSol: Double) {
        if (!paperSol.isFinite() || paperSol <= 0.0) return
        if (paperBaselineSol.compareAndSet(null, paperSol)) {
            try {
                ForensicLogger.lifecycle("PAPER_WALLET_BASELINE_CAPTURED_6417", "baselineSol=${"%.4f".format(paperSol)}")
                PipelineHealthCollector.labelInc("PAPER_WALLET_BASELINE_CAPTURED_6417")
            } catch (_: Throwable) {}
        }
    }

    fun paperWalletGrowthLift(currentPaperSol: Double): Double {
        val base = paperBaselineSol.get() ?: return 1.0
        if (base <= 0.0 || !currentPaperSol.isFinite() || currentPaperSol <= 0.0) return 1.0
        val ratio = currentPaperSol / base
        val (tier, lift) = when {
            ratio >= TIER3_LIFT_THRESHOLD -> 3L to 1.35
            ratio >= TIER2_LIFT_THRESHOLD -> 2L to 1.20
            ratio >= TIER1_LIFT_THRESHOLD -> 1L to 1.10
            else -> 0L to 1.0
        }
        val prior = paperCurrentTier.get()
        if (tier > prior && paperCurrentTier.compareAndSet(prior, tier)) {
            try {
                ForensicLogger.lifecycle("PAPER_WALLET_GROWTH_TIER_6417",
                    "priorTier=$prior newTier=$tier ratio=${"%.2f".format(ratio)} lift=${"%.2f".format(lift)}")
                PipelineHealthCollector.labelInc("PAPER_WALLET_GROWTH_TIER_6417")
            } catch (_: Throwable) {}
        } else if (tier < prior) paperCurrentTier.set(tier)
        return lift
    }

    internal fun resetForTest() {
        primesByLane.clear()
        baselineSol.set(null)
        currentTier.set(0L)
        paperPrimesByLane.clear()
        paperBaselineSol.set(null)
        paperCurrentTier.set(0L)
    }
}
