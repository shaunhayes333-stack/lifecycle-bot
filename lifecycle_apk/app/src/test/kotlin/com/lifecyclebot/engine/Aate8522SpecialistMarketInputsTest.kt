package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import org.junit.Assert.*
import org.junit.Test

class Aate8522SpecialistMarketInputsTest {
    private val now = 1_800_000_000_000L
    private val emptyHistory = NativeHistory8521.Read(emptyList(), emptyList(), null, null, emptyList())
    private fun opinion(lane: String, eligible: Boolean = true, reason: String = "NATIVE_ALLOW") =
        SpecialistBrainBridge7542.Opinion(lane, eligible, 80, 80, 0.01, reason,
            "OWN_SETUP", "native_entry", "native_exit")

    @Test fun realDoorAndNativeReadUseTheObservedChangeInsteadOfAnUnstampedMomentum() {
        val ts = TokenState("market8522", lastPrice = 1.25, lastV3Score = 77, lastV3Confidence = 81)
        ts.momentum = 50.0
        ts.lastPriceChange5m = -6.0
        ts.priceChangeObservedAtMs8084 = now - 1L
        ts.lastBuyPressurePct = 62.0
        ts.buyPressureObservedAtMs8084 = now - 1L
        val birth = AdmissionBirth8144.Read8144(AdmissionBirth8144.Kind8144.KNOWN, now - 180_000L, 180_000L, "CHAIN_MINT_INIT")
        val nativeRead = SpecialistMarketInputs8522.read8522(ts, now, birth, emptyHistory)
        val sheet = ToolkitSignalSheet.Sheet(ToolkitSignalSheet.Setup.CASHFLOW_SCALP, 80.0, "observed", "entry", "exit",
            1.0, 1.0, 1.0, setOf("CASHGEN"), emptyMap(), emptySet(), emptyList(),
            executableLanes7863 = setOf("CASHGEN"), marketInputs8522 = nativeRead)
        ts.lastPriceChange5m = 50.0
        ts.lastV3Score = 20
        val doorRead = SpecialistMarketInputs8522.forCycle8522(sheet, ts)
        assertSame("the entry door receives the actual native evidence, not a second mutable read", nativeRead, doorRead)
        assertEquals(-6.0, doorRead.momentumPct!!, 0.0)
        val entry = com.lifecyclebot.v3.scoring.CashGenerationAI.evaluate(
            ts.mint, "TEST", doorRead.price, 70_000.0, 10.0, doorRead.buyPressurePct!!,
            doorRead.v3Score, doorRead.v3Confidence, doorRead.momentumPct!!, 20.0,
            tokenAgeMinutes = doorRead.age.inputMinutes, ownerLane = "CASHGEN")
        assertFalse("the actual cashflow entry door still refuses the observed fall", entry.shouldEnter)
        assertTrue(entry.reason, entry.reason.contains("momentum=") && entry.reason.contains("<-5"))
        assertEquals(62.0, doorRead.buyPressurePct!!, 0.0)
        assertEquals(77, doorRead.v3Score)
        assertEquals(81, doorRead.v3Confidence)
        assertEquals(1.25, doorRead.price, 0.0)
        assertEquals(3.0, doorRead.age.inputMinutes, 0.0)
    }

    @Test fun staleChangeDoesNotBeatOrderedObservedHistoryAndMissingDoesNotBecomeAFlatReading() {
        val ts = TokenState("history8522", lastPrice = 90.0)
        ts.momentum = 55.0
        ts.lastPriceChange5m = 45.0
        ts.priceChangeObservedAtMs8084 = now - 301_000L   // V5.0.8598 — just past the 300s fresh8491 window
        val history = NativeHistory8521.Read(listOf(100.0, 90.0), listOf(100.0, 90.0), 100.0, null, emptyList())
        val read = SpecialistMarketInputs8522.read8522(ts, now, null, history)
        assertNull(read.change5mPct)
        assertEquals(-10.0, read.momentumPct!!, 1e-9)
        assertTrue(read.age.inputMinutes.isNaN())
        val absent = SpecialistMarketInputs8522.read8522(ts, now, null, emptyHistory)
        assertNull(absent.momentumPct)
        assertNull(absent.buyPressurePct)
        assertEquals(0, absent.v3Score)
        assertEquals("unknown V3 confidence is not the old 25/30/50 prior", 0, absent.v3Confidence)
        ts.lastPriceChange5m = 0.0
        ts.priceChangeObservedAtMs8084 = now
        assertEquals(0.0, SpecialistMarketInputs8522.read8522(ts, now, null, emptyHistory).momentumPct!!, 0.0)
    }

