package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PrintWitness8750
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Signed cross-provider trade identity binds every launch consumer, including 8840 launch facts. */
class Aate8841SignedLaunchDedupTest {
    @Test fun duplicateProvidersPublishOneLaunchEvent() {
        TradeTape8100.resetForTest8100()
        val mint = "Aate8841" + System.nanoTime()
        val now = System.currentTimeMillis()
        val event = now - 1_000L
        val price = 0.000001
        assertTrue(TradeTape8100.note8285(mint, price, 0.1, 0.1 / price, true,
            TradeTape8100.SRC_HELIUS_8169, signature = "signed-event", eventMs = event, recvMs = now,
            priceBasis8750 = PrintWitness8750.Basis.RESERVES))
        assertEquals(1L to 0L, TradeTape8100.launchCounts8841())
        assertFalse(TradeTape8100.note8285(mint, price, 0.1, 0.1 / price, true,
            TradeTape8100.SRC_GECKO_8285, signature = "signed-event", eventMs = event, recvMs = now,
            priceBasis8750 = PrintWitness8750.Basis.RESERVES))
        assertEquals(1L to 1L, TradeTape8100.launchCounts8841())
        assertEquals(PrintWitness8750.Verdict.RESERVES,
            TradeTape8100.launchEvidence8750(mint, price, 0.1, 0.1 / price, true,
                "HELIUS_CURVE", "signed-event", event, now, PrintWitness8750.Basis.RESERVES))
        assertEquals(1L to 2L, TradeTape8100.launchCounts8841())
        assertEquals(PrintWitness8750.Verdict.OUT_OF_BAND,
            TradeTape8100.launchEvidence8750(mint, price * 1.5, 0.1, 0.1 / (price * 1.5), true,
                "HELIUS_CURVE", "signed-event", event, now, PrintWitness8750.Basis.RESERVES))
        assertEquals(1L to 3L, TradeTape8100.launchCounts8841())
    }

    @Test fun rejectedDecodeDoesNotStealValidLaterWitness() {
        TradeTape8100.resetForTest8100()
        val mint = "Aate8841" + System.nanoTime()
        val now = System.currentTimeMillis()
        val event = now - 1_000L
        val price = 0.000001
        assertFalse(TradeTape8100.note8285(mint, price * 1_000, 0.1, 0.1 / price, true,
            TradeTape8100.SRC_HELIUS_8169, signature = "rescued-event", eventMs = event, recvMs = now,
            priceBasis8750 = PrintWitness8750.Basis.RESERVES))
        assertEquals(0L to 0L, TradeTape8100.launchCounts8841())
        assertTrue(TradeTape8100.note8285(mint, price, 0.1, 0.1 / price, true,
            TradeTape8100.SRC_GECKO_8285, signature = "rescued-event", eventMs = event, recvMs = now,
            priceBasis8750 = PrintWitness8750.Basis.RESERVES))
        assertEquals(1L to 0L, TradeTape8100.launchCounts8841())
    }

    @Test fun sourceOrderingGuardsTheFullDataPipeline() {
        val tape = File("src/main/kotlin/com/lifecyclebot/engine/truth/TradeTape8100.kt").readText()
        val launch = tape.substringAfter("fun launchEvidence8750(").substringBefore("fun note8285(")
        assertTrue(launch.indexOf("launchPublished8841[key8841]") in 0 until launch.indexOf("LaunchHour8628.trade("))
        assertTrue(launch.indexOf("if (fresh8841) {") in 0 until launch.indexOf("LaunchFacts8840.onPrint8840("))
        assertTrue(launch.contains("MemePaperTrader8544.onPrint8756("))
        val note = tape.substringAfter("fun note8285(").substringBefore("fun signedFrom8285(")
        assertTrue(note.indexOf("seenSig8285.putIfAbsent") in 0 until note.indexOf("BuyerFlow8666.note("))
        val helius = File("src/main/kotlin/com/lifecyclebot/network/HeliusCurveTape8354.kt").readText()
        val entry = helius.substringAfter("fun onTradeEvent8354(").substringBefore("val witness8750 = try {")
        assertTrue(entry.indexOf("PrintWitness8750.verdict8750(") in 0 until entry.indexOf("CurveReserves7951.noteFrame7951("))
        assertTrue(entry.indexOf("TradeTape8100.duplicateSignedLaunch8841(") in 0 until entry.indexOf("CurveReserves7951.noteFrame7951("))
        val emit = helius.substringAfter("val taped = try {").substringBefore("return taped")
        assertTrue(emit.indexOf("if (taped) {") in 0 until emit.indexOf("PumpFunWS.emitHeliusCurve8354("))
    }
}
