package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Repair6515StartupAnrTest {
    private val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test
    fun `service onCreate never performs canonical replay inline before returning`() {
        val onCreate = source.substring(source.indexOf("override fun onCreate()"), source.indexOf("override fun onStartCommand"))
        val launch = onCreate.indexOf("canonicalBootstrapJob6515 = scope.launch")
        val load = onCreate.indexOf("EconomicEventSchema6464.init6486")
        val rebuild = onCreate.indexOf("CanonicalPositionAuthority6441.rebuildPaperFromEvents6486")
        assertTrue(launch > 0)
        assertTrue(load > launch)
        assertTrue(rebuild > load)
        assertFalse(onCreate.substring(0, launch).contains("EconomicEventSchema6464.init6486"))
    }

    @Test
    fun `complete service failure keeps execution stopped while successful bootstrap resumes one queued start`() {
        assertTrue(source.contains("canonicalBootstrapSucceeded6515 = false"))
        assertTrue(source.contains("serviceBootstrapSucceeded6516 = false"))
        assertTrue(source.contains("START_BLOCKED_SERVICE_BOOTSTRAP_FAILED_6516"))
        assertTrue(source.contains("serviceStartQueued6516.compareAndSet(false, true)"))
        assertTrue(source.contains("canonicalBootstrapJob6515?.join()"))
        assertTrue(source.contains("serviceBootstrapJob6516?.join()"))
        assertTrue(source.contains("loopJob?.isActive != true"))
    }

    @Test
    fun `persisted stores and trader startup are all downstream of IO service bootstrap`() {
        val onCreate = source.substring(source.indexOf("override fun onCreate()"), source.indexOf("override fun onStartCommand"))
        val launch = onCreate.indexOf("serviceBootstrapJob6516 = scope.launch")
        val prefix = onCreate.substring(0, launch)
        val background = onCreate.substring(launch)
        listOf("FeeRetryQueue.init(", "TradeHistoryStore.init(", "LearningPersistence.init(",
            "PositionPersistence.init(", "PerpsTraderAI.init(", "TokenizedStockTrader.start(",
            "CryptoAltTrader.start(").forEach { forbidden -> assertFalse(prefix.contains(forbidden)) }
        assertTrue(background.contains("TradeHistoryStore.init(applicationContext)"))
        assertTrue(background.contains("SERVICE_BOOTSTRAP_READY_6516"))
    }
}
