package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExploreLive8733
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8946 — the careful path's $5k-$10k floor band: the same pocket test at its own floor, 1 open and 8 a day of its own. */
class Aate8946FloorBandTest {
    @Test fun theBandOpensUnderItsOwnLimits() {
        assertEquals(5_000.0, ExploreLive8733.BAND_LO_USD_8946, 0.0)
        assertEquals(10_000.0, ExploreLive8733.CAP_LO_USD_8928, 0.0)
        assertTrue(ExploreLive8733.withinBand8946(0, 7))
        assertFalse(ExploreLive8733.withinBand8946(1, 0))
        assertFalse(ExploreLive8733.withinBand8946(0, 8))
        assertNull("an ordinary careful buy is not held to the band's limits", ExploreLive8733.spendRefusal8733(0.04, System.currentTimeMillis(), "notABandCoin"))
        val explore = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExploreLive8733.kt").readText()
        assertTrue("only a cap miss looks below the floor", explore.contains("if (pocketWhy8939 == \"CAP\") {"))
        assertTrue(explore.contains("AgreementPocket8874.pocketVerdict8939(ts, BAND_LO_USD_8946, nowMs, requireV3 = false)"))
        assertTrue("the rest of the path is unchanged", explore.contains("tokenRefusal8936(proof)?.let { why8928(it); return false }") &&
            explore.contains("private const val MAX_SOL_8733 = 0.05"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 134. The floor band opens cautiously (8946)"))
    }
}
