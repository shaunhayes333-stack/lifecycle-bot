package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7549EarlyLaunchRiskShapeTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun smart_money_cannot_rescue_edge_skip_by_itself() {
        val f=src("engine/FinalDecisionGate.kt")
        val block=f.substringAfter("val early7431").substringBefore("var narrativeAdjustment")
        assertTrue(block.contains("val canonicalEdgeEvidence7549 = strongBuyers7431 || goodLiquidity7431 || decentScore7431"))
        assertTrue(block.contains("if (canonicalEdgeEvidence7549)"))
        assertFalse(block.contains("|| smartMoneyEarly7431"))
        assertTrue(block.contains("SMART_MONEY_EARLY_RISK_SHAPE_7549"))
    }

    @Test fun legacy_bypass_object_is_documented_as_risk_shape_only() {
        val e=src("engine/truth/EarlyLaunchBypass6396.kt")
        assertTrue(e.contains("EARLY LAUNCH RISK SHAPE"))
        assertTrue(e.contains("MUST NOT use allow to rescue"))
        assertTrue(e.contains("Early-risk size multiplier"))
    }

    @Test fun legacy_fresh_launch_scanner_remains_unwired_duplicate() {
        val f=src("engine/FinalDecisionGate.kt")
        assertFalse(f.contains("ModeSpecificScanners.scanFreshLaunch"))
        assertTrue(f.contains("EarlyLaunchBypass6396.evaluateForCanonicalEntry"))
    }
}
