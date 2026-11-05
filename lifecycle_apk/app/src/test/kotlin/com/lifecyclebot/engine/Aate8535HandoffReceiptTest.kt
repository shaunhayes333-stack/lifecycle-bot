package com.lifecyclebot.engine

import com.lifecyclebot.engine.OwnershipChain8364.Step8364
import com.lifecyclebot.engine.market.DipHistory8535
import com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8535 — audit P0-08 (receiver chain receipts: fired -> final -> offered -> FDG seen, no silent lost work),
 * P0-09 (the phase read re-deferred a witnessed launch), P0-10 (no observed high read as a 0 % dip).
 */
class Aate8535HandoffReceiptTest {

    // ── P0-08 ──

    @Test fun a_native_eligible_candidate_that_nobody_evaluates_ends_as_a_named_loss_at_its_furthest_stage() {
        OwnershipChain8364.resetForTests8364()
        val lane = "QUALITY"; val mint = "chain8535-$lane"; val v = 8535L
        OwnershipChain8364.onStage8535(Step8364.NATIVE_FIRED, lane, mint, v, 1_000L)
        OwnershipChain8364.onStage8535(Step8364.READY_FINAL, lane, mint, v, 1_000L)
        OwnershipChain8364.onStage8535(Step8364.OWNER_OFFERED, lane, mint, v, 1_000L)
        val steps = OwnershipChain8364.steps8438(lane)
        assertEquals(1L, steps["NATIVE_FIRED"]); assertEquals(1L, steps["READY_FINAL"]); assertEquals(1L, steps["OWNER_OFFERED"])
        OwnershipChain8364.flushForTests8364()
        assertEquals("lost at the furthest stage it reached", 1L, OwnershipChain8364.lostNoTerminal8364()["$lane|OWNER_OFFERED"])
        assertTrue(OwnershipChain8364.reportLine8364(lane)!!.contains("ownerOffered=1"))
    }

    @Test fun the_fdg_seeing_a_candidate_is_a_stage_and_its_verdict_the_terminal() {
        OwnershipChain8364.resetForTests8364()
        val lane = "BLUECHIP"; val mint = "chain8535-$lane"; val v = 8536L
        OwnershipChain8364.onStage8535(Step8364.OWNER_OFFERED, lane, mint, v, 1_000L)
        OwnershipChain8364.onFdgVerdict8364(lane, mint, v, allow = false, reason = "ENTRY_NO_RECLAIM_8506", nowMs = 2_000L)
        val steps = OwnershipChain8364.steps8438(lane)
        assertEquals(1L, steps["FDG_SEEN"]); assertEquals(1L, steps["FDG_BLOCK"])
        OwnershipChain8364.flushForTests8364()
        assertNull("a refused candidate is not lost work", OwnershipChain8364.lostNoTerminal8364()["$lane|OWNER_OFFERED"])
        // a data deferral is seen too, and a close ends the chain
        val m2 = "chain8535-$lane-2"
        OwnershipChain8364.onStage8535(Step8364.OWNER_OFFERED, lane, m2, v, 1_000L)
        OwnershipChain8364.onFdgVerdict8364(lane, m2, v, allow = false, reason = "DATA_INCOMPLETE_DEFER_8136", nowMs = 2_000L)
        assertEquals(2L, OwnershipChain8364.steps8438(lane)["FDG_SEEN"])
        OwnershipChain8364.onStage8535(Step8364.CLOSE, lane, m2, v, 3_000L)
        assertEquals(1L, OwnershipChain8364.steps8438(lane)["CLOSE"])
        assertTrue(Step8364.NATIVE_FIRED.ordinal < Step8364.QUALIFIED.ordinal && Step8364.FDG_SEEN.ordinal < Step8364.FDG_ALLOW.ordinal && Step8364.EXEC.ordinal < Step8364.CLOSE.ordinal)
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        assertTrue(bridge.contains("OwnershipChain8364.onStage8535(OwnershipChain8364.Step8364.NATIVE_FIRED"))
        assertTrue(bridge.contains("if (ownable8535) OwnershipChain8364.onStage8535(OwnershipChain8364.Step8364.OWNER_OFFERED"))
    }

