package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.RunnerModel8861
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * V5.0.8861 — the runner model: the tape's shape at the $5,000 cross -> "doubled before the stop", rows kept past the tape's
 * 48 hours, the exit graded on the whole stored tape, armed on its forward record only.
 */
class Aate8861RunnerModelTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val M = RunnerModel8861
    private val UNNAMED = Int.MIN_VALUE

    @After fun tearDown() { M.resetForTest8861() }

    @Test fun theFeaturesAreTheTapeAsItStandsAtTheCross() {
        val t = longArrayOf(0L, 2_000L, 20_000L, 50_000L, 70_000L)
        val p = doubleArrayOf(1.0, 1.2, 0.9, 1.5, 1.8)
        val sol = doubleArrayOf(1.0, 0.5, 0.3, 2.0, 1.0)
        val buy = booleanArrayOf(true, true, false, true, true)
        val wallet = intArrayOf(10, 11, 10, 11, UNNAMED)
        val x = M.features8861(t, p, sol, buy, wallet, 5)
        assertEquals(M.FEATURES_8861.size, x.size)
        fun f(name: String) = x[M.FEATURES_8861.indexOf(name)]
        assertEquals(70.0, f("secsSinceFirstPrint"), 0.0); assertEquals(5.0, f("prints"), 0.0); assertEquals(5.0 / (70.0 / 60.0), f("printsPerMin"), 1e-12)
        assertEquals(0.8, f("buyTxShare"), 1e-12); assertEquals(4.2 / 4.8, f("netSolShare"), 1e-12); assertEquals(4.5, f("buySol"), 1e-12)
        assertEquals(1.125, f("avgBuySol"), 1e-12)
        assertEquals("the unnamed buy has a size but no buyer", 2.0, f("uniqueBuyers"), 0.0)
        assertEquals(2.5 / 4.5, f("largestBuyerShare"), 1e-12); assertEquals(3.5 / 4.5, f("top3BuyerShare"), 1e-12)
        assertEquals(0.5, f("repeatBuyerShare"), 1e-12); assertEquals(1.5 / 4.5, f("earlyBuyShare3s"), 1e-12)
        assertEquals(1.0, f("runupFromLow"), 1e-12); assertEquals(0.0, f("fromPeak"), 1e-12)
        assertEquals(0.2, f("ret10s"), 1e-12); assertEquals(1.0, f("ret30s"), 1e-12); assertEquals(0.5, f("ret60s"), 1e-12)
        assertEquals(1.0, f("buys10s"), 0.0); assertEquals(2.0, f("buys30s"), 0.0); assertEquals(2.0, f("buys60s"), 0.0)
        assertTrue("no buy in the half-minute before: acceleration is unread, not zero", f("txAccel").isNaN() && f("volAccel").isNaN())
        assertEquals(0.0, f("newBuyers60s"), 0.0); assertEquals(0.3 / 3.3, f("sellSolShare60s"), 1e-12)
        // the same tape one print earlier is a different reading: nothing after the cross is seen
        assertEquals(50.0, M.features8861(t, p, sol, buy, wallet, 4)[0], 0.0)
        // the time origin does not matter (the replay counts from birth, the live path from its own birth read)
        val shifted = M.features8861(LongArray(5) { t[it] + 9_000L }, p, sol, buy, wallet, 5)
        for (i in x.indices) assertTrue(M.FEATURES_8861[i], (x[i].isNaN() && shifted[i].isNaN()) || x[i] == shifted[i])
    }

    @Test fun theCrossIsTheFirstPrintAtTheFloor() {
        val p = doubleArrayOf(3.0e-8, 5.0e-8, 6.9e-8, 6.0e-8, 9.0e-8)
        assertEquals(2, M.cross8861(p, 5, 6.85e-8))
        assertEquals(-1, M.cross8861(p, 2, 6.85e-8))
        assertEquals(-1, M.cross8861(p, 5, 1.0e-7))
        assertEquals(0.7, M.quantile8861(doubleArrayOf(0.9, 0.1, 0.7, 0.3, 0.5), 0.75), 0.0)
        assertTrue(M.quantile8861(DoubleArray(0), 0.5).isNaN())
    }

    @Test fun doubledMeansTwiceTheEntryBeforeTheStopAndBeforeTheTimeStop() {
        val t = longArrayOf(0L, 10_000L, 20_000L, 30_000L)
        assertTrue(M.doubled8861(t, doubleArrayOf(1.0, 1.4, 1.9, 2.0), 0, 0.8, 600_000L))
        assertFalse("the stop came first", M.doubled8861(t, doubleArrayOf(1.0, 0.8, 2.5, 3.0), 0, 0.8, 600_000L))
        assertFalse("never got there", M.doubled8861(t, doubleArrayOf(1.0, 1.4, 1.9, 1.99), 0, 0.8, 600_000L))
        // ten minutes without +20 %: the doctrine is out, whatever happens after
        val slow = longArrayOf(0L, 300_000L, 600_000L, 900_000L)
        assertFalse(M.doubled8861(slow, doubleArrayOf(1.0, 1.1, 1.15, 2.5), 0, 0.8, 600_000L))
        assertTrue("it made +20 % inside the ten minutes, so it is still held", M.doubled8861(slow, doubleArrayOf(1.0, 1.3, 1.25, 2.5), 0, 0.8, 600_000L))
    }

    // a launch that crosses at 30 s, is bought 10 s later at 7.0e-8, makes +30 %, sits there for the hour, and doubles at 75 minutes
    private val hourT = longArrayOf(0L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L, 60_000L) + LongArray(11) { (it + 1) * 300_000L }
    private val hourP = doubleArrayOf(3.0e-8, 4.0e-8, 5.5e-8, 6.9e-8, 7.0e-8, 7.7e-8, 9.1e-8) + DoubleArray(11) { 9.1e-8 }
    private val allBuys = BooleanArray(hourT.size) { true }
    private val tail = Triple(longArrayOf(4_500_000L, 5_400_000L), doubleArrayOf(1.5e-7, 1.6e-7), booleanArrayOf(true, true))
    private val x0 = DoubleArray(RunnerModel8861.FEATURES_8861.size)

    @Test fun theHoldIsGradedOnTheWholeStoredTapeNotCutAtTheHour() {
        val cut = M.rowOf8861("MintA", 1_000L, x0, hourT, hourP, allBuys, allBuys, null, 30_000L, 6.9e-8, 10_000L, 3.0)!!
        assertFalse(cut.pastHour); assertFalse("sold at the hour's last print, it never doubled", cut.doubled)
        assertEquals("+30 % at the hour's last print, less cost", 27.0, cut.net, 1e-6)
        val whole = M.rowOf8861("MintA", 1_000L, x0, hourT, hourP, allBuys, allBuys, tail, 30_000L, 6.9e-8, 10_000L, 3.0)!!
        assertTrue(whole.pastHour); assertTrue("it doubled at 75 minutes", whole.doubled)
        assertEquals("a quarter sold at 2x, the rest at the last print", 122.0, whole.net, 1e-6)
        assertNull("a cross print the spike filter dropped is not a row", M.rowOf8861("MintA", 1_000L, x0, hourT, hourP, allBuys, allBuys, tail, 31_000L, 6.9e-8, 10_000L, 3.0))
        assertEquals(20.0, M.STOP_PCT_8861, 0.0)
        assertEquals("the followed clock's horizon after the hour", 4L * 3_600_000L, M.HOLD_MS_8861)
    }

    /** 500 launches in time order, 40 % of them with accelerating volume at the cross; exactly those doubled. */
    private fun rows(n: Int = 500) = List(n) { i ->
        val runs = i % 5 < 2
        val x = DoubleArray(M.FEATURES_8861.size); x[M.FEATURES_8861.indexOf("volAccel")] = if (runs) 3.0 else 1.0
        RunnerModel8861.Row("Mint$i", 1_000_000L + i * 60_000L, x, runs, if (runs) 100.0 else -20.0, false)
    }

    @Test fun theShareItBuysIsChosenOnLaterLaunchesAndReadOnTheNewest() {
        val r = rows()
        assertEquals(160, M.MIN_TRAIN_ROWS_8861)
        assertEquals(0.7, M.choose8861(r.subList(0, 300), r.subList(300, 400), 0L)!!, 0.0)
        assertNull("too few rows to grow a tree", M.choose8861(r.subList(0, 100), r.subList(100, 200), 0L))
        M.seedForTest8861(r, emptyList(), emptyList(), 0L)
        val line = M.statusLine8861()
        assertTrue(line, line.contains(" SHADOW model=fitted buys top 30% newestFifth{picks n40/+100.0±0.0%/win100%/2x+100% of all n100/+28.0±5.9%/win40%/2x+40%}"))
        assertTrue(line, line.contains("forward{picks n0 of every cross n0}"))
        assertTrue(line.contains("armed on the forward record only (n>=100, mean-2SE>0, positive without its best)]"))
    }

    @Test fun itIsArmedOnTheForwardRecordAlone() {
        fun rec(v: List<Double>) = RunnerModel8861.Record().also { r -> v.forEach(r::add) }
        val hundred = List(100) { if (it % 2 == 0) 60.0 else -10.0 }
        assertTrue(M.proves8861(rec(hundred)))
        assertFalse("99 picks", M.proves8861(rec(hundred.dropLast(1))))
        assertFalse("carried by one pick", M.proves8861(rec(List(99) { -1.0 } + 5_000.0)))
        assertFalse("not two standard errors above zero", M.proves8861(rec(List(100) { if (it % 2 == 0) 60.0 else -55.0 })))
        // a newest fifth that reads well arms nothing; the forward record does
        M.seedForTest8861(rows(), emptyList(), emptyList(), 0L)
        assertTrue(M.statusLine8861().contains(" SHADOW ")); assertNull(M.evidence8861("Mint1", 0L))
        M.seedForTest8861(rows(), hundred, hundred + hundred, 0L)
        assertTrue(M.statusLine8861().contains(" ARMED "))
        assertTrue(M.statusLine8861().contains("forward{picks n100/+25.0±3.5%/win50%/2x+0% of every cross n200/+25.0±2.5%/win50%/2x+0%}"))
        assertNull("armed, but the model made no pick on this coin", M.evidence8861("Mint1", 0L))
    }

    @Test fun theRowsAndTheForwardRecordAreKept() {
        val pick = RunnerModel8861.Record().also { it.add(40.0); it.add(-20.0) }
        val all = RunnerModel8861.Record().also { it.add(40.0); it.add(-20.0); it.add(-20.0) }
        val rs = rows(3)
        val bytes = ByteArrayOutputStream().also { o -> DataOutputStream(o).use { M.write8861(it, rs, pick, all) } }.toByteArray()
        val (back, p2, a2) = M.read8861(DataInputStream(ByteArrayInputStream(bytes)))!!
        assertEquals(3, back.size); assertEquals("Mint1", back[1].mint); assertEquals(rs[1].bornMs, back[1].bornMs)
        assertTrue(back[1].doubled && !back[2].doubled); assertEquals(3.0, back[0].x[M.FEATURES_8861.indexOf("volAccel")], 0.0)
        assertEquals(2, p2.n); assertEquals(10.0, p2.mean, 1e-12); assertEquals(40.0, p2.best, 0.0); assertEquals(3, a2.n)
        assertNull(M.read8861(DataInputStream(ByteArrayInputStream(ByteArray(40)))))
        assertNull("a file cut short", M.read8861(DataInputStream(ByteArrayInputStream(bytes.copyOf(bytes.size - 3)))))
        assertFalse("a record of chain prints is not a learning store", src("engine/truth/LearningResetSweep7781.kt").contains("runner_rows_8861"))
    }

    @Test fun theLivePathReadsThePrintAsTheTapeStoresItAndTheReplayReadsPastTheHour() {
        val tape = src("engine/truth/TradeTape8100.kt")
        val stored = tape.indexOf("LaunchHour8628.trade(mint, eventMs, sig, isBuy, sol, tokenQty, priceSol, recvMs, src, wallet)")
        val live = tape.indexOf("RunnerModel8861.onPrint8861(mint, eventMs, recvMs, stored, sol, isBuy, wallet)")
        assertTrue("beside the store, with the stored price", stored >= 0 && live > stored && live - stored < 500)
        assertTrue(tape.contains("if (sig.isNotBlank()) LaunchHour8628.observedPrice8642(sol, tokenQty, priceSol)?.let { stored ->"))
        val replay = src("engine/truth/LaunchReplay8744.kt")
        assertTrue(replay.contains("RunnerModel8861.onLaunch8861(r, solUsd, nowMs)") && replay.contains("RunnerModel8861.evaluate8861(cost, need, nowMs)"))
        assertTrue(src("engine/truth/LaunchHour8628.kt").contains("\"SELECT t,p,side FROM trade WHERE m=? AND t > ? AND t <= ? ORDER BY t,s,side\""))
        val model = src("engine/truth/RunnerModel8861.kt")
        assertTrue(model.contains("LaunchHour8628.tail8861(c.r.mint, c.r.bornMs, HOLD_MS_8861)"))
        assertTrue("a pick is forward only when the launch is newer than every row the model saw", model.contains("if (m0 != null && th0.isFinite() && r.bornMs > newestSeen)"))
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("?: RunnerModel8861.evidence8861(ts.mint, nowMs)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Runner model (§8861): "))
    }
}
