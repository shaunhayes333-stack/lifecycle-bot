package com.lifecyclebot.engine

import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * V5.0.6630 §C LEGACY_REPLAY_ISOLATION coverage.
 *
 * Operator directive Feb 2026:
 *   > "Make all replay/parity/migration code DIAGNOSTIC ONLY until
 *   >  validation passes. Do NOT automatically migrate every canonical
 *   >  position into a 'carry' unit and then use those derived values
 *   >  to overwrite cash/openCost/realized PnL."
 */
class Aate6630LegacyReplayIsolationCoverageTest {

    @After
    fun tearDown() {
        try { com.lifecyclebot.engine.truth.LegacyReplayIsolation6630.resetForTest() } catch (_: Throwable) {}
    }

    @Test
    fun aate6630_migration_gate_is_closed_by_default() {
        com.lifecyclebot.engine.truth.LegacyReplayIsolation6630.resetForTest()
        val authorized = com.lifecyclebot.engine.truth.LegacyReplayIsolation6630.migrationAuthorized6630()
        assertEquals("V5.0.6630 §C: migration gate must be CLOSED at boot per operator directive",
            false, authorized)
    }

    @Test
    fun aate6630_migration_toggle_advances_status_counters() {
        com.lifecyclebot.engine.truth.LegacyReplayIsolation6630.resetForTest()
        com.lifecyclebot.engine.truth.LegacyReplayIsolation6630
            .setMigrationAuthorized6630(true, source = "aate6630-unit-test")
        assertTrue("V5.0.6630 §C: migration must engage after toggle",
            com.lifecyclebot.engine.truth.LegacyReplayIsolation6630.migrationAuthorized6630())
        com.lifecyclebot.engine.truth.LegacyReplayIsolation6630
            .setMigrationAuthorized6630(false, source = "aate6630-unit-test")
        assertTrue("V5.0.6630 §C: migration must disengage after toggle back",
            !com.lifecyclebot.engine.truth.LegacyReplayIsolation6630.migrationAuthorized6630())
    }

    @Test
    fun aate6630_disposition_recorder_counts_quarantines_and_migrations() {
        com.lifecyclebot.engine.truth.LegacyReplayIsolation6630.resetForTest()
        com.lifecyclebot.engine.truth.LegacyReplayIsolation6630
            .recordDisposition6630(migrated = false, positionId = "aate6630-pos-A", mint = "aate6630-mintA")
        com.lifecyclebot.engine.truth.LegacyReplayIsolation6630
            .recordDisposition6630(migrated = true, positionId = "aate6630-pos-B", mint = "aate6630-mintB")
        val status = com.lifecyclebot.engine.truth.LegacyReplayIsolation6630.statusLine6630()
        assertTrue("V5.0.6630 §C: quarantine and migration counters must advance separately",
            status.contains("quarantines=1") && status.contains("migrations=1"))
    }

    @Test
    fun aate6630_replay_authority_consults_isolation_gate_source_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt"
        ).readText()
        assertTrue("V5.0.6630 §C: replay must consult LegacyReplayIsolation6630 before migrating",
            src.contains("LegacyReplayIsolation6630.migrationAuthorized6630") &&
                src.contains("LEGACY_SOL_PER_TOKEN_QUARANTINED_6630") &&
                src.contains("recordDisposition6630(migrated = false") &&
                src.contains("recordDisposition6630(migrated = true"))
    }
}
