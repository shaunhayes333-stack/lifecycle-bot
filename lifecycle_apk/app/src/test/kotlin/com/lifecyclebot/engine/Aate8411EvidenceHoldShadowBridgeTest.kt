package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8411 — an evidence hold must create the shadow evidence that can eventually retire that hold. */
class Aate8411EvidenceHoldShadowBridgeTest {
    @Test fun live_evidence_hold_opens_lane_shadow_without_changing_the_live_verdict() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        assertTrue(src.contains("final?.startsWith(\"EVIDENCE_HOLD_8336_\") == true"))
        assertTrue(src.contains("LaneShadowProof7307.onUnprovenRefusal("))
        assertTrue(src.contains("price = ts.lastPrice, liquidityUsd = ts.lastLiquidityUsd"))
        assertTrue(src.contains("sizeSol = 0.042"))
        assertTrue(src.contains("LANE_SHADOW_PROOF_OPENED_FROM_EVIDENCE_HOLD_8411"))
        assertTrue(src.contains("val final = core ?: hold ?: niche ?: wait"))
        assertTrue(src.contains("return final"))
        // The bridge is evidence-only: it must not convert this hold to an admission.
        assertTrue(src.indexOf("LaneShadowProof7307.onUnprovenRefusal") < src.indexOf("return final"))
    }
}
