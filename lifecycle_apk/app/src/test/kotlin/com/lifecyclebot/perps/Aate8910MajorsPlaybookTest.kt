package com.lifecyclebot.perps

import com.lifecyclebot.engine.truth.CryptoSpotPaperBacking8788
import com.lifecyclebot.perps.MajorSetups8910.Candle8910
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8910 — the majors playbook on the real hourly tape (capitulation reclaim, funding / OI-flush reversal, BTC regime and
 * relative-strength rotation, range low, breakout retest, session), the twin's strategy records, and the backing by strategy.
 */
class Aate8910MajorsPlaybookTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val H = 3_600_000L
    private fun k(i: Int, o: Double, h: Double, l: Double, c: Double, v: Double = 10.0, buy: Double = 0.5) =
        Candle8910(i * H, o, h, l, c, v, v * buy)

    /** 20 quiet hours at 100, a -6 % flush on 4x volume, a higher low, and a buyer-led last hour. */
    private fun flushed(): List<Candle8910> =
        (0 until 20).map { k(it, 100.0, 101.0, 99.5, 100.2) } +
            k(20, 100.0, 100.1, 93.0, 94.0, v = 40.0, buy = 0.25) +
            k(21, 94.0, 94.8, 93.5, 94.5) + k(22, 94.5, 95.0, 94.0, 94.6) + k(23, 94.5, 95.5, 94.2, 95.4, v = 12.0, buy = 0.6)

    @Test fun theFlushAndReclaimStopsUnderTheLowAndTargetsTheBreakdown() {
        val c = flushed()
        assertEquals(20, MajorSetups8910.flushIndex8910(c, 12))
        assertEquals("the open of the red run that flushed", 100.0, MajorSetups8910.breakdownLevel8910(c, 20), 1e-9)
        val s = MajorSetups8910.capitulation8910(c, 95.6)
        assertNotNull(s)
        assertEquals("MAJ8910_CAPITULATION_RECLAIM", s!!.strategy)
        assertEquals(93.0 * 0.997, s.stop, 1e-9)
        assertEquals(100.0, s.target, 1e-9)
        assertNull("no reclaim yet (under the last hour's high and the day's VWAP)", MajorSetups8910.capitulation8910(c, 94.3))
        val lowerLow = c.dropLast(1) + k(23, 94.5, 95.5, 92.5, 95.4, v = 12.0, buy = 0.6)
        assertNull("a lower low is no reversal", MajorSetups8910.capitulation8910(lowerLow, 95.6))
        val sellers = c.dropLast(1) + k(23, 94.5, 95.5, 94.2, 95.4, v = 12.0, buy = 0.4)
        assertNull("sellers leading the last hour", MajorSetups8910.capitulation8910(sellers, 95.6))
    }

    @Test fun crowdedShortsOrClearedLeverageMakeItTheFundingReversal() {
        val c = flushed()
        assertNull("unobserved funding and OI fire nothing", MajorSetups8910.fundingFlush8910(c, 95.6, null, null))
        assertNull("funding not crowded", MajorSetups8910.fundingFlush8910(c, 95.6, -0.00005, -1.0))
        assertEquals("FUNDING_FLUSH_REVERSAL", MajorSetups8910.fundingFlush8910(c, 95.6, -0.0002, null)?.name)
        assertEquals("FUNDING_FLUSH_REVERSAL", MajorSetups8910.fundingFlush8910(c, 95.6, null, -6.0)?.name)
        assertEquals("the strongest setup first", "FUNDING_FLUSH_REVERSAL", MajorSetups8910.best8910(c, 95.6, "U", Double.NaN, -1, -0.0002, null)?.name)
        assertEquals("CAPITULATION_RECLAIM", MajorSetups8910.best8910(c, 95.6, "U", Double.NaN, -1, null, null)?.name)
    }

    @Test fun btcLeadsAndTheLeadersRotate() {
        assertEquals("RECLAIM", MajorSetups8910.btcRegime8910(flushed(), 95.6))
        val up = (0 until 24).map { k(it, 100.0 + it, 101.2 + it, 99.7 + it, 100.8 + it, buy = 0.6) }
        val price = up.last().close + 0.1
        assertEquals("UP", MajorSetups8910.btcRegime8910(up, price))
        assertEquals("U", MajorSetups8910.btcRegime8910(up.take(10), price))
        val s = MajorSetups8910.rsRotation8910(up, price, "UP", 3.0, 0)
        assertNotNull(s); assertEquals("RS_ROTATION", s!!.name)
        assertEquals(up.takeLast(4).minOf { it.low } * 0.997, s.stop, 1e-9)
        assertEquals(price + 2.0 * (price - s.stop), s.target, 1e-9)
        assertNull("BTC falling", MajorSetups8910.rsRotation8910(up, price, "DOWN", 3.0, 0))
        assertNull("not a leader", MajorSetups8910.rsRotation8910(up, price, "UP", 3.0, 3))
        assertNull("not ahead enough", MajorSetups8910.rsRotation8910(up, price, "UP", 1.0, 0))
    }

    @Test fun theRangeLowAndTheBreakoutRetest() {
        val range = (0 until 45).map { i -> if (i == 10) k(i, 101.0, 102.0, 100.0, 101.5) else if (i == 20) k(i, 108.0, 110.0, 107.0, 109.0) else k(i, 104.0, 105.0, 103.0, 104.5) } +
            k(45, 102.0, 102.5, 101.0, 101.5) + k(46, 101.5, 102.0, 101.2, 101.4) + k(47, 101.4, 102.0, 101.5, 101.9, buy = 0.6)
        val r = MajorSetups8910.rangeLow8910(range, 102.0)
        assertNotNull(r); assertEquals("RANGE_LOW_RECLAIM", r!!.name)
        assertEquals(100.0 * 0.997, r.stop, 1e-9); assertEquals(105.0, r.target, 1e-9)
        assertNull("in the middle of the range", MajorSetups8910.rangeLow8910(range, 104.0))
        val bo = (0 until 48).map { k(it, 97.0, 100.0, 95.0, 98.0) } +
            k(48, 99.0, 101.5, 98.8, 101.0) + k(49, 101.0, 101.2, 100.3, 100.8) + k(50, 100.8, 101.3, 100.4, 101.0) +
            k(51, 101.0, 101.4, 100.5, 101.1) + k(52, 101.1, 101.4, 100.6, 100.9) + k(53, 100.9, 101.2, 100.5, 100.95, buy = 0.6)
        val b = MajorSetups8910.breakoutRetest8910(bo, 100.9)
        assertNotNull(b); assertEquals("BREAKOUT_RETEST", b!!.name)
        assertEquals(minOf(100.0 * 0.992, 100.3 * 0.997), b.stop, 1e-9)
        assertNull("too far above the level to be a retest", MajorSetups8910.breakoutRetest8910(bo, 102.0))
        assertFalse("reward under 1.2R", MajorSetups8910.worth8910(100.0, 98.0, 102.0))
        assertFalse("target under twice the round trip", MajorSetups8910.worth8910(100.0, 99.5, 101.5))
        assertTrue(MajorSetups8910.worth8910(100.0, 98.0, 103.0))
    }

    @Test fun theSessionAndTheTape() {
        assertEquals("ASIA", MajorSetups8910.session8910(3 * H))
        assertEquals("EU", MajorSetups8910.session8910(8 * H))
        assertEquals("US", MajorSetups8910.session8910(14 * H))
        assertEquals("LATE", MajorSetups8910.session8910(22 * H))
        val body = "[[3600000,\"100\",\"101\",\"99\",\"100.5\",\"12\",7199999,\"0\",5,\"7\",\"0\",\"0\"]," +
            "[7200000,\"100.5\",\"102\",\"100\",\"101\",\"9\",10799999,\"0\",5,\"4\",\"0\",\"0\"]]"
        val c = MajorTape8910.parseKlines8910(body, 8_000_000L)
        assertEquals("the hour still open is dropped", 1, c.size)
        assertEquals(7.0 / 12.0, c[0].buyShare, 1e-9)
        assertEquals(-0.0002, MajorTape8910.bybitFunding8910("{\"result\":{\"list\":[{\"fundingRate\":\"-0.0002\"}]}}")!!, 1e-12)
        assertEquals(-10.0, MajorTape8910.bybitOi4h8910("{\"result\":{\"list\":[{\"openInterest\":\"90\"},{\"openInterest\":\"95\"},{\"openInterest\":\"97\"},{\"openInterest\":\"99\"},{\"openInterest\":\"100\"}]}}")!!, 1e-9)
        assertEquals(0.0001, MajorTape8910.okxFunding8910("{\"data\":[{\"fundingRate\":\"0.0001\"}]}")!!, 1e-12)
        assertEquals(-5.0, MajorTape8910.okxOi4h8910("{\"data\":[[\"5\",\"95\"],[\"4\",\"96\"],[\"3\",\"97\"],[\"2\",\"99\"],[\"1\",\"100\"]]}")!!, 1e-9)
        assertNull(MajorTape8910.bybitOi4h8910("{\"result\":{\"list\":[]}}"))
        assertNull("not a major", MajorTape8910.candles8910("NOTAMAJOR8910"))
    }

    @Test fun eachCloseIsFiledByStrategySessionAndRegime() {
        assertEquals("CRYPTO_SPOT|S=MAJ8910_CAPITULATION_RECLAIM", CryptoTwin8883.strategyKey8910("crypto_spot", "MAJ8910_CAPITULATION_RECLAIM"))
        assertEquals("CRYPTO_ALT|S=DESK_DIP_HUNTER_X", CryptoTwin8883.strategyKey8910("CRYPTO_ALT", "desk dip_hunter:x"))
        assertNull(CryptoTwin8883.strategyKey8910("CRYPTO_ALT", " "))
        val sl = CryptoTwin8883.slices8910("CRYPTO_SPOT", listOf("STRATEGY7803=MAJ8910_RS_ROTATION", "SESSION8910=US", "BTCREG8910=UP"), 0L)
        assertEquals(listOf("CRYPTO_SPOT|S=MAJ8910_RS_ROTATION", "CRYPTO_SPOT|S=MAJ8910_RS_ROTATION@US", "CRYPTO_SPOT|S=MAJ8910_RS_ROTATION@BTC_UP"), sl)
        val noTags = CryptoTwin8883.slices8910("CRYPTO_ALT", listOf("STRATEGY7803=DESK_CORE"), 14 * H, regime = "DOWN")
        assertEquals("CRYPTO_ALT|S=DESK_CORE@US", noTags[1]); assertEquals("CRYPTO_ALT|S=DESK_CORE@BTC_DOWN", noTags[2])
        CryptoTwin8883.resetForTest8883()
        repeat(25) { CryptoTwin8883.addCloseForTest8883("CRYPTO_SPOT|S=MAJ8910_RS_ROTATION", it.toLong(), if (it % 5 == 0) -2.0 else 4.0, 0.1) }
        CryptoTwin8883.addCloseForTest8883("CRYPTO_SPOT|TRF1", 1L, 1.0, 0.1)
        val rec = CryptoTwin8883.strategyRecord8910("CRYPTO_SPOT", "MAJ8910_RS_ROTATION")
        assertNotNull(rec); assertEquals(25, rec!!.first.trades)
        assertEquals(1, CryptoTwin8883.gradedStrategies8910("CRYPTO_SPOT"))
        assertEquals("strategy records are not trend cells", 0, CryptoTwin8883.gradedCells8886("CRYPTO_SPOT"))
        assertTrue(CryptoTwin8883.statusLine8883().contains("records{-} cells8886=1"))
        assertTrue(CryptoTwin8883.strategyLine8910().contains("CRYPTO_SPOT:MAJ8910_RS_ROTATION{n25"))
        CryptoTwin8883.resetForTest8883()
    }

    @Test fun aStrategyBacksOnItsOwnRecordAndALosingOneWithdraws() {
        // with no strategy read it is the cell rule exactly
        for (lane in listOf(true, false)) for (cb in listOf(true, false)) for (cl in listOf(true, false)) if (!(cb && cl))
            assertEquals(CryptoSpotPaperBacking8788.effective8886(lane, cb, cl), CryptoSpotPaperBacking8788.effective8910(lane, cb, cl, false, false))
        assertTrue("a backing strategy grants what the lane does not", CryptoSpotPaperBacking8788.effective8910(false, false, false, true, false))
        assertFalse("a proven-losing strategy withdraws the lane's backing", CryptoSpotPaperBacking8788.effective8910(true, false, false, false, true))
        assertFalse("a proven-losing cell still withdraws", CryptoSpotPaperBacking8788.effective8910(false, false, true, true, false))
    }

    @Test fun wired() {
        val alt = src("perps/CryptoAltTrader.kt")
        assertTrue(alt.contains("try { setupSignal8910(market, data)?.let { signals.add(it) } } catch (_: Throwable) {}"))
        assertTrue(alt.contains("val tp = if (setupLevels8910) signal.setupTarget8910 else tpDesk8910"))
        assertTrue(alt.contains("val sl = if (setupLevels8910) signal.setupStop8910 else slDesk8910"))
        assertTrue(alt.contains("val tp8110 = if (setupLevels8910) tp else laneTp8110(signal, isSpot, finalTp, finalSize, candidate.liquidityUsd) ?: tp"))
        val contract = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        assertEquals("every backing read, including early data classification, carries the strategy", 7,
            Regex(Regex.escape("candidate.evidence[\"trend8886\"], candidate.evidence[\"strategy7803\"])")).findAll(contract).count())
        val brief = src("engine/DiagnosisBrief8438.kt")
        assertTrue(brief.contains("CryptoTwin8883.strategyLine8910()"))
        assertTrue(brief.contains("MajorSetups8910.statusLine8910()"))
    }
}
