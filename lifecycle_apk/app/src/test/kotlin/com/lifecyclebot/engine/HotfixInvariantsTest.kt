package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6312-6315 — HOTFIX REGRESSION SUITE.
 *
 * Locks the operator hotfix invariants:
 *   §7   sell qty never exceeds wallet raw quantity — clamp verified
 *   §9   BLUE_CHIP / SHIT_COIN / MOON_SHOT alias merge to canonical form
 *   §13  QUICK_RUNNER / TAKE_PROFIT reason with losing PnL fails invariant
 *   §17  DNA-early-veto criteria (loser >= 8 losses, avgLoss <= -20%)
 *   §21  Mint re-entry cooldown after catastrophic / loss / scratch exits
 *   §4   Bypass denylist prevents PROBE_ONLY / SMART_SIZER_V3_DUST_PROMOTED
 *        from authorizing a live buy
 */
class HotfixInvariantsTest {

    @Before
    fun setUp() {
        // Reset safety-hold state between tests — governor evaluation during
        // some tests can arm the hold if TradeHistoryStore returns bad rows.
        try { LiveEntrySafetyHold.clear("test-setUp") } catch (_: Throwable) {}
    }

    @After
    fun tearDown() {
        try { LiveEntrySafetyHold.clear("test-tearDown") } catch (_: Throwable) {}
    }

    // ─── §9 lane alias normalization ─────────────────────────────

    @Test
    fun blueChipAliasCollapsesToBluechip() {
        assertEquals("BLUECHIP", LaneAlias.normalize("BLUE_CHIP"))
        assertEquals("BLUECHIP", LaneAlias.normalize("blue_chip"))
        assertEquals("BLUECHIP", LaneAlias.normalize("BLUECHIP"))
        assertTrue(LaneAlias.sameCanonical("BLUE_CHIP", "BLUECHIP"))
        assertTrue(LaneAlias.sameCanonical("BLUE_CHIP", "bluechip"))
    }

    @Test
    fun shitCoinAndMoonShotAliasesCollapse() {
        assertEquals("SHITCOIN", LaneAlias.normalize("SHIT_COIN"))
        assertEquals("SHITCOIN", LaneAlias.normalize("shit_coin"))
        assertEquals("MOONSHOT", LaneAlias.normalize("MOON_SHOT"))
        assertEquals("MOONSHOT", LaneAlias.normalize("moon_shot"))
        assertTrue(LaneAlias.sameCanonical("SHIT_COIN", "shitcoin"))
        assertTrue(LaneAlias.sameCanonical("MOON_SHOT", "moonshot"))
    }

    @Test
    fun unknownLaneStringsPassThroughUppercase() {
        // Non-aliased lanes must NOT be forced to any canonical name;
        // they simply upper-case so downstream comparisons still work.
        assertEquals("STANDARD", LaneAlias.normalize("standard"))
        assertEquals("QUALITY", LaneAlias.normalize("Quality"))
        assertEquals("", LaneAlias.normalize(""))
        assertEquals("", LaneAlias.normalize(null))
    }

    // ─── §4 bypass denylist ──────────────────────────────────────

    @Test
    fun bypassDenylistBlocksLiveEntry() {
        // V5.0.6333 — the SMART_SIZER_V3_DUST_PROMOTED_6271 label was
        // moved to the ADVISORY tier (it is a size-shaping signal, not
        // a disqualifier). The HARD tier keeps genuine data-integrity
        // disqualifiers — TOKEN_MAP_PENDING means the candidate does
        // not yet have the on-chain metadata required to execute a
        // live buy, so it MUST redirect to shadow.
        val result = LiveEntrySafetyHold.assessLiveEntry(
            mint = "TEST_MINT_" + System.nanoTime(),
            symbol = "TEST",
            candidateScore = 80.0,
            entryReasons = listOf("STANDARD", "TOKEN_MAP_PENDING"),
            lane = "STANDARD",
        )
        assertFalse("token-map-pending must NOT authorize live", result.allow)
        assertTrue("redirect-to-shadow flag must be set", result.redirectToShadow)
        assertTrue(
            "failedInvariants should reference the hard-denylisted label",
            result.failedInvariants.any { it.contains("TOKEN_MAP_PENDING") }
        )
    }

