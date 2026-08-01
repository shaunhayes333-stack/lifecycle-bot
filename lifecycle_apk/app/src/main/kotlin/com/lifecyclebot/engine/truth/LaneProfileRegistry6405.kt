package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6405 §12 — LANE-SPECIFIC EXIT PROFILES.
 *
 * The bot runs multiple sub-traders (CashGeneration, ShitCoin,
 * Quality, BlueChip, Moonshot). Each lane wants a distinct exit
 * profile (strict-stop, trailing-stop, partial-take multiples).
 * This authority owns the profile registry so lane behaviour is
 * data-driven and unit-testable — no more per-lane branching
 * scattered across Executor.
 */
object LaneProfileRegistry6405 {

    data class ExitProfile(
        val strictStopFractionOfEntry: Double,
        val partialTakeMultiple: Double,
        val trailingStopFractionOfPeak: Double,
        val drawdownExitFractionOfPeak: Double,
    )

    // Sensible defaults per lane. Callers may override via [put].
    private val defaults: Map<String, ExitProfile> = mapOf(
        "TREASURY"  to ExitProfile(0.85, 1.15, 0.95, 0.90),  // very conservative
        "BLUECHIP"  to ExitProfile(0.80, 1.25, 0.90, 0.80),
        "QUALITY"   to ExitProfile(0.75, 1.50, 0.85, 0.70),
        "SHITCOIN"  to ExitProfile(0.65, 2.00, 0.75, 0.50),  // classic meme profile
        "MOONSHOT"  to ExitProfile(0.50, 5.00, 0.60, 0.30),  // wide holdout
    )

    private val overrides = ConcurrentHashMap<String, ExitProfile>()

    fun get(lane: String): ExitProfile =
        overrides[lane] ?: defaults[lane] ?: defaults["QUALITY"]!!

    fun put(lane: String, profile: ExitProfile) {
        overrides[lane] = profile
        try {
            ForensicLogger.lifecycle(
                "LANE_PROFILE_OVERRIDE_6405",
                "lane=$lane strictStop=${profile.strictStopFractionOfEntry} " +
                    "partialTake=${profile.partialTakeMultiple} " +
                    "trailStop=${profile.trailingStopFractionOfPeak} " +
                    "drawdownExit=${profile.drawdownExitFractionOfPeak}",
            )
            PipelineHealthCollector.labelInc("LANE_PROFILE_OVERRIDE_6405")
        } catch (_: Throwable) {}
    }

    fun lanes(): Set<String> = defaults.keys + overrides.keys

    internal fun clearForTest() { overrides.clear() }
}
