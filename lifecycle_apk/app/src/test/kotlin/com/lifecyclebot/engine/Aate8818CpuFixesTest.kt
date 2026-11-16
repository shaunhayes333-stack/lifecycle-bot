package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.ColourShape8807
import com.lifecyclebot.engine.chart.OwnBars8816
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8818 — the work the 494b46bb CPU sampler named (error-log rows one transaction each, the alt registry scanned on
 * every miss, the regex number screen in the shadow book, the trade-mode regex per leaderboard row), the Cortex close
 * that an earlier snapshot already carried, and the own-chart store turning over.
 */
class Aate8818CpuFixesTest {
    private val min = 60_000L
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Before fun setUp() { OwnBars8816.resetForTest8816(); ColourShape8807.resetForTest8807(); OwnBars8816.solUsdForTest8816 = 100.0 }
    @After fun tearDown() { OwnBars8816.solUsdForTest8816 = 0.0 }

    @Test fun aFullChartStoreRetiresItsStalestCoinForANewOne() {
        val t0 = 50_000L * min
        for (i in 0 until OwnBars8816.MAX_MINTS) OwnBars8816.onPrint8816("M$i", t0, 0.001, 0.1, t0 + i)   // M0 is the stalest
        assertEquals(OwnBars8816.MAX_MINTS, OwnBars8816.recordingForTest8816())
        // every coin printed under ROOM_IDLE_MS ago: the new coin is refused
        OwnBars8816.onPrint8816("Fresh1", t0 + min, 0.001, 0.1, t0 + min)
        assertTrue(OwnBars8816.statusLine8816().contains("refusedFull=1 evictedForRoom8818=0"))
        // later, the stalest coin has been quiet long enough: it makes room
        for (i in 1 until OwnBars8816.MAX_MINTS) OwnBars8816.onPrint8816("M$i", t0 + 3 * min, 0.001, 0.1, t0 + 3 * min)
        OwnBars8816.onPrint8816("Fresh2", t0 + 3 * min, 0.001, 0.1, t0 + 3 * min + 1)
        val line = OwnBars8816.statusLine8816()
        assertTrue(line, line.contains("evictedForRoom8818=1") && line.contains("tooShort=1"))
        assertEquals(OwnBars8816.MAX_MINTS, OwnBars8816.recordingForTest8816())
        assertEquals(120, OwnBars8816.MAX_BARS)
        assertTrue("the SOL rate is read as of now", src("engine/chart/OwnBars8816.kt").contains("ShadowMark8360.solUsd8756(nowMs)"))
    }

    @Test fun tradeModesAreNormalisedOnceAndTheSame() {
        val a = TradeHistoryStore.normalizeTradeModeName("blue_chip")
        assertEquals("BLUECHIP", a)
        assertSame(a, TradeHistoryStore.normalizeTradeModeName("blue_chip"))
        assertEquals("CASHGEN", TradeHistoryStore.normalizeTradeModeName("Cash Generation"))
        assertEquals("STANDARD", TradeHistoryStore.normalizeTradeModeName("standard"))
        assertEquals("weird-lane", TradeHistoryStore.normalizeTradeModeName(" weird-lane "))
    }

    @Test fun theNamedHotSpotsAreFixedAtTheirSource() {
        val log = src("engine/ErrorLogger.kt")
        assertTrue(log.contains("database.beginTransaction()") && log.contains("database.setTransactionSuccessful()"))
        assertTrue(log.contains("DELETE FROM error_logs WHERE id <= (SELECT MAX(id) FROM error_logs) - \$MAX_LOGS"))
        assertTrue(log.contains("helper.setWriteAheadLoggingEnabled(true)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("ErrorLogger.statusLine8818()"))
        val reg = src("perps/DynamicAltTokenRegistry.kt").substringAfter("fun getTokenByMint(mint: String): DynToken? {").substringBefore("private class AddressIndex8818")
        assertTrue(!reg.contains("registry.values.filter") && reg.contains("addressIndex8818(now, force = true)"))
        val shadow = src("engine/truth/ShadowColdStore8397.kt").substringAfter("private fun decode(value: String?)").substringBefore("private fun encode(")
        assertTrue(!shadow.contains("?.toDoubleOrNull()") && shadow.contains("java.lang.Double.parseDouble(s)"))   // the KDoc names the old call; the code must not make it
        val cortex = src("engine/cortex/Cortex7885.kt").substringAfter("private fun durableCloseCommitLocked8325(").substringBefore("private const val WF_REFRESH_MS_8144")
        val joined = cortex.indexOf("if (additionalBlobs8372.isEmpty() && LearningPersistence.receiptCommitted8818(receipt)) {")
        assertTrue(joined in 0 until cortex.indexOf("val encode = snapshotEncoder8386(periodic = false) ?: return false"))
        assertTrue(src("engine/LearningPersistence.kt").contains("fun receiptCommitted8818(receipt: String): Boolean"))
    }
}
