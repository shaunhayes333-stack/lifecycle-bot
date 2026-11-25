package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchSniper8748
import com.lifecyclebot.engine.truth.SignalReplay8828
import com.lifecyclebot.engine.truth.SignalTape8828
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9008 — the at-its-open settings with the first three seconds' buyers (other than the first print's wallet) under
 * 3 % of supply; a proven tape signal's fire holds its graded stop; the evidence funnel prints the final gate's own blocks.
 */
class Aate9008CleanOpenAndSignalStopTest {
    /** The creator buys [devSol], one other wallet buys [earlySol] at 1 s, six more buy at 4-9 s, bar 1 closes at +5 %. */
    private fun launch(devSol: Double, earlySol: Double): Pair<SignalTape8828, List<String>> {
        val t = SignalTape8828()
        t.onPrint(200, 1.00e-7, true, devSol, 1, true)
        t.onPrint(1_000, 1.00e-7, true, earlySol, 2, true)
        for (k in 0 until 6) t.onPrint(4_000L + k * 900L, 1.02e-7, true, 0.1, 10L + k, true)
        t.onPrint(12_000, 1.05e-7, true, 0.1, 20, true)
        return t to t.onPrint(21_000, 1.05e-7, true, 0.1, 21, true)
    }

    @Test fun theFirstSecondsBuyersDecideTheCleanSetting() {
        val open = SignalTape8828.AT_OPEN_20S_9007; val clean = SignalTape8828.AT_OPEN_CLEAN_20S_9008
        val (tapeA, a) = launch(devSol = 5.0, earlySol = 1.0)          // 1 SOL at 1e-7 = 1e7 tokens = 1 % of supply
        assertEquals(1.0, tapeA.earlyPct9008(), 1e-6)
        assertTrue(a.toString(), open in a && clean in a)
        val (tapeB, b) = launch(devSol = 5.0, earlySol = 4.0)          // 4 %: taken in its first seconds
        assertEquals(4.0, tapeB.earlyPct9008(), 1e-6)
        assertTrue(b.toString(), open in b)
        assertFalse(clean in b)
        assertTrue("the creator's own buy is not counted", launch(devSol = 40.0, earlySol = 1.0).first.earlyPct9008() < 3.0)
    }

    @Test fun withoutWalletsTheCleanSettingStaysOff() {
        val t = SignalTape8828()
        for (k in 0 until 8) t.onPrint(500L + k * 900L, 1.00e-7, true, 0.2, -1, true)
        t.onPrint(12_000, 1.05e-7, true, 0.2, -1, true)
        val on = t.onPrint(21_000, 1.05e-7, true, 0.2, -1, true)
        assertTrue(SignalTape8828.AT_OPEN_20S_9007 in on)
        assertFalse(SignalTape8828.AT_OPEN_CLEAN_20S_9008 in on)
        assertTrue(SignalTape8828.WALLET_IDS.containsAll(listOf(SignalTape8828.AT_OPEN_CLEAN_20S_9008, SignalTape8828.AT_OPEN_CLEAN_60S_9008)))
    }

    @Test fun aProvenSignalsFireHoldsItsGradedStop() {
        val rec = SignalReplay8828.Rec("O9008A", 35.0, 240, 18.0, 5.0, -6.0, 9.0, 4.0, 0.25, 0.01, 12.0, 15.0, 2_300.0, true)
        assertEquals(35.0, LaunchSniper8748.signalStop9008("SIGNAL8828:O9008A stop35%", rec)!!, 1e-9)
        assertNull(LaunchSniper8748.signalStop9008("SIGNAL8828:O9007A stop35%", rec))
        assertNull(LaunchSniper8748.signalStop9008("cap\$5k buys>=3 tp5x stop35% DOCTRINE +20s", rec))
        assertNull(LaunchSniper8748.signalStop9008("SIGNAL8828:O9008A stop35%", null))
    }

    @Test fun wired() {
        val sn = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchSniper8748.kt").readText()
        assertTrue(sn.contains("?: signalStop9008(f.ruleId, SignalReplay8828.proven8828()) ?: return"))
        assertTrue(sn.contains("admitted8949[mint] = stop9008 to nowMs"))
        val ep = File("src/main/kotlin/com/lifecyclebot/engine/DecisionEpisode8535.kt").readText()
        assertTrue(ep.contains("val fdg = f.why.entries.filter { it.key.startsWith(\"FDG_BLOCK:\") }.sortedByDescending { it.value }.take(8)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 193. Not already taken by its first seconds (9008)"))
    }
}
