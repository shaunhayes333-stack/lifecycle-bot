package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.chart.ChartSources7955
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.market.LaunchTape7921
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.CandidateMarkFresh8094
import com.lifecyclebot.engine.truth.CandleCoverage8284
import com.lifecyclebot.engine.truth.CandleCoverage8284.Quality8284
import com.lifecyclebot.engine.truth.LifecycleData8283
import com.lifecyclebot.engine.truth.PriceShapeBars8287
import com.lifecyclebot.engine.truth.SetupFeatureResolver8169
import com.lifecyclebot.engine.truth.TradeTape8100
import com.lifecyclebot.network.ChainLaunchFeed8179
import com.lifecyclebot.network.DexHostBudget8287
import com.lifecyclebot.network.DexHostBudget8287.Prio8287
import com.lifecyclebot.network.DexPools8285
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8287 — data delivery repairs from the live 5.0.8286 evidence:
 *  1. DexScreener: one host budget for every caller (held / near-decision first) and one 30-mint batch lane on a
 *     steady cadence; a local refusal is never read as the provider's answer.
 *  2. Shape: observed PRICE prints (curve snapshots included) build MARK_ONLY / TRADE_AGGREGATED one-minute bars,
 *     lifecycle-aware, never volume or buy pressure, never an interpolated minute.
 *  3. Launch evidence: a NEW launch's bp its own observed trades cannot state yet is recorded, not binding; bp from the
 *     launch's own first trades; birth walks no longer queue behind report-only side effects.
 *  4. Birdeye CU is charged for requests that reach Birdeye, not for attempts answered locally; the cap is kept.
 *  5. Codex without a key reports NO_KEY and does not poll.
 */
