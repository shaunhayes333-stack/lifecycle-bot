package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.SignalTape8828
import com.lifecyclebot.network.PumpCurveKeys7269
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9007 — (1) a pump.fun curve seen trading on the program's own stream inside 30 s is a live curve for the LP rule,
 * not only one read by RPC; (2) the launch tape gains "bought at its open": 8+ prints and still within +15 % of the first
 * print at 20-40 s and at 60-80 s; (3) the lane hard cap holds an armed launch rule's own stop.
 */
class Aate9007PumpCurveAndAtOpenSignalTest {
    private val now = 1_795_600_000_000L

    @Test fun aProgramStreamFrameInsideThirtySecondsIsALiveCurve() {
        val id = "CurveStream9007AAAAAAAAAAAAAAAAAAAAAAAAApump"
        assertNull(CurveReserves7951.freshRealSol9007(id, now))
        assertFalse(LiquidityOwnership8522.pumpCurveByStream9007(id, now))
        CurveReserves7951.noteFrame7951(id, 34.0, 1_000_000.0, now - 5_000L)          // 4 SOL real
        assertEquals(4.0, CurveReserves7951.freshRealSol9007(id, now)!!, 1e-6)
        assertFalse("an account read is still a different thing", LiquidityOwnership8522.activeCurveVerified(id, now))
        assertTrue(LiquidityOwnership8522.pumpCurveByStream9007(id, now))
        assertTrue(LiquidityOwnership8522.pumpCurveLive9007(id, now))
        assertTrue(LiquidityOwnership8522.activeCurve8881(id, false, now).first)
        assertFalse("never for a migrated coin", LiquidityOwnership8522.activeCurve8881(id, true, now).first)
        assertFalse("30 s", LiquidityOwnership8522.pumpCurveByStream9007(id, now + 26_000L))
        PumpCurveKeys7269.markGraduated7392(id)
        assertFalse(LiquidityOwnership8522.pumpCurveByStream9007(id, now))
    }

    @Test fun aSuffixWithNoStreamAndAnEmptyCurveProveNothing() {
        assertFalse(LiquidityOwnership8522.pumpCurveByStream9007("NeverSeen9007AAAAAAAAAAAAAAAAAAAAAAAAAAApump", now))
        val thin = "ThinCurve9007AAAAAAAAAAAAAAAAAAAAAAAAAAApump"
        CurveReserves7951.noteFrame7951(thin, 30.3, 1_000_000.0, now - 1_000L)        // 0.3 SOL real: under the 0.5 exit floor
        assertFalse(LiquidityOwnership8522.pumpCurveByStream9007(thin, now))
        assertFalse(LiquidityOwnership8522.pumpCurveByStream9007("NotAPumpMint9007AAAAAAAAAAAAAAAAAAAAAAAAAAA", now))
    }

    /** [n] prints in bar 0 from 1.00e-7, bar 1 closing at [close]; returns what turns on when bar 2's first print closes bar 1. */
    private fun at20s(n: Int, close: Double): List<String> {
        val t = SignalTape8828()
        for (k in 0 until n) t.onPrint(500L + k * 900L, 1.00e-7, k % 2 == 0, 0.2, -1, true)
        t.onPrint(12_000, close, true, 0.2, -1, true)
        return t.onPrint(21_000, close, true, 0.2, -1, true)
    }

    @Test fun boughtAtItsOpenNotAfterItsPump() {
        val a = SignalTape8828.AT_OPEN_20S_9007; val b = SignalTape8828.AT_OPEN_60S_9007
        assertTrue(a in at20s(8, 1.10e-7))
        assertFalse("+40 % over its first print is the pump, not the open", a in at20s(8, 1.40e-7))
        assertFalse("too few prints", a in at20s(4, 1.05e-7))
        assertTrue("under its first print is still its open", a in at20s(8, 0.80e-7))
        assertFalse(b in at20s(8, 1.10e-7))
        // the 60 s setting: quiet through bar 5, bar 6's first print closes it
        val t = SignalTape8828()
        for (k in 0 until 8) t.onPrint(500L + k * 900L, 1.00e-7, true, 0.2, -1, true)
        for (bar in 1..5) t.onPrint(bar * 10_000L + 2_000L, 1.05e-7, true, 0.2, -1, true)
        assertTrue(b in t.onPrint(61_000, 1.05e-7, true, 0.2, -1, true))
        assertTrue(SignalTape8828.IDS.containsAll(listOf(a, b)))
        assertFalse(a in SignalTape8828.WALLET_IDS || b in SignalTape8828.WALLET_IDS)
    }

    @Test fun wired() {
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(ex.contains("if (worstPnl <= laneHardCap9007(ts, laneName)) {"))
        assertTrue(ex.contains("if (laneName != \"MANIPULATED\" && laneName != \"SHITCOIN\" && laneName != \"EXPRESS\") return Double.NEGATIVE_INFINITY"))
        assertTrue(ex.contains("LaunchSniper8748.ruleStopMag8949(ts.mint, ts.position.entryTime)"))
        assertTrue(ex.contains("doSell(ts, \"LANE_HARD_15PCT_SL_\$laneName\", wallet, walletSol)"))
        assertTrue(ex.contains("LiquidityOwnership8522.pumpCurveLive9007(ts.mint))"))
        val safety = File("src/main/kotlin/com/lifecyclebot/engine/TokenSafetyChecker.kt").readText()
        assertTrue(safety.contains("if (lpUnlockedRisk && !isPaperMode && !LiquidityOwnership8522.pumpCurveLive9007(mint)) {"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 192. Bought at its open, and a pump.fun curve has no LP to pull (9007)"))
    }
}
