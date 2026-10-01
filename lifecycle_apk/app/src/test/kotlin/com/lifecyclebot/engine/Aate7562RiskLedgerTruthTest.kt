package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7562RiskLedgerTruthTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `risk sidecars stay out of canonical meme hot path`() {
        val bot = src("engine/BotService.kt")
        val fdg = src("engine/FinalDecisionGate.kt")
        val hot = bot + "\n" + fdg
        assertFalse(hot.contains("ExternalAlphaFeeds.enrichSafety("))
        assertFalse(hot.contains("GeminiCopilot.assessRisk("))
        assertFalse(hot.contains("TradeDatabase.getSuppressionStrength("))
    }

    @Test
    fun `external safety helper is explicitly off hot path and networked`() {
        val s = src("v4/meta/ExternalAlphaFeeds.kt")
        assertTrue(s.contains("call it off the hot path"))
        assertTrue(s.contains("fun enrichSafety("))
        assertTrue(s.contains("api.dexscreener.com"))
        assertTrue(s.contains("api.rugcheck.xyz"))
    }

    @Test
    fun `diagnostic and debug accessors remain non authority`() {
        val route = src("engine/execution/MemeExecutionRouteStack.kt")
        val life = src("engine/TradeLifecycle.kt")
        val sellOnly = src("engine/sell/SellOnlySafeMode.kt")
        assertTrue(route.contains("fun stackExhausted("))
        assertTrue(route.contains("EXEC_STACK_EXHAUSTED"))
        assertTrue(life.contains("for testing/debugging"))
        assertTrue(life.contains("fun forceExpireBlocked("))
        assertTrue(sellOnly.contains("fun blockedBuyCount()"))
    }

    @Test
    fun `db suppression reader is not silently promoted to hot path`() {
        val db = src("engine/TradeDatabase.kt")
        assertTrue(db.contains("fun getSuppressionStrength("))
        assertTrue(db.contains("readableDatabase.rawQuery("))
    }

    @Test
    fun `audit records B risk classifications`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7562 — B_RISK ledger truth + hot-path safety fencing"))
        assertTrue(audit.contains("ExternalAlphaFeeds.enrichSafety"))
        assertTrue(audit.contains("OPTIONAL BACKGROUND SIDECAR / DO NOT HOTPATH WIRE"))
        assertTrue(audit.contains("GeminiCopilot.assessRisk"))
        assertTrue(audit.contains("TradeLifecycle.forceExpireBlocked"))
        assertTrue(audit.contains("DEBUG/TEST-ONLY BY DESIGN"))
    }
}
