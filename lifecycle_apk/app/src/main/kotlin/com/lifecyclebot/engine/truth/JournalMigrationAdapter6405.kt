package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6405 §14 — JOURNAL MIGRATION.
 *
 * Legacy journal writers scattered across the codebase produce free-
 * form strings; the canonical event stream produces typed rows. This
 * migration routes ALL legacy log lines through a strict adapter so
 * the operator dashboard can consume both without losing precision.
 *
 * Rule: the adapter NEVER swallows an unrecognised legacy tag. Every
 * legacy tag is either mapped to a canonical type or explicitly
 * rejected with a JOURNAL_UNMAPPED_TAG_6405 counter — no silent drops.
 */
object JournalMigrationAdapter6405 {

    /**
     * Map a legacy tag → canonical [CanonicalEventStream6405.Type].
     * Returns null when the tag has no canonical counterpart; caller
     * MUST treat null as a data-quality signal, not silent drop.
     */
    fun map(legacyTag: String): CanonicalEventStream6405.Type? {
        val t = legacyTag.uppercase()
        return when {
            t.contains("BUY_INTENT") -> CanonicalEventStream6405.Type.BUY_INTENT
            t.contains("BUY_LANDED") -> CanonicalEventStream6405.Type.BUY_LANDED
            t.contains("BUY_VERIFIED") -> CanonicalEventStream6405.Type.BUY_VERIFIED
            t.contains("SELL_INTENT") -> CanonicalEventStream6405.Type.SELL_INTENT
            t.contains("SELL_LANDED") -> CanonicalEventStream6405.Type.SELL_LANDED
            t.contains("SELL_VERIFIED") -> CanonicalEventStream6405.Type.SELL_VERIFIED
            t.contains("POSITION_TERMINAL") ||
                t.contains("CLOSED_FULL_EXIT") ||
                t.contains("CLOSED_STOP") ||
                t.contains("CLOSED_LIQUIDATED") -> CanonicalEventStream6405.Type.POSITION_TERMINAL
            t.contains("DECIMAL_INTEGRITY_HARD_BLOCK") ||
                t.contains("SELL_ABORTED_DECIMAL_INTEGRITY") ->
                CanonicalEventStream6405.Type.DECIMAL_INTEGRITY_BLOCK
            t.contains("PRICE_INTEGRITY_HARD_BLOCK") ->
                CanonicalEventStream6405.Type.PRICE_INTEGRITY_BLOCK
            t.contains("DUPLICATE_EXIT_BLOCKED") ->
                CanonicalEventStream6405.Type.DUPLICATE_EXIT_BLOCKED
            else -> {
                // V5.0.6405 §7 — SILENT COUNTER ONLY.
                // Operator 6405 snapshot at t=192s showed
                // JOURNAL_UNMAPPED_TAG_6405 at 24 286 emits (127/s),
                // doubling every legacy lifecycle event and starving
                // the async forensic queue. Emitting a lifecycle event
                // for an unmapped tag from INSIDE the lifecycle emitter
                // is a self-amplifying feedback loop. Increment the
                // counter silently and leave the log stream alone —
                // operators can watch the counter, no per-tag record
                // is necessary.
                try {
                    PipelineHealthCollector.labelInc("JOURNAL_UNMAPPED_TAG_6405")
                } catch (_: Throwable) {}
                null
            }
        }
    }
}
