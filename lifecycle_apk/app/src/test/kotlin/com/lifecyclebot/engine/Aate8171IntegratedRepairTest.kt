package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexExit7897
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.TokenMetricStageRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8171 — data-executable plan, capture throughput, forward-exit
 * hygiene and fast mismatch reconciliation must remain wired.
 */
class Aate8171IntegratedRepairTest {
    private fun src(path: String): String =
        File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun cortexCaptureHasIndependentBoundedWorker() {
        val code = src("engine/cortex/Cortex7885.kt")
        assertTrue(code.contains("cortex-capture-8171"))
        // superseded by V5.0.8187: bounded by keyed accounting (coalesced per mint|lane, refusals counted), never an oldest-job poll.
        assertTrue(code.contains("java.util.concurrent.ArrayBlockingQueue(CAPTURE_QUEUE_HARD_8187 + 32)"))
        assertTrue(code.contains("submitCaptureKeyed8187(\"\${ts.mint}|\${labelLane.trim().uppercase()}\", admitted) { captureNow("))
        assertTrue(code.contains("CAPTURE_QUEUE_DROP_8171"))
        assertTrue(code.contains("capturePool8171.queue.size"))
        assertFalse(code.contains("submit(\"CAPTURE\")"))
    }

    @Test fun historicalClosesDoNotFabricateExitRegret() {
        val exit = CortexExit7897
        val at = 1_000_000L
        val horizon = 120_000L
        assertTrue(exit.recentClose8171(at, at + horizon, horizon))
        assertFalse(exit.recentClose8171(at, at + horizon + 1L, horizon))
        assertFalse(exit.recentClose8171(at, at - 1L, horizon))
        assertFalse(exit.recentClose8171(0L, at, horizon))
        val source = src("engine/cortex/CortexExit7897.kt")
        assertTrue(source.contains("EXIT_TRUTH_HISTORICAL_NO_MARK_SKIPPED_8171"))
    }

    @Test fun recentChainMismatchGetsRecheckedButHealthyAuditStaysFiveMinutes() {
        val r = ReconcileRunner8144
        assertTrue(r.shouldRecheck8171(false, 60_000L))
        assertFalse(r.shouldRecheck8171(true, 60_000L))
        assertFalse(r.shouldRecheck8171(true, 299_999L))
        assertTrue(r.shouldRecheck8171(true, 300_000L))
        assertTrue(r.shouldRecheck8171(true, -1L))
        val source = src("engine/ReconcileRunner8144.kt")
        assertTrue(source.contains("MISMATCH_RECHECK_MS_8171, TimeUnit.MILLISECONDS"))
    }

    @Test fun optionalCandlesMayUseOnlyStatisticallyProvenNetEconomics() {
        val c = CandidateAdmission8136
        val edge = CandidateAdmission8136.Evidence8136("CELL", "MOONSHOT|PROVEN", 60, 21.0, 4.0, netOfCost = true)
        val base = CandidateAdmission8136.Candidate8136("mint-no-chart", "MOONSHOT", "EARLY_BUYER_EXPANSION_8170",
            priceAgeMs = 500L, missingInputs = listOf("shape:UNKNOWN", "tape:UNKNOWN"),
            expectedGrossPct = Double.NaN, costPct = 6.0, cell = edge,
            genericScore = 5.0, genericFloor = 25.0)
        val admitted = c.decide8136(base)
        assertEquals(CandidateAdmission8136.Terminal8136.ADMIT, admitted.terminal)
        assertTrue(admitted.trace.any { it.startsWith("grossFromProvenNetLower8171=") })
        assertEquals(17.0, admitted.expectedNetPct, 1e-9)
    }

