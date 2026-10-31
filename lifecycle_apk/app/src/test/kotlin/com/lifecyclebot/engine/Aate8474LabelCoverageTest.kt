package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LaneTrigger8468
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8474 — owner: "the Cortex is fully broken ... we have to close all data gaps". 8471 (97 min): Cortex graded 376 of
 * 11,643 decisions; 3,222 of 4,973 due label marks unpriced (noSource 1,519, late 1,290); off-watch missed 8,381 vs priced
 * 7,666; refused60 n568 against 12,202 lost (4 % priced), admitted60 n1300 against 4,364 lost — every verdict read survivors.
 */
class Aate8474LabelCoverageTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private fun stat(n: Int, lost: Int, mean: Double = 3.0, se: Double = 1.0, n240: Int = 0, mean240: Double = 0.0) =
        ForwardReturnLabeler7731.CellStat("K", n, mean, 0.5, 0.1, se, lost, n240, mean240)

    @Test fun theLabelBatchWaitsForTheLimiterAndRetriesARefusal() {
        val fetch = src("engine/truth/ForwardReturnLabeler7731.kt").substringAfter("private fun fetchOffWatchMarks7737(")
            .substringBefore("private const val TERMINAL_PREFIX_8395")
        assertTrue(fetch.contains("DexscreenerApi().batchPriceFetchTyped8087(ask8418, LABEL_SLOT_WAIT_MS_8474)"))
        assertFalse("no zero-wait batch left", fetch.contains("DexscreenerApi().batchPriceFetch(ask8418)"))
        assertTrue(fetch.contains("r8474.cause == com.lifecyclebot.network.DexscreenerApi.BatchCause8087.LIMITER_REFUSED"))
        assertTrue("a refused coin is re-asked next pass", fetch.contains("for (m in retry8474) offWatchAttemptAt7809.remove(m)"))
        assertTrue("and is not counted missed", fetch.contains("(batch.size - got.size - retry8474.size).coerceAtLeast(0)"))
        assertTrue(src("network/ParallelMarkFanout7088.kt").contains("dexClient7088.batchPriceFetch(c, FANOUT_SLOT_WAIT_MS_8474)"))
    }

    @Test fun aCohortWhoseLabelsWereMostlyLostIsNoVerdict() {
        assertFalse("8471 refused60: 4 % priced", DiagnosisBrief8438.covered8474(stat(568, 12_202)))
        assertFalse("8471 admitted60: 23 % priced", DiagnosisBrief8438.covered8474(stat(1_300, 4_364)))
        assertTrue(DiagnosisBrief8438.covered8474(stat(1_300, 300)))
        assertFalse(DiagnosisBrief8438.covered8474(null))
        val brief = src("engine/DiagnosisBrief8438.kt")
        assertTrue(brief.contains("val separates = coveredBoth8474 && adm != null"))
        assertTrue(brief.contains("UNRELIABLE: most labels lost"))
        // a lane's own-trigger record of survivors proves nothing either
        assertTrue(LaneTrigger8468.proven8468(false, stat(40, 10)))
        assertFalse(LaneTrigger8468.proven8468(false, stat(40, 60)))
    }

    @Test fun everyLostLabelIsNamedByVenue() {
        assertEquals("PUMP_CURVE", ForwardReturnLabeler7731.venueOf8474("AbcPump8474pump", true, false))
        assertEquals("PUMP_GRADUATED", ForwardReturnLabeler7731.venueOf8474("AbcPump8474pump", false, true))
        assertEquals("PUMP_UNREAD", ForwardReturnLabeler7731.venueOf8474("AbcPump8474pump", false, false))
        assertEquals("LAUNCHLAB", ForwardReturnLabeler7731.venueOf8474("AbcBonk8474bonk", false, false))
        assertEquals("OTHER", ForwardReturnLabeler7731.venueOf8474("So11111111111111111111111111111111111111112", false, false))
        val frl = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(frl.contains("noteLostVenue8474(o.mint, late8474)"))
        assertTrue(frl.contains("\" \" + lostByVenueLine8474() +"))
    }
}
