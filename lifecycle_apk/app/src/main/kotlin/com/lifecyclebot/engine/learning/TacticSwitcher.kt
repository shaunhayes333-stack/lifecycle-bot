package com.lifecyclebot.engine.learning

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.LosingPatternMemory
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.ScoreExpectancyTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1333 — TACTIC SWITCHER (Fluid Constantly-Learnt Tactic Rotation).
 *
 * Operator mandate: "if they aren't successful they need to change tactics
 * in a fluid constantly learnt state". This module fulfills that exactly.
 *
 *   NEVER DISABLE A BUCKET. ALWAYS ROTATE ITS TACTIC.
 *
 * Each (lane, scoreBand) tuple gets a current Tactic. When the bucket's
 * recent N closes are net-negative beyond a threshold, the tactic rotates
 * to the next in the cycle. Each new tactic gets a fresh sample window
 * (TRIAL_WINDOW) to prove itself; if it underperforms, rotate again.
 *
 * Tactics (canonical 4-cycle):
 *   MOMENTUM      — enter on positive price velocity + buy pressure  (default)
 *   PULLBACK      — enter on -3% to -8% retracement from local high
 *   REACCUMULATION — enter on sideways consolidation + sustained buys
 *   BREAKOUT      — enter only on confirmed structure break (new HH)
 *
 * Train-First doctrine:
 *   - Bucket NEVER stops trading.
 *   - Score floors / soft-size already protect capital.
 *   - The tactic switch changes WHAT signal qualifies a buy, not WHETHER.
 *
 * Persistence: each tactic written to LearningPersistence as JSON blob
 * "tactic_${LANE}|${BAND}" so the rotation state survives restarts.
 */
object TacticSwitcher {

    private const val TAG = "TacticSwitcher"

    enum class Tactic { MOMENTUM, PULLBACK, REACCUMULATION, BREAKOUT, LAB_PROPOSED }

    /** Fresh trades required before a rotated tactic is judged. */
    private const val TRIAL_WINDOW = 25

    /** Bucket sample size threshold before rotation considered. */
    private const val MIN_SAMPLES = 25

    /** Loss-rate threshold (75%+) to trigger rotation. */
    private const val LOSS_RATE_TRIGGER = 0.75

    /** Mean PnL threshold (-5%+ net-negative) to trigger rotation. */
    private const val MEAN_PNL_TRIGGER = -5.0

    // V5.9.1448 — FAST ROTATION (operator: "lanes need to self pivot sooner").
    // The 25-trade TRIAL_WINDOW meant a catastrophic bucket (e.g. SHITCOIN|S41-60
    // at 1W/22L = 96% loss) bled for ~650 minutes before rotating. Catch obvious
    // disasters EARLY: as few as FAST_MIN_SAMPLES closes at FAST_LOSS_RATE wipeout
    // with a clearly-negative mean rotates immediately — no need to wait for 25.
    // Still sample-gated enough that 2-3 unlucky losses cannot trip it.
    private const val FAST_MIN_SAMPLES   = 10     // evaluate as early as 10 closes
    private const val FAST_LOSS_RATE     = 0.85   // 85%+ losses = clearly broken, not noise
    private const val FAST_MEAN_PNL      = -4.0   // and net-negative

    // V5.9.1563 — Bayesian early-stop. If the posterior probability that the
    // current tactic's loss-rate is above BAYES_LOSS_RATE_TARGET exceeds 85%,
    // rotate before waiting for the full trial window. This catches obvious
    // meme-cycle failures after ~8 decisive closes while still filtering noise.
    private const val BAYES_MIN_SAMPLES = 8
    private const val BAYES_LOSS_RATE_TARGET = 0.70
    private const val BAYES_TRIGGER_PROB = 0.85
    private const val BAYES_MEAN_PNL = -2.0

    // V5.9.1370 — persistent-bleed (second) rotation condition. Catches buckets
    // that sit just under the hard trigger and used to reset every TRIAL_WINDOW.
    private const val PERSIST_WINDOW       = 40     // longer accumulation window
    private const val PERSIST_LOSS_RATE    = 0.70   // 70%+ losses
    private const val PERSIST_MEAN_PNL     = -3.0   // mildly-but-consistently negative

