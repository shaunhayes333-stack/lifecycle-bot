package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device ANR 8664: 312 threads, many in the same concurrent regime recompute. */
class Aate8665RegimeAnrRegressionTest {
    private fun source() = File("src/main/kotlin/com/lifecyclebot/engine/RegimeDetector.kt").readText()

    @Test fun `scanner and UI regime reads cannot synchronously clean trade history`() {
        val src = source()
        val current = src.substringAfter("fun current(): RegimeSnapshot {")
            .substringBefore("fun currentRegime(): Regime")
        assertTrue("one background refresh worker", src.contains("Executors.newSingleThreadExecutor"))
        assertTrue("single-flight starts before scheduling", current.contains("refreshRunning8665.compareAndSet(false, true)"))
        assertTrue("journal recomputation runs only in the worker", current.contains("refreshExecutor8665.execute {"))
        assertFalse("no regime recomputation before the worker", current.substringBefore("refreshExecutor8665.execute {").contains("recompute("))
        assertFalse("no regime recomputation after scheduling", current.substringAfter("refreshExecutor8665.execute {").substringAfter("// Cold-start").contains("recompute("))
        assertTrue("cold start has mode-neutral fallback", current.contains("RegimeSnapshot(Regime.NORMAL, 0.0, 0.0, -1, 0, now)"))
    }

    @Test fun `async refresh preserves current-mode truth and protects invalidations`() {
        val src = source()
        assertTrue(src.contains("recompute(System.currentTimeMillis(), mode6679)"))
        assertTrue(src.contains("c.mode == mode6679"))
        assertTrue(src.contains("cached.set(ModeSnapshot8665(mode6679, fresh))"))
        assertTrue(src.contains("refreshGeneration8665.get() == generation"))
        assertTrue(src.contains("synchronized(refreshPublishLock8665)"))
        assertTrue(src.contains("refreshRunning8665.set(false)"))
        assertTrue(src.contains("DeskPerformanceAuthority6648.classify(it) == DeskPerformanceAuthority6648.Book.MEME"))
        assertTrue(src.contains(".filter { it.mode.equals(mode6679, ignoreCase = true) }"))
    }
}
