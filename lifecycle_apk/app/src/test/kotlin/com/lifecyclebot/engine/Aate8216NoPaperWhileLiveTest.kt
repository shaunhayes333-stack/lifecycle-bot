package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8216 — a LIVE runtime never opens a PAPER position (executor choke + ledger backstop). */
class Aate8216NoPaperWhileLiveTest {
    @After fun back() = RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = false)

    @Test fun ledgerRefusesPaperOpenOnlyWhileLive() {
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        assertTrue(RuntimeModeAuthority.isLive())
        assertTrue(CanonicalPositionAuthority6441.paperRefusedWhileLive8216("paper"))
        assertFalse("live opens are untouched", CanonicalPositionAuthority6441.paperRefusedWhileLive8216("live"))
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        assertFalse("paper runtime still opens paper", CanonicalPositionAuthority6441.paperRefusedWhileLive8216("paper"))
    }

    @Test fun executorRefusesBeforeAnyPaperWorkAndLedgerChecksBeforeMutation() {
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val body = ex.substringAfter("fun paperBuy(ts: TokenState")
        val guard = body.indexOf("markPaperBuyNotOpened(\"LIVE_RUNTIME_NO_PAPER_8216\")")
        assertTrue(guard > 0 && guard < body.indexOf("fun releasePaperBuyNonTerminal6514("))
        val led = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        val open = led.substringAfter("    fun openPosition(\n        idempotencyKey: String,")
        val b = open.indexOf("if (paperRefusedWhileLive8216(canonicalMode6490))")
        assertTrue(b > 0 && b < open.indexOf("val existingSameMint6490"))
    }
}
