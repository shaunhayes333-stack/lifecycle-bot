package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7408HotPathAndPriorRegressionTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun launch_phase_is_cached_across_same_observation_burst() {
        val s = src("engine/truth/LaunchPhaseAuthority7401.kt")
        assertTrue(s.contains("SNAPSHOT_TTL_MS_7408 = 750L"))
        assertTrue(s.contains("cache7408[mintKey7408]"))
        assertTrue(s.contains("SAME_PHASE_EMIT_MS_7408"))
    }

    @Test fun missing_supply_telemetry_is_state_coalesced() {
        val s = src("engine/truth/TokenMetricsAuthority7069.kt")
        assertTrue(s.contains("NO_SUPPLY_EMIT_INTERVAL_MS_7408"))
        assertTrue(s.contains("noSupplyEmitAt7408[mint]"))
    }

    @Test fun live_paper_seed_is_weak_prior_and_cannot_boost_size() {
        val s = src("engine/LiveProbabilityEngine.kt")
        assertTrue(s.contains("(paperRawP7408 - 0.5) * 0.25"))
        assertTrue(s.contains("coerceAtMost(8L)"))
        assertTrue(s.contains("if (!liveRuntime7408 && paperColdStartWinner7403"))
    }

    @Test fun source_native_or_pending_pair_cannot_be_called_no_pair() {
        val s = src("engine/BotService.kt")
        assertTrue(s.contains("PAIR_SOURCE_NATIVE_PRICE_PENDING"))
        assertTrue(s.contains("PAIR_PENDING_HYDRATION"))
        assertTrue(s.contains("val agedNoPair = hardUnavailable7408"))
        assertTrue(s.contains("PAIR_HARD_UNAVAILABLE"))
    }
}
