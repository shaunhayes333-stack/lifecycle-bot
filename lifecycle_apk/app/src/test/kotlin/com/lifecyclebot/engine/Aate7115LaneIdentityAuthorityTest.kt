package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.7115 §ONE_LANE_IDENTITY.
 *
 * The 5.0.7113 device snapshot measured 1,251 SEALED_INTENT_REJECTED_LANE_MISMATCH_7096
 * against 66 EXEC_GATE allows. Cause: ExecutableOpenGate carried a private second
 * copy of CanonicalLaneIdentity6506's alias table which folded
 * CASHGEN -> TREASURY. The producer sealed an intent's `canonicalLane` as
 * "CASHGEN" and the consumer looked for it as "TREASURY", so no CASHGEN entry
 * could ever match its own sealed authority.
 *
 * These tests pin the three things that must stay true: the authority knows
 * every alias the copies knew, it merges neither pair the operator's §11 rule
 * forbids, and the gate no longer treats two distinct executable lanes as one.
 */
class Aate7115LaneIdentityAuthorityTest {

    @Before fun setup() {
        ExecutableOpenGate.resetForTests()
        LaneExecutionCoordinator.resetForTests()
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(true)
        RuntimeModeAuthority.publishExecutorMode(true)
        RuntimeModeAuthority.publishPipelineMode(true)
    }

    @After fun cleanup() { ExecutableOpenGate.resetForTests() }

    // ── THE §11 RULE ──────────────────────────────────────────────────────────

    @Test fun `cashgen is never folded into treasury`() {
        assertEquals("CASHGEN", CanonicalLaneIdentity6506.canonical("CASHGEN"))
        assertEquals("CASHGEN", CanonicalLaneIdentity6506.canonical("CASH_GENERATION"))
        assertEquals("CASHGEN", CanonicalLaneIdentity6506.canonical("cash generation"))
        assertEquals("CASHGEN", CanonicalLaneIdentity6506.canonical("CASH-GEN"))
        assertEquals("TREASURY", CanonicalLaneIdentity6506.canonical("TREASURY"))
        assertFalse(CanonicalLaneIdentity6506.sameLane("CASHGEN", "TREASURY"))
    }

    @Test fun `core standard and v3 core stay three distinct lanes`() {
        val core = CanonicalLaneIdentity6506.canonical("CORE")
        val std = CanonicalLaneIdentity6506.canonical("STANDARD")
        val v3c = CanonicalLaneIdentity6506.canonical("V3_CORE")
        assertEquals("CORE", core)
        assertEquals("STANDARD", std)
        assertEquals("V3_CORE", v3c)
        assertEquals(3, setOf(core, std, v3c).size)
    }

    // ── THE UNION OF THE THIRTEEN COPIES ──────────────────────────────────────

    @Test fun `authority knows every alias the hand copies knew`() {
        val expected = mapOf(
            "BLUE_CHIP" to "BLUECHIP",
            "BLUE-CHIP" to "BLUECHIP",
            "BLUE CHIP" to "BLUECHIP",
            "MOON_SHOT" to "MOONSHOT",
            "MOON-SHOT" to "MOONSHOT",
            "moon shot" to "MOONSHOT",
            "SHIT_COIN" to "SHITCOIN",
            "SHIT COIN" to "SHITCOIN",
            "MANIP" to "MANIPULATED",
            "DIP" to "DIP_HUNTER",
            "DIPHUNTER" to "DIP_HUNTER",
            "SNIPER" to "PROJECT_SNIPER",
            "PROJECT" to "PROJECT_SNIPER",
            "SNIPE" to "PROJECT_SNIPER",
            "PROJECTSNIPER" to "PROJECT_SNIPER",
            "PROJECT-SNIPER" to "PROJECT_SNIPER",
            "PRESALE_SNIPE" to "PROJECT_SNIPER",
            "MICRO_CAP" to "MICRO",
            "MICROCAP" to "MICRO",
            "CASH_GENERATION" to "CASHGEN",
            "CASH_GEN" to "CASHGEN",
        )
        expected.forEach { (raw, canon) ->
            assertEquals("alias $raw", canon, CanonicalLaneIdentity6506.canonical(raw))
        }
    }

