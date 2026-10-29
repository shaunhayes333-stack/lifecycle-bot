package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import com.lifecyclebot.engine.truth.ExploreShadow8347
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LearnerAttribution8418
import com.lifecyclebot.engine.truth.ReturnMagnitude8419
import com.lifecyclebot.engine.truth.SpecialistMiner7972
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8419 — items 1 and 2 of the reviewers' learning audit: the specialist counters after 3,000 consecutive losses, and
 * the magnitude of a return classified (corrupt / unwitnessed extreme / train at its value), never clamped.
 */
class Aate8419ReturnMagnitudeTest {

    private val facts = listOf("mc=10_30K", "age=LT5M", "bp=GE70", "dev=WIN", "hv=1_5", "src=PUMP")

    private fun minerKey(lane: String): String = SpecialistMiner7972.combosForTest8419(lane, facts).first()

    @Test fun the_classifier_trains_the_observed_value_and_refuses_corrupt_arithmetic_by_name() {
        val five = ReturnMagnitude8419.classify8419(500.0, witnessed = true)
        val six = ReturnMagnitude8419.classify8419(600.0, witnessed = true)
        assertTrue(five.train && six.train)
        assertEquals(ReturnMagnitude8419.REASON_TRAIN, five.reason)
        assertEquals(500.0, five.value, 0.0); assertEquals(600.0, six.value, 0.0)
        assertEquals(1_000.0, ReturnMagnitude8419.PLAUSIBLE_UNWITNESSED_PCT_8419, 0.0)
        assertEquals(-100.0, ReturnMagnitude8419.TOTAL_LOSS_PCT_8419, 0.0)
        assertEquals(0.0001, ReturnMagnitude8419.MIN_PLAUSIBLE_BASIS_SOL_8419, 0.0)
        assertNotEquals(five.value, six.value, 0.0)
        // a witnessed 50x trains at its full value; the same move unwitnessed is observed only (not clamped to 1,000)
        assertEquals(5_000.0, ReturnMagnitude8419.classify8419(5_000.0, witnessed = true).value, 0.0)
        val unwitnessed = ReturnMagnitude8419.classify8419(5_000.0, witnessed = false)
        assertFalse(unwitnessed.train); assertEquals(ReturnMagnitude8419.REASON_UNWITNESSED_EXTREME, unwitnessed.reason)
        assertFalse(unwitnessed.corrupt)
        assertEquals(5_000.0, unwitnessed.value, 0.0)   // the value is reported, never reshaped
        // below a total loss and non-finite values are corrupt, whatever the witness
        val below = ReturnMagnitude8419.classify8419(-150.0, witnessed = true)
        assertFalse(below.train); assertTrue(below.corrupt); assertEquals(ReturnMagnitude8419.REASON_BELOW_TOTAL_LOSS, below.reason)
        assertTrue(ReturnMagnitude8419.classify8419(Double.NaN, witnessed = true).corrupt)
        assertEquals(ReturnMagnitude8419.REASON_NON_FINITE, ReturnMagnitude8419.classify8419(Double.POSITIVE_INFINITY, witnessed = true).reason)
        assertEquals(-100.0, ReturnMagnitude8419.classify8419(-100.0, witnessed = false).value, 0.0)
        assertTrue(ReturnMagnitude8419.classify8419(-100.0, witnessed = false).train)
        // the shadow books store the close as it happened; a basis break is a total loss by name
        assertEquals(5_000.0, ReturnMagnitude8419.bookedShadowValue8419(5_000.0), 0.0)
        assertEquals(-100.0, ReturnMagnitude8419.bookedShadowValue8419(-150.0), 0.0)
    }

    @Test fun a_unit_break_is_refused_as_corrupt_not_clamped() {
        // a real 0.05 SOL ticket that made 0.025 SOL (+50 %), read through a 1e6 decimals slip: fraction 5e5 (= +5e7 %)
        assertTrue(ReturnMagnitude8419.unitBreak8419(0.025, 500_000.0, 100_000.0))
        // a real +150,000 % on a 0.01 SOL ticket (15 SOL out) is a position, not a unit break
        assertFalse(ReturnMagnitude8419.unitBreak8419(15.0, 1_500.0, 100_000.0))
        // within the ceiling nothing is judged here
        assertFalse(ReturnMagnitude8419.unitBreak8419(0.000001, 5.0, 100_000.0))
    }

    private fun event(pnl: Double, fraction: Double, pct: Double, mode: String, quality: String) =
        CanonicalTradeFinalizedBus6450.Event(
            positionId = "p8419", mint = "mint8419", outcome = CanonicalTradeFinalizedBus6450.Outcome.WIN,
            netRealizedPnlSol = pnl, grossRealizedPnlSol = pnl, returnFraction = fraction,
            netReturnPct = pct, feesSol = 0.0, entryLane = "MOONSHOT", entryStrategyPid = "s",
            entryTactic = "MOMENTUM", exitReason = "TP", holdingTimeMs = 1000,
            dataQuality = quality, priceIntegrity = quality, mode = mode, settledAtMs = 1L,
        )

