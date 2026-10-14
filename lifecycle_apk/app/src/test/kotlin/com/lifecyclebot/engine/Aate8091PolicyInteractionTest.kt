package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.CortexExit7897
import com.lifecyclebot.engine.cortex.StopAuthority7887
import com.lifecyclebot.engine.truth.CostLedger7962
import com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot
import com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
import com.lifecyclebot.engine.truth.MissingMarkExitVeto6835
import com.lifecyclebot.engine.truth.TradeCostMeasurement8090
import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8091PolicyInteractionTest {
    @Test fun reEvaluationBeforeSealWinsAndAfterSealCannotRewritePositionOrSetup() {
        val prior = RuntimeModeAuthority.isPaper()
        val h = StrategyHypothesisEngine
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        h.reset(); ExecutionDecisionSnapshot6510.resetForTest()
        try {
            val first = "BREAKOUT>OLD_SETUP>QUICK_FLIP>BREAKOUT"
            val final = "GRADUATION>LAUNCH_LADDER_PROVEN>DIAMOND_HANDS_RUNNER>MOMENTUM"
            val later = "REVERSAL>NEW_SETUP>QUICK_FLIP>PULLBACK"
            h.getSizeBias("MOONSHOT", 60, "NORMAL", "seal8091", first, 8091L, "OLD_SETUP")
            h.getSizeBias("MOONSHOT", 60, "NORMAL", "seal8091", final, 8091L, "LAUNCH_LADDER_PROVEN")
            val sealed = ExecutionDecisionSnapshot6510.record(ExecutionDecisionSnapshot(
                "seal8091", 8091L, "BUY", "MOONSHOT", 60.0, System.currentTimeMillis(), mode = "PAPER", resolvedSizeSol = 0.03))
            h.getSizeBias("MOONSHOT", 70, "NORMAL", "seal8091", later, 8091L, "NEW_SETUP")
            assertEquals(sealed, ExecutionDecisionSnapshot6510.record(sealed.copy(score = 70.0, resolvedSizeSol = 0.06)))
            h.bindExecutedPosition7428("p8091", "seal8091", 8091L, "MOONSHOT", "PAPER")
            assertEquals(final.split('>'), h.exactEntryIdentity8090("p8091", "seal8091", "MOONSHOT", 8091L))
            assertEquals("LAUNCH_LADDER_PROVEN", h.entrySetupTag8091("p8091", "seal8091", "MOONSHOT", 8091L))
            val persisted = h.exportState(); h.reset(); h.importState(persisted)
            assertEquals(final.split('>'), h.exactEntryIdentity8090("p8091", "seal8091", "MOONSHOT", 8091L))
            assertEquals("LAUNCH_LADDER_PROVEN", h.entrySetupTag8091("p8091", "seal8091", "MOONSHOT", 8091L))
            assertNull(h.exactEntryIdentity8090("p8091", "otherMint", "MOONSHOT", 8091L))
        } finally {
            h.reset(); ExecutionDecisionSnapshot6510.resetForTest()
            RuntimeModeAuthority.publishConfig(paperMode = prior, autoTrade = true)
        }
    }

    private fun position(style: String) = TokenState(mint = "policy8091$style",
        position = Position(qtyToken = 1.0, entryPrice = 1.0, entryTime = 1L,
            tradingMode = "MOONSHOT", isPaperPosition = false,
            entryPolicySnapshot = "style=$style;entryTactic=BREAKOUT"))

    @Test fun staleMarkDoesNotChangeHoldingContractAndStopsUseTheSameFloorAsLabels() {
        val ts = position("DIAMOND_HANDS_RUNNER")
        ts.lastPrice = 0.85; ts.lastPriceUpdate = System.currentTimeMillis() - 10_000L
        assertNotNull(LifecycleHold8065.defer8065(ts, "CORTEX_EXIT_7897_MOONSHOT_FWD-7PCT"))
        assertNotNull(LifecycleHold8065.defer8065(ts, "STRICT_SL_-15"))
        assertEquals(LifecycleHold8065.FLOOR_PCT, StopAuthority7887.stopMagFor(ts), 0.0)
        assertEquals(StopAuthority7887.laneStopMag8029("MOONSHOT", true), StopAuthority7887.stopMagFor(ts), 0.0)
        for (reason in listOf("RUG_DETECTED", "RAPID_CATASTROPHE_STOP", "MANUAL_SELL", "KILL_SWITCH"))
            assertNull(LifecycleHold8065.defer8065(ts, reason))
        ts.lastPrice = 0.59
        assertNull(LifecycleHold8065.defer8065(ts, "STRICT_SL_-15"))
        val quick = position("QUICK_FLIP")
        assertNull(LifecycleHold8065.defer8065(quick, "CORTEX_EXIT_7897_MOONSHOT_FWD-7PCT"))
        assertTrue(StopAuthority7887.stopMagFor(quick) <= 25.0)
    }

    @Test fun cortexExitRequiresObservedPriceLikeOtherPriceDrivenExits() {
        MissingMarkExitVeto6835.clearForTest()
        try {
            val now = System.currentTimeMillis()
            val reason = "CORTEX_EXIT_7897_MOONSHOT_FWD-7PCT"
            assertFalse(MissingMarkExitVeto6835.evaluate("stale8091", 0.9, now - 61_000L, reason).allow)
            assertFalse(MissingMarkExitVeto6835.evaluate("missing8091", Double.NaN, now, reason).allow)
            assertTrue(MissingMarkExitVeto6835.evaluate("fresh8091", 0.9, now - 10_000L, reason).allow)
            assertTrue(MissingMarkExitVeto6835.evaluate("manual8091", Double.NaN, 0L, "MANUAL_SELL").allow)
        } finally { MissingMarkExitVeto6835.clearForTest() }
    }

    @Test fun unsignedTargetCannotRestoreMixedExitAuthority() {
        assertFalse(CortexExit7897.forwardTarget8091(JSONObject().put("books", JSONObject())))
        assertFalse(CortexExit7897.forwardTarget8091(JSONObject().put("target8091", "REALIZED_RETURN")))
        assertTrue(CortexExit7897.forwardTarget8091(JSONObject().put("target8091", CortexExit7897.TARGET_8091)))
    }

    @Test fun recoveryGraceDoesNotRetryUnobservableHistoricalEventsForever() {
        assertFalse(CostLedger7962.recoveryExpired8091(1000L, 180_999L))
        assertFalse(CostLedger7962.recoveryExpired8091(1000L, 181_000L))
        assertTrue(CostLedger7962.recoveryExpired8091(1000L, 181_001L))
        assertFalse(CostLedger7962.recoveryExpired8091(0L, 181_001L))
    }

    @Test fun favourableExecutionIsRetainedWithoutTurningCostsIntoEntrySubsidies() {
        val sales = listOf(TradeCostMeasurement8090.Sale(BigInteger("100"), 1.01, 2.0, 200.0))
        val result = TradeCostMeasurement8090.measure(0.99, BigInteger("100"), 0, 2.0, 200.0, sales)!!
        assertEquals(-2.0 / 0.99, result.costPct, 1e-9)
        assertEquals(-1.0, result.buyPct, 1e-9)
        assertEquals(0.0, CostLedger7962.measuredFrom7962(List(8) { doubleArrayOf(result.costPct, 0.99, result.buyPct) }, 0.99)!!, 0.0)
        assertNull(TradeCostMeasurement8090.measure(0.99, BigInteger("101"), 0, 2.0, 200.0, sales))
    }

    @Test fun firstSaleCanCreateTailWithoutClaimingItIsFreeOrIgnoringTheFloor() {
        assertEquals(TailBag8034.Decision.SetAside, TailBag8034.decidePure8034("MOONSHOT", "TIME_EXIT", 900.0, null, 1000L))
        assertFalse(TailBag8034.capitalRecovered8090("unfunded8091", "noProof8091"))
        val tail = TailBag8034.Tail(1L, 1L, 0)
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("MOONSHOT", "HARD_STOP", -41.0, tail, 1000L))
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("MOONSHOT", "RAPID_CATASTROPHE_STOP", -25.0, tail, 1000L))
    }
}
