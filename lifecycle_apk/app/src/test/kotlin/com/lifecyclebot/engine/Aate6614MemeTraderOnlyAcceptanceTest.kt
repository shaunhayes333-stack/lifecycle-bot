package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Executable MemeTrader-only acceptance for immutable specialist ownership. */
class Aate6614MemeTraderOnlyAcceptanceTest {
    private val desks = listOf(
        "CORE", "EXPRESS", "MANIPULATED", "DIP_HUNTER", "TREASURY", "CASHGEN",
        "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "MOONSHOT", "PROJECT_SNIPER",
    )

    @Test fun every_enabled_specialist_can_seal_its_own_immutable_election() {
        LaneExecutionCoordinator.resetForTests()
        desks.forEachIndexed { i, lane ->
            val mint = "M6614_${lane}_${System.nanoTime()}_$i"
            val elected = LaneExecutionCoordinator.canRequestExecution(mint, lane)
            assertTrue("$lane must be eligible to own a matching candidate: ${elected.reason}", elected.allowed)
            assertEquals(lane, elected.primaryLane)
            assertTrue(elected.electionId.isNotBlank())
            val attemptedRewrite = LaneExecutionCoordinator.canRequestExecution(mint, if (lane == "QUALITY") "SHITCOIN" else "QUALITY")
            assertFalse("sealed $lane owner must not be rewritten", attemptedRewrite.allowed)
            assertEquals(lane, attemptedRewrite.primaryLane)
            assertEquals(elected.electionId, attemptedRewrite.electionId)
        }
    }

    @Test fun executable_token_map_materializes_mark_without_secondary_provider() {
        CanonicalPriceMarkRegistry6522.resetForTest()
        val mint = "M6614_MARK_${System.nanoTime()}"
        val result = CanonicalPriceMarkRegistry6522.refreshFromExecutableTokenMap6614(
            mint = mint, pairOrPool = "RaydiumPool6614", quoteMint = "USDC",
            source = "DEXSCREENER_PAIR_POLL", priceUsd = 0.0000123,
            liquidityUsd = 8_000.0, routeStatus = "DEX_ROUTABLE",
        )
        assertTrue(result.reason, result.promoted)
        val mark = CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE)
        assertNotNull(mark)
        assertEquals("RaydiumPool6614", mark!!.pairId)
    }

    @Test fun meme_source_contract_preserves_lane_and_closes_funnel_holes() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val coordinator = File("src/main/kotlin/com/lifecyclebot/engine/LaneExecutionCoordinator.kt").readText()
        val auth = File("src/main/kotlin/com/lifecyclebot/engine/TradeAuthorizer.kt").readText()
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val sheet = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        assertTrue(bot.contains("roleFitPrimary6614") && bot.contains("strongestRole6614") && bot.contains("ensembleCoreFit6614"))
        assertFalse(coordinator.contains("TREASURY_DEFER_SPECIALIST_FIRST"))
        assertTrue(coordinator.contains("Do not re-elect it here using static"))
        assertTrue(auth.contains("CASHGEN,") && bot.contains("\"CORE\" -> TradeAuthorizer.ExecutionBook.CORE") && bot.contains("\"CASHGEN\" -> TradeAuthorizer.ExecutionBook.CASHGEN"))
        assertTrue(bot.contains("SPECIALIST_INTENT_WITHOUT_FDG_OUTCOME") && bot.contains("FDG_ALLOW") && bot.contains("FDG_BLOCK"))
        assertTrue(gate.contains("markId6614") && gate.contains("sealedProvenance6614") && gate.contains("EXPIRED_TICKET_ECONOMIC_REJECT_6614"))
        assertTrue(sheet.contains("fdgAllow + fdgBlock == 0L") && sheet.contains("SPECIALIST_INTENT_WITHOUT_FDG_OUTCOME="))
    }
}
