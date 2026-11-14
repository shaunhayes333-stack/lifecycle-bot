package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.PrintWitness8750
import com.lifecyclebot.engine.truth.PrintWitness8750.Verdict
import com.lifecyclebot.engine.truth.TradeTape8100
import com.lifecyclebot.network.PumpSwapPools8750
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8750 — evidence integrity (external review of 21641a619, verified): a print needs an independent price witness, a
 * PumpSwap pool's coin is read from chain, and the edge model's refusal must separate refused from kept coins.
 */
class Aate8750EvidenceIntegrityTest {
    @Test fun aPrintIsJudgedAgainstAnIndependentWitness() {
        val R = PrintWitness8750.Basis.RESERVES
        // a DECLARED reserves price whose own trade agrees -> RESERVES
        assertEquals(Verdict.RESERVES, PrintWitness8750.verdict8750(1.0e-6, 0.5, 0.5 / 1.01e-6, null, R))
        // the same numbers without the declaration are not evidence: provenance is declared, never inferred
        assertEquals(Verdict.UNWITNESSED, PrintWitness8750.verdict8750(1.0e-6, 0.5, 0.5 / 1.01e-6, null))
        // a reserves price whose own SOL / tokens is 1000x off (a decimal error in the quantity) -> refused
        assertEquals(Verdict.OUT_OF_BAND, PrintWitness8750.verdict8750(1.0e-6, 0.5, 0.5 / 1.0e-3, null, R))
        // a reserves price an independent reading contradicts -> refused
        assertEquals(Verdict.OUT_OF_BAND, PrintWitness8750.verdict8750(1.0e-6, 0.5, 0.5 / 1.01e-6, 1.0e-3, R))
        // the print's own ratio with no independent reading -> UNWITNESSED; agreeing reading -> WITNESSED; 1e6x off -> refused
        assertTrue(PrintWitness8750.ownRatio8750(2.0e-6, 0.2, 0.2 / 2.0e-6))
        assertEquals(Verdict.UNWITNESSED, PrintWitness8750.verdict8750(2.0e-6, 0.2, 0.2 / 2.0e-6, null))
        assertEquals(Verdict.WITNESSED, PrintWitness8750.verdict8750(2.0e-6, 0.2, 0.2 / 2.0e-6, 1.5e-6))
        assertEquals(Verdict.OUT_OF_BAND, PrintWitness8750.verdict8750(2.0, 0.2, 0.2 / 2.0, 2.0e-6))
    }

    @Test fun aCorruptedMillionXPrintCannotEnterTheTape() {
        TradeTape8100.resetForTest8100()
        val mint = "Wit8750" + "a".repeat(33) + "pump"
        val t0 = System.currentTimeMillis()
        // a curve print priced from reserves: witnessed, it becomes the coin's reference
        assertTrue(TradeTape8100.note8285(mint, 1.0e-6, 0.5, 0.5 / 1.01e-6, true, TradeTape8100.SRC_HELIUS_8169, signature = "W1", recvMs = t0,
            priceBasis8750 = PrintWitness8750.Basis.RESERVES))
        assertTrue(PrintWitness8750.witnessedPrint8750(mint, t0))
        // the same coin decoded a million times too high (unit error), self-priced: refused before the tape, history or sniper
        assertFalse(TradeTape8100.note8285(mint, 1.0, 0.5, 0.5, true, TradeTape8100.SRC_HELIUS_8169, signature = "W2", recvMs = t0 + 1_000L))
        assertEquals(1.0e-6, TradeTape8100.lastPrint8360(mint)!!.first, 1e-12)
        val tape = File("src/main/kotlin/com/lifecyclebot/engine/truth/TradeTape8100.kt").readText()
        val route = tape.substringAfter("fun launchEvidence8750(").substringBefore("fun note8285(")
        assertTrue(route.indexOf("PrintWitness8750.check8750(") in 0 until route.indexOf("LaunchHour8628.trade("))
        assertTrue(route.contains("if ((v == PrintWitness8750.Verdict.RESERVES || v == PrintWitness8750.Verdict.WITNESSED) && eventMs in 1L..recvMs) {"))
        // the launch history and the sniper are fed nowhere else
        val main = File("src/main/kotlin/com/lifecyclebot").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
        val feeders = main.filter { f -> f.readText().let { it.contains("LaunchHour8628.trade(") || it.contains("LaunchSniper8748.onPrint8748(") } }
            .map { it.name }.filter { it != "LaunchHour8628.kt" && it != "LaunchSniper8748.kt" }.toList()
        assertEquals(listOf("TradeTape8100.kt"), feeders)
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(lab.contains("PrintWitness8750.witnessedPrint8750(mint, tp.second)"))
    }

