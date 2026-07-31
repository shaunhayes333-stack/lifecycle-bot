package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6401 §9 — PairHydrationState6398 wire assertions for the
 * INTAKE / NO_PAIR_NO_FALLBACK choke fix.
 *
 * 6401 snapshot (`INTAKE: allow=0 block=70 · INTAKE/NO_PAIR_NO_FALLBACK: 70`)
 * showed 100% of intake events blocked during DexScreener degradation.
 * The BotService no-pair fallback branch now:
 *   1. Fast-seeds `ts.lastPrice` from the pump.fun 1e9 supply
 *      constant when the source is pump.fun-native and mcap > 0, so
 *      `synthesizeFallbackPair` fires BEFORE the 15-20s oracle chain.
 *   2. Records the canonical PairHydrationState6398 snapshot before
 *      any demote decision, so operators can distinguish
 *      PAIR_PENDING_HYDRATION (hot for another 45s) from
 *      PAIR_HARD_UNAVAILABLE (all providers exhausted).
 *
 * These invariants cover the state-machine responses expected by the
 * wire path.
 */
class Bundle6401IntakeHydrationWireTest {

    @Before fun setUp() { PairHydrationState6398.clearAllForTest() }
    @After fun tearDown() { PairHydrationState6398.clearAllForTest() }

    private val MINT = "PumpFunMint111111111111111111111111111"

    @Test fun pumpfun_bonding_curve_resolves_source_native() {
        val snap = PairHydrationState6398.resolve(
            mint = MINT,
            dexscreenerPair = null,
            raydiumPoolFromScanner = null,
            pumpFunBondingCurve = MINT,   // pump.fun bonding curve IS the mint
            heliusPair = null,
            jupiterRouteOk = false,
            birdeyePair = null,
            watchlistLastKnownPair = null,
            providersAttempted = listOf("DEXSCREENER", "BIRDEYE"),
            hydrationStartedAtMs = System.currentTimeMillis(),
        )
        assertEquals(PairHydrationState6398.State.PAIR_SOURCE_NATIVE, snap.state)
        assertNotEquals("must NOT emit HARD_UNAVAILABLE while pump.fun bonding curve is a valid source",
            PairHydrationState6398.State.PAIR_HARD_UNAVAILABLE, snap.state)
    }

    @Test fun fresh_intake_stays_pending_during_hydration_ttl() {
        val now = System.currentTimeMillis()
        val snap = PairHydrationState6398.resolve(
            mint = "FreshMint2222222222222222222222222222222",
            dexscreenerPair = null,
            raydiumPoolFromScanner = null,
            pumpFunBondingCurve = null,
            heliusPair = null,
            jupiterRouteOk = false,
            birdeyePair = null,
            watchlistLastKnownPair = null,
            providersAttempted = listOf("DEXSCREENER"),
            hydrationStartedAtMs = now,           // just started
            nowMs = now + 5_000L,                 // 5s in — still under 45s TTL
        )
        assertEquals(PairHydrationState6398.State.PAIR_PENDING_HYDRATION, snap.state)
    }

    @Test fun aged_intake_after_ttl_becomes_hard_unavailable() {
        val started = System.currentTimeMillis() - PairHydrationState6398.HYDRATION_TTL_MS - 5_000L
        val snap = PairHydrationState6398.resolve(
            mint = "StaleMint3333333333333333333333333333333",
            dexscreenerPair = null,
            raydiumPoolFromScanner = null,
            pumpFunBondingCurve = null,
            heliusPair = null,
            jupiterRouteOk = false,
            birdeyePair = null,
            watchlistLastKnownPair = null,
            providersAttempted = listOf("DEXSCREENER", "BIRDEYE", "ORACLE"),
            hydrationStartedAtMs = started,
        )
        assertEquals(PairHydrationState6398.State.PAIR_HARD_UNAVAILABLE, snap.state)
        // Ledger increment must fire so operators see the transition.
        assertTrue(PairHydrationState6398.hardUnavailableEvents.get() >= 1L)
    }

    @Test fun raydium_scanner_pool_survives_dexscreener_degradation() {
        val snap = PairHydrationState6398.resolve(
            mint = "RaydiumMint44444444444444444444444444444",
            dexscreenerPair = null,
            raydiumPoolFromScanner = "poolAddr123",
            pumpFunBondingCurve = null,
            heliusPair = null,
            jupiterRouteOk = false,
            birdeyePair = null,
            watchlistLastKnownPair = null,
            providersAttempted = listOf("DEXSCREENER"),
            hydrationStartedAtMs = System.currentTimeMillis(),
        )
        assertEquals(PairHydrationState6398.State.PAIR_SOURCE_NATIVE, snap.state)
        assertEquals("poolAddr123", snap.pairAddress)
    }

    /** The operator directive: "Enabled routes must not fail when DexScreener is down." */
    @Test fun dexscreener_degraded_does_not_erase_source_native() {
        // Every non-DexScreener provider returned null EXCEPT pump.fun
        // bonding curve. Pipeline must resolve to SOURCE_NATIVE, NOT
        // HARD_UNAVAILABLE.
        val snap = PairHydrationState6398.resolve(
            mint = MINT,
            dexscreenerPair = null,   // DexScreener degraded
            raydiumPoolFromScanner = null,
            pumpFunBondingCurve = MINT,
            heliusPair = null,
            jupiterRouteOk = false,
            birdeyePair = null,
            watchlistLastKnownPair = null,
            providersAttempted = listOf("DEXSCREENER", "BIRDEYE", "ORACLE"),
            hydrationStartedAtMs = System.currentTimeMillis() - 60_000L,   // aged
        )
        assertEquals(PairHydrationState6398.State.PAIR_SOURCE_NATIVE, snap.state)
        assertEquals(0L, PairHydrationState6398.hardUnavailableEvents.get())
    }
}
