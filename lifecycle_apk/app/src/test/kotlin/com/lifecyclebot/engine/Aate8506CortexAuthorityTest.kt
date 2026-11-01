package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexAuthority8366
import com.lifecyclebot.engine.cortex.CortexAuthority8366.Row8366
import com.lifecyclebot.engine.cortex.CortexScoreboard7885.Bucket
import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import org.junit.Assert.*
import org.junit.Test

class Aate8506CortexAuthorityTest {
    @Test fun nativeDecisionAndHistoricalRowsSurviveCheckpoint() {
        for (native in listOf(true, false, null)) {
            val book = CortexGradeBook8144()
            book.addDecision(CortexGradeRecord8144('D', "mint|MOONSHOT|1000", "mint|MOONSHOT", 1000,
                "MOONSHOT", "BASE", "TREND", "EARLY", "LOW", "SMALL", 2, 30.123456789, true, true, false)
                .apply { incumbent8506 = native })
            val encoded = book.encode()
            val restored = CortexGradeBook8144().apply { decode(encoded) }.decision("mint|MOONSHOT|1000")!!
            assertEquals(native, restored.incumbent8506)
            assertEquals(30.123456789, restored.predictedEdgePct, 0.0)
            // Prior builds wrote 47 fields, without a frozen native decision.
            val historical = encoded.substringBeforeLast('\u001f')
            val old = CortexGradeBook8144().apply { decode(historical) }.decision("mint|MOONSHOT|1000")!!
            assertNull(old.incumbent8506)
        }
    }

    private fun rows() = (1..123).map { i ->
        val native = i % 3 != 0
        val bucket = when (i % 3) { 0 -> Bucket.STRONG; 1 -> Bucket.REFUSE; else -> Bucket.NEUTRAL }
        val net = if (bucket == Bucket.REFUSE) -6.0 else 12.0
        Row8366(i.toLong(), net, net, bucket.ordinal, 500L, 500L, native)
    }

    @Test fun onlySameOpportunityNetOutperformanceCanPromote() {
        val rows = rows()
        assertTrue(CortexAuthority8366.beatsIncumbent8506(rows, 600L))
        assertFalse(CortexAuthority8366.beatsIncumbent8506(rows.map { it.copy(incumbent8506 = null) }, 600L))
        assertFalse(CortexAuthority8366.beatsIncumbent8506(rows.map { it.copy(netPct = -it.netPct) }, 600L))
        assertFalse(CortexAuthority8366.beatsIncumbent8506(rows.map { it.copy(frozenBucket = Bucket.NEUTRAL.ordinal) }, 600L))
        assertFalse(CortexAuthority8366.beatsIncumbent8506(rows, 400L))
        assertFalse(CortexAuthority8366.beatsIncumbent8506(rows.map {
            if (it.incumbent8506 == false && it.atMs % 2L == 0L) it.copy(netPct = Double.NaN) else it
        }, 600L))
    }
}
