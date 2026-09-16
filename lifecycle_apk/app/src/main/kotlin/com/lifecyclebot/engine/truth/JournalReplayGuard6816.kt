package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6816 §REPLAY_JOURNAL_PARITY — operator directive V5.0.6813 item #3:
 *   "Replay must not train while openCostΔ != 0. One canonical terminal
 *    mutation → exactly one finalized economic event."
 *
 * Forensic dump (V5.0.6812): closed=226 vs finalized=218. Eight terminal
 * mutations reached the closed authority but never reached the canonical
 * finalized bus — because the replay reconstruction path was publishing
 * synthetic envelopes into the bus while the paper ledger's openCostΔ
 * still had unresolved buys in-flight. This gates that path.
 *
 * DESIGN — surgical, additive, non-crashy:
 *   • A caller entering the historical replay reconstruction path calls
 *     `enterReplay(source)`. Exit via `exitReplay(source)`.
 *   • While replay is active, `shouldSkipPublish(env)` returns true when
 *     the paper ledger's openCostΔ is non-zero (i.e. a real BUY has not
 *     yet settled), which is the exact class of contamination the
 *     operator flagged.
 *   • The guard is *self-defeating* if it faults: any exception in
 *     `shouldSkipPublish` returns false (fail-open). This preserves the
 *     hot-path invariant established after the V5.0.6811 crash trauma —
 *     the guard cannot itself become the source of a runtime fault.
 *
 * Public surface is deliberately tiny so consumers can wire this with a
 * single call site each.
 */
object JournalReplayGuard6816 {

    private const val OPEN_COST_TOLERANCE_SOL = 1e-6

    private val replayActive = AtomicBoolean(false)
    private val depth = AtomicLong(0L)
    private val enterCount = AtomicLong(0L)
    private val exitCount = AtomicLong(0L)
    private val skipCount = AtomicLong(0L)
    private val allowCount = AtomicLong(0L)

    fun isReplay(): Boolean = replayActive.get()

    /** Increment the replay depth. First entry flips the active flag. */
    fun enterReplay(source: String) {
        val next = depth.incrementAndGet()
        enterCount.incrementAndGet()
        replayActive.set(next > 0L)
        try {
            PipelineHealthCollector.labelInc("REPLAY_GUARD_ENTER_6816")
            PipelineHealthCollector.labelInc(
                "REPLAY_GUARD_ENTER_6816_${source.uppercase().take(32)}"
            )
        } catch (_: Throwable) {}
    }

    /** Decrement the replay depth. Zero flips the active flag. */
    fun exitReplay(source: String) {
        val next = depth.updateAndGet { cur -> (cur - 1L).coerceAtLeast(0L) }
        exitCount.incrementAndGet()
        replayActive.set(next > 0L)
        try {
            PipelineHealthCollector.labelInc("REPLAY_GUARD_EXIT_6816")
            PipelineHealthCollector.labelInc(
                "REPLAY_GUARD_EXIT_6816_${source.uppercase().take(32)}"
            )
        } catch (_: Throwable) {}
    }

    /**
     * True when the canonical finalized bus MUST NOT publish this
     * envelope because a real BUY is still in flight (openCostΔ != 0)
     * AND replay is active. Fail-open on any exception.
     */
    fun shouldSkipPublish(tradeId: String, mint: String, mode: String): Boolean {
        return try {
            if (!replayActive.get()) {
                allowCount.incrementAndGet()
                return false
            }
            // Only guard against the paper ledger contamination class the
            // operator identified. LIVE mode has its own reconciler.
            val paperMode = mode.equals("paper", ignoreCase = true) ||
                mode.equals("PAPER", ignoreCase = true)
            if (!paperMode) {
                allowCount.incrementAndGet()
                return false
            }
            val openCost = try {
                PaperAccountLedger6430.openCostBasisSol()
            } catch (_: Throwable) { 0.0 }
            if (kotlin.math.abs(openCost) <= OPEN_COST_TOLERANCE_SOL) {
                allowCount.incrementAndGet()
                return false
            }
            skipCount.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("REPLAY_JOURNAL_CONTAMINATION_SKIP_6816")
                ForensicLogger.lifecycle(
                    "REPLAY_JOURNAL_CONTAMINATION_SKIP_6816",
                    "tradeId=${tradeId.take(24)} mint=${mint.take(10)} mode=$mode " +
                        "openCostΔSol=${"%.6f".format(openCost)} " +
                        "action=skip_finalized_publish_until_openCostDelta_zero",
                )
            } catch (_: Throwable) {}
            true
        } catch (_: Throwable) { false }
    }

    fun statusLine(): String =
        "active=${replayActive.get()} depth=${depth.get()} enter=${enterCount.get()} " +
            "exit=${exitCount.get()} skip=${skipCount.get()} allow=${allowCount.get()}"

    internal fun clearForTest() {
        replayActive.set(false); depth.set(0L)
        enterCount.set(0L); exitCount.set(0L)
        skipCount.set(0L); allowCount.set(0L)
    }
}
