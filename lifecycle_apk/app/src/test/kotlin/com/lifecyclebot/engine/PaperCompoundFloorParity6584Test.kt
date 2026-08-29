package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6584 §P0-8 — paper-mode compound-floor parity.
 *
 * Operator forensic (6580):
 *   wallet 4.641 SOL, recommended 0.400 SOL,
 *   canonical requested 0.01247 SOL, final=0 BELOW_MIN_EXECUTABLE.
 *
 * Root cause: LiveSizingProfile.laneCompoundFloor bailed out on
 * isPaperMode=true → 15 stacked multipliers compound-shrunk the paper
 * request to dust unopposed.
 *
 * Fix: paper mode now inherits the SOL-based compound floor + wallet-pct
 * cap. Live mode behaviour unchanged.
 */
class PaperCompoundFloorParity6584Test {

    private val sizerSrc = File("src/main/kotlin/com/lifecyclebot/engine/SmartSizer.kt").readText()
    private val profileSrc = File("src/main/kotlin/com/lifecyclebot/engine/LiveSizingProfile.kt").readText()

    @Test
    fun smart_sizer_applies_compound_floor_in_paper_mode() {
        assertTrue(
            "SmartSizer.calculate final-size stage must now include paper mode " +
                "in the laneCompoundFloor call (was gated by !isPaperMode)",
            sizerSrc.contains("if (size > 0.0 && LiveSizingProfile.enabled) {") &&
                sizerSrc.contains("isPaperMode = isPaperMode,")
        )
    }

    @Test
    fun lane_compound_floor_no_longer_bails_on_paper_mode() {
        assertTrue(
            "LiveSizingProfile.laneCompoundFloor must NOT bail out on isPaperMode=true anymore",
            !profileSrc.contains("if (!enabled || isPaperMode || baseSol <= 0.0) return baseSol")
        )
        assertTrue(
            "LiveSizingProfile.laneCompoundFloor short-circuit is now enabled+baseSol only",
            profileSrc.contains("if (!enabled || baseSol <= 0.0) return baseSol")
        )
    }
}
