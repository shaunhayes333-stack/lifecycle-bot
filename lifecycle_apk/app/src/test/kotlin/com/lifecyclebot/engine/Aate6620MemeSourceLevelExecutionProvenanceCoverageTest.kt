package com.lifecyclebot.engine

import org.junit.Test
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * V5.0.6620 — MEME_SOURCE_LEVEL_EXECUTION_PROVENANCE (Slice 1)
 *
 * Operator directive Feb 2026: repair the architectural corruption
 * between specialist decision → owner lane → FDG → immutable execution
 * intent → size/mark → execution ticket → executor → canonical position
 * → exit confirmation → finalization/learning. This slice lands three
 * SOURCE-LEVEL SEALS:
 *
 *   §7  — Ownership gate: STANDARD/V3_CORE cannot execute when a
 *         specialist owns the candidate. Authority owner is promoted
 *         at the executor entry site (Executor.paperBuy) via
 *         MemeOwnershipInvariant6620.resolveExecutorLane6620.
 *
 *   §9  — candidateVersion authority: the two pre-6620 wall-clock
 *         sources (CanonicalSizingBridge6532.kt:47 default,
 *         BotService.kt:21279) are eliminated in favour of
 *         LaneExecutionCoordinator.candidateVersionFor(mint).
 *
 *   §12 — Sell finality: MemeSellFinality6620.awaitConfirmationOrKeepOpen
 *         is the receiver every MemeTrader exit routes through — state
 *         release / registry closure / exposure clear / learning arm
 *         happen ONLY inside the onConfirmed block. Broad rollout is
 *         Slice 2; the receiver + counters ship in this slice.
 *
 * Slices 2 (§2/§5/§6 transactional BuyResult + coordinator + immutable
 * entryLane / §4 remove specialist→wrong-executor aliasing) and 3
 * (§10 V3 soft opinion + §11 canonical MemeLane enum + §13 remove
 * post-hoc healing) build on this slice.
 */
class Aate6620MemeSourceLevelExecutionProvenanceCoverageTest {

    @After
    fun tearDown() {
        try { com.lifecyclebot.engine.truth.MemeOwnershipInvariant6620.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.MemeSellFinality6620.resetForTest() } catch (_: Throwable) {}
    }

