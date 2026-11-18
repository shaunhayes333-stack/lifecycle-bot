package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LabelMark8386
import com.lifecyclebot.engine.truth.RunnerMetrics8853
import com.lifecyclebot.engine.truth.RunnerRouteQuotes8853
import com.lifecyclebot.engine.truth.RunnerStudy8852
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

/** 8853 is evidence research; none of these cases grants a live trade or a realized fill. */
class Aate8853RunnerEvidenceTest {
    private val at = 1_800_000_000_000L
    private val solMint = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"
    private val routes = RunnerRouteQuotes8853
    private val study = RunnerStudy8852

    private fun mark(mint: String, px: Double, stamp: Long) =
        LabelMark8386(mint, "pump.curve", px, stamp, stamp, "CURVE_FRAME", independentBasis = true)

    private fun quote(requested: Long, received: Long, input: Long, output: Long, solUsd: Double) =
        RunnerRouteQuotes8853.QuoteLeg(requested, received, input, output, solUsd,
            "metis", "pool_1", 0.5)

    @Before fun startClean() = clear()
    @After fun clear() { study.resetForTest(); routes.resetForTest() }

    @Test fun routeQuoteRequiresSameBuyQuantityForSixHourSellAndNeverClaimsFill() {
        assertFalse(routes.solanaMint8853("0x1234567890abcdef"))
        assertFalse(routes.solanaMint8853("BTCUSDT"))
        assertTrue(routes.solanaMint8853(solMint))
        assertTrue(routes.nominate(solMint, "MOONSHOT", at))
        assertFalse(routes.acceptEntry(solMint, at + 1, quote(at + 500, at + 1_000,
            40_000_000L, 20_000_000_000L, 250.0)))
        assertTrue(routes.acceptEntry(solMint, at, quote(at + 500, at + 1_000,
            40_000_000L, 20_000_000_000L, 250.0)))
        val target = at + 1_000 + study.SIX_HOURS_MS
        assertFalse(routes.acceptExit(solMint, at, quote(target, target + 1_000,
            10_000_000_000L, 1_000_000_000L, 300.0)))
        assertTrue(routes.acceptExit(solMint, at, quote(target, target + 1_000,
            20_000_000_000L, 1_000_000_000L, 300.0)))
        val result = routes.resultsForTest().single()
        assertEquals(RunnerRouteQuotes8853.State.TWO_QUOTES_INDICATIVE, result.state)
        assertEquals(10.0, result.nominalEntryUsd!!, 0.0001)
        assertEquals(300.0, result.grossSellUsd!!, 0.0001)
        val evidence = result.journal()
        assertEquals("JUPITER_NON_BINDING_INDICATIVE", evidence.getString("quoteClass"))
        assertTrue(evidence.getBoolean("fullBuyQuotedQuantitySold"))
        assertFalse(evidence.getBoolean("buyFillObserved"))
        assertFalse(evidence.getBoolean("sellFillObserved"))
        assertTrue(evidence.isNull("realisedWalletPnl"))
    }

    @Test fun entryQuoteExpiryIsMissingNotAZeroReturn() {
        assertTrue(routes.nominate(solMint, "EXPRESS", at))
        routes.tick(at + study.ENTRY_WINDOW_MS + 1)
        val result = routes.resultsForTest().single()
        assertEquals(RunnerRouteQuotes8853.State.ENTRY_QUOTE_UNAVAILABLE, result.state)
        assertNull(result.grossSellUsd)
        assertNull(result.nominalEntryUsd)
    }

    @Test fun restartPreservesQuotedInputAndOriginalSixHourClock() {
        routes.nominate(solMint, "CRYPTO_MOMENTUM", at)
        assertTrue(routes.acceptEntry(solMint, at, quote(at + 100, at + 200,
            50_000_000L, 900_000_000L, 200.0)))
        val saved = routes.encode()
        routes.resetForTest()
        routes.restore(saved, at + 300)
        val target = at + 200 + study.SIX_HOURS_MS
        assertTrue(routes.acceptExit(solMint, at, quote(target, target + 1_000,
            900_000_000L, 50_000_000L, 200.0)))
        assertEquals(10.0, routes.resultsForTest().single().grossSellUsd!!, 0.0001)
    }

