package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.chart.LocalTapeBars8128
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.CandleCoverage8284
import com.lifecyclebot.engine.truth.CandleCoverage8284.Coverage8284
import com.lifecyclebot.engine.truth.CandleCoverage8284.Quality8284
import com.lifecyclebot.engine.truth.HeliusSwapCandles7819
import com.lifecyclebot.engine.truth.LiquidityTruth8037
import com.lifecyclebot.engine.truth.PriceShapeBars8287
import com.lifecyclebot.engine.truth.TradePlan7739
import com.lifecyclebot.engine.truth.TradeTape8100
import com.lifecyclebot.network.DexHostBudget8287
import com.lifecyclebot.network.PumpFunWS
import com.lifecyclebot.network.PumpSwapTape8307
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8317 (owner directive 8314, P3) — late entries: a fresh coin is read on the bars it can have.
 *  - age-aware minimums: CandleCoverage8284 / LocalTapeBars8128 / TradePlan7739 ask a launch-window coin for three
 *    minutes (PriceShapeBars8287.minMinutes8287); coins over 15 minutes keep today's numbers;
 *  - 15-second plan bars from the coin's own EXECUTED prints (no fabricated bar, no price-only print, no SOL price = none);
 *  - the local tape re-bins every 10 s for a launch;
 *  - an active *pump curve with a price but no trusted cap derives its depth from price x 1e9 — no other mint, no price-less mint.
 * EXIT_SAFE_HARD_FLOOR_USD_8140 ($500) is untouched.
 */
