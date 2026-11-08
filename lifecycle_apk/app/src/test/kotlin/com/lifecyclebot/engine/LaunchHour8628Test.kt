package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchHour8628.ObservedPath
import com.lifecyclebot.engine.truth.LaunchHour8628
import org.junit.Assert.*
import org.junit.Test

class LaunchHour8628Test {
    @Test fun firstHourPricesRejectRawUnitConfusionButPreserveRealRunnerPrints() {
        assertEquals(2.0e-7, LaunchHour8628.observedPrice8642(0.4, 2_000_000.0, 2.05e-7)!!, 1e-15)
        assertEquals("a later 10x runner is fine when its current curve price confirms it", 2e-6,
            LaunchHour8628.observedPrice8642(0.4, 200_000.0, 2.05e-6)!!, 1e-14)
        assertNull("raw token quantities cannot invent a million-fold peak",
            LaunchHour8628.observedPrice8642(0.4, 2.0, 2.05e-7))
        assertNull(LaunchHour8628.observedPrice8642(0.4, 2_000_000.0, Double.NaN))
        assertNull(LaunchHour8628.observedPrice8642(Double.POSITIVE_INFINITY, 2_000_000.0, 2e-7))
    }
    @Test fun peakBeforeTheSetupNeverCountsAsItsRunner() {
        val path = ObservedPath(listOf(0L to 1.0, 5_000L to 11.0, 6_000L to 2.0, 3_599_000L to 3.0), 0)
        val launch = path.grade(0, true, 3_600_000, 2.0)
        assertEquals(11.0, launch.peak!!.second, 0.0)
        assertEquals(198.0, launch.net!!, 0.0)
        val setup = path.grade(5_000, false, 3_600_000, 2.0)
        assertEquals(2.0, setup.entry!!.second, 0.0)
        assertEquals(3.0, setup.peak!!.second, 0.0)
        assertEquals(48.0, setup.net!!, 0.0)
        assertEquals(2, path.coveredMinutes)
    }

    @Test fun missingEntryOrCloseOrUnfinishedHourStaysUnpriced() {
        val missingEntry = ObservedPath(listOf(65_000L to 1.0, 3_599_000L to 2.0), 0)
        assertNull(missingEntry.grade(0, true, 3_600_000, 2.0).net)
        assertNull(missingEntry.grade(64_000, false, 3_599_999, 2.0).net)
        assertNull(ObservedPath(listOf(0L to 1.0, 3_530_000L to 3.0), 0).grade(0, true, 3_600_000, 2.0).net)
    }
}
