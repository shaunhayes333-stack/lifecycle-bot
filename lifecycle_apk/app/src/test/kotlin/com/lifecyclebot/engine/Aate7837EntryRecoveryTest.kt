package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.FieldManual7715
import com.lifecyclebot.engine.truth.QuoteRevalidation7809
import com.lifecyclebot.engine.truth.TradePlan7739
import org.junit.Assert.*
import org.junit.Test

class Aate7837EntryRecoveryTest {
    @Test fun movedQuoteRefreshesOnlyTheNewPriceAndInvalidatesOldVerdict() {
        val fetch = QuoteRevalidation7809.fetcher7809
        val hook = QuoteRevalidation7809.onConfirmed7809
        val async = QuoteRevalidation7809.async7809
        try {
            QuoteRevalidation7809.resetForTest()
            QuoteRevalidation7809.async7809 = false
            QuoteRevalidation7809.fetcher7809 = { 1.2 }
            var invalidated = ""
            QuoteRevalidation7809.onConfirmed7809 = { invalidated = it }
            val ts = TokenState(mint = "moved7837")
            ts.lastPrice = 1.0
            ts.lastPriceUpdate = System.currentTimeMillis() - 600_000L
            assertTrue(QuoteRevalidation7809.request(ts.mint, ts.lastPrice))
            assertEquals(ts.mint, invalidated)
            assertNull(QuoteRevalidation7809.confirmedAgeMs(ts.mint, 1.0))
            assertEquals(FieldManual7715.Regime.IMPAIRED, FieldManual7715.regimeOf(ts))
            // Normal hydration supplies the actual new price. The confirmation
            // must now beat the stale legacy timestamp in both consumers.
            ts.lastPrice = 1.2
            assertNotNull(QuoteRevalidation7809.confirmedAgeMs(ts.mint, ts.lastPrice))
            assertTrue(FieldManual7715.quoteAgeMs7837(ts, System.currentTimeMillis()) in 0L..10_000L)
            assertNotEquals(FieldManual7715.Regime.IMPAIRED, FieldManual7715.regimeOf(ts))
        } finally {
            QuoteRevalidation7809.fetcher7809 = fetch
            QuoteRevalidation7809.onConfirmed7809 = hook
            QuoteRevalidation7809.async7809 = async
            QuoteRevalidation7809.resetForTest()
        }
    }

    @Test fun setupAndCostUseObservedFirstTargetAndInvalidation() {
        val base = FieldManual7715.mandateFor("QUALITY")
        val breakout = TradePlan7739.Read(TradePlan7739.Setup.BASE_BREAKOUT, "BREAKOUT", 4.0, 8.0, 16.0)
        val actual = FieldManual7715.mandateFromRead7837(base, breakout)
        assertEquals(FieldManual7715.SetupFamily.BASE_BREAKOUT, actual.setup)
        assertEquals(8.0, actual.expectedGrossPct, 0.0)
        assertEquals(4.0, actual.invalidationPct, 0.0)
        assertEquals(base.riskFraction, actual.riskFraction, 0.0)
        assertSame(base, FieldManual7715.mandateFromRead7837(base, breakout.copy(setup = null)))
        assertSame(base, FieldManual7715.mandateFromRead7837(base, breakout.copy(stopPct = Double.NaN)))
        val perps = FieldManual7715.mandateFor("PERPS")
        assertSame(perps, FieldManual7715.mandateFromRead7837(perps, breakout))
        assertFalse(FieldManual7715.pricesAgree7837(1.0, 1.2))
        assertFalse(FieldManual7715.pricesAgree7837(Double.NaN, 1.0))
    }

    @Test fun oldReplayCannotBecomeANewLossAndRealDrawdownLatchSurvives() {
        assertFalse(KillSwitch.outcomeIsCurrent7837(900L, 1000L, 1200L))
        assertTrue(KillSwitch.outcomeIsCurrent7837(1100L, 1000L, 1200L))
        assertFalse(KillSwitch.outcomeIsCurrent7837(1300L, 1000L, 1200L))
        assertTrue(KillSwitch.staleBaselineLatch7837("MAX_DRAWDOWN: old", 900L, 1000L, 0.21, 0.21, 0.21))
        assertFalse(KillSwitch.staleBaselineLatch7837("MAX_DRAWDOWN: real", 1100L, 1000L, 0.28, 0.28, 0.21))
        assertFalse(KillSwitch.staleBaselineLatch7837("MAX_DRAWDOWN: real", 900L, 1000L, 0.28, 0.28, 0.21))
        assertFalse(KillSwitch.staleBaselineLatch7837("MANUAL: stopped", 900L, 1000L, 0.21, 0.21, 0.21))
        assertFalse(KillSwitch.staleBaselineLatch7837("MAX_DRAWDOWN: old", 900L, 1000L, 0.21, 0.21, Double.NaN))
    }
}
