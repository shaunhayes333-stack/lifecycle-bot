package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExploreLive8733
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8940 — the careful path takes its whole proven cohort: 4 open, 30 a day, each buy still at most 0.05 SOL. */
class Aate8940KrakenCapsTest {
    @Test fun theCapsTakeTheCohortAndTheSizeStays() {
        assertEquals(4, ExploreLive8733.MAX_OPEN_8733)
        assertEquals(30, ExploreLive8733.MAX_PER_DAY_8733)
        assertTrue(ExploreLive8733.withinBudget8733(3, 29))
        assertFalse(ExploreLive8733.withinBudget8733(4, 0))
        assertFalse(ExploreLive8733.withinBudget8733(0, 30))
        val explore = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExploreLive8733.kt").readText()
        assertTrue(explore.contains("private const val MAX_SOL_8733 = 0.05"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 128. The careful path takes all of its cohort (8940)"))
    }
}
