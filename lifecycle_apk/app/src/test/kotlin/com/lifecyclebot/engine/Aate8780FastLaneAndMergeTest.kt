package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8780 — code audit (discovery F11, F12): the fast lane runs on the runtime mode; the merge queue is one critical section. */
class Aate8780FastLaneAndMergeTest {
    @Test fun the_fast_lane_reads_the_current_config_and_mode() {
        val bs = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bs.contains("processTokenCycle(mint, cycleCfg8779(cfg), wallet, t0)"))
        assertTrue(bs.contains("processTokenCycle(mint, cycleCfg8779(cfg), wallet, System.currentTimeMillis())"))
        assertTrue(bs.contains("return c.copy(paperMode = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { c.paperMode })"))
    }

    @Test fun merge_enqueue_is_atomic_and_its_scanner_set_concurrent() {
        val q = File("src/main/kotlin/com/lifecyclebot/engine/TokenMergeQueue.kt").readText()
        assertTrue(q.contains("    @Synchronized\n    fun enqueue("))
        assertTrue(q.contains("scanners = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { add(scanner) },"))
    }
}
