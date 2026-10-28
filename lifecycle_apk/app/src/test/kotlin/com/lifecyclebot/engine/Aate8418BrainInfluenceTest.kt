package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexExit7897
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexTiming7900
import com.lifecyclebot.engine.cortex.LaneBrainVotes8366
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323
import com.lifecyclebot.engine.learning.TacticSwitcher
import com.lifecyclebot.engine.truth.SpecialistMiner7972
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8418 item 16 — counterfactual proof that each brain/specialist family changes the decision it claims to change.
 * Every test holds the candidate fixed, flips ONE input/state of ONE brain, and asserts the downstream decision output
 * (bucket / admit / size multiple / wait / exit / promotion) moves as designed. Results are tabulated in
 * lifecycle_apk/BRAIN_INFLUENCE_8418.md. Families that only receive data are pinned at their consuming call site.
 */
class Aate8418BrainInfluenceTest {

    // ── Cortex voters → fused edge → verdict bucket (admit / refuse) ────────────────────────────────────────────────

    private fun trainedLedger8418(lane: String, voter: String, grades: Int): CortexLedger7885 {
        val ledger = CortexLedger7885()
        val edges = doubleArrayOf(0.0)
        for (i in 0 until grades) {
            val raw = if (i % 2 == 0) 1.0 else -1.0
            val y = if (raw > 0.0) 12.0 else -12.0
            ledger.grade(lane, listOf(voter), listOf(edges), doubleArrayOf(raw), y, y)
        }
        return ledger
    }

    private fun fusedEdge8418(ledger: CortexLedger7885, lane: String, voter: String, raw: Double): Double =
        ledger.fuse(lane, listOf(CortexLedger7885.Vote(voter, doubleArrayOf(0.0), raw, setOf(voter)))).edgePct

    @Test fun cortexSeatedVoterFlipsTheVerdictBucketForTheSameCandidate8418() {
        val lane = "QUALITY"; val voter = "PROOF8418_SEATED"
        val ledger = trainedLedger8418(lane, voter, 300)
        val seat = ledger.seats["$voter|$lane"]!!
        assertTrue("voter must hold earned authority (scored=${seat.scored})", seat.authority() > 0.0)
        val bullish = fusedEdge8418(ledger, lane, voter, 1.0)
        val bearish = fusedEdge8418(ledger, lane, voter, -1.0)
        assertTrue("bullish read must lift the fused edge above the lane prior: $bullish", bullish > 3.0)
        assertTrue("bearish read must sink the fused edge: $bearish", bearish < -2.0)
        assertEquals(CortexScoreboard7885.Bucket.STRONG, CortexScoreboard7885.bucketOf(bullish, 0.0, false))
        assertEquals(CortexScoreboard7885.Bucket.REFUSE, CortexScoreboard7885.bucketOf(bearish, 0.0, false))
    }

    @Test fun cortexUnseatedVoterReceivesDataButCannotMoveTheFusion8418() {
        val lane = "QUALITY"; val voter = "PROOF8418_UNSEATED"
        val ledger = trainedLedger8418(lane, voter, 30)   // below MIN_SCORED: no authority yet
        assertEquals(0.0, ledger.seats["$voter|$lane"]!!.authority(), 0.0)
        assertEquals(fusedEdge8418(ledger, lane, voter, 1.0), fusedEdge8418(ledger, lane, voter, -1.0), 1e-12)
    }

    // ── Cortex conviction / allocator / specialist → order size ─────────────────────────────────────────────────

    @Test fun cortexConvictionAuthorityChangesTheSizeMultiple8418() {
        val requested = 0.1
        val stake = Cortex7885.kellyStakeSol(meanPct = 10.0, variancePct2 = 400.0, equitySol = 10.0)
        assertEquals(6.25, stake, 1e-9)
        assertEquals(0.0, Cortex7885.kellyStakeSol(meanPct = -10.0, variancePct2 = 400.0, equitySol = 10.0), 0.0)
        assertEquals("no authority: the request stands", 1.0, Cortex7885.graduatedMult7955(0.0, stake, requested), 0.0)
        assertEquals("half authority: half way to the capped Kelly multiple", 1.75, Cortex7885.graduatedMult7955(0.5, stake, requested), 1e-9)
        assertEquals("full authority: capped Kelly multiple", 2.5, Cortex7885.graduatedMult7955(1.0, stake, requested), 1e-9)
    }

