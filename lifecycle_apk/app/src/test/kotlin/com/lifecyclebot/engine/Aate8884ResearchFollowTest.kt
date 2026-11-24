package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ResearchFollow8884
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8884 — owner: "fuck 4x. I want it to look far far beyond that". Every decision that wrote its hour research row is
 * read again 4 h and 24 h after it, priced by Jupiter in the entry's own unit, and written to the same research file.
 */
class Aate8884ResearchFollowTest {
    @Before fun setUp() = ResearchFollow8884.resetForTest8884()
    @After fun tearDown() = ResearchFollow8884.resetForTest8884()

    @Test fun aReadIsNetAndGrossAgainstTheDecisionsOwnEntry() {
        val r = ResearchFollow8884.returns8884(0.001, 0.05, 3.0)!!
        assertEquals("a 50x is +4,900 % gross", 4_900.0, r.second, 1e-6)
        assertEquals(4_897.0, r.first, 1e-6)
        assertNull("no price is no number", ResearchFollow8884.returns8884(0.001, Double.NaN, 3.0))
        assertNull(ResearchFollow8884.returns8884(0.0, 0.05, 3.0))
        val body = JSONObject("""{"MintA":{"usdPrice":0.0123,"blockId":1},"MintB":{"usdPrice":0}}""")
        assertEquals(0.0123, ResearchFollow8884.priceOf8884(body, "MintA")!!, 1e-12)
        assertNull("a zero price is not a price", ResearchFollow8884.priceOf8884(body, "MintB"))
        assertNull("an unpriced mint is absent from the answer", ResearchFollow8884.priceOf8884(body, "MintC"))
        assertEquals(listOf(4L * 3_600_000L, 24L * 3_600_000L), ResearchFollow8884.HORIZONS_8884.toList())
    }

    @Test fun aDecisionWaitsWithItsFactsAndSurvivesTheFileRoundTrip() {
        ResearchFollow8884.note8884(1_000L, "MintA", "MOONSHOT", true, "EARLY", "FIRST_DIP", 12.0, 30.0, 9_000.0, 20_000.0, 3.0, 0.0001, "ADMIT")
        ResearchFollow8884.note8884(1_000L, "MintB", "MOONSHOT", false, "", "", Double.NaN, Double.NaN, Double.NaN, Double.NaN, 3.0, 0.0, "X")
        val w = ResearchFollow8884.pendingForTest8884().single()
        assertEquals("waits for the 4 h read first", 0, w.next)
        val back = ResearchFollow8884.decode8884(ResearchFollow8884.encode8884(w))
        assertEquals(w, back)
        val nan = ResearchFollow8884.decode8884(ResearchFollow8884.encode8884(w.copy(pred = Double.NaN)))!!
        assertTrue(nan.pred.isNaN())
        assertNull(ResearchFollow8884.decode8884("{bad"))
        assertTrue(ResearchFollow8884.statusLine8884().contains("waiting4h=1 waiting24h=0"))
    }

    @Test fun itIsWiredAtTheHourRow() {
        fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
        val book = src("engine/truth/ForwardReturnLabeler7731.kt").substringAfter("private fun bookTwoForty7944(").substringBefore("private fun flatMarkNow8342(")
        assertTrue(book.indexOf("ResearchFollow8884.note8884(o.atMs, o.mint, o.lane") > book.indexOf("ResearchRows8877.note8877("))
        assertTrue("never for a flat (lost) mark", book.indexOf("if (flatMarkNow8342(o, gross0)) { flatMarks8322.incrementAndGet(); markLost(o); lostHour8983(o); return true }") <
            book.indexOf("ResearchFollow8884.note8884("))
        val follow = src("engine/truth/ResearchFollow8884.kt")
        assertTrue(follow.contains("https://lite-api.jup.ag/price/v3?ids="))
        // superseded by V5.0.8993: the row is built first, then emitted (an unpriced one carries the decision's price for the chain)
        assertTrue(follow.contains("val row8993 = ResearchRows8877.line8877(w.atMs, horizon, w.mint"))   // V5.0.8978 — filed here, or by the trading process from the lab
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("ResearchFollow8884.statusLine8884()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 73. Every decision is read again at 4 h and 24 h (8884)"))
    }
}
