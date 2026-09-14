package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PostSealAuthorityInvariants6760
import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6760 §DIRECT_SOURCE_REPAIR — behavioural regression fence for the
 * five root-cause repairs mandated by V5.0.6759 operator directive:
 *
 *   §1  Phantom-sized derived from unresolved records only.
 *   §2  Cash-starve requires genuine sub-min-executable cash.
 *   §6  Fresh-source promotion failure classified into distinct sub-reasons.
 *   §7  Post-FDG-allow legacy gates demoted to advisory unless hard-safety.
 *
 * Runtime tests exercise the authorities directly; source tests fence the
 * callsite plumbing so a future refactor cannot silently reintroduce the
 * defect.
 */
class Aate6760DirectSourceRepairTest {

    // ---------- §1 PHANTOM_SIZED_AT_SOURCE ---------------------------------

    @Test fun phantom_counts_only_sized_records_without_terminal() {
        val runId = "aate6760-runId"
        val mode = "paper"
        val fresh = SpecialistCausalFunnel6625.CausalKey(
            runId = runId, mode = mode, mint = "MEMESIZED6760",
            lane = "SHITCOIN6760", authorityVersion = 1L, intentId = "INTENT:1",
        )
        // Sized without any terminal — but must age past PHANTOM_TTL_MS_6760
        // to be counted as phantom (records younger than TTL are in-flight,
        // not phantoms — see §PHANTOM_SIZED_AT_SOURCE docblock).
        SpecialistCausalFunnel6625.stamp6625(fresh, Stage.SIZE, "SIZED_EXECUTABLE")
        val agedNow = System.currentTimeMillis() + SpecialistCausalFunnel6625.PHANTOM_TTL_MS_6760 + 1_000L
        val snap1 = SpecialistCausalFunnel6625.laneSnapshot6647(fresh.lane, agedNow)
        assertTrue(
            "sized-without-terminal past TTL must appear in phantomSizedOnly (got ${snap1.phantomSizedOnly})",
            snap1.phantomSizedOnly >= 1,
        )
        // Now emit a TICKET terminal on the SAME causal record — phantom
        // must drop to below the prior count.
        SpecialistCausalFunnel6625.stamp6625(fresh, Stage.TICKET, "TICKET_CREATED")
        val snap2 = SpecialistCausalFunnel6625.laneSnapshot6647(fresh.lane, agedNow)
        assertTrue(
            "TICKET terminal must reduce phantom count (before=${snap1.phantomSizedOnly} after=${snap2.phantomSizedOnly})",
            snap2.phantomSizedOnly < snap1.phantomSizedOnly,
        )
    }

    @Test fun fresh_sized_records_are_in_flight_not_phantoms() {
        // §PHANTOM_SIZED_AT_SOURCE — a candidate sized in the current
        // pump cadence tick is legitimately in-flight; the reap
        // authority terminalizes it if it exceeds TTL.
        val runId = "aate6760-runId-fresh"
        val mode = "paper"
        val fresh = SpecialistCausalFunnel6625.CausalKey(
            runId = runId, mode = mode, mint = "MEMEFRESH6760",
            lane = "MOONSHOT6760", authorityVersion = 1L, intentId = "INTENT:fresh",
        )
        SpecialistCausalFunnel6625.stamp6625(fresh, Stage.SIZE, "SIZED_EXECUTABLE")
        // Immediate snapshot — under TTL, must NOT count as phantom.
        val snap = SpecialistCausalFunnel6625.laneSnapshot6647(fresh.lane, System.currentTimeMillis())
        assertEquals(
            "fresh sized record must not be counted as phantom (still within TTL)",
            0, snap.phantomSizedOnly,
        )
    }