class Aate8287DataDeliveryTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private fun mint(tag: String) = ("M8287" + tag + "x".repeat(40)).take(44)
    private val min = 60_000L

    // ── 1. DexScreener host budget and batch lane ──

    @Test fun bulkCallersNeverSpendTheDecisionReserveAndExitsAreNeverRefused() {
        val b = DexHostBudget8287.Bucket8287(perMin = 60, burst = 6.0, reserve = 2.0)
        val t0 = 1_000_000L
        var bulk = 0
        while (b.take8287(t0, Prio8287.BULK) == 0L) bulk++
        assertEquals(4, bulk)                                         // stops with the reserve (2 tokens) left
        assertEquals(0L, b.take8287(t0, Prio8287.NEAR))               // a near-decision read still goes
        assertEquals(0L, b.take8287(t0, Prio8287.HELD))
        assertEquals(1_000L, b.take8287(t0, Prio8287.NEAR))           // empty: waits for the next token (1/s), bounded
        assertEquals(-1L, b.take8287(t0, Prio8287.NEAR))              // beyond PRIORITY_WAIT_MS_8287: refused, not queued
        assertEquals(0L, b.take8287(t0, Prio8287.EXIT))               // held-mark exit traffic is never refused
        // a provider 429 pauses every non-exit caller for its Retry-After
        b.noteLimited8287(t0, DexHostBudget8287.retryAfterMs8287("10"))
        assertEquals(-1L, b.take8287(t0 + 5_000L, Prio8287.HELD))
        assertEquals(0L, b.take8287(t0 + 5_000L, Prio8287.EXIT))
        assertEquals(0L, b.take8287(t0 + 11_000L, Prio8287.NEAR))
        assertTrue(DexHostBudget8287.PRIORITY_WAIT_MS_8287 in 1_000L..2_000L)
    }

    @Test fun allDexScreenerCallersTogetherStayWellUnderTheDocumentedLimit() {
        val b = DexHostBudget8287.Bucket8287()
        var bulk = 0
        var nearRefused = 0
        var t = 0L
        while (t < 60_000L) {
            if (b.take8287(t, Prio8287.BULK) == 0L) bulk++           // a scanner hammering every 10 ms
            if (t % 1_000L == 0L && b.take8287(t, Prio8287.NEAR) < 0L) nearRefused++
            t += 10L
        }
        assertTrue("bulk=$bulk", bulk <= DexHostBudget8287.PER_MIN_8287 + DexHostBudget8287.BURST_8287.toInt())
        assertTrue(DexHostBudget8287.PER_MIN_8287 + DexHostBudget8287.BURST_8287 < 300.0)   // DexScreener ~300 req/min
        assertTrue(DexHostBudget8287.BULK_RESERVE_8287 >= 1.0)
        assertEquals("a decision coin's read is never starved by bulk", 0, nearRefused)
        assertEquals(Prio8287.EXIT, DexHostBudget8287.prioOf8287("BULK", exit = true))
        assertEquals(Prio8287.HELD, DexHostBudget8287.prioOf8287("HELD", exit = false))
        assertEquals(Prio8287.NEAR, DexHostBudget8287.prioOf8287("near", exit = false))
        assertEquals(Prio8287.BULK, DexHostBudget8287.prioOf8287(null, exit = false))
        assertEquals("NEAR", DexHostBudget8287.headerFor8287(held = false, near = true))
        assertEquals(15_000L, DexHostBudget8287.retryAfterMs8287(null))
        assertEquals("api.dexscreener.com", DexHostBudget8287.HOST_8287)
        // the shared client applies it to every caller, before the circuit (a local refusal is never a provider failure)
        val shared = src("network/SharedHttpClient.kt")
        assertTrue(shared.indexOf(".addInterceptor(com.lifecyclebot.network.DexRateInterceptor8287)") in 0 until
            shared.indexOf(".addInterceptor(com.lifecyclebot.network.HostCircuitInterceptor)"))
        assertTrue(DexHostBudget8287.statusLine8287(0L).startsWith("dexHost8287[perMin=180 "))
    }

    @Test fun theBatchLaneCollectsUpToThirtyMintsPerCallOnASteadyCadence() {
        val lane = DexBatchLane8287
        assertEquals(-1L, lane.waitMs8287(0, 0L, 99_999L, false))
        assertEquals(lane.COLLECT_MS_8287, lane.waitMs8287(1, 0L, 99_999L, false))     // a lone mint waits for company
        assertEquals(0L, lane.waitMs8287(DexPools8285.BATCH_MAX_8285, 0L, 99_999L, false))   // a full batch goes now
        assertEquals(0L, lane.waitMs8287(1, 0L, 99_999L, true))                        // a held mint never waits to collect
        assertEquals(800L, lane.waitMs8287(30, 0L, 200L, true))                        // but the cadence holds
        assertTrue(lane.MAX_CALLS_PER_MIN_8287 <= 60)
        assertEquals(60_000L, lane.MIN_SPACING_MS_8287 * lane.MAX_CALLS_PER_MIN_8287)
        // one minute of mints arriving one every 100 ms: <= 60 calls, ~10 mints per call (8286: ~1 mint per "batch")
        val pending = ArrayDeque<Long>()
        var next = 0L; var last = -999_999L; var calls = 0; var sent = 0; var t = 0L
        while (t < 60_000L) {
            while (next <= t) { pending.addLast(next); next += 100L }
            val w = lane.waitMs8287(pending.size, if (pending.isEmpty()) 0L else t - pending.first(), t - last, false)
            if (w == 0L) {
                val n = minOf(DexPools8285.BATCH_MAX_8285, pending.size)
                repeat(n) { pending.removeFirst() }
                calls++; sent += n; last = t
            }
            t += 10L
        }
        assertTrue("calls=$calls", calls in 50..60)
        assertTrue("perCall=${sent / calls}", sent / calls >= 8)
    }

    @Test fun theLaneWaitsOutTheProvidersCooldownAndALocalRefusalNeverClimbsIt() {
        val l = ProviderLanes8285
        l.resetForTests8285()
        val t0 = 5_000_000L
        assertEquals(0L, l.coolingLeftMs8287(l.DEX_8285, t0))
        assertTrue(l.enqueue8285(l.DEX_8285, l.KIND_PAIR_8285, mint("a"), ProviderLanes8285.Prio8285.BULK, t0))
        assertTrue(l.enqueue8285(l.DEX_8285, l.KIND_PAIR_8285, mint("b"), ProviderLanes8285.Prio8285.NEAR_DECISION, t0 + 300L))
        assertFalse(l.heldPending8287(l.DEX_8285, l.KIND_PAIR_8285))
        assertEquals(700L, l.oldestPendingAgeMs8287(l.DEX_8285, l.KIND_PAIR_8285, t0 + 700L))
        assertTrue(l.enqueue8285(l.DEX_8285, l.KIND_PAIR_8285, mint("c"), ProviderLanes8285.Prio8285.HELD, t0 + 400L))
        assertTrue(l.heldPending8287(l.DEX_8285, l.KIND_PAIR_8285))
        l.noteResult8285(l.DEX_8285, 429, t0)
        assertEquals(l.cooldownMs8285(0), l.coolingLeftMs8287(l.DEX_8285, t0))
        l.noteResult8285(l.GECKO_8285, 403, t0)
        assertEquals(Long.MAX_VALUE, l.coolingLeftMs8287(l.GECKO_8285, t0))
        l.resetForTests8285()
        // the batch: a synthetic (local) answer returns LIMITER_REFUSED before the provider's ladder is touched
        val d = src("network/DexscreenerApi.kt")
        val batch = d.substringAfter("fun batchPairs8285(").substringBefore("/**")
        assertTrue(batch.contains("RateLimiter.waitForSlot(\"dexscreener\", BATCH_SLOT_WAIT_MS_8287)"))
        assertTrue(batch.indexOf("if (code[0] == SYNTHETIC_CODE_8287) return BatchPairs8285(emptySet(), want, BatchCause8087.LIMITER_REFUSED)") in
            0 until batch.indexOf("lanes.noteResult8285(lanes.DEX_8285, code[0], nowMs)"))
        assertTrue(d.contains("HostCircuitInterceptor.isSyntheticBlock(resp)) code8285[0] = SYNTHETIC_CODE_8287"))
        // the lane waits the cooldown out instead of counting every pending mint throttled per retry
        val lane = src("engine/DexBatchLane8287.kt")
        assertTrue(lane.contains("val cooling = lanes.coolingLeftMs8287(lanes.DEX_8285, now)"))
        assertTrue(lane.contains("cooldownWaits.incrementAndGet()"))
    }

    @Test fun singleMintLookupsAndCandidateMarksRideTheBatch() {
        val d = src("network/DexscreenerApi.kt")
        // one pair cache for every instance, filled by each batch answer
        assertTrue(d.contains("private val pairCache = SHARED_PAIR_CACHE_8287"))
        assertTrue(d.contains("cacheBatchPairs8287(rows, json, nowMs, prices8287)"))
        assertTrue(d.contains("pairCache[\"solana|\$mint\"] = CachedPair(pair, nowMs)"))
        // a bulk read with a recent answer joins the lane; a local refusal is never cached as "no pair"
        val best = d.substringAfter("private fun getBestPairInternal(").substringBefore("/** Search by query string")
        assertTrue(best.contains("if (chainId == \"solana\" && prio8287 == \"BULK\" && cached != null && now - cached.timestamp < CACHE_TTL_MS * 3) {"))
        assertTrue(best.contains("com.lifecyclebot.engine.DexBatchLane8287.enqueue8287(tokenAddress)"))
        assertTrue(best.indexOf("return cached?.pair") in 0 until best.indexOf("pairCache[cacheKey] = CachedPair(result"))
        // setup features: the orchestrator's pair fetch enqueues into the same lane
        assertTrue(src("engine/DataOrchestrator.kt").contains("private fun drainPairLane8285() = DexBatchLane8287.kick8287()"))
        // candidate marks: no second 30-mint reader on the host
        val fresh = src("engine/truth/CandidateMarkFresh8094.kt")
        assertFalse(fresh.contains("api.dexscreener.com"))
        assertTrue(fresh.contains("com.lifecyclebot.engine.DexBatchLane8287.enqueue8287(m)"))
        assertTrue(src("engine/DexBatchLane8287.kt").contains("CandidateMarkFresh8094.onLaneAnswer8287(batch, r.prices8287)"))
        // an answer for a mint this refresh never asked for changes nothing
        val before = CandidateMarkFresh8094.statusLine().substringAfter("dexReads=").substringBefore(' ')
        CandidateMarkFresh8094.onLaneAnswer8287(listOf(mint("unasked")), mapOf(mint("unasked") to 0.001))
        assertEquals(before, CandidateMarkFresh8094.statusLine().substringAfter("dexReads=").substringBefore(' '))
        assertTrue(DexBatchLane8287.statusLine8287().startsWith("dexLane8287[calls="))
        assertTrue(DataPipeline8191.statusLine8191().contains("dexHost8287["))
    }

    // ── 2. Shape from observed price prints ──

    @Test fun priceOnlyPrintsBuildMarkOnlyBarsWithNoVolumeOrBuyPressure() {
        val t0 = 100L * min
        val p = listOf(
            TradeTape8100.Print8128(t0 + 1_000L, 1.0e-6, 0.5, true),
            TradeTape8100.Print8128(t0 + 30_000L, 1.1e-6, 0.2, false),
            TradeTape8100.Print8128(t0 + min + 5_000L, 1.2e-6, 0.0, false, "curve", "", priceOnly = true),
            TradeTape8100.Print8128(t0 + min + 40_000L, 1.3e-6, 9.9, true, "curve", "", priceOnly = true),   // size ignored
            TradeTape8100.Print8128(t0 + 3 * min + 1_000L, 1.4e-6, 0.3, true),
            TradeTape8100.Print8128(t0 + 3 * min + 50_000L, 1.6e-6, 0.0, false, "curve", "", priceOnly = true),
        )
        val bars = PriceShapeBars8287.bars8287(p)
        assertEquals(listOf(t0, t0 + min, t0 + 3 * min), bars.map { it.minuteMs })   // minute +2 was not observed: no bar
        val mark = bars[1]
        assertEquals(Quality8284.MARK_ONLY, mark.quality)
        assertEquals(0.0, mark.volumeSol, 0.0); assertEquals(0, mark.buys); assertEquals(0, mark.sells)
        assertEquals(1.3e-6, mark.high, 1e-15)
        val mixed = bars[2]
        assertEquals(Quality8284.TRADE_AGGREGATED, mixed.quality)
        assertEquals(1.6e-6, mixed.high, 1e-15)                      // an observed price states the high
        assertEquals(0.3, mixed.volumeSol, 1e-12)                    // only the executed print is volume
        assertEquals(1, mixed.buys); assertEquals(0, mixed.sells)
        val c = PriceShapeBars8287.candle8287(mark, 150.0)
        assertEquals(0.0, c.volumeH1, 0.0); assertEquals(0.0, c.volume24h, 0.0)
        assertEquals(0, c.buysH1); assertEquals(0, c.sellsH1)
        assertEquals(1.3e-6 * 150.0, c.highUsd, 1e-12)
        assertFalse(c.synthetic)
    }

    @Test fun theSeriesAShapeNeedsDependsOnTheTokensLifecycle() {
        assertEquals(3, PriceShapeBars8287.minMinutes8287(4.0))        // a 4-minute launch: three observed minutes
        assertEquals(6, PriceShapeBars8287.minMinutes8287(60.0))
        assertEquals(10, PriceShapeBars8287.minMinutes8287(600.0))
        assertEquals(10, PriceShapeBars8287.minMinutes8287(Double.NaN)) // unknown age claims nothing
        val t0 = 200L * min
        val p = (0 until 4).map { TradeTape8100.Print8128(t0 + it * min + 10_000L, 1e-6 * (1 + it * 0.1), 0.0, false, "curve", "", priceOnly = true) }
        val young = PriceShapeBars8287.series8287(p, 150.0, PriceShapeBars8287.minMinutes8287(4.0))
        assertNotNull(young)
        assertEquals(4, young!!.size)
        assertTrue(young.all { CandleCoverage8284.qualityOf8284(it) == Quality8284.MARK_ONLY && it.volumeH1 == 0.0 })
        assertTrue(young.all { CandleCoverage8284.tagOf8284(it)?.source == PriceShapeBars8287.SOURCE_8287 })
        assertNull(PriceShapeBars8287.series8287(p, 150.0, PriceShapeBars8287.minMinutes8287(600.0)))
        assertNull(PriceShapeBars8287.series8287(p, 0.0, 3))           // no SOL price at all: no USD bars
        // the provider alternate reads the venue's observed prices with the lifecycle's minimum (8286: a=1475 r=0)
        val feed = src("network/SolanaOhlcvFeed6916.kt")
        assertTrue(feed.contains("val c = observedCandles8287(mint, n, nowMs) ?: return null"))
        assertTrue(feed.contains("shape.series8287(prints, shape.solUsd8287(nowMs), shape.minMinutes8287(shape.ageMin8287(mint, nowMs)))"))
    }

    @Test fun curveOnlyPricesMakeTheShapeResolvableWithoutAnInterpolatedMinute() {
        TradeTape8100.resetForTest8100()
        CandleCoverage8284.resetForTest8284()
        val m = mint("curve")
        val nowL = (System.currentTimeMillis() / min) * min + 30_000L
        for (k in listOf(6L, 5L, 4L, 2L)) {
            TradeTape8100.notePrice8169(m, 1e-6 * (10 - k), TradeTape8100.SRC_CURVE_8169, nowL - k * min)
            TradeTape8100.note8100(m, 1e-6 * (10 - k) * 1.01, 0.4, true, nowL - k * min + 20_000L, TradeTape8100.SRC_CURVE_8169)
        }
        assertTrue("price-only prints are never executed prints", TradeTape8100.prints8128(m, nowL, 30 * min).isEmpty())
        val observed = TradeTape8100.observedPrints8287(m, nowL, 30 * min)!!
        assertEquals(8, observed.size)
        assertTrue(observed.all { it.priceOnly && it.sol == 0.0 && !it.isBuy })
        val ts = TokenState(mint = m)
        val changed = PriceShapeBars8287.mergePriceOnly8287(ts, nowL, 30 * min, solUsd = 100.0)
        assertEquals(4, changed)
        assertEquals(listOf(6L, 5L, 4L, 2L).map { (nowL - it * min) / min }, ts.history.map { it.ts / min })   // -3 stays empty
        assertTrue(ts.history.all { CandleCoverage8284.qualityOf8284(it) == Quality8284.MARK_ONLY })
        assertTrue(ts.history.all { it.volumeH1 == 0.0 && it.buysH1 == 0 && it.sellsH1 == 0 })
        assertEquals(LanePlaybook7907.Obs8084.OBSERVED, LanePlaybook7907.shapeObs8084(ts.history.toList(), nowL))
        assertEquals(0, PriceShapeBars8287.mergePriceOnly8287(ts, nowL + 1_000L, 30 * min, solUsd = 100.0))   // paced
        assertTrue(PriceShapeBars8287.statusLine8287().contains("markOnlyMerged="))
        // the candle request path adds them beside the executed-tape bars
        val req = src("engine/truth/HeliusSwapCandles7819.kt")
        assertTrue(req.contains("localTape8284(ts, nowMs, \"budget\"); priceBars8287(ts, nowMs)"))
        assertTrue(req.contains("PriceShapeBars8287.mergePriceOnly8287(ts, nowMs, WINDOW_MS)"))
        TradeTape8100.resetForTest8100()
        CandleCoverage8284.resetForTest8284()
    }

    // ── 3. Launch evidence and births ──

    @Test fun aNewLaunchsFlowItsOwnTradesCannotStateYetIsNotBinding() {
        val forty = 40.0 / 60.0
        val missing = listOf("bp:DEFAULT_UNOBSERVED", "chg:DEFAULT_UNOBSERVED")
        assertTrue(LifecycleData8283.bpNotYetPossible8287(forty, 3))
        assertFalse(LifecycleData8283.bpNotYetPossible8287(forty, null))            // trades not observed: no claim
        assertFalse(LifecycleData8283.bpNotYetPossible8287(forty, 6))               // enough trades: it can exist
        assertFalse(LifecycleData8283.bpNotYetPossible8287(20.0, 1))                // not a NEW token
        val judged = LifecycleData8283.split8287(missing, forty, null, observedTrades = 3)
        assertTrue(judged.binding.toString(), judged.binding.isEmpty())
        assertEquals(missing.toSet(), judged.unavailable.toSet())
        // the token's trades not observed, enough trades to state it, an older token, a history setup: bp stays binding
        assertEquals(listOf("bp:DEFAULT_UNOBSERVED"), LifecycleData8283.split8287(missing, forty, null, observedTrades = null).binding)
        assertEquals(listOf("bp:DEFAULT_UNOBSERVED"), LifecycleData8283.split8287(missing, forty, null, observedTrades = 8).binding)
        assertTrue(LifecycleData8283.split8287(missing, 30.0, null, observedTrades = 3).binding.contains("bp:DEFAULT_UNOBSERVED"))
        assertTrue(LifecycleData8283.split8287(missing, Double.NaN, null, observedTrades = 3).binding.containsAll(missing))
        assertTrue(LifecycleData8283.split8287(missing, forty, "TREND_PULLBACK", observedTrades = 3).binding.containsAll(missing))
        // critical unknowns stay binding whatever the launch evidence
        for (c in listOf("liq:UNKNOWN", "price", "route:UNKNOWN", "sellability:UNKNOWN", "wallet_balance:UNKNOWN", "cost:UNKNOWN")) {
            assertTrue(c, LifecycleData8283.split8287(missing + c, forty, null, observedTrades = 2).binding == listOf(c))
        }
        LifecycleData8283.clearForTest8283()
        LifecycleData8283.note8283(judged)
        assertTrue(LifecycleData8283.statusLine8283(), LifecycleData8283.statusLine8283().contains("judgedOnLaunchEvidence=1"))
        LifecycleData8283.clearForTest8283()
    }

    @Test fun bpComesFromTheLaunchsOwnFirstTrades() {
        val now = System.currentTimeMillis()
        val m = mint("launchbp")
        assertTrue(LaunchTape7921.recoverBirth8167(m, now - 60_000L, now))
        for (i in 0 until 8) LaunchTape7921.onTrade(m, "w$i", if (i < 6) 0.3 else 0.3, i < 6, now)
        assertEquals(8, LaunchTape7921.observedTrades8287(m, now))
        val f = LaunchTape7921.features(m, now)!!
        assertEquals(8, f.trades8287)
        assertEquals(75.0, f.bpAll8287, 1e-9)                         // 6 x 0.3 bought of 8 x 0.3 traded
        assertEquals(75.0, SetupFeatureResolver8169.launchBp8287(f).value!!, 1e-9)
        assertEquals(SetupFeatureResolver8169.LAUNCH_8287, SetupFeatureResolver8169.launchBp8287(f).source)
        assertNull(LaunchTape7921.observedTrades8287(mint("nolaunch"), now))
        fun feat(age: Double, trades: Int, bp: Double) = LaunchTape7921.Feat(age, 3, 1.0, 3.0, 60.0, 10.0, 30.0, false,
            Double.NaN, Double.NaN, 1.0, trades8287 = trades, bpAll8287 = bp)
        assertEquals("TOO_FEW", SetupFeatureResolver8169.launchBp8287(feat(1.0, 3, 80.0)).why)
        assertEquals("PAST_BP_WINDOW", SetupFeatureResolver8169.launchBp8287(feat(9.0, 30, 80.0)).why)
        assertEquals("NO_SIZE", SetupFeatureResolver8169.launchBp8287(feat(1.0, 9, Double.NaN)).why)
        assertEquals("NO_LAUNCH_RECORD", SetupFeatureResolver8169.launchBp8287(null).why)
        assertTrue(src("engine/truth/SetupFeatureResolver8169.kt").contains("Cand8169(TAPE, tapeBp, tapeWhy), launchBp8287(launch8287), pairBp8169("))
    }

    @Test fun throughTheClassifierALaunchWithFewObservedTradesIsJudgedOnItsLaunchEvidence() {
        LifecycleData8283.clearForTest8283()
        val now = System.currentTimeMillis()
        val launched = TokenState(mint = mint("judged"), symbol = "JDG")
        launched.lastPrice = 0.0001; launched.lastPriceUpdate = now; launched.lastMcap = 6_000.0; launched.lastLiquidityUsd = 4_000.0
        AdmissionBirth8144.noteChainMintInit8179(launched.mint, now - 20_000L)
        assertTrue(LaunchTape7921.recoverBirth8167(launched.mint, now - 20_000L, now))
        // two small first buys: observed, too few to state a flow, too few for any crowd setup to fire
        for (i in 0 until 2) LaunchTape7921.onTrade(launched.mint, "b$i", 0.05, true, now)
        val split = LanePlaybook7907.lifecycleSplit8283(launched, "SHITCOIN", now)
        assertNotNull(split)
        assertTrue(split!!.unavailable.toString(), split.unavailable.any { it.startsWith("bp:") })
        assertTrue(split.binding.toString(), split.binding.none { it.startsWith("bp:") || it.startsWith("chg:") || it.startsWith("shape:") })
        // the same young token whose trades nobody observed still waits for its flow
        val unseen = TokenState(mint = mint("unseen"), symbol = "UNS")
        unseen.lastPrice = 0.0001; unseen.lastPriceUpdate = now; unseen.lastMcap = 6_000.0; unseen.lastLiquidityUsd = 4_000.0
        AdmissionBirth8144.noteChainMintInit8179(unseen.mint, now - 20_000L)
        val unseenSplit = LanePlaybook7907.lifecycleSplit8283(unseen, "SHITCOIN", now)
        assertNotNull(unseenSplit)
        assertTrue(unseenSplit!!.binding.toString(), unseenSplit.binding.any { it.startsWith("bp:") })
        assertEquals(2, LanePlaybook7907.launchTrades8287(launched.mint, now))
        assertNull(LanePlaybook7907.launchTrades8287(unseen.mint, now))
        LifecycleData8283.clearForTest8283()
    }

    @Test fun birthWalksHaveTheirOwnPoolAndBatchAnswersWitnessCurveBirths() {
        val hyd = src("engine/truth/TokenBirthHydrator7441.kt")
        val request = hyd.substringAfter("fun request(mint: String) {").substringBefore("// V5.0.8265")
        assertFalse("a birth is a decision input, not a report-only side effect", request.contains("ChokeReliefBus.launch"))
        assertTrue(request.contains("pool8287.execute(Walk8287(m))"))
        assertTrue(hyd.contains("(ex.queue.poll() as? Walk8287)?.let { inFlight.remove(it.mint); dropped8287.incrementAndGet() }"))
        assertTrue(hyd.contains("finally { TokenBirthHydrator7441.inFlight.remove(mint) }"))
        assertTrue(com.lifecyclebot.engine.truth.TokenBirthHydrator7441.statusLine7441().contains("dropped8287="))
        // every batch answer now passes its best pair through parsePair: a pump mint's own curve pair is its birth witness
        assertTrue(src("network/DexscreenerApi.kt").contains("val pair = try { withHolders8128(parsePair(best), mint) } catch (_: Throwable) { null } ?: continue"))
        val m = ("Brth8287" + "y".repeat(40)).take(40) + "pump"
        val born = System.currentTimeMillis() - 30_000L
        AdmissionBirth8144.notePairCreated8169(m, "pumpfun", born)
        assertEquals(born, com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.resolve(m)?.birthMs)
    }

    // ── 4. Birdeye CU: what reached Birdeye ──

    @Test fun birdeyeCuCountsRequestsThatReachedBirdeyeNotAttempts() {
        val g = BirdeyeBudgetGate
        g.resetForTests(dailyCapOverride = 150_000L)
        assertEquals(System.currentTimeMillis() / 86_400_000L, g.snapshot().dayKey)   // the UTC day
        repeat(100) { g.recordCalls(1) }                              // 100 call sites charged before asking
        assertEquals(2_500L, g.snapshot().cuToday)
        repeat(100) { g.refundUnsent8287() }                          // ... and every one was answered locally
        assertEquals(0L, g.snapshot().cuToday)
        g.recordCalls(1); g.chargeWire8287()                          // a charged call that went out: counted once
        assertEquals(25L, g.snapshot().cuToday)
        g.chargeWire8287()                                            // a request nobody charged (or a retry): charged
        assertEquals(50L, g.snapshot().cuToday)
        g.recordCalls(1)                                              // a charge whose request never reached the client ...
        g.chargeWire8287(System.currentTimeMillis() + g.RESERVATION_TTL_MS_8287 + 1_000L)   // ... expires; this wire call is charged
        assertEquals(75L, g.snapshot().cuToday)
        assertTrue(g.statusLine8287(), g.statusLine8287().contains("wire=3"))
        // the real cap is kept
        g.resetForTests(dailyCapOverride = 50L)
        g.chargeWire8287(); g.chargeWire8287()
        assertFalse(g.canAfford(1))
        g.resetForTests()
        val hc = src("network/HostCircuitInterceptor.kt")
        assertTrue(hc.indexOf("com.lifecyclebot.engine.BirdeyeBudgetGate.chargeWire8287(now)") in 0 until hc.indexOf("chain.proceed(wireReq)"))
        assertEquals(3, Regex("birdeyeUnsent8287\\(provider\\)").findAll(hc).count())   // budget refusal, lockout, cooldown
    }

    // ── 5. Codex without a key ──

    @Test fun codexWithoutAKeyIsNoKeyNotFailedPolls() {
        val c = ChainLaunchFeed8179
        assertEquals("NO_KEY", c.codexState8287(keyed = false, resting = false, polls = 0L, failed = 0L))
        assertEquals("NO_KEY", c.codexState8287(keyed = false, resting = false, polls = 8L, failed = 8L))
        assertEquals("FAILING", c.codexState8287(keyed = true, resting = false, polls = 8L, failed = 8L))
        assertEquals("OK", c.codexState8287(keyed = true, resting = false, polls = 8L, failed = 2L))
        assertEquals("RESTING", c.codexState8287(keyed = true, resting = true, polls = 8L, failed = 2L))
        assertFalse("no key configured in a JVM test", ChartSources7955.codexKeyed8287())
        assertTrue(c.statusLine8179(), c.statusLine8179().contains("codex8179[state=NO_KEY "))
        val poll = src("network/ChainLaunchFeed8179.kt").substringAfter("private fun pollCodex8179() {").substringBefore("val minimal = codexMinimal")
        assertTrue(poll.indexOf("if (!codexKeyed8287) { codexNoKeySkips8287.incrementAndGet(); return }") in 0 until poll.indexOf("codexPolls.incrementAndGet()"))
    }
}
