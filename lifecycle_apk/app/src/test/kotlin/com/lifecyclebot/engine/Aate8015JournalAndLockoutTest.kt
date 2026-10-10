package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8015JournalAndLockoutTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theJournalHeaderIsTheRowsOnScreen() {
        val j = src("ui/JournalActivity.kt")
        assertFalse("tiles no longer read the saved lifetime counters", j.contains("lifetime7205.terminalCloses7205"))
        assertTrue(j.contains("val terminal8015 = entries.filter { it.side.equals(\"SELL\", ignoreCase = true) }"))
        assertTrue(j.contains("(\${wins8015.size}W/\${losses8015}L/\${scratches8015}S)"))
        assertTrue(j.contains("TradeHistoryStore.journalRevision7343()"))
        assertTrue(j.contains("newest8015 == lastRenderedNewest8015"))
    }

    @Test fun clearResetsThePerPositionCounters() {
        val t = src("engine/TradeHistoryStore.kt")
        assertEquals(2, Regex("lifetimeTerminalCloses7205 = 0; lifetimeTerminalWins7205 = 0").findAll(t).count())
    }

    @Test fun voterMemoIsOneSlotAndCryptoRespectsTheLockout() {
        assertTrue(src("engine/cortex/CortexVotersWide8004.kt").contains("memo[name] = Triple(ts.mint, now, r)"))
        assertTrue(src("perps/CryptoAltTrader.kt").contains("if (cooldownRefusal8015(pendingMint7436) != null) return LiveCryptoOpenResult7434.Failed(\"CATASTROPHE_COOLDOWN_8015\")"))
    }
}
