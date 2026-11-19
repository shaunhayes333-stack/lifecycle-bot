package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.LearnerColumns8798
import com.lifecyclebot.engine.truth.NetworkTier8873
import com.lifecyclebot.engine.truth.NetworkTier8873.Tier8873
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8873 — owner, 20 Nov: "there are 60,000% profit opportunities out there. literally everyday" / "fuck 4x. I want it
 * to look far far beyond that" / "the meme trader isnt a pumpfun trader exclusively is a solana network trader". The runner
 * ladder no longer sells most of the bag at 10x, the replay grades the live ladder itself, and every labelled decision is
 * filed by network tier, venue and mint origin.
 */
class Aate8873TailLadderAndNetworkTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theLadderKeepsTheStakeBy5xAndRidesPast10x() {
        val t = RunnerGrab7967.TIERS_7967
        assertEquals(listOf(100.0, 400.0, 900.0, 2_900.0, 9_900.0, 29_900.0), t.map { it.first })
        assertEquals("the 2x and 5x rungs are the 8644 rungs", listOf(0.25, 0.18), t.take(2).map { it.second })
        val stakeBackAt5x = (0.25 * 2.0 + 0.75 * 0.18 * 5.0) * 0.95
        assertTrue("the stake is back by 5x after 5 % slip: $stakeBackAt5x", stakeBackAt5x >= 1.0)
        var left = 1.0
        val leftAfter = t.map { left *= 1.0 - it.second; left }
        assertEquals("46 % of the original rides past 10x (was 20 %)", 0.46125, leftAfter[2], 1e-9)
        assertTrue("the last rung leaves the 20 % residual reserve intact", leftAfter.last() >= RunnerExitContract8136.RUNNER_RESIDUAL_SHARE_8136)
        // a 100x run that then collapses: rungs at 2x / 5x / 10x / 30x / 100x and the 45 % trail sells the rest near 55x
        val hundred = SpikeCapture7943.realisableGrossPct(9_900.0, (RunnerDoctrine8584.line8584(100.0) - 1.0) * 100.0, t)
        val before = SpikeCapture7943.realisableGrossPct(9_900.0, (RunnerDoctrine8584.line8584(100.0) - 1.0) * 100.0,
            listOf(100.0 to 0.25, 400.0 to 0.18, 900.0 to 0.67))
        assertTrue("a 100x banks far more of the move: $hundred vs $before", hundred > before + 1_000.0)
        val tenX = SpikeCapture7943.realisableGrossPct(900.0, 450.0, t)
        assertEquals("the price: a run that stops at exactly 10x banks +411 % (was +515 %)", 411.375, tenX, 1e-6)
    }

    @Test fun everyConsumerReadsTheOneLadder() {
        val spike = src("engine/SpikeCapture7943.kt")
        assertTrue("a chain-confirmed 30x+ slice confirms its tier",
            spike.contains("if (tier !in 1..maxOf(TIERS.size, RunnerGrab7967.TIERS_7967.size)) return"))
        assertTrue("the rung book accepts six tiers", RunnerLive8153.rungOf8153("SPIKE_CAPTURE_7943_T6_29950PCT") == 6)
        assertEquals(4, SpikeCapture7943.tierReached(4_900.0, RunnerGrab7967.TIERS_7967))
        assertEquals("a gap from 10x straight to 120x sells the two crossed rungs compounded", 1.0 - 0.75 * 0.75,
            SpikeCapture7943.crossedTierFraction8079(RunnerGrab7967.TIERS_7967, 3, 5), 1e-12)
        val line = RunnerDoctrine8584.statusLine8584()
        assertTrue(line, line.contains("ladder=+100%:25/+400%:18/+900%:25/+2900%:25/+9900%:25/+29900%:22-of-remainder (8873)"))
        // the launch replay's DOCTRINE is the live ladder as shares of the original
        val rungs = LaunchReplay8744.doctrineRungs8873()
        assertEquals(listOf(2.0, 5.0, 10.0, 30.0, 100.0, 300.0), rungs.map { it.first })
        assertEquals(0.135, rungs[1].second, 1e-12)
        assertEquals(1.0 - 0.2023734375, rungs.sumOf { it.second }, 1e-9)
        assertTrue(src("engine/truth/LaunchReplay8744.kt").contains("Ladder.DOCTRINE -> doctrineRungs8873()"))
    }

    @Test fun tiersNameWhereOnTheNetworkACoinTrades() {
        assertEquals(Tier8873.FRESH, NetworkTier8873.tierOf8873(12.0, 8_000.0))
        assertEquals(Tier8873.YOUNG, NetworkTier8873.tierOf8873(300.0, 40_000.0))
        assertEquals(Tier8873.OLDER_SMALL, NetworkTier8873.tierOf8873(3_000.0, 40_000.0))
        assertEquals(Tier8873.ESTABLISHED, NetworkTier8873.tierOf8873(3_000.0, 250_000.0))
        assertEquals("$1M+ is established at any age", Tier8873.ESTABLISHED, NetworkTier8873.tierOf8873(5.0, 2_000_000.0))
        assertEquals(Tier8873.UNKNOWN, NetworkTier8873.tierOf8873(Double.NaN, 50_000.0))
        assertEquals("RAYDIUM", NetworkTier8873.venueName8873(3.0))
        assertEquals("UNREAD", NetworkTier8873.venueName8873(Double.NaN))
    }

    @Test fun theReportCountsAttentionAndOutcomeByTierVenueAndOrigin() {
        NetworkTier8873.resetForTest8873()
        NetworkTier8873.note8873("Apump", 10.0, 9_000.0, 0.0, -20.0, filled = true)
        NetworkTier8873.note8873("Bpump", 20.0, 9_000.0, 0.0, Double.NaN, filled = false)
        NetworkTier8873.note8873("Cxyz", 4_000.0, 400_000.0, 3.0, 30.0, filled = true)
        val line = NetworkTier8873.statusLine8873()
        assertTrue(line, line.contains("FRESH=66%(n2 filled1 -20.0%)"))
        assertTrue(line, line.contains("ESTABLISHED=33%(n1 filled1 +30.0%)"))
        assertTrue(line, line.contains("PUMP_CURVE=66%(n2 filled1 -20.0%)") && line.contains("RAYDIUM=33%(n1 filled1 +30.0%)"))
        assertTrue(line, line.contains("PUMP_MINT=66%") && line.contains("OTHER_MINT=33%"))
        NetworkTier8873.resetForTest8873()
        val edge = src("engine/truth/EdgeModel8537.kt").substringAfter("private fun appendDataset(").substringBefore("io.execute {")
        assertTrue(edge.contains("NetworkTier8873.note8873(s.mint, s.raw.ageMin, s.raw.mcap, LearnerColumns8798.valueOf8873(s.raw.ext, \"venue\"), net, filled)"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("NetworkTier8873.statusLine8873()"))
    }

    @Test fun theVenueIsReadFromTheChosenPoolWhenTheScannerStampedTheChain() {
        assertTrue("nothing read: unknown", LearnerColumns8798.venueOf("Xmint", "", "", "", false).isNaN())
        assertEquals("a scanner's chain stamp alone stays the 8798 'other' code", 6.0,
            LearnerColumns8798.venueOf("Xmint", "", "solana", "", false), 0.0)
        assertEquals(3.0, LearnerColumns8798.venueOf("Xmint", "", "solana", "", false, poolDex = "raydium"), 0.0)
        assertEquals(4.0, LearnerColumns8798.venueOf("Xmint", "", "solana", "", false, poolDex = "meteora"), 0.0)
        assertEquals(1.0, LearnerColumns8798.venueOf("Xpump", "", "solana", "", false, poolDex = "pumpswap"), 0.0)
        assertEquals("a pump mint on its curve", 0.0, LearnerColumns8798.venueOf("Xpump", "", "solana", "", false, poolDex = "pumpfun"), 0.0)
        assertEquals("a bonk mint with nothing read is LaunchLab-born", 2.0, LearnerColumns8798.venueOf("Xbonk", "", "", "", false), 0.0)
        val x = DoubleArray(LearnerColumns8798.SIZE) { Double.NaN }
        assertTrue(LearnerColumns8798.valueOf8873(x, "venue").isNaN())
        assertTrue(LearnerColumns8798.valueOf8873(DoubleArray(0), "venue").isNaN())
        assertTrue(src("engine/truth/LearnerColumns8798.kt").contains("poolDex = com.lifecyclebot.network.DexPools8285.dataPool8285(mint)?.dexId.orEmpty()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 62. The runner ladder rides past 10x, and the whole network is measured (8873)"))
    }
}