    @Test fun `canonicalisation is idempotent and separator insensitive`() {
        val lanes = listOf(
            "CORE", "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED",
            "TREASURY", "CASHGEN", "SHITCOIN", "QUALITY", "BLUECHIP", "EXPRESS",
            "CYCLIC", "STANDARD", "V3_CORE", "MICRO",
            // aliases too — folding twice must not move again
            "BLUE_CHIP", "SHIT COIN", "MANIP", "DIP", "SNIPER", "CASH_GENERATION",
            "MOON-SHOT", "MICROCAP", "PRESALE_SNIPE",
        )
        lanes.forEach { raw ->
            val once = CanonicalLaneIdentity6506.canonical(raw)
            assertEquals("idempotent for $raw", once, CanonicalLaneIdentity6506.canonical(once))
        }
    }

    @Test fun `every executable lane is a fixed point`() {
        // MemeOwnershipInvariant6620's executable set. A canonicaliser that
        // REWRITES an executable lane name is the 7115 defect: the name the
        // ownership authority uses would not survive a round trip through the
        // identity authority, so a sealed intent could not be matched to it.
        listOf(
            "CORE", "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER",
            "MANIPULATED", "TREASURY", "CASHGEN",
        ).forEach { lane ->
            assertEquals("executable lane $lane must survive canonicalisation",
                lane, CanonicalLaneIdentity6506.canonical(lane))
        }
    }

    @Test fun `blank and null fold to empty rather than to a lane`() {
        assertEquals("", CanonicalLaneIdentity6506.canonical(null))
        assertEquals("", CanonicalLaneIdentity6506.canonical("   "))
        assertFalse(CanonicalLaneIdentity6506.sameLane("", ""))
    }

    // ── THE GATE NO LONGER CONFLATES TWO LANES ────────────────────────────────

    @Test fun `gate stops treating cashgen and treasury as one lane`() {
        // Before 7115 both sides of this comparison folded to TREASURY, so the
        // gate answered true — two distinct executable specialists were one lane
        // as far as every lane-identity check in the file was concerned.
        assertFalse(ExecutableOpenGate.lanesCompatibleForTests("TREASURY", "CASHGEN"))
        assertFalse(ExecutableOpenGate.lanesCompatibleForTests("CASHGEN", "TREASURY"))
        assertTrue(ExecutableOpenGate.lanesCompatibleForTests("CASHGEN", "CASHGEN"))
        assertTrue(ExecutableOpenGate.lanesCompatibleForTests("CASHGEN", "CASH_GENERATION"))
    }

    @Test fun `gate now knows the aliases only the boundary knew`() {
        // The gate's private copy did not carry MOON_SHOT or MICRO_CAP, so on the
        // paths that bypass canOpenExecutablePosition's boundary fold it saw them
        // as unrelated lane names.
        assertTrue(ExecutableOpenGate.lanesCompatibleForTests("MOONSHOT", "MOON_SHOT"))
        assertTrue(ExecutableOpenGate.lanesCompatibleForTests("MOONSHOT", "MOON-SHOT"))
    }

    // ── THE SEALING SITE, WHICH IS WHAT THE 1251 EVENTS WERE ──────────────────

    @Test fun `sealed intent lane is canonical not merely uppercased`() {
        val mint = "LaneId7115${System.nanoTime()}"
        val cv = LaneExecutionCoordinator.candidateVersionFor(mint)
        val intent = ExecutableOpenGate.recordFdgAndGetIntent6533(
            mint = mint,
            symbol = "LANE7115",
            lane = "CASH_GENERATION",   // a legacy alias, as an upstream producer spells it
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = 90,
            safetyTier = "SAFE",
            liquidityUsd = 5_000.0,
            preFdgVerdict = "BUY",
            candidateVersion = cv,
            entryScore = 82,
        )
        assertNotNull(intent)
        // Before 7115 this field was `lane.uppercase()` and would read
        // "CASH_GENERATION", while the consumer looked for canonicalLane(lane).
        // The field is NAMED canonicalLane; it must hold a canonical lane.
        assertEquals("CASHGEN", intent!!.canonicalLane)
        assertEquals(intent, ExecutableOpenGate.activeExecutionIntent6519("PAPER", mint, cv))
    }

    // ── THE COPIES ARE GONE ───────────────────────────────────────────────────

    @Test fun `the gate no longer carries its own alias table`() {
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val body = gate.substringAfter("private fun canonicalLane(lane: String)")
            .substringBefore("private val SOURCE_BUCKET_LANES_6871")
        assertTrue("canonicalLane must delegate to the authority",
            body.contains("CanonicalLaneIdentity6506.canonical"))
        // The fold that caused the 1,251 events, and the §11-forbidden merge.
        assertFalse(gate.contains("\"CASHGEN\", \"CASH_GENERATION\" -> \"TREASURY\""))
    }
}
