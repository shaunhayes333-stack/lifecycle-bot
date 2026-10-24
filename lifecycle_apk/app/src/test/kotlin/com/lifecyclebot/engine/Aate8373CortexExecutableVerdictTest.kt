package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import org.junit.Assert.assertEquals
import org.junit.Test

/** Forward runner incidence is not executable profit and cannot mask a losing net EV. */
class Aate8373CortexExecutableVerdictTest {
    @Test fun negativeExecutableEdgeStaysRefusedEvenForRunnerHeavyLane() {
        assertEquals(CortexScoreboard7885.Bucket.REFUSE, CortexScoreboard7885.bucketOf(-8.0, 0.95, true))
        assertEquals(CortexScoreboard7885.Bucket.REFUSE, CortexScoreboard7885.bucketOf(-2.1, 0.10, true))
        assertEquals(CortexScoreboard7885.Bucket.REFUSE, CortexScoreboard7885.bucketOf(-8.0, 0.0, false))
    }

    @Test fun positiveAndUncalibratedInputsRetainIndependentMeaning() {
        assertEquals(CortexScoreboard7885.Bucket.STRONG, CortexScoreboard7885.bucketOf(3.1, 0.0, true))
        assertEquals(CortexScoreboard7885.Bucket.NEUTRAL, CortexScoreboard7885.bucketOf(0.0, 0.8, true))
        assertEquals(CortexScoreboard7885.Bucket.NEUTRAL, CortexScoreboard7885.bucketOf(Double.NaN, 0.8, true))
    }
}
