package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8051 — the "unknown" gaps: refusal taxonomy, forecast signature, unstaged coins. */
class Aate8051FillUnknownsTest {
    @Test fun refusalsAreFiled() {
        listOf("WATCH_FIRST_8019_BAND_NEGATIVE", "NET_EDGE_COIN_TOO_SLOW_8035", "REPEAT_LOSER_8022", "CURVE_NOBODY_BOUGHT_8046",
            "AT_LAUNCH_CAP_8047", "PRICE_COLLAPSED_ONCHAIN_8038", "NATIVE_NO_CONTINUATION").forEach {
            assertEquals(it, RejectTaxonomy.Category.ADVISORY, RejectTaxonomy.classify(it).category)
            assertTrue(RejectTaxonomy.classify(it).trainable)
        }
        assertEquals(RejectTaxonomy.Category.PENALTY, RejectTaxonomy.classify("TOKEN_MAP_INCOMPLETE").category)
        assertEquals(RejectTaxonomy.Category.HARD_SAFETY, RejectTaxonomy.classify("NARRATIVE_SCAM: x").category)
        assertEquals(RejectTaxonomy.Category.HARD_SAFETY, RejectTaxonomy.classify("HARD_BLOCK_DISTRIBUTION").category)
    }

    @Test fun nearestStage() {
        assertEquals("N_MARKUP", TokenMetricStageRouter.nearestStage8051(0.95, 5.0, 40.0, 55.0, 45.0))
        assertEquals("N_FLAT_HIGH", TokenMetricStageRouter.nearestStage8051(0.95, 5.0, 10.0, 50.0, 50.0))
        assertEquals("N_FALLING", TokenMetricStageRouter.nearestStage8051(0.70, 30.0, 10.0, 40.0, 60.0))
        assertEquals("N_PULLBACK", TokenMetricStageRouter.nearestStage8051(0.70, 30.0, 10.0, 56.0, 44.0))
    }

    @Test fun signatureFilled() {
        val f = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(!f.contains("admitted, score, \"U\", regime, \"UNKNOWN\""))
        assertTrue(f.contains("fun signature8051(ts: TokenState, lane: String)"))
        val v = File("src/main/kotlin/com/lifecyclebot/engine/cortex/CortexVoters7885.kt").readText()
        assertTrue(!v.contains("\"U\", regime(), \"UNKNOWN\""))
    }
}