    // V5.0.3821 — POST-PIVOT FAIL-FAST.
    // Operator doctrine: lanes NEVER disable; strategies must pivot. Report 3820
    // showed MOONSHOT|S41-60 already pivoted MOMENTUM→PULLBACK, then the new tactic
    // went 0W/4L at μ=-48.8% but was still waiting for the generic 8/25-close gates.
    // Once a bucket has already pivoted away from its initial tactic, four decisive
    // losses are enough evidence that THIS tactic is wrong too. Rotate again; never
    // suppress the lane.
    private const val POST_PIVOT_FAST_MIN_SAMPLES = 4
    private const val POST_PIVOT_FAST_LOSS_RATE = 1.00
    private const val POST_PIVOT_FAST_MEAN_PNL = -8.0

    private data class Cell(
        val tactic: AtomicInteger,
        val trialStartedAt: AtomicLong,          // ms
        val tradesSinceRotation: AtomicInteger,
        val pnlSumSinceRotation: AtomicLong,     // in basis-points (×100)
        val winsSinceRotation: AtomicInteger,
        val lossesSinceRotation: AtomicInteger,
        val lastRotationReason: java.util.concurrent.atomic.AtomicReference<String>,
    )

    private val cells = ConcurrentHashMap<String, Cell>()
    private val mutex = Any()

    private fun key(lane: String, scoreBand: String): String =
        "${lane.uppercase().take(24)}|${scoreBand.uppercase().take(8)}"

    private fun getOrCreate(lane: String, scoreBand: String): Cell {
        val k = key(lane, scoreBand)
        return cells.computeIfAbsent(k) {
            val cell = Cell(
                tactic = AtomicInteger(Tactic.MOMENTUM.ordinal),
                trialStartedAt = AtomicLong(System.currentTimeMillis()),
                tradesSinceRotation = AtomicInteger(0),
                pnlSumSinceRotation = AtomicLong(0L),
                winsSinceRotation = AtomicInteger(0),
                lossesSinceRotation = AtomicInteger(0),
                lastRotationReason = java.util.concurrent.atomic.AtomicReference("initial"),
            )
            loadFromPersistenceIfAny(k, cell)
            cell
        }
    }

    /** Public read API — sub-traders call this at entry-decision time. */
    fun currentTactic(lane: String, scoreBand: String): Tactic =
        Tactic.values()[getOrCreate(lane, scoreBand).tactic.get()]

    /** Convenience overload accepting raw score. */
    fun currentTactic(lane: String, score: Int): Tactic {
        val band = try { LosingPatternMemory.scoreBand(score) } catch (_: Throwable) { "" }
        return currentTactic(lane, band)
    }

    private fun posteriorLossProbAbove(losses: Int, wins: Int, threshold: Double = BAYES_LOSS_RATE_TARGET): Double {
        // Loss probability p ~ Beta(losses+1, wins+1). For integer parameters,
        // P(p > x) = sum_{j=0}^{a-1} C(a+b-1,j) x^j (1-x)^(a+b-1-j).
        val a = (losses + 1).coerceAtLeast(1)
        val b = (wins + 1).coerceAtLeast(1)
        val n = a + b - 1
        val x = threshold.coerceIn(0.001, 0.999)
        var sum = 0.0
        var comb = 1.0
        for (j in 0 until a) {
            if (j > 0) comb *= (n - (j - 1)).toDouble() / j.toDouble()
            sum += comb * Math.pow(x, j.toDouble()) * Math.pow(1.0 - x, (n - j).toDouble())
        }
        return sum.coerceIn(0.0, 1.0)
    }

    internal fun posteriorLossProbAboveForTest(losses: Int, wins: Int, threshold: Double = BAYES_LOSS_RATE_TARGET): Double =
        posteriorLossProbAbove(losses, wins, threshold)

