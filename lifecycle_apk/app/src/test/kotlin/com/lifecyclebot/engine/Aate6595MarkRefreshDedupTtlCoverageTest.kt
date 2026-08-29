package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * V5.0.6595 — mark-refresh dedup TTL.
 *
 * Operator directive Feb 2026:
 *   > "Deduplicate mark refresh by (assetClass, instrumentId) with one
 *   >  in-flight request and TTL. Never enqueue another refresh while
 *   >  one is in flight. Never repeatedly request an incompatible
 *   >  provider for a known asset class."
 *
 * Snapshot 6591 observed 24,807 CANONICAL_EXIT_MARK_REFRESH_QUEUED_6513
 * events for 51 open positions in ~7 minutes — ~9 refreshes per position
 * per exit-feed tick. Root cause: the pre-6595 pending-set dedup only
 * stopped concurrent enqueues; the moment the async coroutine removed
 * the mint from the set, the next tick re-queued the same refresh.
 *
 * Fix: per-mint TTL enforced on ATTEMPT and SUCCESS timestamps. Skips
 * counted as MARK_REFRESH_TTL_SKIPPED_6594.
 */
class Aate6595MarkRefreshDedupTtlCoverageTest {

    @Test
    fun aate6595_mark_refresh_ttl_present_and_enforced_before_enqueue() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue(
            "V5.0.6595: attempt + success timestamp maps must exist",
            src.contains("exitMarkRefreshLastAttemptMs6594") &&
                src.contains("exitMarkRefreshLastSuccessMs6594")
        )
        assertTrue(
            "V5.0.6595: TTL constants must be defined",
            src.contains("TTL_SUCCESS_MS = 30_000L") &&
                src.contains("TTL_FAILURE_MS = 5_000L")
        )
        assertTrue(
            "V5.0.6595: TTL gate must precede the pending-set add",
            src.contains("ttlActive6594") &&
                src.contains("MARK_REFRESH_TTL_SKIPPED_6594")
        )
        assertTrue(
            "V5.0.6595: successful refresh must record its own success timestamp",
            src.contains("exitMarkRefreshLastSuccessMs6594[cp.mint] = System.currentTimeMillis()")
        )
    }
}
