package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.SmartMoney8756
import com.lifecyclebot.engine.truth.SmartMoney8756.Mode
import com.lifecyclebot.engine.truth.SmartMoney8756.Variant
import com.lifecyclebot.engine.truth.WalletLedger8860
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
 * V5.0.8860 — a wallet's follower record is kept past the tape's 48 hours: a launch leaving the window is graded once into
 * the ledger, left out of the window's own fit, and the kept record is added to what the window says.
 */
class Aate8860WalletLedgerTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val H = 3_600_000L
    private val names = listOf("WalletSniper", "WalletOne", "WalletTwo")

    @After fun tearDown() { WalletLedger8860.resetForTest8860() }

    /** A launch born at [born], printed every 10 s, all live buys of 0.5 SOL, wallet ids by print index. */
    private fun path(born: Long, prices: List<Double>, wallets: Map<Int, Int>): LaunchReplay8744.Path8748 {
        val n = prices.size
        return LaunchReplay8744.clean8748(LongArray(n) { it * 10_000L }, prices.toDoubleArray(), BooleanArray(n) { true },
            BooleanArray(n) { true }, DoubleArray(n) { 0.5 }, IntArray(n) { wallets[it] ?: -1 }, born)
    }
    private val rising = (0..60).map { 1.0e-7 * (1.0 + it * 0.1) }
    private val dumping = (0..60).map { 1.0e-7 * Math.pow(0.95, it.toDouble()) }

    @Test fun twoRecordsAddUpToTheRecordOfAllTheirFollows() {
        val a = listOf(12.0, -30.0, 55.0); val b = listOf(-8.0, 140.0)
        fun acc(v: List<Double>) = SmartMoney8756.Acc().also { x -> v.forEach(x::add) }
        val whole = acc(a + b); val pa = acc(a); val pb = acc(b)
        val (n, mean, m2) = WalletLedger8860.merged8860(pa.n, pa.mean, pa.m2, pb.n, pb.mean, pb.m2)
        assertEquals(whole.n, n); assertEquals(whole.mean, mean, 1e-9); assertEquals(whole.m2, m2, 1e-6)
        assertEquals(Triple(pb.n, pb.mean, pb.m2), WalletLedger8860.merged8860(0, 0.0, 0.0, pb.n, pb.mean, pb.m2))
        assertEquals(Triple(pa.n, pa.mean, pa.m2), WalletLedger8860.merged8860(pa.n, pa.mean, pa.m2, 0, 0.0, 0.0))
        // the window's record with the kept one added, snipes included
        val kept = WalletLedger8860.Rec(pb.n, pb.mean, pb.m2, snipes = 1, lastMs = 5L)
        val both = pa.also { it.snipes = 1 }.with8860(kept)
        assertEquals(5, both.n); assertEquals(whole.mean, both.mean, 1e-9); assertEquals(2, both.snipes)
        assertTrue("no kept record: the window's own", pa.with8860(null) === pa)
    }

    @Test fun aLaunchIsArchivedBeforeItLeavesTheTapeAndNeverWhileItsHourIsOpen() {
        val now = 1_795_000_000_000L
        val born = LongArray(10) { now - (10 - it) * H }                        // ten launches, 10 h .. 1 h old
        assertEquals("by age: 44 h", now - WalletLedger8860.ARCHIVE_AGE_MS_8860, WalletLedger8860.archiveCut8860(now, born, false, 0L))
        assertEquals("at the launch cap: the oldest fifth", born[2], WalletLedger8860.archiveCut8860(now, born, true, 0L))
        val fresh = LongArray(10) { now - (10 - it) * 60_000L }                 // a cap filled inside ten minutes
        assertEquals("never a launch whose first hour may still be printing",
            now - WalletLedger8860.COMPLETE_MS_8860, WalletLedger8860.archiveCut8860(now, fresh, true, 0L))
        assertEquals("the cut never moves back", now - H, WalletLedger8860.archiveCut8860(now, born, false, now - H))
        assertTrue(WalletLedger8860.ARCHIVE_AGE_MS_8860 < 48 * H && WalletLedger8860.COMPLETE_MS_8860 > H)
    }

    @Test fun anArchivedLaunchIsGradedOnceAndLeftOutOfTheWindow() {
        val now = 1_795_000_000_000L
        val old1 = path(now - 47 * H, rising, mapOf(0 to 0, 1 to 1, 2 to 2))    // leaving the tape: sniper at t=0, two followers
        val old2 = path(now - 45 * H, rising, mapOf(1 to 1))
        val recent = path(now - 5 * H, dumping, mapOf(1 to 1))
        val all = listOf(old1, old2, recent)
        val cut = WalletLedger8860.archive8860(all, names, now, false, 10_000L, 3.0)
        assertEquals(now - WalletLedger8860.ARCHIVE_AGE_MS_8860, cut)
        val kept = WalletLedger8860.snapshot8860()
        assertEquals(2, kept["WalletOne"]!!.n); assertEquals(1, kept["WalletTwo"]!!.n)
        assertTrue(kept["WalletOne"]!!.mean > 0.0)
        assertEquals("a first buy inside 3 s is a snipe", 1, kept["WalletSniper"]!!.snipes)
        assertEquals(now - 45 * H, kept["WalletOne"]!!.lastMs)
        // the same launches offered again on the next run: nothing is counted twice
        assertEquals(cut, WalletLedger8860.archive8860(all, names, now, false, 10_000L, 3.0))
        assertEquals(2, WalletLedger8860.snapshot8860()["WalletOne"]!!.n)
        assertTrue(WalletLedger8860.statusLine8860().startsWith("walletLedger8860[wallets=3 n>=3=0 n>=8=0 launchesArchived=2 thisRun=0 archivedTo=44hAgo atCap=false]"))
        // the window fit sees only the launch still in the window; the kept record makes the wallet's whole record
        val window = SmartMoney8756.fit8756(all.filter { it.bornMs >= cut }, 10_000L, 3.0)
        assertEquals(1, window[1]!!.n)
        val whole = window[1]!!.with8860(kept["WalletOne"])
        assertEquals(3, whole.n)
        assertTrue("three follows clear a bar one could not", SmartMoney8756.provenSet8756(mapOf("WalletOne" to whole), Variant(3, 0.0, Mode.SINGLE, 1.0)).isNotEmpty())
        assertTrue(SmartMoney8756.provenSet8756(mapOf("WalletOne" to window[1]!!), Variant(3, 0.0, Mode.SINGLE, 1.0)).isEmpty())
    }

    @Test fun theLedgerSurvivesARestartAndRefusesAFileThatIsNotOne() {
        val recs = mapOf("WalletOne" to WalletLedger8860.Rec(9, 14.5, 3_200.0, 2, 77L), "WalletTwo" to WalletLedger8860.Rec(1, -30.0, 0.0, 0, 5L))
        val bytes = ByteArrayOutputStream().also { o -> DataOutputStream(o).use { WalletLedger8860.write8860(it, 1_234L, 56L, recs) } }.toByteArray()
        val (archivedTo, launches, back) = WalletLedger8860.read8860(DataInputStream(ByteArrayInputStream(bytes)))!!
        assertEquals(1_234L, archivedTo); assertEquals(56L, launches); assertEquals(2, back.size)
        assertEquals(9, back["WalletOne"]!!.n); assertEquals(14.5, back["WalletOne"]!!.mean, 0.0); assertEquals(3_200.0, back["WalletOne"]!!.m2, 0.0)
        assertEquals(2, back["WalletOne"]!!.snipes); assertEquals(77L, back["WalletOne"]!!.lastMs)
        assertNull(WalletLedger8860.read8860(DataInputStream(ByteArrayInputStream(ByteArray(64)))))
        assertNull("a file cut short", WalletLedger8860.read8860(DataInputStream(ByteArrayInputStream(bytes.copyOf(bytes.size - 5)))))
    }

    @Test fun theGradingAddsTheKeptRecordWithoutBreakingItsTimeOrder() {
        val sm = src("engine/truth/SmartMoney8756.kt")
        val eval = sm.substringAfter("internal fun evaluate8756").substringBefore("// ── live ──")
        val archive = eval.indexOf("WalletLedger8860.archive8860(all, walletNames, nowMs, set.size >= LaunchReplay8744.MAX_LAUNCHES, delay, costPct)")
        assertTrue("archived before the window is split", archive in 0 until eval.indexOf("val paths = all.filter { it.bornMs >= cut }"))
        assertTrue(eval.contains("val accTrain = kept(fit8756(train, delay, costPct))"))
        assertTrue(eval.contains("val accTrainValid = kept(fit8756(paths.subList(0, b), delay, costPct))"))
        assertTrue("a wallet proven on its kept record alone still fires", eval.contains("for ((name, r) in prior) byName[name] = Acc().with8860(r)"))
        assertTrue(src("engine/truth/LaunchReplay8744.kt").contains("SmartMoney8756.evaluate8756(set, walletNames, cost, need)"))
        assertFalse("a record of chain prints is not a learning store", src("engine/truth/LearningResetSweep7781.kt").contains("wallet_ledger_8860"))
        assertTrue(sm.contains("\${WalletLedger8860.statusLine8860()}"))
    }
}
