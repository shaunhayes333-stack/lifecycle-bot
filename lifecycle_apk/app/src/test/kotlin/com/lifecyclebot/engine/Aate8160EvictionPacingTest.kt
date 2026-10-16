package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EvictionPacing8160
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8160 — paper commit eviction is paced, so a max-history repair cannot go O(n^2) at bootstrap. */
class Aate8160EvictionPacingTest {
    @Test fun scansOnlyAfterRealGrowth() {
        assertFalse(EvictionPacing8160.due8160(4096, 4096, 0))
        assertFalse(EvictionPacing8160.due8160(4096 + 256, 4096, 0))
        assertTrue(EvictionPacing8160.due8160(4096 + 257, 4096, 0))
        // a scan that could evict nothing (all uncommitted) does not rescan on every following stamp
        assertFalse(EvictionPacing8160.due8160(4400, 4096, 4400))
        assertFalse(EvictionPacing8160.due8160(4655, 4096, 4400))
        assertTrue(EvictionPacing8160.due8160(4656, 4096, 4400))
    }

    @Test fun onePassLandsAtNinetyPercentOfCap() {
        assertEquals(4400 - 3686, EvictionPacing8160.dropCount8160(4400, 4096))
        assertEquals(0, EvictionPacing8160.dropCount8160(3000, 4096))
    }

    @Test fun replayOfEightThousandStampsScansAHandfulOfTimes() {
        var last = 0; var scans = 0; var size = 0
        repeat(8192) {
            size++
            if (EvictionPacing8160.due8160(size, 4096, last)) { scans++; last = size }  // worst case: nothing committed
        }
        assertTrue("scans=$scans", scans <= 16)
    }

    @Test fun commitMapUsesThePacing() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperEconomicAtomicCommit6632.kt").readText()
        assertTrue(s.contains("if (!EvictionPacing8160.due8160(entries.size, CAP, lastEvictScanSize8160)) return"))
    }
}