    /**
     * Called from journal-write site (sell path) so the switcher observes
     * outcome per (lane, scoreBand) and decides whether to rotate.
     *
     * pnlPct: realized PnL% on the trade.
     */
    fun onTradeClosed(lane: String, scoreBand: String, pnlPct: Double) {
        val cell = getOrCreate(lane, scoreBand)
        cell.tradesSinceRotation.incrementAndGet()
        cell.pnlSumSinceRotation.addAndGet((pnlPct * 100).toLong())
        if (pnlPct > 0.0) cell.winsSinceRotation.incrementAndGet() else cell.lossesSinceRotation.incrementAndGet()

        val tradesIn = cell.tradesSinceRotation.get()
        val losses = cell.lossesSinceRotation.get()
        val lossRate = if (tradesIn > 0) losses.toDouble() / tradesIn else 0.0
        val meanPnl = if (tradesIn > 0) (cell.pnlSumSinceRotation.get().toDouble() / 100.0) / tradesIn else 0.0

        // V5.0.3821 — post-pivot fail-fast. Do this BEFORE Bayesian/normal fast
        // gates because once a non-initial tactic instantly goes 0/4, waiting for
        // 8 or 25 closes is needless bleed. This is a tactic rotation only.
        val alreadyPivoted = cell.tactic.get() != Tactic.MOMENTUM.ordinal || cell.lastRotationReason.get() != "initial"
        if (alreadyPivoted && tradesIn >= POST_PIVOT_FAST_MIN_SAMPLES && tradesIn < TRIAL_WINDOW &&
            lossRate >= POST_PIVOT_FAST_LOSS_RATE && meanPnl <= POST_PIVOT_FAST_MEAN_PNL) {
            rotate(lane, scoreBand, cell, "post-pivot-fast lossRate=${"%.0f".format(lossRate * 100)}% mean=${"%+.1f".format(meanPnl)}% n=$tradesIn")
            return
        }

        // V5.9.1563 — Bayesian early-stop. This is deliberately earlier than
        // FAST_ROTATION and probability-based instead of raw-threshold-only.
        if (tradesIn >= BAYES_MIN_SAMPLES && tradesIn < TRIAL_WINDOW && meanPnl <= BAYES_MEAN_PNL) {
            val probLosing = posteriorLossProbAbove(losses, cell.winsSinceRotation.get())
            if (probLosing >= BAYES_TRIGGER_PROB) {
                val targetPct = "%.0f".format(BAYES_LOSS_RATE_TARGET * 100)
                val probPct = "%.0f".format(probLosing * 100)
                val meanFmt = "%+.1f".format(meanPnl)
                rotate(lane, scoreBand, cell, "bayes P(loss>$targetPct%)=$probPct% mean=$meanFmt% n=$tradesIn")
                return
            }
        }

        // V5.9.1448 — FAST-ROTATION early path. Before the full TRIAL_WINDOW, a
        // bucket that is ALREADY catastrophically bad (>=FAST_MIN_SAMPLES closes,
        // >=FAST_LOSS_RATE loss, net-negative) rotates NOW instead of bleeding to 25.
        // This is what makes lanes self-pivot sooner; the normal thresholds below
        // remain for slower/borderline bleeds.
        if (tradesIn in FAST_MIN_SAMPLES until TRIAL_WINDOW) {
            if (lossRate >= FAST_LOSS_RATE && meanPnl <= FAST_MEAN_PNL) {
                rotate(lane, scoreBand, cell, "fast lossRate=${"%.0f".format(lossRate * 100)}% mean=${"%+.1f".format(meanPnl)}% n=$tradesIn")
                return
            }
        }

        // Below the trial window (and not a fast-rotation case): keep accumulating.
        if (tradesIn < TRIAL_WINDOW) {
            persist(key(lane, scoreBand), cell)
            return
        }

        // V5.9.1370 — BLEEDER FIX. Two rotation conditions now (OR), and the
        // non-trigger branch no longer fully wipes the window.
        //  (1) original: hard bleed — lossRate>=75% AND mean<=-5% in this window.
        //  (2) NEW: persistent bleed — a bucket sitting JUST UNDER the hard trigger
        //      (e.g. 70% loss / -4% mean) used to reset to zero every 25 trades and
        //      bleed forever. Now if the bucket is clearly net-negative
        //      (mean<=-3% AND lossRate>=PERSIST_LOSS_RATE) over a LONGER accumulated
        //      window (>=PERSIST_WINDOW), rotate. The window only resets on a
        //      genuinely healthy read; a still-bleeding-but-sub-trigger bucket keeps
        //      its counters so the persistent-bleed math can mature. NEVER disables —
        //      only rotates the tactic (doctrine entry #18 / rule: rotate, don't kill).
        val hardBleed = lossRate >= LOSS_RATE_TRIGGER && meanPnl <= MEAN_PNL_TRIGGER
        val persistBleed = tradesIn >= PERSIST_WINDOW &&
            lossRate >= PERSIST_LOSS_RATE && meanPnl <= PERSIST_MEAN_PNL
        if (hardBleed || persistBleed) {
            val tag = if (hardBleed) "hard" else "persist"
            rotate(lane, scoreBand, cell, "$tag lossRate=${"%.0f".format(lossRate * 100)}% mean=${"%+.1f".format(meanPnl)}% n=$tradesIn")
        } else if (meanPnl > 0.0 && lossRate < 0.60) {
            // Genuinely healthy window — reset and watch forward cleanly.
            cell.tradesSinceRotation.set(0)
            cell.pnlSumSinceRotation.set(0L)
            cell.winsSinceRotation.set(0)
            cell.lossesSinceRotation.set(0)
            cell.trialStartedAt.set(System.currentTimeMillis())
            persist(key(lane, scoreBand), cell)
        } else {
            // Mediocre / still bleeding but under the hard trigger: KEEP counters so
            // the persistent-bleed window (2) can accumulate toward a rotation
            // instead of being wiped every TRIAL_WINDOW. Just persist and continue.
            persist(key(lane, scoreBand), cell)
        }
    }