class Aate8317AgeAwareBarsTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val min = 60_000L

    @Test fun aLaunchWindowCoinNeedsThreeMinutesAndOlderCoinsKeepTodaysNumbers() {
        assertEquals(3, PriceShapeBars8287.ageAwareMin8317(4.0, 10))
        assertEquals(3, PriceShapeBars8287.ageAwareMin8317(14.9, 6))
        assertEquals(3, PriceShapeBars8287.ageAwareMin8317(4.0, 5))
        assertEquals(2, PriceShapeBars8287.ageAwareMin8317(4.0, 2))            // never above today's own number
        assertEquals(10, PriceShapeBars8287.ageAwareMin8317(60.0, 10))         // DEVELOPING keeps today's
        assertEquals(6, PriceShapeBars8287.ageAwareMin8317(600.0, 6))          // ESTABLISHED keeps today's
        assertEquals(6, PriceShapeBars8287.ageAwareMin8317(Double.NaN, 6))     // unknown age claims nothing
        // CandleCoverage8284: 1 m coverage of a 4-minute launch needs three bars; older / unknown six.
        assertEquals(3, CandleCoverage8284.min1mBars8317(4L * min))
        assertEquals(6, CandleCoverage8284.min1mBars8317(20L * min))
        assertEquals(6, CandleCoverage8284.min1mBars8317(null))
        // LocalTapeBars8128: three traded minutes for a launch, ten otherwise.
        assertEquals(3, LocalTapeBars8128.minMinutes8317(4.0))
        assertEquals(10, LocalTapeBars8128.minMinutes8317(600.0))
        assertEquals(10, LocalTapeBars8128.minMinutes8317(Double.NaN))
        // TradePlan7739: three bars for a launch, five otherwise.
        assertEquals(3, TradePlan7739.minBars8317(4.0))
        assertEquals(5, TradePlan7739.minBars8317(30.0))
        assertEquals(5, TradePlan7739.minBars8317(Double.NaN))
        assertTrue(src("engine/truth/CandleCoverage8284.kt").contains("BUCKET_MS, min1mBars8317(ageMs))"))
    }

    @Test fun oneMinuteCoverageOfAFourMinuteLaunchIsCoveredByThreeBars() {
        CandleCoverage8284.resetForTest8284(); TradeTape8100.resetForTest8100()
        val mint = "Cov8317aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val ts = TokenState(mint = mint)
        val now = (System.currentTimeMillis() / min) * min + 30_000L
        fun trade(ts: Long, px: Double) = Candle(ts = ts, priceUsd = px, marketCap = 0.0, volumeH1 = 10.0, volume24h = 0.0,
            buysH1 = 3, sellsH1 = 1, highUsd = px * 1.1, lowUsd = px * 0.9, openUsd = px * 0.95)
        assertEquals(Coverage8284.NOT_YET_POSSIBLE, CandleCoverage8284.coverage8284(ts, now, 2L * min)[1])   // 2 min old: not yet
        CandleCoverage8284.merge8284(ts, (1..3).map { trade(now - it * min, 1.0 + it) }, Quality8284.TRADE_OHLCV, "helius_enhanced_tx", "", now)
        assertEquals(Coverage8284.COVERED, CandleCoverage8284.coverage8284(ts, now, 4L * min)[1])            // a 4-minute launch: three bars cover
        assertEquals(Coverage8284.PARTIAL, CandleCoverage8284.coverage8284(ts, now, 20L * min)[1])          // at 20 min the same three are partial
        assertEquals(Coverage8284.PARTIAL, CandleCoverage8284.coverage8284(ts, now, null)[1])               // unknown age: today's six
        CandleCoverage8284.resetForTest8284()
    }

    @Test fun localTapeBarsOfALaunchNeedThreeTradedMinutesAndStillNeverFillAGap() {
        val t0 = 60_000L * 1_000L
        fun prt(m: Int, sec: Int, px: Double) = TradeTape8100.Print8128(t0 + m * 60_000L + sec * 1_000L, px, 1.0, true)
        val three = listOf(prt(0, 5, 1e-6), prt(1, 10, 1.1e-6), prt(2, 20, 1.2e-6))
        assertNull(LocalTapeBars8128.fromPrints8128(three, 150.0))                                   // today's ten still the default
        assertEquals(3, LocalTapeBars8128.fromPrints8128(three, 150.0, LocalTapeBars8128.minMinutes8317(4.0))!!.size)
        assertNull(LocalTapeBars8128.fromPrints8128(three.take(2), 150.0, LocalTapeBars8128.minMinutes8317(4.0)))
        // minutes 0, 1, 5: three traded minutes, three bars — minutes 2–4 are not invented
        val gappy = listOf(prt(0, 5, 1e-6), prt(1, 10, 1.1e-6), prt(5, 20, 1.2e-6))
        val bars = LocalTapeBars8128.fromPrints8128(gappy, 150.0, 3)!!
        assertEquals(listOf(0L, 1L, 5L), bars.map { (it.t - t0) / 60_000L })
        assertNull(LocalTapeBars8128.fromPrints8128(three, 0.0, 3))                                    // no SOL price: no USD bars
        val marks = (0 until 3).map { TradePlan7739.Bar(t0 + it * 60_000L, 1.0, 1.1, 0.9, 1.05) }
        assertNull(LocalTapeBars8128.fromMarks8128(marks))
        assertEquals(3, LocalTapeBars8128.fromMarks8128(marks, 3)!!.size)
    }

    @Test fun thePlanSetupsReadALaunchOnThreeBars() {
        val tp = TradePlan7739
        fun bar(i: Int, o: Double, h: Double, l: Double, c: Double) = TradePlan7739.Bar(i * 60_000L, o, h, l, c)
        val micro = tp.tierFor(50_000.0)
        val pb = listOf(bar(0, 1.0, 1.02, 0.98, 1.0), bar(1, 1.0, 1.25, 1.0, 1.22), bar(2, 1.22, 1.50, 1.2, 1.45), bar(3, 1.45, 1.46, 1.30, 1.32))
        assertEquals("TOO_FEW_BARS", tp.pullbackReclaim(pb, micro).why)                               // today's five
        assertNotEquals("TOO_FEW_BARS", tp.pullbackReclaim(pb, micro, tp.minBars8317(4.0)).why)      // a launch: read
        assertEquals("TOO_FEW_BARS", tp.pullbackReclaim(pb.take(2), micro, 3).why)                    // two bars are never a shape
        assertEquals("TOO_FEW_BARS", tp.baseBreakout(pb.take(3), micro, false, 3).why)
        assertNotEquals("TOO_FEW_BARS", tp.baseBreakout(pb, micro, false, 3).why)
        assertNotEquals("TOO_FEW_BARS", tp.sweepReclaim(pb, micro, 3).why)
        assertEquals("TOO_FEW_BARS", tp.analyze(pb, micro, false, 1, 1).why)
        assertNotEquals("TOO_FEW_BARS", tp.analyze(pb, micro, false, 1, 1, 3).why)
        val plan = src("engine/truth/TradePlan7739.kt")
        assertTrue(plan.contains("private const val MIN_BARS_7739 = 5"))                               // older coins keep today's
        val read = plan.substringAfter("fun readForEntry7837(").substringBefore("// ── entry ──")
        assertTrue(read.contains("barsFrom(ts, nowMs, entry8317 = true)"))
        assertTrue(read.contains("minBars8317(age8317)"))
        // the exit trail still reads one-minute bars
        val trail = plan.substringAfter("fun trailBroken(").substringBefore("fun onExit(")
        assertTrue(trail.contains("barsFrom(ts, nowMs)"))
    }

    @Test fun fifteenSecondBarsComeOnlyFromExecutedPrintsOfTheCoin() {
        TradeTape8100.resetForTest8100()
        val mint = "Young8317aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val t0 = 1_800_000_000_000L
        // executed prints in buckets 0, 1, 3, 4, 5, 6 (bucket 2 untraded; a price-only print in bucket 7)
        for ((sec, px) in listOf(2 to 1.0e-6, 14 to 1.1e-6, 20 to 1.2e-6, 50 to 1.3e-6, 61 to 1.25e-6, 77 to 1.3e-6, 92 to 1.4e-6)) {
            TradeTape8100.note8100(mint, px, 0.5, true, t0 + sec * 1_000L)
        }
        TradeTape8100.notePrice8169(mint, 9e-6, TradeTape8100.SRC_CURVE_8169, t0 + 110_000L)
        val prints = TradeTape8100.prints8128(mint, t0 + 120_000L, 30L * 60_000L)
        val bars = TradePlan7739.bars15s8317(prints, 100.0)
        assertEquals(listOf(0L, 1L, 3L, 4L, 5L, 6L), bars.map { (it.startMs - t0) / TradePlan7739.YOUNG_BAR_MS_8317 })
        assertEquals(1.0e-6 * 100.0, bars[0].open, 1e-12)
        assertEquals(1.1e-6 * 100.0, bars[0].high, 1e-12)
        assertEquals(1.1e-6 * 100.0, bars[0].close, 1e-12)
        assertEquals(1.0e-6 * 100.0, bars[0].low, 1e-12)
        assertTrue(bars.none { it.high >= 9e-6 * 100.0 })                                         // the curve snapshot is not a bar
        assertTrue(TradePlan7739.bars15s8317(prints, 0.0).isEmpty())                               // no SOL price: no bars
        val po = TradeTape8100.observedPrints8287(mint, t0 + 120_000L, 30L * 60_000L)!!.filter { it.priceOnly }
        assertTrue(TradePlan7739.bars15s8317(po, 100.0).isEmpty())                                 // price-only prints alone: nothing
        assertEquals(15_000L, TradePlan7739.YOUNG_BAR_MS_8317)
        val plan = src("engine/truth/TradePlan7739.kt")
        val from = plan.substringAfter("private fun barsFrom(").substringBefore("private fun localTapeBars8136(")
        assertTrue(from.contains("if (entry8317) youngBars8317(ts.mint, nowMs, age8317)?.takeIf { it.size > merged.size }?.let { return it }"))
        val young = plan.substringAfter("private fun youngBars8317(").substringBefore("// ── setups (pure) ──")
        assertTrue(young.contains("Lifecycle8283.NEW"))
        assertTrue(young.contains("if (bars.size < MIN_BARS_7739 + 1) null"))
        TradeTape8100.resetForTest8100()
    }

    @Test fun theLocalTapeRebinsEveryTenSecondsForALaunch() {
        assertEquals(10_000L, HeliusSwapCandles7819.localTapeGapMs8317(4.0))
        assertEquals(10_000L, HeliusSwapCandles7819.localTapeGapMs8317(14.0))
        assertEquals(30_000L, HeliusSwapCandles7819.localTapeGapMs8317(15.0))
        assertEquals(30_000L, HeliusSwapCandles7819.localTapeGapMs8317(Double.NaN))
        assertTrue(src("engine/truth/HeliusSwapCandles7819.kt").contains("if (nowMs - last in 0L until localTapeGapMs8317(ageMin8317(ts, nowMs))) return null"))
    }

    @Test fun anActivePumpCurveWithAPriceDerivesItsCapFromThePriceOnlyWhenTheTrustedCapIsMissing() {
        val lt = LiquidityTruth8037
        assertEquals(5_000.0, lt.curveCapUsd8317("Abcpump", 5_000.0, 0.00001), 1e-9)          // trusted cap first
        assertEquals(10_000.0, lt.curveCapUsd8317("Abcpump", 0.0, 0.00001), 1e-6)             // row cap 0: price x 1e9
        assertEquals(10_000.0, lt.curveCapUsd8317("Abcpump", Double.NaN, 0.00001), 1e-6)
        assertTrue(lt.curveCapUsd8317("AbcXYZ", Double.NaN, 0.00001).isNaN())                   // not a *pump mint: nothing derived
        assertTrue(lt.curveCapUsd8317("Abcpump", Double.NaN, 0.0).isNaN())                      // no price: nothing derived
        assertTrue(lt.curveCapUsd8317("Abcpump", Double.NaN, Double.NaN).isNaN())
        // the depth still follows the curve invariant and its active-curve ceiling
        assertNotNull(lt.curveDepthUsd8164(lt.curveCapUsd8317("Abcpump", Double.NaN, 0.00001), 106.0))
        assertNull(lt.curveDepthUsd8164(lt.curveCapUsd8317("AbcXYZ", Double.NaN, 0.00001), 106.0))
        val s = src("engine/truth/LiquidityTruth8037.kt")
        val f = s.substringAfter("private fun curveDepthFromCap8164(").substringBefore("private val capFromPrice8317")
        assertTrue(f.contains("PumpFunDirectApi.isPumpFunMint(mint)"))                           // the *pump gate stays first
        assertTrue(f.contains("val capUsd = curveCapUsd8317(mint, trusted, ts.lastPrice)"))
        // the $500 live liquidity floor is the owner's and is not lowered
        assertTrue(src("engine/LaneAgeLiquidityScope8140.kt").contains("EXIT_SAFE_HARD_FLOOR_USD_8140 = 500.0"))
    }
}

