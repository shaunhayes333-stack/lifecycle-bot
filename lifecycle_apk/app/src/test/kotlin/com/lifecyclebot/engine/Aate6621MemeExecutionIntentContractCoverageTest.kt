package com.lifecyclebot.engine

import org.junit.Test
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * V5.0.6621 — MEME_SOURCE_LEVEL_EXECUTION_PROVENANCE (Slice 2).
 *
 * Delivers the sealed contract + coordinator + entry-lane registry
 * receivers so Slice 3 rollout is a mechanical migration:
 *
 *   §2/§6 — MemeExecutionIntent6621.Intent immutable envelope +
 *           MemeEntryCoordinator6621.submitMemeSpecialistEntry6621
 *           canonical entry funnel.
 *   §5    — BuyResult6621.Outcome sealed + record6621 +
 *           assertOpenedOrCountPremature6621 premature-commit probe.
 *   §3    — PositionEntryLaneRegistry6621 seals ENTRY lane against
 *           subsequent rewrites (Position.tradingMode stays mutable
 *           for activeLane / strategyMode transitions).
 *   §4    — MemeEntryCoordinator6621.probeAliasing6621 flags every
 *           historical specialist→wrong-executor routing (QUALITY→
 *           blueChipBuy, MANIPULATED→shitCoinBuy, EXPRESS→shitCoinBuy,
 *           PROJECT_SNIPER→STANDARD/CORE/SNIPE).
 *   §8    — MemeExecutionIntent6621.validateTicketRestore6621 rejects
 *           cross-lane / cross-version ticket restores.
 *   §11   — canonicaliseLane6621 boundary parser normalises legacy
 *           aliases (BLUE_CHIP, SHIT_COIN, SNIPE) at the seal moment.
 */
class Aate6621MemeExecutionIntentContractCoverageTest {

    @After
    fun tearDown() {
        try { com.lifecyclebot.engine.truth.MemeExecutionIntent6621.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.BuyResult6621.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.PositionEntryLaneRegistry6621.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.MemeEntryCoordinator6621.resetForTest() } catch (_: Throwable) {}
    }

