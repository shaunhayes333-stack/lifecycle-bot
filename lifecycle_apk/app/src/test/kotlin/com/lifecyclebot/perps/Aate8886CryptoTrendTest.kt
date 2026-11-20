package com.lifecyclebot.perps

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.truth.CryptoSpotPaperBacking8788
import com.lifecyclebot.network.PairInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8886 — plan W7: "keeps the classic signal engine; adds a multi-horizon price and volume trend score for majors, with
 * order-flow imbalance for entry timing; twin coverage". Each crypto candidate carries its trend cell; the twin grades every
 * lane|cell; a cell's own record backs or withdraws the lane backing, deflated across the cells graded.
 */
class Aate8886CryptoTrendTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val now = 1_800_000_000_000L

    @Before fun setUp() { CryptoTwin8883.resetForTest8883(); CryptoTrend8886.resetForTest8886() }
    @After fun tearDown() { CryptoTwin8883.resetForTest8883(); CryptoTrend8886.resetForTest8886() }

    private fun tok(trendAt: Long = now - 60_000L, shortAt: Long = now - 30_000L) = DynamicAltTokenRegistry.DynToken(
        mint = "Trend8886", chainId = "solana", symbol = "TRND", name = "Trend", price = 1.0, priceObservedAtMs8372 = now,
        priceChange24h = 5.0, change24hObservedAtMs8752 = now - 60_000L, volume24h = 100_000.0,
        buys5m8832 = 10, sells5m8832 = 4, volume5m8832 = 1_000.0, change5mPct8832 = 1.2, shortWindowObservedAtMs8832 = shortAt,
        change1hPct8886 = 2.0, change6hPct8886 = -1.0, trendObservedAtMs8886 = trendAt)

    @Test fun theBandReadsEveryMeasuredHorizonAndNothingElse() {
        assertEquals("one horizon is not a trend", "TU", CryptoTrend8886.band8886(listOf(3.0, Double.NaN)))
        assertEquals("T3", CryptoTrend8886.band8886(listOf(1.0, 2.0, 0.1)))
        assertEquals("T0", CryptoTrend8886.band8886(listOf(-1.0, -2.0)))
        assertEquals("T2", CryptoTrend8886.band8886(listOf(1.0, 2.0, -0.5)))
        assertEquals("a tie is not up", "T1", CryptoTrend8886.band8886(listOf(1.0, -2.0)))
        assertEquals("a flat reading is neither", "T1", CryptoTrend8886.band8886(listOf(0.0, 1.0, -1.0)))
        assertTrue("a reading counts while its age is at most half its horizon", CryptoTrend8886.current8886(now - 30 * 60_000L, 3_600_000L, now))
        assertFalse(CryptoTrend8886.current8886(now - 31 * 60_000L, 3_600_000L, now))
        assertFalse("a future clock is not a reading", CryptoTrend8886.current8886(now + 1L, 3_600_000L, now))
        assertFalse("no clock, no reading", CryptoTrend8886.current8886(0L, 3_600_000L, now))
    }

    @Test fun theReadIsTheRowsOwnObservationsUnderTheirOwnClocks() {
        val r = CryptoTrend8886.read8886(tok(), now)
        // 5m +1.2, 1h +2.0, 6h -1.0, 24h +5.0 (7d not carried): 3 up, 1 down; buyers lead; 1,000 > 100,000 / 288
        assertEquals("T2F1V1", r.key)
        assertEquals("T2F1", r.cell)
        assertEquals(4, r.horizons); assertEquals(3, r.up); assertEquals(1, r.down)
        val stale1h = CryptoTrend8886.read8886(tok(trendAt = now - 40 * 60_000L), now)
        assertEquals("a 40-minute-old 1 h change is dropped; the 6 h one still holds", 3, stale1h.horizons)
        val noShort = CryptoTrend8886.read8886(tok(shortAt = now - 121_000L), now)
        assertEquals("the five-minute set keeps CryptoShortWindow8832's own freshness", "T2FUVU", noShort.key)
        assertEquals("TUFUVU", CryptoTrend8886.read8886(null, now).key)
        CryptoTrend8886.note8886(r)
        assertTrue(CryptoTrend8886.statusLine8886().contains("cells{T2F1=1}"))
        assertTrue(CryptoTrend8886.statusLine8886().contains("n4=1"))
    }

    @Test fun thePairMergeCarriesTheSetUnderThePairsClockAndNeverRollsItBack() {
        val pair = PairInfo(pairAddress = "Pool8886", baseSymbol = "TRND", baseName = "Trend", url = "",
            candle = Candle(now - 5_000L, 1.0, 0.0, 0.0, 0.0), priceChangeH1 = 4.0, priceChangeH6 = -2.0)
        val older = tok(trendAt = now - 60_000L).copy(change7dPct8886 = 9.0)
        val merged = DynamicAltTokenRegistry.mergePairObservation8870(older, pair, now)
        assertEquals(4.0, merged.change1hPct8886, 0.0)
        assertEquals(-2.0, merged.change6hPct8886, 0.0)
        assertTrue("a newer observation replaces the whole set", merged.change7dPct8886.isNaN())
        assertEquals(now - 5_000L, merged.trendObservedAtMs8886)
        val newer = tok(trendAt = now - 1_000L)
        val kept = DynamicAltTokenRegistry.mergePairObservation8870(newer, pair, now)
        assertEquals(newer.change1hPct8886, kept.change1hPct8886, 0.0)
        assertEquals(newer.trendObservedAtMs8886, kept.trendObservedAtMs8886)
        val absent = DynamicAltTokenRegistry.mergePairObservation8870(older, pair.copy(priceChangeH1 = Double.NaN, priceChangeH6 = Double.NaN), now)
        assertEquals("a pair without h1 / h6 leaves the set alone", older.trendObservedAtMs8886, absent.trendObservedAtMs8886)
        val reg = src("perps/DynamicAltTokenRegistry.kt")
        assertTrue(reg.contains("&sparkline=false&price_change_percentage=1h,24h,7d"))
        assertTrue(reg.contains("obj.optDouble(\"price_change_percentage_7d_in_currency\", Double.NaN)"))
        assertTrue(reg.contains("val change6 = number(changes, \"h6\")"))
        assertEquals("both upsert branches carry the set", 2, Regex("trendObservedAtMs8886 = newestTrend8886.trendObservedAtMs8886").findAll(reg).count())
        assertTrue(src("network/DexscreenerApi.kt").contains("priceChangeH6    = p.optJSONObject(\"priceChange\")?.takeIf { it.has(\"h6\") }"))
    }

    @Test fun theFamilyMarginIsTheOneSeRuleHeldAcrossTheCellsGraded() {
        assertEquals(0.5, CryptoTrend8886.phi8886(0.0), 1e-7)
        assertEquals(0.8413447, CryptoTrend8886.phi8886(1.0), 1e-6)
        assertEquals(0.1586553, CryptoTrend8886.phi8886(-1.0), 1e-6)
        assertEquals("one cell: the plain one-SE rule", 1.0, CryptoTrend8886.familyZ8886(1), 1e-4)
        assertEquals(1.3868, CryptoTrend8886.familyZ8886(2), 1e-3)
        assertEquals(2.2751, CryptoTrend8886.familyZ8886(15), 1e-3)
        assertEquals(1.0, CryptoTrend8886.familyZ8886(0), 1e-4)
    }

    @Test fun aCellsProofIsTheLeaderboardsClampedRecord() {
        val p = CryptoTrend8886.proof8886(listOf(10.0, -5.0, 0.2, 3.0))
        assertEquals(4.0, p[0], 0.0)
        assertEquals(2.05, p[1], 1e-12)
        assertEquals(kotlin.math.sqrt((134.04 / 4.0 - 2.05 * 2.05) / 4.0), p[2], 1e-12)
        assertEquals(13.2 / 5.0, p[3], 1e-12)
        assertEquals("a glitch print is clamped as the leaderboard clamps it", -100.0, CryptoTrend8886.proof8886(listOf(-500.0))[1], 0.0)
        assertTrue(CryptoTrend8886.proof8886(emptyList())[1].isNaN())
    }

    @Test fun aCellBacksOnlyAboveItsFamilyMarginAndALosingCellWithdraws() {
        val strong = doubleArrayOf(40.0, 3.0, 1.0, 2.0)
        assertTrue(CryptoTrend8886.cellBacks8886(25, 15, 3.0, 0.4, 2.0, strong, 1))
        assertTrue(CryptoTrend8886.cellBacks8886(25, 15, 3.0, 0.4, 2.0, strong, 15))
        val thin = doubleArrayOf(40.0, 1.5, 1.0, 1.3)
        assertTrue("one cell: mean - 1 SE > 0", CryptoTrend8886.cellBacks8886(25, 15, 1.5, 0.4, 2.0, thin, 1))
        assertFalse("fifteen cells graded: the margin is 2.28 SE", CryptoTrend8886.cellBacks8886(25, 15, 1.5, 0.4, 2.0, thin, 15))
        assertFalse("the lane rule still binds (19 decisive)", CryptoTrend8886.cellBacks8886(10, 9, 3.0, 0.4, 2.0, strong, 1))
        assertTrue(CryptoTrend8886.cellLosing8886(doubleArrayOf(30.0, -3.0, 1.0, 0.6)))
        assertFalse("under 30 closes nothing is proven", CryptoTrend8886.cellLosing8886(doubleArrayOf(29.0, -3.0, 1.0, 0.6)))
        assertTrue(CryptoSpotPaperBacking8788.effective8886(true, false, false))
        assertFalse("a proven-losing cell withdraws the lane's backing", CryptoSpotPaperBacking8788.effective8886(true, false, true))
        assertTrue("a backing cell grants what the lane does not", CryptoSpotPaperBacking8788.effective8886(false, true, false))
        assertFalse(CryptoSpotPaperBacking8788.effective8886(false, false, false))
    }

    @Test fun theTwinFilesEachCloseUnderItsLaneAndItsCell() {
        assertEquals("T3F1", CryptoTrend8886.cellOfReasons8886(listOf("STRATEGY7803=MOMENTUM", "TREND8886=T3F1V0")))
        assertNull(CryptoTrend8886.cellOfReasons8886(listOf("STRATEGY7803=MOMENTUM")))
        for (i in 0 until 22) CryptoTwin8883.addCloseForTest8883("CRYPTO_ALT|T3F1", now + i, if (i % 2 == 0) 6.0 else -2.0, 0.1)
        for (i in 0 until 5) CryptoTwin8883.addCloseForTest8883("CRYPTO_ALT|T0F0", now + i, -3.0, 0.1)
        val rec = CryptoTwin8883.cellRecord8886("crypto_alt", "t3f1")!!
        assertEquals(22, rec.first.trades); assertEquals(11, rec.first.wins); assertEquals(11, rec.first.losses)
        assertEquals(2.0, rec.second[1], 1e-12)
        assertEquals("only cells holding 20 decisive closes are graded", 1, CryptoTwin8883.gradedCells8886("CRYPTO_ALT"))
        assertNull(CryptoTwin8883.metric8883("CRYPTO_ALT"))
        assertTrue("cells are counted, not printed as lanes", CryptoTwin8883.statusLine8883().contains("records{-} cells8886=2"))
        val twin = src("perps/CryptoTwin8883.kt")
        assertTrue(twin.contains("for (k in listOfNotNull(t.lane, t.cell?.let { \"\${t.lane}\$CELL_SEP_8886\$it\" }))"))
        assertTrue(twin.contains("Twin(twin, l, CryptoTrend8886.cellOfReasons8886(p.reasons))"))
    }

    @Test fun theCandidateCarriesItsCellToEveryBackingRead() {
        val alt = src("perps/CryptoAltTrader.kt")
        assertTrue(alt.contains("CryptoTrend8886.read8886(DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(candidate.assetKey) ?: signal.evaluationOwner8870,"))
        assertTrue(alt.contains("CryptoTrend8886.EVIDENCE_8886 to trend8886?.cell.orEmpty(),"))
        assertTrue(alt.contains("listOfNotNull(trend8886.takeIf { it.isNotBlank() }?.let { CryptoTrend8886.TAG_8886 + it }),"))
        assertTrue(alt.contains("canonicalFinalSize6570, lev, tp8110, sl, paper7803, trend8886?.key.orEmpty())"))
        assertTrue(alt.contains("signal, isSpot, candidate, mktSym, finalSize, lev, tp8110, sl, false, trend8886?.key.orEmpty()), candidate.selectedLane)"))
        val contract = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        assertEquals("backs, both capacity waits, the engine pass, the reserve and its wait", 6,
            Regex(Regex.escape("candidate.evidence[\"trend8886\"]")).findAll(contract).count())
        val backing = src("engine/truth/CryptoSpotPaperBacking8788.kt")
        assertTrue(backing.contains("val record = effective8886(laneRecord, cellBacks, cellLosing)"))
        assertTrue(backing.contains("if (!(try { com.lifecyclebot.engine.LiveNoPaper8184.closeOutInLive8872() } catch (_: Throwable) { false })) return false to false"))
        assertTrue(src("engine/truth/CryptoSignalEngine8857.kt").contains("CryptoSpotPaperBacking8788.backs8788(lane, nowMs, cell)"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("CryptoSpotPaperBacking8788.cellStatusLine8886()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 75. The crypto trend cell (8886)"))
    }
}
