package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.cortex.LanePlaybook7907.Obs8084
import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.truth.LocalCandleSynthesis7055
import com.lifecyclebot.engine.truth.PairFlow8169
import com.lifecyclebot.engine.truth.SetupFeatureResolver8169
import com.lifecyclebot.engine.truth.SetupFeatureResolver8169.Cand8169
import com.lifecyclebot.engine.truth.TradeTape8100
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8169 — data starvation (owner 5.0.8168 report: setup features complete for 73 of 15,849 evaluations,
 * DATA_INCOMPLETE dominating every lane while DexScreener, the sweep and the live trade streams were healthy).
 */
class Aate8169DataTest {
    private fun src(p: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val now = 1_800_000_000_000L
    private val mint = "Data8169Mint".padEnd(44, 'D')

    // ── the resolver: first real value wins, a missing input names every source's reason ──

    @Test fun theFirstSourceWithARealValueWinsAndAMissingInputNamesEveryReason() {
        val r = SetupFeatureResolver8169
        val win = r.first8169(listOf<Cand8169<Double>>(Cand8169("primary", null, "STALE"), Cand8169("tape8100", null, "NO_PRINTS"), Cand8169("dexPair", 61.0, "")))
        assertEquals(61.0, win.value!!, 1e-9)
        assertEquals("dexPair", win.source)
        assertNull(win.why)
        val none = r.first8169(listOf<Cand8169<Double>>(Cand8169("primary", null, "DEFAULT_UNOBSERVED"), Cand8169("dexPair", null, "NOT_FETCHED")))
        assertNull(none.value)
        assertNull(none.source)
        assertEquals("primary:DEFAULT_UNOBSERVED|dexPair:NOT_FETCHED", none.why)
        // the primary reading is never overridden by a later source
        val keep = r.first8169(listOf<Cand8169<Double>>(Cand8169("primary", 40.0, "OBSERVED"), Cand8169("dexPair", 90.0, "")))
        assertEquals(40.0, keep.value!!, 1e-9)
    }

    @Test fun buyShareComesFromCountedTradesOnly() {
        val r = SetupFeatureResolver8169
        assertEquals(75.0, r.countShare8169(3, 1, 10, 10)!!, 1e-9)        // the 5m window when it traded
        assertEquals(40.0, r.countShare8169(0, 0, 4, 6)!!, 1e-9)          // else the hour
        assertEquals(40.0, r.countShare8169(-1, -1, 4, 6)!!, 1e-9)        // 5m not supplied
        assertNull(r.countShare8169(0, 0, 0, 0))                           // nobody traded: no reading, never 50/50
        assertNull(r.countShare8169(-1, -1, -1, -1))
    }

    @Test fun aPairReadingIsUsedOnlyWhileFreshAndOnlyForWhatItCarried() {
        val r = SetupFeatureResolver8169
        val full = JSONObject("""{"txns":{"m5":{"buys":6,"sells":2},"h1":{"buys":30,"sells":30}},"priceChange":{"m5":4.5,"h1":-12.0},"liquidity":{"usd":25000}}""")
        val f = PairFlow8169.fromJson8169(full, now - 60_000L)
        assertEquals(75.0, r.pairBp8169(f, now).value!!, 1e-9)
        assertEquals(4.5 to -12.0, r.pairChg8169(f, now).value)
        assertEquals(25_000.0, r.pairLiq8169(f, now).value!!, 1e-9)
        // stale: older than the primary's own 15-minute window
        val old = PairFlow8169.fromJson8169(full, now - 16 * 60_000L)
        assertEquals("STALE", r.pairBp8169(old, now).why)
        assertNull(r.pairChg8169(old, now).value)
        // a pair without txns / priceChange / liquidity states none of them
        val bare = PairFlow8169.fromJson8169(JSONObject("""{"priceUsd":"0.001"}"""), now)
        assertEquals(-1, bare.buysM5)
        assertTrue(bare.chg5m.isNaN() && bare.liquidityUsd.isNaN())
        assertEquals("NO_TRADES", r.pairBp8169(bare, now).why)
        assertEquals("NO_CHANGE", r.pairChg8169(bare, now).why)
        assertEquals("NO_LIQUIDITY", r.pairLiq8169(bare, now).why)
        assertEquals("NOT_FETCHED", r.pairBp8169(null, now).why)
    }

    @Test fun theSweepRowIsASidedDatedReadingAndItsZeroHourMoveIsUnknown() {
        val r = SetupFeatureResolver8169
        fun row(b: Int, s: Int, c5: Double, c1: Double, at: Long) = MarketSweep7297.Row(mint, "D", "D", 1.0, 1e5, 2e4, 1e4, 1e5, c1, b + s, 0, 0.0, false, 1.0, setOf("JUP_TRADED"),
            priceChange5mPct = c5, buysH1 = b, sellsH1 = s, observedAtMs8169 = at)
        assertEquals(60.0, r.sweepBp8169(row(60, 40, 2.0, 8.0, now - 60_000L), now).value!!, 1e-9)
        assertEquals(2.0 to 8.0, r.sweepChg8169(row(60, 40, 2.0, 8.0, now - 60_000L), now).value)
        assertEquals("NO_CHANGE", r.sweepChg8169(row(60, 40, 2.0, 0.0, now), now).why)         // 0.0 = not supplied
        assertEquals("NO_CHANGE", r.sweepChg8169(row(60, 40, Double.NaN, 8.0, now), now).why)
        assertEquals("NO_SPLIT", r.sweepBp8169(row(-1, -1, 2.0, 8.0, now), now).why)
        assertEquals("STALE", r.sweepBp8169(row(60, 40, 2.0, 8.0, 0L), now).why)                 // undated = not fresh
        assertEquals("NOT_LISTED", r.sweepBp8169(null, now).why)
        // merge keeps the sided counts and dates the merged row by its OLDEST dated answer
        val merged = MarketSweep7297.merge(listOf(row(60, 40, 2.0, 8.0, now - 5_000L), row(-1, -1, Double.NaN, 0.0, now - 90_000L),
            row(-1, -1, Double.NaN, 0.0, 0L).copy(providers = setOf("RAYDIUM_VOLUME"))))
        assertEquals(1, merged.size)
        assertEquals(60, merged[0].buysH1)
        assertEquals(40, merged[0].sellsH1)
        assertEquals(now - 90_000L, merged[0].observedAtMs8169)
    }

    @Test fun aNewerPairAnswerIsKeptAndAnOlderOneNeverReplacesIt() {
        PairFlow8169.clearForTest8169()
        val newer = PairFlow8169.Flow8169(5, 5, -1, -1, 1.0, 2.0, 1_000.0, now)
        val older = PairFlow8169.Flow8169(1, 9, -1, -1, 3.0, 4.0, 2_000.0, now - 30_000L)
        PairFlow8169.note8169(mint, newer)
        PairFlow8169.note8169(mint, older)
        assertEquals(now, PairFlow8169.peek8169(mint)!!.atMs)
        PairFlow8169.note8169("short", newer)                          // not a mint
        assertNull(PairFlow8169.peek8169("short"))
        PairFlow8169.clearForTest8169()
    }

    @Test fun fillForAnEvaluationReadsThePairWhenThePrimaryAndTapeAreSilentAndCountsIt() {
        PairFlow8169.clearForTest8169()
        TradeTape8100.resetForTest8100()
        SetupFeatureResolver8169.clearForTest8169()
        val t = System.currentTimeMillis()
        PairFlow8169.note8169(mint, PairFlow8169.Flow8169(9, 3, -1, -1, 3.0, 20.0, 40_000.0, t - 30_000L))
        val fill = SetupFeatureResolver8169.fillFor8169(mint, t, "DEFAULT_UNOBSERVED", null, null, "STALE", null, null,
            "INVALID", false, false, null, false, false, 3.0, false)
        assertEquals("dexPair", fill.bp.source)
        assertEquals(75.0, fill.bp.value!!, 1e-9)
        assertEquals(3.0 to 20.0, fill.chg.value)
        assertEquals("dexPair", fill.liq.source)
        // shape has no third source: it stays missing and names why
        assertNull(fill.shape.value)
        assertEquals("primary:INVALID|tape8100:NO_PRINTS", fill.shape.why)
        val line = SetupFeatureResolver8169.statusLine8169()
        assertTrue(line, line.startsWith("features8169[core=bp,chg,shape,liq complete=0/1 "))
        assertTrue(line, line.contains("bp[dexPair=1]"))
        assertTrue(line, line.contains("shape[primary:INVALID|tape8100:NO_PRINTS=1]"))
        assertTrue(line, line.contains("launchTape{- missing=NOT_PUMP_LAUNCH=1}"))
        assertTrue(line, line.contains("swaps{- missing=tape8100:NO_PRINTS=1}"))
        PairFlow8169.clearForTest8169()
        SetupFeatureResolver8169.clearForTest8169()
    }

    @Test fun launchTapeAbsenceIsNamed() {
        val r = SetupFeatureResolver8169
        assertEquals("PAST_LAUNCH_WINDOW", r.launchTapeWhy8169("Abcpump", 90.0))
        assertEquals("NOT_PUMP_LAUNCH", r.launchTapeWhy8169("AbcXYZ", 5.0))
        assertEquals("NO_BIRTH_WITNESSED", r.launchTapeWhy8169("Abcpump", Double.NaN))
    }

    // ── the trade tape: every received print reaches it, price-only pool ticks never become buy pressure ──

    @Test fun poolPriceTicksFeedChangeAndShapeButNeverBuyPressureOrExecutedPrints() {
        val tape = TradeTape8100
        tape.resetForTest8100()
        val t0 = 50_000_000L
        for (m in 0 until 8) tape.notePrice8169("TICK_8169", 1.0 + m * 0.05, TradeTape8100.SRC_PUMPSWAP_8169, t0 + m * 60_000L)
        val r = tape.read8100("TICK_8169", t0 + 7 * 60_000L + 1_000L)!!
        assertNull(r.bp)                                              // no side, no size: never buy pressure
        assertNotNull(r.shape)                                        // the pool's own price path
        assertTrue(tape.prints8128("TICK_8169", t0 + 8 * 60_000L, 60L * 60_000L).isEmpty())   // not an executed print
        // sized trades still make buy pressure, and the ticks do not dilute it
        for (k in 0 until 6) tape.note8100("TICK_8169", 1.4, 1.0, k < 3, t0 + 7 * 60_000L + k * 1_000L, TradeTape8100.SRC_HELIUS_8169)
        assertEquals(50.0, tape.read8100("TICK_8169", t0 + 7 * 60_000L + 10_000L)!!.bp!!, 1e-9)
        val line = tape.statusLine8169(t0 + 7 * 60_000L + 10_000L)
        assertTrue(line, line.contains("printsBySource{pumpswap=8,helius=6}"))
        assertTrue(line, line.startsWith("tape8169[mintsWithTape=1 fresh=1 "))
        assertEquals("NO_PRINTS", tape.absentWhy8169("NONE_8169", t0))
        assertEquals("STALE", tape.absentWhy8169("TICK_8169", t0 + 60L * 60_000L))
        tape.resetForTest8100()
    }

    @Test fun everyReceivedFeedReachesTheTape() {
        assertTrue(src("network/PumpFunWS.kt").contains("TradeTape8100.note8100(mint, priceSol, sol7743, txType == \"buy\")"))
        assertTrue(src("engine/truth/ProofDataSwapTape8144.kt").contains("TradeTape8100.note8100(mint, sol / tokenAmount, sol, isBuy, nowMs, TradeTape8100.SRC_HELIUS_8169)"))
        assertTrue(src("network/GradTicks8018.kt").contains("TradeTape8100.notePrice8169(mint, px, com.lifecyclebot.engine.truth.TradeTape8100.SRC_PUMPSWAP_8169)"))
        assertTrue(src("network/CurveTicks7968.kt").contains("TradeTape8100.notePrice8169(mint, px, com.lifecyclebot.engine.truth.TradeTape8100.SRC_CURVE_8169)"))
    }

    // ── the history the shape reads runs forwards ──

    @Test fun aLateLocalCandleGoesInAtItsMinuteAndARepeatedSnapshotIsNotAppendedTwice() {
        fun c(t: Long, p: Double) = Candle(t, p, 0.0, 0.0, 0.0)
        val h = ArrayDeque<Candle>()
        val s1 = c(now - 50_000L, 1.0)
        assertTrue(LocalCandleSynthesis7055.appendInOrder8169(h, s1))
        val s2 = c(now - 20_000L, 1.1)
        assertTrue(LocalCandleSynthesis7055.appendInOrder8169(h, s2))
        assertFalse(LocalCandleSynthesis7055.appendInOrder8169(h, s2))                   // the same cached snapshot again
        assertEquals(2, h.size)
        assertTrue(LocalCandleSynthesis7055.appendInOrder8169(h, c(now - 60_000L, 0.9)))   // a minute candle stamped at its start
        assertEquals(listOf(now - 60_000L, now - 50_000L, now - 20_000L), h.map { it.ts })
        assertTrue(LocalCandleSynthesis7055.appendInOrder8169(h, c(now, 1.2)))
        assertEquals(Obs8084.OBSERVED, LanePlaybook7907.shapeObs8084(h.toList(), now))
        // the same sequence appended blindly was the device's shape:INVALID
        val blind = listOf(s1, s2, s2, c(now - 60_000L, 0.9))
        assertEquals(Obs8084.INVALID, LanePlaybook7907.shapeObs8084(blind, now))
        assertEquals(1, LocalCandleSynthesis7055.orderedIndex8169(listOf(c(10L, 1.0), c(30L, 1.0)), 20L))
        assertEquals(2, LocalCandleSynthesis7055.orderedIndex8169(listOf(c(10L, 1.0), c(30L, 1.0)), 30L))
        // a full history drops its oldest point
        val full = ArrayDeque<Candle>()
        for (i in 0 until 5) LocalCandleSynthesis7055.appendInOrder8169(full, c(now + i, 1.0), max = 3)
        assertEquals(3, full.size)
    }

    // ── dead or cooling providers never block the fill ──

    @Test fun theShapeRepairAsksCodexAtMostOncePerMintPerTenMinutes() {
        val r = SetupFeatureResolver8169
        assertTrue(r.codexRepairDue8169(null, now))
        assertFalse(r.codexRepairDue8169(now - 60_000L, now))
        assertTrue(r.codexRepairDue8169(now - r.CODEX_REPAIR_GAP_MS_8169, now))
        val d = src("engine/DataOrchestrator.kt")
        // the 7809 seed contract is unchanged; Codex follows the keyless rung inside the repair only
        assertTrue(d.contains("keylessFetch7809(mint, \"1m\", 120, poolHint6916, attempts = 3)"))
        assertTrue(d.contains("SEED_RETRY_FLOOR_MS_7809 = 2_600L"))
        assertTrue(d.contains("if (repairShape8168 && repairShapeFromCodex8169(ts)) return"))
        assertTrue(d.contains("SetupHistoryRepair8168.canReplace(ts.history.toList(), bars, System.currentTimeMillis())"))
        assertTrue(src("engine/chart/ChartSources7955.kt").contains("fun codexCandles8169("))
    }

    @Test fun theFeaturesReadTheResolverAndTheReportCarriesBothLines() {
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("SetupFeatureResolver8169.fillFor8169(ts.mint, nowMs"))
        assertTrue(pb.contains("val tapeBp8100 = if (!bpKnown) tape8100?.bp else null"))
        assertTrue(pb.contains("val shapeKnown = shapeObs == Obs8084.OBSERVED"))
        assertTrue(pb.contains("\"bp\" to (if (tapeBp8100 != null || altBp8169 != null) Obs8084.OBSERVED else bpObs)"))
        val dx = src("network/DexscreenerApi.kt")
        assertTrue(dx.contains("PairFlow8169.notePair8169(tokenAddress, p, now)"))
        assertTrue(dx.contains("PairFlow8169.fromJson8169(p, batchAtMs8169)"))
        val cs = src("engine/chart/ChartSources7955.kt")
        assertTrue(cs.contains("SetupFeatureResolver8169.statusLine8169()"))
        assertTrue(cs.contains("TradeTape8100.statusLine8169(nowMs)"))
        assertTrue(src("engine/BotService.kt").contains("LocalCandleSynthesis7055.appendInOrder8169(ts.history, pair.candle)"))
    }
}
