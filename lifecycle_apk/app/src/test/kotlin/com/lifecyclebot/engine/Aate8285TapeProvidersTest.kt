package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ProofDataSwapTape8144
import com.lifecyclebot.engine.truth.TradeTape8100
import com.lifecyclebot.network.DexPools8285
import com.lifecyclebot.network.PumpFunWS
import com.lifecyclebot.network.SolanaOhlcvFeed6916
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8285 — directive 8282 P2 (trade-tape provenance) and P3 (keyless providers), plus the P5 per-feature source
 * counters. Tape: per-trade source / pool / signature / event and receive time / side / SOL / token size, one trade per
 * transaction across feeds, one venue per series, price-only curve prints never buys / sells / volume.
 */
class Aate8285TapeProvidersTest {
    private fun src(p: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val t0 = 1_800_000_000_000L
    private val mint = "Tape8285Mint".padEnd(44, 'T')
    private val tape = TradeTape8100

    // ── P2: one transaction delivered by Helius and PumpPortal is one trade ──

    @Test fun pumpPortalThenHeliusSameSignatureIsOneTrade() {
        tape.resetForTest8100(); ProofDataSwapTape8144.clearForTest8144()
        assertTrue(tape.note8285(mint, 1e-6, 0.5, 500_000.0, true, TradeTape8100.SRC_PUMPPORTAL_8285,
            pool = TradeTape8100.VENUE_CURVE_8285, signature = "SIG_A", recvMs = t0))
        // PumpPortal delivered the mint 1 s ago, signed: the Helius copy reaches the tape's signature check and is refused there
        assertFalse(ProofDataSwapTape8144.noteHelius8144(mint, "SIG_A", 0.5, 500_000.0, true, pumpPortalLastMs = t0, nowMs = t0 + 1_000L))
        assertEquals(1, tape.prints8128(mint, t0 + 2_000L, 60_000L).size)
        // a DISTINCT swap PumpPortal missed is not dropped by the PumpPortal time window any more
        assertTrue(ProofDataSwapTape8144.noteHelius8144(mint, "SIG_B", 0.2, 190_000.0, false, pumpPortalLastMs = t0, nowMs = t0 + 1_500L))
        val prints = tape.prints8128(mint, t0 + 2_000L, 60_000L)
        assertEquals(2, prints.size)
        assertEquals(listOf("pumpportal", "helius"), prints.map { it.source })
        val line = tape.statusLine8285()
        assertTrue(line, line.contains("dupCrossFeed=1"))
        assertTrue(ProofDataSwapTape8144.statusLine8144().contains("dupOnTape8285=1"))
        assertTrue(ProofDataSwapTape8144.statusLine8144().contains("distinctBesidePumpPortal8285=1"))
        tape.resetForTest8100(); ProofDataSwapTape8144.clearForTest8144()
    }

    @Test fun heliusThenPumpPortalSameSignatureIsOneTrade() {
        tape.resetForTest8100(); ProofDataSwapTape8144.clearForTest8144()
        assertTrue(ProofDataSwapTape8144.noteHelius8144(mint, "SIG_C", 0.5, 500_000.0, true, pumpPortalLastMs = 0L, nowMs = t0))
        assertFalse(tape.note8285(mint, 1e-6, 0.5, 500_000.0, true, TradeTape8100.SRC_PUMPPORTAL_8285, signature = "SIG_C", recvMs = t0 + 300L))
        assertEquals(1, tape.prints8128(mint, t0 + 1_000L, 60_000L).size)
        // the same signature on the other side is a different trade (never merged by signature alone)
        assertTrue(tape.note8285(mint, 1e-6, 0.4, 400_000.0, false, TradeTape8100.SRC_PUMPPORTAL_8285, signature = "SIG_C", recvMs = t0 + 400L))
        // an unsigned print cannot be matched: it is taped as delivered and counted
        assertTrue(tape.note8285(mint, 1e-6, 0.1, 100_000.0, true, TradeTape8100.SRC_PUMPPORTAL_8285, recvMs = t0 + 500L))
        assertEquals(3, tape.prints8128(mint, t0 + 1_000L, 60_000L).size)
        assertTrue(tape.statusLine8285().contains("unsigned=1"))
        tape.resetForTest8100(); ProofDataSwapTape8144.clearForTest8144()
    }

    @Test fun unsignedPumpPortalStillHoldsTheHeliusWindow() {
        tape.resetForTest8100(); ProofDataSwapTape8144.clearForTest8144()
        // PumpPortal delivered the mint but no signed print is on the tape: the 8144 window still keeps one feed per mint
        assertFalse(ProofDataSwapTape8144.noteHelius8144(mint, "SIG_D", 0.5, 1_000.0, true, pumpPortalLastMs = t0 - 1_000L, nowMs = t0))
        assertFalse(ProofDataSwapTape8144.admit8285(0.5, 1_000.0, 1_000L, pumpPortalSigned = false))
        assertTrue(ProofDataSwapTape8144.admit8285(0.5, 1_000.0, 1_000L, pumpPortalSigned = true))
        assertTrue(ProofDataSwapTape8144.admit8285(0.5, 1_000.0, 25_000L, pumpPortalSigned = false))
        assertFalse(ProofDataSwapTape8144.admit8285(0.5, 0.0, 25_000L, pumpPortalSigned = true))   // no executed price
        tape.resetForTest8100(); ProofDataSwapTape8144.clearForTest8144()
    }

    // ── P2: two pools never distort one venue's OHLCV or flow ──

    @Test fun twoPoolsCannotDistortOneVenuesFlowOrBars() {
        tape.resetForTest8100()
        val now = t0 + 10 * 60_000L
        // venue A: a steady 1.0 market, all buys
        for (k in 0 until 8) tape.note8285(mint, 1.0e-6, 0.5, 500_000.0, true, "pumpportal", pool = "PoolA".padEnd(44, 'A'),
            signature = "A$k", recvMs = now - 100_000L + k * 10_000L)
        // venue B (another pool of the same token) traded earlier at 3x and sold
        for (k in 0 until 8) tape.note8285(mint, 3.0e-6, 2.0, 660_000.0, false, "gecko", pool = "PoolB".padEnd(44, 'B'),
            signature = "B$k", eventMs = now - 260_000L + k * 5_000L, recvMs = now - 1_000L)
        // venue B's prints arrived last but are OLDER by event time: A's newest executed print names the venue
        val r = tape.read8100(mint, now)!!
        assertEquals("PoolA".padEnd(44, 'A'), r.venue8285)
        assertEquals(100.0, r.bp!!, 1e-9)                   // B's sells never enter A's flow
        assertEquals(8, r.buys8285); assertEquals(0, r.sells8285)
        val prints = tape.prints8128(mint, now, 30 * 60_000L)
        assertTrue(prints.isNotEmpty())
        assertTrue(prints.all { it.pool == "PoolA".padEnd(44, 'A') })
        assertTrue(prints.all { it.priceSol == 1.0e-6 })     // B's 3x prints never become A's highs
        assertTrue(tape.statusLine8285().contains("venueSplitReads="))
        tape.resetForTest8100()
    }

    @Test fun venuePurePicks() {
        val a = TradeTape8100.Trade(1L, 1.0, 1.0, true, pool = "A")
        val b = TradeTape8100.Trade(2L, 2.0, 1.0, true, pool = "B")
        val u = TradeTape8100.Trade(3L, 1.1, 1.0, false)
        val tickB = TradeTape8100.Trade(4L, 2.1, 0.0, false, priceOnly = true, pool = "B")
        assertEquals("B", TradeTape8100.venueOf8285(listOf(a, b, u)))
        assertEquals("A", TradeTape8100.venueOf8285(listOf(b, a, tickB)))   // an executed print outranks a newer price tick
        assertEquals(listOf(a, u), TradeTape8100.sameVenue8285(listOf(a, b, u), "A"))
        assertEquals("", TradeTape8100.venueOf8285(listOf(u)))
        assertEquals(TradeTape8100.VENUE_CURVE_8285, TradeTape8100.poolKey8285(TradeTape8100.SRC_CURVE_8169, ""))
        assertEquals(TradeTape8100.VENUE_AMM_8285, TradeTape8100.poolKey8285(TradeTape8100.SRC_PUMPSWAP_8169, ""))
        assertEquals("", TradeTape8100.poolKey8285(TradeTape8100.SRC_HELIUS_8169, ""))
        assertEquals(TradeTape8100.VENUE_CURVE_8285, PumpFunWS.venue8285(""))
        assertEquals(TradeTape8100.VENUE_CURVE_8285, PumpFunWS.venue8285("pump"))
        assertEquals(TradeTape8100.VENUE_AMM_8285, PumpFunWS.venue8285("pump-amm"))
        assertEquals("pumpportal:bonk", PumpFunWS.venue8285("bonk"))
        assertEquals(1_700_000_000_000L, PumpFunWS.frameEventMs8285(1_700_000_000L))
        assertEquals(1_700_000_000_123L, PumpFunWS.frameEventMs8285(1_700_000_000_123L))
        assertEquals(0L, PumpFunWS.frameEventMs8285(0L))
    }

    // ── P2: price-only curve prints never count as buys, sells or volume ──

    @Test fun curveSnapshotsAndSnapshotTradesAreNeverBuyPressureOrVolume() {
        tape.resetForTest8100()
        val now = t0 + 5 * 60_000L
        // the 8252 snapshot-difference "trades" (side + size inferred from two snapshots) and plain snapshots
        for (k in 0 until 10) tape.note8100(mint, 1e-6 * (1 + k * 0.02), 0.3, true, now - 100_000L + k * 10_000L, TradeTape8100.SRC_CURVE_8169)
        for (k in 0 until 5) tape.notePrice8169(mint, 1.2e-6, TradeTape8100.SRC_CURVE_8169, now - 4_000L + k * 500L)
        val r = tape.read8100(mint, now)!!
        assertNull(r.bp); assertNull(r.bpTx8285)
        assertEquals(0, r.buys8285 + r.sells8285)
        assertTrue(tape.prints8128(mint, now, 30 * 60_000L).isEmpty())       // never an executed print for the bars
        assertNotNull(r.shape)                                                 // price still states the (young) shape
        val line = tape.statusLine8285()
        assertTrue(line, line.contains("curveInferredPriceOnly=10"))
        assertTrue(line, line.contains("priceOnly=5"))
        assertTrue(line, line.contains("executed=0"))
        // executed prints of the same venue are read alone for flow: the curve prints never dilute them
        for (k in 0 until 6) tape.note8285(mint, 1.2e-6, if (k < 2) 3.0 else 0.5, 1.0, k < 2, "pumpportal",
            pool = TradeTape8100.VENUE_CURVE_8285, signature = "E$k", recvMs = now + k * 100L)
        val r2 = tape.read8100(mint, now + 1_000L)!!
        assertEquals(6.0 / 8.0 * 100.0, r2.bp!!, 1e-9)     // SOL-volume share: 6 of 8 SOL bought
        assertEquals(2.0 / 6.0 * 100.0, r2.bpTx8285!!, 1e-9) // transaction-count share: 2 of 6 trades were buys
        assertEquals(2, r2.buys8285); assertEquals(4, r2.sells8285)
        tape.resetForTest8100()
    }

    @Test fun txCountAndVolumeBuyPressureAreSeparate() {
        assertNull(TradeTape8100.buyPressureTx8285(listOf(true, false)))
        assertEquals(50.0, TradeTape8100.buyPressureTx8285(List(6) { it % 2 == 0 })!!, 1e-9)
        assertEquals(75.0, TradeTape8100.buyPressure8100(List(6) { it to 1.0 }.map { (i, v) -> (i < 4) to (if (i < 4) 1.5 else 1.0) })!!, 0.01)
        assertTrue(src("engine/truth/TradeTape8100.kt").contains("val bpTx8285 = buyPressureTx8285(flow8285.map { it.isBuy })"))
    }

    // ── P2: event timestamps, receive timestamps and latency ──

    @Test fun eventTimeOrdersTheTapeAndLatencyIsRecorded() {
        tape.resetForTest8100()
        val now = t0 + 60_000L
        tape.note8285(mint, 2e-6, 0.5, 250_000.0, true, "pumpportal", signature = "N1", recvMs = now - 1_000L)
        // a fallback feed's trade that happened BEFORE it arrives later: placed by its event time, its latency recorded
        tape.note8285(mint, 1e-6, 0.5, 500_000.0, false, TradeTape8100.SRC_GECKO_8285, signature = "G1",
            eventMs = now - 30_000L, recvMs = now - 28_500L)
        val prints = tape.prints8128(mint, now, 60_000L)
        assertEquals(listOf(now - 30_000L, now - 1_000L), prints.map { it.atMs })
        val line = tape.statusLine8285()
        assertTrue(line, line.contains("gecko:n=1,p50=1500ms,max=1500ms"))
        assertTrue(line, line.contains("noEventTs{pumpportal=1}"))
        // a future-dated event is not believed: the receive time stands in
        tape.note8285(mint, 2e-6, 0.5, 250_000.0, true, "pumpportal", signature = "N2", eventMs = now + 60_000L, recvMs = now)
        assertEquals(now, tape.prints8128(mint, now, 60_000L).last().atMs)
        tape.resetForTest8100()
    }

    @Test fun pumpFunWsTapesTheFrameWithItsProvenanceAndKeepsTheFrameBudget() {
        val ws = src("network/PumpFunWS.kt")
        assertTrue(ws.contains("tapeFrame8285(j, mint, priceSol, sol7743, tok7743, txType == \"buy\")"))
        assertTrue(ws.contains("pool = venue8285(j.optString(\"pool\", \"\")), signature = j.optString(\"signature\", \"\")"))
        assertTrue(ws.contains("noteTradeFrame8192(mint)  // V5.0.8192 — metered: per-launch cap and daily budget"))
        assertTrue(src("engine/truth/ProofDataSwapTape8144.kt").contains(
            "TradeTape8100.note8285(mint, sol / tokenAmount, sol, tokenAmount, isBuy, TradeTape8100.SRC_HELIUS_8169, signature = signature, recvMs = nowMs)"))
        assertTrue(src("engine/truth/TradeTape8100.kt").contains("val curve8285 = source == SRC_CURVE_8169"))
    }

    // ── P3: one candle provider down -> local observations usable, an alternate within its own budget ──

    @Test fun localTapeCandlesServeWhenTheCandleProviderIsDown() {
        val prints = (0 until 12).flatMap { m ->
            listOf(
                TradeTape8100.Print8128(t0 + m * 60_000L + 1_000L, 1e-6 * (1 + m * 0.01), 0.4, true),
                TradeTape8100.Print8128(t0 + m * 60_000L + 30_000L, 1e-6 * (1 + m * 0.01 + 0.005), 0.2, false),
            )
        }
        val c = SolanaOhlcvFeed6916.candlesFromPrints8285(prints, 150.0)!!
        assertEquals(12, c.size)
        assertEquals(1e-6 * 150.0, c.first().openUsd, 1e-12)
        assertEquals((0.4 + 0.2) * 150.0, c.first().volume24h, 1e-9)
        assertTrue(c.all { it.lowUsd <= minOf(it.openUsd, it.priceUsd) && it.highUsd >= maxOf(it.openUsd, it.priceUsd) })
        assertFalse(c.any { it.synthetic })
        assertNull(SolanaOhlcvFeed6916.candlesFromPrints8285(prints.take(6), 150.0))   // too few traded minutes: no bars
        assertNull(SolanaOhlcvFeed6916.candlesFromPrints8285(prints, 0.0))             // no SOL price: no bars
        val f = src("network/SolanaOhlcvFeed6916.kt")
        val fetch = f.substringAfter("fun fetchCandles6916(").substringBefore("// ══════ V5.0.7293")
        assertTrue(fetch.contains("if (now < cooldownUntilMs.get()) {"))
        assertTrue(fetch.contains("localTapeCandles8285(mint, timeframeLabel, n, now)?.let { return it }"))
        assertTrue(fetch.contains("return localTapeCandles8285(mint, timeframeLabel, n, now) ?: emptyList()"))
        // the cache is still read first, before any provider gate
        assertTrue(fetch.indexOf("cachedCandles8253(mint, timeframeLabel, n, now)") < fetch.indexOf("localTapeCandles8285"))
    }

    @Test fun providerBackoffIsIndependentPerProvider() {
        val l = ProviderLanes8285
        l.resetForTests8285()
        val now = t0
        l.noteResult8285(l.GECKO_8285, 429, now)
        assertTrue(l.cooling8285(l.GECKO_8285, now + 1_000L))
        assertFalse(l.cooling8285(l.DEX_8285, now + 1_000L))                 // one cooldown never blocks another provider
        assertFalse(l.cooling8285(l.GECKO_8285, now + l.cooldownMs8285(0) + 1L))
        l.noteResult8285(l.DEX_8285, 500, now); l.noteResult8285(l.DEX_8285, 500, now)
        assertFalse(l.cooling8285(l.DEX_8285, now))                          // a 5xx streak cools after three
        l.noteResult8285(l.DEX_8285, 500, now)
        assertTrue(l.cooling8285(l.DEX_8285, now + 1L))
        l.noteResult8285("dexpaprika", 403, now)
        assertTrue(l.cooling8285("dexpaprika", now + 24L * 3_600_000L))     // 403: disabled, not retried
        assertEquals(30_000L, l.cooldownMs8285(0)); assertEquals(60_000L, l.cooldownMs8285(1)); assertEquals(600_000L, l.cooldownMs8285(9))
        assertTrue(l.statusLine8285(now).contains("dexpaprika:disabled403"))
        l.resetForTests8285()
        // the keyless feed's own DexPaprika latch on 401/402/403 stands
        val f = src("network/SolanaOhlcvFeed6916.kt")
        assertTrue(f.contains("resp.code in setOf(401, 402, 403)"))
        assertTrue(f.contains("pace8285(code)  // V5.0.8285 — adaptive pacing + this provider's own lane backoff"))
    }

    @Test fun geckoPacingAdaptsWithinItsFreeTier() {
        val s = SolanaOhlcvFeed6916
        assertEquals(3_750L, s.nextIntervalMs8285(2_500L, 429, 0L))
        assertEquals(6_000L, s.nextIntervalMs8285(5_000L, 429, 0L))          // never slower than 10/min
        assertEquals(3_750L, s.nextIntervalMs8285(3_750L, 200, 3L))
        assertEquals(3_500L, s.nextIntervalMs8285(3_750L, 200, 19L))         // 20 straight answers narrow 250 ms
        assertEquals(2_500L, s.nextIntervalMs8285(2_500L, 200, 39L))         // never faster than 24/min
    }

    @Test fun priorityHeldNearDecisionHandoffBulkAndIdenticalRequestsDedupe() {
        val l = ProviderLanes8285
        l.resetForTests8285()
        assertEquals(ProviderLanes8285.Prio8285.HELD, l.prioOf8285(held = true, nearDecision = true, handoff = true))
        assertEquals(ProviderLanes8285.Prio8285.NEAR_DECISION, l.prioOf8285(false, true, true))
        assertEquals(ProviderLanes8285.Prio8285.HANDOFF, l.prioOf8285(false, false, true))
        assertEquals(ProviderLanes8285.Prio8285.BULK, l.prioOf8285(false, false, false))
        assertTrue(l.enqueue8285(l.DEX_8285, l.KIND_PAIR_8285, "bulk1", ProviderLanes8285.Prio8285.BULK, t0))
        assertTrue(l.enqueue8285(l.DEX_8285, l.KIND_PAIR_8285, "hand1", ProviderLanes8285.Prio8285.HANDOFF, t0 + 1))
        assertTrue(l.enqueue8285(l.DEX_8285, l.KIND_PAIR_8285, "near1", ProviderLanes8285.Prio8285.NEAR_DECISION, t0 + 2))
        assertTrue(l.enqueue8285(l.DEX_8285, l.KIND_PAIR_8285, "held1", ProviderLanes8285.Prio8285.HELD, t0 + 3))
        // the same request from another lane is the same pending request; it inherits the higher priority
        assertFalse(l.enqueue8285(l.DEX_8285, l.KIND_PAIR_8285, "bulk1", ProviderLanes8285.Prio8285.NEAR_DECISION, t0 + 4))
        // another provider / kind is a different request
        assertTrue(l.enqueue8285(l.GECKO_8285, l.KIND_TRADES_8285, "bulk1", ProviderLanes8285.Prio8285.BULK, t0 + 5))
        assertEquals(listOf("held1", "bulk1", "near1"), l.drain8285(l.DEX_8285, l.KIND_PAIR_8285, 3))
        assertEquals(listOf("hand1"), l.drain8285(l.DEX_8285, l.KIND_PAIR_8285, 30))
        assertEquals(1, l.pendingCount8285(l.GECKO_8285, l.KIND_TRADES_8285))
        assertTrue(l.statusLine8285(t0).contains("coalesced=1"))
        l.resetForTests8285()
    }

    // ── P3: DexScreener batch of up to 30, pool identities kept, data pool chosen deliberately ──

    private fun pair(mint: String, pool: String, quote: String, liq: Double, vol: Double = 0.0, dex: String = "raydium") = JSONObject()
        .put("chainId", "solana").put("pairAddress", pool).put("dexId", dex).put("priceUsd", "0.001")
        .put("baseToken", JSONObject().put("address", mint)).put("quoteToken", JSONObject().put("address", quote))
        .put("liquidity", JSONObject().put("usd", liq)).put("volume", JSONObject().put("h24", vol))
        .put("txns", JSONObject().put("m5", JSONObject().put("buys", 3).put("sells", 1)))

    @Test fun dataPoolIsChosenDeliberatelyAndPoolIdentitiesAreKept() {
        DexPools8285.resetForTests8285()
        val wsol = "So11111111111111111111111111111111111111112"
        val m = "Pool8285Mint".padEnd(44, 'P')
        val rows = listOf(
            pair(m, "Deep".padEnd(44, '1'), "Weird".padEnd(44, 'W'), 90_000.0),   // deepest, but an exotic quote
            pair(m, "Sol".padEnd(44, '2'), wsol, 40_000.0, 5_000.0),
            pair(m, "Sol".padEnd(44, '3'), wsol, 40_000.0, 9_000.0),               // same depth, more volume
        ).mapNotNull { DexPools8285.rowOf8285(it, t0) }
        assertEquals(3, rows.size)
        assertEquals("Sol".padEnd(44, '3'), DexPools8285.choose8285(rows, null)!!.pairAddress)
        // the pool already in use stays while it is listed with liquidity (no flapping)
        assertEquals("Sol".padEnd(44, '2'), DexPools8285.choose8285(rows, "Sol".padEnd(44, '2'))!!.pairAddress)
        // ...but an unlisted / drained preferred pool does not
        assertEquals("Sol".padEnd(44, '3'), DexPools8285.choose8285(rows, "Gone".padEnd(44, '9'))!!.pairAddress)
        assertNull(DexPools8285.choose8285(emptyList(), null))
        DexPools8285.record8285(rows)
        assertEquals(3, DexPools8285.poolsOf8285(m).size)                      // every pool's identity kept
        DexPools8285.markChosen8285(m, "Sol".padEnd(44, '2'))
        assertEquals("Sol".padEnd(44, '2'), DexPools8285.chosen8285(m))
        assertEquals("Sol".padEnd(44, '2'), DexPools8285.dataPool8285(m)!!.pairAddress)
        // a non-Solana or unpriced row is not a pool row
        assertNull(DexPools8285.rowOf8285(pair(m, "X".padEnd(44, 'X'), wsol, 1.0).put("chainId", "base"), t0))
        assertNull(DexPools8285.rowOf8285(pair(m, "X".padEnd(44, 'X'), wsol, 1.0).put("priceUsd", "0"), t0))
        assertEquals(0L, DexPools8285.batches8285.get()); assertEquals(0L, DexPools8285.liquidityRows8285.get())
        assertTrue(DexPools8285.statusLine8285().startsWith("dexPools8285[mints=1 chosen=1 "))
        DexPools8285.resetForTests8285()
    }

    @Test fun ordinaryPairUpdatesBatchThirtyAndWakeTheAsker() {
        val d = src("network/DexscreenerApi.kt")
        assertEquals(30, DexPools8285.BATCH_MAX_8285)
        assertTrue(d.contains("val want = mints.map { it.trim() }.filter { it.length in 32..44 }.distinct().take(DexPools8285.BATCH_MAX_8285)"))
        assertTrue(d.contains("\"https://api.dexscreener.com/tokens/v1/solana/\${want.joinToString(\",\")}\""))
        assertTrue(d.contains("if (lanes.cooling8285(lanes.DEX_8285, nowMs) || !RateLimiter.allowRequest(\"dexscreener\"))"))
        val o = src("engine/DataOrchestrator.kt")
        val refresh = o.substringAfter("private fun refreshSources8191(").substringBefore("private val pairLaneRunning8285")
        assertTrue(refresh.contains("ProviderLanes8285.enqueue8285(ProviderLanes8285.DEX_8285, ProviderLanes8285.KIND_PAIR_8285, mint, ProviderLanes8285.prioFor8285(mint))) drainPairLane8285()"))
        assertFalse(refresh.contains("getBestPair(mint)"))
        assertTrue(o.contains("val r = try { DexscreenerApi().batchPairs8285(batch) } catch (_: Throwable) { null }"))
        assertTrue(o.contains("com.lifecyclebot.engine.truth.CandidateAdmission8136.evidenceArrived8252(m, com.lifecyclebot.engine.truth.CandidateAdmission8136.PAIR_FEATURES_8252)"))
        // the GeckoTerminal pool-trades fallback is bounded: held / near decision only, its own lane, its own cooldown
        val gecko = o.substringAfter("private fun geckoTradesFallback8285(").substringBefore("private val tradesLaneRunning8285")
        assertTrue(gecko.contains("if (prio != ProviderLanes8285.Prio8285.HELD && prio != ProviderLanes8285.Prio8285.NEAR_DECISION) return"))
        assertTrue(gecko.contains("if (ProviderLanes8285.cooling8285(ProviderLanes8285.GECKO_8285)) {"))
        assertTrue(gecko.contains("if (why == \"TOO_FEW\" || tradesPool8285(mint).isBlank()) return"))
    }

    @Test fun featureArrivalMarkersSitWhereProvidersAcceptEvidence() {
        val t = src("engine/truth/TradeTape8100.kt")
        assertTrue(t.contains("// V5.0.8285 FEATURE_ARRIVED_TAPE(mint)"))
        assertTrue(t.contains("// V5.0.8285 FEATURE_ARRIVED_PRICE(mint)"))
        val d = src("network/DexscreenerApi.kt")
        assertTrue(d.contains("// V5.0.8285 FEATURE_ARRIVED_POOL_FLOW(mint)"))
        assertTrue(d.contains("// V5.0.8285 FEATURE_ARRIVED_LIQUIDITY(mint)"))
        assertTrue(src("network/CurveTicks7968.kt").contains("// V5.0.8285 FEATURE_ARRIVED_LIQUIDITY(mint)"))
    }

    // ── P3: GeckoTerminal pool trades as a bounded fallback onto the tape ──

    @Test fun geckoPoolTradesParseOnlyFullyStatedSolSwaps() {
        val wsol = "So11111111111111111111111111111111111111112"
        fun row(kind: String, from: String, to: String, fa: String, ta: String, sig: String = "S", ts: String = "2026-10-21T10:00:00Z") =
            JSONObject().put("attributes", JSONObject().put("kind", kind).put("tx_hash", sig).put("block_timestamp", ts)
                .put("from_token_address", from).put("to_token_address", to).put("from_token_amount", fa).put("to_token_amount", ta))
        val json = JSONObject().put("data", JSONArray()
            .put(row("buy", wsol, mint, "0.5", "500000", "S1", "2026-10-21T10:00:05Z"))
            .put(row("sell", mint, wsol, "100000", "0.1", "S2", "2026-10-21T10:00:01Z"))
            .put(row("buy", "Usdc".padEnd(44, 'U'), mint, "80", "500000", "S3"))     // no SOL size: refused
            .put(row("buy", wsol, mint, "0.5", "500000", "", "2026-10-21T10:00:05Z"))  // no signature: refused
            .put(row("buy", wsol, mint, "0.5", "0", "S5")))                         // no token size: refused
        val (trades, bad) = SolanaOhlcvFeed6916.parsePoolTrades8285(json, mint)
        assertEquals(3, bad)
        assertEquals(listOf("S2", "S1"), trades.map { it.signature })               // event order
        assertFalse(trades[0].isBuy); assertEquals(0.1, trades[0].sol, 1e-12); assertEquals(100_000.0, trades[0].tokens, 1e-9)
        assertTrue(trades[1].isBuy)
        assertEquals(java.time.Instant.parse("2026-10-21T10:00:05Z").toEpochMilli(), trades[1].eventMs)
        assertTrue(SolanaOhlcvFeed6916.statusLine().contains("gecko8285[intervalMs="))
    }

    @Test fun poolTradesTakeOnlyTheirShareOfTheGeckoBudget() {
        val s = SolanaOhlcvFeed6916
        assertEquals(6, s.TRADES_PER_MIN_8285)
        assertTrue(s.tradesSlotFree8285(List(5) { t0 - it * 1_000L }, t0))
        assertFalse(s.tradesSlotFree8285(List(6) { t0 - it * 1_000L }, t0))
        assertTrue(s.tradesSlotFree8285(List(6) { t0 - 61_000L - it }, t0))          // older than a minute: free again
        val f = src("network/SolanaOhlcvFeed6916.kt").substringAfter("fun fetchPoolTrades8285(")
        assertTrue(f.indexOf("if (nowMs < cooldownUntilMs.get()) {") in 0 until f.indexOf("val f = fetch6982(\"\$BASE/pools/\$p/trades\")"))
        assertTrue(f.indexOf("if (!claimTradesSlot8285(nowMs)) {") in 0 until f.indexOf("val f = fetch6982(\"\$BASE/pools/\$p/trades\")"))
    }

    // ── P5: per missing feature, the source's attempts / returns / invalid / throttled / repaired ──

    @Test fun featureSourceCountersHelperLine() {
        val c = FeatureSourceCounters8285
        c.resetForTests8285()
        c.note8285("bp", "dexBatch", c.ATTEMPT); c.note8285("bp", "dexBatch", c.ATTEMPT)
        c.note8285("bp", "dexBatch", c.RETURNED); c.note8285("bp", "dexBatch", c.THROTTLED)
        c.note8285("tape", "geckoTrades", c.INVALID); c.note8285("tape", "geckoTrades", c.REPAIRED)
        c.note8285("tape", "geckoTrades", "bogus")
        assertEquals(2L, c.count8285("bp", "dexBatch", c.ATTEMPT))
        assertEquals("featureSources8285[bp{dexBatch:a=2,r=1,i=0,t=1,rep=0} tape{geckoTrades:a=0,r=0,i=1,t=0,rep=1}]", c.line8285())
        assertTrue(DataPipeline8191.statusLine8191().contains("featureSources8285["))
        assertTrue(DataPipeline8191.statusLine8191().contains("lanes8285["))
        c.resetForTests8285()
    }
}
