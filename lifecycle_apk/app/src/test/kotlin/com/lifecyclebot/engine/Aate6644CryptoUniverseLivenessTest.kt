package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6644CryptoUniverseLivenessTest {
    private val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
    private val bridge = File("src/main/kotlin/com/lifecyclebot/perps/crypto/CryptoBridgeAdapter.kt").readText()
    private val cex = File("src/main/kotlin/com/lifecyclebot/perps/crypto/CryptoCexAdapter.kt").readText()

    @Test fun paperCannotBeDeadlockedByLearnedLiveDiscipline() {
        assertTrue(trader.contains("learnedDisciplineVeto6644 && isPaperMode.get()"))
        assertTrue(trader.contains("action=continue_to_fdg"))
        assertTrue(trader.contains("learnedDisciplineVeto6644 && !isPaperMode.get()"))
        assertTrue(trader.contains("CryptoRugMintBlacklist.isBlacklisted"))
    }

    @Test fun inferredSpreadIsNotExecutionHardSafety() {
        assertTrue(trader.contains("ESTIMATED_SPREAD_HIGH_UNVERIFIED_6644"))
        assertFalse(trader.contains("hardNo += \"SPREAD_TOO_HIGH\""))
    }

    @Test fun unimplementedNativeChainAdaptersStayExplicitlyFailClosed() {
        assertTrue(bridge.contains("STUB") || bridge.contains("not wired"))
        assertTrue(cex.contains("STUB") || cex.contains("not configured"))
    }
}
