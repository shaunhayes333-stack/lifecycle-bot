package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CapitalDrawdown7948
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7990RecordTruthTest {
    @Test fun oneTickSpikeCannotSetThePeak() {
        assertEquals(0.28, CapitalDrawdown7948.peakCandidate7990(0.63, 0.28), 1e-12)
        assertEquals(0.30, CapitalDrawdown7948.peakCandidate7990(0.30, 0.31), 1e-12)
        assertEquals(0.5, CapitalDrawdown7948.peakCandidate7990(0.5, Double.NaN), 1e-12)
    }

    @Test fun basisUncertainRowsNeverTeach() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        assertTrue(s.contains("            run {\n                try { LearningQuarantineGate6470.quarantinePositionId(t7807.positionId, t7807.quarantineReason) }"))
    }
}
