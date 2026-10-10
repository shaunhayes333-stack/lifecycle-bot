package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7991RunnerHoldTest {
    @Test fun losersAreNotHeld() {
        assertTrue(RunnerGrab7967.holdsThrough7991(40.0, 30L * 60_000L))      // green runner: held
        assertTrue(RunnerGrab7967.holdsThrough7991(-8.0, 60_000L))            // early shakeout: room
        assertFalse(RunnerGrab7967.holdsThrough7991(-8.0, 4L * 60_000L))      // still red after 3 min: stops run
        assertFalse(RunnerGrab7967.holdsThrough7991(-20.0, 30_000L))          // below -15%: stops run
        assertFalse(RunnerGrab7967.holdsThrough7991(Double.NaN, 0L))
    }
}