    /**
     * External-driven rotation check — TacticSwitcher.maybeRotateFromMemory()
     * can be called periodically (e.g., from the bot loop) to pull stats from
     * LosingPatternMemory + ScoreExpectancyTracker so even quiet buckets get
     * re-evaluated when accumulated history shows poison.
     */
    fun maybeRotateFromMemory(lane: String, scoreBand: String) {
        val cell = getOrCreate(lane, scoreBand)
        // V5.9.1370 — was gated on cell.tradesSinceRotation>=MIN_SAMPLES, which
        // blocked exactly the QUIET buckets this memory-driven path exists to
        // catch (a bucket can be net-poison in accumulated history while its
        // since-rotation counter is tiny). The correct sample guard is the
        // MEMORY's own totalSamples below — that's what we're judging on.
        val st = try { LosingPatternMemory.stats(lane, scoreBandToMidScore(scoreBand)) } catch (_: Throwable) { return }
        val totalSamples = st.wins + st.losses
        if (totalSamples < BAYES_MIN_SAMPLES) return
        val lossRate = if (totalSamples > 0) st.losses.toDouble() / totalSamples else 0.0
        val memBayesProb = if (totalSamples >= BAYES_MIN_SAMPLES && st.meanPnl <= BAYES_MEAN_PNL) {
            posteriorLossProbAbove(st.losses, st.wins)
        } else 0.0
        val memBayesBleed = memBayesProb >= BAYES_TRIGGER_PROB
        // Same OR semantics as the inline path, plus V5.9.1564 memory-sweep
        // Bayesian early-stop so existing bad buckets rotate without waiting
        // for a fresh close after app update.
        val hardBleed = lossRate >= LOSS_RATE_TRIGGER && st.meanPnl <= MEAN_PNL_TRIGGER
        val persistBleed = totalSamples >= PERSIST_WINDOW &&
            lossRate >= PERSIST_LOSS_RATE && st.meanPnl <= PERSIST_MEAN_PNL
        if (hardBleed || persistBleed || memBayesBleed) {
            val tag = when {
                hardBleed -> "mem-hard"
                persistBleed -> "mem-persist"
                else -> {
                    val targetPct = "%.0f".format(BAYES_LOSS_RATE_TARGET * 100)
                    val probPct = "%.0f".format(memBayesProb * 100)
                    "mem-bayes P(loss>$targetPct%)=$probPct%"
                }
            }
            rotate(lane, scoreBand, cell, "$tag:lossRate=${"%.0f".format(lossRate * 100)}% μ=${"%+.1f".format(st.meanPnl)}% n=$totalSamples")
        }
    }

