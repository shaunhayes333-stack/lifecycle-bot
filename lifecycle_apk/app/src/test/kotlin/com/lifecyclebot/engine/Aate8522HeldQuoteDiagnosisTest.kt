package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

class Aate8522HeldQuoteDiagnosisTest {
    private val detail = "heldQuote8189[asked=16 ok=0 noRoute=16 served=0 cached=0 inFlight=0 missBy8435{JUP_EXCEPTION_NO_FRESH_RESERVE=16}]"

    @Test fun closedLiveBookRetainsCumulativeMissEvidenceWithoutCreatingACurrentConflict() {
        val row = DiagnosisBrief8438.heldQuoteEvidence8522(16L, 0L, 0, detail)
        assertEquals(detail, row.detail)
        assertTrue(row.summary.contains("cumulative asked=16 ok=0 liveHeld=0"))
        assertFalse(row.summary.contains("CONFLICT"))
        assertFalse(row.summary.contains("REVIEW"))
        assertNull("closed holdings cannot create a current fix-queue item", row.fix)
    }

    @Test fun anOpenLiveBookWithUnansweredQuotesStillAsksForReviewButNeverInventsRouteReadiness() {
        val row = DiagnosisBrief8438.heldQuoteEvidence8522(16L, 0L, 1, detail)
        assertEquals(detail, row.detail)
        assertTrue(row.summary.contains("REVIEW"))
        assertNotNull(row.fix)
        assertFalse(row.summary.contains("route READY"))
        assertFalse(row.fix!!.text.contains("provider outage"))
        assertNull(DiagnosisBrief8438.heldQuoteEvidence8522(16L, 1L, 1, detail).fix)
        assertNull(DiagnosisBrief8438.heldQuoteEvidence8522(9L, 0L, 1, detail).fix)
    }
}
