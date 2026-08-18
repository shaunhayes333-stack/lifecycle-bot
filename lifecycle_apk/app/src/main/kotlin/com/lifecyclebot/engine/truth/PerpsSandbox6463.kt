package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6463 §P1 — SOL PERPS SANDBOX (paper-only leverage toggle).
 *
 * OPERATOR MANDATE (Feb 2026):
 *   "SOL Perps Sandbox: Wire the paper-only leverage toggle and lock
 *    down the risk-exit clock semantics for perps positions"
 *
 * SCOPE (Phase 1)
 * ────────────────
 * This is the SANDBOX substrate only. It ships:
 *   - a global paper-only ENABLE latch (default: OFF)
 *   - a per-position leverage record (mint / positionId / leverageX)
 *   - a risk-exit clock semantic that treats perps stops at
 *     leverage-scaled thresholds (a 5x position at −20% underlying
 *     is a −100% margin call and MUST liquidate immediately)
 *   - hard live-mode gate: any live path calling `open()` is refused.
 *
 * NO LIVE EXECUTION. NO REAL PERPS ROUTE. NO ORDERBOOK WIRING.
 * The purpose is to lock the risk-exit invariants and let
 * `CanonicalRiskClock6454.evaluate` interrogate a per-position
 * leverage snapshot so future perps modes cannot bypass the stop.
 *
 * INVARIANTS
 * ──────────
 *  I1  Live mode always sees leverage=1.0 (spot). openLeveragedPaper()
 *      returns false unconditionally when paperMode=false.
 *  I2  Any leverage ≥ 2.0 forces an EFFECTIVE stop at
 *      `underlyingDropPct * leverage ≥ MARGIN_CALL_PCT` (80%).
 *  I3  Every perps position must have a canonical positionId; blank
 *      positionIds are refused and logged.
 *  I4  On close(), all state is purged so a re-opened mint gets a
 *      fresh leverage record.
 *  I5  `evaluateRiskExit(positionId, underlyingDropPct)` is idempotent
 *      and returns EXIT_LIQUIDATION when the effective drawdown
 *      exceeds MARGIN_CALL_PCT.
 */
object PerpsSandbox6463 {

    private const val MAX_LEVERAGE = 10.0
    private const val MARGIN_CALL_PCT = 80.0   // %; leverage-scaled

    enum class OpenResult { OPENED, REFUSED_LIVE_MODE, REFUSED_BLANK_POSITION, REFUSED_LEVERAGE_BOUNDS, DISABLED }
    enum class RiskExitVerdict { NO_ACTION, WARNING_MARGIN, EXIT_LIQUIDATION, UNKNOWN_POSITION }

    data class Position(
        val positionId: String,
        val mint: String,
        val leverageX: Double,
        val openedAtMs: Long,
        val entryPx: Double,
    )

    private val enabled = AtomicBoolean(false)
    private val positions = ConcurrentHashMap<String, Position>()

    // Telemetry
    private val opens = AtomicLong(0L)
    private val refuses = AtomicLong(0L)
    private val liquidations = AtomicLong(0L)
    private val marginWarnings = AtomicLong(0L)
    private val lastToggleAtMs = AtomicReference<Long>(0L)

    // ─── Toggle ───────────────────────────────────────────────────────────

    /**
     * Enable/disable the sandbox. Only takes effect in paper mode.
     * Passing paperMode=false is refused (logged) — perps stay off.
     */
    fun setEnabled(desired: Boolean, paperMode: Boolean) {
        if (!paperMode && desired) {
            try {
                ForensicLogger.lifecycle(
                    "PERPS_SANDBOX_ENABLE_REFUSED_LIVE_6463",
                    "desired=$desired paperMode=$paperMode — perps sandbox is paper-only",
                )
                PipelineHealthCollector.labelInc("PERPS_SANDBOX_ENABLE_REFUSED_LIVE_6463")
            } catch (_: Throwable) {}
            return
        }
        val was = enabled.getAndSet(desired)
        lastToggleAtMs.set(System.currentTimeMillis())
        if (was != desired) {
            try {
                ForensicLogger.lifecycle(
                    "PERPS_SANDBOX_TOGGLE_6463",
                    "was=$was now=$desired paperMode=$paperMode",
                )
                PipelineHealthCollector.labelInc("PERPS_SANDBOX_TOGGLE_6463")
            } catch (_: Throwable) {}
        }
    }

    fun isEnabled(): Boolean = enabled.get()

    // ─── Open / Close ─────────────────────────────────────────────────────

