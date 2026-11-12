package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ManagedBands8719
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8719 — owner: "are we starting to narrow into the 4x and 10x runners" / "the liq floor and market cap floor should
 * be fluid". 8712: 60 coins went 4x, live held 5 and sold all five early on the half-gain trail; 13 were refused on floors
 * that could not learn.
 */
class Aate8719RunnerRoomAndFluidFloorsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aWinnerKeepsTheRoomItsStopGaveIt() {
        val d = RunnerDoctrine8584
        d.enabled8584 = true
        // 8712: AJFnmp peaked about +31 % on a 25 % stop and was sold at +15.7 %; the line is now +5 %, not +15 %
        assertEquals(1.05, d.lineWithStop8719(1.28, 25.0), 1e-9)
        assertEquals(1.125, d.lineWithStop8719(1.5, 25.0), 1e-9)
        assertEquals(1.35, d.lineWithStop8719(1.8, 25.0), 1e-9)
        assertEquals("the two rules meet at 2x", 1.5, d.lineWithStop8719(2.0, 25.0), 1e-9)
        assertEquals("from 2x the 45 % give-back is unchanged", 2.75, d.lineWithStop8719(5.0, 25.0), 1e-9)
        assertEquals("a tight stop keeps the tighter half-gain line", 1.25, d.lineWithStop8719(1.5, 15.0), 1e-9)
        assertEquals("no stop read: the old line", d.line8584(1.3), d.lineWithStop8719(1.3, Double.NaN), 0.0)
        assertFalse("+15 % after a +30 % peak is held on a 25 % stop", d.trailBreached8630("SHITCOIN", 15.0, 30.0, 25.0))
        assertTrue("and sold once it is back to +5 %", d.trailBreached8630("SHITCOIN", 5.0, 30.0, 25.0))
        assertTrue("another profit exit is held above the same line", d.holds8584("MOONSHOT", "TRAILING_STOP", 15.0, 30.0, 25.0))
        assertEquals("the paper book runs the same line", 5.0, d.paperStopLevel8584(25.0, 28.0), 1e-9)
    }

    @Test fun aFloorOpensOnWhatTheLaneWouldHaveBanked() {
        assertEquals("the loss stops at the stop plus one round trip", -23.0, ManagedBands8719.managed8719(-60.0, 20.0, 3.0), 0.0)
        assertEquals(-10.0, ManagedBands8719.managed8719(-10.0, 20.0, 3.0), 0.0)
        assertEquals("the runner is kept to the ledger's cap", 400.0, ManagedBands8719.managed8719(2_500.0, 20.0, 3.0), 0.0)
        assertTrue(ManagedBands8719.proven8719(30.0, 90.0, 3_000.0))
        assertFalse("29 labels prove nothing", ManagedBands8719.proven8719(29.0, 90.0, 3_000.0))
        assertFalse(ManagedBands8719.proven8719(200.0, -40.0, 9_000.0))

        ManagedBands8719.resetForTest8719()
        // forty coins of $15k cap / $1.5k pool (the record decays, so forty weigh about 36): most stop out, a few run —
        // positive once the loss is the stop
        repeat(40) { i -> ManagedBands8719.addForTest8719("MOONSHOT", 1_500.0, 15_000.0, if (i % 5 == 0) 300.0 else -60.0, 3.0, 20.0) }
        assertTrue(ManagedBands8719.liquidityOpen8719("MOONSHOT", 1_200.0))
        assertFalse("another band stays shut", ManagedBands8719.liquidityOpen8719("MOONSHOT", 300.0))
        assertFalse("another lane's record is its own", ManagedBands8719.liquidityOpen8719("EXPRESS", 1_200.0))
        // the cap floor steps down through the proven bucket and stops at the unproven one
        assertEquals(10_000.0, ManagedBands8719.capFloor8719("MOONSHOT", 31_622.7766), 1.0)
        assertEquals(10_000.0, ManagedBands8719.capFloor8719("MOONSHOT", 10_000.0), 0.0)
        assertEquals(31_622.7766, ManagedBands8719.capFloor8719("EXPRESS", 31_622.7766), 0.0)
        ManagedBands8719.resetForTest8719()
    }

    @Test fun wiring() {
        assertTrue(src("engine/truth/EdgeModel8537.kt").contains("ManagedBands8719.learn8719(s.raw.lane, s.raw.liq, s.raw.mcap, netPct, s.raw.costPct)"))
        assertTrue(src("engine/LaneAgeLiquidityTruth8140.kt").contains("ManagedBands8719.liquidityOpen8719(lane, liquidityUsd)"))
        assertTrue(src("engine/market/LaneHunter7297.kt").contains("ManagedBands8719.capFloor8719(lane, byCloses, MAX_EDGE_STEPS)"))
        assertTrue(src("v3/scoring/ShitCoinExpress.kt").contains("LaneHunter7297.floorFor(\"EXPRESS\", MIN_MARKET_CAP_USD)"))
        val d = src("engine/RunnerDoctrine8584.kt")
        assertTrue(d.contains("return 1.0 + pnlPct / 100.0 <= lineWithStop8719(1.0 + peakPct / 100.0, stopPct)"))
        assertTrue(d.contains("return now > lineWithStop8719(peak, stopPct)"))
    }
}
