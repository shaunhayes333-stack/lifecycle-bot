package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8412 — live route-away and the shadow landing model share one verdict; a thin window falls back to the ledger record. */
class Aate8412PumpLandingAlignTest {
    private fun r(resolved: Int, landed: Int) = PumpLanding8288.Rate8288(resolved, landed)

    @Test fun thin_window_uses_lifetime_record() {
        assertTrue("3/17 lifetime, empty window", PumpLanding8288.effectiveAway8412(r(0, 0), r(17, 3)))
        assertTrue(PumpLanding8288.effectiveAway8412(r(2, 0), r(17, 3)))
        assertFalse("good lifetime, empty window", PumpLanding8288.effectiveAway8412(r(0, 0), r(17, 15)))
        assertFalse("no evidence anywhere", PumpLanding8288.effectiveAway8412(r(0, 0), r(0, 0)))
    }

    @Test fun a_full_window_decides_over_lifetime() {
        assertFalse("recent route recovered", PumpLanding8288.effectiveAway8412(r(4, 4), r(40, 5)))
        assertTrue("recent route failing", PumpLanding8288.effectiveAway8412(r(4, 0), r(40, 38)))
    }

    @Test fun live_and_shadow_read_the_same_verdict() {
        val pl = File("src/main/kotlin/com/lifecyclebot/engine/PumpLanding8288.kt").readText()
        val sa = File("src/main/kotlin/com/lifecyclebot/engine/truth/ShadowAlign8366.kt").readText()
        assertTrue(pl.contains("val r = awayNow8412(nowMs) ?: return false"))
        assertTrue(sa.contains("PumpLanding8288.awayNow8412(nowMs) != null"))
        assertFalse(sa.contains("rows8288(nowMs - com.lifecyclebot.engine.PumpLanding8288.WINDOW_MS_8288)"))
    }
}