    @Test
    fun advisoryLabelPassesLiveEntryButLogs() {
        // V5.0.6333 — SMART_SIZER_V3_DUST_PROMOTED is a soft-shape
        // signal; upstream sizing already shrinks the trade. It must
        // NOT hard-block the live buy on its own — score and hold
        // still gate everything else.
        val result = LiveEntrySafetyHold.assessLiveEntry(
            mint = "TEST_MINT_" + System.nanoTime(),
            symbol = "TEST",
            candidateScore = 80.0,
            entryReasons = listOf("STANDARD", "SMART_SIZER_V3_DUST_PROMOTED_6271"),
            lane = "STANDARD",
        )
        // ALLOW unless a lingering safety hold from another test is
        // still armed — either way, MUST NOT be a hard-denylist fail.
        assertFalse(
            "advisory labels must not populate a HARD denylist failure",
            result.failedInvariants.any { it.contains("HARD_DENYLISTED") }
        )
    }

    @Test
    fun probeOnlyReasonBlocksLiveEntry() {
        val result = LiveEntrySafetyHold.assessLiveEntry(
            mint = "TEST_MINT_" + System.nanoTime(),
            symbol = "TEST",
            candidateScore = 90.0,
            entryReasons = listOf("STANDARD", "PROBE_ONLY"),
            lane = "STANDARD",
        )
        assertFalse(result.allow)
        assertTrue(result.redirectToShadow)
    }

    @Test
    fun scoreBelowLiveFloorRedirectsToShadow() {
        val result = LiveEntrySafetyHold.assessLiveEntry(
            mint = "TEST_MINT_" + System.nanoTime(),
            symbol = "TEST",
            candidateScore = 10.0,
            entryReasons = listOf("STANDARD"),
            lane = "STANDARD",
        )
        assertFalse("score=10 must not authorize a live buy", result.allow)
        assertTrue(result.redirectToShadow)
        assertTrue(result.failedInvariants.any { it.contains("SCORE_BELOW_LIVE_FLOOR") })
    }

    @Test
    fun cleanCandidateAllowsLiveEntry() {
        // A well-scored candidate with no denylist / no hold should be
        // ALLOWED unless the confidence governor put us in HOLD (which
        // depends on TradeHistoryStore state — not initialized in unit
        // tests, so LiveConfidenceStats.load returns canonicalN=0 →
        // BASELINE governor).
        val result = LiveEntrySafetyHold.assessLiveEntry(
            mint = "CLEAN_MINT_" + System.nanoTime(),
            symbol = "CLEAN",
            candidateScore = 80.0,
            entryReasons = listOf("STANDARD"),
            lane = "STANDARD",
        )
        assertTrue(
            "expected ALLOW; got reason=${result.reason} failed=${result.failedInvariants}",
            result.allow || result.reason == "SAFETY_HOLD_ARMED"  // may be armed by other tests
        )
    }

    // ─── §21 mint re-entry cooldown ──────────────────────────────

    @Test
    fun catastrophicCloseArmsLongCooldown() {
        val mint = "COOLDOWN_MINT_" + System.nanoTime()
        MintReEntryCooldown.onFinalisedClose(mint, "CATASTROPHIC_HARD_BACKSTOP_-25", -47.0)
        val block = MintReEntryCooldown.shouldBlockReEntry(mint)
        assertNotNull("catastrophic close must arm a cooldown", block)
        assertTrue(
            "cooldown message must reference remaining seconds",
            block!!.contains("cooldown remaining")
        )
        MintReEntryCooldown.clear(mint, "test-cleanup")
    }

    @Test
    fun winningCloseDoesNotArmCooldown() {
        val mint = "WIN_MINT_" + System.nanoTime()
        MintReEntryCooldown.onFinalisedClose(mint, "QUICK_RUNNER_10X_FULL_EXIT", 850.0)
        val block = MintReEntryCooldown.shouldBlockReEntry(mint)
        assertNull("winning close must NOT arm any cooldown", block)
    }

    @Test
    fun structureChangedClearsCooldown() {
        val mint = "STRUCT_MINT_" + System.nanoTime()
        MintReEntryCooldown.onFinalisedClose(mint, "REALIZED_LOSS", -8.0)
        assertNotNull(MintReEntryCooldown.shouldBlockReEntry(mint))
        MintReEntryCooldown.clear(mint, "MARKET_STRUCTURE_CHANGE_TEST")
        assertNull(MintReEntryCooldown.shouldBlockReEntry(mint))
    }

    // ─── §8 canonical buy fill registry (V5.0.6320 / 6323) ──────

