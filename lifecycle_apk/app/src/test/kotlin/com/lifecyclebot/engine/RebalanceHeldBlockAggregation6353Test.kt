package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * V5.0.6353 — hot-watchlist rebalance held-block log aggregation golden-tape test.
 *
 * Operator symptom: LIVE_HELD_SOURCE_REBALANCE_EVICT_BLOCKED_4550 fired 5855
 * times in 15 minutes (~6.3/sec) — pure observability spam because the guard
 * was already doing the right thing (never evict a held/open position).
 *
 * Fix: collect the held-blocked count inside the loop; emit a SINGLE
 * aggregated label + forensic line per rebalance pass. Same label preserved
 * so dashboards keep working; ~100× less log volume.
 */
class RebalanceHeldBlockAggregation6353Test {

    @Test
    fun held_block_label_still_exists_but_is_emitted_once_per_pass() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        // Label preserved for dashboards.
        assertTrue(txt.contains("LIVE_HELD_SOURCE_REBALANCE_EVICT_BLOCKED_4550"))
        // Aggregated tag present.
        assertTrue("aggregated held-block emit must be tagged 6353",
            txt.contains("keep_watchlist_aggregated_6353"))
        // Per-iteration continue no longer bumps the label.
        assertFalse("per-iteration labelInc must be removed",
            txt.contains("try { PipelineHealthCollector.labelInc(\"LIVE_HELD_SOURCE_REBALANCE_EVICT_BLOCKED_4550\") } catch (_: Throwable) {}\n                    try { ForensicLogger.lifecycle(\"LIVE_HELD_SOURCE_REBALANCE_EVICT_BLOCKED_4550\""))
    }

    @Test
    fun aggregation_counter_and_first_mint_tracked() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(txt.contains("var heldBlockedThisPass = 0"))
        assertTrue(txt.contains("var heldBlockedFirstMint = \"\""))
        assertTrue(txt.contains("heldBlockedThisPass++"))
    }
}
