package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** V5.0.9042 — the dataset seed fills the evidence the decision reads; it no longer waits up to 30 min after a start. */
class Aate9042SeedFirstTest {
    @Test fun theSeedDoesNotWaitForHeadroom() {
        assertTrue("datasetSeed" in JobCost8813.DECISION_INPUTS_9042)
        assertFalse(JobCost8813.waitsForHeadroom9042("datasetSeed", true))
        assertTrue(JobCost8813.waitsForHeadroom9042("strategyLab", true))
        assertFalse(JobCost8813.waitsForHeadroom9042("strategyLab", false))
        JobCost8813.pressureForTest9033 = "CRITICAL"
        try {
            val ran = CountDownLatch(1)
            val t = Thread { JobCost8813.measureBackground8817("datasetSeed") { ran.countDown() } }
            t.start()
            assertTrue("the seed runs at once even with the heap at its limit", ran.await(2, TimeUnit.SECONDS))
            t.join(5_000L)
        } finally { JobCost8813.pressureForTest9033 = null; JobCost8813.resetForTest8813() }
        assertEquals(JobCost8813.YIELDING_TURN_WAIT_MS_9033, JobCost8813.turnWaitMs9033("datasetSeed", JobCost8813.waitsForHeadroom9042("datasetSeed", true)))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §227 — V5.0.9042: the seed first"))
        assertTrue(File("../../.github/workflows/build.yml").readText().contains("--tests '*Aate9042*'"))
    }
}
