package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8411 — an evidence hold must create the shadow evidence that can eventually retire that hold.
 *
 * superseded by V5.0.8418 — restored after 76565f9ca (8417) deleted this file without a note. The 8411 bridge
 * (LaneShadowProof7307.onUnprovenRefusal on EVIDENCE_HOLD_8336_, `sizeSol = 0.042`, LANE_SHADOW_PROOF_OPENED_FROM_EVIDENCE_HOLD_8411)
 * was removed by 8417 as redundant: the hold already opens the EvidenceShadow8344 position, which is the book
 * EvidenceHold8336 reads. The invariant is kept on the replacement path: a LIVE refusal whose final reason IS the hold
 * (no core refusal) opens the shadow, after the verdict is fixed, and the bridge never turns the hold into an admission.
 */
class Aate8411EvidenceHoldShadowBridgeTest {
    @Test fun live_evidence_hold_opens_lane_shadow_without_changing_the_live_verdict() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        // superseded by V5.0.8418 — was assertTrue(src.contains("LaneShadowProof7307.onUnprovenRefusal(")) and the 8411 labels.
        assertFalse(src.contains("LANE_SHADOW_PROOF_OPENED_FROM_EVIDENCE_HOLD_8411"))
        assertTrue(src.contains("val final = core ?: hold ?: niche ?: wait"))
        assertTrue(src.contains("if (!paper && core == null && hold != null && final == hold)"))
        assertTrue(src.contains("EvidenceShadow8344.open8344(ts, l, nowMs, advisoryEntry8393 = shadowOnly8393)"))
        assertTrue(src.contains("return final"))
        // The bridge is evidence-only: it opens after the verdict is recorded and before the hold is returned unchanged.
        val verdict = src.indexOf("val verdict = when { final == null -> Verdict8083.ADMIT")
        val open = src.indexOf("EvidenceShadow8344.open8344(ts, l, nowMs, advisoryEntry8393 = shadowOnly8393)")
        assertTrue(verdict in 0 until open)
        assertTrue(open < src.indexOf("return final", open))
    }
}
