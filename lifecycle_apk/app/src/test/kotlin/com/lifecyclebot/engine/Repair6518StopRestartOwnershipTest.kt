package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tape for the 5.0.6637 ACTIVE-but-dead stop/start race. */
class Repair6518StopRestartOwnershipTest {
    private val service = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
    private val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

    @Test
    fun `stop latch closes before asynchronous teardown is launched`() {
        val stopCase = service.indexOf("ACTION_STOP  ->")
        val accepted = service.indexOf("LIFECYCLE_STOP_ACCEPTED", stopCase)
        val latched = service.indexOf("stopInProgress = true", accepted)
        val launched = service.indexOf("scope.launch { stopBot(stopSource) }", latched)
        assertTrue(stopCase > 0 && accepted > stopCase && latched > accepted && launched > latched)
    }

    @Test
    fun `start during stop queues before inspecting old loop and never force clears stop`() {
        val startCase = service.indexOf("ACTION_START ->")
        val queued = service.indexOf("if (stopInProgress || restartAfterStopDispatchPending6518)", startCase)
        val oldLoopGuard = service.indexOf("if (loopJob?.isActive == true && !forceRestartConfirmed)", startCase)
        assertTrue(queued > startCase && oldLoopGuard > queued)
        assertFalse(service.contains("stopBot() did not complete in 30s — force-clearing flag"))
        assertFalse(service.contains("drained_after_stop=true"))
        assertTrue(service.contains("scheduleFreshStartAfterStop6518(source)"))
    }

    @Test
    fun `stop cancellation is pinned to captured job ownership`() {
        val stop = service.substring(service.indexOf("fun stopBot("), service.indexOf("private fun scheduleKeepAliveAlarm"))
        assertTrue(stop.contains("val stoppingLoopJob = synchronized(loopJobLock) { loopJob }"))
        assertTrue(stop.contains("stoppingLoopJob?.cancel"))
        assertFalse("stop tail must never cancel a replacement through the mutable field", stop.contains("loopJob?.cancel"))
    }

    @Test
    fun `paper shutdown returns before learning and per lane close fanout`() {
        val sell = executor.substring(executor.indexOf("fun paperSell("), executor.indexOf("fun closeAllPositions("))
        val fast = sell.indexOf("if (reason == \"bot_shutdown\")")
        val fluid = sell.indexOf("FluidLearning.recordPaperSell")
        val laneFanout = sell.indexOf("CashGenerationAI.closePosition")
        assertTrue(fast > 0 && fluid > fast && laneFanout > fast)
    }
}
