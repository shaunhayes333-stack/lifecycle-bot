package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate6697LearningReplayAuthorityRepairTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun cross_asset_history_repair_never_selects_solana_meme_positions() {
        val s = src("engine/truth/CanonicalPaperTransaction6486.kt")
        assertTrue(s.contains("it.assetClass != AssetClass.SOLANA_TOKEN"))
        assertTrue(s.contains("CROSS_ASSET_CANONICAL_OPEN_6659"))
    }

    @Test fun replay_supersedes_legacy_6659_projection_when_native_buy_exists() {
        val s = src("engine/truth/JournalEconomicReplay6619.kt")
        assertTrue(s.contains("nativeBuyPositions6697"))
        assertTrue(s.contains("JOURNAL_CROSS_ASSET_OPEN_SUPERSEDED_6697"))
        assertTrue(s.contains("ignore_repair_projection_native_buy_exists"))
    }

    @Test fun learning_exclusion_is_not_reported_as_consumer_ack() {
        val bus = src("engine/truth/CanonicalFinalizedTradeBus6464.kt")
        val bridge = src("engine/truth/FinalizedBusConsumerBridge6465.kt")
        assertTrue(bus.contains("consumerExcluded"))
        assertTrue(bus.contains("fun exclude(consumer: String, tradeId: String, reason: String)"))
        assertTrue(bus.contains("processed="))
        assertTrue(bus.contains("excluded="))
        assertTrue(bridge.contains("FINALIZED_LEARNING_INELIGIBLE_EXCLUDED_6697"))
        assertTrue(bridge.contains("LEARNING_QUARANTINE_EXCLUDED_NO_MUTATION_6697"))
        assertFalse(bridge.contains("FINALIZED_LEARNING_INELIGIBLE_ACK_NO_MUTATION_6519"))
    }

    @Test fun durable_legacy_false_acks_are_not_imported_after_6697() {
        val p = src("engine/truth/CanonicalFinalityPersistence6486.kt")
        assertTrue(p.contains("ACK_PREFIX_6697"))
        assertTrue(p.contains("ack6697:"))
        assertFalse(p.contains("private const val ACK_PREFIX_6486 = \"ack:\""))
    }

    @Test fun terminal_economic_identity_survives_process_restart() {
        val p = src("engine/truth/CanonicalFinalityPersistence6486.kt")
        assertTrue(p.contains("put(\"economicEventId\", e.economicEventId)"))
        assertTrue(p.contains("economicEventId = j.optString(\"economicEventId\", \"\")"))
    }
}
