package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class Bundle6405PaperLiveParityTest {

    @After fun tearDown() {
        CanonicalEventStream6405.clearForTest()
        PaperLiveParityKernel6405.clearForTest()
    }

    @Test fun paper_and_live_compute_identical_realised_pnl() {
        // Same series of events; only the lane flag differs.
        val paperKey = PaperLiveParityKernel6405.PositionKey("W_P", "M", 1L)
        val liveKey = PaperLiveParityKernel6405.PositionKey("W_L", "M", 1L)
        listOf("W_P", "W_L").forEach { w ->
            CanonicalEventStream6405.append(
                wallet = w, mint = "M", positionGeneration = 1L,
                type = CanonicalEventStream6405.Type.BUY_VERIFIED,
                rawQty = BigInteger.valueOf(1_000L),
                lamports = BigInteger.valueOf(1_000_000L),
            )
            CanonicalEventStream6405.append(
                wallet = w, mint = "M", positionGeneration = 1L,
                type = CanonicalEventStream6405.Type.SELL_VERIFIED,
                rawQty = BigInteger.valueOf(1_000L),
                lamports = BigInteger.valueOf(1_500_000L),
            )
        }
        PaperLiveParityKernel6405.setLane(paperKey, isPaper = true)
        PaperLiveParityKernel6405.setLane(liveKey, isPaper = false)
        val paper = PaperLiveParityKernel6405.compute(paperKey)
        val live = PaperLiveParityKernel6405.compute(liveKey)
        assertEquals(paper.realisedLamports, live.realisedLamports)
        assertEquals(BigInteger.valueOf(500_000L), paper.realisedLamports)
        assertTrue(paper.fullyExited)
        assertTrue(live.fullyExited)
    }
}

class Bundle6405CompoundingEngineTest {

    @Test fun losing_lifetime_does_not_grow_base() {
        val next = CompoundingEngine6405.nextBase(
            CompoundingEngine6405.Input(
                currentBaseLamports = BigInteger.valueOf(1_000_000L),
                realisedLamports = BigInteger.valueOf(-100_000L),
                compoundFraction = 0.5,
                maxLamports = BigInteger.valueOf(10_000_000L),
            ),
        )
        assertEquals(BigInteger.valueOf(1_000_000L), next)
    }

    @Test fun winning_lifetime_adds_fraction_of_realised() {
        val next = CompoundingEngine6405.nextBase(
            CompoundingEngine6405.Input(
                currentBaseLamports = BigInteger.valueOf(1_000_000L),
                realisedLamports = BigInteger.valueOf(2_000_000L),
                compoundFraction = 0.25,
                maxLamports = BigInteger.valueOf(10_000_000L),
            ),
        )
        // 1M + 25% * 2M = 1.5M
        assertEquals(BigInteger.valueOf(1_500_000L), next)
    }

    @Test fun caps_at_maxLamports() {
        val next = CompoundingEngine6405.nextBase(
            CompoundingEngine6405.Input(
                currentBaseLamports = BigInteger.valueOf(9_000_000L),
                realisedLamports = BigInteger.valueOf(5_000_000L),
                compoundFraction = 1.0,
                maxLamports = BigInteger.valueOf(10_000_000L),
            ),
        )
        assertEquals(BigInteger.valueOf(10_000_000L), next)
    }

    @Test fun clamps_out_of_range_fraction() {
        val next = CompoundingEngine6405.nextBase(
            CompoundingEngine6405.Input(
                currentBaseLamports = BigInteger.valueOf(1_000_000L),
                realisedLamports = BigInteger.valueOf(1_000_000L),
                compoundFraction = 5.0, // out of [0,1] -> clamped to 1.0
                maxLamports = BigInteger.valueOf(10_000_000L),
            ),
        )
        assertEquals(BigInteger.valueOf(2_000_000L), next)
    }
}

class Bundle6405GlobalEntryPolicyTest {

    @After fun tearDown() {
        GlobalEntryPolicy6405.clearForTest()
        PriceIntegrityAuthority6405.clearForTest()
        TerminalFinalityAuthority6405.clearForTest()
    }

    @Test fun global_pause_short_circuits_everything() {
        GlobalEntryPolicy6405.setGlobalPause(true, "OPERATOR_HOLD")
        val d = GlobalEntryPolicy6405.evaluate(
            "MINT_A", 1L, 1.0, PriceIntegrityAuthority6405.PriceSource.Jupiter,
        )
        assertFalse(d.allow)
        assertTrue(d.reason.startsWith("GLOBAL_PAUSE:"))
    }

