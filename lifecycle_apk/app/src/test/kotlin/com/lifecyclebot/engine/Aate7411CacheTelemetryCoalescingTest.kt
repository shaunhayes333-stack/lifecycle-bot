package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7411CacheTelemetryCoalescingTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun strategy_cache_hits_are_cadence_coalesced() {
        val s = src("engine/StrategyTelemetry.kt")
        assertTrue(s.contains("CACHE_HIT_EMIT_INTERVAL_MS_7411"))
        assertTrue(s.contains("emitCacheHit7411(\"leaderboard:\$key\""))
        assertTrue(s.contains("emitCacheHit7411(\"paper:\$limit\""))
        assertTrue(s.contains("STRATEGY_CLEAN_LIVE_LEADERBOARD_CACHE_HIT_6327"))
    }

    @Test fun specialist_liveness_coalescing_does_not_emit_every_offer() {
        val s = src("engine/truth/MemeExecutionFunnelReceivers6625.kt")
        assertTrue(s.contains("COALESCED_EMIT_INTERVAL_MS_7411"))
        assertTrue(s.contains("coalescedEmitAt7411"))
        assertTrue(s.contains("last.compareAndSet(prior, now)"))
    }
}
