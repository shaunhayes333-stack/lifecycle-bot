package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6829 §INVENTORY_PRESSURE — operator diagnosis item #4 for build 5.0.6828:
 *   "cash: 4.0171 SOL, open market value: 18.8414 SOL, 67 canonical active
 *    positions. Slot forced=59, exitPending=true. Generating
 *    EXEC_DEFERRED_SLOT_HEALTH=87, MEME_TURNOVER_PRESSURE_DEFER=87,
 *    ORDER_SIZE_BLOCKED_EXIT_THROUGHPUT=156. Classic churn/inventory
 *    imbalance — gross execution rate excessive yet individual candidates
 *    throttled because inventory isn't recycling fast enough."
 *
 * DESIGN — read the current open-position count and expose a
 * PRESSURE_LEVEL that consumers use to tighten intake, without
 * hard-choking any single lane.
 *   • setOpenPositions(n) called from the position authority on any
 *     open/close event.
 *   • pressureLevel(): 0..3 (NONE / MILD / HIGH / CRITICAL)
 *   • intakeMultiplier(): size damper 1.0 down to 0.20 as pressure rises
 *   • scoreFloorDelta(): +0 / +3 / +7 / +12 as pressure rises
 *   • blockNewIntake(): true only at CRITICAL — used as a last-resort
 *     read for callers that already have the pressure telemetry.
 *   • Thresholds tuned to the operator's dump: 67 open positions at
 *     4 SOL cash was the "should have been deferring 30 positions
 *     ago" state.
 */
object InventoryPressureGovernor6829 {

    // Configurable open-position thresholds
    private const val MILD_OPEN = 25
    private const val HIGH_OPEN = 40
    private const val CRIT_OPEN = 55

    enum class Pressure { NONE, MILD, HIGH, CRITICAL }

    private val openPositions = AtomicInteger(0)
    private val updates = AtomicLong(0L)
    private val queries = AtomicLong(0L)

    fun setOpenPositions(n: Int) {
        openPositions.set(n.coerceAtLeast(0))
        updates.incrementAndGet()
    }

    fun openPositions(): Int = openPositions.get()

    fun pressureLevel(): Pressure {
        queries.incrementAndGet()
        val n = openPositions.get()
        return when {
            n >= CRIT_OPEN -> Pressure.CRITICAL
            n >= HIGH_OPEN -> Pressure.HIGH
            n >= MILD_OPEN -> Pressure.MILD
            else -> Pressure.NONE
        }
    }

    fun intakeMultiplier(): Double {
        val p = pressureLevel()
        return when (p) {
            Pressure.NONE -> 1.0
            Pressure.MILD -> 0.75
            Pressure.HIGH -> 0.50
            Pressure.CRITICAL -> 0.20
        }.also {
            if (p != Pressure.NONE) {
                try {
                    PipelineHealthCollector.labelInc("INVENTORY_PRESSURE_INTAKE_MULT_APPLIED_6829")
                    PipelineHealthCollector.labelInc("INVENTORY_PRESSURE_6829_${p.name}")
                } catch (_: Throwable) {}
            }
        }
    }

    fun scoreFloorDelta(): Double {
        val p = pressureLevel()
        return when (p) {
            Pressure.NONE -> 0.0
            Pressure.MILD -> 3.0
            Pressure.HIGH -> 7.0
            Pressure.CRITICAL -> 12.0
        }
    }

    fun blockNewIntake(): Boolean {
        val p = pressureLevel()
        if (p == Pressure.CRITICAL) {
            try {
                PipelineHealthCollector.labelInc("INVENTORY_PRESSURE_INTAKE_BLOCKED_6829")
                ForensicLogger.lifecycle(
                    "INVENTORY_PRESSURE_INTAKE_BLOCKED_6829",
                    "openPositions=${openPositions.get()} threshold=$CRIT_OPEN " +
                        "action=defer_new_intake_until_exit_recycles_capital",
                )
            } catch (_: Throwable) {}
            return true
        }
        return false
    }

    fun statusLine(): String =
        "openPositions=${openPositions.get()} pressure=${pressureLevel()} " +
            "intakeMult=${"%.2f".format(intakeMultiplier())} " +
            "scoreFloorDelta=${"%.1f".format(scoreFloorDelta())} " +
            "updates=${updates.get()} queries=${queries.get()}"

    internal fun clearForTest() {
        openPositions.set(0); updates.set(0L); queries.set(0L)
    }
}
