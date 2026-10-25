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

    @Test fun geckoHostBudgetServesCandleReadsBeforeLists() {
        val g = com.lifecyclebot.network.GeckoHostBudget8382
        assertEquals(10, g.PER_MIN_8382)   // api.geckoterminal.com/docs: ~10 calls/minute
        assertEquals(com.lifecyclebot.network.DexHostBudget8287.Prio8287.NEAR, g.prioOf8382("/api/v2/networks/solana/pools/Abc/ohlcv/minute", false))
        assertEquals(com.lifecyclebot.network.DexHostBudget8287.Prio8287.NEAR, g.prioOf8382("/api/v2/networks/solana/pools/Abc/trades", false))
        assertEquals(com.lifecyclebot.network.DexHostBudget8287.Prio8287.BULK, g.prioOf8382("/api/v2/networks/solana/trending_pools", false))
        assertEquals(com.lifecyclebot.network.DexHostBudget8287.Prio8287.EXIT, g.prioOf8382("/api/v2/networks/solana/trending_pools", true))
        val b = com.lifecyclebot.network.DexHostBudget8287.Bucket8287(g.PER_MIN_8382, g.BURST_8382, g.BULK_RESERVE_8382, 0, Long.MAX_VALUE)
        val t = 1_000_000L
        assertEquals(0L, b.take8287(t, com.lifecyclebot.network.DexHostBudget8287.Prio8287.BULK))          // 3 -> 2
        assertEquals(0L, b.take8287(t, com.lifecyclebot.network.DexHostBudget8287.Prio8287.BULK))          // 2 -> 1
        assertEquals(-1L, b.take8287(t, com.lifecyclebot.network.DexHostBudget8287.Prio8287.BULK))         // the last token is the candle reads'
        assertEquals(0L, b.take8287(t, com.lifecyclebot.network.DexHostBudget8287.Prio8287.NEAR))
        assertTrue(b.take8287(t, com.lifecyclebot.network.DexHostBudget8287.Prio8287.NEAR, g.DECISION_WAIT_MS_8382) < 0L)   // next token is 6 s away
        assertEquals(10, b.perMin8359(t + 3_600_000L))   // never raised above the documented rate
    }

    @Test fun theSharedClientCarriesTheGeckoBudgetAndTheFeedIgnoresLocalRefusals() {
        val c = File("src/main/kotlin/com/lifecyclebot/network/SharedHttpClient.kt").readText()
        assertTrue(c.indexOf("addInterceptor(com.lifecyclebot.network.GeckoHostBudget8382)") in 1 until c.indexOf(".addInterceptor(com.lifecyclebot.network.HostCircuitInterceptor)"))
        val f = File("src/main/kotlin/com/lifecyclebot/network/SolanaOhlcvFeed6916.kt").readText()
        assertTrue(f.indexOf("OHLCV_GECKO_LOCAL_BUDGET_WAIT_8382") in 1 until f.indexOf("noteResponse6944(resp.code"))
    }
}
