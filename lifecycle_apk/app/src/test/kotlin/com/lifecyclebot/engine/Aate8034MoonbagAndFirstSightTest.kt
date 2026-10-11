package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.FirstSight8026
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8034 — the moonbag tail, and first sight buying the STRONG setups the Cortex already proves on small samples. */
class Aate8034MoonbagAndFirstSightTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val now = 1_000_000_000L

    @Test fun theFirstOrdinaryExitSetsATailAside() {
        assertEquals(TailBag8034.Decision.SetAside, TailBag8034.decidePure8034("MOONSHOT", "STRICT_SL_-15", -15.0, null, now))
        assertEquals(TailBag8034.Decision.SetAside, TailBag8034.decidePure8034("SHITCOIN", "TICK_PROFIT_LOCK_peak60_now40", 40.0, null, now))
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("QUALITY", "STRICT_SL_-12", -12.0, null, now))       // not a meme lane
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("MOONSHOT", "RUG_DETECTED_LIQUIDITY_PULLED", -80.0, null, now))
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("MOONSHOT", "MANUAL_SELL", 5.0, null, now))
        assertEquals(0.25, TailBag8034.TAIL_FRACTION, 0.0)
    }

    @Test fun theTailHoldsUntilARugOrItsMultiples() {
        val t = TailBag8034.Tail(entryTime = 1L, setAt = now, taken = 0)
        assertEquals(TailBag8034.Decision.Hold, TailBag8034.decidePure8034("MOONSHOT", "STRICT_SL_-15", -40.0, t, now + 60_000L))
        assertEquals(TailBag8034.Decision.Hold, TailBag8034.decidePure8034("MOONSHOT", "TICK_PROFIT_LOCK", 150.0, t, now + 60_000L))
        assertEquals(TailBag8034.Decision.Milestone(0, 1.0 / 3.0), TailBag8034.decidePure8034("MOONSHOT", "PEAK_TRAIL", 450.0, t, now + 60_000L))
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("MOONSHOT", "LIQUIDITY_COLLAPSE", -90.0, t, now + 60_000L))
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("MOONSHOT", "STRICT_SL_-15", -40.0, t, now + TailBag8034.TAIL_MAX_AGE_MS + 1))
        assertEquals(null, TailBag8034.milestoneAt8034(1_000.0, 1))
        assertEquals(1 to 0.5, TailBag8034.milestoneAt8034(2_000.0, 1))
        assertEquals(2 to 1.0, TailBag8034.milestoneAt8034(5_000.0, 2))
        assertTrue(TailBag8034.statusLine().contains("tails="))
    }

    @Test fun tailsAreWiredAtTheSellDoorAndTakeNoLaneSlot() {
        val e = src("engine/Executor.kt")
        assertTrue(e.contains("if (!ts.position.isPaperPosition) tailBagGate8034(ts, requestReason, wallet, walletSol)?.let { return it }"))
        assertTrue(e.contains("TailBag8034.partialHeld8034(ts, reason)"))
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains("!com.lifecyclebot.engine.TailBag8034.isTailMint8034(it.mint)"))
        assertTrue(src("engine/truth/CanonicalFinalizedTradeBus6464.kt").contains("TailBag8034.onClose8034(env)"))
        assertFalse(TailBag8034.isTailMint8034("none"))
    }

    @Test fun firstSightBuysASmallProvenStrongRecord() {
        assertTrue(FirstSight8026.smallSampleClears8034(10.0, 51.8, 20.0, 0.4))     // EXPRESS-like
        assertFalse(FirstSight8026.smallSampleClears8034(4.0, 51.8, 20.0, 0.4))     // too few
        assertFalse(FirstSight8026.smallSampleClears8034(10.0, 15.0, 14.0, 0.4))    // lower bound under +2%
        assertFalse(FirstSight8026.smallSampleClears8034(10.0, 51.8, 20.0, 0.1))    // win rate
        assertEquals(5.0, FirstSight8026.SMALL_SAMPLE_MIN_N_8034, 0.0)
        assertEquals(2.0, FirstSight8026.SMALL_SAMPLE_MARGIN_8034, 0.0)
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("!board.inverted7948(a.lane)) board.evidenceFor8025(a.lane) else -1.0"))
    }
}
