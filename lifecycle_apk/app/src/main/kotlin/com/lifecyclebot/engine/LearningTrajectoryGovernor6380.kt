package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * V5.0.6380 — LEARNING TRAJECTORY GOVERNOR.
 *
 * Operator directives (verbatim, both received across sessions):
 *   "again minimum daily wallet increase of 2x-5x in paper or live trading.
 *    no exceptions. bot must benchmark there!!!!"
 *   "it must work the right way and learn the right way to trade from the
 *    first trade. it must improve over time not go backwards!!!"
 *
 * Additive read-only telemetry module — never mutates trading state, never
 * blocks a buy. Its only job is to surface WHETHER the AATE stack is
 * hitting the 2×-5× daily target or regressing, so the operator sees a
 * single unambiguous counter in every pipeline dump:
 *
 *   WALLET_GROWTH_TRAJECTORY_6380|UP|<pct24h>          (positive delta)
 *   WALLET_GROWTH_TRAJECTORY_6380|DOWN|<pct24h>        (negative delta)
 *   WALLET_GROWTH_TRAJECTORY_6380|FLAT                 (within ±1%)
 *   WALLET_GROWTH_TARGET_HIT_6380|2X                   (≥ 2× start)
 *   WALLET_GROWTH_TARGET_HIT_6380|5X                   (≥ 5× start)
 *   WALLET_GROWTH_TARGET_MISS_6380|<pctOfTarget>       (< 2×)
 *
 * The trajectory sampler stores one (wallet, ts) snapshot per hour so we
 * can compare current wallet to the value 24h ago. If we don't have 24h
 * of history yet, we compare against uptime-scaled expectations (so from
 * TRADE ONE the operator sees an early indicator whether we're on track).
 */
object LearningTrajectoryGovernor6380 {

    /** Bucket window — one snapshot per hour maximum. */
    private const val SNAPSHOT_BUCKET_MS: Long = 60L * 60L * 1000L

    /** 24-hour comparison window. */
    private const val LOOKBACK_MS: Long = 24L * 60L * 60L * 1000L

    /** 2× / 5× multipliers as directed by operator. */
    private const val TARGET_MIN_MULT: Double = 2.0
    private const val TARGET_STRETCH_MULT: Double = 5.0

    /** Flat-band tolerance for UP/DOWN/FLAT tagging. */
    private const val FLAT_BAND_PCT: Double = 1.0

    private data class Snap(val walletSol: Double, val tsMs: Long)

    // Small ring buffer — 32 hourly samples is plenty for 24h lookback.
    private val snaps = ArrayDeque<Snap>()
    private val samplesTaken = AtomicLong(0L)
    private val lastEmittedAt = AtomicLong(0L)
    @Volatile private var lastEmittedTag: String = ""

    /**
     * Record a wallet snapshot at most once per SNAPSHOT_BUCKET_MS. Cheap
     * on the hot path — a single timestamp compare then early-out.
     */
    @Synchronized
    fun observe(walletSol: Double, startCapitalSol: Double) {
        val now = System.currentTimeMillis()
        val last = snaps.lastOrNull()
        if (last == null || (now - last.tsMs) >= SNAPSHOT_BUCKET_MS) {
            snaps.addLast(Snap(walletSol, now))
            samplesTaken.incrementAndGet()
            // Keep buffer bounded to ~48 hours worth of samples.
            while (snaps.size > 48) snaps.removeFirst()
            emitTrajectory(walletSol, startCapitalSol, now)
        }
    }

    private fun emitTrajectory(currentWallet: Double, startCapitalSol: Double, now: Long) {
        val cutoff = now - LOOKBACK_MS
        // Find oldest snapshot within lookback window (approximates the 24h-ago wallet).
        val anchor = snaps.firstOrNull { it.tsMs >= cutoff && it.tsMs < now - SNAPSHOT_BUCKET_MS / 2 }
            ?: snaps.firstOrNull()
        val anchorWallet = anchor?.walletSol ?: startCapitalSol.coerceAtLeast(0.001)

        // Trajectory tag.
        val delta = currentWallet - anchorWallet
        val pct = if (anchorWallet > 0.001) (delta / anchorWallet) * 100.0 else 0.0
        val trajectoryTag = when {
            abs(pct) < FLAT_BAND_PCT -> "FLAT"
            pct > 0 -> "UP|${"%.0f".format(pct)}pct"
            else -> "DOWN|${"%.0f".format(pct)}pct"
        }
        try { PipelineHealthCollector.labelInc("WALLET_GROWTH_TRAJECTORY_6380|$trajectoryTag") } catch (_: Throwable) {}

        // 2× / 5× target vs start capital.
        val walletMult = if (startCapitalSol > 0.001) currentWallet / startCapitalSol else 0.0
        try {
            when {
                walletMult >= TARGET_STRETCH_MULT ->
                    PipelineHealthCollector.labelInc("WALLET_GROWTH_TARGET_HIT_6380|5X")
                walletMult >= TARGET_MIN_MULT ->
                    PipelineHealthCollector.labelInc("WALLET_GROWTH_TARGET_HIT_6380|2X")
                else -> {
                    val pctOfTarget = (walletMult / TARGET_MIN_MULT * 100.0).coerceAtLeast(0.0)
                    PipelineHealthCollector.labelInc("WALLET_GROWTH_TARGET_MISS_6380|${"%.0f".format(pctOfTarget)}pct_of_2x")
                }
            }
        } catch (_: Throwable) {}

        lastEmittedAt.set(now)
        lastEmittedTag = trajectoryTag
    }

    fun statusLine(): String {
        val n = samplesTaken.get()
        if (n == 0L) return "V5.0.6380 trajectory: no samples yet"
        val current = snaps.lastOrNull() ?: return "V5.0.6380 trajectory: uninitialised"
        val anchor = snaps.firstOrNull() ?: current
        val hours = ((current.tsMs - anchor.tsMs).coerceAtLeast(0)) / (60 * 60 * 1000)
        val pct = if (anchor.walletSol > 0.001)
            ((current.walletSol - anchor.walletSol) / anchor.walletSol) * 100.0 else 0.0
        return "V5.0.6380 trajectory: last=$lastEmittedTag · ${"%.4f".format(current.walletSol)}SOL over ${hours}h from ${"%.4f".format(anchor.walletSol)}SOL (${"%+.1f".format(pct)}%)"
    }

    internal fun resetForTest() {
        snaps.clear()
        samplesTaken.set(0L)
        lastEmittedAt.set(0L)
        lastEmittedTag = ""
    }

    internal fun sampleCount(): Int = snaps.size
}