    @Test fun signalCandidatesRequireFreshIndependentFactsAndSamePoolLiquidity() {
        fun witness(pressure: Double? = null, change: Double? = null,
                    safety: Boolean? = null, breakStructure: Boolean? = null,
                    buyerShare: Double? = null, liq: Double? = null, pool: String = "") =
            RunnerMetrics8853.Witness(at, breakStructure, buyerShare, pressure, change, safety, liq, pool,
                pressureSourceMs = if (pressure != null) at else 0,
                changeSourceMs = if (change != null) at else 0,
                safetySourceMs = if (safety != null) at else 0)
        val partial = witness(pressure = 20.0)
        assertFalse(partial.replayCoverage())
        assertNull(RunnerMetrics8853.candidateExit(partial, 3.0, null, ""))
        assertEquals("OBSERVED_DISTRIBUTION_AND_FALLING_PRICE",
            RunnerMetrics8853.candidateExit(witness(20.0, -12.0), 3.0, null, ""))
        assertNull(RunnerMetrics8853.candidateExit(witness(20.0, -12.0), 1.1, null, ""))
        assertEquals("OBSERVED_STRUCTURE_BREAK_WEAK_BUYERS",
            RunnerMetrics8853.candidateExit(witness(breakStructure = true, buyerShare = 0.2), 2.0, null, ""))
        assertNull(RunnerMetrics8853.candidateExit(witness(liq = 400.0, pool = "other"), 4.0, 1000.0, "entry"))
        assertEquals("OBSERVED_SAME_POOL_LIQUIDITY_HALF",
            RunnerMetrics8853.candidateExit(witness(liq = 400.0, pool = "entry"), 4.0, 1000.0, "entry"))
        assertEquals("OBSERVED_SAFETY_HARD_BLOCK",
            RunnerMetrics8853.candidateExit(witness(safety = true), 1.0, null, ""))
        assertTrue(witness(pressure = 75.0, change = 2.0, safety = false).replayCoverage())
    }

    @Test fun denselyObservedMetricsSeparateEarlyExitFromSixHourHoldEvenAcrossRestart() {
        val mint = "metric_replay_8853"
        study.nominate(mint, "CORE", "MEME_NATIVE_READY", 91.0, at)
        val entry = at + 1_000
        fun metrics(stamp: Long, sell: Boolean = false) = RunnerMetrics8853.Witness(
            atMs = stamp, buyerPressurePct = if (sell) 20.0 else 80.0,
            change5mPct = if (sell) -12.0 else 2.0, hardSafety = false,
            pressureSourceMs = stamp, changeSourceMs = stamp, safetySourceMs = stamp)
        study.observe(mint, mark(mint, 1.0, entry), entry, 4.0, true, metrics(entry))
        for (i in 1..3) {
            val stamp = entry + i * 5L * 60_000L
            study.observe(mint, mark(mint, if (i == 3) 3.0 else 2.0, stamp), stamp,
                metrics = metrics(stamp, sell = i == 3))
        }
        val saved = study.encode()
        study.resetForTest()
        study.restore(saved, entry + 16L * 60_000L)
        for (i in 4..72) {
            val stamp = entry + i * 5L * 60_000L
            study.observe(mint, mark(mint, 8.0, stamp), stamp, metrics = metrics(stamp))
        }
        val r = study.resultsForTest().single { it.mint == mint }
        assertEquals(RunnerStudy8852.State.COMPLETE_MARK_ONLY, r.state)
        assertEquals(79.6, r.holdUsd10!!, 0.0001)
        assertEquals(29.6, r.metricsOnlyExitUsd10!!, 0.0001)
        assertEquals("OBSERVED_DISTRIBUTION_AND_FALLING_PRICE", r.metricsExitReason)
        assertEquals(73, r.metricsSamples)
        assertEquals(5 * 60_000L, r.metricsMaxGapMs)
    }

    @Test fun incompleteMetricPathNeverPretendsThereWasACompleteSignalReplay() {
        val mint = "metric_missing_8853"
        study.nominate(mint, "REAWAKEN", "MEME_NATIVE_READY", 72.0, at)
        val entry = at + 1_000
        study.observe(mint, mark(mint, 1.0, entry), entry, 4.0, true,
            RunnerMetrics8853.Witness(entry, hardSafety = false))
        for (i in 1..72) {
            val stamp = entry + i * 5L * 60_000L
            study.observe(mint, mark(mint, 5.0, stamp), stamp,
                metrics = RunnerMetrics8853.Witness(stamp, hardSafety = false))
        }
        val r = study.resultsForTest().single { it.mint == mint }
        assertEquals("METRIC_PATH_INCOMPLETE", r.metricsExitReason)
        assertNull(r.metricsOnlyExitUsd10)
        assertEquals(0, r.metricsSamples)
    }
}
