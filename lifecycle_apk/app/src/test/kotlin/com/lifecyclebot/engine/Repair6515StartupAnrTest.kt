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
    fun `canonical failure keeps execution stopped while successful replay resumes one queued start`() {
        assertTrue(source.contains("canonicalBootstrapSucceeded6515 = false"))
        assertTrue(source.contains("START_BLOCKED_CANONICAL_BOOTSTRAP_FAILED_6515"))
        assertTrue(source.contains("canonicalStartQueued6515.compareAndSet(false, true)"))
        assertTrue(source.contains("canonicalBootstrapJob6515?.join()"))
        assertTrue(source.contains("loopJob?.isActive != true"))
    }
}