    @Test
    fun aate6621_intent_authority_exists_with_required_immutability() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/MemeExecutionIntent6621.kt"
        ).readText()
        assertTrue(
            "V5.0.6621: MemeExecutionIntent6621 object must exist",
            src.contains("object MemeExecutionIntent6621")
        )
        assertTrue(
            "V5.0.6621: Intent data class must be fully immutable (all val)",
            src.contains("data class Intent(") &&
                listOf("attemptId", "candidateId", "candidateVersion", "mint",
                    "symbol", "lane", "mode", "side", "fdgVerdict",
                    "requestedSol", "sealedSol", "createdAtMs")
                    .all { src.contains("val $it") }
        )
        assertTrue(
            "V5.0.6621: canonicaliseLane6621 must exist as §11 boundary parser",
            src.contains("fun canonicaliseLane6621(raw: String)") &&
                src.contains("\"BLUE_CHIP\"") && src.contains("\"BLUECHIP\"") &&
                src.contains("\"SNIPE\"") && src.contains("\"PROJECT_SNIPER\"")
        )
    }

    @Test
    fun aate6621_seal_emits_normalised_lane_and_authority_version() {
        val i = com.lifecyclebot.engine.truth.MemeExecutionIntent6621
        val intent = i.seal6621(
            candidateId = "candA",
            mint = "MintA",
            symbol = "AAA",
            rawLane = "BLUE_CHIP",   // legacy alias
            mode = com.lifecyclebot.engine.truth.MemeExecutionIntent6621.ExecutionMode.PAPER,
            side = com.lifecyclebot.engine.truth.MemeExecutionIntent6621.Side.BUY,
            fdgVerdict = "BUY",
            requestedSol = 0.5,
            sealedSol = 0.45,
        )
        assertEquals("V5.0.6621: BLUE_CHIP must normalise to BLUECHIP at seal", "BLUECHIP", intent.lane)
        assertTrue("V5.0.6621: attemptId must be assigned", intent.attemptId.isNotBlank())
        assertNotNull("V5.0.6621: byAttemptId must retrieve the sealed intent",
            i.byAttemptId(intent.attemptId))
    }

    @Test
    fun aate6621_ticket_restore_rejects_cross_lane() {
        val i = com.lifecyclebot.engine.truth.MemeExecutionIntent6621
        val intent = i.seal6621(
            candidateId = "candB", mint = "MintB", symbol = "BBB",
            rawLane = "PROJECT_SNIPER",
            mode = com.lifecyclebot.engine.truth.MemeExecutionIntent6621.ExecutionMode.PAPER,
            side = com.lifecyclebot.engine.truth.MemeExecutionIntent6621.Side.BUY,
            fdgVerdict = "BUY", requestedSol = 0.2, sealedSol = 0.2,
        )
        // Same lane, same ver → match
        val okSame = i.validateTicketRestore6621(intent.attemptId, "MintB",
            "PROJECT_SNIPER", intent.candidateVersion)
        assertTrue("V5.0.6621: identical-dimension ticket must match", okSame)
        // Cross-lane → refuse
        val badLane = i.validateTicketRestore6621(intent.attemptId, "MintB",
            "STANDARD", intent.candidateVersion)
        assertFalse("V5.0.6621: cross-lane ticket must be refused", badLane)
        // Version drift → refuse
        val badVer = i.validateTicketRestore6621(intent.attemptId, "MintB",
            "PROJECT_SNIPER", intent.candidateVersion + 999L)
        assertFalse("V5.0.6621: cross-version ticket must be refused", badVer)
    }

    @Test
    fun aate6621_buy_result_sealed_and_premature_probe() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/BuyResult6621.kt"
        ).readText()
        assertTrue(
            "V5.0.6621: BuyResult6621.Outcome sealed with Opened/Rejected/Failed",
            src.contains("sealed interface Outcome") &&
                src.contains("data class Opened(") &&
                src.contains("data class Rejected(") &&
                src.contains("data class Failed(")
        )
        val r = com.lifecyclebot.engine.truth.BuyResult6621
        val att = "attempt-x-6621"
        val premature = r.assertOpenedOrCountPremature6621(att, "MOONSHOT", "addPosition")
        assertFalse("V5.0.6621: no outcome recorded yet → premature must be false", premature)
        r.record6621(com.lifecyclebot.engine.truth.BuyResult6621.Outcome.Opened(
            attemptId = att, positionId = "pos-x-6621",
            fillPrice = 0.001, filledSol = 0.10, lane = "MOONSHOT",
        ))
        val ok = r.assertOpenedOrCountPremature6621(att, "MOONSHOT", "addPosition")
        assertTrue("V5.0.6621: after Opened recorded, premature probe returns true", ok)
    }

    @Test
    fun aate6621_position_entry_lane_registry_seals_and_refuses_rewrite() {
        val reg = com.lifecyclebot.engine.truth.PositionEntryLaneRegistry6621
        val sealed = reg.seal6621("pos-y", "PROJECT_SNIPER")
        assertEquals("V5.0.6621: entry lane must be sealed on first call", "PROJECT_SNIPER", sealed)
        // Attempt a rewrite — must return the sealed lane, not the rewrite value.
        val rewrite = reg.seal6621("pos-y", "MOONSHOT")
        assertEquals("V5.0.6621: entry lane must refuse rewrite; original stays", "PROJECT_SNIPER", rewrite)
        assertEquals("V5.0.6621: entryLane6621 read returns the sealed value",
            "PROJECT_SNIPER", reg.entryLane6621("pos-y"))
    }

    @Test
    fun aate6621_aliasing_probe_detects_forbidden_routes() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/MemeEntryCoordinator6621.kt"
        ).readText()
        assertTrue(
            "V5.0.6621: coordinator must expose submitMemeSpecialistEntry6621 canonical funnel",
            src.contains("fun submitMemeSpecialistEntry6621(") &&
                src.contains("MEME_ENTRY_COORDINATOR_SUBMIT_6621")
        )
        assertTrue(
            "V5.0.6621: coordinator must probe specialist→wrong-executor aliasing",
            src.contains("fun probeAliasing6621(") &&
                src.contains("QUALITY") && src.contains("BLUECHIPBUY") &&
                src.contains("MANIPULATED") && src.contains("SHITCOINBUY") &&
                src.contains("EXPRESS") &&
                src.contains("SPECIALIST_EXECUTOR_ALIAS_ATTEMPT_6621")
        )
    }
}
