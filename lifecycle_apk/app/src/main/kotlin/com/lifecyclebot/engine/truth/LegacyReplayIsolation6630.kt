package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6630 §C LEGACY_REPLAY_ISOLATION.
 *
 * Operator directive (Feb 2026):
 *   > "Current runtime:
 *   >   REPLAY_FILL_PRICE_UNIT_REJECTED = 482
 *   >   REPLAY_UNIT_MIGRATED_TO_CARRY   = 482
 *   >   EVENT_STREAM_REPLAY_DIVERGED at event index 10
 *   >   cash delta ~127 SOL
 *   >
 *   > This repair system is no longer safe as a balance writer.
 *   > Make all replay/parity/migration code DIAGNOSTIC ONLY until
 *   > validation passes. Do NOT automatically migrate every canonical
 *   > position into a 'carry' unit and then use those derived values
 *   > to overwrite cash/openCost/realized PnL.
 *   >
 *   > If no trustworthy checkpoint exists, create a new PAPER
 *   > ACCOUNTING EPOCH while retaining the old trade journal
 *   > read-only for history/tax/audit purposes."
 *
 * This isolation gate exposes ONE boolean the replay path consults
 * before opening a canonical position from a legacy SOL-per-token
 * fillPrice event. When the gate is closed (default), those events
 * are QUARANTINED instead of MIGRATED, so the durable journal
 * retains history but the canonical PaperCapitalAuthority is NOT
 * mutated with 482 basis-untrusted positions.
 *
 * When the operator has validated a repaired accounting epoch, they
 * flip the gate open via `enableLegacyCarryMigration6630(true)` and
 * the migration path re-engages. This lets the operator do a
 * runtime-controlled repair without recompiling.
 */
object LegacyReplayIsolation6630 {

    private val migrationEnabled = AtomicBoolean(false)  // gate CLOSED by default
    private val quarantines = AtomicLong(0L)
    private val migrations = AtomicLong(0L)

    /**
     * True when the replay path is authorized to migrate a legacy
     * SOL-per-token BUY into a pure-carry OPEN canonical position.
     * Default is FALSE per operator directive: replay is diagnostic
     * only until an accounting-epoch reset validates the population.
     */
    fun migrationAuthorized6630(): Boolean = migrationEnabled.get()

    /** Runtime toggle. Only the operator flips this after a manual
     *  accounting-epoch reset. Change is logged so the flip moment is
     *  visible in the pipeline dump. */
    fun setMigrationAuthorized6630(value: Boolean, source: String = "operator") {
        val prev = migrationEnabled.getAndSet(value)
        if (prev != value) try {
            PipelineHealthCollector.labelInc(
                if (value) "LEGACY_REPLAY_ISOLATION_MIGRATION_ENABLED_6630"
                else "LEGACY_REPLAY_ISOLATION_MIGRATION_DISABLED_6630",
            )
            ForensicLogger.lifecycle(
                "LEGACY_REPLAY_ISOLATION_TOGGLE_6630",
                "from=$prev to=$value source=$source",
            )
        } catch (_: Throwable) {}
    }

    /** Called by the replay path to record which branch it took for
     *  a legacy fillPrice unit event. Passive — does not decide, only
     *  counts, so callers can grep the isolated vs migrated split. */
    fun recordDisposition6630(migrated: Boolean, positionId: String, mint: String) {
        if (migrated) {
            migrations.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("LEGACY_REPLAY_MIGRATED_6630")
                ForensicLogger.lifecycle(
                    "LEGACY_REPLAY_MIGRATED_6630",
                    "positionId=$positionId mint=${mint.take(10)} authorized=true",
                )
            } catch (_: Throwable) {}
        } else {
            quarantines.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("LEGACY_REPLAY_QUARANTINED_6630")
                ForensicLogger.lifecycle(
                    "LEGACY_REPLAY_QUARANTINED_6630",
                    "positionId=$positionId mint=${mint.take(10)} " +
                        "reason=migration_gate_closed_pending_epoch_reset",
                )
            } catch (_: Throwable) {}
        }
    }

    fun statusLine6630(): String =
        "migrationAuthorized=${migrationEnabled.get()} quarantines=${quarantines.get()} migrations=${migrations.get()}"

    /** V5.0.6630 test-only reset. */
    fun resetForTest() {
        migrationEnabled.set(false)
        quarantines.set(0L)
        migrations.set(0L)
    }
}
