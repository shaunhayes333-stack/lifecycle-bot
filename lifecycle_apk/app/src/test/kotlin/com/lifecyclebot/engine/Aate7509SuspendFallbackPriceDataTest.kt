package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7509SuspendFallbackPriceDataTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test fun fallback_is_suspend_and_contains_no_nested_runBlocking() {
        val s = src()
        val fn = s.substringAfter("private suspend fun tryFallbackPriceData(")
            .substringBefore("private val entryHydrationPending6647")
        assertFalse(fn.contains("runBlocking"))
        assertTrue(fn.contains("withTimeoutOrNull(2000L)"))
        assertTrue(fn.contains("DexScreenerOracle.getPriceByAddress"))
        assertTrue(fn.contains("BirdeyeOracle.getPriceByAddress"))
    }

    @Test fun entry_hydration_runs_fallback_on_io_dispatcher() {
        val s = src()
        val fn = s.substringAfter("private fun requestEntryHydration6647")
            .substringBefore("// ═══════════════════════════════════════════════════════════════════════════")
        assertTrue(fn.contains("withContext(kotlinx.coroutines.Dispatchers.IO)"))
        assertTrue(fn.contains("tryFallbackPriceData(mint, ts)"))
    }

    @Test fun all_existing_provider_branches_remain() {
        val s = src()
        val fn = s.substringAfter("private suspend fun tryFallbackPriceData(")
            .substringBefore("private val entryHydrationPending6647")
        assertTrue(fn.contains("BirdeyeApi"))
        assertTrue(fn.contains("DexScreenerOracle"))
        assertTrue(fn.contains("BirdeyeOracle"))
        assertTrue(fn.contains("frontend-api-v3.pump.fun"))
    }
}
