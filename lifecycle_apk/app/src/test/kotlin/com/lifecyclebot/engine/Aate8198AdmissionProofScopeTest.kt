package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionEdge8180
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Decision8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 8198 P0-A — one admission proof per (mode, mint, lane, candidate generation); another lane never clears or borrows it. */
class Aate8198AdmissionProofScopeTest {
    private val a = AdmissionEdge8180
    private val t0 = 1_700_000_000_000L
    private val admit = Decision8136(Terminal8136.ADMIT, "SPECIFIC_EDGE_PROVEN_8136", true, "LAUNCH_LADDER", 12.0, emptyList(), "SETUP:LAUNCH_LADDER n60 +22.0±4.0")
    private val refuse = Decision8136(Terminal8136.REFUSE, "STAGE_PROVEN_LOSING_8136", true, null, Double.NaN, emptyList())
    private val defer = Decision8136(Terminal8136.DEFER_DATA, "DATA_INCOMPLETE_DEFER_8136", false, null, Double.NaN, emptyList())

    @Before fun setUp() = a.resetForTests8180()
    @After fun tearDown() = a.resetForTests8180()

    @Test fun anotherLanesRefusalDoesNotClearTheAdmittingLanesProof() {
        a.note8180("LIVE", "mLEGO", "SHITCOIN", 3L, admit, t0)
        a.note8180("LIVE", "mLEGO", "MOONSHOT", 3L, refuse, t0 + 10)
        a.note8180("LIVE", "mLEGO", "QUALITY", 3L, defer, t0 + 20)
        val p = a.proof8198("LIVE", "mLEGO", "SHITCOIN", 3L, t0 + 30)
        assertNotNull(p)
        assertEquals("SHITCOIN", p!!.lane)
        assertEquals("LAUNCH_LADDER", p.chosenSetup)
        assertEquals("SETUP:LAUNCH_LADDER n60 +22.0±4.0", p.basis)      // the exact evidence travels, not a boolean
        assertFalse(a.admitted8180("LIVE", "mLEGO", "MOONSHOT", 3L, t0 + 30))   // the refusing lane borrows nothing
        assertFalse(a.admitted8180("LIVE", "mLEGO", "QUALITY", 3L, t0 + 30))
    }

    @Test fun reverseOrderRefusalFirstThenAdmitHoldsTheAdmit() {
        a.note8180("LIVE", "mX", "MOONSHOT", 1L, refuse, t0)
        a.note8180("LIVE", "mX", "SHITCOIN", 1L, admit, t0 + 5)
        assertTrue(a.admitted8180("LIVE", "mX", "SHITCOIN", 1L, t0 + 6))
        assertFalse(a.admitted8180("LIVE", "mX", "MOONSHOT", 1L, t0 + 6))
    }

    @Test fun aStaleGenerationNeverAuthorisesTheNextCandidate() {
        a.note8180("LIVE", "mR2", "CORE", 4L, admit, t0)
        assertTrue(a.admitted8180("LIVE", "mR2", "CORE", 4L, t0 + 1))
        assertFalse(a.admitted8180("LIVE", "mR2", "CORE", 5L, t0 + 1))     // new generation: re-prove
        assertFalse(a.admitted8180("LIVE", "mR2", "CORE", 3L, t0 + 1))
    }

    @Test fun modesAreIsolated() {
        a.note8180("PAPER", "mP", "SHITCOIN", 2L, admit, t0)
        assertTrue(a.admitted8180("PAPER", "mP", "SHITCOIN", 2L, t0 + 1))
        assertFalse(a.admitted8180("LIVE", "mP", "SHITCOIN", 2L, t0 + 1))
    }

    @Test fun onlyTheSameLanesLaterTerminalReplacesOrClearsIt() {
        a.note8180("LIVE", "mS", "SHITCOIN", 9L, admit, t0)
        a.note8180("LIVE", "mS", "shitcoin", 9L, refuse, t0 + 1)          // same canonical lane, any casing
        assertFalse(a.admitted8180("LIVE", "mS", "SHITCOIN", 9L, t0 + 2))
        a.note8180("LIVE", "mS", "SHITCOIN", 9L, admit, t0 + 3)
        assertTrue(a.admitted8180("LIVE", "mS", "SHITCOIN", 9L, t0 + a.TTL_MS_8180 + 3))
        assertFalse(a.admitted8180("LIVE", "mS", "SHITCOIN", 9L, t0 + a.TTL_MS_8180 + 4))
    }

    @Test fun theLiveAdmissionAdapterStampsItsOwnLaneAndGeneration() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(src.contains("AdmissionEdge8180.note8180(mode, mint, laneRaw, candidateVersion, d, nowMs)"))
        assertFalse(src.contains("note8180(mint, AdmissionEdge8180.carriesEdge8180(d), nowMs)"))
    }

    // V5.0.8235 — a generic ADMIT carries its economic basis to the sizer, but not promotion rights.
    @Test fun aGenericAdmitCarriesItsBasisWithoutPromotionRights() {
        val generic = Decision8136(Terminal8136.ADMIT, "GENERIC_PRIOR_CLEARS_8136", false, "IGNITION_5M", 4.0, emptyList(), "PRIOR",
            costPct = 6.0, costSizeSol = 0.0285)
        a.note8180("LIVE", "mG", "SHITCOIN", 2L, generic, t0)
        val p = a.proof8198("LIVE", "mG", "SHITCOIN", 2L, t0 + 1)
        assertNotNull(p)
        assertFalse(p!!.specificEdge)
        assertEquals(10.0, p.expectedGrossPct, 1e-9)                      // net 4 + round trip 6: the admission's own gross
        assertFalse(a.admitted8180("LIVE", "mG", "SHITCOIN", 2L, t0 + 1))  // no route-minimum promotion on a prior
        a.note8180("LIVE", "mG", "SHITCOIN", 2L, refuse, t0 + 2)
        assertNull(a.proof8198("LIVE", "mG", "SHITCOIN", 2L, t0 + 3))      // the same lane's later terminal clears it
    }

    @Test fun anUnfetchedRugcheckIsPendingNotARefusal() {
        val moon = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        assertTrue(moon.contains("val pendingRc = rugcheckScore == 1 || rugcheckScore < 0"))
        val manip = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ManipulatedTraderAI.kt").readText()
        assertTrue(manip.contains("if (rugcheckScore == 0) score -= 20"))
    }
}
