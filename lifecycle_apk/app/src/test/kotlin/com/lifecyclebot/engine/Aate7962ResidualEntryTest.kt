package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7962 — a re-adopted residual keeps its original entry price for exits (Frank: +646% read as +6%). */
class Aate7962ResidualEntryTest {
    @Test fun ratioGuard() {
        assertTrue(residualPriceUsable7962(7.46))        // Frank: ~7x since the bot's buy
        assertTrue(residualPriceUsable7962(0.58))        // a residual down 42%
        assertFalse(residualPriceUsable7962(150.0 * 3))  // SOL vs USD mix-up
        assertFalse(residualPriceUsable7962(Double.NaN))
        assertFalse(residualPriceUsable7962(0.0))
    }

    @Test fun wired() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt").readText()
        assertTrue(s.contains("return residualBasis7962(mark, b)"))
        assertTrue(s.contains("source = mark.source + \"_RESIDUAL_7962\""))
    }
}