    @Test fun recentWatchlistAdditionCannotTurnAnEstablishedCoinIntoAThreeSecondLaunch() {
        val ts = TokenState("birth8522", addedToWatchlistAt = now - 3_000L)
        val exact = AdmissionBirth8144.Read8144(AdmissionBirth8144.Kind8144.KNOWN,
            now - 86_400_000L, 86_400_000L, "CHAIN_MINT_INIT")
        assertEquals(1440.0, SpecialistMarketInputs8522.read8522(ts, now, exact, emptyHistory).age.inputMinutes, 0.0)
        val lower = AdmissionBirth8144.Read8144(AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN,
            0L, 180_000L, "FIRST_SEEN")
        val age = SpecialistMarketInputs8522.read8522(ts, now, lower, emptyHistory).age
        assertNull(age.exactMinutes)
        assertEquals(3.0, age.minimumMinutes!!, 0.0)
        assertTrue(SpecialistMarketInputs8522.read8522(ts, now, null, emptyHistory).age.inputMinutes.isNaN())
    }

    @Test fun cashflowNativeReadyMatchesItsExistingMandateWithoutVetoingTheMemeSetup() {
        for (lane in listOf("CASHGEN", "TREASURY")) {
            val ready = opinion(lane)
            assertEquals(ready, NativeLaneConstraints8521.requireCashflowRole8522(ready, 1_000_000.0, 50_000.0))
            val lowCap = NativeLaneConstraints8521.requireCashflowRole8522(ready, 999_999.0, 50_000.0)
            val lowLiq = NativeLaneConstraints8521.requireCashflowRole8522(ready, 1_000_000.0, 49_999.0)
            assertFalse(lowCap.eligible); assertFalse(lowLiq.eligible)
            assertTrue(lowCap.gradeable); assertTrue(lowLiq.gradeable)
            assertFalse(NativeLaneConstraints8521.missingData8521(lowCap.reason))
            assertFalse(NativeLaneConstraints8521.missingData8521(lowLiq.reason))
            for (unknown in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                val deferred = NativeLaneConstraints8521.requireCashflowRole8522(ready, 1_000_000.0, unknown)
                assertFalse(deferred.eligible); assertFalse(deferred.gradeable)
                assertTrue(NativeLaneConstraints8521.missingData8521(deferred.reason))
            }
        }
        val independent = opinion("MOONSHOT")
        assertEquals(independent, NativeLaneConstraints8521.requireCashflowRole8522(independent, 20_000.0, 3_000.0))
        val safetyRefusal = opinion("CASHGEN", false, "RUG_RISK")
        assertSame(safetyRefusal, NativeLaneConstraints8521.requireCashflowRole8522(safetyRefusal, 2_000_000.0, 70_000.0))
    }

    @Test fun nativeHistoryDeferralActuallyRequestsHydrationWithoutGrantingEligibilityOrDuplicatingFetches() {
        val refused = listOf(
            opinion("DIP_HUNTER", false, "NATIVE_PRICE_HISTORY_UNOBSERVED_DEFER_8521"),
            opinion("CYCLIC", false, "NATIVE_PRICE_HISTORY_UNOBSERVED_DEFER_8521"),
            opinion("EXPRESS", false, "NATIVE_MOMENTUM_UNOBSERVED_DEFER_8236"),
            opinion("CASHGEN", false, "NATIVE_CASHFLOW_ROLE_LIQ_FLOOR_8522"),
            opinion("QUALITY", false, "MCAP_TOO_LOW"), opinion("SHITCOIN", false, "RUG_RISK"),
        )
        val missing = SpecialistMarketInputs8522.missingNativeInputs8522(refused)
        assertEquals(listOf("shape:HISTORY_SPARSE", "chg:UNKNOWN"), missing)
        val prior = LanePlaybook7907.inputRefresher8085
        val requested = mutableListOf<List<String>>()
        try {
            LanePlaybook7907.inputRefresher8085 = { _, _, features -> requested += features }
            val ts = TokenState("native-hydration8522")
            LanePlaybook7907.requestEvidence8153(ts, missing, now)
            LanePlaybook7907.requestEvidence8153(ts, missing, now + 3_000L)
            assertEquals("the shared per-feature provider budget remains in force", listOf(missing), requested)
            assertTrue(refused.none { it.eligible })
        } finally { LanePlaybook7907.inputRefresher8085 = prior }
    }
}
