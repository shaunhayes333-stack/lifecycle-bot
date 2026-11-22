package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ChainConfirm8950
import com.lifecyclebot.engine.truth.DataWaitRecovery8957
import com.lifecyclebot.engine.truth.LanePocket8934
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8957DataRecoveryTest {
    @Test fun missingDataAndProviderTimeoutsRemainRetryableButLossesAndSafetyDoNot() {
        for (prefix in listOf("CANONICAL_DEFERRED:", "CANONICAL_BLOCKED:")) {
            for (why in listOf("DATA_INCOMPLETE_DEFER_8136", "DATA_NEVER_ARRIVED_8418", "DATA_WAIT_SPENT_8418"))
                assertTrue(DataWaitRecovery8957.retryableCanonical(prefix + why))
            for (why in listOf("LIVE_NON_POSITIVE_EXPECTANCY_7828", "BIG_LOSER_8130", "SAFETY_RUG", "FALLING_HOUR_DEFER_8418"))
                assertFalse(DataWaitRecovery8957.retryableCanonical(prefix + why))
        }
        assertFalse(DataWaitRecovery8957.retryableCanonical("SOMETHING:DATA_INCOMPLETE_DEFER_8136"))
    }

    @Test fun criticalInputsReachTheSameAccountReaderAsUnprefixedInputs() {
        for (name in listOf("PRICE", "EXECUTABLE_PRICE", "SUPPLY", "DECIMALS", "MCAP", "HISTORY", "MOVE", "LIQUIDITY")) {
            val plain = ChainConfirm8950.inputsOf8950(listOf(name))
            assertTrue(name, plain.isNotEmpty())
            assertEquals(name, plain, ChainConfirm8950.inputsOf8950(listOf("CRITICAL_$name:UNKNOWN")))
        }
    }

    @Test fun restartPreservesMultiHorizonFactsAndOriginalClocksAcrossChains() {
        for (chain in listOf("solana", "ethereum", "bsc", "polygon")) {
            val original = DynamicAltTokenRegistry.DynToken("address", "TEST", "Test", chainId = chain,
                buys5m8832 = 0, sells5m8832 = 12, volume5m8832 = 125.0, change5mPct8832 = 0.0,
                shortWindowObservedAtMs8832 = 123L, change1hPct8886 = -4.0, change6hPct8886 = 15.0,
                change7dPct8886 = Double.NaN, trendObservedAtMs8886 = 100L)
            val disk = JSONObject(DynamicAltTokenRegistry.persistObservedWindows8957(JSONObject(), original).toString())
            val restored = DynamicAltTokenRegistry.restoreObservedWindows8957(disk,
                DynamicAltTokenRegistry.DynToken("address", "TEST", "Test", chainId = chain))
            assertEquals(chain, restored.chainId)
            assertEquals(0, restored.buys5m8832)
            assertEquals(12, restored.sells5m8832)
            assertEquals(125.0, restored.volume5m8832, 0.0)
            assertEquals(0.0, restored.change5mPct8832, 0.0)
            assertEquals(-4.0, restored.change1hPct8886, 0.0)
            assertEquals(15.0, restored.change6hPct8886, 0.0)
            assertTrue(restored.change7dPct8886.isNaN())
            assertEquals(123L, restored.shortWindowObservedAtMs8832)
            assertEquals(100L, restored.trendObservedAtMs8886)
        }
    }

    @Test fun oldSnapshotsDoNotFabricateTrendOrFlowObservations() {
        val restored = DynamicAltTokenRegistry.restoreObservedWindows8957(JSONObject(),
            DynamicAltTokenRegistry.DynToken("address", "TEST", "Test"))
        assertEquals(-1, restored.buys5m8832)
        assertTrue(restored.change5mPct8832.isNaN())
        assertTrue(restored.change1hPct8886.isNaN())
        assertEquals(0L, restored.shortWindowObservedAtMs8832)
        assertEquals(0L, restored.trendObservedAtMs8886)
    }

    @Test fun aLateFourHourMarkCannotBecomeAProfitableBluechipPocket() {
        LanePocket8934.resetForTest8934()
        try {
            val row = JSONObject().put("t", 1L).put("horizonMs", 14_400_000L).put("mint", "late")
                .put("lane", "BLUECHIP").put("mcap", 3_000_000.0).put("net", 999.0)
                .put("reason", "LATE_READ_8884:ADMIT")
            LanePocket8934.fileForTest8934(row)
            assertFalse(LanePocket8934.statusLine8934().contains("BLUECHIP=1"))
            row.put("mint", "timely").put("reason", "JUP_8884:ADMIT")
            LanePocket8934.fileForTest8934(row)
            assertTrue(LanePocket8934.statusLine8934().contains("BLUECHIP=1"))
        } finally { LanePocket8934.resetForTest8934() }
    }

    @Test fun deferredSignalsRefreshAndReleaseOwnershipWithoutOpeningATwin() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val arm = src.substringAfter("is com.lifecyclebot.engine.truth.CanonicalAssetEntryResult6551.Deferred -> {")
            .substringBefore("val canonicalFinalSize6570")
        assertTrue(arm.contains("refreshDeferredInputs8957(candidate.assetKey)"))
        assertTrue(arm.contains("terminalDisposition6613(\"CANONICAL_DEFERRED:"))
        assertFalse(arm.contains("CryptoTwin8883.open"))
        val clock = File("src/main/kotlin/com/lifecyclebot/engine/truth/LabelClock8442.kt").readText()
        assertTrue(clock.contains("ResearchFollow8884.ensureStarted8884()"))
    }

    @Test fun stoppedStockDeskRetriesUnconfirmedSellsBeforeEntrySettings() {
        val src = File("src/main/kotlin/com/lifecyclebot/xstocks/XsDesk.kt").readText()
        val risk = src.substringAfter("private fun liveRisk(now: Long, today: Long) {")
            .substringBefore("fun clearKill()")
        assertTrue(risk.indexOf("if (killed)") < risk.indexOf("val cfg = cfg()"))
        assertTrue(risk.substringBefore("val cfg = cfg()").contains("for (p in livePositions()) close(p,"))
        val daily = src.substringAfter("private fun daily(now: Long, today: Long) {").substringBefore("// ── marks")
        assertTrue(daily.indexOf("if (ready.isEmpty())") < daily.indexOf("lastEvalDay = today"))
    }
}
