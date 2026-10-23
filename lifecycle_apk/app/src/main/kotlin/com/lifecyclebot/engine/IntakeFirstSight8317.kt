package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8317 §A_LAUNCH_IS_NEAR_A_DECISION_THE_MOMENT_IT_ARRIVES (owner directive 8314, P3 Fix 3 / P4.1).
 *
 * 5.0.8314: DEX_HOST_BUDGET_REFUSED_8287_BULK 106,240 while DexScreener sent 0 429s. A new coin with unknown liquidity
 * was deferred by its lane (NATIVE_LANE_LIQ_UNHYDRATED_DEFER SHITCOIN/EXPRESS 1,112 / 1,109) before the setup
 * classifier could ask for its inputs, so it was never marked near-decision (DataPipeline8191), its DexScreener pair
 * fetch stayed BULK and was refused while fewer than the reserve remained — a circular rule that kept new coins at the
 * lowest priority. And LiquidityTruth8037.hydrate8037 queued the chain read of an unknown curve only when a decision
 * first read the coin (flushed every 10 s), so a brand-new curve was judged before its depth was asked for.
 *
 *  - [noteIntake8317]: a mint entering intake inside its launch window ([LAUNCH_WINDOW_MS_8317] of chain age, or whose
 *    chain age is not resolved yet) is near a decision now, so its pair fetch, candle seed, Helius focus and Birdeye
 *    enrichment run at decision priority for the ten minutes DataPipeline8191 keeps the mark.
 *  - [firstSight8317]: the moment a TokenState is created for it, its curve / pool depth is asked from the chain
 *    (OnChainDepth8038.needDepth8047: curve coins and named pools only, per-mint throttled), beside the lane / tool
 *    affinities intake wrote there before.
 * Nothing is inferred: a mark is priority, not evidence; the chain read answers with observed reserves or nothing.
 */
object IntakeFirstSight8317 {
    /** The launch window intake marks near-decision: the directive's 30 minutes of chain age. */
    const val LAUNCH_WINDOW_MS_8317 = 30L * 60_000L

    private val nearMarked = AtomicLong(0L)
    private val nearUnknownAge = AtomicLong(0L)
    private val notMarked = AtomicLong(0L)
    private val depthAsked = AtomicLong(0L)

    /** Pure: is a coin of chain age [ageMs] (null = not resolved) inside its launch window? Unresolved is inside: it is checked, not assumed old. */
    fun insideLaunchWindow8317(ageMs: Long?): Boolean = ageMs == null || ageMs < LAUNCH_WINDOW_MS_8317

    private fun chainAgeMs8317(mint: String, nowMs: Long): Long? = try {
        com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.resolve(mint, nowMs)?.let { (nowMs - it.birthMs).coerceAtLeast(0L) }
    } catch (_: Throwable) { null }

    /** TokenMergeQueue.enqueue (a mint's first entry) / [firstSight8317]: mark a launch-window coin near a decision. True when marked. */
    fun noteIntake8317(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank()) return false
        val age = chainAgeMs8317(mint, nowMs)
        if (!insideLaunchWindow8317(age)) { notMarked.incrementAndGet(); return false }
        try { DataPipeline8191.noteNearDecision8191(mint, nowMs) } catch (_: Throwable) { return false }
        nearMarked.incrementAndGet()
        if (age == null) nearUnknownAge.incrementAndGet()
        try { PipelineHealthCollector.labelInc(if (age == null) "INTAKE_NEAR_DECISION_UNKNOWN_AGE_8317" else "INTAKE_NEAR_DECISION_LAUNCH_8317") } catch (_: Throwable) {}
        return true
    }

    /**
     * BotService.admitProtectedMemeIntake (the line that creates a TokenState): the affinities as before, then the chain
     * depth read for a coin whose depth nobody has read and the near-decision mark.
     */
    fun firstSight8317(fresh: TokenState, laneAffinity: Collection<String>, toolAffinity: Collection<String>) {
        fresh.laneAffinity.addAll(laneAffinity)
        fresh.toolAffinity.addAll(toolAffinity)
        if (!(fresh.lastLiquidityUsd > 0.0)) {
            try { com.lifecyclebot.engine.truth.OnChainDepth8038.needDepth8047(fresh); depthAsked.incrementAndGet() } catch (_: Throwable) {}
        }
        noteIntake8317(fresh.mint)
        // V5.0.8345 — THE install site of a receiver's TokenState (admitProtectedMemeIntake's getOrPut): a hand-off task deferred
        // DEFERRED_NO_TOKEN_STATE resumes here, at the arrival, not when a later priority window happens to notice (8344: 0 resumes).
        try { HandoffReceiverTasks8153.onStateInstalled8330(fresh.mint) } catch (_: Throwable) {}
    }

    fun statusLine8317(): String =
        "intake8317[nearMarked=${nearMarked.get()} unknownAge=${nearUnknownAge.get()} pastWindow=${notMarked.get()} depthAsked=${depthAsked.get()}]"
}
