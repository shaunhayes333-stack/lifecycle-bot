package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8580 — labels convert chain marks with a rate up to 30 min old; execution keeps the 5-minute rate. */
class Aate8580LabelSolRateTest {
    @Test fun labels_use_the_label_rate_and_execution_keeps_the_fresh_one() {
        assertEquals(30L * 60_000L, WalletManager.SOL_PRICE_LABEL_MAX_AGE_MS_8580)
        assertEquals(0.0, WalletManager.labelSolPrice8580(-1L), 0.0)   // nothing observed that long ago
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        // V5.0.8750 (supersedes 4): the quiet-curve label mark reads the label rate too
        assertEquals(5, Regex("WalletManager\\.labelSolPrice8580\\(nowMs\\)").findAll(lab).count())
        assertTrue(!lab.contains("WalletManager.freshSolPrice8163(nowMs)"))
        val w = File("src/main/kotlin/com/lifecyclebot/engine/WalletManager.kt").readText()
        assertTrue(w.contains("val fresh = freshSolPrice8163(nowMs)\n            if (fresh > 0.0) return fresh"))
    }
}
