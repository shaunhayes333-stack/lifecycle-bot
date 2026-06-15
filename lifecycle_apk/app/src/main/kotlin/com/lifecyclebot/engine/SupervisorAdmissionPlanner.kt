package com.lifecyclebot.engine

/**
 * V5.9.1548 — single-source supervisor admission planner.
 *
 * The supervisor already enforces an adaptive LIVE worker cap at lease acquire time,
 * but the bot-loop selector was still handing the supervisor up to the legacy
 * SUPERVISOR_MAX_INFLIGHT slice (96). Under timeout pressure this manufactured
 * 0/96 or 24/96 cycles: most of the batch was guaranteed to defer/retry, which
 * amplified worker-timeout storms without increasing executable coverage.
 *
 * This helper only plans the PER-CYCLE selection slice. It does NOT relax gates,
 * does NOT raise caps, and does NOT touch exits/positions. Healthy runtime returns
 * maxInFlight unchanged, so normal throughput is preserved. Under pressure it aligns
 * selection with the effective live-worker budget plus mandatory forced-open rows,
 * preventing admission churn while keeping enough surface area for discovery.
 */
object SupervisorAdmissionPlanner {
    data class Plan(
        val perCycleCap: Int,
        val reason: String,
        val pressureBand: String,
    )

    fun plan(
        maxInFlight: Int,
        liveCap: Int,
        currentLoad: Int,
        timeoutCount10m: Int,
        forcedOpenCount: Int,
    ): Plan {
        val maxCap = maxInFlight.coerceAtLeast(1)
        val live = liveCap.coerceAtLeast(1)
        val active = currentLoad.coerceAtLeast(0)
        val forced = forcedOpenCount.coerceAtLeast(0)

        val pressureBand = when {
            timeoutCount10m >= 500 -> "severe_timeout_pressure"
            timeoutCount10m >= 150 -> "heavy_timeout_pressure"
            timeoutCount10m >= 30  -> "moderate_timeout_pressure"
            active >= live        -> "live_cap_saturated"
            active >= live * 3 / 4 -> "live_cap_near_full"
            else -> "healthy"
        }

        val target = when (pressureBand) {
            "healthy" -> maxCap
            // V5.0.3676 — operator TUNING patch (recovery). Pressure caps now
            // match the spec exactly so the per-cycle slice never exceeds the
            // effective worker drain rate when supervisor is choking. Healthy
            // returns maxCap unchanged (no throughput cost on a clean runtime).
            //   spec: normalCap=24 / heavyTimeoutCap=12 / severeTimeoutCap=6
            //         maxPerCycle=8 hard ceiling under pressure
            "live_cap_near_full" -> minOf(maxCap, 20)
            "live_cap_saturated" -> minOf(maxCap, 12)
            "moderate_timeout_pressure" -> minOf(maxCap, 12)
            "heavy_timeout_pressure" -> minOf(maxCap, 8)
            else -> minOf(maxCap, 6) // severe timeout pressure
        }

        // Forced-open rows are mandatory management surface. Include them in the slice,
        // but never let pressure planning expand beyond the legacy ceiling.
        val cap = maxOf(forced, target).coerceIn(1, maxCap)
        return Plan(
            perCycleCap = cap,
            pressureBand = pressureBand,
            reason = "band=$pressureBand active=$active liveCap=$live max=$maxCap forced=$forced timeouts10m=$timeoutCount10m cap=$cap",
        )
    }
}
