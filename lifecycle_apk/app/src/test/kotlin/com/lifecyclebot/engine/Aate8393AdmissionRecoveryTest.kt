package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.File

class Aate8393AdmissionRecoveryTest {
    private val ts = TokenState(mint = "mint8393", symbol = "TEST")
    private fun candidate() = Candidate8136(mint = ts.mint, lane = "MOONSHOT", selectedSetup = "BASE",
        priceAgeMs = 1000, expectedGrossPct = 30.0, costPct = 5.0, genericScore = 90.0, genericFloor = 30.0,
        live = true, setups = listOf(Evidence8136("SETUP", "BASE", 40, -12.0, 1.0)))
    private fun evaluate(c: Candidate8136, shadow: Boolean, paper: Boolean = false) =
        FdgGate8365.inContext8365(ts, c.lane, null, paper, shadow) {
            Rehearsal8339.collecting8342 {
                CandidateAdmission8136.decide8136(c) { Rehearsal8339.doorBindsNow8342(it, c.lane) }
            }
        }
    @After fun restoreMode() {
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = false)
        EvidenceHold8336.enabled8336 = true; EvidenceHold8336.rehearsal8336 = true
    }
    @Test fun heldShadowContinuesThroughEvidenceDoorsWithoutPublishingALiveIdentity() {
        val (d, reasons) = evaluate(candidate(), true)
        assertEquals(Terminal8136.ADMIT, d.terminal)
        assertEquals("BASE", d.chosenSetup)
        assertTrue(reasons.contains("SETUP_RECORD_LOSING_8136"))
        assertTrue(reasons.contains("LIVE_GENERIC_PRIOR_UNSUPPORTED_8238"))
        assertFalse(FdgGate8365.shadowOnly8393())
    }
    @Test fun actualLiveStillRefusesKnownCandidateLoss() {
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        assertEquals("SETUP_RECORD_LOSING_8136", evaluate(candidate(), false).first.reason)
        assertTrue(Rehearsal8339.binds8393("SETUP_RECORD_LOSING_8136", false, true, false, true))
    }
    @Test fun heldShadowCannotSkipEconomicsAfterALabelDoor() {
        val (d, reasons) = evaluate(candidate().copy(expectedGrossPct = 1.0), true)
        assertEquals("EXECUTION_ECONOMICS_8136", d.reason)
        assertTrue(reasons.contains("SETUP_RECORD_LOSING_8136"))
    }
    @Test fun heldShadowCannotSkipMissingPriceOrData() {
        assertEquals(Terminal8136.DEFER_DATA, evaluate(candidate().copy(priceAgeMs = Long.MAX_VALUE), true).first.terminal)
        assertEquals(Terminal8136.DEFER_DATA, evaluate(candidate().copy(missingInputs = listOf("liquidity:UNKNOWN")), true).first.terminal)
    }
    @Test fun heldShadowCannotSkipSafetyStructureOrGenericFloor() {
        assertEquals("SAFETY_REFUSED_8136", evaluate(candidate().copy(safetyRefusal = "RUG"), true).first.reason)
        assertEquals("STRUCTURAL_RISK_8136", evaluate(candidate().copy(structuralRisk = "RUG_PRONE"), true).first.reason)
        assertEquals("GENERIC_SCORE_FLOOR_8136", evaluate(candidate().copy(genericScore = 5.0), true).first.reason)
    }
    @Test fun eventLocalPaperContextOverridesTheUiMode() {
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        EvidenceHold8336.enabled8336 = true; EvidenceHold8336.rehearsal8336 = true
        assertEquals(Terminal8136.ADMIT, evaluate(candidate().copy(live = false), false, true).first.terminal)
    }
    @Test fun existingLiveHoldBasisDemotesEvidenceButNotSafety() {
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        FdgGate8365.inContext8365(ts, "MOONSHOT", FdgGate8365.BASIS_EXPLORATION_8365) {
            assertFalse(Rehearsal8339.doorBindsNow8342("LIVE_PROVEN_SETUP_ONLY_8059", "MOONSHOT"))
            assertTrue(Rehearsal8339.doorBindsNow8342("CHASING_SPIKE_8065", "MOONSHOT"))
            assertTrue(Rehearsal8339.doorBindsNow8342("LIVE_SETUP_LOSING_8083", "MOONSHOT"))
            assertTrue(Rehearsal8339.doorBindsNow8342("EVIDENCE_HOLD_8336_MOONSHOT", "MOONSHOT"))
        }
    }
    @Test fun nestedPoliciesRestoreAfterFailureAndHaveDistinctCacheKeys() {
        val outside = FdgGate8365.policyKey8393()
        FdgGate8365.inContext8365(ts, "MOONSHOT", null, false, true) {
            val held = FdgGate8365.policyKey8393()
            try { FdgGate8365.inContext8365(ts, "MOONSHOT", null, true, false) {
                assertNotEquals(held, FdgGate8365.policyKey8393()); error("test")
            } } catch (_: IllegalStateException) {}
            assertEquals(held, FdgGate8365.policyKey8393())
        }
        assertEquals(outside, FdgGate8365.policyKey8393())
    }
    @Test fun unknownAndMixedHardReasonsNeverDemote() {
        for (reason in listOf("NEW_UNKNOWN_DOOR", "PLAYBOOK_RUG", "COST_PRIOR", "REBUY_LOCKOUT", "DATA_INCOMPLETE_DEFER_8136"))
            assertTrue(reason, Rehearsal8339.binds8393(reason, true, true, true, true))
    }
    @Test fun shadowProvenanceSurvivesRestartWithoutRelabellingOldEntries() {
        val rec = ShadowPersist8355.PosRec8355("mint", "MOONSHOT", 1.0, 1000L, 5.0, 15.0,
            50.0, 360000L, 1.0, 0.0, 0, 0.0, 1.0, 1000L, advisoryEntry8393 = true)
        val encoded = ShadowPersist8355.encodePos8355(rec)
        assertEquals(rec, ShadowPersist8355.decodePos8355(encoded))
        val old = encoded.split('|').take(19).joinToString("|")
        assertFalse(ShadowPersist8355.decodePos8355(old)!!.advisoryEntry8393)
    }
    @Test fun onlyNewTaggedClosesCanProveAdvisoryPolicy() {
        val rows = listOf(doubleArrayOf(20.0, 1000.0), doubleArrayOf(20.0, 900.0, 0.0),
            doubleArrayOf(1.0, 800.0, 1.0), doubleArrayOf(20.0, 15.0, 1.0, 8394.0, 20.0), doubleArrayOf(20.0, Double.NaN, 1.0))
        assertEquals(listOf(15.0), EvidenceShadow8344.advisoryNets8393(rows, 10L))
        assertFalse(EvidenceHold8336.proof8336(listOf(15.0), listOf(0.0045)).proven)
    }
    @Test fun holdAndLiveIdentityAreDecidedAfterTheCanonicalCore() {
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        val start = gate.indexOf("fun liveRefusal(")
        val code = gate.substring(start, gate.indexOf("private fun canonicalRefusal8392", start))
        assertTrue(code.indexOf("val hold =") < code.indexOf("canonicalRefusal8392(ts"))
        assertTrue(code.contains("val final = core ?: hold ?: niche ?: wait"))
        assertTrue(code.contains("if (!paper && final == null) LiveTruth8064.noteLiveAdmit8083"))
        assertTrue(code.contains("if (!paper && core == null && hold != null && final == hold)"))
        assertTrue(code.contains("advisoryEntry8393 = shadowOnly8393"))
    }

    @Test fun heldShadowDoesNotPublishLivePositiveEvidence() {
        FdgGate8365.inContext8365(ts, "MOONSHOT", null, false, true) {
            com.lifecyclebot.engine.truth.LiveEdgeGate7877.notePositive7975("shadow-only-8393", 1000L)
        }
        assertFalse(com.lifecyclebot.engine.truth.LiveEdgeGate7877.positiveAdmit7975("shadow-only-8393", 1001L))
    }
}
