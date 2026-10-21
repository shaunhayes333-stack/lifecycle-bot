package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexOutcomeRoute8144
import com.lifecyclebot.engine.cortex.CortexOutcomeRoute8144.Attribution8144
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.cortex.LaneRealisedGovernor8295
import com.lifecyclebot.engine.cortex.LaneSetupAttribution8295
import com.lifecyclebot.engine.truth.AateEntryIdentity8136
import com.lifecyclebot.engine.truth.EntryIdentityStore8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8295 — live realised outcomes govern lane setup authority; realised closes return to the playbook setup that
 * admitted them (not the desk toolkit's setup name); a losing lane bank refutes the runner-tail exemption.
 */
class Aate8295LaneAuthorityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    /** A label book like 8286's LAUNCH_LADDER_PROVEN: n824 at +83.5%, sd ~120. */
    private fun labelBook(n: Int = 824, mean: Double = 83.5, sd: Double = 120.0): CortexLedger7885.Stat {
        val s = CortexLedger7885.realisedStat8187()
        s.n = n.toDouble(); s.sum = mean * n; s.sumSq = (sd * sd + mean * mean) * n
        s.wins = 0.55 * n; s.runners = 0.2 * n
        return s
    }

    @Test fun aRecordProvenOnLabelsLosesFullAuthorityWhenItsLiveClosesLose() {
        val book = labelBook()
        assertEquals(1.0, LanePlaybook7907.setupFraction7955(book), 1e-9)          // 8286: auth=1.00
        // The setup's own two live closes lost (8286 SHITCOIN -33.8% / -16.7%).
        val g = LaneRealisedGovernor8295.governedCopy8295(book, doubleArrayOf(2.0, -25.25), doubleArrayOf(2.0, -25.25))
        assertTrue(g.n < 30.0)
        assertTrue(g.mean() < book.mean())
        assertFalse(LanePlaybook7907.setupProvenPositive7948(g))
        assertTrue(LanePlaybook7907.setupFraction7955(g) < 1.0)
        // Even ONE losing close moves the read (8187 needed two of the opposite sign).
        val one = LaneRealisedGovernor8295.govern8295(824.0, 83.5, doubleArrayOf(1.0, -33.8), null)
        assertEquals(29.0, one[0], 1e-9)                                            // under the 30-label proof
        assertEquals((60.0 * 83.5 - 40.0 * 33.8) / 100.0, one[1], 1e-9)
    }

    @Test fun aLosingLaneBankShrinksEverySetupsEvidenceWithoutMovingItsMean() {
        // The lane's live closes (other setups) lost: n5 at -29.6%.
        val g = LaneRealisedGovernor8295.govern8295(824.0, 83.5, null, doubleArrayOf(5.0, -29.6))
        assertEquals(83.5, g[1], 1e-9)                                              // another setup's loss is not this return
        assertEquals(29.0, g[0], 1e-9)                                              // full proof needs the bank to agree
        assertFalse(LanePlaybook7907.setupProvenPositive7948(LaneRealisedGovernor8295.governedCopy8295(labelBook(), null, doubleArrayOf(5.0, -29.6))))
        val more = LaneRealisedGovernor8295.govern8295(824.0, 83.5, null, doubleArrayOf(12.0, -20.0))
        assertTrue(more[0] < 30.0)
        assertTrue(LanePlaybook7907.setupFraction7955(LaneRealisedGovernor8295.governedCopy8295(labelBook(), null, doubleArrayOf(12.0, -20.0))) < 1.0)
        // Fewer than three closes is not a lane verdict.
        val few = LaneRealisedGovernor8295.govern8295(824.0, 83.5, null, doubleArrayOf(2.0, -40.0))
        assertEquals(824.0, few[0], 0.0); assertEquals(83.5, few[1], 0.0)
    }

    @Test fun theGovernorNeverRaisesARecordAndLeavesAWinningBankAlone() {
        val book = labelBook()
        assertSame(book, LaneRealisedGovernor8295.governedCopy8295(book, doubleArrayOf(4.0, 35.0), doubleArrayOf(9.0, 12.0)))
        assertSame(book, LaneRealisedGovernor8295.governedCopy8295(book, null, null))
        // A negative label record with losing closes is never pulled up and never gains evidence.
        val neg = LaneRealisedGovernor8295.govern8295(40.0, -5.0, doubleArrayOf(3.0, 10.0), doubleArrayOf(6.0, -8.0))
        assertEquals(-5.0, neg[1], 1e-9)
        assertEquals(40.0, neg[0], 0.0)                                             // a losing record keeps its evidence
        val provenLoser = labelBook(n = 120, mean = -15.0, sd = 25.0).also { it.runners = 0.0 }
        val kept = LaneRealisedGovernor8295.governedCopy8295(provenLoser, doubleArrayOf(3.0, -20.0), doubleArrayOf(8.0, -18.0))
        assertSame(provenLoser, kept)
        assertTrue(LanePlaybook7907.provenLosing(kept, true))                       // the refusal still binds
        val small = LaneRealisedGovernor8295.govern8295(10.0, 20.0, doubleArrayOf(2.0, -30.0), null)
        assertTrue(small[0] <= 10.0); assertTrue(small[1] <= 20.0)
        // Bad inputs are unchanged.
        val nan = LaneRealisedGovernor8295.govern8295(Double.NaN, 1.0, doubleArrayOf(2.0, -3.0), null)
        assertTrue(nan[0].isNaN())
    }

    @Test fun laneAggregateAndRunnerTailRefutation() {
        val m = mapOf(
            "MOONSHOT|MAINSTREAM_CRYPTO_SWING" to doubleArrayOf(2.0, -9.9),   // pre-8295 mis-filed closes still count for the lane
            "MOONSHOT|LAUNCH_LADDER_PROVEN" to doubleArrayOf(1.0, -12.0),
            "SHITCOIN|BREAKOUT_RUNNER" to doubleArrayOf(1.0, -33.8),
            "MOONSHOT|BAD" to doubleArrayOf(Double.NaN, 3.0),
        )
        val lane = LaneRealisedGovernor8295.laneOf8295(m, "MOONSHOT")!!
        assertEquals(3.0, lane[0], 1e-9)
        assertEquals((2.0 * -9.9 - 12.0) / 3.0, lane[1], 1e-9)
        assertTrue(LaneRealisedGovernor8295.tailRefuted8295(lane))
        assertFalse(LaneRealisedGovernor8295.tailRefuted8295(LaneRealisedGovernor8295.laneOf8295(m, "SHITCOIN")))
        assertEquals(null, LaneRealisedGovernor8295.laneOf8295(m, "QUALITY"))
        // A refuted tail lets the 7907 losing rule bind on a runner lane.
        val losing = labelBook(n = 60, mean = -12.0, sd = 30.0)
        assertFalse(LanePlaybook7907.provenLosing(losing, true))                    // 20% runner tail exempted it
        assertTrue(LanePlaybook7907.provenLosing(losing, false))
    }

    @Test fun deskToolkitSetupNamesNeverOwnAPlaybookOutcome() {
        assertTrue(LaneSetupAttribution8295.deskSetup8295("MAINSTREAM_CRYPTO_SWING"))
        assertTrue(LaneSetupAttribution8295.deskSetup8295("NONE"))
        assertFalse(LaneSetupAttribution8295.deskSetup8295("LAUNCH_LADDER_PROVEN"))
        assertFalse(LaneSetupAttribution8295.deskSetup8295("BREAKOUT_RUNNER"))
        assertFalse(LaneSetupAttribution8295.deskSetup8295(""))
        // The seal left the playbook setup blank and the close filled it with the toolkit name: the Cortex's own entry setup owns it.
        val owner = CortexOutcomeRoute8144.owner8144("LIVE", "M1", "LIVE", "M1", "MOONSHOT", "MAINSTREAM_CRYPTO_SWING", null, "MOONSHOT", "LAUNCH_LADDER_PROVEN")
        assertEquals(Attribution8144.CORTEX_ENTRY, owner.attribution)
        assertEquals("LAUNCH_LADDER_PROVEN", owner.setup)
        assertEquals("MOONSHOT", owner.lane)
        val book = CortexGradeBook8144()
        val t = CortexGradeRecord8144('T', "p1", "M1|MOONSHOT", 1L, "MOONSHOT", "LAUNCH_LADDER_PROVEN", "-", "MID", "C3_6", "UNK", 0, 0.0, true, true, false)
        t.mode = "LIVE"
        assertTrue(book.openTrade(t))
        book.closeTrade("p1", owner, -10.6)
        assertEquals(-10.6, book.realisedBySetup8187("MOONSHOT", "LIVE")["LAUNCH_LADDER_PROVEN"]!![1], 1e-9)
        assertFalse(book.realisedBySetup8187("MOONSHOT", "LIVE").containsKey("MAINSTREAM_CRYPTO_SWING"))
        // A toolkit name with nothing else is unverifiable (lane economics only), never a strategy sample.
        val unv = CortexOutcomeRoute8144.owner8144("LIVE", "M1", "LIVE", "M1", "MOONSHOT", "DEGEN_MICRO_SNIPE", "NONE", "MOONSHOT", "NONE")
        assertFalse(unv.trainsStrategy)
        // A genuine playbook seal still owns it.
        val sealed = CortexOutcomeRoute8144.owner8144("LIVE", "M1", "LIVE", "M1", "MOONSHOT", "SOCIAL_LAUNCH;FAST_CROWD#O=MATCHED#V=1", null, "MOONSHOT", "")
        assertEquals(Attribution8144.SEALED_IDENTITY, sealed.attribution); assertEquals("SOCIAL_LAUNCH", sealed.setup)
    }

    @Test fun theCloseNoLongerFillsTheSealWithADeskSetup() {
        val store = EntryIdentityStore8136()
        val id = AateEntryIdentity8136(
            mode = "LIVE", mint = "M", positionId = "pos", candidateVersion = 1L, ownerLane = "MOONSHOT", exactSetup = "",
            strategyVariant = "", tactic = "MOMENTUM", entryStyle = "", exitPolicy = "", evidenceVersion = "rev1", decisionTimestampMs = 1L,
            scoreFinal = 60.0, pWin = 0.5, expectedPnlPct = 1.0, rugP = 0.1, contributors = emptyList(), shadow = false,
        )
        assertTrue(store.seal(id))
        store.fillBlanks("pos", "MAINSTREAM_CRYPTO_SWING", "v", "SWING", "TRAIL")
        assertEquals("", store.get("pos")?.exactSetup)
        assertEquals("TRAIL", store.get("pos")?.exitPolicy)
        val id2 = id.copy(positionId = "pos2")
        assertTrue(store.seal(id2))
        store.fillBlanks("pos2", "FIRST_PULLBACK", "v", "", "")
        assertEquals("FIRST_PULLBACK", store.get("pos2")?.exactSetup)               // a playbook name still fills
    }

    @Test fun wiring() {
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("LaneRealisedGovernor8295.governedCopy8295(exec, rr, laneRealised8295(lane))"))
        assertTrue(pb.contains("val runner = runnerTail8295(lane)"))
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("fun laneRealised8295(laneRaw: String): DoubleArray?"))
        assertTrue(cx.contains("it.substringAfter('|') !in fresh8295"))
        assertTrue(src("engine/cortex/CortexLabelHygiene8144.kt").contains("!LaneSetupAttribution8295.deskSetup8295(s)"))
    }
}
