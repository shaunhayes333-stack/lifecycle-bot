package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.SignalReplay8828
import com.lifecyclebot.engine.truth.SignalTape8828
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8828 — the owner's signals read on the tape, graded on the stored launches, traded only once proven. */
class Aate8828OwnerSignalsGradedTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun genuineOpeningBidsTurnOnWhenTheFirstBarClosesOnALivePrint() {
        val t = SignalTape8828()
        assertEquals(emptyList<String>(), t.onPrint(1_000, 1e-7, true, 0.5, 11, true))
        assertEquals(emptyList<String>(), t.onPrint(5_000, 1e-7, true, 0.5, 12, true))
        assertEquals(emptyList<String>(), t.onPrint(9_000, 1e-7, true, 0.5, 13, true))
        // superseded by V5.0.9025 — three buys within 1.5x of the first print is also the quiet open (O9025A), on the same close
        assertEquals("the bar closes on the next bar's print", listOf("S001", SignalTape8828.QUIET_OPEN_9025), t.onPrint(12_000, 1.1e-7, true, 0.5, 14, true))
        // once per coin
        assertFalse("S001" in t.onPrint(25_000, 1.2e-7, true, 0.5, 15, true))
        // a backfilled closing print never triggers; the next live one may
        val late = SignalTape8828()
        late.onPrint(1_000, 1e-7, true, 0.5, 11, false); late.onPrint(5_000, 1e-7, true, 0.5, 12, false); late.onPrint(9_000, 1e-7, true, 0.5, 13, false)
        assertEquals(emptyList<String>(), late.onPrint(12_000, 1e-7, true, 0.5, 14, false))
        // unnamed buyers: a wallet signal stays off
        val anon = SignalTape8828()
        anon.onPrint(1_000, 1e-7, true, 0.5, -1, true); anon.onPrint(5_000, 1e-7, true, 0.5, -1, true); anon.onPrint(9_000, 1e-7, true, 0.5, -1, true)
        assertFalse("S001" in anon.onPrint(12_000, 1.1e-7, true, 0.5, -1, true))
    }

    @Test fun reactivationAfterAQuietSpellNeedsANewHighAndNewBuyers() {
        val t = SignalTape8828()
        t.onPrint(1_000, 1.0e-7, true, 0.2, 1, true); t.onPrint(4_000, 1.1e-7, false, 0.1, -1, true)
        t.onPrint(12_000, 1.05e-7, true, 0.2, 2, true)                       // bar 1
        assertTrue(t.onPrint(72_000, 1.3e-7, true, 0.3, 3, true).isEmpty())    // 60 s quiet, bar 7 opens
        t.onPrint(74_000, 1.35e-7, true, 0.3, 4, true)
        assertTrue("S012" in t.onPrint(81_000, 1.3e-7, true, 0.1, 5, true))   // bar 7 closes: new high, two new buyers
        // superseded by V5.0.8994: 15 signals until the base breakout's two settings joined the tape
        // superseded by V5.0.9007: 17 until the two bought-at-its-open settings joined
        // superseded by V5.0.9008: 19 until the two clean at-its-open settings joined
        // superseded by V5.0.9010 — two at-its-open-on-an-empty-curve settings (was 21)
        // superseded by V5.0.9025 — the quiet-open setting joins the tape (24)
        assertTrue(SignalTape8828.IDS.size == 24 && SignalTape8828.IDS.containsAll(SignalTape8828.WALLET_IDS))
    }

    @Test fun theReplayGradesEachSignalOnTheBotsLatencyAndCanProveIt() {
        val paths = (0 until 120).map { k ->
            val t = ArrayList<Long>(); val p = ArrayList<Double>(); val w = ArrayList<Int>()
            listOf(1_000L, 5_000L, 9_000L, 12_000L).forEachIndexed { i, ms -> t.add(ms); p.add(1e-7); w.add(i) }
            for (j in 0 until 30) { t.add(14_000L + 2_000L * j); p.add(1e-7 + 2e-7 * j / 29.0); w.add(4 + j) }
            LaunchReplay8744.Path8748(t.toLongArray(), p.toDoubleArray(), BooleanArray(t.size) { true }, BooleanArray(t.size) { true }, 0,
                DoubleArray(t.size) { 0.5 }, w.toIntArray(), bornMs = 1_000_000L * k)
        }
        val recs = SignalReplay8828.grade8828(paths, 100.0, 0.0, 10_000L)
        val s001 = recs.first { it.key == "S001 stop35%" }
        assertEquals(120, s001.n); assertTrue(s001.mean > 0.0 && s001.halfA > 0.0 && s001.halfB > 0.0 && s001.proven)
        assertTrue(s001.wallet)
        SignalReplay8828.evaluate8828(paths, 100.0, 0.0, 10_000L)
        assertNotNull(SignalReplay8828.proven8828())
        assertTrue(SignalReplay8828.labLine8828().contains("S001 stop35% n120"))
        // the proof bar: thin, or one losing half, is not proven
        val e = (0 until 200).map { SignalReplay8828.Entry(it.toLong(), if (it < 100) -5.0 else 30.0, 8_000.0) }
        assertFalse(SignalReplay8828.stats8828("S052", 35.0, e)!!.proven)
        assertFalse(SignalReplay8828.stats8828("S052", 35.0, e.drop(120))!!.proven)
        assertEquals(-1L, SignalReplay8828.walletKey8828(""))
        assertTrue(SignalReplay8828.walletKey8828("Wallet111") >= 0L)
    }

    @Test fun theLiveTapeIsTheGradedTapeAndEveryReaderIsWired() {
        val sn = src("engine/truth/LaunchSniper8748.kt")
        assertTrue(sn.contains("st.tape.onPrint(eventMs - st.bornMs, priceSol, isBuy, sol, SignalReplay8828.walletKey8828(wallet), live)"))
        assertTrue(sn.contains("\"SIGNAL8828:\${rec.key}\""))
        assertTrue(src("engine/truth/TradeTape8100.kt").contains("LaunchSniper8748.onPrint8748(mint, eventMs, priceSol, isBuy, recvMs, sig, sol, wallet)"))
        assertTrue(src("engine/truth/LaunchReplay8744.kt").contains("SignalReplay8828.evaluate8828(set, solUsd, cost, need)"))
        assertTrue(src("engine/truth/SignalReplay8828.kt").contains("val on = tape.onPrint(x.t[i], x.p[i], x.buy[i], x.sol?.getOrNull(i) ?: 0.0, w, x.live[i])"))
        assertTrue(src("engine/lab/LlmLabEngine.kt").contains("\$signals8828"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("SignalReplay8828.statusLine8828()"))
    }
}
