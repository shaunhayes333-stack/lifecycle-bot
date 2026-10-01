package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7606RuntimeForwardProgressRepairTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `held mark provider executor has no backlog queue`() {
        val s = src("engine/truth/HeldHotMarkAuthority7419.kt")
        assertTrue(s.contains("ThreadPoolExecutor("))
        assertTrue(s.contains("SynchronousQueue<Runnable>()"))
        assertTrue(s.contains("HELD_HOT_PROVIDER_POOL_SATURATED_7606"))
        assertFalse(s.contains("Executors.newFixedThreadPool(2)"))
    }

    @Test
    fun `paper price impact cannot block on network`() {
        val s = src("engine/FluidLearning.kt")
        val start = s.indexOf("fun recordPriceImpact(")
        val end = s.indexOf("fun clearPriceImpact(", start)
        val body = s.substring(start, end)
        assertTrue(body.contains("WalletManager.lastKnownSolPrice"))
        assertFalse(body.contains("runBlocking"))
        assertFalse(body.contains("PriceAggregator.getPrice"))
    }

    @Test
    fun `audit records runtime repair`() {
        val a = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(a.contains("V5.0.7606 — runtime forward-progress repair"))
        assertTrue(a.contains("held=101 fresh=4 staleRefresh=1 missing=94"))
    }
}
