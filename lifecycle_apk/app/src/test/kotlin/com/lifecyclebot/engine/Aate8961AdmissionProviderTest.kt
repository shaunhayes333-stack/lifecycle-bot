package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.*
import org.junit.Test

class Aate8961AdmissionProviderTest {
    private val weak = CandidateAdmission8136.Evidence8136("LAB", "weak", 40, 1.0, 2.0)
    private val pocket = CandidateAdmission8136.Evidence8136("LANE_POCKET", "own", 50, 15.0, 4.0)

    @Test fun stopsReadingOnceSupportedEvidenceIsFound() {
        val read = mutableListOf<String>()
        val providers = sequence {
            read += "earlier"
            yield(weak)
            read += "pocket"
            yield(pocket)
            read += "later"
            yield(pocket.copy(kind = "LATER"))
        }.constrainOnce()
        assertEquals(pocket, CandidateAdmission8136.selectMarketEvidence8961(providers, 0.0, 3.0))
        assertEquals(listOf("earlier", "pocket"), read)
    }

    @Test fun unsupportedFallbackDoesNotReadAProviderTwice() {
        var reads = 0
        val providers = sequence {
            reads++
            yield(null)
            reads++
            yield(weak)
            reads++
            yield(pocket.copy(suspect8290 = true))
        }.constrainOnce()
        assertEquals(weak, CandidateAdmission8136.selectMarketEvidence8961(providers, 0.0, 3.0))
        assertEquals(3, reads)
        assertFalse(CandidateAdmission8136.supported8136(weak, 3.0))
    }

    @Test fun fallbackIsRawAndShortfallOnlyChangesSupport() {
        val earlier = weak.copy(meanPct = 5.0)
        assertEquals(pocket, CandidateAdmission8136.selectMarketEvidence8961(sequenceOf(earlier, pocket), 4.0, 3.0))
        assertEquals(earlier, CandidateAdmission8136.selectMarketEvidence8961(sequenceOf(earlier), 10.0, 3.0))
        assertNull(CandidateAdmission8136.selectMarketEvidence8961(sequenceOf(null), 0.0, 3.0))
    }
}
