package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6658 — source-level acceptance locks for the hot-loop unchoke,
 * TICKET stamp retrieval parity, and decision-aware status detection.
 *
 * The fixes address (from operator Feb 2026 pipeline dump):
 *   • bot loop > 35s per cycle (openPositions() per-mint O(N) scans)
 *   • BLUECHIP / SHITCOIN status=TICKET_CHOKED with size,mark > 0
 *   • QUALITY status=SIZING_CHOKED after V5.0.6657 FDG fanout
 */
class Aate6658HotLoopUnchokeTest {

    @Test
    fun `processTokenCycle uses indexed mint lookup rather than full openPositions scan`() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val cycleStart = bot.indexOf("private fun processTokenCycle(mint: String")
        assertTrue("processTokenCycle must exist", cycleStart > 0)
        // The next few lines must resolve the canonical open through the
        // targeted accelerator, not the full-list scan the operator flagged.
        val prelude = bot.substring(cycleStart, cycleStart + 4000)
        assertTrue(
            "processTokenCycle must call firstOpenForMint(mint) rather than openPositions().firstOrNull",
            prelude.contains("CanonicalPositionAuthority6441.firstOpenForMint(mint)"),
        )
        // Strip the code comments (// … end of line) so we don't match ourselves
        // — the change-log inside the fix references the old scan by name.
        val code = prelude.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse(
            "processTokenCycle must not re-introduce openPositions().firstOrNull { it.mint == mint }",
            code.contains("CanonicalPositionAuthority6441.openPositions()") &&
                code.substringAfter("CanonicalPositionAuthority6441.openPositions()")
                    .take(200).contains(".firstOrNull { it.mint == mint }"),
        )
    }

    @Test
    fun `firstOpenForMint is defined on the canonical position authority`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        assertTrue(
            "firstOpenForMint accelerator must exist",
            src.contains("fun firstOpenForMint(mint: String): Position?"),
        )
        assertTrue(
            "accelerator must reuse isEconomicallyValidOpen6631 authority (no divergent filter)",
            src.substringAfter("fun firstOpenForMint")
                .substringBefore("\n    }\n").contains("isEconomicallyValidOpen6631(p)"),
        )
    }

    @Test
    fun `specialist ticket stamp fires whether intent was retrieved or created`() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        // Retrieval no longer swallows the TICKET stamp — the stamp is
        // fanned out immediately after the retrieval/create tuple.
        assertTrue(
            "TICKET must be stamped whenever specialistIntent6614 is non-null (retrieved or created)",
            bot.contains("V5.0.6658 §TICKET_STAMP_RETRIEVAL_PARITY"),
        )
        assertTrue(
            "TICKET stamp must use the intent's canonical attemptId so recordDeskStage dedupe holds",
            bot.contains("ToolkitSignalSheet.recordDeskStage(cyclePrimaryLane, \"TICKET\", ticketStampIntent6658.attemptId)"),
        )
    }

    @Test
    fun `specialist role liveness distinguishes fdg-blocked-all from sizing-choked`() {
        val toolkit = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        val start = toolkit.indexOf("val status = when {")
        val end = toolkit.indexOf("else -> \"ACTIVE\"", start)
        assertTrue("designated role liveness block must exist", start > 0 && end > start)
        val block = toolkit.substring(start, end)
        assertTrue(
            "FDG_BLOCKED_ALL must be triggered when fdgAllow == 0 before SIZING_CHOKED",
            block.indexOf("FDG_BLOCKED_ALL") in (block.indexOf("FDG_CHOKED") + 1) until block.indexOf("SIZING_CHOKED"),
        )
        assertTrue(
            "status detection must reference fdgAllow == 0L as the FDG_BLOCKED_ALL condition",
            block.contains("fdgAllow == 0L -> \"FDG_BLOCKED_ALL\""),
        )
    }

    @Test
    fun `final decision gate stamps use ExecutionBook-aligned primary lane not TradingModeTag`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(
            "FinalDecisionGate must resolve the ExecutionBook-aligned primary lane at FDG entry",
            src.contains("V5.0.6658 §SPECIALIST_LANE_STAMP_ALIGNMENT"),
        )
        assertTrue(
            "canonicalPrimaryLane6658 must be pulled from LaneExecutionCoordinator.currentElection6600",
            src.contains("LaneExecutionCoordinator") && src.contains("currentElection6600(ts.mint)?.primaryLane"),
        )
        assertTrue(
            "FDG_ALLOW/FDG_BLOCK stamp must use canonicalPrimaryLane6658",
            src.contains("recordDeskStage(canonicalPrimaryLane6658, if (shouldTradeFinal)"),
        )
        assertTrue(
            "OrderSizeResolver.resolve must receive canonicalPrimaryLane6658 as laneName",
            src.contains("laneName = canonicalPrimaryLane6658,"),
        )
    }

    @Test
    fun `executor preflight pins pre-ticket lane to active intent when authority snapshot lags`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "Executor must consult ExecutableOpenGate.activeExecutionIntent6519 to converge preTicketLane6514",
            src.contains("V5.0.6658 §PRE_TICKET_LANE_INTENT_CONVERGENCE"),
        )
        assertTrue(
            "preTicketLane6514 must prefer the sealed active intent before snapshot and legacy fallbacks",
            src.contains("val preTicketLane6514 = activeIntentLane6658\n            ?: authority6513?.executionLane\n            ?: layerTag"),
        )
    }

    @Test
    fun `paper buy rejects sentinel provenance at entry so template prices cannot seal a canonical position`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "paperBuy must consult MarketDataProvenance6471 before sealing entry",
            src.contains("V5.0.6658 §ENTRY_PRICE_PROVENANCE_ENFORCEMENT"),
        )
        assertTrue(
            "paperBuy must reject when entryProvenance6658 is not AUTHORITATIVE",
            src.contains("MarketDataProvenance6471.classify(") &&
                src.contains("if (entryProvenance6658 != com.lifecyclebot.engine.truth.MarketDataProvenance6471.Provenance.AUTHORITATIVE)"),
        )
        assertTrue(
            "paperBuy must stamp markPaperBuyNotOpened(\"ENTRY_PROVENANCE_...\") to keep finality reconciliation intact",
            src.contains("markPaperBuyNotOpened(\"ENTRY_PROVENANCE_"),
        )
    }

    @Test
    fun `market data provenance flags standalone sentinel prices even when tuple varies`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/MarketDataProvenance6471.kt").readText()
        assertTrue(
            "SENTINEL_PRICES_STANDALONE_6658 list must exist",
            src.contains("SENTINEL_PRICES_STANDALONE_6658 = doubleArrayOf"),
        )
        assertTrue(
            "classify() must consult the standalone sentinel-price list independent of the (mcap, liquidity) tuple",
            src.contains("V5.0.6658 §SENTINEL_PRICE_STANDALONE") &&
                src.contains("sentinel_price_standalone(\$price)"),
        )
        // The three observed operator sentinel prices must be listed.
        listOf("0.050250000", "0.000052530", "0.000000589600").forEach { p ->
            assertTrue("standalone sentinel-price list must contain $p", src.contains(p))
        }
    }

    @Test
    fun `every BotService caller of FinalDecisionGate wires specialistLane to the ExecutionBook desk`() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        // Every call site must pass specialistLane so canonicalPrimaryLane6658
        // converges on the funnel desk instead of falling through to
        // tradingModeTag.name. Enumerate the operator lanes explicitly.
        val required = listOf(
            "specialistLane = \"TREASURY\"",
            "specialistLane = \"QUALITY\"",
            "specialistLane = \"BLUECHIP\"",
            "specialistLane = \"SHITCOIN\"",
            "specialistLane = \"MOONSHOT\"",
            "specialistLane = \"MANIPULATED\"",
            "specialistLane = \"EXPRESS\"",
            "specialistLane = \"DIP_HUNTER\"",
            "specialistLane = cyclePrimaryLane",
        )
        required.forEach { needle ->
            assertTrue(
                "BotService must wire $needle into FinalDecisionGate.evaluate — otherwise the funnel loses stamps",
                bot.contains(needle),
            )
        }
    }
}
