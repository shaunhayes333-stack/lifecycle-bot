package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.market.BuyerFlow8666
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.ResearchRows8877
import com.lifecyclebot.perps.CryptoTwin8883
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8924 — the five: buyer flow on the research rows, the stranded paper basis, the crypto hour cap graded, the Cortex
 * seat sides made on first write, and the source timeouts named (with 8924's mark fan-out queue that waits instead of drops).
 */
class Aate8924AllFiveTest {

    @Test fun theResearchRowCarriesTheDecisionsBuyerFlow() {
        val v = BuyerFlow8666.View(atMs = 1L, distinctBuyers60s = 14, newBuyers15s = 5, buyersPerMin = 14.0, buySharePct = 71.5,
            netSol60s = 3.25, buyTx15s = 9, buyTxPrev15s = 4, repeatBuyers60s = 2, txPerMin = 30.0, volumeAccelScore = 62.0,
            sustainedDemand = true)
        val flow = ResearchRows8877.flowJson8924(v)
        val f = JSONObject(flow)
        assertEquals(14, f.getInt("buyers60")); assertEquals(5, f.getInt("new15")); assertEquals(71.5, f.getDouble("buyShare"), 1e-9)
        assertEquals(3.25, f.getDouble("netSol60"), 1e-9); assertEquals(9, f.getInt("buyTx15")); assertEquals(4, f.getInt("buyTxPrev15"))
        assertTrue(f.getBoolean("sustained"))
        assertEquals("no flow read: nothing written", "", ResearchRows8877.flowJson8924(null))
        val row = JSONObject(ResearchRows8877.line8877(1L, 3_600_000L, "m", "EXPRESS", true, "BASE_START", "S", 1.0, 0.0, 9_000.0,
            6_000.0, 3.0, 1.0, 4.0, 10.0, 8.0, -2.0, "r", "", flow))
        assertEquals(14, row.getJSONObject("flow8924").getInt("buyers60"))
        assertFalse(JSONObject(ResearchRows8877.line8877(1L, 3_600_000L, "m", "EXPRESS", true, "BASE_START", "S", 1.0, 0.0, 9_000.0,
            6_000.0, 3.0, 1.0, 4.0, 10.0, 8.0, -2.0, "r")).has("flow8924"))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(src.contains("it.flow8924 = try { ResearchRows8877.flowJson8924(com.lifecyclebot.engine.market.BuyerFlow8666.readForLearning9056(ts.mint, nowMs)) }"))
    }

    @Test fun theHourCapGainsAnHourOnlyWhereTheExtraHourIsProven() {
        CryptoTwin8883.resetForTest8883()
        assertFalse(CryptoTwin8883.holdExtends8924("CRYPTO_ALT"))
        for (i in 0 until 29) CryptoTwin8883.addHoldPairForTest8924("CRYPTO_ALT", 0.0, 6.0 + (i % 3))
        assertFalse("29 pairs: not yet proven", CryptoTwin8883.holdExtends8924("CRYPTO_ALT"))
        CryptoTwin8883.addHoldPairForTest8924("CRYPTO_ALT", 0.0, 7.0)
        assertTrue("30 pairs, the extra hour +7 % ± 0.15", CryptoTwin8883.holdExtends8924("crypto_alt "))
        for (i in 0 until 40) CryptoTwin8883.addHoldPairForTest8924("CRYPTO_SPOT", 2.0, 2.0 - 1.0 - (i % 2))
        assertFalse("a worse extra hour keeps the cap", CryptoTwin8883.holdExtends8924("CRYPTO_SPOT"))
        for (i in 0 until 40) CryptoTwin8883.addHoldPairForTest8924("CRYPTO_LEV", 0.0, if (i % 2 == 0) 30.0 else -29.0)
        assertFalse("a noisy coin-flip keeps the cap", CryptoTwin8883.holdExtends8924("CRYPTO_LEV"))
        assertTrue(CryptoTwin8883.holdLine8924().contains("CRYPTO_ALT{n30 extraHour+7.0%"))
        assertTrue(CryptoTwin8883.holdLine8924().contains("EXTENDS"))
        val g = CryptoTwin8883.holdGain8924(listOf(doubleArrayOf(1.0, 3.0), doubleArrayOf(1.0, 5.0)))
        assertEquals(2.0, g[0], 0.0); assertEquals(3.0, g[1], 1e-9); assertEquals(1.0, g[2], 1e-9)
        val enc = CryptoTwin8883.encodePairs8924(listOf(doubleArrayOf(1.5, -2.25), doubleArrayOf(0.0, 9.0)))
        val dec = CryptoTwin8883.decodePairs8924("$enc;bad;1.0,NaN;3")
        assertEquals(2, dec.size); assertEquals(-2.25, dec[0][1], 0.0); assertEquals(9.0, dec[1][1], 0.0)
        CryptoTwin8883.resetForTest8883()
        val desk = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(desk.contains("adaptiveMaxHoldSeconds = holdRecommendation6663?.maxSeconds"))
        assertTrue(desk.contains("if (signal.setupHoldSec8910 <= 0 && CryptoTwin8883.holdExtends8924(candidate.selectedLane)) (cap + CryptoTwin8883.HOLD_EXT_SEC_8924).coerceAtMost(2 * 3_600) else cap"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/perps/CryptoTwin8883.kt").readText()
            .contains("if (kind.contains(\"HOLD_MAX\") && ghosts8924.size < MAX_GHOSTS_8924) ghosts8924.putIfAbsent(key, Ghost8924(t.lane, at, pnl, nowMs))"))
    }

    @Test fun aStrandedBasisIsWrittenOffOnlyWhenExactlyAttributed() {
        assertEquals(0.0064, PaperAccountLedger6430.strandedWriteOff8924(0.0064, listOf(0.0064), unpaired = false)!!, 1e-12)
        assertNull("another lot holds open cost: kept", PaperAccountLedger6430.strandedWriteOff8924(0.5, listOf(0.0064), unpaired = false))
        assertNull("a receipt in flight: wait", PaperAccountLedger6430.strandedWriteOff8924(0.0064, listOf(0.0064), unpaired = true))
        assertNull(PaperAccountLedger6430.strandedWriteOff8924(0.0064, emptyList(), unpaired = false))
        assertNull(PaperAccountLedger6430.strandedWriteOff8924(0.0064, listOf(Double.NaN), unpaired = false))
        assertEquals(0.0164, PaperAccountLedger6430.strandedWriteOff8924(0.0164, listOf(0.0064, 0.01), unpaired = false)!!, 1e-12)
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTerminalBridge6469.kt").readText()
        assertTrue(bridge.contains("if (terminal && why.startsWith(\"QUARANTINED\")) try {"))
        val ledger = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt").readText()
        assertTrue("openCost and realised move together", ledger.contains("        openCostBasisPico.addAndGet(-toPico(off))\n        realizedPnlPico.addAndGet(-toPico(off))"))
    }

    @Test fun aSeatSideIsMadeOnItsFirstWriteAndReadsAsBefore() {
        val seat = CortexLedger7885.Seat(4)
        assertEquals(0, seat.sidesMade8924())
        assertEquals(0.0, seat.aboveRead8924().n, 0.0)
        assertEquals("a read makes no side", 0, seat.sidesMade8924())
        val emptyEnc = seat.encode()
        val old = CortexLedger7885.Seat(4)
        old.decode(emptyEnc)
        assertEquals("an empty stored side restores unmade", 0, old.sidesMade8924())
        seat.above.add(12.0, false)
        assertEquals(1, seat.sidesMade8924())
        val back = CortexLedger7885.Seat(4).also { it.decode(seat.encode()) }
        assertEquals(1, back.sidesMade8924())
        assertEquals(seat.encode(), back.encode())
        assertEquals(seat.above.n, back.aboveRead8924().n, 1e-12)
        assertEquals(seat.encode(), seat.detached8386().encode())
        assertTrue(CortexLedger7885.zeroEnc8924(CortexLedger7885.EMPTY_SIDE_ENC_8924))
        assertTrue(CortexLedger7885.zeroEnc8924("0.0,0.0,0.0,0.0"))
        assertFalse(CortexLedger7885.zeroEnc8924("1.0,2.0,4.0,0.0,1.0"))
        val led = CortexLedger7885()
        led.seats["v|CORE@HOT"] = CortexLedger7885.Seat(4).also { it.bins[1].add(5.0, false) }
        assertTrue(led.census8904(Any()).contains("sidesMade8924=0/2"))
    }

    @Test fun eachTimedOutSourceIsNamed() {
        ScannerSourceCircuitBreaker6363.reset()
        ScannerSourceCircuitBreaker6363.noteDone8924("DEXSCREENER", 1_000L)
        ScannerSourceCircuitBreaker6363.noteDone8924("DEXSCREENER", 3_000L)
        ScannerSourceCircuitBreaker6363.noteTimeout8924("DEXSCREENER", 4L, tripped = true)
        ScannerSourceCircuitBreaker6363.noteSkipped8924("DEXSCREENER")
        ScannerSourceCircuitBreaker6363.noteDone8924("PUMP_FUN_NEW", 500L)
        val line = ScannerSourceCircuitBreaker6363.line8924()
        assertTrue(line, line.contains("DEXSCREENER{to1/3 mean2000ms max3000ms enqDuring4 trips1 skipped1}"))
        assertFalse("a source that never timed out is not listed", line.contains("PUMP_FUN_NEW{"))
        ScannerSourceCircuitBreaker6363.reset()
        val fan = File("src/main/kotlin/com/lifecyclebot/network/ObservationFanout8527.kt").readText()
        assertTrue(fan.contains("Pools(sourceThreads = 12, leafThreads = 12, sourceQueue = 96, leafQueue = 144, prefix = \"mark-background-8527\")"))
        assertTrue(fan.contains("task.scope.remainingMs() // a queued-expired task never reaches a provider"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 112. All five (8924)"))
    }
}
