package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8781 — code audit (discovery F14): the selector and the source bandit file a source under the same family. */
class Aate8781FamilyMappingTest {
    @Test fun profiles_are_dex_and_meteora_pools_are_pool_launches_in_both() {
        val bs = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bs.contains("tags.contains(SourceTruth8633.DEX_PROFILES_8633) -> \"DEX\""))
        val b = File("src/main/kotlin/com/lifecyclebot/engine/ScannerDiversityBandit.kt").readText()
        assertTrue(b.contains("\"RAYDIUM\" to listOf(\"RAYDIUM\", \"METEORA\", \"NEW_POOL\"),"))
        assertTrue(b.contains("\"DEX_PROFILES\""))
    }
}
