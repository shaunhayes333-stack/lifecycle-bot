package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885.Bucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8025NeverFullyLearntTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun authorityGrowsWithLifetimeSampleAndNeverReachesOne() {
        assertEquals(0.0, CortexScoreboard7885.maturity8025(0.0), 0.0)
        assertEquals(62.0 / 1_062.0, CortexScoreboard7885.maturity8025(62.0), 1e-12)          // MOONSHOT in 8023: ~0.06
        assertEquals(0.5, CortexScoreboard7885.maturity8025(1_000.0), 1e-12)
        assertEquals(0.75, CortexScoreboard7885.maturity8025(3_000.0), 1e-12)
        assertTrue(CortexScoreboard7885.maturity8025(1e9) < 1.0)
        assertTrue(CortexScoreboard7885.matureAuthority8025(1.0, 1e9) <= CortexScoreboard7885.MAX_AUTHORITY_8025)
        assertTrue(CortexScoreboard7885.matureAuthority8025(1.0, 62.0) < 0.5)                  // no overrule at 62 grades
        assertEquals(1_000.0, CortexScoreboard7885.MATURITY_HALF_N_8025, 0.0)
    }

    @Test fun theLifetimeCountSurvivesDecayAndASaveRoundTrip() {
        val board = CortexScoreboard7885()
        repeat(2_000) { i -> board.record("MOONSHOT", Bucket.STRONG, false, 10.0 + if (i % 2 == 0) 2.0 else -2.0, 0.0) }
        assertEquals(2_000.0, board.strongLifetime8025("MOONSHOT"), 0.0)
        assertTrue(board.books["MOONSHOT"]!!.byBucket[Bucket.STRONG.ordinal].n < 2_000.0)        // the book itself decays
        val restored = CortexScoreboard7885().also { it.decode(board.encode()) }
        assertEquals(2_000.0, restored.strongLifetime8025("MOONSHOT"), 0.0)
        assertFalse(restored.overruleAuthority("MOONSHOT"))                                        // never full authority
        assertTrue(restored.fractionFor7955("MOONSHOT") < CortexScoreboard7885.MAX_AUTHORITY_8025 + 1e-12)
    }

    @Test fun runnersBrokenBasisAndMemoryAreHandled() {
        assertTrue(RunnerPlay8018.confirmsRun8018(150.0, 3 * 60 * 60_000L, firming = true, held = false))  // 3 h after the decision
        assertTrue(src("engine/BotService.kt").contains("if (!runTicket8025 && !com.lifecyclebot.engine.ExecutableOpenGate.probeShouldEmit6747(\"DUST_PROBE\"))"))
        assertFalse(BasisBreak8019.released8025(60_000L))
        assertTrue(BasisBreak8019.released8025(BasisBreak8019.RELEASE_MS_8025))
        assertTrue(MemoryGuard7977.prunable8025(watched = false, open = false, ticketed = false, staleMs = 31 * 60_000L))
        assertFalse(MemoryGuard7977.prunable8025(watched = true, open = false, ticketed = false, staleMs = 31 * 60_000L))
        assertFalse(MemoryGuard7977.prunable8025(watched = false, open = true, ticketed = false, staleMs = 31 * 60_000L))
        assertFalse(MemoryGuard7977.prunable8025(watched = false, open = false, ticketed = true, staleMs = 31 * 60_000L))
        assertFalse(MemoryGuard7977.prunable8025(watched = false, open = false, ticketed = false, staleMs = 5 * 60_000L))
        assertEquals(300, MemoryGuard7977.KEEP_TOKENS_8025)
    }
}
