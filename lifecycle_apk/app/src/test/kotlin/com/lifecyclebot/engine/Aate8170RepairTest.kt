package com.lifecyclebot.engine

import com.lifecyclebot.engine.HandoffReceiverTasks8153
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Outcome8153
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.PaperFundingGuard8170
import com.lifecyclebot.engine.truth.SetupFeatureResolver8169
import com.lifecyclebot.engine.market.LaneHunter7297
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8170 — upstream data, receiver and paper-equity regressions. */
class Aate8170RepairTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun optionalDataCannotOverrideCriticalProof() {
        val a = CandidateAdmission8136
        assertTrue(a.criticalMissing8170("liq:UNKNOWN"))
        assertTrue(a.criticalMissing8170("quote:UNKNOWN"))
        assertFalse(a.criticalMissing8170("shape:UNKNOWN"))
        assertFalse(a.criticalMissing8170("tape:STALE"))
        val edge = a.Evidence8136(a.KIND_CELL, "PUMP|MOONSHOT|EARLY", 75, 18.0, 4.0)
        val base = a.Candidate8136("mint8170", "MOONSHOT", "CROWD_FORMING",
            priceAgeMs = 1000L, expectedGrossPct = 35.0, costPct = 7.0,
            cell = edge, genericScore = 8.0, genericFloor = 30.0)
        val optional = a.decide8136(base.copy(missingInputs = listOf("shape:UNKNOWN", "tape:UNKNOWN")))
        assertEquals(a.Terminal8136.ADMIT, optional.terminal)
        assertTrue(optional.trace.any { it.startsWith("optionalUnobserved8170=") })
        assertTrue(optional.specific)
        for (critical in listOf("liq:UNKNOWN", "liquidity:UNKNOWN", "price:STALE", "route:UNKNOWN",
            "sellability:UNKNOWN", "decimals:UNKNOWN", "economics:UNKNOWN", "quote:UNKNOWN")) {
            val d = a.decide8136(base.copy(missingInputs = listOf("shape:UNKNOWN", critical)))
            assertEquals(critical, a.Terminal8136.DEFER_DATA, d.terminal)
            assertTrue(d.trace.any { it.startsWith("criticalUnobserved8170=") })
        }
        val noEdge = a.decide8136(base.copy(cell = null, missingInputs = listOf("shape:UNKNOWN"), genericScore = 90.0))
        assertEquals(a.Terminal8136.DEFER_DATA, noEdge.terminal)
        assertEquals(a.Terminal8136.REFUSE, a.decide8136(base.copy(safetyRefusal = "RUG")).terminal)
    }

    @Test fun newVersionReceiverSupersedesOnlyOnANewHandoffAndNeverOnOldEvidence() {
        val t0 = 100_000L
        val tk = HandoffReceiverTasks8153
        val base = tk.open8153(null, "mint8170", "MOONSHOT", "PROJECT_SNIPER", "PHASE",
            100L, t0, t0)!!
        assertEquals(base, tk.open8153(base, "EXPRESS", "PROJECT_SNIPER", "REPEAT", 101L,
            t0 + 1000L, t0 + 1000L, newHandoffAtMs = t0 - 1L))
        val newT = tk.open8153(base, "EXPRESS", "PROJECT_SNIPER", "FRESH", 101L,
            t0 + 2000L, t0 + 2000L, newHandoffAtMs = t0 + 2000L)!!
        assertEquals("EXPRESS", newT.lane)
        assertEquals(101L, newT.version)
        assertTrue(newT.pending)
        assertFalse(tk.due8153(newT, "EXPRESS", t0 + 2001L, 100L))
        assertTrue(tk.due8153(newT, "EXPRESS", t0 + 2001L, 101L))
        assertEquals(Outcome8153.EVALUATED, tk.onFdg8153(newT,"EXPRESS",true,null,t0 + 3000L,101L).outcome)
    }

    @Test fun nativeFitRefusalTransfersAndMissingDataTriggersRefresh() {
        val src = src("engine/HandoffReceiverTasks8153.kt")
        val block = src.substringAfter("fun onReceiverNativeRefused8153(").substringBefore("fun onNativeCandidate8153(")
        assertTrue(block.contains("refused8153(mint, lane, reason, nowMs)"))
        assertFalse(block.contains("blockTask8153(mint, lane, block"))
        assertTrue(src.contains("LanePlaybook7907.requestEvidence8153(ts, missing + liq, nowMs)"))
        assertEquals(HandoffReceiverTasks8153.Block8153.FIT,
            HandoffReceiverTasks8153.classify8153("MCAP_TOO_HIGH", true, false))
        assertEquals(HandoffReceiverTasks8153.Block8153.BINDING,
            HandoffReceiverTasks8153.classify8153("RUG_DETECTED", false, true))
    }

    @Test fun hunterRetainsTopRankedCandidatesAndGivesUnwatchedMintsASlot() {
        val ranked = (1..15).map { "mint$it" }
        val picks = LaneHunter7297.freshFirst8170(ranked, { it !in setOf("mint10", "mint11", "mint12") }, 8)
        assertEquals(listOf("mint1", "mint2", "mint3", "mint10", "mint11", "mint12", "mint4", "mint5"), picks)
        assertEquals(emptyList<String>(), LaneHunter7297.freshFirst8170(ranked, { false }, 0))
        // Each lane's own ranking is retained, only the order within the
        // already-qualified candidate population changes.
        assertEquals(ranked.take(5), LaneHunter7297.freshFirst8170(ranked, { true }, 5))
    }

    @Test fun launchSourceReadingsAreStrictlyFresherThanGeneralMarketReadings() {
        val r = SetupFeatureResolver8169
        assertEquals(60_000L, r.maxEvidenceAge8170(2.5))
        assertEquals(r.FRESH_MS_8169, r.maxEvidenceAge8170(40.0))
        assertEquals(r.FRESH_MS_8169, r.maxEvidenceAge8170(Double.NaN))
        val t = 1_900_000L
        val source = com.lifecyclebot.engine.truth.PairFlow8169.Flow8169(12, 4, -1, -1, 12.0, 30.0, 9000.0, t - 120_000L)
        assertNull(r.pairBp8169(source, t, r.maxEvidenceAge8170(2.0)).value)
        assertEquals(75.0, r.pairBp8169(source, t, r.maxEvidenceAge8170(40.0)).value!!, 1e-9)
        assertNull(r.pairLiq8169(source, t, r.maxEvidenceAge8170(2.0)).value)
    }

    @Test fun earlyLaunchUsesObservedFlowNotMissingCandles() {
        val f = LanePlaybook7907.F(age=0.8,runup=Double.NaN,pxPeak=Double.NaN,dd=Double.NaN,
            bp=Double.NaN,liq=7000.0,mcap=10_000.0,top=Double.NaN,
            chg5m=Double.NaN,chg1h=Double.NaN,holderGrowth=Double.NaN,
            volAccel=Double.NaN,planSetup="",launchPhase="", tapeCrowd=11.0,
            tapeBuyersPerMin=7.0,tapeBuyShare=76.0,tapeNetSol=1.5,tapeLargest=23.0,tapeDevSold=0.0)
        val matched = LanePlaybook7907.matches("PROJECT_SNIPER", f)!!.map { it.id }
        assertTrue(matched.contains("EARLY_BUYER_EXPANSION_8170"))
        assertFalse(LanePlaybook7907.matches("PROJECT_SNIPER", LanePlaybook7907.F(
            age=0.8,runup=Double.NaN,pxPeak=Double.NaN,dd=Double.NaN,bp=Double.NaN,
            liq=7000.0,mcap=10_000.0,top=Double.NaN,chg5m=Double.NaN,chg1h=Double.NaN,
            holderGrowth=Double.NaN,volAccel=Double.NaN,planSetup="",launchPhase=""
        ))!!.map { it.id }.contains("EARLY_BUYER_EXPANSION_8170"))
    }

    @Test fun zeroBasisPaperInventoryHasZeroFundedMarkAuthority() {
        val p = PaperFundingGuard8170
        assertFalse(p.hasFundedBasis8170(0.0, 2.5))
        assertFalse(p.hasFundedBasis8170(12.0, 0.0))
        assertFalse(p.hasFundedBasis8170(Double.NaN, 1.0))
        assertFalse(p.hasFundedBasis8170(12.0, Double.POSITIVE_INFINITY))
        assertTrue(p.hasFundedBasis8170(12.0, 2.5))
        val capital = src("engine/truth/CanonicalCapitalAuthority6450.kt")
        assertTrue(capital.contains("PAPER_UNFUNDED_MARK_SUPPRESSED_8170"))
        assertTrue(capital.contains("return@sumOf 0.0"))
    }
}
