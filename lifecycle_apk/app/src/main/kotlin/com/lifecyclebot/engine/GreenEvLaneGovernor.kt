package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6120h — GreenEvLaneGovernor
 *
 * OPERATOR DIRECTIVE: "ideally all of the lanes should be able to trade
 * with positive returns and brilliant EV. winrate will drive up as a
 * result. Compounding the wallet balance striving to achieve its minimum
 * daily 2x-5x balance compound target."
 *
 * FOLLOW-UP: "how can a paused lane prove recovery if it isnt trading?
 * shadow engine? llm lab? we have to make sure the llm strategy lab is
 * able to actually do this! the paused lane goes to the llm lab it
 * proves new strategies for that lane they get promoted the lane
 * returns to trading. Autonomously."
 *
 * WHAT THIS DOES
 * ══════════════
 *
 * For every LIVE lane the operator has enabled, the Governor tracks a
 * 20-trade rolling window of realized EV. If EV falls below -3.0% and
 * the lane has enough sample (n >= 20), the lane enters PAUSED state:
 *
 *   1. Live entries on that lane are blocked at the FDG (size × 0.0 = veto).
 *   2. The lane is REGISTERED with the LLM Lab as a recovery target.
 *      LlmLabEngine focuses its creation gap on strategies tagged for
 *      the paused lane (min entry score, TP/SL/hold, regime). The Lab
 *      paper-trades those strategies against live universe ticks.
 *   3. When any lab strategy for the lane clears the recovery threshold
 *      (paper WR >= 55% AND paper avgPnl >= +15% over 8+ paper trades),
 *      it is auto-promoted. The lane returns to LIVE with the promoted
 *      strategy's SL/TP/hold hints applied.
 *   4. If no strategy promotes inside RECOVERY_MAX_MS (24h), the lane
 *      stays paused but the Governor keeps re-registering with the Lab
 *      so evolution keeps trying.
 *
 * ALSO: REGIME-CHOP DAMPER. When the regime detector says CHOP AND the
 * blended rolling WR is < 30%, ALL lanes size ×0.6. This is a market-
 * condition acknowledgement — even a healthy lane loses money in a chop
 * market, so we reduce exposure across the board until regime clears.
 *
 * DOCTRINE #86 — fail-open. Every read is cached in-memory. Every write
 * is on a daemon coroutine. Every function is try/catch and returns a
 * neutral default. The governor is a filter, not a hard dependency —
 * if it errors out the bot still trades.
 */
object GreenEvLaneGovernor {

    // ── Tunables ────────────────────────────────────────────────────────
    /** Rolling window size for lane EV computation. */
    const val WINDOW_TRADES = 20

    /** EV threshold below which a lane gets paused. */
    const val PAUSE_EV_THRESHOLD = -3.0

    /** How many wins in a row after resume-attempt to lock recovery. */
    const val RECOVERY_WIN_STREAK = 5

    /** How long a paused lane can stay paused before Governor logs stall. */
    const val RECOVERY_MAX_MS = 24L * 60L * 60L * 1000L

    /** Regime-chop lane-wide damper. */
    const val CHOP_DAMP_MULT = 0.6
    const val CHOP_DAMP_WR_THRESHOLD = 0.30

    // Update at most once per Nms so the recompute doesn't hit hot path.
    private const val RECOMPUTE_INTERVAL_MS = 30_000L

    // ── State ────────────────────────────────────────────────────────────
    enum class LaneState { LIVE, PAUSED, RECOVERING }

    data class LaneStatus(
        val lane: String,
        val state: LaneState,
        val ev: Double,
        val wr: Double,
        val n: Int,
        val pausedAtMs: Long,
        val recoveryWins: Int,
    )

