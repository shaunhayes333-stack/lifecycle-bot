package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI
import java.util.concurrent.ConcurrentHashMap

/**
 * HeldPositionPivotArbiter (V5.9.1351)
 * ════════════════════════════════════════════════════════════════════════════
 * THE PROBLEM IT SOLVES
 *   Every open position's exit technique was welded to its ENTRY lane:
 *   BotService derives `positionTradeType` purely from `ts.position.tradingMode`
 *   (stamped once at buy), and ModeSpecificExits is a `when(tradeType)` switch.
 *   So a token entered as a snipe is managed as a snipe until it dies — even
 *   when its LIVE metrics now match a different, more profitable technique.
 *   The backtest screamed this: the lane-agnostic "let-run + -15% floor" bucket
 *   netted positive while rigid specialist lanes (MOONSHOT, SHITCOIN) went
 *   net-negative and got flagged STOP TRADING. Money left on the table =
 *   winners round-tripped because the entry style never let them run, and
 *   stalled positions held because the entry style never told them to cut.
 *
 * WHAT IT DOES
 *   Once per supervisor tick, per open position, it asks the EXISTING predictive
 *   brains "given what this token looks like RIGHT NOW, which exit technique does
 *   the accumulated database expect to capture the most?" and pivots the
 *   position's live exit mode toward it — ahead of the move, not reacting to it:
 *     • ForwardOutcomeModel.forecast(lane,...) — forward pWin × expectedPnl per
 *       candidate technique, keyed on the token's CURRENT signature. The same
 *       brain FDG uses at entry, now consulted mid-hold.
 *     • MomentumPredictorAI.getPrediction(mint) — is it still climbing or rolling?
 *     • CollectiveIntelligenceAI.getModeRecommendation(mode) — does the hive
 *       favour this mode right now?
 *
 * DISCIPLINE (doctrine-compliant — see PERFORMANCE_DOCTRINE + active instructions)
 *   • SOFT-SHAPE ONLY. It changes HOW we hold/exit, never blocks a trade. No veto,
 *     no new gate. FDG and the unconditional -15% hard floor are untouched.
 *   • Anti-flip-flop: a pivot only fires when the candidate's blended score beats
 *     the current style's by CONVICTION_MARGIN, and not more than once per
 *     PIVOT_COOLDOWN_MS per position.
 *   • Entry attribution is immutable (V5.0.6866): a pivot changes the EXIT STYLE,
 *     so ForwardOutcomeModel keeps the signature of the lane that made the entry.
 *     This previously re-stamped with the pivoted lane, which destroyed the
 *     original signature and credited the settled outcome to a lane that never
 *     took the decision. The pivot is recorded on Position.modeHistory instead.
 *   • Fail-open: any exception leaves the position on its entry style.
 *
 * It does NOT scale size / add to winners — that touches the buy/exposure path and
 * is a deliberate fast-follow. This push is pure exit-side.
 */
object HeldPositionPivotArbiter {

    private const val TAG = "PivotArbiter"

    // A pivot must clear the incumbent by this much blended-score margin to fire.
    private const val CONVICTION_MARGIN = 0.12
    // Minimum gap between pivots on the same position (anti-oscillation).
    private const val PIVOT_COOLDOWN_MS = 45_000L
    // Don't pivot in the first few seconds — let the entry thesis play out first.
    private const val MIN_HOLD_BEFORE_PIVOT_MS = 20_000L

    private val pivotCount  = ConcurrentHashMap<String, Int>()

    /** Candidate live exit techniques, expressed as tradingMode strings that the
     *  existing BotService→ModeSpecificExits switch already understands. */
    private val CANDIDATES = listOf(
        "MOONSHOT",      // GRADUATION — let a confirmed runner run (the proven winner)
        "PUMP_SNIPER",   // SENTIMENT_IGNITION — ride an igniting pump, tighter
        "STANDARD",      // TREND_PULLBACK — widest patience, tightest stop (diamond-ish)
        "REVIVAL",       // REVERSAL_RECLAIM — take first target quick on a bounce
        "MICRO_CAP",     // FRESH_LAUNCH — fastest stop/partial for a fading fresh
    )

    /** Immutable event-local owner. A missing/mismatched seal never grants shared-tool authority. */
    internal fun entryOwner8384(ts: TokenState): String {
        val id = ts.position.positionId
        if (id.isBlank()) return ""
        val snap = com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.cachedManagement8384(id) ?: return ""
        val mode = if (ts.position.isPaperPosition) "paper" else "live"
        return if (snap.mint == ts.mint && snap.mode8090.equals(mode, true)) snap.entryLane else ""
    }

