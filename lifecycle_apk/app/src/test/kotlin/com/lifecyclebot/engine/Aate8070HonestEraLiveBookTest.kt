package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveTruth8064
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8070 — the live book only counts closes made by honest-era builds. */
class Aate8070HonestEraLiveBookTest {
    @Test fun eraByBuild() {
        assertTrue(LiveTruth8064.honestEra8070("5.0.8064"))
        assertTrue(LiveTruth8064.honestEra8070("5.0.8070"))
        assertFalse(LiveTruth8064.honestEra8070("5.0.8063"))
        assertFalse(LiveTruth8064.honestEra8070("pre_7103"))
        assertFalse(LiveTruth8064.honestEra8070(null))
        assertFalse(LiveTruth8064.honestEra8070(""))
    }
}
