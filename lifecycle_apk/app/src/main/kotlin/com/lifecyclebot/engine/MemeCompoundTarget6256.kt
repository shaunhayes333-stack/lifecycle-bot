package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * MEME COMPOUND TARGET — V5.0.6256
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Operator directive: "meme trader must target 2x -5x live wallet balance
 *                     growth compound daily!!!!"
 *
 * Tracks the LIVE wallet's SOL balance at the start of each rolling UTC day
 * and provides a per-lane size multiplier that pushes MEME-family lanes
 * (MEME / SHITCOIN / MOONSHOT / PRESALE_SNIPE) toward 2x–5x growth by end of
 * day. When the daily target is hit, sizing normalises. When behind, sizing
 * scales up (capped) to give the compounding engine enough headroom to reach
 * the target on the remaining trades of the day.
 *
 * PAPER MODE: this tracker is a no-op. Paper sizing is governed by the
 * existing CompoundGrowthMentality / LiveStrategyTuner stack. Operator's
 * ask was explicit that this applies to LIVE wallet growth.
 *
 * DOCTRINE:
 *   • Never a gate. Never blocks a trade. Purely advisory sizing bias.
 *   • Read from LiveStrategyTuner / Executor sizing stacks by multiplying
 *     into the sizeMult product alongside compoundFactor / ddReserveFactor.
 *   • Rate-limited: recomputes at most once per 2000ms per snapshot() call.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object MemeCompoundTarget6256 {

    // Configurable targets. Lower bound 2x, upper bound 5x per operator directive.
    private const val TARGET_LOW_MULT  = 2.0
    private const val TARGET_HIGH_MULT = 5.0

    // Sizing multiplier ladder — never exceeds 2.5× so we don't blow the wallet
    // chasing an unreachable target. Below target: press. At/above: normalise.
    private const val SIZE_MAX          = 2.50
    private const val SIZE_ON_TRACK     = 1.15
    private const val SIZE_AT_TARGET    = 1.00
    private const val SIZE_ABOVE_TARGET = 0.85

    private data class DayAnchor(
        val dayEpochMs: Long,
        val startWalletSol: Double,
    )

    // The rolling UTC-day anchor. Recomputed at midnight UTC or first observation.
    private val anchor = AtomicReference<DayAnchor?>(null)

    @Volatile private var lastSnapshotAtMs: Long = 0L
    @Volatile private var cachedMult: Double = 1.0
    @Volatile private var cachedProgress: Double = 0.0
    @Volatile private var cachedStartSol: Double = 0.0
    @Volatile private var cachedCurrentSol: Double = 0.0

    private fun utcDayEpoch(nowMs: Long): Long = (nowMs / 86_400_000L) * 86_400_000L

    /**
     * Feed the current live wallet SOL balance in. Called by BotService /
     * WalletManager on every wallet refresh (cheap: AtomicReference CAS).
     */
    fun observeLiveWallet(currentLiveSol: Double, nowMs: Long = System.currentTimeMillis()) {
        if (!currentLiveSol.isFinite() || currentLiveSol < 0.0) return
        val today = utcDayEpoch(nowMs)
        val cur = anchor.get()
        if (cur == null || cur.dayEpochMs != today) {
            // Roll to a new UTC day — snapshot the starting balance.
            anchor.compareAndSet(cur, DayAnchor(today, currentLiveSol))
            try { ForensicLogger.lifecycle(
                "MEME_COMPOUND_TARGET_DAY_ROLL_6256",
                "startWalletSol=${"%.4f".format(currentLiveSol)} dayEpochMs=$today"
            ) } catch (_: Throwable) {}
        }
        cachedCurrentSol = currentLiveSol
    }

    /**
     * Advisory size multiplier for a lane. Non-MEME lanes get 1.00×
     * unchanged. MEME-family lanes get the target-based ladder.
     */
    fun sizeAdvisoryFor(lane: String?): Double {
        val laneUp = (lane ?: "").uppercase()
        if (!laneUp.contains("MEME") && !laneUp.contains("SHITCOIN") &&
            !laneUp.contains("MOONSHOT") && !laneUp.contains("PRESALE"))
            return 1.0
        maybeRecompute()
        return cachedMult
    }

    /** For the operational report / dashboard visibility. */
    fun statusLine(): String {
        maybeRecompute()
        return "V5.0.6256_MEME_COMPOUND_TARGET: start=${"%.4f".format(cachedStartSol)} SOL " +
            "current=${"%.4f".format(cachedCurrentSol)} SOL " +
            "progress=${"%.2f".format(cachedProgress)}x " +
            "target=${TARGET_LOW_MULT}x-${TARGET_HIGH_MULT}x " +
            "sizeMult×=${"%.2f".format(cachedMult)}"
    }

    private fun maybeRecompute() {
        val now = System.currentTimeMillis()
        if (now - lastSnapshotAtMs < 2_000L) return
        lastSnapshotAtMs = now
        val a = anchor.get() ?: run {
            cachedMult = 1.0; cachedProgress = 0.0; return
        }
        val start = a.startWalletSol
        val current = cachedCurrentSol
        cachedStartSol = start
        if (start < 0.001) { cachedMult = 1.0; cachedProgress = 0.0; return }
        val progress = current / start
        cachedProgress = progress
        cachedMult = when {
            progress >= TARGET_HIGH_MULT -> SIZE_ABOVE_TARGET
            progress >= TARGET_LOW_MULT  -> SIZE_AT_TARGET
            progress >= 1.30             -> SIZE_ON_TRACK
            progress >= 1.00             -> max(SIZE_ON_TRACK, 1.25)
            else                         -> SIZE_MAX  // wallet below start today → press hardest
        }.coerceAtMost(SIZE_MAX)
    }
}
