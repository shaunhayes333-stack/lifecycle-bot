package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FieldManual7715
import com.lifecyclebot.engine.truth.LiquidityTruth8037
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8037 — VOCAT: V3 saw $8,471 of depth, every other brain read the tick's $0 and priced a 24% round trip. */
class Aate8037LiquidityTruthTest {
    @Test fun aMissingTickTakesTheObservedDepth() {
        assertEquals(8_471.0, LiquidityTruth8037.fill8037(0.0, 8_471.0)!!, 1e-9)
        // A real tick value is never lowered or overridden.
        assertNull(LiquidityTruth8037.fill8037(5_000.0, 8_471.0))
        // Nothing observed: nothing invented.
        assertNull(LiquidityTruth8037.fill8037(0.0, 0.0))
        assertNull(LiquidityTruth8037.fill8037(0.0, Double.NaN))
    }

    @Test fun unknownDepthIsNotTwentyPercentImpactForNetEdge() {
        // At $8.5K of depth a $3 ticket's impact is tiny; with depth unseen the model charged the 20% maximum.
        val seen = FieldManual7715.roundTripCostPct7766(0.0273, 3.0, 8_471.0)
        val unseen = FieldManual7715.roundTripCostPct7766(0.0273, 3.0, 0.0)
        assertTrue("seen=$seen", seen < 6.0)
        assertTrue("unseen=$unseen", unseen > 20.0)
        val netEdge = File("src/main/kotlin/com/lifecyclebot/engine/truth/NetEdge8030.kt").readText()
        assertTrue(netEdge.contains("liquidity8037(ts)") && netEdge.contains("depthKnown"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/FieldManual7715.kt").readText().contains("LiquidityTruth8037.hydrate8037(ts)"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText().contains("LiquidityTruth8037.hydrate8037(ts)"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/collective/TursoClient.kt").readText().contains("PROBE_HEADER_6976"))
    }
}
