package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** V5.0.8773 — code audit (discovery F5, F6): create intake leaves the WebSocket reader; error responses are closed. */
class Aate8773IntakeAndSocketsTest {
    @Test fun create_intake_runs_off_the_reader_thread_in_order() {
        val order = java.util.Collections.synchronizedList(ArrayList<Int>())
        val done = CountDownLatch(3)
        val reader = Thread.currentThread()
        var offReader = true
        for (i in 1..3) com.lifecyclebot.network.PumpFunWS.runCreateIntake8773 {
            if (Thread.currentThread() === reader) offReader = false
            order.add(i); done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2, 3), order.toList())
        assertTrue(offReader)
        assertTrue(com.lifecyclebot.network.PumpFunWS.CREATE_QUEUE_8773 >= 1_000)
    }

    @Test fun error_responses_are_closed() {
        val sc = File("src/main/kotlin/com/lifecyclebot/engine/SolanaMarketScanner.kt").readText()
        assertTrue(sc.contains("        resp.use {\n        if (resp.isSuccessful) {"))
        assertTrue(sc.contains("resp.close()   // V5.0.8773"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/network/BirdeyeApi.kt").readText().contains("}.also { try { resp.close() } catch (_: Throwable) {} }"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/network/CoinGeckoSolanaTopMcap.kt").readText().contains("resp.use { if (it.isSuccessful) it.body?.string() else null }"))
    }
}
