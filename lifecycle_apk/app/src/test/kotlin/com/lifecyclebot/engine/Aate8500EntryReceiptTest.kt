package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EntryReceipt8500
import com.lifecyclebot.engine.truth.RunnerFunnel8297
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8500EntryReceiptTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun bandsNameWhereOnTheChartABuyWasMade() {
        assertEquals("<15", EntryReceipt8500.runupBand8500(5.0))
        assertEquals("40-80", EntryReceipt8500.runupBand8500(60.0))
        assertEquals("150+", EntryReceipt8500.runupBand8500(400.0))
        assertEquals("atHigh", EntryReceipt8500.peakBand8500(0.99))
        assertEquals("pullback", EntryReceipt8500.peakBand8500(0.75))
        assertEquals("?", EntryReceipt8500.peakBand8500(0.0))
    }

    @Test fun aReceiptFoldsIntoDoorStageRunupAndPeakCohorts() {
        val r = EntryReceipt8500.Receipt8500("MOONSHOT", "ADMIT_X+GRAB", "S", "CONTROLLED_MARKUP", 90.0, 0.98, 30.0, 4.0, 1L)
        assertEquals(listOf("door|MOONSHOT|ADMIT_X+GRAB", "stage|MOONSHOT|CONTROLLED_MARKUP", "runup|80-150", "peak|atHigh"),
            EntryReceipt8500.keysFor8500(r))
    }

    @Test fun aLiveCloseGradesItsReceiptAndTheBookSurvivesARestart() {
        EntryReceipt8500.resetForTest8500()
        EntryReceipt8500.fold8500("door|MOONSHOT|A", RunnerFunnel8297.Outcome8297.LOSS_BOUGHT_TOP, -12.0)
        EntryReceipt8500.fold8500("door|MOONSHOT|A", RunnerFunnel8297.Outcome8297.WIN, 20.0)
        val enc = EntryReceipt8500.encode8500()
        EntryReceipt8500.resetForTest8500()
        EntryReceipt8500.decode8500(enc)
        val line = EntryReceipt8500.line8500()
        assertTrue(line, line.contains("MOONSHOT|A{n2 w1 top1 gave0 +4.0%}"))
        // a paper or non-terminal close grades nothing
        EntryReceipt8500.onLiveClose8500("m", "paper", true, -5.0, 0.0)
        EntryReceipt8500.onLiveClose8500("m", "live", false, -5.0, 0.0)
        assertTrue(EntryReceipt8500.line8500().contains("{n2 "))
    }

    @Test fun theReceiptIsWiredAtAdmissionBuyLandedAndClose() {
        assertTrue(src("engine/truth/EntryReceipt8500.kt").contains("AdmissionEdge8180.proof8198(\"LIVE\", ts.mint, lane, version, nowMs)"))
        val ex = src("engine/Executor.kt")
        assertTrue(ex.indexOf("EntryReceipt8500.onLiveBuy8500(ts, entryEvidence7863.receipt8506, pidLive6486)") in 1 until ex.indexOf("\"LIVE_BUY_LANDED\", \"mint="))
        val cap = src("engine/truth/RunnerCapture8017.kt")
        assertTrue(cap.contains("EntryReceipt8500.onLiveClose8500(env.mint, env.mode, env.terminal, env.realizedReturnPct, env.mfePct, env.positionId, env.atMs, env.holdingTimeMs, env.lane, env.assetClassTag)"))
        assertTrue(cap.contains("EntryReceipt8500.line8500()"))
    }

    @Test fun rugProneReadsTop10AtTheSafetyOwnersConcentratedTierAndNamesItsTrigger() {
        // V5.0.8696 — top-10 concentration left the structural stage (its refusals did better than the rest); it names the
        // two tests that remain.
        assertEquals("VALUATION_AIR", TokenMetricStageRouter.rugWhy8500(true, false))
        assertEquals("VALUATION_AIR+THIN_LIQ_RUNUP", TokenMetricStageRouter.rugWhy8500(true, true))
        val r = src("engine/TokenMetricStageRouter.kt")
        assertTrue(!r.contains("val topHeavy"))
        assertTrue(r.contains("val rugProne = airRug8500 || thinRug8500"))
        // hard safety on the same number is untouched
        assertTrue(src("engine/TokenSafetyChecker.kt").contains("topHolderPct > 80.0 ->"))
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("STRUCTURAL_RISK_8136_WHY_"))
    }
}