/**
 * V5.0.8317 (owner directive 8314, P4) — data holes: a launch is near a decision from its first sighting, the chain depth
 * read starts at first sight, the DexScreener bulk reserve is 2, a setup-watched coin is exempt from the PumpPortal
 * frame cap, the Helius parsed tape keeps at least six coins and halves only past 3 MB/min, a PumpSwap pool created on
 * chain is known to the program-stream tape, and a tape print stays fresh for the whole buy-pressure window.
 */
class Aate8317DataHolesTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun aLaunchInsideItsWindowOrOfUnresolvedAgeIsNearADecisionAtIntake() {
        val i = IntakeFirstSight8317
        assertTrue(i.insideLaunchWindow8317(null))
        assertTrue(i.insideLaunchWindow8317(0L))
        assertTrue(i.insideLaunchWindow8317(i.LAUNCH_WINDOW_MS_8317 - 1L))
        assertFalse(i.insideLaunchWindow8317(i.LAUNCH_WINDOW_MS_8317))
        assertEquals(30L * 60_000L, i.LAUNCH_WINDOW_MS_8317)
        DataPipeline8191.resetForTests8191()
        val now = System.currentTimeMillis()
        val young = "Young8317bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val old = "Old8317ccccccccccccccccccccccccccccccccccccc"   // 44 chars: a valid mint length
        AdmissionBirth8144.noteChainMintInit8179(young, now - 5L * 60_000L)       // born five minutes ago on chain
        AdmissionBirth8144.noteChainMintInit8179(old, now - 3L * 3_600_000L)      // born three hours ago
        assertTrue(i.noteIntake8317(young, now))
        assertTrue(DataPipeline8191.nearDecision8191(young, now))
        assertFalse(i.noteIntake8317(old, now))
        assertFalse(DataPipeline8191.nearDecision8191(old, now))
        assertFalse(i.noteIntake8317("", now))
        assertTrue(i.statusLine8317().startsWith("intake8317[nearMarked="))
        DataPipeline8191.resetForTests8191()
        // wired at the two first sightings: the merge queue's first entry and the TokenState's creation
        val q = src("engine/TokenMergeQueue.kt")
        val fresh = q.substringAfter("pendingDiscoveries[mint] = MergeEntry(").substringBefore("ErrorLogger.debug(")
        assertTrue(fresh.contains("IntakeFirstSight8317.noteIntake8317(mint, now)"))
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("IntakeFirstSight8317.firstSight8317(fresh, laneAffinity, toolAffinity)"))
        assertFalse(bs.contains("fresh.laneAffinity.addAll(laneAffinity)"))
        val fs = src("engine/IntakeFirstSight8317.kt")
        assertTrue(fs.contains("fresh.laneAffinity.addAll(laneAffinity)") && fs.contains("fresh.toolAffinity.addAll(toolAffinity)"))
        assertTrue(fs.contains("OnChainDepth8038.needDepth8047(fresh)"))
        assertTrue(src("engine/DataPipeline8191.kt").contains("IntakeFirstSight8317.statusLine8317()"))
    }

    @Test fun firstSightAsksTheChainForDepthOnlyWhenNoneIsKnown() {
        // non-curve mints with a chain birth: no curve batch and no birth walk leave the test JVM
        val now = System.currentTimeMillis()
        val ts = TokenState(mint = "Depth8317dddddddddddddddddddddddddddddddddddd")
        AdmissionBirth8144.noteChainMintInit8179(ts.mint, now - 60_000L)
        ts.lastLiquidityUsd = 2_500.0
        IntakeFirstSight8317.firstSight8317(ts, setOf("SHITCOIN"), setOf("SNIPE"))
        assertTrue(ts.laneAffinity.contains("SHITCOIN") && ts.toolAffinity.contains("SNIPE"))
        assertTrue(DataPipeline8191.nearDecision8191(ts.mint, System.currentTimeMillis()))   // one minute old: near a decision (read at or after the mark)
        val line = IntakeFirstSight8317.statusLine8317()
        val asked = line.substringAfter("depthAsked=").substringBefore("]").toLong()
        val ts2 = TokenState(mint = "Depth8317eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
        AdmissionBirth8144.noteChainMintInit8179(ts2.mint, now - 60_000L)
        IntakeFirstSight8317.firstSight8317(ts2, emptySet(), emptySet())
        val asked2 = IntakeFirstSight8317.statusLine8317().substringAfter("depthAsked=").substringBefore("]").toLong()
        assertEquals(asked + 1L, asked2)
    }

    @Test fun theBulkReserveIsTwoAndDecisionReadsAreStillNeverStarved() {
        assertEquals(2.0, DexHostBudget8287.BULK_RESERVE_8287, 1e-12)
        val b = DexHostBudget8287.Bucket8287()
        var bulk = 0
        while (b.take8287(0L, DexHostBudget8287.Prio8287.BULK) == 0L) bulk++
        assertEquals((DexHostBudget8287.BURST_8287 - 2.0).toInt(), bulk)           // bulk stops with two tokens left
        assertEquals(0L, b.take8287(0L, DexHostBudget8287.Prio8287.NEAR))
        assertEquals(0L, b.take8287(0L, DexHostBudget8287.Prio8287.HELD))
    }

    @Test fun aSetupWatchedCoinIsExemptFromTheFrameCapButNotFromTheDaysBudget() {
        val cap = PumpFunWS.LAUNCH_FRAME_CAP_8192
        val budget = PumpFunWS.DAILY_FRAME_BUDGET_8192
        assertTrue(PumpFunWS.dropLaunch8317(held = false, setupWatched = false, mintFrames = cap, framesToday = 10L))
        assertFalse(PumpFunWS.dropLaunch8317(held = false, setupWatched = true, mintFrames = 10 * cap, framesToday = 10L))
        assertTrue(PumpFunWS.dropLaunch8317(held = false, setupWatched = true, mintFrames = 1L, framesToday = budget))
        assertFalse(PumpFunWS.dropLaunch8317(held = true, setupWatched = false, mintFrames = 10 * cap, framesToday = 10 * budget))
        assertFalse(PumpFunWS.dropLaunch8317(held = false, setupWatched = false, mintFrames = cap - 1, framesToday = 10L))
        assertTrue(PumpFunWS.capBlocks8317(cap, held = false, setupWatched = false))
        assertFalse(PumpFunWS.capBlocks8317(cap, held = false, setupWatched = true))
        assertFalse(PumpFunWS.capBlocks8317(cap, held = true, setupWatched = false))
        assertFalse(PumpFunWS.capBlocks8317(cap - 1, held = false, setupWatched = false))
        val ws = src("network/PumpFunWS.kt")
        val frame = ws.substringAfter("private fun noteTradeFrame8192(").substringBefore("fun costLine8192()")
        assertTrue(frame.contains("if (!dropLaunch8317(held, setupWatched8191(mint, now), mintFrames, today)) return"))
        val metered = ws.substringAfter("private fun meteredSubAllowed8193(").substringBefore("private fun registerFreshLifecycle7420(")
        assertTrue(metered.contains("capBlocks8317(framesByMint8192[mint]?.get() ?: 0L, mint in heldMints8192, setup8317 || setupWatched8191(mint, now))"))
        assertTrue(metered.contains("launchSubAllowed8192(framesToday8192.get())"))                 // the daily budget still binds
        // a fresh (non-setup) launch registration keeps the cap
        val fresh = ws.substringAfter("private fun registerFreshLifecycle7420(").substringBefore("lifecycleMints7420[mint] = now")
        assertTrue(fresh.contains("(framesByMint8192[mint]?.get() ?: 0L) >= LAUNCH_FRAME_CAP_8192"))
    }

    @Test fun theParsedTapeKeepsSixCoinsAndHalvesOnlyPastThreeMegabytesAMinute() {
        assertEquals(12, PumpSwapTape8307.nextTapeWidth8307(12, 2_000_000L))      // 2 MB no longer halves
        assertEquals(6, PumpSwapTape8307.nextTapeWidth8307(12, 3_200_000L))
        assertEquals(6, PumpSwapTape8307.nextTapeWidth8307(6, 9_000_000L))        // the floor
        assertEquals(6, PumpSwapTape8307.nextTapeWidth8307(2, 9_000_000L))
        assertEquals(6, PumpSwapTape8307.nextTapeWidth8307(4, 100_000L))
        assertEquals(12, PumpSwapTape8307.nextTapeWidth8307(11, 100_000L))
        assertEquals(12, PumpSwapTape8307.TAPE_MAX_8307)
        // over the day's pace only held coins stay, whatever the width
        val held = listOf("H1".padEnd(44, 'h'), "H2".padEnd(44, 'h'))
        assertEquals(held, PumpSwapTape8307.tapeSet8307(held, (3..20).map { "N$it".padEnd(44, 'n') }, 12, true))
    }

    @Test fun aPumpSwapPoolCreatedOnChainIsKnownToTheProgramStreamTape() {
        val feed = src("network/ChainLaunchFeed8179.kt")
        val deliver = feed.substringAfter("private fun deliver8179(").substringBefore("private fun notePumpSwapPool8317(")
        assertTrue(deliver.indexOf("notePumpSwapPool8317(l)") in 0 until deliver.indexOf("if (seenMints.contains8179(l.mint))"))
        val note = feed.substringAfter("private fun notePumpSwapPool8317(").substringBefore("private fun recordBirth8179(")
        assertTrue(note.contains("if (l.venue != Venue8179.PUMPSWAP || l.pool.isBlank() || l.mint.isBlank()) return"))
        assertTrue(note.contains("PumpSwapTape8307.notePoolMint8307(l.pool, l.mint)"))
    }

    @Test fun aTapePrintStaysFreshForTheWholeBuyPressureWindow() {
        TradeTape8100.resetForTest8100()
        val mint = "Fresh8317fffffffffffffffffffffffffffffffffff"
        val t0 = 1_800_000_000_000L
        TradeTape8100.note8100(mint, 1e-6, 0.5, true, t0)
        assertEquals("TOO_FEW", TradeTape8100.absentWhy8169(mint, t0 + 4L * 60_000L))      // 4 min: still fresh (was STALE at 2)
        assertNotNull(TradeTape8100.read8100(mint, t0 + 4L * 60_000L))
        assertEquals("STALE", TradeTape8100.absentWhy8169(mint, t0 + 5L * 60_000L + 1L))
        assertNull(TradeTape8100.read8100(mint, t0 + 5L * 60_000L + 1L))
        assertTrue(src("engine/truth/TradeTape8100.kt").contains("private const val FRESH_MS = 300_000L"))
        TradeTape8100.resetForTest8100()
    }
}
