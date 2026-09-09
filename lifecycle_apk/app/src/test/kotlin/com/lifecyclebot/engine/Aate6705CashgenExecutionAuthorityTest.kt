package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** V5.0.6705 — CASHGEN is executable; only STANDARD/V3_CORE are observers. */
class Aate6705CashgenExecutionAuthorityTest {
    @Before fun setup() {
        ExecutableOpenGate.resetForTests()
        LaneExecutionCoordinator.resetForTests()
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(true)
        RuntimeModeAuthority.publishExecutorMode(true)
        RuntimeModeAuthority.publishPipelineMode(true)
    }

    @After fun cleanup() { ExecutableOpenGate.resetForTests() }

    @Test fun `cashgen fdg allow materializes immutable execution intent`() {
        val mint = "CashgenExec6705${System.nanoTime()}"
        val cv = LaneExecutionCoordinator.candidateVersionFor(mint)
        val intent = ExecutableOpenGate.recordFdgAndGetIntent6533(
            mint = mint,
            symbol = "CASH6705",
            lane = "CASHGEN",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = 90,
            safetyTier = "SAFE",
            liquidityUsd = 5_000.0,
            preFdgVerdict = "BUY",
            candidateVersion = cv,
            entryScore = 82,
        )
        assertNotNull(intent)
        assertEquals("CASHGEN", intent!!.canonicalLane)
        assertTrue(intent.fdgAllowed)
        assertSame(intent, ExecutableOpenGate.activeExecutionIntent6519("PAPER", mint, cv))
    }

    @Test fun `shadow policy matches canonical meme ownership contract`() {
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val body = gate.substringAfter("private fun isShadowReadOnlyLane6487")
            .substringBefore("fun recordEntryAuthority6487")
        assertTrue(body.contains("V3_CORE"))
        assertTrue(body.contains("STANDARD"))
        assertFalse(body.contains("CASHGEN"))

        val ownership = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemeOwnershipInvariant6620.kt").readText()
        assertTrue(ownership.contains("\"MANIPULATED\", \"TREASURY\", \"CASHGEN\""))
        assertTrue(ownership.contains("setOf(\"STANDARD\", \"V3_CORE\")"))
    }
}
