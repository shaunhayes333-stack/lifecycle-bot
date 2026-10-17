package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.PairHydrationState6398
import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 8197: regression fixtures for lost native routes and stranded qualified receivers. */
class Aate8197NativeHandoffAndHydrationTest {
    @Test fun nativeVenuePoolAndCanonicalDexPoolSurviveScannerPairMiss() {
        val h = PairHydrationState6398
        val now = 2_000_000_000_000L
        val t = TokenState(mint = "mint-meteora8197", symbol = "A")
        t.lastPriceDex = "METEORA"
        t.lastPricePoolAddr = "actual-meteora-pool"
        t.lastPriceUpdate = now
        val native = h.resolveFromToken8196(t, false, now - 120_000L, now)
        assertEquals(PairHydrationState6398.State.PAIR_SOURCE_NATIVE, native.state)
        assertEquals("actual-meteora-pool", native.pairAddress)
        assertEquals("SOURCE_NATIVE_POOL_8197", native.reason)

        t.lastPriceUpdate = now - 600_000L
        val stale = h.resolveFromToken8196(t, false, now - 120_000L, now)
        assertEquals(PairHydrationState6398.State.PAIR_PENDING_HYDRATION, stale.state)

        t.tokenMap.poolAddress = "verified-dex-pool"
        t.tokenMap.hydrationComplete = true
        t.tokenMap.updatedAtMs = now
        t.tokenMap.providerTimestamps["DEXSCREENER"] = now
        val confirmed = h.resolveFromToken8196(t, false, now - 120_000L, now)
        assertEquals(PairHydrationState6398.State.PAIR_CONFIRMED, confirmed.state)
        assertEquals("verified-dex-pool", confirmed.pairAddress)
    }

    @Test fun codexNativeLaunchesKeepTheirActualPoolsWithoutInventedDepth() {
        val h = PairHydrationState6398
        val now = 2_000_000_000_000L
        for (source in listOf("CODEX_NEW_METEORADBC", "CODEX_NEW_PUMPSWAP")) {
            val t = TokenState(mint = "mint-source-" + source, symbol = "S")
            t.source = source
            t.lastPricePoolAddr = "provider-pool-" + source
            t.lastPriceUpdate = now
            val result = h.resolveFromToken8196(t, false, now - 120_000L, now)
            assertEquals(PairHydrationState6398.State.PAIR_SOURCE_NATIVE, result.state)
            assertEquals(t.lastPricePoolAddr, result.pairAddress)
            assertEquals("SOURCE_NATIVE_POOL_8197", result.reason)
        }
    }

    @Test fun pumpCreateIsNotAFabricatedPoolAndRealCurveRemainsNative() {
        val h = PairHydrationState6398
        val now = 2_000_000_000_000L
        val t = TokenState(mint = "mint-pump8197", symbol = "P")
        t.source = "PUMP_FUN_NEW"
        val unproven = h.resolveFromToken8196(t, true, now - 120_000L, now)
        assertEquals(PairHydrationState6398.State.PAIR_PENDING_HYDRATION, unproven.state)
        assertNotEquals(t.mint, unproven.pairAddress)

        t.tokenMap.pumpFunBondingCurveAddress = "verified-pda-not-mint"
        t.tokenMap.pumpFunExecutable = true
        t.tokenMap.updatedAtMs = now
        val verified = h.resolveFromToken8196(t, true, now - 120_000L, now)
        assertEquals(PairHydrationState6398.State.PAIR_SOURCE_NATIVE, verified.state)
        assertEquals("verified-pda-not-mint", verified.pairAddress)
    }

