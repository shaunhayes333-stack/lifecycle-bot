package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexWalkForward8144
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8238 — live money needs proven evidence: no generic-prior live admits, no in-sample Cortex means. */
class Aate8238ProvenEvidenceOnlyTest {
    @Test fun theMedianIsTheTypicalCoinNotTheOutlier() {
        // CORE STRONG on the 8237 screen: a mean of +30% carried by two runners while the typical coin lost 18%
        val series = listOf(-18.0, -22.0, -15.0, -19.0, -17.0, 210.0, 190.0)
        assertTrue(CortexWalkForward8144.stat8144(series).mean > 30.0)
        assertEquals(-17.0, CortexWalkForward8144.median8238(series), 1e-9)
        assertEquals(2.5, CortexWalkForward8144.median8238(listOf(1.0, 4.0, Double.NaN)), 1e-9)
        assertTrue(CortexWalkForward8144.median8238(emptyList()).isNaN())
    }

    @Test fun aGenericPriorRefusedLiveMoneyGoesToTheShadowBook() {
        val reason = "CANDIDATE_ADMISSION_8136_LIVE_GENERIC_PRIOR_UNSUPPORTED_8238"
        val shadow = FdgOutcomeContinuity8197.shadowOpenReason8233(true, false, reason)
        assertTrue(shadow?.startsWith("generic_prior_shadow:") == true)
        assertTrue(reason.startsWith(shadow!!.substringAfter(':')))
        assertNull(FdgOutcomeContinuity8197.shadowOpenReason8233(true, false, "CANDIDATE_ADMISSION_8136_EXECUTION_ECONOMICS_8136"))
        assertNull(FdgOutcomeContinuity8197.shadowOpenReason8233(false, false, "CANDIDATE_ADMISSION_8136_LIVE_GENERIC_PRIOR_UNSUPPORTED_8238"))
    }

    @Test fun theLiveAdapterAndTheCortexDoorCarryTheContract() {
        val adm = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(adm.contains("if (c.live && !cellSupported) {"))
        assertTrue(adm.contains("live = try { !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() }"))
        val cortex = java.io.File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        assertTrue(cortex.contains("!wf.validated -> nullWhy8201(\"NOT_WALK_FORWARD_VALIDATED_${'$'}{wf.verdict.name}\")"))
        assertTrue(cortex.contains("!(median > 0.0) -> nullWhy8201(\"MEDIAN_NOT_POSITIVE\")"))
    }
}
