package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8418 §NEVER_BUY_THE_UNRETRACED_IMPULSE / §CHART_TIMING_BINDS_FOR_SPECIALISTS. */
class Aate8418ChartTimingTest {
    private fun src(p: String) = java.io.File("src/main/kotlin/com/lifecyclebot/engine/$p").readText()

    @Test fun evidenceAdmitsReadTheChartFirst() {
        val plan = src("truth/TradePlan7739.kt")
        val body = plan.substringAfter("fun liveBlockReason(").substringBefore("private fun chartPlanRefusal8136(")
        val read = body.indexOf("val read8418 = readForEntry7837(ts, nowMs)")
        val specificPlan = body.indexOf("if (specific8171?.admitted == true && specific8171.specific) {")
        assertTrue("the chart is read before any evidence admit plans", read in 0 until specificPlan)
        assertTrue(body.contains("if (evidenceAdmit8418 && read8418.setup != null)"))
        assertTrue(body.contains("if (evidenceAdmit8418 && read8418.why == \"NO_PULLBACK_YET\")"))
    }

    @Test fun theTradePlanBindsALiveSpecialistBuy() {
        val fdg = src("FinalDecisionGate.kt")
        val helper = fdg.substringAfter("private fun specialistAdvisoryBlock7845(").substringBefore("private fun cellProofBlock7731(")
        assertTrue(helper.contains("if (!paper) { try { PipelineHealthCollector.labelInc(\"FDG_TRADE_PLAN_BINDS_SPECIALIST_8418\") } catch (_: Throwable) {}; return plan8418 }"))
        // evaluate() itself is untouched (Aate8418FdgEvaluateTextTest pins its return block)
        assertTrue(fdg.indexOf("private fun specialistAdvisoryBlock7845(") < fdg.indexOf("fun evaluate("))
    }
}
