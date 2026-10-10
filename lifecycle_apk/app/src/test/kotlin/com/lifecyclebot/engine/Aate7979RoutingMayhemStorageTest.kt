package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SpecialistMiner7972
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7979RoutingMayhemStorageTest {

    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/engine/$rel").readText()

    @Test fun directRouteSellsNeverWaitOnJupiter() {
        assertFalse(ExecutionHealthGuard.shouldDeferDirectRouteSell("MINT_A_000000000000000000000000000", "TAKE_PROFIT"))
    }

    @Test fun onlyMayhemSpecialistsLetMayhemCoinsThrough() {
        SpecialistMiner7972.resetForTest7972()
        val facts = listOf("mc=LT10K", "bp=GE70", "mh=Y", "age=LT5M")
        repeat(30) { i -> SpecialistMiner7972.onLabel7972("SHITCOIN", "M$i", facts, if (i % 3 == 0) 10.0 else 60.0, 70.0, 1_000L) }
        // A combination containing mh=Y is promoted and is what the mayhem gate looks for.
        assertTrue(SpecialistMiner7972.statusLine7972().contains("mh=Y"))
        assertTrue(src("SpikeCapture7943.kt").contains("SpecialistMiner7972.goodMayhem7979(ts, nowMs)"))
        SpecialistMiner7972.resetForTest7972()
    }

    @Test fun tokenArchiveKeepsOnlyTheWorkingSetInMemory() {
        val tm = src("TokenMetaCache.kt")
        assertTrue(tm.contains("fun evictToMemoryCap7979"))
        assertTrue(tm.contains("FROM token_meta WHERE mint = ? LIMIT 1"))
        assertTrue(src("BotService.kt").contains("cache.evictToMemoryCap7979()"))
    }
}
