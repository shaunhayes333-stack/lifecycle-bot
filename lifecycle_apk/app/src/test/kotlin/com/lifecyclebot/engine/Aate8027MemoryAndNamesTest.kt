package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8027 — 8026 died of OOM at 22.7 min with 0 token rows pruned; the unmanaged coin had no name. */
class Aate8027MemoryAndNamesTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun tokenRowsPruneOnIntakeAgeAtEveryTrim() {
        assertTrue(MemoryGuard7977.prunable8025(watched = false, open = false, ticketed = false, staleMs = 11 * 60_000L))
        assertFalse(MemoryGuard7977.prunable8025(watched = false, open = false, ticketed = false, staleMs = 9 * 60_000L))
        val g = src("engine/MemoryGuard7977.kt")
        assertTrue(g.contains("val last = ts.addedToWatchlistAt"))
        assertTrue(g.contains("        try { pruneTokens8025() } catch (_: Throwable) {}"))
        assertFalse(g.contains("if (hard) try { pruneTokens8025() }"))
    }

    @Test fun heldAndUnreconciledCoinsAreNamed() {
        assertTrue(com.lifecyclebot.engine.truth.MissingMarkExitVeto6835.statusLine().contains("held=["))
        assertTrue(com.lifecyclebot.engine.truth.OnChainCost8024.statusLine().contains("unreconciledNow=["))
    }
}
