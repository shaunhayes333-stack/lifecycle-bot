package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8178 — three 8174/8175 regressions: cold Cortex decisions dropped, learned patterns unmatched, curve liquidity nulled. */
class Aate8178RegressionRepairTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun learnedPatternsMatchTheirLaneInBothSignatureShapes() {
        // BehaviorLearning canonical key: assetClass|runtimeMode|trader|venue|… (the lane is the third field).
        assertTrue(AICrossTalk.patternAppliesToLane7329("MEME|LIVE|SHITCOIN|PUMP|JUP|BC1|MIG0|0_5M|LIQ_LO", "SHITCOIN"))
        assertFalse(AICrossTalk.patternAppliesToLane7329("MEME|LIVE|SHITCOIN|PUMP|JUP|BC1|MIG0|0_5M|LIQ_LO", "MOONSHOT"))
        // Legacy '_' keys: quality_mode_liquidity….
        assertTrue(AICrossTalk.patternAppliesToLane7329("A_DIP_HUNTER_MID", "DIP_HUNTER"))
        assertTrue(AICrossTalk.patternAppliesToLane7329("B_BLUE_CHIP_HIGH_EARLY_RISING", "BLUECHIP"))
        assertFalse(AICrossTalk.patternAppliesToLane7329("A_SHITCOIN_MID", "CORE"))
        assertFalse(AICrossTalk.patternAppliesToLane7329("A_MOONSHOTX_MID", "MOONSHOT"))
    }

    @Test fun coldCortexDecisionIsReadOnTheCapturePoolNotDropped() {
        val s = src("engine/cortex/Cortex7885.kt")
        val cap = s.substringAfter("fun capture(ts: TokenState, labelLane: String, admitted: Boolean").substringBefore("private fun captureCold8178(")
        val cold = cap.substringAfter("if (a8174 == null) {").substringBefore("val ctx8174 =")
        // superseded by V5.0.8187: the cold capture is queued per mint|lane (coalesced, refusals counted).
        assertTrue(cold.contains("admitted) { captureCold8178("))
        assertFalse(cold.contains("cachedOrSchedule(ts, labelLane, nowMs)"))
        val body = s.substringAfter("private fun captureCold8178(").substringBefore("private fun captureNow(")
        assertTrue(body.contains("val a = assess(ts, labelLane)"))
        assertTrue(body.contains("captureNow(ts.mint, labelLane, admitted, decisionAtMs,"))
        assertTrue(body.contains("!p.lateRead8169"))
    }

    @Test fun curveLiquidityKeepsItsReadingWhileTheBuyDoorStillRevalidates() {
        val lc = src("engine/truth/LaunchCohort8173.kt")
        val fn = lc.substringAfter("fun curveRealSolFor8173(").substringBefore("/** PumpFunWS create frame")
        assertTrue(fn.contains("return read.realSol"))
        assertFalse(fn.contains("return executableReserve8174(read"))
        val depth = src("engine/truth/OnChainDepth8038.kt")
        assertTrue(depth.contains("CURVE_RESERVES_STALE_DEFER_8174"))
    }
}
