package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.MemeStrategies8546
import com.lifecyclebot.engine.truth.SmartMoney8756
import com.lifecyclebot.engine.truth.SmartMoney8756.Mode
import com.lifecyclebot.engine.truth.SmartMoney8756.Variant
import com.lifecyclebot.engine.truth.StopSlip8756
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8756 — owner: "do all 3 ... go to the internet search what a bot needs to capture these big wins". Graded smart money
 * (what FOLLOWING a wallet returned, fitted / chosen / tested on time-ordered launches), stops that run on the print and are
 * confirmed by the coin's own trades, stop slippage measured, and the scale-out ladder graded beside the doctrine.
 */
class Aate8756SmartMoneyAndFastExitsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    /** A launch printed every 10 s, all live buys of 0.5 SOL, [wallets] by print index ("" = unnamed). */
    private fun path(prices: List<Double>, wallets: Map<Int, String>, sol: Double = 0.5): LaunchReplay8744.Path8748 {
        val n = prices.size
        return LaunchReplay8744.clean8748(LongArray(n) { it * 10_000L }, prices.toDoubleArray(), BooleanArray(n) { true },
            BooleanArray(n) { true }, DoubleArray(n) { sol }, Array(n) { wallets[it] ?: "" }, 1L)
    }
    private val rising = (0..60).map { 1.0e-7 * (1.0 + it * 0.1) }          // 7x over ten minutes
    private val dumping = (0..60).map { 1.0e-7 * Math.pow(0.95, it.toDouble()) }

    @Test fun aWalletIsGradedOnWhatFollowingItReturned() {
        val up = path(rising, mapOf(1 to "W1"))
        val down = path(dumping, mapOf(1 to "W1"))
        assertTrue(SmartMoney8756.followNet8756(up, 1, 10_000L, 3.0)!! > 0.0)
        assertTrue(SmartMoney8756.followNet8756(down, 1, 10_000L, 3.0)!! < 0.0)
        assertEquals(mapOf("W1" to 1), SmartMoney8756.firstBuys8756(up))
        // a dust buy is not a buy to follow
        assertTrue(SmartMoney8756.firstBuys8756(path(rising, mapOf(1 to "W1"), sol = 0.01)).isEmpty())
    }

    @Test fun snipersAndLosersAreNeverProven() {
        val launches = listOf(
            path(rising, mapOf(0 to "SNIPER", 1 to "W1", 2 to "W2")),
            path(rising, mapOf(0 to "SNIPER", 1 to "W1", 2 to "W2")),
            path(dumping, mapOf(0 to "SNIPER", 1 to "LOSER")),
            path(dumping, mapOf(1 to "LOSER")),
        )
        val acc = SmartMoney8756.fit8756(launches, 10_000L, 3.0)
        assertEquals(2, acc["W1"]!!.n)
        assertEquals(3, acc["SNIPER"]!!.snipes)   // its first buys were inside the first 3 s
        val proven = SmartMoney8756.provenSet8756(acc, Variant(2, 0.0, Mode.SINGLE, 1.0))
        assertEquals(setOf("W1", "W2"), proven)   // the sniper made money on two of three, but snipers cannot be followed
        assertTrue(SmartMoney8756.provenSet8756(acc, Variant(3, 0.0, Mode.SINGLE, 1.0)).isEmpty())
    }

    @Test fun theSignalFiresOnTheFirstProvenBuyOrOnTwoConverging() {
        val x = path(rising, mapOf(0 to "S", 1 to "W1", 2 to "W2"))
        assertEquals(1, SmartMoney8756.fireIndex8756(x, setOf("W1"), Variant(3, 0.0, Mode.SINGLE, 1.0)))
        assertEquals(-1, SmartMoney8756.fireIndex8756(x, setOf("W1"), Variant(3, 0.0, Mode.CONVERGE, 1.0)))
        assertEquals(2, SmartMoney8756.fireIndex8756(x, setOf("W1", "W2"), Variant(3, 0.0, Mode.CONVERGE, 1.0)))
        // half of the buy SOL so far was bought in the first 3 s: over a 30 % sniper cap, the single-wallet signal waits
        assertEquals(0.5, SmartMoney8756.sniperShare8756(x, 1), 1e-12)
        assertEquals(2, SmartMoney8756.fireIndex8756(x, setOf("W1", "W2"), Variant(3, 0.0, Mode.SINGLE, 0.4)))
        assertEquals(-1, SmartMoney8756.fireIndex8756(x, emptySet(), Variant(3, 0.0, Mode.SINGLE, 1.0)))
    }

    @Test fun armedOnlyOnTheUntouchedTestRecord() {
        val v = Variant(5, 1.0, Mode.CONVERGE, 0.3)
        fun g(n: Int, ex: Double, se: Double) = SmartMoney8756.Graded(v, n, ex, 0.0, ex, se, 0.5, 0.1, 0.01)
        assertTrue(SmartMoney8756.armed8756(g(100, 5.0, 2.0)))
        assertFalse(SmartMoney8756.armed8756(g(99, 5.0, 2.0)))
        assertFalse(SmartMoney8756.armed8756(g(400, 1.0, 2.0)))
        assertFalse(SmartMoney8756.armed8756(null))
        val best = g(40, 8.0, 3.0)
        assertEquals(best, SmartMoney8756.choose8756(listOf(g(29, 50.0, 1.0), best, g(200, 4.0, 1.0))))
        assertEquals(24, SmartMoney8756.VARIANTS.size)
        val st = SmartMoney8756.stats8756(v, listOf(-10.0, -10.0, 5.0, 900.0, 900.0, 900.0))!!
        assertEquals(6, st.n); assertEquals(0.5, st.tenX, 1e-12); assertEquals(-5.0, st.exTop3, 1e-12)
    }

    @Test fun liveCountsOnlyProvenWalletsBuying() {
        SmartMoney8756.resetForTest8756()
        SmartMoney8756.setLiveForTest8756(Variant(3, 0.0, Mode.CONVERGE, 1.0), setOf("W1", "W2"), null)
        val mint = "Mint8756aaaaaaaaaaaaaaaaaaaaaaaaaaaaaapump"
        val t = 1_800_000_000_000L
        SmartMoney8756.onPrint8756(mint, "W1", t, true, 0.5, t + 1_000L)
        SmartMoney8756.onPrint8756(mint, "NOBODY", t, true, 5.0, t + 1_000L)
        SmartMoney8756.onPrint8756(mint, "W2", t, false, 0.5, t + 1_000L)    // a sell
        SmartMoney8756.onPrint8756(mint, "W2", t, true, 0.01, t + 1_000L)    // dust
        assertEquals(1, SmartMoney8756.provenBuyers8756(mint, 60_000L, t + 2_000L))
        SmartMoney8756.onPrint8756(mint, "W2", t + 5_000L, true, 0.5, t + 6_000L)
        assertEquals(2, SmartMoney8756.provenBuyers8756(mint, 60_000L, t + 7_000L))
        assertEquals(0, SmartMoney8756.provenBuyers8756(mint, 60_000L, t + 600_000L))
        assertNull(SmartMoney8756.evidence8756(mint, t + 7_000L))             // never armed: no evidence
        SmartMoney8756.resetForTest8756()
    }

    @Test fun followSizeNeedsAProvenWallet() {
        assertNull(MemeStrategies8546.followProven8756("largeBuys=3", 0))
        assertEquals("largeBuys=3 provenWallets=2", MemeStrategies8546.followProven8756("largeBuys=3", 2))
        assertNull(MemeStrategies8546.followProven8756(null, 4))
        val fam = src("engine/truth/MemeStrategies8546.kt")
        assertTrue(fam.contains("add(Family8546.FOLLOW_SIZE, followProven8756(ExpertSetups8692.followSize8692("))
        val flow = src("engine/truth/FlowRead8546.kt")
        assertTrue(flow.contains("SmartMoney8756.provenBuyers8756(mint, 300_000L, nowMs)"))
        assertFalse(flow.contains("SmartMoneyFeed6394.smartWallets8546(mint"))
    }

    @Test fun theCoinsOwnTradesConfirmAStop() {
        val now = 1_000_000L
        val onPool = { v: String -> v == "POOL" }
        val two = listOf(Triple(0.80, now - 1_000L, "POOL"), Triple(0.84, now - 1_500L, "POOL"))
        assertTrue(StopMarkConfirm8418.tapeVerdict8756(two, 1.0, -15.0, now, onPool))
        assertFalse(StopMarkConfirm8418.tapeVerdict8756(two.take(1), 1.0, -15.0, now, onPool))                          // one print is a wick
        assertFalse(StopMarkConfirm8418.tapeVerdict8756(listOf(two[0], Triple(0.90, now - 1_500L, "POOL")), 1.0, -15.0, now, onPool))
        assertFalse(StopMarkConfirm8418.tapeVerdict8756(two.map { it.copy(second = now - 5_000L) }, 1.0, -15.0, now, onPool))  // stale
        assertFalse(StopMarkConfirm8418.tapeVerdict8756(two.map { it.copy(third = "OTHER") }, 1.0, -15.0, now, onPool))       // another venue
        val pool = "P".repeat(44); val curveKey = "C".repeat(44)
        assertTrue(StopMarkConfirm8418.tapeOnVenue8756(pool, pool, onCurve = false, curveKey = null))
        assertFalse(StopMarkConfirm8418.tapeOnVenue8756("Q".repeat(44), pool, onCurve = false, curveKey = null))
        assertTrue(StopMarkConfirm8418.tapeOnVenue8756("pump.curve", "", onCurve = true, curveKey = curveKey))
        assertTrue(StopMarkConfirm8418.tapeOnVenue8756("pump.curve", curveKey, onCurve = true, curveKey = curveKey))
        assertFalse(StopMarkConfirm8418.tapeOnVenue8756("pump.curve", "", onCurve = false, curveKey = curveKey))   // graduated
        assertFalse(StopMarkConfirm8418.tapeOnVenue8756("pump.curve", pool, onCurve = true, curveKey = curveKey))  // filled on a pool
        val confirm = src("engine/StopMarkConfirm8418.kt").substringAfter("fun executableConfirms8419(")
        assertTrue(confirm.indexOf("tapeConfirms8756(ts, thresholdPct, nowMs)") in 0 until confirm.indexOf("reserveVerdict8485(ts, asked, nowMs, thresholdPct)"))
    }

    @Test fun stopSlipIsMeasuredPerBookAndLane() {
        StopSlip8756.resetForTest8756()
        StopSlip8756.notePaper8756("shitcoin", -20.0, -28.0, -36.0, true)
        StopSlip8756.noteLiveTrigger8756("m|1", "RAPID_FLUID_STOP_-15", -18.0, 1_000L)
        StopSlip8756.noteLiveFill8756("m|1", -40.0, 2_000L)
        StopSlip8756.noteLiveFill8756("m|1", -90.0, 3_000L)   // counted once
        val line = StopSlip8756.statusLine8756()
        assertTrue(line, line.contains("SHITCOIN:n1 level-20 trig-28 fill-36 print1"))
        assertTrue(line, line.contains("RAPID_FLUID_STOP:n1 level-15 trig-18 fill-40"))
        assertTrue(src("engine/Executor.kt").contains("StopSlip8756.noteLiveFill8756(slipKey8756, ts.lastExitPnlPct, ts.lastExitTs)"))
        StopSlip8756.resetForTest8756()
    }

    @Test fun thePaperBookExitsOnEveryWitnessedPrint() {
        val tape = src("engine/truth/TradeTape8100.kt").substringAfter("fun launchEvidence8750(").substringBefore("fun note8285(")
        assertTrue(tape.contains("MemePaperTrader8544.onPrint8756(mint, priceSol, eventMs)"))
        assertTrue(tape.contains("SmartMoney8756.onPrint8756(mint, wallet, eventMs, isBuy, sol, recvMs)"))
        assertTrue(tape.contains("LaunchHour8628.trade(mint, eventMs, sig, isBuy, sol, tokenQty, priceSol, recvMs, src, wallet)"))
        assertTrue(src("network/HeliusCurveTape8354.kt").contains("Basis.RESERVES, wallet = ev.user)"))
        val book = src("engine/truth/MemePaperTrader8544.kt")
        val tick = book.substringAfter("fun tick8544(").substringBefore("fun onPrint8756(")
        assertTrue(tick.contains("step8756(p, obs, markSource8560(obs?.source.orEmpty(), tokens[p.mint]), nowMs, stopLatency8699, normalLatency8699, byPrint = false)"))
        assertTrue(book.contains("StopSlip8756.notePaper8756(p.exitLane, p.slipLevel8756, p.slipTrig8756, g, p.slipByPrint8756)"))
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(adm.contains("?: SmartMoney8756.evidence8756(ts.mint, nowMs)\n"))
        assertTrue(src("engine/truth/LaunchReplay8744.kt").contains("SmartMoney8756.evaluate8756(set, cost, need)"))
    }

    @Test fun theScaleOutLadderIsGradedBesideTheDoctrine() {
        val n = 140
        val t = LongArray(n) { it * 10_000L }
        val p = DoubleArray(n) { i -> if (i <= 130) 1.0 + i * 0.1 else 14.0 - (i - 130) * 1.2 }   // to 14x, then a fall
        val x = LaunchReplay8744.clean8748(t, p, BooleanArray(n) { true }, null)
        val scale = LaunchReplay8744.exitNet8754(x, 0, LaunchReplay8744.Ladder.SCALE_OUT, 2.0, 0.7, 600_000L, 0.0)!!
        val doctrine = LaunchReplay8744.exitNet8754(x, 0, LaunchReplay8744.Ladder.DOCTRINE, 2.0, 0.7, 600_000L, 0.0)!!
        assertTrue(scale > 300.0 && doctrine > 300.0)
        val rule = LaunchReplay8744.Rule(10_000.0, 3, 2.0, 20.0, LaunchReplay8744.Ladder.SCALE_OUT, 10_000L)
        val r = LaunchReplay8744.stats8748(rule, List(60) { if (it < 50) -10.0 else 400.0 })!!
        val line = LaunchReplay8744.ladderLine8756(listOf(r))
        assertTrue(line, line.contains("SCALE_OUT=exTop3") && line.contains("HALF_TRAIL=-"))
    }
}