    @Test fun theUnknownStageRoutesOnlyGenuinelyEstablishedNativeFits() {
        assertTrue(src("engine/TokenMetricStageRouter.kt").contains("UNKNOWN_ESTABLISHED_HANDOFF_8171"))
        val t = TokenMetricStageRouter
        assertEquals("QUALITY", t.unknownStageOwner8171(TokenMetricStageRouter.Stage.UNKNOWN, 300.0, 220_000.0, 25_000.0, 60.0, 39.0))
        assertEquals("BLUECHIP", t.unknownStageOwner8171(TokenMetricStageRouter.Stage.UNKNOWN, 30_000.0, 3_000_000.0, 200_000.0, 57.0, 43.0))
        assertEquals(null, t.unknownStageOwner8171(TokenMetricStageRouter.Stage.UNKNOWN, 1.0, 220_000.0, 25_000.0, 60.0, 39.0))
        assertEquals(null, t.unknownStageOwner8171(TokenMetricStageRouter.Stage.UNKNOWN, 300.0, 220_000.0, 1_000.0, 60.0, 39.0))
        assertEquals(null, t.unknownStageOwner8171(TokenMetricStageRouter.Stage.DUMPING, 300.0, 220_000.0, 25_000.0, 60.0, 39.0))
    }

    @Test fun currentPriceStagePrefersFreshTokenTickOverOldCandles() {
        val t = TokenMetricStageRouter
        assertEquals(3.0, t.freshestStagePrice8171(1.0, 100L, 3.0, 150L), 0.0)
        assertEquals(1.0, t.freshestStagePrice8171(1.0, 150L, 3.0, 100L), 0.0)
        assertEquals(3.0, t.freshestStagePrice8171(Double.NaN, 0L, 3.0, 150L), 0.0)
        assertEquals(0.0, t.freshestStagePrice8171(Double.NaN, 0L, Double.NaN, 0L), 0.0)
    }

    @Test fun pricedEntryEconomicsUseFreshObservedDepthWithoutChangingSafetyAuthority() {
        val net = com.lifecyclebot.engine.truth.NetEdge8030
        assertEquals(25_000.0, net.pricingDepth8171(25_000.0, 99_000.0), 0.0)
        assertEquals(8_000.0, net.pricingDepth8171(0.0, 8_000.0), 0.0)
        assertEquals(0.0, net.pricingDepth8171(0.0, null), 0.0)
        assertEquals(0.0, net.pricingDepth8171(Double.NaN, -10.0), 0.0)
        val source = src("engine/truth/NetEdge8030.kt")
        assertTrue(source.contains("SetupFeatureResolver8169.observedLiquidity8170"))
        assertTrue(source.contains("NET_EDGE_OBSERVED_DEPTH_COST_ONLY_8171"))
    }

    @Test fun aProvenCostClearingAdmissionGetsAPlanBeforeOptionalBarsWait() {
        val p = src("engine/truth/TradePlan7739.kt")
        val start = p.indexOf("val specific8171 = candidateAdmission")
        val wait = p.indexOf("val read = readForEntry7837(ts, nowMs)")
        assertTrue(start > 0)
        assertTrue(start < wait)
        assertTrue(p.contains("if (specific8171?.admitted == true && specific8171.specific)"))
        assertTrue(p.contains("PLAN_GRADED_EDGE_OPTIONAL_BARS_ADMIT_8171"))
        assertTrue(p.indexOf("LiveEdgeGate7877.liveRefusal(ts, lane, paper, nowMs, candidateVersion, candidateAdmission)?.let { return it }") in 0 until start)
        val c = CandidateAdmission8136
        val edge = CandidateAdmission8136.Evidence8136("CELL","PROVEN",60,16.0,4.0)
        val d = c.decide8136(CandidateAdmission8136.Candidate8136(
            mint="m",lane="MOONSHOT",selectedSetup="CROWD_FORMING",priceAgeMs=1000,
            missingInputs=listOf("shape:UNKNOWN","tape:UNKNOWN"),
            expectedGrossPct=35.0,costPct=6.0,cell=edge,genericScore=4.0,genericFloor=25.0))
        assertTrue(d.admitted)
        assertTrue(d.specific)
        assertEquals("SPECIFIC_EDGE_PROVEN_8136",d.reason)
    }
}