    @Test fun a_chain_finalized_win_above_the_ceiling_trains_an_unverified_one_is_quarantined_and_a_unit_break_is_corrupt() {
        val finalized = event(15.0, 1_500.0, 150_000.0, "LIVE", "confirmed_signature")
        assertEquals("LIVE_FINALIZED", finalized.economicFinality)
        assertTrue(CanonicalTradeFinalizedBus6450.chainFinalized8419(finalized.economicFinality))
        assertNull(CanonicalTradeFinalizedBus6450.economicInvalidReasonForTest6495(finalized))
        val paper = event(15.0, 1_500.0, 150_000.0, "PAPER", "canonical_paper_fill")
        assertEquals("RETURN_OUTSIDE_TRAINABLE_RANGE", CanonicalTradeFinalizedBus6450.economicInvalidReasonForTest6495(paper))
        val unverified = event(15.0, 1_500.0, 150_000.0, "LIVE", "estimated")
        assertEquals("RETURN_OUTSIDE_TRAINABLE_RANGE", CanonicalTradeFinalizedBus6450.economicInvalidReasonForTest6495(unverified))
        // the 1e6 unit break is corrupt by name even on a finalized row; it is never reshaped into a trainable value
        val broken = event(0.025, 500_000.0, 50_000_000.0, "LIVE", "confirmed_signature")
        assertEquals("RETURN_UNIT_BREAK_8419", CanonicalTradeFinalizedBus6450.economicInvalidReasonForTest6495(broken))
        // below a total loss is still outside the range for every row
        assertEquals("RETURN_OUTSIDE_TRAINABLE_RANGE", CanonicalTradeFinalizedBus6450.economicInvalidReasonForTest6495(
            event(-0.5, -1.5, -150.0, "LIVE", "confirmed_signature")))
        // +500 % and +600 % finalized closes are both valid, distinct values
        assertNull(CanonicalTradeFinalizedBus6450.economicInvalidReasonForTest6495(event(0.5, 5.0, 500.0, "LIVE", "confirmed_signature")))
        assertNull(CanonicalTradeFinalizedBus6450.economicInvalidReasonForTest6495(event(0.6, 6.0, 600.0, "LIVE", "confirmed_signature")))
    }

    @Test fun the_specialist_miner_books_plus_500_and_plus_600_as_different_records() {
        LearnerAttribution8418.resetForTest8418()
        SpecialistMiner7972.resetForTest7972()
        SpecialistMiner7972.onLabel7972("SHITCOIN", "M500", facts, 500.0, 520.0, 1_000L)
        SpecialistMiner7972.onLabel7972("MOONSHOT", "M600", facts, 600.0, 620.0, 1_000L)
        val five = requireNotNull(SpecialistMiner7972.statForTest8419(minerKey("SHITCOIN")))
        val six = requireNotNull(SpecialistMiner7972.statForTest8419(minerKey("MOONSHOT")))
        assertEquals(1.0, five[0], 0.0); assertEquals(500.0, five[1], 1e-9); assertEquals(1.0, five[3], 0.0)
        assertEquals(1.0, six[0], 0.0); assertEquals(600.0, six[1], 1e-9)
        assertNotEquals(five[1], six[1], 0.0)   // NET_CAP (300) made these the same record
        // a corrupt label (below a total loss) is refused by name, not booked at -100
        SpecialistMiner7972.onLabel7972("SHITCOIN", "MBAD", facts, -150.0, -150.0, 1_000L)
        assertEquals(1.0, requireNotNull(SpecialistMiner7972.statForTest8419(minerKey("SHITCOIN")))[0], 0.0)
        assertEquals(1L, SpecialistMiner7972.corruptRefused8419())
        SpecialistMiner7972.resetForTest7972()
    }

