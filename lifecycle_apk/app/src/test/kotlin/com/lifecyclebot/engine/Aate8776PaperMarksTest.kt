package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8776 — code audit (data P1-2, P1-7): the paper / shadow books and the labels read only what was observed. */
class Aate8776PaperMarksTest {
    @Test fun the_shadow_and_paper_marks_refuse_seeds_and_unwitnessed_prints() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/ShadowMark8360.kt").readText()
        assertTrue(s.contains("if (ts != null && !TokenFacts.seedPrice8559(ts.lastPriceSource)) candidates += Observation8372(ts.lastPrice"))
        assertTrue(s.contains("TradeTape8100.lastPrint8360(mint)?.takeIf { PrintWitness8750.witnessedPrint8750(mint, it.second) }"))
        assertTrue(s.contains("val seed8775 = ts != null && TokenFacts.seedPrice8559(ts.lastPriceSource)"))
        assertTrue(s.contains("PrintWitness8750.witnessedPrint8750(mint, print.second)"))
    }

    @Test fun an_off_watch_snapshot_is_not_a_live_market_read() {
        assertFalse(ForwardReturnLabeler7731.liveMark8454("OFFWATCH", false))
        assertTrue("a chain witness still is", ForwardReturnLabeler7731.liveMark8454("OFFWATCH", true))
    }
}
