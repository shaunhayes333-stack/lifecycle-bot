package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7668CorrelationRuntimeFeedTest {
    @Test fun correlationHistoryIsFedByBackgroundMarketScannerNotOnlyUi() {
        val s = File("src/main/kotlin/com/lifecyclebot/perps/PerpsMarketScanners.kt").readText()
        assertTrue(s.contains("CorrelationScanner.recordPrice(market, data.price)"))
    }

    @Test fun batchWrapperAndFullScanAreNotArtificiallyHotPathWired() {
        val s = File("src/main/kotlin/com/lifecyclebot/perps/PerpsMarketScanners.kt").readText()
        assertTrue(!s.contains("CorrelationScanner.recordPrices("))
        assertTrue(!s.contains("CorrelationScanner.scanAllCorrelations("))
        assertTrue(!s.contains("CorrelationScanner.getActionableSignals("))
    }
}