    @Test
    fun aate6620_ownership_invariant_module_exists_with_required_api() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/MemeOwnershipInvariant6620.kt"
        ).readText()
        assertTrue(
            "V5.0.6620: MemeOwnershipInvariant6620 object must exist",
            src.contains("object MemeOwnershipInvariant6620")
        )
        assertTrue(
            "V5.0.6620: canonical specialist set must include every MemeTrader lane operator directive §1 lists",
            listOf(
                "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS",
                "CORE", "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER",
                "MANIPULATED", "TREASURY", "CASHGEN",
            ).all { src.contains("\"$it\"") }
        )
        assertTrue(
            "V5.0.6620: observer-only lanes STANDARD + V3_CORE must be sealed against specialist ownership theft",
            src.contains("\"STANDARD\"") && src.contains("\"V3_CORE\"")
        )
        assertTrue(
            "V5.0.6620: resolveExecutorLane6620 must emit PAPER_BUY_STANDARD_ON_SPECIALIST_OWNED_6620 + LANE_EXEC_WITHOUT_SAME_LANE_CANONICAL_INTENT_6620",
            src.contains("fun resolveExecutorLane6620(") &&
                src.contains("PAPER_BUY_STANDARD_ON_SPECIALIST_OWNED_6620") &&
                src.contains("LANE_EXEC_WITHOUT_SAME_LANE_CANONICAL_INTENT_6620")
        )
    }

    @Test
    fun aate6620_observer_lane_blocked_when_specialist_owns() {
        val inv = com.lifecyclebot.engine.truth.MemeOwnershipInvariant6620
        val res = inv.resolveExecutorLane6620(
            mint = "MintPengu",
            symbol = "PENGU",
            derivedLane = "STANDARD",
            authorityOwnerLane = "PROJECT_SNIPER",
        )
        assertEquals("V5.0.6620: authority PROJECT_SNIPER must be promoted", "PROJECT_SNIPER", res.lane)
        assertTrue("V5.0.6620: observer STANDARD must be flagged blocked", res.observerBlocked)
        assertTrue("V5.0.6620: promotion flag must fire", res.promoted)
    }

    @Test
    fun aate6620_matching_specialist_is_happy_path() {
        val inv = com.lifecyclebot.engine.truth.MemeOwnershipInvariant6620
        val res = inv.resolveExecutorLane6620(
            mint = "MintMoon",
            symbol = "MOON",
            derivedLane = "MOONSHOT",
            authorityOwnerLane = "MOONSHOT",
        )
        assertEquals("V5.0.6620: matching lanes must return the specialist verbatim", "MOONSHOT", res.lane)
        assertFalse("V5.0.6620: happy path must NOT be promoted", res.promoted)
        assertFalse("V5.0.6620: happy path must NOT flag observer block", res.observerBlocked)
    }

    @Test
    fun aate6620_cross_specialist_rewrite_promotes_authority() {
        val inv = com.lifecyclebot.engine.truth.MemeOwnershipInvariant6620
        val res = inv.resolveExecutorLane6620(
            mint = "MintCross",
            symbol = "XYZ",
            derivedLane = "SHITCOIN",           // caller thinks SHITCOIN
            authorityOwnerLane = "PROJECT_SNIPER", // authority owns as PROJECT_SNIPER
        )
        assertEquals("V5.0.6620: cross-specialist rewrite must return the authority owner",
            "PROJECT_SNIPER", res.lane)
        assertTrue("V5.0.6620: cross-specialist rewrite must be marked promoted", res.promoted)
    }

    @Test
    fun aate6620_paperBuy_uses_ownership_resolver_and_promotes_authority() {
        val exec = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/Executor.kt"
        ).readText()
        assertTrue(
            "V5.0.6620: Executor.paperBuy must route derived lane through resolveExecutorLane6620",
            exec.contains("MemeOwnershipInvariant6620") &&
                exec.contains(".resolveExecutorLane6620(ts.mint, ts.symbol, derivedLane6620, authorityOwner6620)")
        )
        assertTrue(
            "V5.0.6620: paperBuy must derive authorityOwner6620 from authority6513.executionLane",
            exec.contains("val authorityOwner6620 = authority6513?.executionLane?.uppercase()?.takeIf { it.isNotBlank() }")
        )
        assertTrue(
            "V5.0.6620: gateLane6451 must be sourced from the ownership resolution (not synthesized directly)",
            exec.contains("val gateLane6451 = ownershipResolution6620.lane")
        )
    }

    @Test
    fun aate6620_wallclock_candidateVersion_authority_eliminated() {
        val bridge = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalSizingBridge6532.kt"
        ).readText()
        val bot = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue(
            "V5.0.6620: CanonicalSizingBridge6532 must no longer default candidateVersion to System.currentTimeMillis()",
            !bridge.contains("candidateVersion: Long = System.currentTimeMillis()")
        )
        assertTrue(
            "V5.0.6620: bridge must delegate to LaneExecutionCoordinator.candidateVersionFor(...)",
            bridge.contains("LaneExecutionCoordinator") &&
                bridge.contains(".candidateVersionFor(")
        )
        assertTrue(
            "V5.0.6620: BotService V3 handoff must no longer pass candidateVersion=System.currentTimeMillis()",
            !bot.contains("candidateVersion = System.currentTimeMillis(),")
        )
        assertTrue(
            "V5.0.6620: BotService V3 handoff must use LaneExecutionCoordinator.candidateVersionFor for candidateVersion",
            bot.contains("com.lifecyclebot.engine.LaneExecutionCoordinator") &&
                bot.contains(".candidateVersionFor(ts.mint)")
        )
    }

    @Test
    fun aate6620_sell_finality_module_exists_with_required_api() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/MemeSellFinality6620.kt"
        ).readText()
        assertTrue(
            "V5.0.6620: MemeSellFinality6620 object must exist",
            src.contains("object MemeSellFinality6620")
        )
        assertTrue(
            "V5.0.6620: sell-finality must expose awaitConfirmationOrKeepOpen with the operator's four outcome semantics",
            src.contains("fun awaitConfirmationOrKeepOpen(") &&
                src.contains("Outcome.CONFIRMED") &&
                src.contains("Outcome.PAPER_CONFIRMED") &&
                src.contains("Outcome.PENDING") &&
                src.contains("Outcome.FAILED")
        )
        assertTrue(
            "V5.0.6620: sell-finality must count kept-open + bypass attempts so the operator can grep them",
            src.contains("MEME_SELL_KEPT_OPEN_") &&
                src.contains("MEME_SELL_STATE_RELEASED_WITHOUT_CONFIRMATION_6620")
        )
    }

    @Test
    fun aate6620_kept_open_when_sell_not_confirmed() {
        val gate = com.lifecyclebot.engine.truth.MemeSellFinality6620
        var confirmedRan = false
        val ok = gate.awaitConfirmationOrKeepOpen(
            lane = "MOONSHOT",
            positionId = "pos-6620-a",
            sellOutcome = com.lifecyclebot.engine.truth.MemeSellFinality6620.Outcome.PENDING,
            note = "test",
        ) { confirmedRan = true }
        assertFalse("V5.0.6620: onConfirmed must NOT run on PENDING outcome", confirmedRan)
        assertFalse("V5.0.6620: gate must return false when kept open", ok)
    }

    @Test
    fun aate6620_confirmed_branch_runs_finalizer() {
        val gate = com.lifecyclebot.engine.truth.MemeSellFinality6620
        var confirmedRan = false
        val ok = gate.awaitConfirmationOrKeepOpen(
            lane = "SHITCOIN",
            positionId = "pos-6620-b",
            sellOutcome = com.lifecyclebot.engine.truth.MemeSellFinality6620.Outcome.PAPER_CONFIRMED,
            note = "test",
        ) { confirmedRan = true }
        assertTrue("V5.0.6620: onConfirmed must run on PAPER_CONFIRMED outcome", confirmedRan)
        assertTrue("V5.0.6620: gate must return true when confirmed", ok)
    }
}
