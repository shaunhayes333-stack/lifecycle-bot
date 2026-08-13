package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6439 — LOSING STREAK REFLEX.
 *
 * OPERATOR DIRECTIVE:
 *   "Bad behaviour and consistently buying into losers ... should
 *    NEVER be seen or recognised as good behaviour."
 *
 * When the bot rings up MAX_CONSECUTIVE_LOSSES realised losses in a
 * row, LosingStreakReflex6439 SHOUTS at every entry gate to STOP
 * opening new positions for CONSECUTIVE_LOSS_COOLDOWN_MS.
 *
 * shouldBlockNewBuys() → true means every BUY (paper OR live) is
 * denied at the exec gate. Positions already open still exit
 * normally — this reflex only stops NEW ones.
 *
 * The reflex is single-source-of-truth via CapitalPreservationCreed6439
 * so tuning the creed retunes the reflex automatically.
 *
 * onTradeClosed(realizedSolDelta, mint) is the ONLY setter. Callers:
 *   • Executor paper-close paths
 *   • Executor live-close paths (via reconciler)
 *   • MarketsLiveExecutor.close
 *   • V3 exit paths
 * All of them call this once per position close.
 */
object LosingStreakReflex6439 {

    private val consecutiveLosses = AtomicInteger(0)
    private val consecutiveWins = AtomicInteger(0)
    private val cooldownUntilMs = AtomicLong(0L)
    private val totalTrips = AtomicLong(0L)
    private val totalBlocks = AtomicLong(0L)
    @Volatile private var lastLossMint: String = ""

    /**
     * Called once per closed position. `realizedSolDelta` is the wallet
     * SOL delta from open→close (negative = loss). Break-even (delta = 0)
     * counts as a LOSS by the creed ("Break-even is NOT positive").
     */
    fun onTradeClosed(realizedSolDelta: Double, mint: String) {
        val losing = CapitalPreservationCreed6439.isLosingBehaviour(realizedSolDelta)
        if (losing) {
            val n = consecutiveLosses.incrementAndGet()
            consecutiveWins.set(0)
            lastLossMint = mint
            if (n >= CapitalPreservationCreed6439.MAX_CONSECUTIVE_LOSSES) {
                val cooldownEnd = System.currentTimeMillis() +
                    CapitalPreservationCreed6439.CONSECUTIVE_LOSS_COOLDOWN_MS
                cooldownUntilMs.set(cooldownEnd)
                totalTrips.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "LOSING_STREAK_TRIPPED_6439",
                        "consecutiveLosses=$n cooldownMs=${CapitalPreservationCreed6439.CONSECUTIVE_LOSS_COOLDOWN_MS} " +
                            "lastLossMint=${mint.take(12)} lastDeltaSol=${"%.5f".format(realizedSolDelta)}",
                    )
                } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("LOSING_STREAK_TRIPPED_6439") } catch (_: Throwable) {}
            }
        } else {
            consecutiveLosses.set(0)
            consecutiveWins.incrementAndGet()
        }
    }

    /**
     * The ONE gate every buy site must consult. Returns true when the
     * reflex is currently blocking new entries.
     */
    fun shouldBlockNewBuys(): Boolean {
        val until = cooldownUntilMs.get()
        val blocked = until > System.currentTimeMillis()
        if (blocked) {
            totalBlocks.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LOSING_STREAK_BLOCK_6439") } catch (_: Throwable) {}
        }
        return blocked
    }

    /** Remaining cool-down in seconds (0 if not currently cooling down). */
    fun cooldownRemainingSec(): Long {
        val remMs = cooldownUntilMs.get() - System.currentTimeMillis()
        return if (remMs <= 0L) 0L else remMs / 1000L
    }

    fun consecutiveLossesNow(): Int = consecutiveLosses.get()
    fun consecutiveWinsNow(): Int = consecutiveWins.get()

    /** Manual admin reset (used on wallet-day-rollover, session restart). */
    fun reset() {
        consecutiveLosses.set(0)
        consecutiveWins.set(0)
        cooldownUntilMs.set(0L)
        lastLossMint = ""
    }

    fun statusLine(): String =
        "consecLosses=${consecutiveLosses.get()} consecWins=${consecutiveWins.get()} " +
            "cooldownRemSec=${cooldownRemainingSec()} totalTrips=${totalTrips.get()} " +
            "totalBlocks=${totalBlocks.get()} lastLossMint=${lastLossMint.take(12)}"
}
