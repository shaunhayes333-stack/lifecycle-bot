package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.EntryEconomics8329
import com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236
import com.lifecyclebot.engine.truth.PriceAge8549
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9019 — a price seen after the deciding clock was read is the freshest price, never a stale one; a ticket sealed
 * without a mark is measured against the price its admission read; the holder block judges the largest single wallet.
 */
class Aate9019FreshIsNotStaleTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun anAgeIsNeverNegative() {
        val now = 1_000_000L
        assertEquals(4_000L, PriceAge8549.ageMs9019(now - 4_000L, now))
        assertEquals("seen after the clock was read: fresh", 0L, PriceAge8549.ageMs9019(now + 300L, now))
        assertEquals("a five-second cycle later: still fresh", 0L, PriceAge8549.ageMs9019(now + 5_100L, now))
        assertEquals(0L, PriceAge8549.ageMs9019(now + PriceAge8549.AHEAD_MAX_MS_9019, now))
        assertEquals("further ahead than any cycle or clock skew is not a time", Long.MAX_VALUE, PriceAge8549.ageMs9019(now + PriceAge8549.AHEAD_MAX_MS_9019 + 1L, now))
        assertEquals("an unstamped price is unknown, as before", Long.MAX_VALUE, PriceAge8549.ageMs9019(0L, now))
        // the live bar reads that age: fresh passes, unknown is still stale
        assertFalse(CandidateAdmission8136.priceStale8136(PriceAge8549.ageMs9019(now + 2_000L, now), hotLaunch = true, live = true))
        assertTrue(CandidateAdmission8136.priceStale8136(PriceAge8549.ageMs9019(0L, now), hotLaunch = false, live = true))
        assertTrue(CandidateAdmission8136.priceStale8136(PriceAge8549.ageMs9019(now - 31_000L, now), hotLaunch = false, live = true))
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("val priceAge = PriceAge8549.ageCounted9019(observedAt, nowMs)"))
        assertTrue(PriceAge8549.statusLine8549().contains("seenAfterClock9019="))
        val before = PriceAge8549.aheadLine9019()
        PriceAge8549.ageCounted9019(now + 10L, now)
        assertTrue(PriceAge8549.aheadLine9019() != before)
    }

    @Test fun aTicketIsMeasuredAgainstItsOwnMarkElseThePriceItsAdmissionRead() {
        val e = EntryEconomics8329
        val now = 5_000_000L
        assertEquals(0.002 to now - 3_000L, e.decisionRef9019(0.002, now - 3_000L, 0.001 to now - 9_000L, now))
        assertEquals("a mark stamped just after this clock was read is observed now", 0.002 to now, e.decisionRef9019(0.002, now + 800L, null, now))
        assertEquals("a ticket sealed without a mark takes the admission's price", 0.001 to now - 9_000L, e.decisionRef9019(0.0, 0L, 0.001 to now - 9_000L, now))
        assertNull("no mark and no admission price: nothing to measure against", e.decisionRef9019(0.0, 0L, null, now))
        assertNull(e.decisionRef9019(Double.NaN, now, 0.0 to now, now))
        // the one refusal now names its cause
        assertNull(e.staleCause9019(false, now - 3_000L, now + 40_000L, now))
        assertEquals("MINT_MISMATCH", e.staleCause9019(true, now, now + 40_000L, now))
        assertEquals("NO_DECISION_PRICE", e.staleCause9019(false, 0L, now + 40_000L, now))
        assertEquals("PRICE_STAMPED_FAR_AHEAD", e.staleCause9019(false, now + 120_000L, now + 40_000L, now))
        assertEquals("DECISION_PRICE_OLD", e.staleCause9019(false, now - 11L * 60_000L, now + 40_000L, now))
        assertEquals("TICKET_EXPIRED", e.staleCause9019(false, now - 3_000L, now - e.LATE_SEND_GRACE_MS_8918 - 1L, now))
        assertNull("inside the late-send grace the re-quote decides", e.staleCause9019(false, now - 3_000L, now - 1_000L, now))
        assertTrue(e.staleLine9019().startsWith("decisionStale9019[byCause{"))
        assertTrue(e.line8918().contains("decisionStale9019["))
        val s = src("engine/truth/EntryEconomics8329.kt")
        assertTrue(s.contains("val ref9019 = decisionRef9019(intent.executableMarkPriceUsd6613, intent.executableMarkTimestampMs6613, admissionPrice9019[intent.mint], nowMs)"))
        assertTrue(s.contains("ts.lastPriceUpdate - now > PriceAge8549.AHEAD_MAX_MS_9019"))
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("EntryEconomics8329.noteAdmissionPrice9019(ts.mint, ts.lastPrice, ts.lastPriceUpdate, nowMs)"))
    }

    @Test fun theHolderBlockJudgesTheLargestSingleWallet() {
        val g = PreTradeHardGate
        assertEquals(5.0, g.SINGLE_HOLDER_FATAL_PCT_9019, 0.0); assertEquals(50.0, g.TOP10_SPREAD_FATAL_PCT_9019, 0.0)
        assertEquals("one wallet at the limit is refused whatever the top-10 reads", "TOP_HOLDER_CONCENTRATION", g.holderBlock9019(20.0, 5.0))
        assertEquals("TOP_HOLDER_CONCENTRATION", g.holderBlock9019(12.0, 9.0))
        assertNull("spread holders: ten wallets under the limit may hold up to 50 % between them", g.holderBlock9019(42.0, 4.2))
        assertEquals("TOP_HOLDER_CONCENTRATION", g.holderBlock9019(50.0, 4.9))
        assertNull(g.holderBlock9019(20.0, 2.0))
        // no single-wallet reading: the 35 % rule stands exactly as before
        assertEquals("TOP_HOLDER_CONCENTRATION", g.holderBlock9019(35.0, Double.NaN)); assertNull(g.holderBlock9019(34.9, Double.NaN))
        assertEquals("TOP_HOLDER_CONCENTRATION", g.holderBlock9019(42.0, -1.0))
        assertTrue("a launch nobody followed has no single-wallet reading", g.singleHolderPct9019("Aate9019NeverFollowed1111111111111111111111").isNaN())
        val s = src("engine/PreTradeHardGate.kt")
        // superseded by V5.0.9020: the top-10 share is read net of the owner's named traders before the same limit judges it
        assertTrue(s.contains("if (holderBlock9019(top10J9020, single9019) != null) {"))
        assertTrue("the chain-proven RugCheck substitute keeps the 35 % figure", s.contains("topHolderPct < TOP_HOLDER_FATAL_PCT &&"))
    }

    @Test fun aFrozenPaperCoinIsNotRepairedBesideLive() {
        assertFalse("not LIVE with paper closing out: repaired as before", MarkIdentityRepairAuthority7236.frozenPaperOnly9019("Aate9019NoSuchMint11111111111111111111111111"))
        val s = src("engine/truth/MarkIdentityRepairAuthority7236.kt")
        val fn = s.substringAfter("fun requestRepair(mint: String, context: String) {").substringBefore("repairAttempts.incrementAndGet()")
        assertTrue(fn.indexOf("if (frozenPaperOnly9019(mint)) {") in 1 until fn.indexOf("lastAttempt[mint] = now"))
        assertTrue(s.contains("CanonicalPositionAuthority6441.hasOpenMint(bare, \"paper\") && !CanonicalPositionAuthority6441.hasOpenMint(bare, \"live\")"))
    }
}
