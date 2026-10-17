package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
import com.lifecyclebot.network.ChainLaunchFeed8179
import com.lifecyclebot.network.HeliusRateGate8191
import com.lifecyclebot.network.HeliusSocketShare8191
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8191 — P1 data pipeline (owner diagnosis of 5.0.8186): the chain launch feed's socket, Helius per-second
 * pacing and the credential check, the exhaustion latch, Birdeye spend, GeckoTerminal OHLCV and setup feature coverage.
 */
class Aate8191DataPipelineTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val t0 = 5_000_000_000L

    // ── 1. ChainLaunchFeed8179: FAIL_599_ProtocolException ──

    @Test fun aWebSocketUpgradeIsNeverRefusedByTheLocalCircuit() {
        val hc = src("network/HostCircuitInterceptor.kt")
        assertTrue(hc.contains("fun wsUpgrade8191(req: okhttp3.Request): Boolean = req.header(\"Upgrade\")?.equals(\"websocket\", ignoreCase = true) == true"))
        assertTrue(hc.contains("(wsUpgrade8191(req) && provider != \"birdeye\") ||"))
        val up = okhttp3.Request.Builder().url("https://mainnet.helius-rpc.com/?api-key=k").header("Upgrade", "websocket").build()
        val plain = okhttp3.Request.Builder().url("https://mainnet.helius-rpc.com/?api-key=k").build()
        assertTrue(com.lifecyclebot.network.HostCircuitInterceptor.wsUpgrade8191(up))
        assertFalse(com.lifecyclebot.network.HostCircuitInterceptor.wsUpgrade8191(plain))
    }

    @Test fun theFeedRidesHeliusWebSocketsSocket() {
        val h = src("network/HeliusWebSocket.kt")
        assertTrue(h.contains("HeliusSocketShare8191.attach8191(webSocket)"))
        assertTrue(h.contains("if (HeliusSocketShare8191.offer8191(text)) return"))
        assertTrue(h.split("HeliusSocketShare8191.detach8191(").size - 1 >= 4)   // disconnect, failure, closing, closed
        val f = src("network/ChainLaunchFeed8179.kt")
        assertTrue(f.contains("if (HeliusSocketShare8191.attached8191()) {"))
        assertTrue(f.contains("subAcks8191.incrementAndGet()"))
        assertTrue(f.contains("noteHandshake8191(response, t)"))
    }

    @Test fun sharedFramesAreClaimedByIdAndSubscriptionWithoutParsingLogs() {
        assertEquals(1_000_000_007L, HeliusSocketShare8191.replyId8191("{\"jsonrpc\":\"2.0\",\"result\":4412,\"id\":1000000007}"))
        assertEquals(12L, HeliusSocketShare8191.replyId8191("{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":12}"))
        assertNull(HeliusSocketShare8191.replyId8191("{\"jsonrpc\":\"2.0\",\"method\":\"logsNotification\",\"params\":{\"result\":{},\"subscription\":5}}"))
        val logs = (1..200).joinToString(",") { "\"Program log: line $it\"" }
        val note = "{\"jsonrpc\":\"2.0\",\"method\":\"logsNotification\",\"params\":{\"result\":{\"context\":{\"slot\":1},\"value\":{\"signature\":\"s\",\"err\":null,\"logs\":[$logs]}},\"subscription\":24040}}"
        assertEquals(24040L, HeliusSocketShare8191.subscriptionId8191(note))
        assertNull(HeliusSocketShare8191.subscriptionId8191("{\"jsonrpc\":\"2.0\",\"result\":1,\"id\":3}"))
        assertTrue(ChainLaunchFeed8179.FEED_ID_BASE_8191 >= 1_000_000_000)
    }

    @Test fun aRefusedUpgradeWaitsOutRetryAfterAndALadder() {
        val f = ChainLaunchFeed8179
        assertEquals(45_000L, f.handshakeBackoffMs8191(429, 45L, 1))
        assertEquals(5_000L, f.handshakeBackoffMs8191(429, 1L, 1))
        assertEquals(30_000L, f.handshakeBackoffMs8191(429, null, 1))
        assertEquals(60_000L, f.handshakeBackoffMs8191(429, null, 2))
        assertEquals(10L * 60_000L, f.handshakeBackoffMs8191(429, null, 9))
        assertEquals(10L * 60_000L, f.handshakeBackoffMs8191(401, null, 1))
        assertEquals(30_000L, f.handshakeBackoffMs8191(599, null, 1))
        assertEquals(0L, f.handshakeBackoffMs8191(0, null, 1))
        val txt = f.handshakeText8191(429, "Too Many Requests", false, java.net.ProtocolException("Expected HTTP 101 response but was '429 Too Many Requests'"))
        assertTrue(txt.startsWith("code=429_msg=Too_Many_Requests_ex=ProtocolException:Expected_HTTP_101"))
        assertFalse(txt.contains(' '))
        assertTrue(f.handshakeText8191(599, "HostCircuit cool-down", true, null).startsWith("code=599_LOCAL_"))
    }

    // ── 2. Helius per-second pacing and the credential check ──

    @Test fun theBucketPacesBackgroundAndNeverDelaysExecution() {
        val b = HeliusRateGate8191.Bucket8191("t", initialRate = 4.0, minRate = 1.0, maxRate = 10.0)
        repeat(4) { assertEquals(0L, b.take8191(t0, priority = false)) }
        val w = b.take8191(t0, priority = false)
        assertTrue("5th call waits for a token", w in 1L..300L)
        assertEquals(0L, b.take8191(t0, priority = true))          // execution: never waits
        assertEquals(-1L, b.take8191(t0, priority = false, maxWaitMs = 10L))   // too long: refused locally
    }

    @Test fun a429HalvesTheRatePausesForRetryAfterAndRecovers() {
        val b = HeliusRateGate8191.Bucket8191("t", initialRate = 8.0, minRate = 2.0, maxRate = 40.0)
        b.take8191(t0, false)
        b.onRateLimited8191(t0, 3L)
        assertEquals(4.0, b.rate8191(), 1e-9)
        b.onRateLimited8191(t0 + 10, 3L)                           // same burst: halves once
        assertEquals(4.0, b.rate8191(), 1e-9)
        assertEquals(t0 + 3_010L, b.pausedUntil8191())   // the later answer extends the pause
        assertEquals(-1L, b.take8191(t0 + 100, false))              // paused 2.9 s > 1.5 s wait
        assertEquals(0L, b.take8191(t0 + 100, true))                // execution still goes
        b.take8191(t0 + HeliusRateGate8191.RECOVER_EVERY_MS_8191 + 10, true)
        assertEquals(5.0, b.rate8191(), 1e-9)
    }

    @Test fun heliusHostsMapToTheirBucketsAndLocalCreditAnswersAreNotRateSignals() {
        assertTrue(HeliusRateGate8191.bucketFor8191("mainnet.helius-rpc.com") === HeliusRateGate8191.rpc8191)
        assertTrue(HeliusRateGate8191.bucketFor8191("api.helius.xyz") === HeliusRateGate8191.enhanced8191)
        assertNull(HeliusRateGate8191.bucketFor8191("sender.helius-rpc.com"))
        assertNull(HeliusRateGate8191.bucketFor8191("api.dexscreener.com"))
        assertTrue(HeliusRateGate8191.providerRateLimit8191(429, "Too Many Requests"))
        assertFalse(HeliusRateGate8191.providerRateLimit8191(429, "AATE_HELIUS_BUDGET_8066"))
        assertEquals(7L, HeliusRateGate8191.retryAfterSec8191(" 7 "))
        assertNull(HeliusRateGate8191.retryAfterSec8191("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertTrue(src("network/SharedHttpClient.kt").contains(".addInterceptor(com.lifecyclebot.network.HeliusRateInterceptor8191)"))
        assertTrue(HeliusRateGate8191.statusLine8191(t0).startsWith("heliusRps8191["))
    }

    @Test fun theCredentialCheckNeverMarksTheKeyInvalidOnA429() {
        val k = KeyValidator
        assertEquals(4_000L, k.heliusProbeRetryMs8191(429, false, "Too Many Requests", "4", 1))
        assertEquals(2_000L, k.heliusProbeRetryMs8191(429, false, "Too Many Requests", null, 1))
        assertEquals(4_000L, k.heliusProbeRetryMs8191(429, false, "Too Many Requests", null, 2))
        assertEquals(-1L, k.heliusProbeRetryMs8191(429, false, "Too Many Requests", null, 3))
        assertEquals(2_000L, k.heliusProbeRetryMs8191(599, true, "HostCircuit cool-down", null, 1))
        assertEquals(-1L, k.heliusProbeRetryMs8191(429, false, "AATE_HELIUS_BUDGET_8066", null, 1))
        assertEquals(-1L, k.heliusProbeRetryMs8191(500, false, "x", null, 1))
        val kv = src("engine/KeyValidator.kt")
        // superseded by V5.0.8191: a 429 used to be markDead(service, 429, ..., "HELIUS_RATE_LIMIT_429").
        assertFalse(kv.contains("markDead(service, 429, body.take(120), \"HELIUS_RATE_LIMIT_429\")"))
        assertTrue(kv.contains("HELIUS_RATE_LIMIT_429_KEY_NOT_INVALID_8191"))
        assertTrue(kv.contains("Thread({ probeHeliusPaced8191(heliusKey, walletAddress.orEmpty()) }"))
    }

    @Test fun aRateLimitAnswerIsNeverCreditExhaustion() {
        val h = HeliusCreditEconomy7881
        assertFalse(h.isExhaustionAnswer8094(429, "{\"error\":{\"code\":-32429,\"message\":\"You have exceeded your rate limit, please upgrade your plan\"}}"))
        assertFalse(h.isExhaustionAnswer8094(429, "Too many requests per second"))
        assertTrue(h.isExhaustionAnswer8094(429, "{\"error\":\"max usage reached for this plan, upgrade your plan\"}"))
        assertFalse(com.lifecyclebot.network.rpcExhaustedReply8094(200, "exceeded your rate limit; upgrade your plan"))
        assertFalse(h.exhaustionConfirmed8191(listOf(t0, t0 + 1_000L), t0 + 2_000L))
        assertTrue(h.exhaustionConfirmed8191(listOf(t0, t0 + 1_000L, t0 + 2_000L), t0 + 2_000L))
        assertFalse(h.exhaustionConfirmed8191(listOf(t0, t0 + 1_000L, t0 + 2_000L), t0 + 2_000L + h.EXHAUST_CONFIRM_MS_8191 + 1))
        assertTrue(src("network/HeliusCreditMeterInterceptor7881.kt").contains("HeliusCreditEconomy7881.noteExhaustionAnswer8191(resp.code)"))
    }

    // ── coordinator evidence (5.0.8186b) ──

    @Test fun aBusyStreamThatRarelyDecodesIsReleased() {
        assertFalse(com.lifecyclebot.network.heliusStreamLowYield8191(199, 0, 0L))
        assertTrue(com.lifecyclebot.network.heliusStreamLowYield8191(200, 3, 0L))      // 1.5% yield
        assertFalse(com.lifecyclebot.network.heliusStreamLowYield8191(200, 4, 0L))     // 2%
        assertTrue(com.lifecyclebot.network.heliusStreamLowYield8191(40, 0, 3L * 1_048_576L))
        assertFalse(com.lifecyclebot.network.heliusStreamLowYield8191(5_000, 4_000, 50L * 1_048_576L))
    }

    private fun le(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun pumpSwapBuy(base: Long, quote: Long, poolBase: Long, poolQuote: Long): ByteArray {
        val b = ByteArray(184)
        byteArrayOf(103, 244.toByte(), 82, 31, 44, 245.toByte(), 119, 119).copyInto(b, 0)
        le(1_700_000_000L).copyInto(b, 8); le(base).copyInto(b, 16); le(poolBase).copyInto(b, 48)
        le(poolQuote).copyInto(b, 56); le(quote).copyInto(b, 64)
        return b
    }

    @Test fun aPumpSwapBuyEventDecodesOnlyWhenItsPriceAgreesWithThePool() {
        val ev = com.lifecyclebot.network.PumpSwapEvents8191.decode8191(pumpSwapBuy(1_000_000_000L, 100_000_000L, 1_000_000_000_000L, 100_000_000_000L))
        assertTrue(ev != null && ev.isBuy)
        assertEquals(0.1, ev!!.sol, 1e-12)
        assertEquals(1_000.0, ev.tokens, 1e-9)
        assertNull(com.lifecyclebot.network.PumpSwapEvents8191.decode8191(pumpSwapBuy(1_000_000_000L, 100_000_000L, 1_000_000_000_000L, 900_000_000_000L)))
        assertNull(com.lifecyclebot.network.PumpSwapEvents8191.decode8191(ByteArray(100)))
        assertTrue(src("network/HeliusWebSocket.kt").contains("if (mint.endsWith(\"pump\")) for (i in 0 until logs.length()) {"))
    }

    @Test fun accountSubscribeGoesToTheKeyedHeliusSocketFirstAndAMethodMissingEndpointIsBenched() {
        val r = src("engine/RuntimeProviderAuthority6685.kt")
        assertTrue(r.contains("return (extra + urls).mapNotNull(::websocketFromRpc8079).distinct()"))
        val g = src("network/GradTicks8018.kt")
        assertTrue(g.contains("CurveTicks7968.usableEndpoints8102(com.lifecyclebot.engine.RuntimeProviderAuthority6685.websocketCandidates8079(key, preferredRpc), methodMissingAt8191, now)"))
        assertTrue(g.contains("methodMissingAt8191[currentUrl8191] = System.currentTimeMillis()"))
    }

    @Test fun theOhlcvRequestAfterAPoolResolveWaitsForItsSlot() {
        val f = src("network/SolanaOhlcvFeed6916.kt")
        assertTrue(f.contains("if (lookup6982.askedProvider && awaitSlot8191(MIN_INTERVAL_MS + 500L)) slotWaits8191.incrementAndGet()"))
        assertTrue(f.indexOf("awaitSlot8191(MIN_INTERVAL_MS + 500L)") < f.indexOf("val candles6982 = fetch6982(url)"))
    }

    @Test fun theSweepEnrichCallIsEndpointScopedAndARecoveredProviderClearsItsFailure() {
        val hc = com.lifecyclebot.network.HostCircuitInterceptor
        val path = "/tokens/v1/solana/So11111111111111111111111111111111111111112,EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        assertEquals("dexscreener@tokens/solana", hc.scopedLabel8179(hc.labelFor8125("api.dexscreener.com", path, null), path))
        assertTrue(src("engine/market/MarketSweep7297.kt").contains("if (provider.isNotBlank()) lastFail7301.remove(provider)\n                resp.body?.string()"))
    }

    // ── 3. Birdeye spend: held / near-decision only, Codex first for candles ──

    @Test fun birdeyePremiumRunsOnlyForHeldOrNearDecisionCoinsWithoutACheaperFreshSource() {
        val d = DataPipeline8191
        assertTrue(d.premiumAllowedPure8191(held = true, nearDecision = false, cheaperFresh = true))
        assertTrue(d.premiumAllowedPure8191(held = false, nearDecision = true, cheaperFresh = false))
        assertFalse(d.premiumAllowedPure8191(held = false, nearDecision = true, cheaperFresh = true))
        assertFalse(d.premiumAllowedPure8191(held = false, nearDecision = false, cheaperFresh = false))
        val bs = src("engine/BotService.kt")
        // superseded by V5.0.8191: the tradability-only gate "val tradable = liqUsd >= 2_000.0 && mcapUsd >= 5_000.0".
        assertFalse(bs.contains("val tradable = liqUsd >= 2_000.0 && mcapUsd >= 5_000.0"))
        assertTrue(bs.contains("val tradable = DataPipeline8191.premium8191(ts)"))
        assertTrue(src("engine/DataPipeline8191.kt").contains("ts.lastLiquidityUsd >= 2_000.0 && ts.lastMcap >= 5_000.0 && premiumAllowed8191(ts.mint)"))
    }

    @Test fun aCoinAskingForItsInputsIsNearADecisionForTenMinutes() {
        val d = DataPipeline8191
        d.resetForTests8191()
        val m = "NearMint8191xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        assertFalse(d.nearDecision8191(m, t0))
        d.noteNearDecision8191(m, t0)
        assertTrue(d.nearDecision8191(m, t0 + d.NEAR_DECISION_MS_8191))
        assertFalse(d.nearDecision8191(m, t0 + d.NEAR_DECISION_MS_8191 + 1))
        assertFalse(d.nearDecisionAt8191(null, t0))
        d.resetForTests8191()
    }

    @Test fun ordinarySeedsShareSixCodexCallsAMinute() {
        val d = DataPipeline8191
        d.resetForTests8191()
        repeat(d.CODEX_ORDINARY_PER_MIN_8191) { assertTrue(d.claimCodexOrdinary8191(t0 + it)) }
        assertFalse(d.claimCodexOrdinary8191(t0 + 100))
        assertTrue(d.claimCodexOrdinary8191(t0 + 60_001))
        d.resetForTests8191()
    }

    @Test fun birdeyeSpendIsNamedByEndpoint() {
        val d = DataPipeline8191
        d.resetForTests8191()
        assertEquals("defi/v3/token/trade-data", d.birdeyeEndpoint8191("https://public-api.birdeye.so/defi/v3/token/trade-data/single?address=x"))
        assertEquals("defi/ohlcv", d.birdeyeEndpoint8191("https://public-api.birdeye.so/defi/ohlcv?address=x&type=1m"))
        d.noteBirdeye8191("https://public-api.birdeye.so/defi/ohlcv?address=x&type=1m")
        assertTrue(d.statusLine8191().contains("defi/ohlcv=1"))
        assertTrue(src("network/BirdeyeApi.kt").contains("com.lifecyclebot.engine.DataPipeline8191.noteBirdeye8191(url)"))
        d.resetForTests8191()
    }

    @Test fun candleSeedsAskCodexFirstForPriorityCoinsAndNeverBuyAFilledTimeframe() {
        val o = src("engine/DataOrchestrator.kt")
        assertTrue(o.contains("val codexFirst8191 = priority8191 && (if (repairShape8168) repairShapeFromCodex8169(ts) else codexSeed8191(ts))"))
        assertTrue(o.contains("val k1m = if (codexFirst8191) emptyList() else keylessFetch7809(mint, \"1m\", 120, poolHint6916, attempts = 3)"))
        assertTrue(o.contains("if (ts.history.size < 10 && DataPipeline8191.claimCodexOrdinary8191()) codexSeed8191(ts)"))
        assertTrue(o.indexOf("if (!priority8191) {") < o.indexOf("val candles1m = "))
        assertTrue(o.contains("val candles1m = if (ts.history.size >= 10 && !repairShape8168) emptyList() else birdeye.getCandles(mint, \"1m\", 120)"))
        assertTrue(o.contains("val candles5m = if (ts.history5m.size >= 5) emptyList() else birdeye.getCandles(mint, \"5m\", 60)"))
    }

    // ── 5. setup features: trade frames reach the evaluated coin, a missing pair is fetched ──

    @Test fun anEvaluatedCoinJoinsTheKeyedTradeStream() {
        val o = src("engine/DataOrchestrator.kt")
        assertTrue(o.contains("if (keys.any { it in TRADE_FED_INPUTS_8191 }) try { PumpFunWS.watchForSetup8191(mint) } catch (_: Throwable) {}"))
        assertTrue(o.contains("refreshSources8191(mint, keys)"))
        val w = src("network/PumpFunWS.kt")
        assertTrue(w.contains("val drop = tradeSubscriptions7278 - wanted - lifecycleMints7420.keys - setupMints8191.keys"))
        assertTrue(w.contains("val unsub8191 = expired.filter { !setupWatched8191(it, now) }"))
    }

    @Test fun setupWatchEvictsExpiredThenEldestButNeverALiveLaunch() {
        val w = com.lifecyclebot.network.PumpFunWS
        val watched = mapOf("a" to t0, "b" to t0 + 1_000L, "c" to t0 + 2_000L, "old" to t0 - 700_000L)
        assertEquals(listOf("old", "a"), w.setupEvictions8191(watched, "d", t0 + 3_000L, 3, 600_000L) { false })
        assertEquals(listOf("old"), w.setupEvictions8191(watched, "d", t0 + 3_000L, 3, 600_000L) { it == "a" })
        assertEquals(listOf("old"), w.setupEvictions8191(watched, "d", t0 + 3_000L, 10, 600_000L) { false })
    }

    @Test fun aNotFetchedPairTriggersOneFetchAMinute() {
        val d = DataPipeline8191
        assertTrue(d.pairFetchDue8191(null, null, t0))
        assertFalse(d.pairFetchDue8191(null, t0 - 10_000L, t0))
        assertTrue(d.pairFetchDue8191(null, t0 - d.PAIR_FETCH_GAP_MS_8191, t0))
        assertFalse(d.pairFetchDue8191(t0 - 60_000L, null, t0))                    // fresh pair flow: nothing to fetch
        assertTrue(d.pairFetchDue8191(t0 - 16L * 60_000L, null, t0))              // stale
        assertTrue(src("engine/DataOrchestrator.kt").contains("DataPipeline8191.claimPairFetch8191(mint)) scope.launch {"))
    }
}
