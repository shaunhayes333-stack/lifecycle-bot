package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.truth.ExpertSetups8692
import com.lifecyclebot.engine.truth.MemeStrategies8546
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8692 — the expert playbook's setups and avoid shapes, and their measured (not paper) route to LIVE. */
class Aate8692ExpertPlaybookTest {
    private fun bar(i: Int, close: Double, high: Double = close, low: Double = close, open: Double = close, vol: Double? = 100.0) =
        Candle(ts = i * 60_000L, priceUsd = close, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0,
            highUsd = high, lowUsd = low, openUsd = open, periodVolumeUsd8521 = vol)

    private fun dipBars(vol: Boolean = true): List<Candle> {
        val run = (0..14).map { i -> val p = 1.0 + i * 0.1; bar(i, p, p * 1.01, p * 0.99, p - 0.05, if (vol) 100.0 else null) }
        return run + listOf(
            bar(15, 2.3, 2.45, 2.25, 2.4, if (vol) 100.0 else null),
            bar(16, 2.1, 2.3, 2.05, 2.3, if (vol) 100.0 else null),
            bar(17, 2.0, 2.1, 1.95, 2.1, if (vol) 300.0 else null),
            bar(18, 1.6, 2.0, 1.55, 2.0, if (vol) 400.0 else null),   // the -20 % flush on 4x volume
            bar(19, 1.7, 1.75, 1.6, 1.6, if (vol) 300.0 else null),
        )
    }

    @Test fun dipBuyNeedsARunAFlushOnVolumeAndRealVolume() {
        assertNotNull(ExpertSetups8692.dipBuyVolume8692(dipBars()))
        assertNull("no volume, no setup", ExpertSetups8692.dipBuyVolume8692(dipBars(vol = false)))
        val flat = (0..20).map { bar(it, 1.0, 1.01, 0.99, 1.0) }
        assertNull("a coin that never ran", ExpertSetups8692.dipBuyVolume8692(flat))
    }

    private fun buy(atMs: Long, sol: Double) = TradeTape8100.Print8128(atMs, 1e-7, sol, true)

    @Test fun followSizeIsANewCoinTakingSize() {
        val now = 1_000_000L
        assertNotNull(ExpertSetups8692.followSize8692(listOf(buy(now - 5_000, 6.0)), now, 2.0))
        assertNull("older than five minutes", ExpertSetups8692.followSize8692(listOf(buy(now - 5_000, 6.0)), now, 6.0))
        assertNotNull(ExpertSetups8692.followSize8692(listOf(buy(now - 9_000, 1.2), buy(now - 5_000, 1.5), buy(now - 2_000, 1.1)), now, 1.0))
        assertNull("spread out is not a cluster", ExpertSetups8692.followSize8692(listOf(buy(now - 50_000, 1.2), buy(now - 30_000, 1.5), buy(now - 2_000, 1.1)), now, 1.0))
        assertNull(ExpertSetups8692.followSize8692(listOf(buy(now - 5_000, 6.0)), now, null))
    }

    @Test fun spikeAndFadeAndStaircaseAreRecognised() {
        val spike = listOf(bar(0, 1.0, 1.01, 0.99, 1.0), bar(1, 1.0, 1.01, 0.99, 1.0),
            bar(2, 1.05, 1.5, 0.98, 1.0), bar(3, 1.03, 1.06, 1.02, 1.05), bar(4, 1.02, 1.04, 1.01, 1.03))
        assertTrue(ExpertSetups8692.spikeFade8692(spike, sellDominant = true))
        assertTrue("unknown tape does not clear it", ExpertSetups8692.spikeFade8692(spike, sellDominant = null))
        assertFalse("buyers in control is not a fade", ExpertSetups8692.spikeFade8692(spike, sellDominant = false))
        val highs = listOf(1.0, 1.5, 1.2, 1.4, 1.1, 1.3, 1.0, 0.9)
        val stairs = highs.mapIndexed { i, h -> bar(i, h * 0.95, h, h * 0.9, h * 0.95) }
        assertTrue(ExpertSetups8692.staircaseDown8692(stairs))
        val up = highs.reversed().mapIndexed { i, h -> bar(i, h * 0.95, h, h * 0.9, h * 0.95) }
        assertFalse(ExpertSetups8692.staircaseDown8692(up))
    }

    @Test fun setupsAreGradedAsTheirOwnCohortAndEarnLiveOnlyOnThatRecord() {
        assertEquals("SETUP8692_DIP_BUY_VOLUME", ExpertSetups8692.cohort8692("dip_buy_volume"))
        assertTrue(ExpertSetups8692.setupCohort8692("SETUP8692_FOLLOW_SIZE"))
        assertFalse(ExpertSetups8692.setupCohort8692("MOONSHOT"))
        assertNull("no signal, no edge", MemeStrategies8546.measuredProven8692("no-signal-mint-8692"))
        val strategies = src("engine/truth/MemeStrategies8546.kt")
        assertTrue(strategies.contains("ForwardReturnLabeler7731.observe(ts, ExpertSetups8692.cohort8692(s.family.name), false, \"SETUP_SIGNAL_8692\", nowMs)"))
        assertTrue(strategies.contains("LiveEdgeGate7877.cohortProvenPositive7948("))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("MemeStrategies8546.measuredProven8692(ts.mint, nowMs)"))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("|| ExpertSetups8692.setupCohort8692(o.lane)"))
        val lane = src("engine/truth/LaneAuthority8577.kt")
        assertTrue(lane.contains("expertAvoid8692(ts, nowMs)?.let { return it }"))
        assertTrue(lane.contains("if (GateOutcome8418.selectionGateRetired8696(\"CANDIDATE_ADMISSION_8136_\$why\", lane)) return null"))
    }

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
}
