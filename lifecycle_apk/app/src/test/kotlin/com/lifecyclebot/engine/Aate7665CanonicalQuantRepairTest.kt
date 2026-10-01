package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7665CanonicalQuantRepairTest {
    @Test fun quantMetricsIsARealCanonicalConsumer() {
        val bus = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(bus.contains(""QuantMetrics7665""))
        assertTrue(bridge.contains(""QuantMetrics7665" -> deliverToQuantMetrics7665(env)"))
        assertTrue(bridge.contains("QuantMetrics.recordTrade("))
    }

    @Test fun superCriticUsesOnlyMatureQuantRisk() {
        val c = File("src/main/kotlin/com/lifecyclebot/engine/SuperAdversarialCritic7635.kt").readText()
        val q = File("src/main/kotlin/com/lifecyclebot/engine/SuperQuantRiskContext7665.kt").readText()
        assertTrue(c.contains("SuperQuantRiskContext7665.snapshot()"))
        assertTrue(c.contains("quant7665?.usable == true"))
        assertTrue(q.contains("sampleAdequacy == "USABLE" || r.sampleAdequacy == "ROBUST""))
        assertTrue(q.contains("riskPressure"))
    }

    @Test fun portfolioAnalyticsRemainsUnwiredUntilCanonicalPositionMarksExist() {
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(!bridge.contains("PortfolioAnalytics"))
    }
}
