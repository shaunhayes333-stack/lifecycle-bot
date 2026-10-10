package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8021LoopRelieveTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun learningTicksLeaveTheTradingLoop() {
        val b = src("engine/BotService.kt")
        val i = b.indexOf("learningTickInFlight8020.compareAndSet(false, true)")
        assertTrue(i > 0)
        val j = b.indexOf("ForwardReturnLabeler7731.tick(", i)
        assertTrue(j > i && b.substring(i, j).contains("scope.launch(kotlinx.coroutines.Dispatchers.Default"))
        assertTrue(b.contains("markProgress(\"POST_SUPERVISOR/WALLET_RECONCILE\")"))
    }
}
