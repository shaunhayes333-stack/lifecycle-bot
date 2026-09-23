package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot
import com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
import com.lifecyclebot.perps.CryptoAltTrader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression coverage for the four authority mismatches visible in 5.0.7250. */
class Aate7251AuthorityConvergenceTest {

    @After fun cleanup() {
        ExecutionDecisionSnapshot6510.resetForTest()
        LaneExecutionCoordinator.resetForTests()
        BotRuntimeController.resetForTests()
    }

    @Test fun `recent sealed decision pins candidate version across bucket boundary`() {
        BotRuntimeController.resetForTests()
        val generation = BotRuntimeController.beginStart(paperMode = true, enabledTraders = "MEME")
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(true)
        RuntimeModeAuthority.publishExecutorMode(true)
        RuntimeModeAuthority.publishPipelineMode(true)
        val mint = "Mint7251StableSeal"
        val sealedVersion = 72510001L
        ExecutionDecisionSnapshot6510.record(
            ExecutionDecisionSnapshot(
                mint = mint,
                candidateVersion = sealedVersion,
                verdict = "BUY",
                executionLane = "QUALITY",
                score = 61.0,
                generatedAtMs = System.currentTimeMillis(),
                runtimeGeneration = generation,
                mode = "PAPER",
            )
        )
        assertEquals(sealedVersion, LaneExecutionCoordinator.candidateVersionFor(mint))
    }

    @Test fun `dynamic spot owns crypto alt lane while static spot and leverage remain isolated`() {
        assertEquals("CRYPTO_ALT", CryptoAltTrader.canonicalCryptoLane7251(isDynamic = true, isSpot = true))
        assertEquals("CRYPTO_SPOT", CryptoAltTrader.canonicalCryptoLane7251(isDynamic = false, isSpot = true))
        assertEquals("CRYPTO_LEV", CryptoAltTrader.canonicalCryptoLane7251(isDynamic = true, isSpot = false))
        val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(trader.contains("selectedLane = canonicalCryptoLane7251(signal.isDynamic, effectiveIsSpot6536)"))
    }

    @Test fun `carried marks cannot be promoted into fresh exit ticks`() {
        val registry = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(registry.contains("data class HeldMarkRefresh7251"))
        assertTrue(registry.contains("freshObservation = fresh"))
        assertTrue(registry.contains("return existing.price") &&
            !registry.substringAfter("private fun carryForwardPrice6819").substringBefore("\n    }").contains("lastUpdatedMs"))
        assertTrue(trader.contains("!heldMark7251.freshObservation"))
        assertTrue(trader.contains("markUpdatedAtMs = if (position.isDynamic)"))
        assertTrue(trader.contains("DYNAMIC_MARK_MAX_AGE_MS_6654 = 60_000L"))
        assertTrue(trader.contains("CRYPTO_HELD_MARK_REFRESH_COALESCED_7251"))
    }

    @Test fun `active accounting replays use the canonical quarantine scope`() {
        val journal = File("src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicReplay6619.kt").readText()
        val typed = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperReplay6464.kt").readText()
        assertTrue(journal.contains("quarantinedPositionIds6635(\"paper\")"))
        assertTrue(journal.contains("JOURNAL_QUARANTINED_ROWS_EXCLUDED_ACTIVE_ACCOUNT_7251"))
        assertTrue(typed.contains("quarantinedPositionIds6635(\"paper\")"))
        assertTrue(typed.contains("PAPER_REPLAY_EXCLUDED_CANONICAL_QUARANTINE_7251"))
    }

    @Test fun `hot exit heartbeat advances through each position`() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val loop = bot.substringAfter("managedThisTick6663.forEach { ts ->")
            .substringBefore("// V5.0.5999")
        assertTrue(loop.contains("lastTickExitSweepMs = System.currentTimeMillis()"))
        assertTrue(loop.contains("finally"))
    }
}