    @Test fun reap_stale_sized_reservations_terminalizes_orphans() {
        val runId = "aate6760-runId-reap"
        val mode = "paper"
        val key = SpecialistCausalFunnel6625.CausalKey(
            runId = runId, mode = mode, mint = "MEMEREAP6760",
            lane = "CYCLIC6760", authorityVersion = 1L, intentId = "INTENT:reap",
        )
        SpecialistCausalFunnel6625.stamp6625(key, Stage.SIZE, "SIZED_EXECUTABLE")
        val phantomBefore = SpecialistCausalFunnel6625.laneSnapshot6647(
            key.lane, System.currentTimeMillis() + SpecialistCausalFunnel6625.PHANTOM_TTL_MS_6760 + 1_000L,
        ).phantomSizedOnly
        assertTrue("must have at least one phantom before reap", phantomBefore >= 1)
        // Advance nowMs beyond TTL — the reservation must be terminalized.
        val sweptCount = SpecialistCausalFunnel6625.reapStaleSizedReservations6760(
            ttlMs = 0L,
            nowMs = System.currentTimeMillis() + 10_000L,
        )
        assertTrue("reap must terminalize at least one stale sized reservation", sweptCount >= 1)
        val snapAfter = SpecialistCausalFunnel6625.laneSnapshot6647(
            key.lane, System.currentTimeMillis() + SpecialistCausalFunnel6625.PHANTOM_TTL_MS_6760 + 1_000L,
        )
        assertTrue(
            "STALE_SIZED_TERMINAL_6760 must appear in the causal outcomes",
            snapAfter.outcomes.keys.any { it == "STALE_SIZED_TERMINAL_6760" },
        )
        assertTrue(
            "phantom count must drop after terminalization (before=$phantomBefore after=${snapAfter.phantomSizedOnly})",
            snapAfter.phantomSizedOnly < phantomBefore,
        )
    }

    // ---------- §2 CASH_STARVED_AT_SOURCE ----------------------------------

