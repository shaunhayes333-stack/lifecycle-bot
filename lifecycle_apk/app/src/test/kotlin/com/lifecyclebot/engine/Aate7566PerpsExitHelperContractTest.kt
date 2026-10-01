package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7566PerpsExitHelperContractTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `perps and market rows are helper shaped`() {
        assertTrue(src("perps/PerpsAdvancedAI.kt").contains("fun shouldPartialExit("))
        assertTrue(src("perps/PerpsTrailingStop.kt").contains("fun getTrailStop("))
        assertTrue(src("perps/JupiterPerps.kt").contains("suspend fun getPoolInfo("))
        assertTrue(src("perps/JupiterPerps.kt").contains("withContext(Dispatchers.IO)"))
        assertTrue(src("perps/strategy/ForexStrategy.kt").contains("fun tpSlPrices("))
        assertTrue(src("engine/execution/RouteValidator.kt").contains("fun validateFinalOutput("))
    }

    @Test
    fun `operator authority remains explicit`() {
        val repair = src("engine/RuntimeRepairState.kt")
        val toxic = src("engine/ToxicModeCircuitBreaker.kt")
        assertTrue(repair.contains("REQUEST_PAPER_MODE_IGNORED"))
        assertTrue(repair.contains("mode authority is operator-controlled"))
        assertTrue(toxic.contains("fun activateEmergencyStop()"))
        assertTrue(toxic.contains("fun deactivateEmergencyStop()"))
    }

    @Test
    fun `audit records helper classifications`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7566 — C_EXIT perps/market helper + operator-control classification"))
        assertTrue(audit.contains("BACKGROUND NETWORK HELPER"))
        assertTrue(audit.contains("OPERATOR-AUTHORITY NO-OP"))
        assertTrue(audit.contains("EXPLICIT OPERATOR/EMERGENCY CONTROLS"))
    }
}
