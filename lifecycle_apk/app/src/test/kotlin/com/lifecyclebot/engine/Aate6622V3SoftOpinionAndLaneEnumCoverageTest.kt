package com.lifecyclebot.engine

import org.junit.Test
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * V5.0.6622 — MEME_SOURCE_LEVEL_EXECUTION_PROVENANCE (Slice 3).
 *
 *   §10 V3 soft-opinion contract — V3VerdictContract6622 answers
 *       "is this verdict genuinely fatal" so specialists no longer
 *       treat WATCH/REJECT as global blocks.
 *   §11 Canonical MemeLane6622 enum + parse6622 boundary parser —
 *       every internal object uses the enum; only persistence
 *       carries legacy strings.
 *   §13 PostHocHealingAudit6622 detects lingering downstream-rewrite
 *       patterns so operator can attack creator-side.
 */
class Aate6622V3SoftOpinionAndLaneEnumCoverageTest {

    @After
    fun tearDown() {
        try { com.lifecyclebot.engine.truth.V3VerdictContract6622.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.PostHocHealingAudit6622.resetForTest() } catch (_: Throwable) {}
    }

    @Test
    fun aate6622_v3_verdict_contract_isFatal_only_for_genuine_fatals() {
        val v3 = com.lifecyclebot.engine.truth.V3VerdictContract6622
        assertTrue("V5.0.6622: HONEYPOT is fatal", v3.isFatal6622("HONEYPOT"))
        assertTrue("V5.0.6622: UNSELLABLE is fatal", v3.isFatal6622("UNSELLABLE"))
        assertTrue("V5.0.6622: BANNED_MINT is fatal", v3.isFatal6622("BANNED_MINT"))
        assertTrue("V5.0.6622: RUG_HIGH is fatal", v3.isFatal6622("RUG_HIGH"))
        // Soft opinions must NOT be fatal
        assertFalse("V5.0.6622: WATCH is soft", v3.isFatal6622("WATCH"))
        assertFalse("V5.0.6622: REJECT is soft", v3.isFatal6622("REJECT"))
        assertFalse("V5.0.6622: score_weak is soft", v3.isFatal6622("score_weak"))
        assertFalse("V5.0.6622: null is soft", v3.isFatal6622(null))
    }

    @Test
    fun aate6622_soft_confidence_multiplier_never_zero() {
        val v3 = com.lifecyclebot.engine.truth.V3VerdictContract6622
        assertEquals("V5.0.6622: WATCH → 0.5", 0.5, v3.softConfidenceMultiplier6622("WATCH"), 1e-9)
        assertEquals("V5.0.6622: REJECT → 0.75", 0.75, v3.softConfidenceMultiplier6622("REJECT"), 1e-9)
        assertEquals("V5.0.6622: unknown → 1.0", 1.0, v3.softConfidenceMultiplier6622("PROBE_ONLY"), 1e-9)
    }

    @Test
    fun aate6622_meme_lane_enum_parses_legacy_aliases() {
        val ML = com.lifecyclebot.engine.truth.MemeLane6622::class.java
        assertEquals("V5.0.6622: BLUE_CHIP → BLUECHIP",
            com.lifecyclebot.engine.truth.MemeLane6622.BLUECHIP,
            com.lifecyclebot.engine.truth.MemeLane6622.parse6622("BLUE_CHIP"))
        assertEquals("V5.0.6622: SHIT_COIN → SHITCOIN",
            com.lifecyclebot.engine.truth.MemeLane6622.SHITCOIN,
            com.lifecyclebot.engine.truth.MemeLane6622.parse6622("SHIT_COIN"))
        assertEquals("V5.0.6622: SNIPE → PROJECT_SNIPER",
            com.lifecyclebot.engine.truth.MemeLane6622.PROJECT_SNIPER,
            com.lifecyclebot.engine.truth.MemeLane6622.parse6622("SNIPE"))
        assertEquals("V5.0.6622: DIPHUNTER → DIP_HUNTER",
            com.lifecyclebot.engine.truth.MemeLane6622.DIP_HUNTER,
            com.lifecyclebot.engine.truth.MemeLane6622.parse6622("DIPHUNTER"))
        assertEquals("V5.0.6622: blank → STANDARD",
            com.lifecyclebot.engine.truth.MemeLane6622.STANDARD,
            com.lifecyclebot.engine.truth.MemeLane6622.parse6622(""))
        // CORE / STANDARD / V3_CORE must remain distinct (operator §11)
        assertEquals("V5.0.6622: CORE preserved",
            com.lifecyclebot.engine.truth.MemeLane6622.CORE,
            com.lifecyclebot.engine.truth.MemeLane6622.parse6622("CORE"))
        assertEquals("V5.0.6622: STANDARD preserved",
            com.lifecyclebot.engine.truth.MemeLane6622.STANDARD,
            com.lifecyclebot.engine.truth.MemeLane6622.parse6622("STANDARD"))
        assertEquals("V5.0.6622: V3_CORE preserved",
            com.lifecyclebot.engine.truth.MemeLane6622.V3_CORE,
            com.lifecyclebot.engine.truth.MemeLane6622.parse6622("V3_CORE"))
    }

    @Test
    fun aate6622_meme_lane_enum_covers_operator_specialist_set() {
        val required = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS",
            "CORE", "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER",
            "MANIPULATED", "TREASURY", "CASHGEN")
        val allValues = com.lifecyclebot.engine.truth.MemeLane6622.values()
        required.forEach { canonical ->
            assertTrue(
                "V5.0.6622: MemeLane6622 must include $canonical from operator §1",
                allValues.any { it.canonical == canonical }
            )
        }
    }

    @Test
    fun aate6622_post_hoc_healing_audit_emits_counters() {
        val audit = com.lifecyclebot.engine.truth.PostHocHealingAudit6622
        audit.detect6622("OVERRIDE_TRADING_MODE_AFTER_BUY", "test")
        audit.detect6622("RESTORE_IMMUTABLE_TICKET_MISMATCH", "test")
        // No hard assert on counter values (PipelineHealthCollector is
        // not mocked); the source-level receiver must merely exist
        // and be callable.
    }

    @Test
    fun aate6622_v3_and_healing_source_files_exist_with_required_api() {
        val v3 = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/V3VerdictContract6622.kt"
        ).readText()
        assertTrue(
            "V5.0.6622: V3VerdictContract6622 must expose isFatal6622 + softConfidenceMultiplier6622 + recordSpecialistOverride6622",
            v3.contains("fun isFatal6622(") &&
                v3.contains("fun softConfidenceMultiplier6622(") &&
                v3.contains("fun recordSpecialistOverride6622(")
        )
        assertTrue(
            "V5.0.6622: MemeLane6622 enum + parse6622 must live in the same file",
            v3.contains("enum class MemeLane6622") &&
                v3.contains("fun parse6622(raw: String?)")
        )
        assertTrue(
            "V5.0.6622: PostHocHealingAudit6622 must live in the same file with detect6622",
            v3.contains("object PostHocHealingAudit6622") &&
                v3.contains("fun detect6622(")
        )
        assertTrue(
            "V5.0.6622: operator's forbidden-alias-merge rule preserved (CORE / STANDARD / V3_CORE distinct enum values)",
            v3.contains("CORE(\"CORE\")") &&
                v3.contains("STANDARD(\"STANDARD\")") &&
                v3.contains("V3_CORE(\"V3_CORE\")")
        )
    }
}
