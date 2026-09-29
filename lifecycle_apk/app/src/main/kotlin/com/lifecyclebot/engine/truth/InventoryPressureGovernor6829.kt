package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
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
 * DESIGN — read canonical mode-local open positions at each pressure
 * decision. Forensic all-mode counts must never become LIVE pressure.
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

    // V5.0.6833 §INVENTORY_PRESSURE_STAIRCASE — operator directive Feb 2026:
    //   ~35 begin progressive throttling
    //   ~45 halve weak-entry admissions
    //   ~55 HIGH_EDGE only
    //   >=70 exceptional entries only
    // These replace the earlier 25/40/55 tiers. Downstream consumers keep
    // the same call surface (intakeMultiplier / scoreFloorDelta /
    // blockNewIntake) so no wiring changes are required.
    private const val MILD_OPEN = 35        // was 25
    private const val HIGH_OPEN = 45        // was 40 (halve weak entries)
    private const val CRIT_OPEN = 55        // HIGH_EDGE only
    private const val EXCEPT_OPEN_6833 = 70 // exceptional entries only

    enum class Pressure { NONE, MILD, HIGH, CRITICAL, EXCEPTIONAL_6833 }

    private val queries = AtomicLong(0L)

    // V5.0.7432: pressure is admission authority, never a cached all-mode
    // projection. A status-line read of the forensic union must not change it.
    fun openPositions(mode: String): Int {
        val m = mode.trim().uppercase()
        require(m == "LIVE" || m == "PAPER") { "INVENTORY_MODE_REQUIRED_7432 mode=$mode" }
        val open = CanonicalPositionAuthority6441.openPositions()
        val n = open.count { it.mode.equals(m, true) }
        queries.incrementAndGet()
        if (m == "LIVE") try {
            PipelineHealthCollector.labelInc("LIVE_INVENTORY_AUTH_READ_7432")
            PipelineHealthCollector.labelInc("LIVE_INVENTORY_PRESSURE_SOURCE_7432")
            if (open.size > n) {
                PipelineHealthCollector.labelInc("LIVE_INVENTORY_PAPER_ROWS_EXCLUDED_7432")
                PipelineHealthCollector.labelInc("LIVE_INVENTORY_ALLMODE_AUTHORITY_REFUSED_7432")
                PipelineHealthCollector.labelInc("LIVE_INVENTORY_AUTH_DIVERGENCE_7432")
            }
        } catch (_: Throwable) {}
        return n
    }

    fun pressureLevel(mode: String): Pressure {
        val n = openPositions(mode)
        return when {
            n >= EXCEPT_OPEN_6833 -> Pressure.EXCEPTIONAL_6833
            n >= CRIT_OPEN -> Pressure.CRITICAL
            n >= HIGH_OPEN -> Pressure.HIGH
            n >= MILD_OPEN -> Pressure.MILD
            else -> Pressure.NONE
        }
    }

    fun intakeMultiplier(mode: String): Double {
        val p = pressureLevel(mode)
        return when (p) {
            Pressure.NONE -> 1.0
            Pressure.MILD -> 0.75              // 35: begin throttle
            Pressure.HIGH -> 0.50              // 45: halve weak entries
            Pressure.CRITICAL -> 0.25          // 55: HIGH_EDGE only (rest damped hard)
            Pressure.EXCEPTIONAL_6833 -> 0.15  // 70: exceptional only
        }.also {
            if (p != Pressure.NONE) {
                try {
                    PipelineHealthCollector.labelInc("INVENTORY_PRESSURE_INTAKE_MULT_APPLIED_6829")
                    PipelineHealthCollector.labelInc("INVENTORY_PRESSURE_6829_${p.name}")
                } catch (_: Throwable) {}
            }
        }
    }

    fun scoreFloorDelta(mode: String): Double {
        val p = pressureLevel(mode)
        return when (p) {
            Pressure.NONE -> 0.0
            Pressure.MILD -> 3.0
            Pressure.HIGH -> 7.0
            Pressure.CRITICAL -> 12.0
            Pressure.EXCEPTIONAL_6833 -> 18.0
        }
    }

    fun blockNewIntake(mode: String): Boolean {
        val p = pressureLevel(mode)
        // V5.0.6833 §STAIRCASE — hard block only lifted to EXCEPTIONAL
        // tier (>=70 opens). CRITICAL (>=55) is HIGH_EDGE-only, so it
        // remains an admission filter, not an intake block. The
        // OrderSizeResolver / FDG consumers query
        // RuntimeTune6833.isHighEdge() at CRITICAL and
        // RuntimeTune6833.isExceptionalEdge() at EXCEPTIONAL to decide
        // per-candidate; keep this call the last-resort veto.
        if (p == Pressure.EXCEPTIONAL_6833) {
            try {
                PipelineHealthCollector.labelInc("INVENTORY_PRESSURE_INTAKE_BLOCKED_6829")
                ForensicLogger.lifecycle(
                    "INVENTORY_PRESSURE_INTAKE_BLOCKED_6829",
                    "mode=$mode openPositions=${openPositions(mode)} threshold=$EXCEPT_OPEN_6833 " +
                        "action=defer_new_intake_until_exit_recycles_capital",
                )
            } catch (_: Throwable) {}
            return true
        }
        return false
    }

    fun statusLine(): String =
        "liveCanonicalOpen=${openPositions("LIVE")} paperCanonicalOpen=${openPositions("PAPER")} " +
            "livePressure=${pressureLevel("LIVE")} paperPressure=${pressureLevel("PAPER")} " +
            "queries=${queries.get()}"

    internal fun clearForTest() { queries.set(0L) }
}
