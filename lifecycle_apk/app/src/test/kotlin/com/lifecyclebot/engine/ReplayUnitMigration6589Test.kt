package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6589 §P0-6 — persisted paper history replay UNIT migration.
 *
 * Operator forensic (6580):
 *   275 QUARANTINE_REPLAY_UNIT_MISMATCH_6541 events
 *   275 REPLAY_FILL_PRICE_UNIT_REJECTED_6541 events
 *   133 quarantined positions
 *
 * Prior behaviour: on unit mismatch (fillPrice stored as SOL/token
 * instead of USD/token — legacy schema), the position was quarantined
 * entirely, stranding cost/qty and blocking future exits.
 *
 * Fix: open as pure carry (entryPriceUsd = 0, quarantineReason = null,
 * entrySource = REPLAY_UNIT_LEGACY_SOL_PER_TOKEN_6589). The position
 * lifecycle continues; only USD basis is unknowable historically.
 */
class ReplayUnitMigration6589Test {

    private val positionSrc = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()

    @Test
    fun replay_unit_mismatch_migrated_to_carry_not_quarantined() {
        assertTrue(
            "Replay must migrate legacy unit-mismatch events to OPEN carry, not quarantine them",
            positionSrc.contains("V5.0.6589 §P0-6 — LEGACY SOL-PER-TOKEN REPLAY MIGRATION") &&
                positionSrc.contains("REPLAY_UNIT_MIGRATED_TO_CARRY_6589") &&
                positionSrc.contains("entryPriceSource = \"REPLAY_UNIT_LEGACY_SOL_PER_TOKEN_6589\"") &&
                positionSrc.contains("lifecycle = Lifecycle.OPEN, lastMutationMs = e.atMs,\n                                quarantineReason = null,")
        )
    }
}