    /**
     * V5.9.1370 — periodic safety sweep. Re-evaluates EVERY known (lane, band)
     * bucket against accumulated LosingPatternMemory, so quiet/low-frequency
     * bleeders get rotated even when they aren't generating fresh closes fast
     * enough to trip the inline onTradeClosed window. Call from the bot loop on
     * a slow cadence (e.g. every ~30 ticks). Cheap: iterates the in-memory cell
     * keys; the heavy stats read is bounded inside LosingPatternMemory.
     */
    fun sweepAllBuckets() {
        val keys = cells.keys().toList()
        for (k in keys) {
            val parts = k.split("|")
            if (parts.size != 2) continue
            try { maybeRotateFromMemory(parts[0], parts[1]) } catch (_: Throwable) {}
        }
    }

    private fun rotate(lane: String, scoreBand: String, cell: Cell, reason: String) {
        val k = key(lane, scoreBand)
        synchronized(mutex) {
            val current = cell.tactic.get()
            val next = (current + 1) % Tactic.values().size
            cell.tactic.set(next)
            cell.tradesSinceRotation.set(0)
            cell.pnlSumSinceRotation.set(0L)
            cell.winsSinceRotation.set(0)
            cell.lossesSinceRotation.set(0)
            cell.trialStartedAt.set(System.currentTimeMillis())
            cell.lastRotationReason.set(reason)
            persist(k, cell)
            val prevName = Tactic.values()[current].name
            val nextName = Tactic.values()[next].name
            ErrorLogger.info(TAG, "🔄 TACTIC_ROTATE $k  $prevName → $nextName  ($reason)")
            try {
                PipelineHealthCollector.labelInc("TACTIC_ROTATE|$k|$nextName")
            } catch (_: Throwable) {}
            // V5.0.3822 — AUTONOMOUS_LAB_PIVOT_SEED. Rotating is the immediate
            // tactical pivot; seeding the lab gives autonomy a paper-only candidate
            // to invent/test the NEXT tactic shape. No LLM call here, no live authority,
            // no lane disable.
            if (reason.contains("post-pivot-fast", ignoreCase = true) || nextName == Tactic.LAB_PROPOSED.name) {
                try { com.lifecyclebot.engine.lab.LlmLabEngine.seedFromTacticFailure(lane, scoreBand, prevName, nextName, reason) } catch (_: Throwable) {}
            }
        }
    }

    /** Snapshot used by the dump renderer. */
    data class Snapshot(
        val key: String,
        val tactic: Tactic,
        val tradesSinceRotation: Int,
        val winsSinceRotation: Int,
        val lossesSinceRotation: Int,
        val meanPnlPct: Double,
        val ageMs: Long,
        val lastReason: String,
    )

    fun snapshotAll(): List<Snapshot> {
        val now = System.currentTimeMillis()
        return cells.entries.map { (k, c) ->
            val trades = c.tradesSinceRotation.get()
            val pnlMean = if (trades > 0) (c.pnlSumSinceRotation.get().toDouble() / 100.0) / trades else 0.0
            Snapshot(
                key = k,
                tactic = Tactic.values()[c.tactic.get()],
                tradesSinceRotation = trades,
                winsSinceRotation = c.winsSinceRotation.get(),
                lossesSinceRotation = c.lossesSinceRotation.get(),
                meanPnlPct = pnlMean,
                ageMs = now - c.trialStartedAt.get(),
                lastReason = c.lastRotationReason.get() ?: "",
            )
        }.sortedByDescending { it.tradesSinceRotation }
    }

    private fun persist(key: String, cell: Cell) {
        try {
            val json = """{"t":${cell.tactic.get()},"start":${cell.trialStartedAt.get()},"n":${cell.tradesSinceRotation.get()},"pnl":${cell.pnlSumSinceRotation.get()},"w":${cell.winsSinceRotation.get()},"l":${cell.lossesSinceRotation.get()},"r":"${cell.lastRotationReason.get()?.replace("\"", "")?.take(80) ?: ""}"}"""
            LearningPersistence.save("tactic_$key", json)
        } catch (_: Throwable) {}
    }

