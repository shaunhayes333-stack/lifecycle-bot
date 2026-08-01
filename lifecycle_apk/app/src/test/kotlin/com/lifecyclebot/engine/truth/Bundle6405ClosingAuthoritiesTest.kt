package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class Bundle6405LaneProfileRegistryTest {

    @After fun tearDown() { LaneProfileRegistry6405.clearForTest() }

    @Test fun default_profiles_present_for_each_lane() {
        listOf("TREASURY", "BLUECHIP", "QUALITY", "SHITCOIN", "MOONSHOT").forEach {
            val p = LaneProfileRegistry6405.get(it)
            assertTrue(p.strictStopFractionOfEntry in 0.0..1.0)
            assertTrue(p.partialTakeMultiple >= 1.0)
        }
    }

    @Test fun override_takes_precedence_over_default() {
        val custom = LaneProfileRegistry6405.ExitProfile(0.9, 3.0, 0.7, 0.4)
        LaneProfileRegistry6405.put("SHITCOIN", custom)
        assertEquals(custom, LaneProfileRegistry6405.get("SHITCOIN"))
    }

    @Test fun unknown_lane_falls_back_to_quality() {
        val quality = LaneProfileRegistry6405.get("QUALITY")
        assertEquals(quality, LaneProfileRegistry6405.get("UNKNOWN_LANE"))
    }
}

class Bundle6405EntryTimingGateTest {

    private fun sig(
        score: Double = 0.8,
        liq: Double = 10_000.0,
        mcap: Double = 100_000.0,
        age: Long = 60_000L,
    ) = EntryTimingGate6405.Signal(
        scoreZeroToOne = score,
        liquidityUsd = liq,
        marketCapUsd = mcap,
        ageMs = age,
        minLiquidityUsd = 2_500.0,
        minMarketCapUsd = 20_000.0,
        maxAgeMs = 60 * 60_000L,
        earlyScoreThreshold = 0.75,
        waitScoreThreshold = 0.55,
    )

    @Test fun early_when_score_above_threshold() {
        val (t, r) = EntryTimingGate6405.classify(sig())
        assertEquals(EntryTimingGate6405.Timing.EARLY, t)
        assertEquals("OK", r)
    }

    @Test fun wait_between_thresholds() {
        val (t, _) = EntryTimingGate6405.classify(sig(score = 0.6))
        assertEquals(EntryTimingGate6405.Timing.WAIT, t)
    }

    @Test fun rejects_below_wait_threshold() {
        val (t, _) = EntryTimingGate6405.classify(sig(score = 0.3))
        assertEquals(EntryTimingGate6405.Timing.REJECTED, t)
    }

    @Test fun rejects_low_liquidity() {
        val (t, r) = EntryTimingGate6405.classify(sig(liq = 100.0))
        assertEquals(EntryTimingGate6405.Timing.REJECTED, t)
        assertEquals("LIQUIDITY_BELOW_MIN", r)
    }

    @Test fun rejects_low_mcap() {
        val (t, r) = EntryTimingGate6405.classify(sig(mcap = 100.0))
        assertEquals(EntryTimingGate6405.Timing.REJECTED, t)
        assertEquals("MARKETCAP_BELOW_MIN", r)
    }

    @Test fun rejects_over_max_age() {
        val (t, r) = EntryTimingGate6405.classify(sig(age = 2 * 60 * 60_000L))
        assertEquals(EntryTimingGate6405.Timing.REJECTED, t)
        assertEquals("AGE_ABOVE_MAX", r)
    }

    @Test fun rejects_out_of_range_score() {
        val (t, _) = EntryTimingGate6405.classify(sig(score = 1.5))
        assertEquals(EntryTimingGate6405.Timing.REJECTED, t)
    }
}

class Bundle6405JournalMigrationAdapterTest {

    @Test fun maps_known_legacy_tags() {
        val m = JournalMigrationAdapter6405
        assertEquals(CanonicalEventStream6405.Type.BUY_INTENT, m.map("BUY_INTENT_TAG"))
        assertEquals(CanonicalEventStream6405.Type.SELL_VERIFIED, m.map("EMIT_SELL_VERIFIED"))
        assertEquals(
            CanonicalEventStream6405.Type.POSITION_TERMINAL,
            m.map("CLOSED_FULL_EXIT_ROW"),
        )
        assertEquals(
            CanonicalEventStream6405.Type.DECIMAL_INTEGRITY_BLOCK,
            m.map("SELL_ABORTED_DECIMAL_INTEGRITY_6405"),
        )
        assertEquals(
            CanonicalEventStream6405.Type.PRICE_INTEGRITY_BLOCK,
            m.map("PRICE_INTEGRITY_HARD_BLOCK_6405"),
        )
    }