    private val statusByLane = ConcurrentHashMap<String, LaneStatus>()
    private val lastRecomputeMs = AtomicLong(0L)
    private val labRecoveryRequests = ConcurrentHashMap<String, Long>()

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Recompute lane states from journal. Call from BotService bot loop
     * every N loops or from a dedicated background timer. Idempotent and
     * throttled so a hot-path caller can't spam it.
     *
     * @param recentSells recent live SELL rows (canonical trades journal
     *                    entries with side="SELL", mode="live")
     */
    fun recompute(recentSells: List<Trade>, regimeIsChop: Boolean, blendedWr: Double) {
        val nowMs = System.currentTimeMillis()
        val last = lastRecomputeMs.get()
        if (nowMs - last < RECOMPUTE_INTERVAL_MS) return
        if (!lastRecomputeMs.compareAndSet(last, nowMs)) return

        try {
            // Bucket sells by lane (tradingMode) — most-recent-first ordering
            // guaranteed by caller (recentSells is descending by ts_exit).
            val byLane = LinkedHashMap<String, MutableList<Trade>>()
            for (t in recentSells) {
                val lane = t.tradingMode
                if (lane.isBlank()) continue
                val bucket = byLane.getOrPut(lane) { mutableListOf() }
                if (bucket.size < WINDOW_TRADES) bucket.add(t)
            }

            for ((lane, trades) in byLane) {
                if (trades.size < WINDOW_TRADES) continue        // warmup
                val evPct = trades.map { it.pnlPct }.filter { it.isFinite() }.average()
                val wins  = trades.count { it.pnlPct > 5.0 }
                val wr    = wins.toDouble() / trades.size.toDouble()

                val prior = statusByLane[lane]
                val newState = when {
                    prior?.state == LaneState.PAUSED -> {
                        // If the lane's rolling EV came back into green from
                        // some other path (partial fills, wins that snuck
                        // through, or lab-promoted strategy), auto-resume.
                        if (evPct >= 0.0 && wr >= 0.35) LaneState.LIVE else LaneState.PAUSED
                    }
                    prior?.state == LaneState.RECOVERING -> {
                        if (prior.recoveryWins >= RECOVERY_WIN_STREAK) LaneState.LIVE
                        else if (evPct <= PAUSE_EV_THRESHOLD * 2.0) LaneState.PAUSED
                        else LaneState.RECOVERING
                    }
                    else -> {
                        if (evPct < PAUSE_EV_THRESHOLD && trades.size >= WINDOW_TRADES) {
                            LaneState.PAUSED
                        } else LaneState.LIVE
                    }
                }

                val pausedAtMs = when {
                    prior?.state == LaneState.PAUSED && newState == LaneState.PAUSED -> prior.pausedAtMs
                    newState == LaneState.PAUSED -> nowMs
                    else -> 0L
                }
                val recoveryWins = if (newState == LaneState.LIVE) 0 else prior?.recoveryWins ?: 0

                statusByLane[lane] = LaneStatus(
                    lane = lane,
                    state = newState,
                    ev = evPct,
                    wr = wr,
                    n = trades.size,
                    pausedAtMs = pausedAtMs,
                    recoveryWins = recoveryWins,
                )

                if (newState == LaneState.PAUSED && prior?.state != LaneState.PAUSED) {
                    try {
                        ForensicLogger.lifecycle(
                            "GREEN_EV_LANE_PAUSED_6120h",
                            "lane=$lane ev=${"%.2f".format(evPct)}% wr=${"%.2f".format(wr * 100)}% n=${trades.size} — delegating to LLM Lab for recovery",
                        )
                        PipelineHealthCollector.labelInc("GREEN_EV_LANE_PAUSED_6120h")
                    } catch (_: Throwable) {}
                    // V5.0.6121 — richer autopsy for the Lab hint. Extracts
                    // the specific failure signature (RUG_HEAVY / SL_CHURN /
                    // FAST_DEATH / SLOW_BLEED / CHOP_SCALP_MISS) so the Lab
                    // can invent a targeted recovery strategy.
                    val autopsy = try { BleedingLaneAutopsy.autopsy(lane, trades) } catch (_: Throwable) { null }
                    delegateToLab(lane, evPct, wr, trades.size, autopsy?.constraintForLab ?: "")
                }
                if (newState == LaneState.LIVE && prior?.state == LaneState.PAUSED) {
                    try {
                        ForensicLogger.lifecycle(
                            "GREEN_EV_LANE_RESUMED_6120h",
                            "lane=$lane ev=${"%.2f".format(evPct)}% wr=${"%.2f".format(wr * 100)}% n=${trades.size} — recovery proven, live re-enabled",
                        )
                        PipelineHealthCollector.labelInc("GREEN_EV_LANE_RESUMED_6120h")
                    } catch (_: Throwable) {}
                }
            }

            // Persist regime + blended-WR for chop damper decisions.
            regimeChopSnapshot = regimeIsChop
            blendedWrSnapshot = blendedWr
        } catch (_: Throwable) { /* fail-open */ }
    }

