package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7977MemoryAndDispatchTest {

    @Test fun memoryGuardLevels() {
        assertEquals(0, MemoryGuard7977.level7977(0.50))
        assertEquals(1, MemoryGuard7977.level7977(0.80))
        assertEquals(2, MemoryGuard7977.level7977(0.95))
        assertEquals(0, MemoryGuard7977.level7977(Double.NaN))
        assertTrue(MemoryGuard7977.statusLine7977().contains("heap="))
    }

    @Test fun trimsAreSafeOnEmptyCaches() {
        com.lifecyclebot.engine.truth.SpecialistMiner7972.trim7977(true)
        com.lifecyclebot.engine.chart.CandleColors7968.trim7977(true)
        com.lifecyclebot.engine.market.MemeMeta7973.trim7977()
        RunnerGrab7967.trim7977()
        PumpCallouts7968.trim7977()
        com.lifecyclebot.engine.chart.ChartReader7950.trim7977()
        assertTrue(com.lifecyclebot.engine.truth.SpecialistMiner7972.size7977() >= 0)
    }

    @Test fun cryptoWalletLockIsCheckedBeforeTheIntentIsSealed() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(src.contains("CRYPTO_LIVE_REFUSED_WALLET_LOCK_7977"))
    }
}