    @Test fun unknown_tag_returns_null_and_never_silently_drops() {
        assertNull(JournalMigrationAdapter6405.map("SOME_LEGACY_FREEFORM_LOG"))
    }
}

class Bundle6405PortfolioInvariantsTest {

    private fun pos(entry: Long, sold: Long, lamports: Long = 1_000L) =
        CheckpointRecoveryAuthority6405.OpenPosition(
            wallet = "W", mint = "M", positionGeneration = 1L,
            entryRaw = BigInteger.valueOf(entry),
            soldRaw = BigInteger.valueOf(sold),
            entryLamports = BigInteger.valueOf(lamports),
            isPaper = false,
        )

    @Test fun clean_positions_pass_all_invariants() {
        val r = PortfolioInvariants6405.verify(listOf(pos(100L, 40L)))
        assertTrue(r.allPass)
    }

    @Test fun flags_over_sold_position() {
        val r = PortfolioInvariants6405.verify(listOf(pos(100L, 120L)))
        assertFalse(r.allPass)
        assertTrue(r.violations.any { it.contains("I3_OVER_SOLD_ENTRY_INVARIANT") })
    }

    @Test fun flags_zero_entry_lamports() {
        val r = PortfolioInvariants6405.verify(listOf(pos(100L, 40L, lamports = 0L)))
        assertFalse(r.allPass)
        assertTrue(r.violations.any { it.contains("I4_ENTRY_LAMPORTS_NON_POSITIVE") })
    }

    @Test fun wallet_ledger_parity_within_tolerance() {
        val ok = PortfolioInvariants6405.verifyWalletParity(
            "M", 1L,
            walletRaw = BigInteger.valueOf(1_000L),
            ledgerRemainingRaw = BigInteger.valueOf(1_002L),
            toleranceRaw = BigInteger.valueOf(10L),
        )
        assertTrue(ok)
    }

    @Test fun wallet_ledger_parity_outside_tolerance_fails() {
        val ok = PortfolioInvariants6405.verifyWalletParity(
            "M", 1L,
            walletRaw = BigInteger.valueOf(1_000L),
            ledgerRemainingRaw = BigInteger.valueOf(2_000L),
        )
        assertFalse(ok)
    }
}

class Bundle6405CapitalRecyclingTest {

    @After fun tearDown() { GlobalEntryPolicy6405.clearForTest() }

    private fun input(
        realised: Long,
        base: Long = 1_000_000L,
        max: Long = 10_000_000L,
        integrity: Boolean = true,
    ) = CapitalRecyclingOrchestrator6405.Input(
        lane = "SHITCOIN",
        closedRealisedLamports = BigInteger.valueOf(realised),
        currentBaseLamports = BigInteger.valueOf(base),
        maxLamports = BigInteger.valueOf(max),
        compoundFraction = 0.25,
        integrityPass = integrity,
        laneCoolDownAfterLossMs = 60_000L,
        mintForCooldown = "M",
    )

    @Test fun integrity_failure_blocks() {
        val d = CapitalRecyclingOrchestrator6405.decide(input(1_000L, integrity = false))
        assertEquals(CapitalRecyclingOrchestrator6405.Result.BLOCKED_INTEGRITY, d.result)
    }

    @Test fun loss_produces_cooldown() {
        val d = CapitalRecyclingOrchestrator6405.decide(input(-500_000L))
        assertEquals(CapitalRecyclingOrchestrator6405.Result.HOLD_COOLDOWN, d.result)
    }

    @Test fun profit_within_cap_compounds_same_lane() {
        val d = CapitalRecyclingOrchestrator6405.decide(input(1_000_000L))
        assertEquals(CapitalRecyclingOrchestrator6405.Result.REINVEST_SAME_LANE, d.result)
        assertEquals(BigInteger.valueOf(1_250_000L), d.nextBaseLamports)
    }

    @Test fun profit_beyond_cap_rotates_to_stable() {
        val d = CapitalRecyclingOrchestrator6405.decide(input(100_000_000L, base = 8_000_000L))
        assertEquals(CapitalRecyclingOrchestrator6405.Result.REINVEST_ROTATE_TO_STABLE, d.result)
        assertEquals(BigInteger.valueOf(10_000_000L), d.nextBaseLamports)
    }
}