    @Test fun aPumpSwapPoolNamesItsCoinAtByte43() {
        val data = ByteArray(211)
        val base = ByteArray(32) { (it + 1).toByte() }; val quote = ByteArray(32) { (100 + it).toByte() }
        System.arraycopy(base, 0, data, 43, 32); System.arraycopy(quote, 0, data, 75, 32)
        val (b, q) = PumpSwapPools8750.mintsOf8750(data)!!
        assertEquals(io.github.novacrypto.base58.Base58.base58Encode(base), b)
        assertEquals(io.github.novacrypto.base58.Base58.base58Encode(quote), q)
        assertNull(PumpSwapPools8750.mintsOf8750(ByteArray(80)))
        val tape = File("src/main/kotlin/com/lifecyclebot/network/PumpSwapTape8307.kt").readText()
        assertTrue(tape.contains("programUnknownPool.incrementAndGet(); PumpSwapPools8750.resolve8750(ev.pool); continue"))
    }

    @Test fun aRefusalMustSeparateFromWhatItKeeps() {
        // the 8748 report's PROJECT_SNIPER shape: refused -16.9 % (sd 20) vs kept -16.1 % (sd 15), 120 coins each
        val o = EdgeModel8537.Oos()
        repeat(60) { o.add(EdgeModel8537.target8567(-20.0), -36.9) }; repeat(60) { o.add(EdgeModel8537.target8567(-20.0), 3.1) }
        repeat(60) { o.add(EdgeModel8537.target8567(5.0), -31.1) }; repeat(60) { o.add(EdgeModel8537.target8567(5.0), -1.1) }
        assertEquals(-16.9, o.refMean, 1e-9); assertEquals(-16.1, o.keepMean, 1e-9)
        assertTrue(o.refMean + o.refSe < 0.0 && o.refMean < o.keepMean)   // 8715's rule said REFUSES
        assertFalse(EdgeModel8537.refusalAuthority8715(o.refN, o.refMean, o.refSe, o.keepN, o.keepMean, o.keepSe))
    }

    @Test fun aQuietCurveStandsAtItsLastPriceOnlyWhileTheStreamHasNoGap() {
        val now = 10_000_000L
        val stands = { printMs: Long, px: Double, lastProgram: Long, since: Long, grad: Boolean ->
            com.lifecyclebot.engine.truth.QuietCurve8750.stands8750(printMs, px, now, lastProgram, since, grad) }
        assertTrue(stands(now - 600_000L, 3e-8, now - 2_000L, now - 3_600_000L, false))          // quiet 10 min, stream unbroken
        assertFalse(stands(now - 600_000L, 3e-8, now - 2_000L, now - 300_000L, false))           // the stream had a gap since
        assertFalse(stands(now - 600_000L, 3e-8, now - 60_000L, now - 3_600_000L, false))        // the stream is down now
        assertFalse(stands(now - 600_000L, 3e-8, now - 2_000L, now - 3_600_000L, true))          // graduated
        assertFalse(stands(now - 600_000L, 4e-7, now - 2_000L, now - 3_600_000L, false))         // near completion (400 SOL cap)
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(lab.contains("QuietCurve8750.mark8750(mint, nowMs)"))
    }
}
