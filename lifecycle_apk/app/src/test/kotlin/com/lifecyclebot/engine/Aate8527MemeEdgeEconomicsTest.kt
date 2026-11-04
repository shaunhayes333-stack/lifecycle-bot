package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope
import com.lifecyclebot.v3.scoring.MemeEdgeAI
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8527MemeEdgeEconomicsTest {
    private val now = 1_800_000_000_000L
    private fun row(id: Int, returnPct: Double, mode: String = "LIVE", lane: String = "MOONSHOT") = Envelope(
        tradeId = mode + "-trade-" + id, positionId = mode + "-position-" + id,
        atMs = now - id, realizedReturnPct = returnPct, realizedPnlSol = returnPct / 1_000.0,
        mint = "mint-" + id, lane = lane, mode = mode,
        proofState = if (mode == "PAPER") "PAPER_SIMULATED" else "LIVE_FINALIZED",
    )
    private fun evidence(rows: List<Envelope>, mode: String = "LIVE") =
        MemeEdgeAI.economicEvidence8527(MemeEdgeAI.Layer.MOONSHOT, mode, rows, now)
    private fun size(rows: List<Envelope>, mode: String = "LIVE"): Double {
        val e = MemeEdgeAI.economicShaping8527(evidence(rows, mode))
        return e.sizeMultiplier * e.streakMultiplier
    }

    @Test fun HighWinRateAndAWinningStreakCannotUpsizeLosingEconomics() {
        val rows = (1..9).map { row(it, 1.0) } + row(10, -50.0)
        val e = evidence(rows)!!
        assertEquals(90.0, e.winRatePct, 0.0)
        assertTrue(e.streak >= 4)
        assertTrue(e.meanReturnPct < 0.0 && e.netSol < 0.0)
        assertTrue(size(rows) < 1.0)
        assertEquals(1.0, MemeEdgeAI.economicShaping8527(e).streakMultiplier, 0.0)
    }

    @Test fun LowWinRateAsymmetricRunnerIsNotShrunkByItsLosingStreak() {
        val rows = (1..9).map { row(it, -10.0) } + row(10, 600.0)
        val e = evidence(rows)!!
        assertEquals(10.0, e.winRatePct, 0.0)
        assertTrue(e.streak <= -4)
        assertTrue(e.meanReturnPct > 0.0 && e.netSol > 0.0)
        assertTrue(size(rows) >= 1.0)
        assertEquals(1.0, MemeEdgeAI.economicShaping8527(e).streakMultiplier, 0.0)
    }

    @Test fun LiveCannotBorrowPaperOrAnotherLaneAndUnknownModeIsNeutral() {
        val live = (1..10).map { row(it, -10.0) }
        val paper = (1..20).map { row(it, 20.0, "PAPER") }
        val otherLane = (21..40).map { row(it, 100.0, lane = "CRYPTO_MOONSHOT") }
        val mixed = live + paper + otherLane
        assertEquals(10, evidence(mixed, "LIVE")!!.n)
        assertEquals(20, evidence(mixed, "PAPER")!!.n)
        assertTrue(size(mixed, "LIVE") < 1.0)
        assertTrue(size(mixed, "PAPER") > 1.0)
        assertEquals(1.0, size(mixed, "UNKNOWN"), 0.0)
        assertEquals(1.0, size(mixed, "SHADOW"), 0.0)
    }

    @Test fun PartialUnprovenIneligibleInvalidAndFutureRowsCannotCreateEvidence() {
        val valid = row(1, 10.0)
        val invalid = listOf(
            row(2, 100.0).copy(terminal = false),
            row(3, 100.0).copy(proofState = "LIVE_BROADCAST"),
            row(4, 100.0).copy(learningEligible = false),
            row(5, Double.NaN),
            row(6, 10.0).copy(realizedPnlSol = -1.0),
            row(7, 10.0).copy(atMs = now + 1L),
            row(8, 10.0).copy(positionId = ""),
        )
        assertEquals(1, evidence(listOf(valid) + invalid)!!.n)
        assertEquals(1.0, size(listOf(valid) + invalid), 0.0)
        assertNull(evidence(invalid))
    }

    @Test fun UniqueWholePositionsAreCappedAtLatestFifty() {
        val rows = (1..60).map { row(it, it.toDouble()) }
        val duplicate = row(100, 500.0).copy(positionId = rows.first().positionId, atMs = now - 100L)
        val e = evidence(rows + duplicate)!!
        assertEquals(50, e.n)
        assertEquals(25.5, e.meanReturnPct, 0.000001)
    }

    @Test fun EconomicSignConflictAndScratchAreNeutralAndMinimumSampleIsPreserved() {
        // Percentage mean is positive but larger losing principal makes wallet net negative.
        val mixedSize = listOf(row(1, 100.0).copy(realizedPnlSol = 0.001)) +
            (2..6).map { row(it, -1.0).copy(realizedPnlSol = -0.01) }
        assertEquals(1.0, size(mixedSize), 0.0)
        assertEquals(1.0, size((1..10).map { row(it, 0.0) }), 0.0)
        assertEquals(1.0, size((1..4).map { row(it, 100.0) }), 0.0)
        assertEquals(1.0, size((1..4).map { row(it, -90.0) }), 0.0)
    }

    @Test fun BetterWinRateCannotReverseSizingInsideTheSamePositiveEconomicCohort() {
        val multipliers = listOf(35.0, 40.0, 45.0, 50.0, 55.0, 60.0, 70.0, 90.0).map { wr ->
            MemeEdgeAI.economicShaping8527(MemeEdgeAI.EconomicEvidence8527(20, 5.0, 0.1, wr, 0)).sizeMultiplier
        }
        assertTrue(multipliers.zipWithNext().all { (before, after) -> after >= before })
        assertTrue(multipliers.all { it in 1.0..1.15 })
    }

    @Test fun InvalidEvidenceIsNeutralAndZeroNetBreaksTheStreak() {
        for (e in listOf(
            MemeEdgeAI.EconomicEvidence8527(20, Double.NaN, 1.0, 50.0, 4),
            MemeEdgeAI.EconomicEvidence8527(20, 1.0, Double.POSITIVE_INFINITY, 50.0, 4),
            MemeEdgeAI.EconomicEvidence8527(20, 1.0, 1.0, 101.0, 4),
            MemeEdgeAI.EconomicEvidence8527(51, 1.0, 1.0, 50.0, 4),
        )) assertEquals(MemeEdgeAI.EconomicShaping8527(), MemeEdgeAI.economicShaping8527(e))
        val rows = listOf(row(1, -0.0)) + (2..6).map { row(it, -10.0) }
        assertEquals(0, evidence(rows)!!.streak)
    }
    @Test fun BothActiveLaneCallersPassObservedInputsAndExplicitExecutionMode() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val marker = "MemeEdgeAI.evaluate("
        val blocks = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val at = source.indexOf(marker, cursor)
            if (at < 0) break
            var depth = 1
            var end = at + marker.length
            var quoted = false
            var escaped = false
            while (end < source.length && depth > 0) {
                val c = source[end++]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') quoted = false
                } else when (c) {
                    '"' -> quoted = true
                    '(' -> depth++
                    ')' -> depth--
                }
            }
            assertEquals("balanced active MemeEdge call", 0, depth)
            blocks.add(source.substring(at, end))
            cursor = end
        }
        assertEquals(2, blocks.size)
        for (block in blocks) {
            assertTrue(block.contains("ts.tokenMap.volume24hUsd"))
            assertTrue(block.contains("LaneInputTruth8236.buyPressure8491"))
            assertTrue(block.contains("executionMode = if (cfg.paperMode)"))
            assertTrue(block.contains("Double.NaN"))
            assertFalse(block.contains("lastLiquidityUsd * 0.05"))
            assertFalse(block.contains("RuntimeModeAuthority.isLive()) 50.0 else 20.0"))
        }
    }

}
