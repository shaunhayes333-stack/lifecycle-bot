package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FieldManual7715
import org.junit.Assert.assertEquals
import org.junit.Test

/** V5.0.7935 — the fluid lock slides for planned runners; a runner winner never closes red. */
class Aate7935RunnerLockTest {
    @Test fun runnerUnderItsArmPeakIsFlooredAtBreakEvenOnceItClearedTheMargin() {
        // FloopyTrador: peak +21, cost 3% → break-even 3.5, margin cleared → floor 3.5.
        assertEquals(3.5, FieldManual7715.runnerAwareStop7935(Double.NaN, 21.0, 3.0, runnerDeferred = true) { -15.0 }, 1e-9)
        // Peak +8 has not cleared break-even + 6 → room to run (pre-trail stop).
        assertEquals(-15.0, FieldManual7715.runnerAwareStop7935(5.0, 8.0, 3.0, runnerDeferred = true) { -15.0 }, 1e-9)
    }

    @Test fun nonDeferredStopsSlideAsBefore() {
        assertEquals(12.0, FieldManual7715.runnerAwareStop7935(12.0, 30.0, 3.0, runnerDeferred = false) { -15.0 }, 1e-9)
        assertEquals(3.5, FieldManual7715.runnerAwareStop7935(2.0, 6.0, 3.0, runnerDeferred = false) { -15.0 }, 1e-9)
    }
}
