package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AuthorityTable8887
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.9038 — the owner's specification DL-03: observed, coverage and worst case are three records, not one mixed row. */
class Aate9038AuthorityRecordsTest {
    @Test fun eachRecordIsReadFromItsOwnSeries() {
        // 40 reads: 30 priced at +10 / -4 alternating, 10 unpriced
        val nets = (0 until 40).map { i -> if (i % 4 == 3) Double.NaN else if (i % 2 == 0) 10.0 else -4.0 }
        val c = AuthorityTable8887.grade8887(3_600_000L, "MOONSHOT", "lane", "ALL", nets, 2.0)
        val priced = nets.filter { it.isFinite() }
        val worst = nets.map { if (it.isFinite()) it else AuthorityTable8887.TOTAL_LOSS_8887 }
        assertEquals(10, c.unpriced)
        assertEquals(75.0, c.coveragePct9038, 1e-9)
        assertEquals(priced.average(), c.mean, 1e-9)
        assertEquals(worst.average(), c.worstMean9038, 1e-9)
        val pv = priced.sumOf { it * it } / priced.size - priced.average() * priced.average()
        assertEquals(kotlin.math.sqrt(pv / priced.size), c.pricedSe9038, 1e-9)
        assertEquals(priced.take(priced.size / 2).average(), c.pricedHalf1_9038, 1e-9)
        assertEquals(worst.take(worst.size / 2).average(), c.half1, 1e-9)
        // the verdict is unchanged: with a quarter of the reads a total loss this cell cannot PAY
        assertEquals(AuthorityTable8887.Verdict8887.UNPROVEN, c.verdict)
    }

    @Test fun theExportAndTheRankingUseOneSeriesEach() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/AuthorityTable8887.kt").readText()
        assertTrue(src.contains(".put(\"observed9038\", JSONObject()") && src.contains(".put(\"coverage9038\", JSONObject()") && src.contains(".put(\"worstCase9038\", JSONObject()"))
        assertTrue(src.contains("sortedBy { it.mean + t.z * it.pricedSe9038 }"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §223 — V5.0.9038: three records"))
        assertTrue(File("../../.github/workflows/build.yml").readText().contains("--tests '*Aate9038*'"))
    }
}
