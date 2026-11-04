package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.ForwardEntryMark8527
import com.lifecyclebot.engine.truth.LabelBasis8386
import com.lifecyclebot.engine.truth.LabelMark8386
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8527ForwardEntryBasisTest {
    private val now = 1_800_000_000_000L
    private val fresh = 45_000L
    private val oldest = 90_000L
    private fun mark(px: Double = 1.0, at: Long = now - 1_000L, source: String = "TOKEN_STATE",
                     mint: String = "MintA", units: String = "USD_PER_TOKEN") =
        LabelMark8386(mint, "pool", px, at, now, source, units = units)
    private fun select(state: LabelMark8386, market: LabelMark8386? = null) =
        ForwardEntryMark8527.select(state, market, now, fresh, oldest)

    @Test fun sourcePriceAndClockSurviveALiveUpdateDuringAncillaryWork() {
        val token = TokenState("MintA", lastPrice = 1.0, lastPriceUpdate = now - 1_000L)
        val state = LabelMark8386(token.mint, token.lastPriceDex, token.lastPrice, token.lastPriceUpdate, now, "TOKEN_STATE")
        val selected = select(state, mark(1.05, now - 500L, "OFFWATCH"))!!
        token.lastPrice = 1.10
        token.lastPriceUpdate = now
        val basis = LabelBasis8386(selected, 10_000.0)
        assertTrue(basis.mark.known())
        assertEquals(1.0, basis.mark.usd, 0.0)
        assertEquals(now - 1_000L, basis.mark.observedAtMs)
        assertNotEquals(token.lastPrice, basis.mark.usd, 0.0)
        // The old algorithm compared 1.0 to the now-current 1.10 and had no matching typed mark: UNKNOWN.
    }

    @Test fun futureStateClockIsNotFreshAndCannotSeedAnUnknownBasis() {
        val future = mark(at = now + 1L)
        assertNull(select(future))
        val validMarket = mark(0.99, now - 10L, "OFFWATCH")
        assertSame(validMarket, select(future, validMarket))
        assertNull(select(mark(at = 0L)))
    }

    @Test fun matchingIndependentMarkKeepsItsOwnProofAndClockWithoutRoundingInventedPrices() {
        val state = mark()
        val chain = mark(at = now - 2_000L, source = "CURVE_ACCOUNT").copy(independentBasis = true, decimals = 6)
        assertSame(chain, select(state, chain))
        val differentPrice = chain.copy(usd = 1.00000001)
        assertSame(state, select(state, differentPrice))
        assertFalse(select(state, differentPrice)!!.independentBasis)
    }

    @Test fun staleStateFallsBackToFreshMarketButSameExistingEntryAgeBoundRemains() {
        val stale = mark(at = now - 60_000L)
        assertSame(stale, select(stale))
        val market = mark(1.1, now - 1_000L, "OFFWATCH")
        assertSame(market, select(stale, market))
        assertNull(select(mark(at = now - oldest - 1L)))
        assertNull(select(mark(at = now - oldest - 1L), market.copy(observedAtMs = now - fresh - 1L)))
    }

    @Test fun marketFallbackCannotBorrowAnotherMintOrUnitOrFutureObservation() {
        val unusable = mark(at = now - oldest - 1L)
        assertNull(select(unusable, mark(source = "OFFWATCH", mint = "OtherMint")))
        assertNull(select(unusable, mark(source = "OFFWATCH", units = "SOL_PER_TOKEN")))
        assertNull(select(unusable, mark(at = now + 1L, source = "OFFWATCH")))
        assertNull(select(unusable, mark(px = Double.NaN, source = "OFFWATCH")))
    }

    @Test fun runtimeAssignsTheSelectedMarkWithoutRereadingAndUsesCurrentLeaseClock() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        val observe = source.substringAfter("fun observe(ts: TokenState").substringBefore("lastSeenAt[key] = nowMs")
        assertTrue(observe.indexOf("val entryMark8527 =") < observe.indexOf("val liq ="))
        assertTrue(observe.contains("it.basis8386 = LabelBasis8386(entryMark8527, it.entryMcap)"))
        assertTrue(observe.contains("it.decisionBasis8386 = it.basis8386"))
        assertFalse(observe.contains("m.usd == px"))
        assertTrue(observe.contains("curveContradicts8165(ts.mint, px, nowMs, entryMark8527.observedAtMs)"))
        assertTrue(source.contains("KeylessMarks8359.laneCovered8418(m, leaseAt8527)"))
        assertFalse(source.contains("KeylessMarks8359.laneCovered8418(m, nowMs)"))
    }
}
