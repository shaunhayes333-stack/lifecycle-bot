package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.StructureTracker7962
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7970CellBeatsLaneTest {

    private fun read(broke: Boolean, share: Double) =
        StructureTracker7962.Read7962(30, 1, 1.0, 1.2, false, false, false, false, share, 1.1, broke)

    @Test fun cellMustClearFifteenPercentAfterOneSe() {
        assertTrue(RunnerGrab7967.cellClearsBar7970(50, 157.4, 40.0))   // the live MOONSHOT pump.fun <15m cell
        assertTrue(RunnerGrab7967.cellClearsBar7970(20, 30.0, 15.0))
        assertFalse(RunnerGrab7967.cellClearsBar7970(20, 29.0, 15.0))   // 14% after SE
        assertFalse(RunnerGrab7967.cellClearsBar7970(19, 200.0, 10.0))  // too few labels
        assertFalse(RunnerGrab7967.cellClearsBar7970(50, Double.NaN, 1.0))
    }

    @Test fun aYoungChartDoesNotRefuseButABreakdownDoes() {
        assertTrue(RunnerGrab7967.notBreaking7970(null))                  // too young for swings
        assertTrue(RunnerGrab7967.notBreaking7970(read(false, Double.NaN)))
        assertTrue(RunnerGrab7967.notBreaking7970(read(false, 0.5)))
        assertFalse(RunnerGrab7967.notBreaking7970(read(true, 0.8)))      // 1m structure broke
        assertFalse(RunnerGrab7967.notBreaking7970(read(false, 0.3)))     // sellers own the tape
    }
}
