package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8073 — runner tickets are positive admits (route-minimum size, no plan wait); the Helius day survives installs. */
class Aate8073TicketsBuyCreditsPersistTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun ticketIsAPositiveAdmit() {
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        val wf = g.substringAfter("private fun watchFirst7994(").substringBefore("val st = ")
        assertTrue(wf.contains("RunnerTicket8073.isTicket8073(ts.mint, nowMs)"))
        assertTrue(wf.contains("notePositive7975(ts.mint, nowMs)"))
    }

    @Test fun heliusDayRestoresWhenTheStoreIsReady() {
        val h = src("engine/truth/HeliusCreditEconomy7881.kt")
        assertTrue(h.contains("if (!loaded && (try { LearningPersistence.ready() } catch (_: Throwable) { false })) synchronized(this) {"))
        assertTrue(h.contains("        if (!loaded) return\n        lastPersistMs = nowMs"))
        assertTrue(h.contains("total += o.optDouble(\"total\", 0.0)"))
    }
}