    private fun loadFromPersistenceIfAny(key: String, cell: Cell) {
        try {
            val raw = LearningPersistence.load("tactic_$key") ?: return
            Regex(""""t":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                if (it in Tactic.values().indices) cell.tactic.set(it)
            }
            Regex(""""start":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { cell.trialStartedAt.set(it) }
            Regex(""""n":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { cell.tradesSinceRotation.set(it) }
            Regex(""""pnl":(-?\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { cell.pnlSumSinceRotation.set(it) }
            Regex(""""w":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { cell.winsSinceRotation.set(it) }
            Regex(""""l":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { cell.lossesSinceRotation.set(it) }
            Regex(""""r":"([^"]*)"""").find(raw)?.groupValues?.getOrNull(1)?.let { cell.lastRotationReason.set(it) }
        } catch (_: Throwable) {}
    }

    private fun scoreBandToMidScore(band: String): Int = when (band.uppercase()) {
        "S0-10" -> 5
        "S11-25" -> 18
        "S26-40" -> 33
        "S41-60" -> 50
        "S61+" -> 70
        else -> 30
    }

    // ────────────────────────────────────────────────────────────────────────
    // V5.9.1334 — LAB-PROPOSED TACTICS
    //
    // When a bucket rotates to Tactic.LAB_PROPOSED, the switcher asks the
    // LLM Lab for its best paper-proven strategy matching the current lane
    // + score band. If the lab has nothing proven yet, LAB_PROPOSED falls
    // back to MOMENTUM-shape (graceful degrade — never disable).
    //
    // Promotion bar (a lab strategy is "tactic-ready"):
    //   • status = PROMOTED  (already user-promoted in the lab)  OR
    //   • status = ACTIVE AND paperTrades >= 30 AND winRate% >= 45%
    //
    // The switcher reads this purely via LlmLabStore — no writes — so the
    // lab remains the sole author of strategy state.
    // ────────────────────────────────────────────────────────────────────────

    data class LabTacticShape(
        val strategyId: String,
        val strategyName: String,
        val entryScoreMin: Int,
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val maxHoldMins: Int,
        val rationale: String,
    )

    /** Best lab-proven shape for the given (lane, scoreBand), or null. */
    fun labShapeFor(lane: String, scoreBand: String): LabTacticShape? {
        return try {
            val midScore = scoreBandToMidScore(scoreBand)
            val asset = laneToLabAsset(lane)
            val all = com.lifecyclebot.engine.lab.LlmLabStore.allStrategies()
            val ready = all.filter { s ->
                val statusOk = s.status == com.lifecyclebot.engine.lab.LabStrategyStatus.PROMOTED ||
                    (s.status == com.lifecyclebot.engine.lab.LabStrategyStatus.ACTIVE &&
                        s.paperTrades >= 30 && s.winRatePct() >= 45.0)
                val assetOk = s.asset == com.lifecyclebot.engine.lab.LabAssetClass.ANY || s.asset == asset
                val scoreOk = midScore >= (s.entryScoreMin - 8)   // small overlap tolerance
                statusOk && assetOk && scoreOk
            }
            if (ready.isEmpty()) return null
            val best = ready.maxByOrNull { it.paperPnlSol / it.paperTrades.coerceAtLeast(1).toDouble() } ?: return null
            LabTacticShape(
                strategyId = best.id,
                strategyName = best.name,
                entryScoreMin = best.entryScoreMin,
                takeProfitPct = best.takeProfitPct,
                stopLossPct = best.stopLossPct,
                maxHoldMins = best.maxHoldMins,
                rationale = best.rationale.take(120),
            )
        } catch (_: Throwable) { null }
    }

    private fun laneToLabAsset(lane: String): com.lifecyclebot.engine.lab.LabAssetClass {
        val L = lane.uppercase()
        return when {
            L.contains("SHIT") || L.contains("MEME") || L.contains("MOONSHOT") ->
                com.lifecyclebot.engine.lab.LabAssetClass.MEME
            L.contains("BLUECHIP") || L.contains("CRYPTO") || L.contains("ALT") ->
                com.lifecyclebot.engine.lab.LabAssetClass.ALT
            L.contains("STOCK") || L.contains("EQUITY") ->
                com.lifecyclebot.engine.lab.LabAssetClass.STOCK
            L.contains("FOREX") -> com.lifecyclebot.engine.lab.LabAssetClass.FOREX
            L.contains("METAL") -> com.lifecyclebot.engine.lab.LabAssetClass.METAL
            L.contains("COMMOD") -> com.lifecyclebot.engine.lab.LabAssetClass.COMMODITY
            L.contains("MARKET") -> com.lifecyclebot.engine.lab.LabAssetClass.MARKETS
            else -> com.lifecyclebot.engine.lab.LabAssetClass.ANY
        }
    }
}
