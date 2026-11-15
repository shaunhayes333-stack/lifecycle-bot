package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8775 — code audit (data P1-6): an unread 50 / 50 book does not stage a coin PEAK, DUMP or mid-accumulation. */
class Aate8775UnreadBookStageTest {
    @Test fun every_pressure_test_needs_a_read_book() {
        val r = File("src/main/kotlin/com/lifecyclebot/engine/TokenMetricStageRouter.kt").readText()
        assertTrue(r.contains("((bookObserved8464 && (sp >= 52.0 || bp < 52.0)) || hour >= 80.0)"))
        assertTrue(r.contains("(dd >= 24.0 && bookObserved8464 && (bp < 52.0 || sp >= 55.0))"))
        assertTrue(r.contains("dd in 8.0..35.0 && bookObserved8464 && bp >= 50.0"))
        assertTrue(r.indexOf("val bookObserved8464 =") < r.indexOf("val peakExhaustion ="))
    }
}