    fun openLeveragedPaper(
        positionId: String,
        mint: String,
        leverageX: Double,
        entryPx: Double,
        paperMode: Boolean,
    ): OpenResult {
        if (!paperMode) { refuses.incrementAndGet(); return OpenResult.REFUSED_LIVE_MODE }
        if (!enabled.get()) return OpenResult.DISABLED
        if (positionId.isBlank()) {
            refuses.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "PERPS_SANDBOX_OPEN_BLANK_POSITION_6463",
                    "mint=${mint.take(10)} leverageX=$leverageX",
                )
                PipelineHealthCollector.labelInc("PERPS_SANDBOX_OPEN_BLANK_POSITION_6463")
            } catch (_: Throwable) {}
            return OpenResult.REFUSED_BLANK_POSITION
        }
        if (!leverageX.isFinite() || leverageX < 1.0 || leverageX > MAX_LEVERAGE) {
            refuses.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "PERPS_SANDBOX_OPEN_LEVERAGE_BOUNDS_6463",
                    "positionId=${positionId.take(16)} leverageX=$leverageX max=$MAX_LEVERAGE",
                )
                PipelineHealthCollector.labelInc("PERPS_SANDBOX_OPEN_LEVERAGE_BOUNDS_6463")
            } catch (_: Throwable) {}
            return OpenResult.REFUSED_LEVERAGE_BOUNDS
        }
        positions[positionId] = Position(
            positionId = positionId, mint = mint, leverageX = leverageX,
            openedAtMs = System.currentTimeMillis(), entryPx = entryPx,
        )
        opens.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "PERPS_SANDBOX_OPENED_6463",
                "positionId=${positionId.take(16)} mint=${mint.take(10)} leverageX=$leverageX entryPx=${"%.6f".format(entryPx)}",
            )
            PipelineHealthCollector.labelInc("PERPS_SANDBOX_OPENED_6463")
        } catch (_: Throwable) {}
        return OpenResult.OPENED
    }

    fun close(positionId: String, reason: String) {
        val removed = positions.remove(positionId) ?: return
        try {
            ForensicLogger.lifecycle(
                "PERPS_SANDBOX_CLOSED_6463",
                "positionId=${positionId.take(16)} mint=${removed.mint.take(10)} leverageX=${removed.leverageX} reason=${reason.take(40)}",
            )
            PipelineHealthCollector.labelInc("PERPS_SANDBOX_CLOSED_6463")
        } catch (_: Throwable) {}
    }

    fun leverageOf(positionId: String): Double =
        positions[positionId]?.leverageX ?: 1.0

    // ─── Risk-exit clock semantic ─────────────────────────────────────────

    /**
     * Given the underlying spot drawdown (positive %; e.g. 15.0 means
     * price dropped 15% from entry), compute the leveraged equity
     * drawdown and return the risk-exit verdict.
     *
     * EXIT_LIQUIDATION fires when `effectivePct >= MARGIN_CALL_PCT`.
     * The clock caller MUST honour this immediately — no partials,
     * no confirmations. This is the same authority CanonicalRiskClock6454
     * uses for spot stop-losses.
     */
    fun evaluateRiskExit(positionId: String, underlyingDropPct: Double): RiskExitVerdict {
        if (positionId.isBlank()) return RiskExitVerdict.UNKNOWN_POSITION
        val pos = positions[positionId] ?: return RiskExitVerdict.UNKNOWN_POSITION
        if (!underlyingDropPct.isFinite() || underlyingDropPct < 0.0) return RiskExitVerdict.NO_ACTION
        val effectivePct = underlyingDropPct * pos.leverageX
        return when {
            effectivePct >= MARGIN_CALL_PCT -> {
                liquidations.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "PERPS_SANDBOX_LIQUIDATION_6463",
                        "positionId=${positionId.take(16)} mint=${pos.mint.take(10)} leverageX=${pos.leverageX} " +
                            "underlyingDropPct=${"%.2f".format(underlyingDropPct)} effectivePct=${"%.2f".format(effectivePct)} threshold=$MARGIN_CALL_PCT",
                    )
                    PipelineHealthCollector.labelInc("PERPS_SANDBOX_LIQUIDATION_6463")
                } catch (_: Throwable) {}
                RiskExitVerdict.EXIT_LIQUIDATION
            }
            effectivePct >= (MARGIN_CALL_PCT * 0.75) -> {
                marginWarnings.incrementAndGet()
                RiskExitVerdict.WARNING_MARGIN
            }
            else -> RiskExitVerdict.NO_ACTION
        }
    }

    fun statusLine(): String =
        "enabled=${enabled.get()} positions=${positions.size} opens=${opens.get()} refuses=${refuses.get()} " +
            "liquidations=${liquidations.get()} marginWarnings=${marginWarnings.get()} " +
            "maxLeverage=$MAX_LEVERAGE marginCallPct=$MARGIN_CALL_PCT"

    internal fun resetForTest() {
        enabled.set(false); positions.clear()
        opens.set(0L); refuses.set(0L); liquidations.set(0L); marginWarnings.set(0L)
    }
}
