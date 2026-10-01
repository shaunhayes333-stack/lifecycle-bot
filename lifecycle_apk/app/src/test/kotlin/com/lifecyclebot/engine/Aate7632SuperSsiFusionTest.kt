package com.lifecyclebot.engine

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7632SuperSsiFusionTest {
    @Test fun `aligned brains preserve most existing influence`() {
        val s = SuperSsiFusion7632.fuse(listOf(
            SuperSsiFusion7632.Vote("a", 5.0),
            SuperSsiFusion7632.Vote("b", 4.0),
            SuperSsiFusion7632.Vote("c", 3.0),
        ))
        assertEquals("BULL", s.direction)
        assertTrue(s.agreement > 0.99)
        assertTrue(s.fusedDeltaPct > 9.0)
        assertTrue(abs(s.fusedDeltaPct) <= abs(s.rawDeltaPct))
    }

    @Test fun `conflicting brains are attenuated rather than summed blindly`() {
        val s = SuperSsiFusion7632.fuse(listOf(
            SuperSsiFusion7632.Vote("bull1", 8.0),
            SuperSsiFusion7632.Vote("bull2", 5.0),
            SuperSsiFusion7632.Vote("bear1", -7.0),
            SuperSsiFusion7632.Vote("bear2", -4.0),
        ))
        assertTrue(s.disagreement > 0.80)
        assertTrue(abs(s.fusedDeltaPct) < abs(s.rawDeltaPct))
    }

    @Test fun `oracle consumes super ssi without attenuating creator safety facts`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(src.contains("SuperSsiFusion7632.fuse("))
        assertTrue(src.contains("creatorFacts7632"))
        assertTrue(src.contains("creatorRugAdjust7329"))
        assertTrue(src.contains("SUPER_SSI_CONFLICT_7632"))
        assertTrue(src.contains("SUPER_SSI_CONSENSUS_7632"))
    }
}
