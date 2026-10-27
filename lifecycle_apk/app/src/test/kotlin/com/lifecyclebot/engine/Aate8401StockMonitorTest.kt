package com.lifecyclebot.engine

import com.lifecyclebot.perps.TokenizedStockTrader
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Aate8401StockMonitorTest {
    @Test fun exitMonitorRunsWhileDefaultWorkersAreOccupied() = runBlocking {
        val release = CountDownLatch(1)
        val workers = maxOf(2, Runtime.getRuntime().availableProcessors())
        val entered = CountDownLatch(workers)
        val blockers = List(workers) {
            launch(Dispatchers.Default) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue("default workers must be occupied", entered.await(5, TimeUnit.SECONDS))
            val evaluated = CountDownLatch(1)
            var threadName = ""
            val monitor = launch(TokenizedStockTrader.monitorDispatcher8401) {
                threadName = Thread.currentThread().name
                evaluated.countDown()
            }
            assertTrue("exit work must not wait for entry CPU workers", evaluated.await(2, TimeUnit.SECONDS))
            assertTrue(threadName, threadName.startsWith("stock-exit-monitor-8401"))
            monitor.join()
        } finally { release.countDown(); blockers.joinAll() }
        val source = File("src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt").readText()
        assertTrue(source.contains("monitorJob = scope.launch(monitorDispatcher8401)"))
        assertTrue(source.contains("for (market in stockMarkets) {\n            currentCoroutineContext().ensureActive()"))
        assertTrue(source.contains("for (signal in topSignals) {\n            currentCoroutineContext().ensureActive()"))
    }
}
