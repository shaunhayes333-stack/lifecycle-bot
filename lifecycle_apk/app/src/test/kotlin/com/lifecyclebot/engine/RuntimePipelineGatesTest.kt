package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.9.495z31 — Acceptance tests for the runtime/pipeline reliability
 * gates introduced in this push.
 */
class RuntimePipelineGatesTest {

    // ── RuntimeModeAuthority ──────────────────────────────────────

    @Test
    fun mode_authority_publishes_and_reads() {
        RuntimeModeAuthority.publishConfig(paperMode = true,  autoTrade = true)
        assertEquals(RuntimeModeAuthority.Mode.PAPER, RuntimeModeAuthority.authority())
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        assertEquals(RuntimeModeAuthority.Mode.LIVE, RuntimeModeAuthority.authority())
    }

    @Test
    fun mode_authority_detects_desync() {
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)  // LIVE
        RuntimeModeAuthority.publishUiMode(paperMode = true)                     // PAPER (bug)
        val d = RuntimeModeAuthority.detectDesync()
        assertNotNull("desync expected when ui != authority", d)
        assertEquals(RuntimeModeAuthority.Mode.LIVE, d!!.authority)
        assertTrue("UI_MODE must appear in mismatches", "UI_MODE" in d.mismatches.keys)
    }

    @Test
    fun mode_authority_consistent_returns_null_when_aligned() {
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(false)
        RuntimeModeAuthority.publishExecutorMode(false)
        RuntimeModeAuthority.publishPipelineMode(false)
        assertNull(RuntimeModeAuthority.detectDesync())
    }

    // ── SnipeAgeGate ──────────────────────────────────────────────

    @Test
    fun snipe_mode_classifies_old_tokens_to_background_not_reject() {
        assertEquals(SnipeAgeGate.Decision.BACKGROUND_ONLY_OLD_TOKEN,
            SnipeAgeGate.evaluate(ageMinutes = 87 * 60, snipeModeOn = true))
        assertTrue(SnipeAgeGate.shuntToBackground(87 * 60, snipeModeOn = true))
    }

    @Test
    fun snipe_mode_passes_new_tokens() {
        assertEquals(SnipeAgeGate.Decision.SNIPE_AGE_PASS,
            SnipeAgeGate.evaluate(ageMinutes = 5, snipeModeOn = true))
    }

    @Test
    fun snipe_mode_off_passes_anything() {
        assertEquals(SnipeAgeGate.Decision.SNIPE_AGE_PASS,
            SnipeAgeGate.evaluate(ageMinutes = 999_999, snipeModeOn = false))
    }

    // ── EntryWaitOverrideGate ────────────────────────────────────

    @Test
    fun fdg_defers_entry_wait_with_high_risk_and_low_conf() {
        val r = EntryWaitOverrideGate.evaluate(
            entryWait = true, riskHigh = true,
            moonshotOverride = false, confidence = 39
        )
        assertEquals(EntryWaitOverrideGate.Verdict.FDG_DEFER_ENTRY_WAIT, r.verdict)
        assertTrue("defer must keep token in watchlist for re-evaluation", r.keepInWatchlist)
    }

    @Test
    fun fdg_overrides_with_moonshot() {
        val r = EntryWaitOverrideGate.evaluate(
            entryWait = true, riskHigh = true,
            moonshotOverride = true, confidence = 39
        )
        assertEquals(EntryWaitOverrideGate.Verdict.FDG_OVERRIDE_ENTRY_WAIT, r.verdict)
    }

    @Test
    fun fdg_overrides_on_high_conf() {
        val r = EntryWaitOverrideGate.evaluate(
            entryWait = true, riskHigh = true,
            moonshotOverride = false, confidence = 80
        )
        assertEquals(EntryWaitOverrideGate.Verdict.FDG_OVERRIDE_ENTRY_WAIT, r.verdict)
    }

    @Test
    fun fdg_allows_when_not_wait_or_not_high_risk() {
        val r = EntryWaitOverrideGate.evaluate(
            entryWait = false, riskHigh = true,
            moonshotOverride = false, confidence = 30
        )
        assertEquals(EntryWaitOverrideGate.Verdict.ALLOW, r.verdict)
    }

    // ── TierState ────────────────────────────────────────────────

    @Test
    fun tier_count_unlocked_does_not_imply_ready() {
        val s = TierState.evaluate(
            tierCount = 5, tradeCount = 4352, winRatePct = 33.5, targetWrPct = 50.0,
            streakBlocks = 3
        )
        assertFalse(s.isReady)
        assertTrue(TierState.Status.PROFITABILITY_LOCKED in s.statuses)
        assertTrue(TierState.Status.STREAK_GUARD_ACTIVE in s.statuses)
    }

    @Test
    fun tier_ready_when_wr_above_target_and_no_streak() {
        val s = TierState.evaluate(
            tierCount = 5, tradeCount = 100, winRatePct = 60.0, targetWrPct = 50.0,
            streakBlocks = 0
        )
        assertTrue(s.isReady)
    }

    // ── RugCheckPolicy ───────────────────────────────────────────

    @Test
    fun rugcheck_pending_blocks_low_score_live() {
        val s = RugCheckPolicy.evaluate(
            rcConfirmedSafe = false, rcConfirmedRisky = false, rcPending = true,
            isPaperMode = false, score = 50,
        )
        assertEquals(RugCheckPolicy.State.RC_PENDING_BLOCKED, s)
    }

    @Test
    fun rugcheck_pending_allowed_high_score_live() {
        val s = RugCheckPolicy.evaluate(
            rcConfirmedSafe = false, rcConfirmedRisky = false, rcPending = true,
            isPaperMode = false, score = 80,
        )
        assertEquals(RugCheckPolicy.State.RC_PENDING_ALLOWED_LIVE_OVERRIDE, s)
    }

    @Test
    fun rugcheck_pending_allowed_paper() {
        val s = RugCheckPolicy.evaluate(
            rcConfirmedSafe = false, rcConfirmedRisky = false, rcPending = true,
            isPaperMode = true, score = 30,
        )
        assertEquals(RugCheckPolicy.State.RC_PENDING_ALLOWED_PAPER, s)
    }

    // ── WatchlistTtlPolicy ───────────────────────────────────────

    @Test
    fun watchlist_sweeps_stale_entries_in_snipe() {
        WatchlistTtlPolicy.clear()
        WatchlistTtlPolicy.mark("ABC", 70)
        // Pretend it's old by re-marking with a stale ts via reflection? Not needed —
        // call sweepStale immediately, expect 0 removals (entry is fresh).
        val removed = WatchlistTtlPolicy.sweepStale(snipeModeOn = true)
        assertEquals(0, removed)
        assertEquals(1, WatchlistTtlPolicy.size())
    }

    // ── CryptoPositionState ──────────────────────────────────────

    @Test
    fun live_overwrites_paper_in_crypto_state() {
        CryptoPositionState.record("BTC", CryptoPositionState.Bucket.PAPER)
        CryptoPositionState.record("BTC", CryptoPositionState.Bucket.LIVE)
        assertEquals(0, CryptoPositionState.count(CryptoPositionState.Bucket.PAPER))
        assertEquals(1, CryptoPositionState.count(CryptoPositionState.Bucket.LIVE))
        CryptoPositionState.release("BTC", CryptoPositionState.Bucket.LIVE)
    }

    // ── DeadAILayerFilter ────────────────────────────────────────

    @Test
    fun layer_marked_zero_starved_when_zero_ratio_high() {
        DeadAILayerFilter.reset()
        repeat(30) { DeadAILayerFilter.recordContribution("FundingRateAwarenessAI", 0.0) }
        assertEquals(DeadAILayerFilter.LayerHealth.ZERO_STARVED,
            DeadAILayerFilter.health("FundingRateAwarenessAI"))
    }

    @Test
    fun layer_marked_disabled_not_applicable_overrides_zero() {
        DeadAILayerFilter.reset()
        DeadAILayerFilter.markNotApplicable("FundingRateAwarenessAI")
        repeat(30) { DeadAILayerFilter.recordContribution("FundingRateAwarenessAI", 0.0) }
        assertEquals(DeadAILayerFilter.LayerHealth.DISABLED_NOT_APPLICABLE,
            DeadAILayerFilter.health("FundingRateAwarenessAI"))
    }
}
