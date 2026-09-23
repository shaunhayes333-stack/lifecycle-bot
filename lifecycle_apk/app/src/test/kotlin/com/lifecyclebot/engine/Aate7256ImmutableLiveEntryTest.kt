package com.lifecyclebot.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Aate7256ImmutableLiveEntryTest {
    private fun src(relative: String): String =
        File("src/main/kotlin/com/lifecyclebot/$relative").readText()

    @Test
    fun `live execution consumes sealed score before lease and never fabricates quote success`() {
        val gate = src("engine/ExecutableOpenGate.kt")
        val executor = src("engine/Executor.kt")

        assertTrue(gate.contains("effectiveEntryScore7256"))
        assertTrue(executor.contains("private fun resolveLiveEntryScorePreLease7257"))
        assertTrue(
            executor.indexOf("private fun resolveLiveEntryScorePreLease7257") <
                executor.indexOf("private fun liveBuy(ts:"),
        )
        assertTrue(executor.contains("LIVE_EFFECTIVE_SCORE_SEALED_7256"))
        assertTrue(executor.contains("LIVE_BUY_REFUSED_PRELEASE_SCORE_7256"))
        assertTrue(
            executor.indexOf("LIVE_BUY_REFUSED_PRELEASE_SCORE_7256") <
                executor.indexOf("val buyLease = ExecutionAttemptLease.acquire", startIndex = executor.indexOf("private fun liveBuy(ts:")),
        )
        assertTrue(executor.contains("ROUTE_PLAN_OK_7256"))
        assertFalse(executor.contains("stage=preplan_route_quote"))
    }

    @Test
    fun `held and crypto projections are scoped to active account`() {
        val held = src("engine/HeldPositionSupervisor7246.kt")
        val crypto = src("perps/CryptoAltTrader.kt")

        assertTrue(held.contains("currentModeOpenPositions()"))
        assertTrue(held.contains("it.mode.equals(mode, true)"))
        assertTrue(crypto.contains("activeModePositions7256"))
        assertTrue(crypto.contains("it.isPaper == paper"))
        assertTrue(crypto.contains("it.mode.equals(if (activePaperMode) \"paper\" else \"live\", true)"))
    }
}