    private fun cell8418(n: Int, value: Double): CellAllocator7962.Stat7962 =
        CellAllocator7962.Stat7962().also { s -> repeat(n) { s.add(value, 1.0) } }

    @Test fun cellAllocatorPosteriorFlipsSizeAndTheCombinedChain8418() {
        val winning = CellAllocator7962.posterior7962(cell8418(60, 15.0), 0.0, 2.0, 900.0)
        val losing = CellAllocator7962.posterior7962(cell8418(60, -15.0), 0.0, 2.0, 900.0)
        val up = CellAllocator7962.kellySizeMult7962(winning, 0.1)
        val down = CellAllocator7962.kellySizeMult7962(losing, 0.1)
        assertTrue("proven winning cell sizes up: $up", up > 1.0)
        assertTrue("proven losing cell sizes down: $down", down < 1.0)
        // The sizing chain TraderSizingBridge6444 multiplies: Cortex conviction x allocator, under the one cap.
        assertNotEquals(CellAllocator7962.combinedMult7962(1.5, up), CellAllocator7962.combinedMult7962(1.5, down), 1e-9)
        assertEquals(CellAllocator7962.MAX_MULT_7962, CellAllocator7962.combinedMult7962(2.0, up), 1e-9)
    }

    @Test fun cellAllocatorFlipsLastSlotAdmissionAndEarlyRotationExit8418() {
        assertTrue(CellAllocator7962.lastSlotRefusal7962(0.5, 2.0, null, 1))
        assertFalse("a better candidate keeps the slot", CellAllocator7962.lastSlotRefusal7962(2.5, 2.0, null, 1))
        assertFalse("two free slots: nothing is refused", CellAllocator7962.lastSlotRefusal7962(0.5, 2.0, null, 2))
        val age = CellAllocator7962.MIN_ROTATE_AGE_MS_7962 + 60_000L
        assertTrue("losing held cell rotates out", CellAllocator7962.earlyRotation7962(-2.0, -0.4, 1.0, age))
        assertFalse("paying held cell is kept", CellAllocator7962.earlyRotation7962(5.0, 0.9, 1.0, age))
        assertFalse("too young to rotate", CellAllocator7962.earlyRotation7962(-2.0, -0.4, 1.0, 60_000L))
    }

    @Test fun specialistMinerFloorChangesSizeAndPromotionChangesAdmission8418() {
        assertEquals(1.25, SpecialistMiner7972.edgeMult7974(25.0), 1e-9)
        assertEquals(1.0, SpecialistMiner7972.edgeMult7974(-5.0), 0.0)
        assertTrue(SpecialistMiner7972.promotes7972(30, 30.0, 5.0, 15))
        assertFalse("same record, wider SE: not proven", SpecialistMiner7972.promotes7972(30, 30.0, 10.0, 15))
        assertTrue(SpecialistMiner7972.demotes7972(10, -5.0, 2.0))
        assertFalse(SpecialistMiner7972.demotes7972(10, 5.0, 2.0))
    }

    // ── Timing cortex → entry wait;  Exit cortex → hold / sell ─────────────────────────────────────────────────

    private fun stat8418(n: Int, mean: Double): CortexLedger7885.Stat =
        CortexLedger7885.Stat().also { s -> repeat(n) { s.add(mean + if (it % 2 == 0) 1.0 else -1.0, false) } }

    @Test fun timingCortexDipRecordFlipsTheEntryWait8418() {
        assertTrue(CortexTiming7900.waitProven(stat8418(40, -6.0)))
        assertFalse("a shallow dip record does not defer", CortexTiming7900.waitProven(stat8418(40, -1.0)))
        assertFalse("thin record does not defer", CortexTiming7900.waitProven(stat8418(20, -6.0)))
    }

