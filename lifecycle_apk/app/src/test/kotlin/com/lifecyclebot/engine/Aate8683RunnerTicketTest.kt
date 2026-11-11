package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import com.lifecyclebot.engine.truth.MemePaperTrader8544
import com.lifecyclebot.engine.truth.PocketLedger8610
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8683 — runner tickets that can actually issue, in groups the bot learns.
 * 5.0.8680: tickets8073 issued=0 (a 0.106 SOL wallet's 10 % budget is under one executable ticket); 13 of 14 +400 % runners
 * the bot watched were never bought; 51 of 54 coins that went +500 % inside 15 min were under 5 minutes old.
 */
class Aate8683RunnerTicketTest {
    @Test fun aSmallWalletMayHoldOneTicketAtTheMinimum() {
        assertFalse("8680: the old 10 % rule refused every ticket", RunnerTicket8073.fits8073(0.0, 0.0273, 0.106))
        assertTrue(RunnerTicket8073.fitsFluid8681(0.0, 0.0273, 0.106))
        assertFalse("one at a time on a small wallet", RunnerTicket8073.fitsFluid8681(0.0273, 0.0273, 0.106))
        assertFalse("never more than 40 % of equity in one ticket", RunnerTicket8073.fitsFluid8681(0.0, 0.0273, 0.06))
        assertTrue("a larger wallet keeps the 10 % budget", RunnerTicket8073.fitsFluid8681(0.0273, 0.0273, 1.0))
        assertFalse(RunnerTicket8073.fitsFluid8681(0.0819, 0.0273, 1.0))
        assertFalse(RunnerTicket8073.fitsFluid8681(0.0, 0.0273, 0.0))
    }

    @Test fun theTicketGroupFollowsTheLearnedPockets() {
        assertTrue("the fixed graduated group still qualifies", RunnerTicket8073.groupFluid8681(true, null))
        assertTrue(RunnerTicket8073.groupFluid8681(false, PocketLedger8610.Call.PAYS))
        assertFalse(RunnerTicket8073.groupFluid8681(false, PocketLedger8610.Call.UNPROVEN))
        assertFalse(RunnerTicket8073.groupFluid8681(false, PocketLedger8610.Call.LOSES))
        assertFalse("an unseeded ledger proves nothing", RunnerTicket8073.groupFluid8681(false, null))
        val t = File("src/main/kotlin/com/lifecyclebot/engine/RunnerTicket8073.kt").readText()
        assertTrue(t.contains("if (!fitsFluid8681(openTicketSol(), ticketSol, equity))"))
        assertTrue(t.contains("PocketLedger8610.verdict8610(ts, POCKET_LANE_8681, nowMs)"))
        // every other door is unchanged: the record gate still decides last
        assertTrue(t.indexOf("SelectionDoors8310.ticketMayIssue8310(nowMs)") > t.indexOf("fitsFluid8681(openTicketSol()"))
    }

    @Test fun theShadowUnlocksLiveButNeverRestrictsIt() {
        assertNull(LiveEdgeGate7877.shadowAdvisoryOnLive8683("MOONSHOT", "NICHE_ONLY_8351_MOONSHOT_M:<50k"))
        assertNull(LiveEdgeGate7877.shadowAdvisoryOnLive8683("EXPRESS", "ENTRY_TIMING_WAIT_8347_PULLBACK"))
        assertNull(LiveEdgeGate7877.shadowAdvisoryOnLive8683("EXPRESS", null))
        val g = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        assertTrue(g.contains("shadowAdvisoryOnLive8683(l, try { ExploreShadow8347.nicheRefusal8351("))
        // V5.0.8687 — the proven entry-timing wait (a relative verdict) binds on LIVE again; only the niche stays advisory
        assertFalse(g.contains("shadowAdvisoryOnLive8683(l, try { ExploreShadow8347.liveEntryWait8347("))
        assertTrue(g.contains("else try { ExploreShadow8347.liveEntryWait8347(ts, l, EvidenceHold8336.startMs8336, nowMs) }"))
    }

    @Test fun aPaperStopNeedsASecondMarkLikeLive() {
        assertFalse("one wick print is not a stop", MemePaperTrader8544.paperStopConfirmed8683(1_000L, 1_000L, -20.0))
        assertTrue("a later mark still through the level fires", MemePaperTrader8544.paperStopConfirmed8683(1_000L, 2_500L, -20.0))
        assertTrue("a collapse fires at once", MemePaperTrader8544.paperStopConfirmed8683(1_000L, 1_000L, -65.0))
        assertFalse(MemePaperTrader8544.paperStopConfirmed8683(0L, 2_500L, -20.0))
    }
}
