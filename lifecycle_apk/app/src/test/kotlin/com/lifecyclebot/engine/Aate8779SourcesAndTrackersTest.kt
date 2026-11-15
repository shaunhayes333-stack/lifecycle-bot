package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8779 — code audit (discovery F9, F13): crypto hand-offs carry their own source; the proposal map is concurrent. */
class Aate8779SourcesAndTrackersTest {
    @Test fun the_crypto_desk_is_not_dex_trending() {
        assertEquals("CRYPTO_DESK_HANDOFF", SourceTruth8633.CRYPTO_DESK_HANDOFF_8779)
        val alt = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(alt.contains("scanner = com.lifecyclebot.engine.SourceTruth8633.CRYPTO_DESK_HANDOFF_8779,"))
        assertFalse(alt.contains("scanner = \"DEX_TRENDING\""))
        val bs = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bs.contains("tags.contains(SourceTruth8633.CRYPTO_DESK_HANDOFF_8779)) out += listOf(\"QUALITY\", \"TREASURY\", \"CASHGEN\")"))
        assertTrue(bs.contains("else -> \"UNKNOWN\"   // V5.0.8779"))
    }

    @Test fun the_proposal_tracker_map_is_concurrent_and_pruned() {
        val t = File("src/main/kotlin/com/lifecyclebot/engine/TradeLifecycle.kt").readText()
        assertTrue(t.contains("private val proposalTrackers = java.util.concurrent.ConcurrentHashMap<String, ProposalTracker>()"))
        assertEquals(4, Regex("pruneTrackers8779\\(System.currentTimeMillis\\(\\)\\)").findAll(t).count())
    }
}
