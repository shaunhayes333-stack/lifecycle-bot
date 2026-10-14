package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.truth.LiveTruth8064
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8083 — Cortex authority + live entry deadlock repair. */
class Aate8083CortexLiveEntryTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val losing = listOf(-12.0, -5.0, -20.0, 4.0, -7.0, -13.0, -6.0, -9.0)

    @Test fun losingLaneIsABoundedPriorNotARefusal() {
        assertEquals(LiveTruth8064.LOSING_LANE_SIZE_FACTOR_8083, LiveTruth8064.priorSizeFactor8083(losing), 0.0)
        assertTrue(LiveTruth8064.LOSING_LANE_SIZE_FACTOR_8083 > 0.0)
        assertEquals(1.0, LiveTruth8064.priorSizeFactor8083(listOf(-5.0, 3.0)), 0.0)   // immature: no prior
        val t = src("engine/truth/LiveTruth8064.kt")
        assertFalse(t.contains("fun refusal8064("))
    }

    @Test fun negativeLaneWithPositiveCandidateEvidenceIsStillAssessed() {
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        val body = g.substringAfter("fun liveRefusal(ts: TokenState, lane: String, paper: Boolean").substringBefore("private val measuredEdgeAdmitted8060")
        // 8092: the token's best eligible proven/cold strategy is selected BEFORE its exact losing setup check.
        val candidateSetup = body.indexOf("val setup8083 = try {")
        val setupLoss = body.indexOf("LiveTruth8064.setupRefusal8083(l8059, setup8083, nowMs)")
        val provenOnly = body.indexOf("if (proven8059 == null && !measuredEdge8060")
        assertTrue(candidateSetup > 0 && setupLoss > candidateSetup && provenOnly > setupLoss)
        assertTrue(body.substring(candidateSetup, setupLoss).contains("LanePlaybook7907.provenSetup8056"))
        assertTrue(body.substring(candidateSetup, setupLoss).contains("LanePlaybook7907.coldStartSetup8080"))
        assertTrue(body.indexOf("LiveTruth8064.lanePrior8083(l8059, nowMs)") in setupLoss..provenOnly)
        assertFalse(body.contains("refusal8064"))
        assertTrue(body.contains("Verdict8083.DEFER, \"LIVE_PROVEN_SETUP_ONLY_8059\""))
        assertTrue(body.contains("LiveTruth8064.noteLiveAdmit8083(ts.mint, l8083, setupAt, nowMs)"))
    }

    @Test fun negativeCandidateSetupRefusesOnItsOwnBookOnly() {
        assertNull(LiveTruth8064.setupRefusal8083("SHITCOIN_8083T", null))
        assertNull(LiveTruth8064.setupRefusal8083("SHITCOIN_8083T", "NEVER_TRADED_SETUP_8083"))
        assertTrue(LiveTruth8064.provenLosing8064(losing))
    }

    @Test fun partialCortexAuthorityIsBoundedAndNeverFull() {
        // graduated: positive mean with wide SE gives no authority; tight positive edge gives partial < 1
        assertEquals(0.0, CortexScoreboard7885.graduatedFraction7955(72.0, 12.3, 15.0, 0.0, 2.0, 40, false), 0.0)
        val f = CortexScoreboard7885.graduatedFraction7955(30.0, 20.0, 4.0, 0.0, 2.0, 40, false)
        assertTrue(f > 0.0 && f <= CortexScoreboard7885.GRAD_PARTIAL_CAP_7955)
        val c = src("engine/cortex/Cortex7885.kt")
        val cell = c.substringAfter("private fun setupCellAuthority8083(").substringBefore("\n    }\n")
        assertTrue(cell.contains("coerceAtMost(CortexScoreboard7885.GRAD_PARTIAL_CAP_7955)"))
        assertTrue(cell.contains("board.inverted7948(lane, accountMode8077()) || !consistent(lane)"))
        val ov = c.substringAfter("fun overrulesEdgeRefusal(").substringBefore("fun fullAuthorityOverrules8078(")
        assertTrue(ov.contains("if (!softBlock(refusal, false)) return false"))           // hard safety intact
        assertTrue(ov.contains("(provenRefusal && fraction < 1.0)"))                         // own proven loser needs full
        assertTrue(ov.contains("CORTEX_OVERRIDE_EXPLAINED_8083"))
        assertTrue(ov.contains("superseded=${'$'}{refusal.take(80)}"))
    }

    @Test fun multipleSetupsPerLaneAndNoTriggerIsNotNegative() {
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        val proven = pb.substringAfter("fun provenSetup8056(").substringBefore("fun matchedSetups8083(")
        assertTrue(proven.contains("matchedSetups8083(ts, lane, nowMs)"))
        assertTrue(proven.contains("maxByOrNull { it.third }"))
        assertFalse(pb.contains("setup == NO_TRIGGER && noTriggerMeasured7936(st) && !noTriggerProvenPositive(st) ->"))
        assertTrue(pb.contains("setup == NO_TRIGGER && st != null && provenLosing(st, runner) ->"))
    }

    @Test fun lanePriorReachesTheOneSizeAuthority() {
        val fdg = src("engine/FinalDecisionGate.kt")
        assertTrue(fdg.contains("LiveEdgeGate7877.sizeFactor8083(ts.mint,"))
        assertTrue(fdg.contains("val prior8083 = if (config.paperMode) 1.0 else"))
    }

    @Test fun poisonedPeakHealsOnAnyBasisRejectAndCrossAssetPartialsCommit() {
        val s = src("engine/OpenPnlSanity.kt")
        assertTrue(s.contains("verdict.reason == \"TOKEN_DECIMAL_SCALE_DISCONTINUITY_6701\" || peakPoisoned8083"))
        val g = src("engine/truth/PaperPartialEconomicsGate7056.kt")
        assertTrue(g.indexOf("if (basisDerivedProceeds8083) {") < g.indexOf("return no(\"UNRECONSTRUCTIBLE_NO_EXIT_PRICE\""))
        assertTrue(g.indexOf("BASIS_NOT_PRO_RATA") < g.indexOf("if (basisDerivedProceeds8083) {"))   // basis/qty still bind
        val op = src("engine/truth/CanonicalPaperPartialOperation6510.kt")
        assertTrue(op.contains("basisDerivedProceeds8083 = basisDerivedProceeds7066 && !(markPriceUsd7029 > 0.0 && solUsd7029 > 0.0)"))
    }
}
