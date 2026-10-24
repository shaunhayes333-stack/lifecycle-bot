package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexVoters7885
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8003LaneBrainVotersTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun laneBrainSeatsGradeAfterRestart() {
        assertNotNull(CortexVoters7885.edgesFor("LB_MOONSHOT_S"))
        assertNotNull(CortexVoters7885.edgesFor("LB_SHITCOIN_C"))
        assertNotNull(CortexVoters7885.edgesFor("LB_QUALITY_V"))
        assertNull(CortexVoters7885.edgesFor("LB_QUALITY_Q"))
        listOf("TREASURY_SCALP", "LANE_TRUST", "EXEC_CONFIDENCE", "CRYPTO_BEHAVIOR_ADJ").forEach {
            assertTrue(it, CortexVoters7885.IDS.contains(it))
        }
    }

    @Test fun everyLaneBrainVotesInEveryLane() {
        val v = src("engine/cortex/CortexVoters7885.kt")
        // superseded by V5.0.8366 — the lane brains still vote via laneBrainVotes8003; a queued assessment uses the stamped votes inside the TTL.
        assertTrue(v.contains("val lanes = LaneBrainVotes8366.resolve8366(ts.mint, laneBrainVotes8003(ts), System.currentTimeMillis())"))
        assertTrue(v.contains("return lanes + v3m"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("voters=\${CortexVoters7885.votersLine8003()}"))
    }
}