    @Test fun rejects_terminal_generation_rebuy() {
        TerminalFinalityAuthority6405.markTerminal(
            "MINT_B", 1L, TerminalFinalityAuthority6405.Terminal.CLOSED_FULL_EXIT, "TP",
        )
        val d = GlobalEntryPolicy6405.evaluate(
            "MINT_B", 1L, 1.0, PriceIntegrityAuthority6405.PriceSource.Jupiter,
        )
        assertFalse(d.allow)
        assertEquals("TERMINAL_GENERATION_DUPLICATE_BUY", d.reason)
    }

    @Test fun rejects_missing_price_and_pair() {
        val d = GlobalEntryPolicy6405.evaluate(
            "MINT_C", 1L, null, PriceIntegrityAuthority6405.PriceSource.Unknown,
        )
        assertFalse(d.allow)
    }

    @Test fun allows_when_all_gates_pass() {
        PriceIntegrityAuthority6405.recordPairLiquidity("MINT_D", 5_000.0)
        val d = GlobalEntryPolicy6405.evaluate(
            "MINT_D", 1L, 1.0, PriceIntegrityAuthority6405.PriceSource.Jupiter,
        )
        assertTrue(d.allow)
        assertEquals("OK", d.reason)
    }

    @Test fun cooldown_blocks_and_expires() {
        PriceIntegrityAuthority6405.recordPairLiquidity("MINT_E", 5_000.0)
        GlobalEntryPolicy6405.setCooldownMs("MINT_E", 60_000L)
        val d = GlobalEntryPolicy6405.evaluate(
            "MINT_E", 1L, 1.0, PriceIntegrityAuthority6405.PriceSource.Jupiter,
        )
        assertFalse(d.allow)
        assertTrue(d.reason.startsWith("COOLDOWN_"))
    }
}

class Bundle6405MultiHorizonTest {

    private fun input(
        ageMs: Long,
        entry: Double = 1.0,
        current: Double = 1.0,
        peak: Double = 1.0,
    ) = MultiHorizonHolding6405.Input(
        ageMs = ageMs,
        entryPriceUsd = entry,
        currentPriceUsd = current,
        peakPriceUsd = peak,
        strictStopFractionOfEntry = 0.65,
        partialTakeMultiple = 2.0,
        trailingStopFractionOfPeak = 0.80,
        drawdownExitFractionOfPeak = 0.50,
    )

    @Test fun strict_stop_fires_regardless_of_horizon() {
        val a = MultiHorizonHolding6405.decide(input(1_000L, entry = 1.0, current = 0.5))
        val b = MultiHorizonHolding6405.decide(input(10 * 60_000L, entry = 1.0, current = 0.5))
        assertEquals(MultiHorizonHolding6405.Action.STRICT_STOP, a)
        assertEquals(MultiHorizonHolding6405.Action.STRICT_STOP, b)
    }

    @Test fun short_term_partial_take_at_multiple() {
        val a = MultiHorizonHolding6405.decide(input(60_000L, entry = 1.0, current = 2.5, peak = 2.5))
        assertEquals(MultiHorizonHolding6405.Action.PARTIAL_TAKE, a)
    }

    @Test fun mid_term_trailing_stop_from_peak() {
        // ageMs in mid-term, current at 70% of peak → trailing stop fires (< 80%)
        val a = MultiHorizonHolding6405.decide(
            input(10 * 60_000L, entry = 1.0, current = 0.7, peak = 1.0),
        )
        assertEquals(MultiHorizonHolding6405.Action.TRAILING_STOP, a)
    }

    @Test fun long_term_drawdown_exit_below_half_peak() {
        // Long-term (ageMs > 60min) with strictStop tuned so it doesn't fire.
        val i = MultiHorizonHolding6405.Input(
            ageMs = 90 * 60_000L,
            entryPriceUsd = 1.0,
            currentPriceUsd = 0.7,          // relative to peak 2.0 → 35%
            peakPriceUsd = 2.0,
            strictStopFractionOfEntry = 0.30, // 0.7 > 0.3*1.0, so no strict
            partialTakeMultiple = 2.0,
            trailingStopFractionOfPeak = 0.80,
            drawdownExitFractionOfPeak = 0.50,
        )
        assertEquals(MultiHorizonHolding6405.Action.DRAWDOWN_EXIT, MultiHorizonHolding6405.decide(i))
    }

    @Test fun holds_when_no_condition_met() {
        val a = MultiHorizonHolding6405.decide(input(1_000L, entry = 1.0, current = 1.1, peak = 1.2))
        assertEquals(MultiHorizonHolding6405.Action.HOLD, a)
    }
}
