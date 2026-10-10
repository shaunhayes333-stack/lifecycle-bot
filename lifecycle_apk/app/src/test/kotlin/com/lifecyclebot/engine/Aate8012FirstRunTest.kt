package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexVoters7885
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8012FirstRunTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theHiveIsPulledSoonAfterStart() {
        val c = src("collective/CollectiveLearning.kt")
        assertTrue(c.contains("private const val FIRST_SYNC_DELAY_MS_8012 = 30_000L"))
        assertTrue(c.contains("delay(if (first8012) FIRST_SYNC_DELAY_MS_8012 else SYNC_INTERVAL_MS)"))
    }

    @Test fun theSlowGlobalVoterIsGoneAndTheFeeReadingStaysFresh() {
        assertFalse(CortexVoters7885.IDS.contains("DRAWDOWN_AGGRESSION"))
        assertTrue(src("network/ExecWarm8009.kt").contains("PriorityFee8009.pumpPortalSol(0.0)"))
    }
}
