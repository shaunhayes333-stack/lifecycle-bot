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
 * PAPER MODE (V5.0.6304): also active. Uses a paper basis derived from
 * RealizedWalletCompoundingGovernor's clean cumulative PnL so the AI's sizing
 * brain still practises hitting the 2x-5x daily target even without a live
 * wallet feed. Operator: "not aiming for 5x compound daily target".
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
     *
     * V5.0.6304 — SELF-HEAL FROM ZERO ANCHOR. Operator report 2026-07 showed
     * `start=0.0000 SOL current=0.0000 SOL progress=0.00x` because the wallet
     * recon at midnight-UTC latched a start of 0.0 (P2 wallet=0 cold-start
     * bug). Once locked, the anchor never rolled again until the next day.
     * Fix: if the anchor's startWalletSol is effectively zero (<0.001) and a
     * non-zero balance arrives, RE-ANCHOR to today's real starting balance.
     * This is safe — a lane's actual daily target is still computed from a
     * live positive baseline.
     */
    fun observeLiveWallet(currentLiveSol: Double, nowMs: Long = System.currentTimeMillis()) {
        if (!currentLiveSol.isFinite() || currentLiveSol < 0.0) return
        val today = utcDayEpoch(nowMs)
        val cur = anchor.get()
        val needsRoll = cur == null || cur.dayEpochMs != today
        val needsHeal = cur != null && cur.dayEpochMs == today && cur.startWalletSol < 0.001 && currentLiveSol >= 0.001
        if (needsRoll || needsHeal) {
            anchor.compareAndSet(cur, DayAnchor(today, currentLiveSol))
            try {
                val tag = if (needsHeal) "MEME_COMPOUND_TARGET_ZERO_HEAL_6304" else "MEME_COMPOUND_TARGET_DAY_ROLL_6256"
                ForensicLogger.lifecycle(tag, "startWalletSol=${"%.4f".format(currentLiveSol)} dayEpochMs=$today prev=${cur?.startWalletSol ?: -1.0}")
            } catch (_: Throwable) {}
        }
        cachedCurrentSol = currentLiveSol
    }

    /**
     * V5.0.6304 — PAPER MODE FEEDER. Operator directive 2026-07: bot must
     * aim for 2x-5x compound daily target even in paper mode so the AI's
     * sizing brain actually practises hitting the target. Previously the
     * comment claimed "PAPER MODE: no-op" but the bot runs primarily in
     * paper — meaning the whole compound target engine was inert.
     *
     * Paper feed uses the running clean journal PnL as the "current wallet"
     * basis, anchored against whatever the paper balance was at UTC midnight.
     * This lets sizeAdvisoryFor() actually press the meme lanes when paper
     * is behind target, and normalise once the paper journal is 2x+ up.
     */
    fun observePaperBasis(cleanPnlSol: Double, nowMs: Long = System.currentTimeMillis()) {
        if (!cleanPnlSol.isFinite()) return
        val today = utcDayEpoch(nowMs)
        val cur = anchor.get()
        // In paper the "wallet balance" concept is emulated by cumulative clean
        // PnL. Anchor to whatever cleanPnl was at day roll (typically 0 at UTC
        // midnight) then track growth from there.
        val basis = cleanPnlSol.coerceAtLeast(0.0) + 1.0  // +1 so start≠0 and progress math holds
        val needsRoll = cur == null || cur.dayEpochMs != today
        val needsHeal = cur != null && cur.dayEpochMs == today && cur.startWalletSol < 0.001 && basis >= 0.001
        if (needsRoll || needsHeal) {
            anchor.compareAndSet(cur, DayAnchor(today, basis))
            try {
                ForensicLogger.lifecycle(
                    "MEME_COMPOUND_TARGET_PAPER_ROLL_6304",
                    "startBasis=${"%.4f".format(basis)} pnl=${"%.4f".format(cleanPnlSol)} dayEpochMs=$today",
                )
            } catch (_: Throwable) {}
        }
        cachedCurrentSol = basis
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
