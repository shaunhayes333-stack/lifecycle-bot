package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.ConfigStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6218 — LIVE-MODE AUTO-PAUSE GUARD.
 *
 * Operator directive (2026-07-09):
 *   "I still cant understand with the amount of intelligence data and tech
 *    we have how its even possible for the bot to be losing money at all
 *    trading live!!!!"
 *
 *   Config: 1a + 2c + 3a — fully automatic pause+resume, 10-cycle trigger,
 *   PAPER-flip only (do NOT touch lane config).
 *
 * Logic:
 *   • PAUSE trigger (LIVE → PAPER):
 *       cleanLive rolling WR (last 30 closes) < 20 %
 *       AND minimum sample of 20 live closes reached
 *       AND WR has stayed sub-20 % for 10 consecutive evaluation ticks
 *       (each tick fires once per bot-loop cycle at most every 30 s).
 *
 *   • AUTO-RESUME trigger (PAPER → LIVE):
 *       Same guard was the one that flipped PAPER on (operator manual PAPER
 *       toggles are respected — we only auto-resume if we auto-paused it).
 *       Recent paper WR (last 30 closes) ≥ 25 %  AND sample ≥ 20
 *       AND has stayed at ≥25 % for 10 consecutive evaluation ticks.
 *
 * Doctrine:
 *   • Never touches lane config or quarantines. Only flips paperMode.
 *   • Respects manual override: if the operator flipped to PAPER themselves
 *     (or flipped back to LIVE while we were auto-paused), the guard resets
 *     and stops trying to resume. We only ever manage flags we own.
 *   • Fail-open: any exception is swallowed; nothing gets flipped by error.
 *   • Evaluates at most every 30 s, tuned to align with LaneAutoPauseGuard.
 *   • Config is persisted via ConfigStore.save so the flip survives reboots.
 */
object LiveModeAutoPauseGuard {
    const val VERSION = "V5.0.6218_LIVE_MODE_AUTO_PAUSE"

    // Pause trigger
    private const val WR_WINDOW = 30
    private const val PAUSE_WR_PCT = 20.0
    private const val PAUSE_MIN_SAMPLE = 20
    private const val PAUSE_CONSECUTIVE_TICKS = 10

    // Resume trigger
    private const val RESUME_WR_PCT = 25.0
    private const val RESUME_MIN_SAMPLE = 20
    private const val RESUME_CONSECUTIVE_TICKS = 10

    // Evaluation cadence
    private const val EVAL_INTERVAL_MS = 30_000L

    // Win threshold matches LaneAutoPauseGuard: pnlPct >= 5% counts as a win.
    private const val WIN_PCT = 5.0

    private val lastEvalMs = AtomicLong(0L)
    private val pauseStreak = AtomicInteger(0)  // consecutive ticks WR<20% (live mode)
    private val resumeStreak = AtomicInteger(0) // consecutive ticks WR>=25% (paper mode, we-owned)
    @Volatile private var weOwnCurrentPauseFlip = false
    @Volatile private var lastFlipAtMs = 0L

