package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7552NativeUnknownEvidenceNeutralityTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun holder_unknown_uses_explicit_resolution_state_and_neutral_value() {
        val b=src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(b.contains("val top = SpecialistInputParity8527.topHolderPct8527(ts)"))
        val shared=src("engine/SpecialistInputParity8527.kt")
        assertTrue(shared.contains("safetyObservedAtMs in 1L..nowMs"))
        assertEquals(20.0, SpecialistInputParity8527.topHolderPct8527(null, null, 0.0, 0L, 1_000L), 0.0)
        assertEquals(42.0, SpecialistInputParity8527.topHolderPct8527(null, 42.0, 0.0, 0L, 1_000L), 0.0)
        assertEquals(0.0, SpecialistInputParity8527.topHolderPct8527(null, null, 0.0, 900L, 1_000L), 0.0)
    }

    @Test fun unresolved_rugcheck_is_pending_not_fabricated_confirmed_score() {
        val b=src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(b.contains("val rug=ts.safety.rugcheckScore.takeIf{it>=0}?:1"))
        assertFalse(b.contains("val rug=ts.safety.rugcheckScore.takeIf{it>=0}?:3"))
    }

    @Test fun codebase_declares_pending_holder_and_rug_semantics() {
        val model=src("data/Models.kt")
        val safety=src("engine/TokenSafetyChecker.kt")
        val moon=src("v3/scoring/MoonshotTraderAI.kt")
        assertTrue(model.contains("var holderDataResolved: Boolean = false"))
        assertTrue(safety.contains("val topHolderPct: Double = -1.0"))
        assertTrue(moon.contains("val pendingRc = rugcheckScore == 1"))
    }
}