    @Test fun cash_starve_no_longer_uses_compound_ratio_and_count_gate() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExitThroughputAuthority6727.kt").readText()
        assertFalse(
            "compound `cashRatio < CASH_STARVE_RATIO && openCount >= POSITION_CAP_HINT` gate must be retired",
            src.contains("cashRatio < CASH_STARVE_RATIO && openCount >= POSITION_CAP_HINT"),
        )
        assertTrue(
            "cash-starve must now be defined by cash < paperExecutableMinimumSol",
            src.contains("paperMinExec6760 = try {") &&
                src.contains("OrderSizeResolver6441.paperExecutableMinimumSol()"),
        )
        assertTrue(
            "cash-starve block must reference the §CASH_STARVED_AT_SOURCE docblock",
            src.contains("§CASH_STARVED_AT_SOURCE"),
        )
    }

    // ---------- §7 POST_SEAL_AUTHORITY_INVARIANTS --------------------------

    @Test fun post_seal_invariants_block_only_hard_safety() {
        // Hard-safety reasons must remain authoritative.
        for (hard in listOf(
            "POSITION_HARD_CAP_EXIT_THROUGHPUT_6727",
            "CASH_STARVED_EXIT_THROUGHPUT_6727",
            "SAFETY_HARD_VETO_RUG",
            "TOKEN_SAFETY_HARD_VETO",
            "CANONICAL_FINALITY_DUPLICATE",
        )) {
            assertTrue(
                "$hard must still block post-FDG-allow",
                PostSealAuthorityInvariants6760.mayBlockAfterFdgAllow(hard),
            )
        }
        // Legacy pre-seal quality/eligibility gates must NOT block post-seal.
        for (legacy in listOf(
            "EXEC_OPEN_BLOCKED_REGIME_FLOOR_6747",
            "EXEC_OPEN_BLOCKED_SHADOW_TRAIN_ONLY_6683",
            "PAPER_ENTRY_QUALITY_REJECTED_6663",
            "STALE_FEEDBACK_EPOCH_REVALIDATE_6715",
            "MISSING_FEEDBACK_STAMP_REVALIDATE_6715",
            "EXEC_OPEN_DROPPED_TOKEN_STATE_CHANGED",
            "EXEC_FROZEN_SNAPSHOT_MISSING_INTENT_NEEDS_REVALIDATION_6627",
            "EXEC_RESTORED_TICKET_VERSION_DRIFT_6692",
            "FDG_ALLOW_SEALING_RACE_DEFERRED_6739",
        )) {
            assertFalse(
                "$legacy must be demoted to advisory post-FDG-allow",
                PostSealAuthorityInvariants6760.mayBlockAfterFdgAllow(legacy),
            )
        }
        // Blank/null reasons must fail-safe (return false — caller falls through).
        assertFalse(PostSealAuthorityInvariants6760.mayBlockAfterFdgAllow(null))
        assertFalse(PostSealAuthorityInvariants6760.mayBlockAfterFdgAllow(""))
    }

    @Test fun executable_open_gate_uses_post_seal_invariants_for_regime_and_shadow() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "REGIME_FLOOR_6747 block must consult PostSealAuthorityInvariants6760",
            src.contains("PostSealAuthorityInvariants6760.mayBlockAfterFdgAllow(reason6760)") &&
                src.contains("EXEC_OPEN_BLOCKED_REGIME_FLOOR_6747"),
        )
        assertTrue(
            "SHADOW_TRAIN_ONLY_6683 block must consult PostSealAuthorityInvariants6760",
            src.contains("EXEC_OPEN_BLOCKED_SHADOW_TRAIN_ONLY_6683") &&
                src.contains("PostSealAuthorityInvariants6760.mayBlockAfterFdgAllow"),
        )
        assertTrue(
            "advisory branch must emit the POST_SEAL_ADVISORY_ONLY_6760 telemetry",
            src.contains("PostSealAuthorityInvariants6760.emitAdvisory6760"),
        )
    }

    @Test fun executor_paper_entry_quality_uses_post_seal_invariants() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "PAPER_ENTRY_QUALITY_REJECTED_6663 must be demoted to advisory via PostSealAuthorityInvariants6760",
            src.contains("PAPER_ENTRY_QUALITY_REJECTED_6663") &&
                src.contains("PostSealAuthorityInvariants6760.mayBlockAfterFdgAllow(reason6760)") &&
                src.contains("PostSealAuthorityInvariants6760.emitAdvisory6760(reason6760, extra6760)"),
        )
    }

    // ---------- §6 FRESH_SOURCE_MARK_PROMOTION ----------------------------

    @Test fun valid_source_no_executable_mark_split_into_sub_classes() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "VALID_SOURCE_NO_EXECUTABLE_MARK_6760 sub-class label must be emitted",
            src.contains("VALID_SOURCE_NO_EXECUTABLE_MARK_6760|"),
        )
        for (sub in listOf(
            "IDENTITY_UNIT_OR_DECIMAL",
            "PAIR_OR_ROUTE_INVALID",
            "STALE_QUOTE_ONLY",
            "SOURCE_ADVISORY_ONLY",
            "SOURCE_RESOLUTION_EXCEPTION",
        )) {
            assertTrue("sub-class '$sub' must be in the promotion-failure classifier", src.contains("\"$sub\""))
        }
        assertTrue(
            "forensic emission must include subClass6760= so operator triage can grep",
            src.contains("subClass6760=\$subClass6760"),
        )
    }

    // ---------- reap wiring -----------------------------------------------

    @Test fun bot_service_cadence_calls_the_reap_authority() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "BotService pump cadence must call SpecialistCausalFunnel6625.reapStaleSizedReservations6760",
            src.contains("SpecialistCausalFunnel6625.reapStaleSizedReservations6760(30_000L)"),
        )
    }

    // ---------- misc parity ------------------------------------------------

    @Test fun lane_snapshot_still_exposes_phantom_field() {
        // Guard against a future refactor that drops the field from the
        // public snapshot contract — external witnesses (ExecutionSpine
        // Acceptance6647 §PHANTOM_DELTA_DIAG) depend on this field.
        val fields = SpecialistCausalFunnel6625.LaneSnapshot6647::class.java.declaredFields.map { it.name }
        assertEquals(
            "LaneSnapshot6647 must expose lane, counts, outcomes, phantomSizedOnly",
            listOf("lane", "counts", "outcomes", "phantomSizedOnly").toSet(),
            fields.toSet(),
        )
    }
}
