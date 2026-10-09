package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SpecialistMiner7972
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7974SpecialistSizingTest {

    @Test fun sizedByTheProvenFloorNeverBelowTheRequest() {
        assertEquals(1.15, SpecialistMiner7972.edgeMult7974(15.0), 1e-12)
        assertEquals(1.5, SpecialistMiner7972.edgeMult7974(50.0), 1e-12)
        assertEquals(2.0, SpecialistMiner7972.edgeMult7974(400.0), 1e-12)
        assertEquals(1.0, SpecialistMiner7972.edgeMult7974(-5.0), 1e-12)
        assertEquals(1.0, SpecialistMiner7972.edgeMult7974(Double.NaN), 1e-12)
        // No specialist matched this mint: the request stands.
        assertEquals(1.3, SpecialistMiner7972.sizeMult7974("NO_MATCH_MINT_000000000000000000000", 1.3), 1e-12)
    }

    @Test fun sizingBridgeAppliesIt() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/TraderSizingBridge6444.kt").readText()
        assertTrue(src.contains("SpecialistMiner7972.sizeMult7974(mintForSeal,"))
    }
}