    @Test fun three_thousand_consecutive_losses_leave_every_counter_at_zero_wins_and_a_negative_mean() {
        // the specialist miner: a windowed record of the last ~300, no wins, mean -40, finite error
        LearnerAttribution8418.resetForTest8418()
        SpecialistMiner7972.resetForTest7972()
        repeat(3_000) { i -> SpecialistMiner7972.onLabel7972("SHITCOIN", "L$i", facts, -40.0, -40.0, 1_000L + i) }
        val s = requireNotNull(SpecialistMiner7972.statForTest8419(minerKey("SHITCOIN")))
        assertEquals(300.0, s[0], 0.0)
        assertEquals(0.0, s[3], 0.0)                       // wins
        assertEquals(0.0, s[4], 0.0)                       // runners
        assertEquals(-40.0, s[1] / s[0], 1e-6)             // mean
        assertTrue(SpecialistMiner7972.promotedCount8144() == 0)
        val status = SpecialistMiner7972.statusLine7972()
        assertTrue(status, status.contains("specialists=0"))
        assertFalse(status.contains("999"))
        SpecialistMiner7972.resetForTest7972()
        // the labeler tally
        val t = ForwardReturnLabeler7731.Tally()
        repeat(3_000) { t.n60 += 1; t.sum60 += -40.0; t.sumSq60 += 1_600.0; t.window8014(ForwardReturnLabeler7731.CELL_WINDOW_8014) }
        assertEquals(ForwardReturnLabeler7731.CELL_WINDOW_8014, t.n60); assertEquals(0, t.win60); assertEquals(0, t.runner60)
        assertEquals(-40.0, t.sum60 / t.n60, 1e-6)
        // the allocator node
        val a = CellAllocator7962.Stat7962()
        repeat(3_000) { a.add(-40.0, 1.0) }
        assertEquals(-40.0, a.sx / a.w, 1e-6)
        // the lane scorecard (canonical live closes): no wins, pf 0, negative expectancy, never 999
        val losses = List(3_000) { -0.004 }
        val lane = LiveEntrySafetyHold.LiveConfidenceStats.fold8280(losses)
        assertEquals(3_000, lane.canonicalN); assertEquals(0, lane.wins); assertEquals(3_000, lane.losses)
        assertEquals(0.0, lane.winRatePct, 0.0); assertEquals(0.0, lane.profitFactor, 0.0); assertTrue(lane.expectancySol < 0.0)
        assertTrue(PipelineHealthCollector.laneBleeding8280(lane))
        assertTrue(PipelineHealthCollector.laneCell8280(lane).startsWith("n=3000 wr=0% pf=0.00 exp=-0.0040"))
        // the Cortex shadow verdict stats
        val c = ExploreShadow8347.cortexStats8368(List(3_000) { -40.0 })
        assertEquals(0, c.wins); assertEquals(3_000, c.losses); assertEquals(0.0, c.profitFactor, 0.0); assertEquals(-40.0, c.meanPct, 1e-9)
    }

    @Test fun wins_without_losses_print_an_infinite_profit_factor_never_999() {
        val only = LiveEntrySafetyHold.LiveConfidenceStats.fold8280(listOf(0.01, 0.02))
        assertTrue(only.profitFactor.isInfinite())
        assertTrue(PipelineHealthCollector.laneCell8280(only).startsWith("n=2 wr=100% pf=inf exp=+0.0150"))
        assertEquals("inf", ReturnMagnitude8419.pfText8419(Double.POSITIVE_INFINITY))
        assertEquals("-", ReturnMagnitude8419.pfText8419(Double.NaN))
        assertEquals("1.25", ReturnMagnitude8419.pfText8419(1.25))
        assertTrue(ReturnMagnitude8419.profitFactor8419(1.0, 0.0).isInfinite())
        assertTrue(ReturnMagnitude8419.profitFactor8419(0.0, 0.0).isNaN())
        assertEquals(2.0, ReturnMagnitude8419.profitFactor8419(2.0, 1.0), 0.0)
        val hold = File("src/main/kotlin/com/lifecyclebot/engine/LiveEntrySafetyHold.kt").readText()
        assertFalse(hold.contains("else if (grossWinSol > 0.0) 999.0 else 0.0"))
    }

    @Test fun the_clamps_are_gone_from_the_miner_and_the_shadow_books_and_the_bus_classifies() {
        val miner = File("src/main/kotlin/com/lifecyclebot/engine/truth/SpecialistMiner7972.kt").readText()
        assertFalse(miner.contains("net.coerceIn(-100.0, NET_CAP)"))
        assertTrue(miner.contains("if (!ReturnMagnitude8419.classifyLabel8419(net).train) { corruptRefused8419.incrementAndGet(); return DoubleArray(0) }"))
        assertTrue(miner.contains("if (!ReturnMagnitude8419.classifyLabel8419(newNet).train) { corruptRefused8419.incrementAndGet(); return }"))
        val evidence = File("src/main/kotlin/com/lifecyclebot/engine/truth/EvidenceShadow8344.kt").readText()
        assertFalse(evidence.contains("net.coerceIn(-100.0, 1_000.0)"))
        assertTrue(evidence.contains("ReturnMagnitude8419.bookedShadowValue8419(net)"))
        val explore = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExploreShadow8347.kt").readText()
        assertFalse(explore.contains("it[1] = net.coerceIn(-100.0, 1_000.0)"))
        assertTrue(explore.contains("it[1] = ReturnMagnitude8419.bookedShadowValue8419(net)"))
        val bus = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalTradeFinalizedBus6450.kt").readText()
        assertTrue(bus.contains("if (event.netReturnPct > ceiling8419 && !chainFinalized8419(event.economicFinality)) return \"RETURN_OUTSIDE_TRAINABLE_RANGE\""))
        assertTrue(bus.contains("if (ReturnMagnitude8419.unitBreak8419(event.netRealizedPnlSol, event.returnFraction, ceiling8419)) return \"RETURN_UNIT_BREAK_8419\""))
    }
}
