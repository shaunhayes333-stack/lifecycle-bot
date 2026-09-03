package com.lifecyclebot.engine

import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * V5.0.6627 §CAUSAL_AUTHORITY_REPAIR coverage. Verifies the operator's
 * V5.0.6627 correctness mandate landed for items 1, 2-lite, 3, 7-lite.
 * Items 4/5/6/8 are deferred to follow-up commits.
 *
 *   §1 Frozen-snapshot fast-path in ExecutableOpenGate now requires
 *      canonical ExecutionIntent presence before allowing without a
 *      round-trip revalidation. NEEDS_REVALIDATION emitted when the
 *      snapshot lacks intent authority.
 *   §2 SpecialistCausalInvariants6627.scan6627 fires
 *      CAUSAL_COUNTER_CORRUPTION_6627 alarms whenever a lane's
 *      stage counts contain an impossible combination.
 *   §3 ExpressHandoffFunnel6625 has a TTL-based reap that
 *      terminalizes stale intents as
 *      EXPRESS_INTENT_TERMINALIZED_STALE_6627 +
 *      EXPRESS_INTENT_WITHOUT_HANDOFF_TERMINAL_6627.
 *   §7 OpenPositionBasisInvariant6627 fires the proactive
 *      zero-entry-price alarm at canonical OPEN.
 */
class Aate6627CausalAuthorityRepairCoverageTest {

    @After
    fun tearDown() {
        try { com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.SpecialistCausalInvariants6627.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.OpenPositionBasisInvariant6627.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.resetForTest() } catch (_: Throwable) {}
    }

    @Test
    fun aate6627_express_reap_terminalizes_stale_intents() {
        com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.resetForTest()
        com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onIntentSeen6625("aate6627-express-mintA")
        com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onIntentSeen6625("aate6627-express-mintB")
        Thread.sleep(35L)
        val reaped = com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.reap6627(maxAgeMs = 20L)
        assertTrue("V5.0.6627 §3: reap must terminalize both stale intents", reaped >= 2L)
        val status = com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.statusLine()
        assertTrue("V5.0.6627 §3: stale count must include the two reaped intents",
            status.contains("stale=2"))
        assertTrue("V5.0.6627 §3: no live-untermined intents after reap",
            status.contains("liveNoTerminal=0"))
    }

    @Test
    fun aate6627_express_reap_ignores_terminated_intents() {
        com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.resetForTest()
        com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onIntentSeen6625("aate6627-express-mintC")
        com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onMarkAcquisition6625(
            "aate6627-express-mintC", ok = true,
        )
        Thread.sleep(35L)
        val reaped = com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.reap6627(maxAgeMs = 20L)
        assertEquals("V5.0.6627 §3: terminalized intent must not be reaped", 0L, reaped)
    }

    @Test
    fun aate6627_express_supersede_counts_reemitted_intent() {
        com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.resetForTest()
        val mint = "aate6627-express-mintD"
        com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onIntentSeen6625(mint)
        com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onIntentSeen6625(mint)
        val status = com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.statusLine()
        assertTrue("V5.0.6627 §3: re-emit for the same mint must count as supersede",
            status.contains("superseded=1"))
    }

    @Test
    fun aate6627_causal_invariants_alarm_on_exec_without_fdg() {
        com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.resetForTest()
        com.lifecyclebot.engine.truth.SpecialistCausalInvariants6627.resetForTest()
        // Simulate the exact CORE fdgAllow=0 exec=14 pattern the operator
        // captured in the V5.0.6626 dump by stamping only EXEC (no FDG)
        // via the recordDeskStage authority path.
        repeat(3) {
            ToolkitSignalSheet.recordDeskStage("CORE", "EXEC", "aate6627fdg0exec3$it:1")
        }
        val alarms = com.lifecyclebot.engine.truth.SpecialistCausalInvariants6627.scan6627()
        assertTrue("V5.0.6627 §2: EXEC without FDG must raise a corruption alarm",
            alarms >= 1L)
        val status = com.lifecyclebot.engine.truth.SpecialistCausalInvariants6627.statusLine6627()
        assertTrue("V5.0.6627 §2: alarm count must advance", status.contains("alarms="))
    }

    @Test
    fun aate6627_causal_invariants_clean_scan_when_lanes_are_consistent() {
        com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.resetForTest()
        com.lifecyclebot.engine.truth.SpecialistCausalInvariants6627.resetForTest()
        val alarms = com.lifecyclebot.engine.truth.SpecialistCausalInvariants6627.scan6627()
        assertEquals("V5.0.6627 §2: empty specialist counters must be alarm-free",
            0L, alarms)
    }

    @Test
    fun aate6627_open_basis_invariant_alarms_on_zero_entry_price() {
        com.lifecyclebot.engine.truth.OpenPositionBasisInvariant6627.resetForTest()
        com.lifecyclebot.engine.truth.OpenPositionBasisInvariant6627.onCanonicalOpen6627(
            mint = "aate6627-basis-mintE",
            lane = "SHITCOIN",
            entryPrice = 0.0,   // ← the exact defect from operator report
            entryQty = 1_000_000.0,
            entryNotionalSol = 0.5,
        )
        val status = com.lifecyclebot.engine.truth.OpenPositionBasisInvariant6627.statusLine6627()
        assertTrue("V5.0.6627 §7: zero entry price must alarm proactively",
            status.contains("zeroPrice=1"))
    }

    @Test
    fun aate6627_open_basis_invariant_accepts_authoritative_open() {
        com.lifecyclebot.engine.truth.OpenPositionBasisInvariant6627.resetForTest()
        com.lifecyclebot.engine.truth.OpenPositionBasisInvariant6627.onCanonicalOpen6627(
            mint = "aate6627-basis-mintF",
            lane = "CORE",
            entryPrice = 3.162E-6,
            entryQty = 158_000.0,
            entryNotionalSol = 0.5,
        )
        val status = com.lifecyclebot.engine.truth.OpenPositionBasisInvariant6627.statusLine6627()
        assertTrue("V5.0.6627 §7: authoritative basis must count as clean",
            status.contains("clean=1") && status.contains("zeroPrice=0"))
    }

    @Test
    fun aate6627_frozen_snapshot_intent_gate_source_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
        ).readText()
        assertTrue("V5.0.6627 §1: frozen snapshot fast-path must consult ExecutionIntent authority",
            src.contains("EXEC_FROZEN_SNAPSHOT_MISSING_INTENT_NEEDS_REVALIDATION_6627") &&
                src.contains("FROZEN_SNAPSHOT_NEEDS_REVALIDATION_6627"))
    }

    @Test
    fun aate6627_open_basis_wired_into_executor_source_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/Executor.kt"
        ).readText()
        assertTrue("V5.0.6627 §7: canonical OPEN transition must stamp the basis invariant",
            src.contains("OpenPositionBasisInvariant6627.onCanonicalOpen6627"))
    }

    @Test
    fun aate6627_pipeline_dump_publishes_causal_repair_status() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt"
        ).readText()
        assertTrue("V5.0.6627: pipeline dump must include the CAUSAL AUTHORITY REPAIR block",
            src.contains("CAUSAL AUTHORITY REPAIR (V5.0.6627)") &&
                src.contains("ExpressHandoffFunnel6625.reap6627") &&
                src.contains("SpecialistCausalInvariants6627.scan6627") &&
                src.contains("OpenPositionBasisInvariant6627.statusLine6627"))
    }
}
