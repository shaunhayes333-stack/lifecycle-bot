package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6411 §21 — SAFE CANARY MODE.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "After installation, automatically enter live canary mode.
 *  Maximum concurrent new live buys: 1.
 *  Entry size: minimum configured live size.
 *  Required successful confirmations: 2.
 *  After two reconciled successes: restore normal concurrency gradually."
 *
 * DESIGN
 * ──────
 *   • Process-local — resets on install / update / restart.
 *   • State stored in memory only; the on-disk source of truth is
 *     the canonical trade journal (TRADEJRNL_REC counter).
 *   • Ramp ladder: 1 → 2 → normal.
 *   • Any invariant failure (decimal skew, wallet mismatch, unknown
 *     submission) forces the canary to STAY armed until manually
 *     cleared or two more clean confirmations arrive.
 *
 * Consulted by the buy path just before RPC send. When canary
 * requires size reduction, callers should coerce their intent to
 * `LiveSizingProfile.MIN_ENTRY_SOL`.
 */
object LiveCanaryMode6411 {

    enum class Phase { CANARY_ACTIVE, RAMP_2, NORMAL }

    private const val REQUIRED_CLEAN_CONFIRMS = 2

    private val phase = AtomicReference(Phase.CANARY_ACTIVE)
    private val cleanConfirms = AtomicInteger(0)
    private val canaryFailures = AtomicInteger(0)

    fun currentPhase(): Phase = phase.get()

    /** Maximum concurrent live BUYs allowed in the current phase. */
    fun maxConcurrentLiveBuys(): Int = when (phase.get()) {
        Phase.CANARY_ACTIVE -> 1
        Phase.RAMP_2 -> 2
        Phase.NORMAL -> Int.MAX_VALUE
    }

    /** True when the size should be forced to the minimum canary entry. */
    fun forceMinSize(): Boolean = phase.get() == Phase.CANARY_ACTIVE

    /** Record a clean, fully reconciled confirmation. Ramps the phase up. */
    fun recordCleanConfirm(mint: String, symbol: String) {
        val n = cleanConfirms.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "CANARY_CONFIRM_6411",
                "mint=${mint.take(10)} sym=$symbol cleanConfirms=$n phase=${phase.get()}",
            )
            PipelineHealthCollector.labelInc("CANARY_CONFIRM_6411")
        } catch (_: Throwable) {}
        if (n >= REQUIRED_CLEAN_CONFIRMS && phase.get() == Phase.CANARY_ACTIVE) {
            phase.compareAndSet(Phase.CANARY_ACTIVE, Phase.RAMP_2)
            try {
                ForensicLogger.lifecycle(
                    "CANARY_RAMP_6411",
                    "from=CANARY_ACTIVE to=RAMP_2 cleanConfirms=$n",
                )
                PipelineHealthCollector.labelInc("CANARY_RAMP_6411")
            } catch (_: Throwable) {}
        } else if (n >= REQUIRED_CLEAN_CONFIRMS * 2 && phase.get() == Phase.RAMP_2) {
            phase.compareAndSet(Phase.RAMP_2, Phase.NORMAL)
            try {
                ForensicLogger.lifecycle(
                    "CANARY_RAMP_6411",
                    "from=RAMP_2 to=NORMAL cleanConfirms=$n",
                )
                PipelineHealthCollector.labelInc("CANARY_RAMP_6411")
            } catch (_: Throwable) {}
        }
    }

    /** Record an invariant failure (decimal skew, wallet mismatch, unknown submission, etc). */
    fun recordInvariantFailure(reason: String, mint: String, symbol: String) {
        val n = canaryFailures.incrementAndGet()
        val prior = phase.getAndSet(Phase.CANARY_ACTIVE)
        cleanConfirms.set(0)
        try {
            ForensicLogger.lifecycle(
                "CANARY_INVARIANT_FAIL_6411",
                "mint=${mint.take(10)} sym=$symbol reason=$reason failures=$n from=$prior to=CANARY_ACTIVE",
            )
            PipelineHealthCollector.labelInc("CANARY_INVARIANT_FAIL_6411")
        } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "phase=${phase.get()} cleanConfirms=${cleanConfirms.get()} failures=${canaryFailures.get()} " +
            "maxConcurrent=${maxConcurrentLiveBuys()} forceMinSize=${forceMinSize()}"

    internal fun resetForTest() {
        phase.set(Phase.CANARY_ACTIVE)
        cleanConfirms.set(0)
        canaryFailures.set(0)
    }
}