    /** Shared tools are exit techniques, not entry-lane migrations. Unproved losers cannot buy patience. */
    internal fun switchAllowed8384(owner: String, from: String, to: String, pnl: Double, peak: Double,
                                  momentumConfirmed: Boolean): Boolean {
        if (from == to || !pnl.isFinite() || !peak.isFinite() ||
            to !in HoldingLogicLayer.getAllModeParams()) return false
        // superseded by V5.0.8418 — the runner guard binds for EVERY owner again (8384 exempted ensemble desks, so a CORE /
        // TREASURY / CASHGEN / CYCLIC-owned runner could be re-laned out of runner management): V5.0.7369 FBdrdZ.
        if (RunnerExitProfile7277.refusesLaneChange(from, to)) return false
        val old = HoldingLogicLayer.getHoldParams(from)
        val next = HoldingLogicLayer.getHoldParams(to)
        val addsPatience = next.maxHoldTimeMs > old.maxHoldTimeMs || next.targetProfitPct > old.targetProfitPct
        return !addsPatience || (pnl >= 8.0 && (peak - pnl).coerceAtLeast(0.0) <= 8.0 && momentumConfirmed)
    }

    /** One commit point for arbiter, hold layer and cross-talk. Position-scoped, mode-local and restart-durable. */
    internal fun applyMode8384(ts: TokenState, target: String, pnl: Double, peak: Double,
                              momentumConfirmed: Boolean, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val pos = ts.position
        val owner = entryOwner8384(ts)
        val from: String
        synchronized(pos) {
            from = pos.tradingMode.uppercase()
            if (!pos.isOpen || nowMs - pos.entryTime < MIN_HOLD_BEFORE_PIVOT_MS ||
                !switchSlotFree8384(pos.lastManagementSwitchAtMs8384, nowMs) ||
                !switchAllowed8384(owner, from, target, pnl, peak, momentumConfirmed)) return false
            pos.tradingMode = target
            pos.tradingModeEmoji = HoldingLogicLayer.getModeEmoji(target)
            pos.modeHistory = if (pos.modeHistory.isEmpty()) "$from>$target" else "${pos.modeHistory}>$target"
            pos.lastManagementSwitchAtMs8384 = nowMs
        }
        // Existing periodic PositionPersistence checkpoints serialize mode/history/cooldown together.
        try {
            PipelineHealthCollector.labelInc("HELD_SHARED_TOOL_SWITCH_8384_${owner.ifBlank { "LEGACY" }}")
            ForensicLogger.lifecycle("HELD_SHARED_TOOL_SWITCH_8384", "mint=${ts.mint} positionId=${pos.positionId} mode=${if(pos.isPaperPosition) "paper" else "live"} owner=$owner from=$from to=$target reason=$reason at=$nowMs build=8384")
        } catch (_: Throwable) {}
        return true
    }

    internal fun switchSlotFree8384(lastMs: Long, nowMs: Long): Boolean =
        lastMs == 0L || nowMs - lastMs >= PIVOT_COOLDOWN_MS

    data class PivotResult(
        val pivoted: Boolean,
        val fromMode: String,
        val toMode: String,
        val score: Double,
        val incumbentScore: Double,
        val reason: String,
    )