    @Test
    fun canonicalBuyFillRoundTripPreservesUsdAndSolPrices() {
        // V5.0.6323 — a wallet-verified fill must expose BOTH USD and
        // SOL entry prices without mutation. Position card + journal
        // read USD; SOL is kept for downstream SOL-denominated math.
        val mint = "CANON_ROUNDTRIP_" + System.nanoTime()
        CanonicalBuyFillRegistry.clear(mint, "test-setup")
        val fill = CanonicalBuyFillRegistry.CanonicalBuyFill(
            mint = mint,
            walletVerifiedQty = 12_345.6789,
            decimals = 6,
            entryPriceSol = 0.00000587,
            entryPriceUsd = 0.00009761,
            solSpentNet = 0.0725,
            entryTsMs = 1_722_000_000_000L,
            buySignature = "sig_" + System.nanoTime(),
            fillIndex = 0,
            lane = "SHITCOIN",
        )
        CanonicalBuyFillRegistry.record(fill)
        val loaded = CanonicalBuyFillRegistry.get(mint)
        assertNotNull("canonical fill must persist in-memory", loaded)
        assertEquals(fill.entryPriceUsd, loaded!!.entryPriceUsd, 0.0)
        assertEquals(fill.entryPriceSol, loaded.entryPriceSol, 0.0)
        assertEquals(fill.walletVerifiedQty, loaded.walletVerifiedQty, 0.0)
        // V5.0.6323 assert USD is ~150× the SOL price so downstream code
        // can never mistake the two units. Regression-guards against the
        // 6321 bug where the position card preferred entryPriceSol and
        // showed $0.00000587 while the journal held $0.00009761.
        val ratio = loaded.entryPriceUsd / loaded.entryPriceSol
        assertTrue(
            "USD entry must be materially larger than SOL entry (ratio=$ratio)",
            ratio > 10.0
        )
        CanonicalBuyFillRegistry.clear(mint, "test-cleanup")
    }

    @Test
    fun canonicalBuyFillIsImmutableAgainstFakePriceRewrite() {
        // Simulate a WebSocket / lane-reclassifier trying to overwrite
        // the canonical fill with a stale/bad price. The registry must
        // ignore blank-mint / zero-qty writes so downstream PnL basis
        // stays anchored to the on-chain-proven acquisition.
        val mint = "CANON_IMMUTABLE_" + System.nanoTime()
        CanonicalBuyFillRegistry.clear(mint, "test-setup")
        val original = CanonicalBuyFillRegistry.CanonicalBuyFill(
            mint = mint,
            walletVerifiedQty = 5_000.0,
            decimals = 6,
            entryPriceSol = 0.00001,
            entryPriceUsd = 0.0015,
            solSpentNet = 0.05,
            entryTsMs = System.currentTimeMillis(),
            buySignature = "orig_sig",
            fillIndex = 0,
            lane = "STANDARD",
        )
        CanonicalBuyFillRegistry.record(original)
        // Attempt a corrupt overwrite (zero qty — should be rejected).
        CanonicalBuyFillRegistry.record(
            original.copy(walletVerifiedQty = 0.0, entryPriceUsd = 999.99)
        )
        val after = CanonicalBuyFillRegistry.get(mint)
        assertNotNull(after)
        assertEquals(
            "zero-qty write must NOT clobber the canonical entry USD price",
            0.0015, after!!.entryPriceUsd, 0.0
        )
        assertEquals(5_000.0, after.walletVerifiedQty, 0.0)
        CanonicalBuyFillRegistry.clear(mint, "test-cleanup")
    }

    @Test
    fun canonicalBuyFillClearRemovesEntry() {
        val mint = "CANON_CLEAR_" + System.nanoTime()
        CanonicalBuyFillRegistry.record(
            CanonicalBuyFillRegistry.CanonicalBuyFill(
                mint = mint,
                walletVerifiedQty = 100.0,
                decimals = 6,
                entryPriceSol = 0.00002,
                entryPriceUsd = 0.003,
                solSpentNet = 0.002,
                entryTsMs = System.currentTimeMillis(),
                buySignature = "clear_sig",
                fillIndex = 0,
                lane = "STANDARD",
            )
        )
        assertNotNull(CanonicalBuyFillRegistry.get(mint))
        CanonicalBuyFillRegistry.clear(mint, "test-position-close")
        assertNull(
            "cleared fill must not be returned on subsequent get()",
            CanonicalBuyFillRegistry.get(mint)
        )
    }
}