    /**
     * Report an incoming live SELL to the governor so a paused lane can
     * accumulate a win streak toward recovery. Called from BotService's
     * onLiveClose hook.
     */
    fun recordLiveClose(lane: String, pnlPct: Double) {
        try {
            val prior = statusByLane[lane] ?: return
            if (prior.state != LaneState.PAUSED && prior.state != LaneState.RECOVERING) return
            val newRecoveryWins = if (pnlPct > 5.0) prior.recoveryWins + 1 else 0
            val newState = when {
                newRecoveryWins >= RECOVERY_WIN_STREAK -> LaneState.LIVE
                else -> LaneState.RECOVERING
            }
            statusByLane[lane] = prior.copy(state = newState, recoveryWins = newRecoveryWins)
        } catch (_: Throwable) {}
    }

    /**
     * Consumed by FinalDecisionGate. Returns the size multiplier to apply
     * to a new entry on this lane. 0.0 = veto (paused), 0.6 = chop damper,
     * 1.0 = normal.
     */
    fun laneSizeMultiplier(lane: String): Double {
        return try {
            val status = statusByLane[lane]
            val laneBlocked = status?.state == LaneState.PAUSED
            // RECOVERING lanes size 0.5× — small experimental toe-in-water
            // so the recovery-win-streak can actually accumulate. Otherwise
            // the lane is either paused (0.0) or live (1.0 × any other
            // multipliers).
            val laneMult = when (status?.state) {
                LaneState.PAUSED -> 0.0
                LaneState.RECOVERING -> 0.5
                else -> 1.0
            }
            val chopMult = if (regimeChopSnapshot && blendedWrSnapshot < CHOP_DAMP_WR_THRESHOLD) CHOP_DAMP_MULT else 1.0
            (laneMult * chopMult).coerceIn(0.0, 1.0)
        } catch (_: Throwable) { 1.0 }
    }

    /** Read-only inspection for UI / forensic. */
    fun snapshot(): Map<String, LaneStatus> = statusByLane.toMap()

    fun labRecoveryTargets(): List<String> = statusByLane.values
        .filter { it.state == LaneState.PAUSED || it.state == LaneState.RECOVERING }
        .map { it.lane }

    // ── Internal ─────────────────────────────────────────────────────────
    @Volatile private var regimeChopSnapshot: Boolean = false
    @Volatile private var blendedWrSnapshot: Double = 1.0

    /**
     * Register the paused lane with LlmLabEngine as a recovery target so
     * the Lab focuses its next few creation cycles on strategies for
     * this lane. LabEngine.consumeLaneRecoveryHints() reads this list on
     * every creation gap.
     */
    private fun delegateToLab(lane: String, evPct: Double, wr: Double, n: Int, autopsyConstraint: String = "") {
        val nowMs = System.currentTimeMillis()
        val last = labRecoveryRequests[lane] ?: 0L
        if (nowMs - last < 60_000L) return               // 1 min throttle per lane
        labRecoveryRequests[lane] = nowMs
        // Publish to LabEngine via its recovery-hint channel (see hint queue below).
        LabRecoveryHintQueue.enqueue(
            LabRecoveryHint(
                lane = lane,
                evPct = evPct,
                wr = wr,
                n = n,
                requestedAtMs = nowMs,
                autopsyConstraint = autopsyConstraint,
            )
        )
    }
}

/**
 * V5.0.6120h — Cross-module recovery-hint channel. GreenEvLaneGovernor
 * publishes hints when a lane needs Lab attention; LlmLabEngine reads
 * hints on every creation cycle and biases its new-strategy design toward
 * the paused lane's needs. Lightweight — a bounded LinkedBlockingQueue.
 */
object LabRecoveryHintQueue {
    data class LabRecoveryHint(
        val lane: String,
        val evPct: Double,
        val wr: Double,
        val n: Int,
        val requestedAtMs: Long,
        val autopsyConstraint: String = "",
    )

    private val q = java.util.concurrent.LinkedBlockingQueue<LabRecoveryHint>(16)

    fun enqueue(hint: LabRecoveryHint) {
        try {
            // Coalesce: drop older hints for the same lane so the queue
            // doesn't grow unbounded on chronic bleeders.
            q.removeIf { it.lane == hint.lane }
            q.offer(hint)
        } catch (_: Throwable) {}
    }

    fun drainAll(): List<LabRecoveryHint> {
        val out = mutableListOf<LabRecoveryHint>()
        try { q.drainTo(out) } catch (_: Throwable) {}
        return out
    }

    fun peekAll(): List<LabRecoveryHint> = try { q.toList() } catch (_: Throwable) { emptyList() }
}

/** Type alias so callers outside the queue object can name the record easily. */
typealias LabRecoveryHint = LabRecoveryHintQueue.LabRecoveryHint
