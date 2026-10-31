package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexAuthority8366
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
        // no early lane-wide return before the candidate's own evidence is read
        assertTrue(body.indexOf("LiveTruth8064.lanePrior8083(l8059, nowMs)") < body.indexOf("LanePlaybook7907.provenSetup8056(ts, l8059, nowMs)"))
        assertFalse(body.contains("refusal8064"))
        // the candidate's own losing setup still refuses, before any admission door
        assertTrue(body.indexOf("LiveTruth8064.setupRefusal8083(l8059, setup8083, nowMs)") < body.indexOf("LanePlaybook7907.provenSetup8056(ts, l8059, nowMs)"))
        // superseded (8480, owner directive "no evidence is not proven bad"): the proven-only door is removed
        assertFalse(body.contains("return \"LIVE_PROVEN_SETUP_ONLY_8059\""))
        assertTrue(g.contains("if (!paper && final == null) LiveTruth8064.noteLiveAdmit8083"))
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
        // 8372: setup exceptions use the same current STRONG-vs-rest proof as lane authority. Historical bucket
        // inversion / means cannot become an alternative proof; current calibration and realised revocation still bind.
        assertTrue(cell.contains("authorityTest8366(lane, setup)"))
        assertTrue(cell.contains("CortexAuthority8366.authority8366(proof,"))
        assertTrue(cell.contains("calibration.uncalibrated8372(lane, executableOffset8187(lane)), n, mean)"))
        assertFalse(cell.contains("jointBook7961["))
        // superseded by V5.0.8418 — 21d066011 flipped the board.inverted7948 pin to assertFalse with no note: inversion is again
        // a revocation, now on the CURRENT cohort, inside the one authority the setup cell reads (authorityFraction8366).
        assertFalse(cell.contains("board.inverted7948"))
        assertTrue(c.contains("if (invertedCurrent8372(lane)) { inc(\"AUTHORITY_REVOKED_INVERTED_8418_\$lane\"); return 0.0 }"))
        val ov = c.substringAfter("fun overrulesEdgeRefusal(").substringBefore("fun fullAuthorityOverrules8078(")
        assertTrue(ov.contains("if (!softBlock(refusal, false)) return false"))           // hard safety intact
        assertTrue(ov.contains("(provenRefusal && fraction < 1.0)"))                         // own proven loser needs full
        assertTrue(ov.contains("CORTEX_OVERRIDE_EXPLAINED_8083"))
        assertTrue(ov.contains("superseded=${'$'}{refusal.take(80)}"))
    }

    @Test fun currentSetupProofRetainsPartialCapAndRejectsInversionOrRevocation() {
        fun rows(scale: Double) = (0 until 500).map { i ->
            val score = (((i * 37) % 100) - 50.0) / 5.0
            val at = 1_000_000L + i * 60_000L
            CortexAuthority8366.Row8366(at, score, score * scale + 1.0,
                CortexScoreboard7885.bucketOf(score, 0.0, false).ordinal, at + 300_000L)
        }
        fun proof(scale: Double): CortexAuthority8366.Result8366 {
            val sample = rows(scale)
            return CortexAuthority8366.test8372(sample, 1.5, sample.maxOf { it.outcomeAvailableAtMs } + 1L)
        }
        val partial = proof(0.2)
        assertTrue(partial.granted)
        assertTrue(partial.fraction > 0.0 && partial.fraction < 1.0)
        val full = proof(2.0)
        assertEquals(1.0, CortexAuthority8366.authority8366(full, false, 0.0, Double.NaN), 0.0)
        assertEquals(CortexScoreboard7885.GRAD_PARTIAL_CAP_7955,
            CortexAuthority8366.authority8366(full, false, 0.0, Double.NaN)
                .coerceAtMost(CortexScoreboard7885.GRAD_PARTIAL_CAP_7955), 0.0)
        assertFalse(proof(-2.0).granted)
        assertEquals(0.0, CortexAuthority8366.authority8366(proof(-2.0), false, 0.0, Double.NaN), 0.0)
        assertEquals(0.0, CortexAuthority8366.authority8366(full, true, 0.0, Double.NaN), 0.0)
        assertEquals(0.0, CortexAuthority8366.authority8366(full, false, 20.0, -1.0), 0.0)
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