    // ── P0-09 ──

    @Test fun the_phase_read_takes_the_proven_witness_age_and_never_reads_an_unknown_birth_as_late() {
        val now = 1_000_000L
        assertEquals(60_000L to true, LaunchPhaseAuthority7401.birthRead8535(now - 60_000L, null, now))
        assertEquals("canonical birth wins over a witness", 60_000L to true, LaunchPhaseAuthority7401.birthRead8535(now - 60_000L, 5_000L, now))
        assertEquals("a witnessed in-window age is the birth", 45_000L to true, LaunchPhaseAuthority7401.birthRead8535(null, 45_000L, now))
        assertEquals(Long.MAX_VALUE to false, LaunchPhaseAuthority7401.birthRead8535(null, null, now))
        assertEquals(Long.MAX_VALUE to false, LaunchPhaseAuthority7401.birthRead8535(null, -1L, now))
        val sniper = File("src/main/kotlin/com/lifecyclebot/v3/scoring/ProjectSniperAI.kt").readText()
        assertTrue(sniper.contains("LaunchPhaseAuthority7401.snapshot(ts, provenAgeMs8535 = resolvedAgeMs7440)"))
        val phase = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchPhaseAuthority7401.kt").readText()
        assertTrue("a cached unresolved read does not outlive a proven witness", phase.contains("(provenAgeMs8535 == null || cached7408.snapshot.birthResolved)"))
    }

    // ── P0-10 ──

    @Test fun no_observed_high_is_a_wait_or_a_dated_row_reference_never_a_zero_dip() {
        DipHistory8535.clearForTests()
        assertEquals(DipHistory8535.State.WAIT, DipHistory8535.stateOf(null, 8, null, 1.0))
        assertEquals("the bounce witness needs the bot's own tape", DipHistory8535.State.WAIT, DipHistory8535.stateOf(2.0, DipHistory8535.MIN_BOUNCE_POINTS - 1, null, 1.0))
        assertEquals(DipHistory8535.State.OBSERVED_HIGH, DipHistory8535.stateOf(2.0, 4, null, 1.0))
        DipHistory8535.noteRow("m", 1.0, -8.0, -30.0, 10_000L, "jupiter")
        val ref = DipHistory8535.ref("m", 20_000L)
        assertNotNull(ref)
        assertEquals("the price before the larger known drawdown", 1.0 / 0.70, DipHistory8535.referenceHigh(ref, 1.0)!!, 1e-9)
        assertEquals(DipHistory8535.State.ROW_REFERENCE, DipHistory8535.stateOf(null, 4, ref, 1.0))
        assertNull("a row without a drawdown implies nothing", DipHistory8535.referenceHigh(DipHistory8535.RowRef(1.0, 5.0, 12.0, 0L, "x"), 1.0))
        assertNull("a reference below the current price is nothing to reclaim", DipHistory8535.referenceHigh(ref, 5.0))
        assertNull("the reference expires", DipHistory8535.ref("m", 10_000L + 31 * 60_000L))
        assertFalse(DipHistory8535.WAIT_REASON.contains("NOT_A_DIP"))
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        assertTrue(bridge.contains("DipHistory8535.stateOf(nativeHistory8521.high, nativeHistory8521.bouncePrices.size, dipRef8535, price)"))
        assertTrue(bridge.contains("DataDeferFetch8180.request8180(ts, listOf(\"history:UNOBSERVED\"), now)"))   // the per-mint data request (RunnerCapture8180.kt)
        assertFalse("the 0 % fallback is gone", bridge.contains("val high = max(price, nativeHistory8521.high ?: price)"))
    }
}