    @Test fun softGatedNamedReceiverIsRetainedForNextQualifiedEvaluation() {
        val e = SpecialistElection8136
        val tasks = HandoffReceiverTasks8153
        val t0 = 2_000_000_000_000L
        val mint = "mint-data-receiver8197"
        e.resetForTests8136()
        try {
            val views = mapOf(
                "SHITCOIN" to SpecialistElection8136.NativeView8136(true, 65.0, "ALLOW"),
                "MOONSHOT" to SpecialistElection8136.NativeView8136(true, 60.0, "ALLOW"))
            e.registerNative8136(mint, 770L, t0, views, t0)
            e.cyclePrimary8136(mint, 770L, "SHITCOIN", false, null, t0)
            e.notePrimaryRefusal8136(mint, "SHITCOIN", "NATIVE:MCAP_TOO_HIGH", false, t0 + 1L)
            assertEquals("MOONSHOT", e.nameHandoffReceiver8153(mint, "PROJECT_SNIPER", "MOONSHOT", t0 + 2L))
            assertTrue(tasks.openTask8153(mint, "MOONSHOT", "PROJECT_SNIPER", "PHASE_HANDOFF", 770L,
                t0 + 2L, t0 + 2L)!!.pending)
            e.noteGateReason8144(mint, "MOONSHOT", "DATA_INCOMPLETE_DEFER_8136")
            e.noteNotEvaluated8136(mint, "MOONSHOT", "SHITCOIN", true, 60.0, t0 + 3L)
            assertEquals(SpecialistElection8136.Terminal8136.CONTRIBUTOR_SHADOW,
                e.terminalOf8136(mint, "MOONSHOT")?.first)
            assertTrue(tasks.isDue8153(mint, "MOONSHOT", t0 + 4L))
            assertEquals(ElectionRescueContract8144.ClaimOutcome8144.GRANTED,
                e.claimHandoffReceiver8153(mint, "MOONSHOT", "SHITCOIN", null, t0 + 5L))
        } finally { e.resetForTests8136() }
    }

    @Test fun aProviderExhaustionLatchGetsOneMinuteRecoveryProbes() {
        val h = HeliusCreditEconomy7881
        val last = 2_000_000_000_000L
        assertFalse(h.recoveryProbeDue8197(last + 59_999L, last))
        assertTrue(h.recoveryProbeDue8197(last + 60_000L, last))
        assertFalse(h.recoveryProbeDue8197(last + 60_001L, last + 1_000L))
    }

    @Test fun executablePumpMintRoutePassesWithoutInventedCurveAddress() {
        val h = PairHydrationState6398
        val now = 2_000_000_000_000L
        val ts = TokenState(mint = "pump-mint-route8197", symbol = "P")
        ts.source = "PUMP_FUN_NEW"
        ts.lastPrice = 0.00001
        ts.lastPriceUpdate = now
        ts.lastLiquidityUsd = 10_000.0
        ts.tokenMap.updatedAtMs = now
        ts.tokenMap.pumpFunExecutable = true
        val proven = h.resolveFromToken8196(ts, true, now - 120_000L, now)
        assertEquals(PairHydrationState6398.State.ROUTE_CONFIRMED_WITHOUT_PAIR, proven.state)
        assertEquals("PUMPFUN_DIRECT_EXECUTABLE_8197", proven.reason)
        assertEquals(null, proven.pairAddress)
        // Stale price may not manufacture a new current route.
        ts.lastPriceUpdate = now - 120_000L
        val stale = h.resolveFromToken8196(ts, true, now - 120_000L, now)
        assertEquals(PairHydrationState6398.State.PAIR_PENDING_HYDRATION, stale.state)
    }

    @Test fun everyQualifiedLaneIsEligibleForMissingDataRefreshNotOnlyCortexTopTwo() {
        val bridge = SpecialistBrainBridge7542
        fun opinion(lane: String, score: Int, eligible: Boolean = true, authoritative: Boolean = true) =
            SpecialistBrainBridge7542.Opinion(lane, eligible, score, score, 0.03,
                "NATIVE_READY", "SETUP", "ENTRY", "EXIT", authoritative = authoritative)
        val snap = SpecialistBrainBridge7542.Snapshot("mint-all-lanes8197", 1, 2_000_000_000_000L,
            mapOf("SHITCOIN" to opinion("SHITCOIN", 90),
                "MOONSHOT" to opinion("MOONSHOT", 80),
                "QUALITY" to opinion("QUALITY", 60),
                "EXPRESS" to opinion("EXPRESS", 50),
                "TREASURY" to opinion("TREASURY", 45),
                "BLOCKED" to opinion("BLOCKED", 100, false),
                "SHADOW" to opinion("SHADOW", 100, true, false)))
        val refresh = bridge.nativeEvidenceLanes8197(snap).map { it.lane }
        assertEquals(listOf("SHITCOIN", "MOONSHOT", "QUALITY", "EXPRESS", "TREASURY"), refresh)
        // The first two are just the Cortex prewarm budget; the remaining lanes
        // must still have their missing setup fields requested.
        assertEquals(5, refresh.size)
    }
}