    @Test fun exitCortexForwardReadAndRecordFlipHoldAndSell8418() {
        assertEquals(CortexExit7897.Bucket.HOLD_STRONG, CortexExit7897.bucketOf(5.0))
        assertEquals(CortexExit7897.Bucket.SELL_STRONG, CortexExit7897.bucketOf(-5.0))
        assertEquals(CortexExit7897.Bucket.NEUTRAL, CortexExit7897.bucketOf(0.0))
        assertTrue(CortexExit7897.holdProven(stat8418(40, 6.0), 10))
        assertFalse("too few positions: no hold authority", CortexExit7897.holdProven(stat8418(40, 6.0), 5))
        assertTrue(CortexExit7897.sellProven(stat8418(40, -6.0), 10))
        assertFalse(CortexExit7897.sellProven(stat8418(40, 6.0), 10))
    }

    // ── TacticSwitcher, ExpertWallets, lane-brain votes, cheat sheet ───────────────────────────────────────────

    @Test fun tacticSwitcherLosingRecordFlipsTheLosingTacticRefusal8418() {
        assertTrue(TacticSwitcher.tacticLosing8094(12, 2, -10.0))
        assertFalse("winning record", TacticSwitcher.tacticLosing8094(12, 8, -1.0))
        assertFalse("positive mean", TacticSwitcher.tacticLosing8094(12, 2, 1.0))
        assertFalse("below the losing bar's n", TacticSwitcher.tacticLosing8094(11, 0, -10.0))
    }

    @Test fun expertWalletRecordFlipsSourcePromotion8418() {
        assertTrue(ExpertWallets7962.labeledPromotes7962(30, 0.30, 0.20, 2.0))
        assertFalse("profit factor below bar", ExpertWallets7962.labeledPromotes7962(30, 0.30, 0.20, 1.2))
        assertFalse("mean inside the label-to-live gap", ExpertWallets7962.labeledPromotes7962(30, 0.05, 0.20, 2.0))
    }

    @Test fun laneBrainVotesOnlyInfluenceWhileFresh8418() {
        val ttl = LaneBrainVotes8366.TTL_FLOOR_MS_8366
        assertTrue(LaneBrainVotes8366.accepts8366(1_000L, 61_000L, ttl))
        assertFalse("stale stamp is dropped", LaneBrainVotes8366.accepts8366(1_000L, 1_000L + ttl + 1L, ttl))
        assertFalse("future stamp is refused", LaneBrainVotes8366.accepts8366(5_000L, 1_000L, ttl))
        assertFalse("unstamped", LaneBrainVotes8366.accepts8366(0L, 1_000L, ttl))
    }

    @Test fun cheatSheetMandateFlipsDiscoveryRouting8418() {
        val min = LaneCheatSheet8323.mcapMin8323("QUALITY")
        assertTrue(min > 0.0)
        assertTrue(LaneCheatSheet8323.laneFits8323("QUALITY", 200_000.0, 30_000.0))
        assertFalse("below the lane's cap band", LaneCheatSheet8323.laneFits8323("QUALITY", min / 100.0, 30_000.0))
        assertTrue("unknown cap never refuses", LaneCheatSheet8323.laneFits8323("QUALITY", Double.NaN, Double.NaN))
    }

    // ── Families that receive data but whose claimed decision hook has no caller (pinned at source) ───────────────

    private fun src8418(rel: String): String = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun scannerSourceBrainSkipIntakeHasNoCaller8418() {
        val root = File("src/main/kotlin/com/lifecyclebot")
        val callers = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name != "ScannerSourceBrain.kt" }
            .filter { f -> f.readText().contains("ScannerSourceBrain.shouldSkipIntake(") }.map { it.name }.toList()
        assertTrue("ScannerSourceBrain.shouldSkipIntake is not wired to intake (found: $callers)", callers.isEmpty())
        // the multiplier IS wired into the scanner score
        assertTrue(src8418("engine/SolanaMarketScanner.kt").contains("ScannerSourceBrain.intakeMultiplier(token.source.name)"))
    }

    @Test fun sizingChainCarriesEveryClaimedSizeBrain8418() {
        val bridge = src8418("engine/truth/TraderSizingBridge6444.kt")
        for (brain in listOf("FirstSight8026.sizeMult8026(", "RunnerPlay8018.sizeMult8018(", "TailHunter7996.sizeMult7996(",
            "RunnerGrab7967.sizeMult7967(", "SpecialistMiner7972.sizeMult7974(", "CellAllocator7962.combinedSizeMult7962(",
            "Cortex7885.convictionMult(")) assertTrue("size chain must carry $brain", bridge.contains(brain))
    }
}
