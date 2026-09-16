package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6764 §POST_SEAL_SECOND_SWEEP — regression fence for the remaining
 * §7 audit constants routed through `PostSealAuthorityInvariants6760`.
 *
 * V5.0.6760 landed the first batch (SHADOW_TRAIN_ONLY_6683,
 * REGIME_FLOOR_6747, PAPER_ENTRY_QUALITY_REJECTED_6663). V5.0.6763's
 * operator diagnostic quantified per-lane firing rates so the next
 * routing pass could be scoped safely:
 *
 *   FDG_ALLOW_SEALING_RACE_DEFERRED_6739  → demoted (this fence)
 *
 * NOT demoted (kept authoritative per the operator's own code
 * comments — inside `CausalFeedbackAuthority6715` line ~204 they are
 * explicitly documented as MUST HARD-BLOCK integrity guards):
 *
 *   STALE_FEEDBACK_EPOCH_REVALIDATE_6715
 *   MISSING_FEEDBACK_STAMP_REVALIDATE_6715
 *
 * NOT demoted (kept as revalidation drop-and-refresh paths, safer to
 * leave than to convert into advisory-only which would proceed on
 * stale intent snapshots):
 *
 *   EXEC_OPEN_DROPPED_TOKEN_STATE_CHANGED
 *   EXEC_FROZEN_SNAPSHOT_MISSING_INTENT_NEEDS_REVALIDATION_6627
 *   EXEC_RESTORED_TICKET_VERSION_DRIFT_6692 (already telemetry-only,
 *                                            no block, no demotion needed)
 */
class Aate6764PostSealSecondSweepTest {

    @Test fun fdg_allow_sealing_race_deferred_uses_post_seal_authority() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "FDG_ALLOW_SEALING_RACE_DEFERRED_6739 block must consult PostSealAuthorityInvariants6760",
            src.contains("FDG_ALLOW_SEALING_RACE_DEFERRED_6739") &&
                src.contains("PostSealAuthorityInvariants6760.mayBlockAfterFdgAllow(reason6763)"),
        )
        assertTrue(
            "advisory branch must call emitAdvisory6760",
            src.contains("PostSealAuthorityInvariants6760.emitAdvisory6760(reason6763, extra6763)"),
        )
    }
}
