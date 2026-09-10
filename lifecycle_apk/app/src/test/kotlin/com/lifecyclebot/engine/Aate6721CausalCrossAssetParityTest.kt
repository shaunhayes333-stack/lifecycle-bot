package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6721 — §CAUSAL_ALIGN_TO_CROSS_ASSET_PARITY.
 *
 * Triage of 5.0.6720 dumps proved CausalFeedbackAuthority6715.admit() is
 * the "unfair tax" applied only to the meme deck. Crypto/perps decks
 * short-circuit at line 151 via `Admission(true, "NON_MEME_FAIL_OPEN")`
 * and trade at 57% WR / 22 healthy opens. Meme deck (subject to admit()'s
 * hard blocks) stays at 8% WR / 92.6% EXEC_GATE blocked despite four
 * successive patches (6717/6719/6720) each closing a specific accounting
 * bug.
 *
 * Since the crypto deck proves the downstream pipeline works fine WITHOUT
 * any causal admission gate, the correct fix is to align meme lanes to
 * the same fail-open contract while KEEPING all telemetry:
 *
 *   - Reservations still issued (so lifecycle telemetry is honest).
 *   - Stamps still recorded, superseded, TTL-swept.
 *   - Terminals still ACK'd, learning still flows.
 *   - Epoch drift still tracked.
 *
 *   - BUT: every prior hard-block condition emits a
 *     CAUSAL_EXEC_SOFT_MISS_*_6721 counter and returns admission=true
 *     so the meme deck flows like the crypto deck.
 *
 * We keep the hard-block on true integrity violations (mode/lane identity
 * drift, missing feedback stamp when NOT truly cold, stale epoch) because
 * those signal an authoritative pipeline break, not a routine capacity
 * decision.
 */
class Aate6721CausalCrossAssetParityTest {

    private val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CausalFeedbackAuthority6715.kt").readText()

    @Test
    fun `UNRESOLVED_FEEDBACK_CAP is now soft-miss telemetry not a hard block`() {
        assertTrue(
            "V5.0.6721 §CAUSAL_ALIGN_TO_CROSS_ASSET_PARITY marker must appear",
            src.contains("V5.0.6721 §CAUSAL_ALIGN_TO_CROSS_ASSET_PARITY"),
        )
        assertTrue(
            "New soft-miss counter for cap breach must exist",
            src.contains("CAUSAL_EXEC_SOFT_MISS_UNRESOLVED_CAP_6721"),
        )
        assertFalse(
            "Legacy UNRESOLVED_FEEDBACK_CAP_6715 hard-block return must be removed",
            src.contains("return Admission(false, \"UNRESOLVED_FEEDBACK_CAP_6715\""),
        )
    }

    @Test
    fun `TERMINAL_FEEDBACK_NOT_LEARNED is now soft-miss telemetry not a hard block`() {
        assertTrue(
            "New soft-miss counter for pending-learning must exist",
            src.contains("CAUSAL_EXEC_SOFT_MISS_FEEDBACK_PENDING_6721"),
        )
        assertFalse(
            "Legacy TERMINAL_FEEDBACK_NOT_LEARNED_6715 hard-block return must be removed",
            src.contains("return Admission(false, \"TERMINAL_FEEDBACK_NOT_LEARNED_6715\")"),
        )
    }

    @Test
    fun `integrity gates still hard-block (mode-lane drift and stale epoch)`() {
        // These MUST remain hard-blocks — they signal a real integrity issue
        // (not a routine capacity decision).
        assertTrue(
            "mode/lane identity-drift hard block must remain",
            src.contains("FEEDBACK_IDENTITY_DRIFT_REVALIDATE_6715"),
        )
        assertTrue(
            "stale epoch hard block must remain",
            src.contains("STALE_FEEDBACK_EPOCH_REVALIDATE_6715"),
        )
        assertTrue(
            "missing feedback stamp when not cold must still hard block",
            src.contains("MISSING_FEEDBACK_STAMP_REVALIDATE_6715"),
        )
    }
}