    /**
     * Evaluate one held position. If a better-fitting technique is found with
     * conviction, change only its management technique through applyMode8384. The
     * entry outcome signature is never re-stamped. Returns a PivotResult for logging. Never throws.
     */
    fun evaluate(
        ts: TokenState,
        pnlPct: Double,
        peakPnlPct: Double,
        holdTimeMs: Long,
    ): PivotResult {
        val current = ts.position.tradingMode.uppercase().ifBlank { "STANDARD" }
        try {
            val mint = ts.mint
            val now = System.currentTimeMillis()

            // Gate 1: respect the entry thesis for the first moments.
            if (holdTimeMs < MIN_HOLD_BEFORE_PIVOT_MS) {
                return PivotResult(false, current, current, 0.0, 0.0, "too_early")
            }
            // Gate 2: cooldown — don't thrash.
            val last = ts.position.lastManagementSwitchAtMs8384
            if (!switchSlotFree8384(last, now)) {
                return PivotResult(false, current, current, 0.0, 0.0, "cooldown")
            }

            // ── Build the live signature the forecast model is keyed on ──
            val score = (ts.lastV3Score ?: 0).coerceIn(0, 100)
            val quality = resolveQuality(ts)
            val regime = try {
                MarketRegimeAI.getCurrentRegime().label
            } catch (_: Throwable) { "DEFAULT" }
            val edgePhase = "UNKNOWN"  // not tracked on held TokenState; coarse-key handles it

            // ── Predictive brains (shared, read-only) ──
            val momentum = try { MomentumPredictorAI.getPrediction(mint) } catch (_: Throwable) { null }
            val momentumBias = momentumBiasFor(momentum)   // [-0.15, +0.20]
            val drawFromPeak = if (peakPnlPct > 0.0) (peakPnlPct - pnlPct) else 0.0

            val owner8384 = entryOwner8384(ts)
            val ensemble8384 = com.lifecyclebot.engine.cortex.LanePlaybook7907.isEnsemble8384(owner8384)
            // Shared techniques are not permission to import another entry desk's favourable learning.
            // ── Score the incumbent technique ──
            val incumbentScore = blendedScore(
                lane = current, evidenceLane = if (ensemble8384) owner8384 else current, score = score, quality = quality, regime = regime,
                edgePhase = edgePhase, momentumBias = momentumBias,
                pnlPct = pnlPct, drawFromPeak = drawFromPeak, isIncumbent = true,
            )

            // ── Find the best candidate ──
            var bestLane = current
            var bestScore = incumbentScore
            var bestReason = "incumbent"
            val techniques8384 = if (com.lifecyclebot.engine.cortex.LanePlaybook7907.isEnsemble8384(owner8384))
                HoldingLogicLayer.getAllModeParams().keys else CANDIDATES
            for (cand in techniques8384) {
                if (cand == current) continue
                val s = blendedScore(
                    lane = cand, evidenceLane = if (ensemble8384) owner8384 else cand, score = score, quality = quality, regime = regime,
                    edgePhase = edgePhase, momentumBias = momentumBias,
                    pnlPct = pnlPct, drawFromPeak = drawFromPeak, isIncumbent = false,
                )
                if (s > bestScore) { bestScore = s; bestLane = cand; bestReason = "fwd+mom+hive" }
            }

            // Gate 3: conviction margin — only pivot if clearly better.
            if (bestLane == current || (bestScore - incumbentScore) < CONVICTION_MARGIN) {
                return PivotResult(false, current, current, bestScore, incumbentScore, "below_margin")
            }
            if (!switchAllowed8384(owner8384, current, bestLane, pnlPct, peakPnlPct,
                    momentum?.name in setOf("STRONG_PUMP", "PUMP_BUILDING"))) {
                return PivotResult(false, current, current, bestScore, incumbentScore, "runner_lane_kept_7369")
            }

            // V5.0.7403 — NO THESIS LAUNDERING.
            // A losing/failed fast thesis may tighten or become a recovery/quick-cut
            // style, but it may NOT upgrade itself into a more patient runner just
            // because a historical model likes that lane. Only a position already
            // proving itself may graduate into MOONSHOT/STANDARD patience.
            val patientTarget7403 = bestLane == "MOONSHOT" || bestLane == "STANDARD"
            val launch7403 = try {
                com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts)
            } catch (_: Throwable) { null }
            val runnerEarned7403 =
                pnlPct >= 8.0 &&
                drawFromPeak <= 8.0 &&
                launch7403?.tooLateForSnipe != true &&
                momentum?.name !in setOf("WEAK", "DISTRIBUTION")
            if (patientTarget7403 && !runnerEarned7403) {
                try { PipelineHealthCollector.labelInc("HELD_PIVOT_PATIENCE_NOT_EARNED_7403") } catch (_: Throwable) {}
                return PivotResult(false, current, current, bestScore, incumbentScore, "patience_not_earned_7403")
            }
            if (pnlPct < -5.0 && bestLane !in setOf("MICRO_CAP", "REVIVAL")) {
                try { PipelineHealthCollector.labelInc("HELD_PIVOT_LOSER_CANNOT_EXTEND_THESIS_7403") } catch (_: Throwable) {}
                return PivotResult(false, current, current, bestScore, incumbentScore, "loser_cannot_extend_thesis_7403")
            }

            // ── PIVOT. Soft-shape: only changes the live exit style. ──
            if (!applyMode8384(ts, bestLane, pnlPct, peakPnlPct,
                    momentum?.name in setOf("STRONG_PUMP", "PUMP_BUILDING"), bestReason, now))
                return PivotResult(false, current, current, bestScore, incumbentScore, "switch_not_granted_8384")
            pivotCount[mint] = (pivotCount[mint] ?: 0) + 1

            // V5.0.6866 §THE_RESTAMP_CREDITED_AN_ENTRY_TO_A_LANE_THAT_DID_NOT_MAKE_IT —
            // this used to call ForwardOutcomeModel.stamp(mint, bestLane, ...) with the
            // comment "re-stamp so the settled outcome credits the PIVOTED signature".
            //
            // ForwardOutcomeModel is an ENTRY predictor: stamp() is called at buy and
            // records the mint → (fineKey, coarseKey) signature whose cell the settled
            // PnL will update, so that the next candidate matching that signature gets
            // a better P(win) BEFORE entry. Re-stamping replaced pending[mint] outright,
            // so the original entry signature was destroyed and the outcome was credited
            // to a lane that never made the decision. The pivoted lane learned "entries
            // like this do well/badly" about an entry it did not take, and the lane that
            // did take it learned nothing at all — the same corruption V5.0.6855 fixed on
            // the lane-promotion path, which the pivot arbiter's own header advertises as
            // "self-crediting".
            //
            // It also contradicts the two lines above it: this is a "soft-shape" that
            // "only changes the live exit style". An exit-style change is not an entry,
            // so the entry signature stays with the lane that earned it.

            try {
                ForensicLogger.lifecycle(
                    "HELD_PIVOT",
                    "mint=${mint.take(8)} sym=${ts.symbol} $current->$bestLane " +
                    "score=${"%.3f".format(bestScore)} inc=${"%.3f".format(incumbentScore)} " +
                    "pnl=${pnlPct.toInt()}% peak=${peakPnlPct.toInt()}% mom=${momentum?.name ?: "?"} " +
                    "n=${pivotCount[mint]}"
                )
            } catch (_: Throwable) {}

            return PivotResult(true, current, bestLane, bestScore, incumbentScore, bestReason)
        } catch (_: Throwable) {
            // Fail-open: never disturb a held position on error.
            return PivotResult(false, current, current, 0.0, 0.0, "error")
        }
    }

    /**
     * Blended fitness of a technique for THIS token RIGHT NOW.
     * Core = forward expectancy from the accumulated database (pWin × E),
     * shaped by live momentum + the hive's view of the mode + a small geometry
     * term that rewards letting a confirmed runner run and cutting a staller.
     */
    private fun blendedScore(
        lane: String, evidenceLane: String, score: Int, quality: String, regime: String, edgePhase: String,
        momentumBias: Double, pnlPct: Double, drawFromPeak: Double, isIncumbent: Boolean,
    ): Double {
        // Forward expectancy from the proven outcome database.
        val fwd = try {
            ForwardOutcomeModel.forecast(evidenceLane, score, quality, regime, edgePhase)
        } catch (_: Throwable) { null }

        // pWin in [0,1]; expectedPnl normalised; bootstrap -> neutral 0.5.
        val pWin = fwd?.pWin ?: 0.5
        val expTerm = ((fwd?.expectedPnl ?: 0.0) / 100.0).coerceIn(-0.40, 0.20)
        val rugPenalty = (fwd?.pRug ?: 0.0) * 0.25
        // Trust forecasts with real samples more than bootstrap ones.
        val sampleTrust = if ((fwd?.samples ?: 0L) >= 10L) 1.0 else 0.55

        var s = (pWin - 0.5) + expTerm - rugPenalty
        s *= sampleTrust

        // Hive-mind view of this mode (read-only).
        s += hiveBiasFor(evidenceLane)

        // Live momentum bias applies most to the "let it run" techniques.
        val runnerLane = lane == "MOONSHOT" || lane == "STANDARD"
        if (runnerLane) s += momentumBias else s += momentumBias * 0.4

        // Geometry nudge: a position in profit with momentum should lean to runner
        // techniques; one bleeding from peak should lean to quick-cut techniques.
        if (pnlPct > 8.0 && drawFromPeak < 6.0 && runnerLane) s += 0.06
        if (drawFromPeak > 18.0 && (lane == "MICRO_CAP" || lane == "REVIVAL")) s += 0.05

        // Tiny incumbency bonus so we don't pivot on noise-level ties.
        if (isIncumbent) s += 0.03

        return s
    }

    private fun momentumBiasFor(m: MomentumPredictorAI.MomentumPrediction?): Double = when (m?.name) {
        "STRONG_PUMP"   -> 0.20
        "PUMP_BUILDING" -> 0.10
        "WEAK"          -> -0.08
        "DISTRIBUTION"  -> -0.15
        else            -> 0.0
    }

    private fun hiveBiasFor(lane: String): Double {
        return try {
            when (CollectiveIntelligenceAI.getModeRecommendation(lane).name) {
                "STRONGLY_RECOMMENDED" -> 0.10
                "RECOMMENDED"          -> 0.05
                "CAUTION"              -> -0.05
                "AVOID"                -> -0.12
                else                   -> 0.0
            }
        } catch (_: Throwable) { 0.0 }
    }

    private fun resolveQuality(ts: TokenState): String {
        return try {
            val q = ts.meta.setupQuality
            if (q.isNotBlank() && q != "SKIP") q.take(1).uppercase() else "C"
        } catch (_: Throwable) { "C" }
    }

    /** Called by Executor on close to clean per-position state. */
    fun onClosed(mint: String) {
        pivotCount.remove(mint)
    }
}
