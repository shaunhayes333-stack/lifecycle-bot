package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8700 — every LIVE data defer is counted under the wait its own trace names (8689: 1,896 defers, two reason names). */
class Aate8700DeferCauseTest {
    @Test fun aDeferIsNamedByWhatItWaitsFor() {
        val c = CandidateAdmission8136
        assertEquals(listOf("ENTRY_WAIT_FLOW_UNOBSERVED"), c.deferCauses8700(listOf("gross=- cost=+3.1 net=- downside=-", "entryWait8506=FLOW_UNOBSERVED", "missing=swaps,bp,chg")))
        assertEquals(listOf("MOVE_UNREAD"), c.deferCauses8700(listOf("gross=- cost=+3.1 net=- downside=-", "missing=opp:UNKNOWN,economics")))
        assertEquals(listOf("COST_UNKNOWN"), c.deferCauses8700(listOf("gross=- cost=- net=- downside=-", "missing=economics")))
        assertEquals(listOf("MOVE_HISTORY_SPARSE"), c.deferCauses8700(listOf("gross=- cost=+2.8 net=-", "missing=move:HISTORY_SPARSE")))
        assertEquals(listOf("FALLING_HOUR"), c.deferCauses8700(listOf("gross=- cost=+2.8 net=-", "missing=chg:FALLING_HOUR_8418,economics")))
        assertEquals(listOf("CRITICAL_LIQ", "PRICE_STALE"),
            c.deferCauses8700(listOf("gross=+9.0 cost=+2.8 net=+6.2", "missing=liq:UNKNOWN,price", "criticalUnobserved8170=liq:UNKNOWN")))
        assertEquals("optional gaps alone are not a cause", listOf("UNNAMED"), c.deferCauses8700(listOf("gross=+9.0 cost=+2.8 net=+6.2", "missing=tape:UNKNOWN")))
    }

    @Test fun freshLiveDefersAreCountedAndReported() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(s.contains("if (mode == \"LIVE\") deferCauses8700(d.trace).forEach {"))
        assertTrue(s.contains("\${deferTerminalLine8418()} \${deferCauseLine8700()}\""))
    }
}
