package com.lifecyclebot.engine

import org.junit.Test
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * V5.0.6624 — TICKET AUTHORITY CONTINUITY (P1 of operator's V5.0.6622
 * forensic).
 *
 * Operator: "252 pending specialist intents across only three lanes.
 * Something bumps the authority version. The previously valid intent
 * becomes stale. Rather than cheaply validating that the underlying
 * economics are unchanged and resealing, execution rejects it. Reseal
 * against the current authority version when materially unchanged;
 * reject only if economics or safety genuinely changed."
 */
class Aate6624TicketAuthorityContinuityCoverageTest {

    @After
    fun tearDown() {
        try { com.lifecyclebot.engine.truth.TicketAuthorityContinuity6624.resetForTest() } catch (_: Throwable) {}
    }

    @Test
    fun aate6624_version_drift_only_reseals_the_ticket() {
        val T = com.lifecyclebot.engine.truth.TicketAuthorityContinuity6624
        val original = com.lifecyclebot.engine.truth.TicketAuthorityContinuity6624.MaterialBasis(
            mint = "MintA", lane = "PROJECT_SNIPER",
            side = com.lifecyclebot.engine.truth.MemeExecutionIntent6621.Side.BUY,
            requestedSol = 0.10, safetyOk = true, markSol = 0.001,
        )
        T.recordOriginalBasis6624("att-a", original)
        // Same basis (only version drifted upstream) → reseal
        val ok = T.shouldResealOrReject6624("att-a", original.copy())
        assertTrue("V5.0.6624: version-drift-only must reseal, preserving specialist decision", ok)
    }

    @Test
    fun aate6624_size_drift_beyond_tolerance_rejects() {
        val T = com.lifecyclebot.engine.truth.TicketAuthorityContinuity6624
        val original = com.lifecyclebot.engine.truth.TicketAuthorityContinuity6624.MaterialBasis(
            mint = "MintB", lane = "BLUECHIP",
            side = com.lifecyclebot.engine.truth.MemeExecutionIntent6621.Side.BUY,
            requestedSol = 0.10, safetyOk = true, markSol = 0.001,
        )
        T.recordOriginalBasis6624("att-b", original)
        // Size 20% larger — beyond 5% tolerance
        val bad = original.copy(requestedSol = 0.12)
        assertFalse("V5.0.6624: size drift >5% must be genuine reject",
            T.shouldResealOrReject6624("att-b", bad))
    }

    @Test
    fun aate6624_safety_regression_rejects_regardless_of_size() {
        val T = com.lifecyclebot.engine.truth.TicketAuthorityContinuity6624
        val original = com.lifecyclebot.engine.truth.TicketAuthorityContinuity6624.MaterialBasis(
            mint = "MintC", lane = "MOONSHOT",
            side = com.lifecyclebot.engine.truth.MemeExecutionIntent6621.Side.BUY,
            requestedSol = 0.05, safetyOk = true, markSol = 0.002,
        )
        T.recordOriginalBasis6624("att-c", original)
        val unsafe = original.copy(safetyOk = false)
        assertFalse("V5.0.6624: safety regression must always reject",
            T.shouldResealOrReject6624("att-c", unsafe))
    }

    @Test
    fun aate6624_no_original_basis_returns_false() {
        val T = com.lifecyclebot.engine.truth.TicketAuthorityContinuity6624
        val cur = com.lifecyclebot.engine.truth.TicketAuthorityContinuity6624.MaterialBasis(
            mint = "MintD", lane = "SHITCOIN",
            side = com.lifecyclebot.engine.truth.MemeExecutionIntent6621.Side.BUY,
            requestedSol = 0.05, safetyOk = true, markSol = 0.001,
        )
        assertFalse("V5.0.6624: no original basis captured → reject (never fabricate a reseal)",
            T.shouldResealOrReject6624("att-unseen", cur))
    }

    @Test
    fun aate6624_authority_source_file_exists_with_required_api() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/TicketAuthorityContinuity6624.kt"
        ).readText()
        assertTrue(
            "V5.0.6624: TicketAuthorityContinuity6624 object must exist with MaterialBasis + record + should-reseal API",
            src.contains("object TicketAuthorityContinuity6624") &&
                src.contains("data class MaterialBasis(") &&
                src.contains("fun recordOriginalBasis6624(") &&
                src.contains("fun shouldResealOrReject6624(")
        )
        assertTrue(
            "V5.0.6624: counters TICKET_RESEALED_VERSION_DRIFT_ONLY_6624 + TICKET_GENUINE_ECONOMIC_REJECT_6624 + TICKET_RESEAL_SAFETY_REJECT_6624 must fire",
            src.contains("TICKET_RESEALED_VERSION_DRIFT_ONLY_6624") &&
                src.contains("TICKET_GENUINE_ECONOMIC_REJECT_6624") &&
                src.contains("TICKET_RESEAL_SAFETY_REJECT_6624")
        )
    }
}
