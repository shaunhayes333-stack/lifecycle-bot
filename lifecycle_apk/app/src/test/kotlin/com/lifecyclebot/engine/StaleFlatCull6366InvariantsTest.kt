package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6366b — STALE_FLAT_CULL_6366 invariants.
 *
 * Operator directive (verbatim): "if they aren't either good hold potential or
 * green tokens they should be sold."
 *
 * Rule wired inside HoldingLogicLayer.evaluatePosition between the max-hold
 * exit and the isTooEarly check. Fires only when ALL of:
 *   - age >= 15 min
 *   - pnlPct in [-3, +3] (flat)
 *   - not near target (< target × 0.5) — never cull a runner
 *   - meta.momScore < 20 && meta.volScore < 15 (weak momentum)
 *   - whaleSummary blank && velocityScore < 70 (no whale bid)
 *   - holderGrowthRate < 5.0 (no holder growth)
 *   - mode not in {DIAMOND_HANDS, LONG_HOLD, SLEEPER} and not position.isLongHold
 */
class StaleFlatCull6366InvariantsTest {

    private val src: String by lazy {
        File("src/main/kotlin/com/lifecyclebot/engine/HoldingLogicLayer.kt").readText()
    }

    @Test
    fun cull_rule_is_wired_in_evaluatePosition() {
        assertTrue("STALE_FLAT_CULL_6366 must be emitted", src.contains("STALE_FLAT_CULL_6366"))
        assertTrue("Rule must fire an EXIT_NOW", src.contains("HoldAction.EXIT_NOW"))
    }

    @Test
    fun cull_never_touches_patient_modes() {
        assertTrue(
            "Cull must skip DIAMOND_HANDS / LONG_HOLD / SLEEPER — those modes exist to sit flat waiting.",
            src.contains("stalledMode.contains(\"DIAMOND_HANDS\")") &&
                src.contains("stalledMode.contains(\"LONG_HOLD\")") &&
                src.contains("stalledMode.contains(\"SLEEPER\")") &&
                src.contains("position.isLongHold"),
        )
    }

    @Test
    fun cull_thresholds_are_defensible_defaults() {
        // Age gate.
        assertTrue("Age gate must be >= 15 min", src.contains("holdTimeMinutes >= 15L"))
        // Flat band.
        assertTrue("Pnl band must be [-3, +3]", src.contains("currentPnlPct in -3.0..3.0"))
        // Momentum.
        assertTrue("Momentum floor must be < 20", src.contains("ts.meta.momScore < 20"))
        assertTrue("Volume floor must be < 15", src.contains("ts.meta.volScore < 15"))
        // Whale / holders.
        assertTrue("No-whale check", src.contains("ts.meta.whaleSummary.isBlank()") && src.contains("ts.meta.velocityScore < 70.0"))
        assertTrue("No-holder-growth check", src.contains("ts.holderGrowthRate < 5.0"))
        // Runner guard.
        assertTrue(
            "Must not cull anything nearing target — nearTarget guard bails us out.",
            src.contains("currentPnlPct >= (targetProfit6091 * 0.5)"),
        )
    }

    @Test
    fun cull_urgency_is_normal_not_critical() {
        // A cull is capital-efficiency, not safety. Must not preempt other higher-urgency
        // exits (stop-loss, DIAMOND_TOP_GIVEBACK, rugSignal).
        assertTrue(
            "Cull must return Urgency.NORMAL so hard-stop / rugSignal / trailing-stop paths continue to preempt.",
            src.contains("urgency = Urgency.NORMAL"),
        )
        // And the confidence should be moderate — this is a heuristic, not a hard rule.
        assertTrue("Confidence should be in the 40-70 range", src.contains("confidence = 55.0"))
    }
}
