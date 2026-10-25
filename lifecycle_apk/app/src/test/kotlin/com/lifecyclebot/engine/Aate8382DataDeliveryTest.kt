package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** V5.0.8382 — provider health is recent (no lifetime latch), and the Helius / DexScreener readers use their budgets. */
class Aate8382DataDeliveryTest {
    @Test fun aQuietHostDecaysOldFailuresSoAGateProbesItAgain() {
        val st = ApiHealthMonitor.HostStats()
        st.successes.set(13); st.failures4xx.set(22); st.networkErrors.set(15)
        val t0 = System.currentTimeMillis() - 10 * ApiHealthMonitor.QUIET_MS_8382
        st.lastFailureMs.set(t0)
        // the 8381 Birdeye brownout shape: total>=20, hard fails>=10, sr<50%
        assertTrue(st.failures5xx.get() + st.networkErrors.get() >= 10)
        st.decay8382(t0 + ApiHealthMonitor.QUIET_MS_8382)
        assertEquals(11, st.failures4xx.get()); assertEquals(8, st.networkErrors.get()); assertEquals(13, st.successes.get())
        st.decay8382(t0 + ApiHealthMonitor.QUIET_MS_8382 + 1_000L)   // not quiet long enough since the last step
        assertEquals(11, st.failures4xx.get())
        st.decay8382(t0 + 2 * ApiHealthMonitor.QUIET_MS_8382)
        assertTrue(st.networkErrors.get() < 10)                         // brownout's hard-fail bar no longer met
        assertTrue(st.failures4xx.get() >= 1)                           // a failure is never forgotten outright
    }

    @Test fun aBusyHostTracksItsRecentWindow() {
        val st = ApiHealthMonitor.HostStats()
        st.failures5xx.set(150); st.successes.set(60)
        assertTrue(150 + 60 > ApiHealthMonitor.WINDOW_8382); st.lastFailureMs.set(System.currentTimeMillis())
        st.decay8382(System.currentTimeMillis())
        assertEquals(75, st.failures5xx.get()); assertEquals(30, st.successes.get())
    }

    @Test fun everyHealthReaderDecaysFirst() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ApiHealthMonitor.kt").readText()
        assertTrue(src.contains("for (st in hosts.values) st.decay8382(now)"))
        assertTrue(src.contains("s.decay8382(System.currentTimeMillis())   // V5.0.8382"))
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/BirdeyeBudgetGate.kt").readText()
        assertTrue(gate.contains("ApiHealthMonitor.snapshot()[\"birdeye\"]"))
    }
}
