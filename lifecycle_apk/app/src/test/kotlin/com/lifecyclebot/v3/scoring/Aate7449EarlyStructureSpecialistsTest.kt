package com.lifecyclebot.v3.scoring

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7449EarlyStructureSpecialistsTest {
    private fun src(name: String) = File("src/main/kotlin/com/lifecyclebot/v3/scoring/$name").readText()

    @Test fun strongFlowCanFillMissingMomentumButCannotReverseAnObservedPriceMove() {
        fun read(momentum: Double, observed: Boolean) =
            ShitCoinExpress.entryMomentum8670(momentum, observed, 2.0, 80.0, 48.0, 2.0)
        assertTrue(read(-4.0, true) == -4.0)
        assertTrue(read(0.0, true) == 0.0)
        assertTrue(read(0.0, false) == 2.0)
    }

    @Test fun sniper_owns_only_pre_ignition_or_ignition_and_three_minute_window() {
        val s = src("ProjectSniperAI.kt")
        assertTrue(s.contains("MAX_TOKEN_AGE_SECONDS = 180"))
        assertTrue(s.contains("Phase.PRE_IGNITION"))
        assertTrue(s.contains("Phase.IGNITION"))
        assertTrue(s.contains("SNIPER_PHASE_HANDOFF_7794_"))  // V5.0.7794 restamped the phase handoff label
        assertFalse(s.contains("MAX_TOKEN_AGE_SECONDS = 600"))
    }

    @Test fun sniper_confidence_uses_launch_flow_not_large_printed_pump_as_alpha() {
        val s = src("ProjectSniperAI.kt")
        assertTrue(s.contains("launch7449.distinctBuyers60s"))
        assertTrue(s.contains("launch7449.accelerationRising"))
        assertTrue(s.contains("launch7449.buySharePct"))
        assertTrue(s.contains("priceChange > 15.0 -> -8"))
        assertFalse(s.contains("priceChange > 20 -> 15"))
    }

    @Test fun express_neutral_buy_pressure_cannot_fabricate_momentum() {
        val s = src("ShitCoinExpress.kt")
        assertTrue(s.contains("flowIgnitionProxy7449"))
        assertTrue(s.contains("buyPressurePct >= maxOf(58.0, fluidMinBuyPressure + 8.0)"))
        assertTrue(s.contains("volumeChange >= 1.5"))
        assertFalse(s.contains("buyPressurePct >= 50.0) {\n            (buyPressurePct - 49.0)"))
    }

    @Test fun express_scores_early_continuation_and_rejects_parabolic_chase() {
        val s = src("ShitCoinExpress.kt")
        // superseded by V5.0.8605 — the parabolic-chase refusal is deleted: EXPRESS's own labelled decisions showed the coins it
        // refused (5 m > +12 %) netted -0.1 % with 3.7 % runners against -2.7 % / 1.6 % for the coins it allowed.
        assertFalse(s.contains("EXPRESS_CHASE_REJECTED_7449"))
        assertFalse(s.contains("fun chaseExtended8555("))
        assertTrue(s.contains("effectiveMomentum in 7.0..15.0 -> 25"))
        assertTrue(s.contains("priceChange5Min in 2.0..8.0 -> 20"))
        assertFalse(s.contains("priceChange5Min >= 15 -> 15"))
    }
}