    fun evaluate(ctx: Context) {
        val now = System.currentTimeMillis()
        val last = lastEvalMs.get()
        if (now - last < EVAL_INTERVAL_MS) return
        if (!lastEvalMs.compareAndSet(last, now)) return
        try {
            val cfg = try { com.lifecyclebot.data.ConfigStore.load(ctx) } catch (_: Throwable) { return }
            val currentlyPaper = cfg.paperMode
            val clean = try {
                TradeHistoryStore.getRecentCleanStrategyTerminalTrades(limit = 500)
            } catch (_: Throwable) { emptyList() }
            if (clean.isEmpty()) return

            if (!currentlyPaper) {
                // Live mode: watch cleanLive WR — pause if sub-20% for 10 ticks.
                val liveRows = clean.filter { it.mode.equals("live", ignoreCase = true) }
                if (liveRows.size < PAUSE_MIN_SAMPLE) {
                    pauseStreak.set(0)
                    return
                }
                val window = liveRows.takeLast(WR_WINDOW)
                val wins = window.count { it.pnlPct >= WIN_PCT }
                val wrPct = wins.toDouble() / window.size.toDouble() * 100.0
                if (wrPct < PAUSE_WR_PCT) {
                    val streak = pauseStreak.incrementAndGet()
                    try {
                        ForensicLogger.lifecycle(
                            "LIVE_MODE_AUTO_PAUSE_ARMING_6218",
                            "cleanLiveWR=${"%.1f".format(wrPct)} n=${window.size} streak=$streak/$PAUSE_CONSECUTIVE_TICKS threshold=${PAUSE_WR_PCT}",
                        )
                        PipelineHealthCollector.labelInc("LIVE_MODE_AUTO_PAUSE_ARMING_6218")
                    } catch (_: Throwable) {}
                    if (streak >= PAUSE_CONSECUTIVE_TICKS) {
                        flipToPaper(ctx, cfg, wrPct, window.size)
                    }
                } else {
                    if (pauseStreak.getAndSet(0) > 0) {
                        try {
                            ForensicLogger.lifecycle(
                                "LIVE_MODE_AUTO_PAUSE_ARMING_DISARMED_6218",
                                "cleanLiveWR=${"%.1f".format(wrPct)} n=${window.size} threshold=${PAUSE_WR_PCT} reason=wr_recovered",
                            )
                        } catch (_: Throwable) {}
                    }
                }
                // If a manual operator toggle put us back on LIVE while we
                // owned a pause, the flip authority is now theirs — clear.
                if (weOwnCurrentPauseFlip) {
                    weOwnCurrentPauseFlip = false
                    resumeStreak.set(0)
                }
            } else {
                // Paper mode: only auto-resume if WE flipped it. Operator
                // manual PAPER toggles are respected until they manually
                // return to LIVE themselves.
                if (!weOwnCurrentPauseFlip) {
                    resumeStreak.set(0)
                    return
                }
                val paperRows = clean.filter { it.mode.equals("paper", ignoreCase = true) }
                if (paperRows.size < RESUME_MIN_SAMPLE) {
                    resumeStreak.set(0)
                    return
                }
                // Only consider trades AFTER the auto-pause flip fired —
                // stale historical paper wins must not force a premature
                // resume onto a wallet the operator hasn't seen recover.
                val postFlip = paperRows.filter { it.ts >= lastFlipAtMs }
                if (postFlip.size < RESUME_MIN_SAMPLE) {
                    try {
                        ForensicLogger.lifecycle(
                            "LIVE_MODE_AUTO_RESUME_AWAITING_SAMPLE_6218",
                            "postFlipPaperN=${postFlip.size} min=$RESUME_MIN_SAMPLE flipAt=$lastFlipAtMs",
                        )
                    } catch (_: Throwable) {}
                    return
                }
                val window = postFlip.takeLast(WR_WINDOW)
                val wins = window.count { it.pnlPct >= WIN_PCT }
                val wrPct = wins.toDouble() / window.size.toDouble() * 100.0
                if (wrPct >= RESUME_WR_PCT) {
                    val streak = resumeStreak.incrementAndGet()
                    try {
                        ForensicLogger.lifecycle(
                            "LIVE_MODE_AUTO_RESUME_ARMING_6218",
                            "postFlipPaperWR=${"%.1f".format(wrPct)} n=${window.size} streak=$streak/$RESUME_CONSECUTIVE_TICKS threshold=${RESUME_WR_PCT}",
                        )
                        PipelineHealthCollector.labelInc("LIVE_MODE_AUTO_RESUME_ARMING_6218")
                    } catch (_: Throwable) {}
                    if (streak >= RESUME_CONSECUTIVE_TICKS) {
                        flipToLive(ctx, cfg, wrPct, window.size)
                    }
                } else {
                    if (resumeStreak.getAndSet(0) > 0) {
                        try {
                            ForensicLogger.lifecycle(
                                "LIVE_MODE_AUTO_RESUME_ARMING_DISARMED_6218",
                                "postFlipPaperWR=${"%.1f".format(wrPct)} n=${window.size} threshold=${RESUME_WR_PCT} reason=wr_dropped",
                            )
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {
            // Fail-open: never flip on error.
        }
    }

    private fun flipToPaper(ctx: Context, cfg: BotConfig, wrPct: Double, n: Int) {
        try {
            ConfigStore.save(ctx, cfg.copy(paperMode = true))
            weOwnCurrentPauseFlip = true
            lastFlipAtMs = System.currentTimeMillis()
            pauseStreak.set(0)
            resumeStreak.set(0)
            try {
                ForensicLogger.lifecycle(
                    "LIVE_MODE_AUTO_PAUSED_TO_PAPER_6218",
                    "cleanLiveWR=${"%.1f".format(wrPct)} n=$n threshold=${PAUSE_WR_PCT} consecutive=${PAUSE_CONSECUTIVE_TICKS} reason=protect_wallet",
                )
                PipelineHealthCollector.labelInc("LIVE_MODE_AUTO_PAUSED_TO_PAPER_6218")
                ErrorLogger.warn("LiveModeAutoPauseGuard", "$VERSION AUTO-PAUSED LIVE→PAPER: cleanLiveWR=${"%.1f".format(wrPct)}%% n=$n after $PAUSE_CONSECUTIVE_TICKS consecutive sub-${PAUSE_WR_PCT}%% ticks")
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            try { ErrorLogger.warn("LiveModeAutoPauseGuard", "$VERSION flipToPaper failed: ${t.message}") } catch (_: Throwable) {}
        }
    }

    private fun flipToLive(ctx: Context, cfg: BotConfig, wrPct: Double, n: Int) {
        try {
            ConfigStore.save(ctx, cfg.copy(paperMode = false))
            weOwnCurrentPauseFlip = false
            pauseStreak.set(0)
            resumeStreak.set(0)
            try {
                ForensicLogger.lifecycle(
                    "LIVE_MODE_AUTO_RESUMED_TO_LIVE_6218",
                    "postFlipPaperWR=${"%.1f".format(wrPct)} n=$n threshold=${RESUME_WR_PCT} consecutive=${RESUME_CONSECUTIVE_TICKS} reason=paper_edge_recovered",
                )
                PipelineHealthCollector.labelInc("LIVE_MODE_AUTO_RESUMED_TO_LIVE_6218")
                ErrorLogger.info("LiveModeAutoPauseGuard", "$VERSION AUTO-RESUMED PAPER→LIVE: postFlipPaperWR=${"%.1f".format(wrPct)}%% n=$n after $RESUME_CONSECUTIVE_TICKS consecutive >=${RESUME_WR_PCT}%% ticks")
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            try { ErrorLogger.warn("LiveModeAutoPauseGuard", "$VERSION flipToLive failed: ${t.message}") } catch (_: Throwable) {}
        }
    }
}
