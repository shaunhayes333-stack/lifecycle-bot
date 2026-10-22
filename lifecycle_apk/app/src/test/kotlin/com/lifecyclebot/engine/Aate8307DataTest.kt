package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.ChartSources7955
import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
import com.lifecyclebot.engine.truth.ProofDataSwapTape8144
import com.lifecyclebot.engine.truth.TradeTape8100
import com.lifecyclebot.network.ChainLaunchFeed8179
import com.lifecyclebot.network.HostCircuitInterceptor
import com.lifecyclebot.network.PumpFunWS
import com.lifecyclebot.network.PumpSwapEvents8191
import com.lifecyclebot.network.PumpSwapTape8307
import com.lifecyclebot.network.SolanaOhlcvFeed6916
import com.lifecyclebot.network.WalletTokenReadGate8268
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8307 — the data PumpPortal / Helius already deliver reaches the feature pipeline: PumpPortal's PumpSwap balance
 * refusal is scoped to migrated mints (the keyed bonding-curve stream stays up), Helius-decoded PumpSwap swaps reach the
 * trade tape with their pool / signature / block time, wallet token reads coalesce into one, the freed Helius budget goes
 * to decision-priority swap candles, and spent quotas / 429s / one host's 5xx rest only that provider.
 */
class Aate8307DataTest {

    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // ── 1. PumpPortal: the PumpSwap refusal is scoped ──

    @Test fun pumpSwapBalanceRefusalIsNotAWholeStreamRefusal() {
        val msg = "{\"errors\":\"Minimum balance not met for PumpSwap websocket data.\"}".lowercase()
        assertTrue(PumpFunWS.isPumpSwapRefusal8307(msg))
        assertFalse(PumpFunWS.isTradeAuthRefusal7432(msg))
        // The real keyed-stream refusal is still a refusal of the stream.
        val auth = "'subscribeTokenTrade' and 'subscribeAccountTrade' methods are only available when connecting with an API key funded with at least 0.02 SOL."
        assertTrue(PumpFunWS.isTradeAuthRefusal7432(auth))
        assertFalse(PumpFunWS.isPumpSwapRefusal8307(auth))
        assertTrue(PumpFunWS.isTradeAuthRefusal7432("minimum balance not met"))
        assertFalse(PumpFunWS.isPumpSwapRefusal8307("Successfully subscribed to keys."))
    }

    @Test fun pumpSwapRetriesOnASlowLadder() {
        assertEquals(15L * 60_000L, PumpFunWS.pumpSwapRetryDelayMs8307(1))
        assertEquals(30L * 60_000L, PumpFunWS.pumpSwapRetryDelayMs8307(2))
        assertEquals(60L * 60_000L, PumpFunWS.pumpSwapRetryDelayMs8307(3))
        assertEquals(4L * 60L * 60_000L, PumpFunWS.pumpSwapRetryDelayMs8307(9))
        val t0 = 1_000_000_000L
        assertTrue(PumpFunWS.pumpSwapBlocked8307(1, t0, t0 + 14L * 60_000L))
        assertFalse(PumpFunWS.pumpSwapBlocked8307(1, t0, t0 + 15L * 60_000L))
        assertFalse(PumpFunWS.pumpSwapBlocked8307(0, 0L, t0))
        assertEquals("REFUSED_MIN_BALANCE", PumpFunWS.pumpSwapStateOf8307(1, blocked = true, observed = false))
        assertEquals("RETRY_DUE", PumpFunWS.pumpSwapStateOf8307(2, blocked = false, observed = false))
        assertEquals("UNPROBED", PumpFunWS.pumpSwapStateOf8307(0, blocked = false, observed = false))
        assertEquals("OBSERVED", PumpFunWS.pumpSwapStateOf8307(0, blocked = false, observed = true))
    }

    @Test fun aBondingCurveMintIsNeverRefusedBecauseOfPumpSwap() {
        val migrated = setOf("MIGRATEDpump")
        val asked = listOf("CURVEpump", "MIGRATEDpump", "OTHERpump")
        assertEquals(listOf("CURVEpump", "OTHERpump"), PumpFunWS.pumpSwapAdmit8307(asked, blocked = true) { it in migrated })
        assertEquals(asked, PumpFunWS.pumpSwapAdmit8307(asked, blocked = false) { it in migrated })
        // No migration evidence at all: nothing is parked even while refused.
        assertEquals(asked, PumpFunWS.pumpSwapAdmit8307(asked, blocked = true) { false })
    }

    @Test fun theRefusalParksMigratedMintsWithoutReconnectingOrDenyingTheStream() {
        val ws = src("network/PumpFunWS.kt")
        val body = ws.substringAfter("private fun notePumpSwapRefusal8307(").substringBefore("private fun notePumpSwapFrame8307(")
        assertFalse(body.contains("ws?.close"))
        assertFalse(body.contains("tradeAuthDenied7432.set(true)"))
        assertFalse(body.contains("tradeSubscriptions7278.clear()"))
        assertTrue(body.contains("pumpSwapMint8307(it)"))
        // The PumpSwap check comes first, so the whole-stream refusal branch never sees it.
        assertTrue(ws.contains("if (isPumpSwapRefusal8307(error7432)) notePumpSwapRefusal8307(System.currentTimeMillis())  // V5.0.8307 — scoped\n                else if (!tradeAuthDenied7432.get() && isTradeAuthRefusal7432(error7432)) {"))
        // Every subscribe path filters migrated mints while refused; coverage is never claimed for them.
        assertTrue(ws.contains("val add = admitForSubscribe8307(wanted - tradeSubscriptions7278).toSet()"))
        assertTrue(ws.contains("if (admitForSubscribe8307(listOf(mint), nowMs).isEmpty()) return false"))
        assertTrue(ws.contains("mint !in coverageSuspect8307 && !pumpSwapParkedNow8307(mint)"))
        assertTrue(ws.contains("\"\${pumpSwapLine8307()} \" +"))
        assertTrue(ws.contains("noteMigrationFrame8307(mint)"))
    }

    @Test fun aPumpSwapFrameIsPricedByItsOwnSizesOntoTheTapeOnly() {
        assertEquals(0.002, PumpFunWS.ammFramePrice8307(2.0, 1_000.0), 1e-12)
        assertTrue(PumpFunWS.ammFramePrice8307(0.0, 1_000.0).isNaN())
        assertTrue(PumpFunWS.ammFramePrice8307(1.0, 0.0).isNaN())
        val ws = src("network/PumpFunWS.kt")
        val amm = ws.substringAfter("private fun ammFrame8307(").substringBefore("fun noteMigrationFrame8307(")
        assertFalse(amm.contains("CurveReserves7951"))
        assertFalse(amm.contains("onTradeCb"))
        assertTrue(amm.contains("tapeFrame8285(j, mint, px, sol, tok, isBuy)"))
    }

    // ── 2. PumpSwap tape from Helius ──

    private fun le(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private val poolBytes = ByteArray(32) { (it * 7 + 3).toByte() }
    private val poolB58: String = io.github.novacrypto.base58.Base58.base58Encode(poolBytes)

    private fun pumpSwapEvent(buy: Boolean, base: Long, quote: Long, tsSec: Long = 1_760_000_000L): ByteArray {
        val b = ByteArray(184)
        val disc = if (buy) byteArrayOf(103, 244.toByte(), 82, 31, 44, 245.toByte(), 119, 119)
            else byteArrayOf(62, 47, 55, 10, 165.toByte(), 3, 220.toByte(), 42)
        disc.copyInto(b, 0)
        le(tsSec).copyInto(b, 8); le(base).copyInto(b, 16)
        le(base * 1_000L).copyInto(b, 48); le(quote * 1_000L).copyInto(b, 56); le(quote).copyInto(b, 64)
        poolBytes.copyInto(b, 120)
        return b
    }

    @Test fun thePumpSwapEventStatesItsPoolAndBlockTime() {
        val ev = PumpSwapEvents8191.decode8191(pumpSwapEvent(true, 2_000_000_000L, 500_000_000L))
        assertNotNull(ev)
        assertEquals(poolB58, ev!!.pool)
        assertEquals(1_760_000_000L, ev.tsSec)
        assertEquals(0.5, ev.sol, 1e-12)
        assertEquals(2_000.0, ev.tokens, 1e-9)
        assertTrue(PumpSwapTape8307.realPool8307(poolB58))
        assertFalse(PumpSwapTape8307.realPool8307("11111111111111111111111111111111"))
        assertEquals(0L, PumpSwapTape8307.eventMs8307(0L, 1_000L))
        assertEquals(0L, PumpSwapTape8307.eventMs8307(10_000L, 1_000L))   // in the future: unstated
        assertEquals(1_000_000L, PumpSwapTape8307.eventMs8307(1_000L, 2_000_000L))
    }

    @Test fun aHeliusDecodedPumpSwapPrintKeepsItsPoolOnTheTape() {
        TradeTape8100.resetForTest8100(); PumpSwapTape8307.clearForTest8307(); ProofDataSwapTape8144.clearForTest8144()
        val mint = "Mig8307aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaapump"
        val now = 1_760_000_100_000L
        PumpSwapTape8307.noteDecoded8307("sigA", mint, poolB58, 1_760_000_090L, now)
        assertNull(PumpSwapTape8307.takePool8307("sigA", "someOtherMint"))   // a signature never lends its pool to another mint
        PumpSwapTape8307.noteDecoded8307("sigA", mint, poolB58, 1_760_000_090L, now)
        assertTrue(ProofDataSwapTape8144.noteHelius8144(mint, "sigA", 0.5, 2_000.0, true, 0L, now))
        val p = TradeTape8100.prints8128(mint, now, 120_000L)
        assertEquals(1, p.size)
        assertEquals(poolB58, p[0].pool)
        assertEquals(TradeTape8100.SRC_HELIUS_8169, p[0].source)
        assertEquals(1_760_000_090_000L, p[0].atMs)
        assertEquals(0.5 / 2_000.0, p[0].priceSol, 1e-15)
        assertEquals(mint, PumpSwapTape8307.mintForPool8307(poolB58))
    }

    @Test fun programStreamSwapsOnAKnownPoolReachTheTapeAndUnknownPoolsAreNeverGuessed() {
        TradeTape8100.resetForTest8100(); PumpSwapTape8307.clearForTest8307()
        val now = 1_760_000_100_000L
        val line = "Program data: " + java.util.Base64.getEncoder().encodeToString(pumpSwapEvent(false, 1_000_000_000L, 100_000_000L, 1_760_000_095L))
        // Unknown pool: counted, not taped.
        assertEquals(0, PumpSwapTape8307.onProgramLogs8307("sigU", listOf("Program log: Instruction: Sell", line), now))
        val mint = "Kno8307aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaapump"
        PumpSwapTape8307.notePoolMint8307(poolB58, mint)
        assertEquals(1, PumpSwapTape8307.onProgramLogs8307("sigK", listOf("Program log: Instruction: Sell", line), now))
        val p = TradeTape8100.prints8128(mint, now, 120_000L)
        assertEquals(1, p.size)
        assertFalse(p[0].isBuy)
        assertEquals(poolB58, p[0].pool)
        assertEquals(0.1, p[0].sol, 1e-12)
        // The same signature again is one trade.
        assertEquals(0, PumpSwapTape8307.onProgramLogs8307("sigK", listOf(line), now))
        assertTrue(src("network/ChainLaunchFeed8179.kt").contains("PumpSwapTape8307.onProgramLogs8307(sig, logs)"))
        assertTrue(src("network/DexscreenerApi.kt").contains("PumpSwapTape8307.notePoolMint8307(r.pairAddress, r.mint)"))
    }

    @Test fun migratedCoinsTakeTheHeliusFocusFirst() {
        val order = PumpSwapTape8307.migratedFirst8307(listOf("a", "b", "c", "d")) { it == "c" || it == "d" }
        assertEquals(listOf("c", "d", "a", "b"), order)
        assertEquals(listOf("a", "b"), PumpSwapTape8307.migratedFirst8307(listOf("a", "b")) { false })
        assertTrue(src("engine/DataOrchestrator.kt").contains("PumpSwapTape8307.migratedFirst8307(DataPipeline8191.recentNearDecisionMints8269(capacity * 3, nowMs))"))
    }

    // ── 3. Helius credits: one wallet read, decision coins get the freed budget ──

    @Test fun walletReadsJoinTheReadInFlight() {
        val owner = "Owner8307aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        assertNull(WalletTokenReadGate8268.begin(owner, 5_000L))
        assertEquals(5_000L, WalletTokenReadGate8268.inFlightSince8307(owner))
        assertEquals("IN_FLIGHT", WalletTokenReadGate8268.begin(owner, 5_100L))
        val t = Thread { Thread.sleep(80L); WalletTokenReadGate8268.finish(owner, success = true, nowMs = 5_200L) }
        t.start()
        assertTrue(WalletTokenReadGate8268.awaitIdle8307(owner, 3_000L))
        t.join()
        assertEquals(0L, WalletTokenReadGate8268.inFlightSince8307(owner))
        // A forced caller joins only a read that started after its moment; a snapshot serves only if observed after it.
        assertTrue(WalletTokenReadGate8268.joinable8307(2_000L, 1_500L))
        assertFalse(WalletTokenReadGate8268.joinable8307(1_000L, 1_500L))
        assertTrue(WalletTokenReadGate8268.serves8307(1_600L, 2_000L, 1_500L))
        assertFalse(WalletTokenReadGate8268.serves8307(1_400L, 2_000L, 1_500L))
        assertFalse(WalletTokenReadGate8268.serves8307(2_500L, 2_000L, 1_500L))
        val w = src("network/SolanaWallet.kt")
        val strict = w.substringAfter("private fun sharedTokenRead8307(").substringBefore("private fun readTokenAccountsOnWire8268(")
        assertTrue(strict.contains("WalletTokenReadGate8268.awaitIdle8307(publicKeyB58"))
        assertTrue(strict.contains("WALLET_TOKEN_READ_JOINED_8307"))
        assertTrue(w.contains("fun getTokenAccountsWithDecimalsFresh8307(notBeforeMs: Long)"))
    }

    @Test fun decisionCoinsMayUseTheDaysOnPaceHeadroomButNeverPassIt() {
        // Day 50% through: paced budget = 300k * (0.5 + 1/24) = 162.5k.
        assertTrue(HeliusCreditEconomy7881.priorityAdmits8307(300_000.0, 0.5, 100_000.0, 200.0))
        assertFalse(HeliusCreditEconomy7881.priorityAdmits8307(300_000.0, 0.5, 162_400.0, 200.0))
        val c = src("engine/truth/HeliusSwapCandles7819.kt")
        assertTrue(c.contains("if (priority >= PRIORITY_NEAR_DECISION_8284) HeliusCreditEconomy7881.admitPriority8307("))
    }

    // ── 4. quotas, 429s and one host's 5xx ──

    @Test fun aSpentCodexQuotaRestsUntilItsReset() {
        assertTrue(ChartSources7955.quotaExhausted8307(200, "{\"errors\":[{\"message\":\"Usage limit exceeded for this API key\"}]}"))
        assertTrue(ChartSources7955.quotaExhausted8307(429, "{\"message\":\"Monthly quota exceeded\"}"))
        assertTrue(ChartSources7955.quotaExhausted8307(402, ""))
        // GeckoTerminal's per-minute rate limit is not a quota.
        assertFalse(ChartSources7955.quotaExhausted8307(429, "You've exceeded the Rate Limit. Please visit https://www.coingecko.com/en/api/pricing to subscribe to our API plans for higher rate limits."))
        assertFalse(ChartSources7955.quotaExhausted8307(200, "{\"data\":{\"filterTokens\":{\"results\":[]}}}"))
        val now = 1_760_000_000_000L
        val nextDay = (now / 86_400_000L + 1L) * 86_400_000L
        assertEquals(nextDay, ChartSources7955.quotaResetMs8307(now, null, null))
        assertEquals(now + 3_600_000L, ChartSources7955.quotaResetMs8307(now, 3_600L, null))
        assertEquals(now + 600_000L, ChartSources7955.quotaResetMs8307(now, null, "600"))
        assertEquals(1_760_050_000_000L, ChartSources7955.quotaResetMs8307(now, null, "1760050000"))
        assertEquals(nextDay, ChartSources7955.quotaResetMs8307(now, null, "1500000000"))   // in the past: not believable
        assertEquals("QUOTA_EXHAUSTED", ChainLaunchFeed8179.codexState8307(true, true, false, 5, 5))
        assertEquals("FAILING", ChainLaunchFeed8179.codexState8307(false, true, false, 5, 5))
        assertEquals("NO_KEY", ChainLaunchFeed8179.codexState8307(true, false, false, 0, 0))
        val feed = src("network/ChainLaunchFeed8179.kt")
        val poll = feed.substringAfter("private fun pollCodex8179()").substringBefore("// ── V5.0.8191 §THE_FEED_RIDES")
        assertTrue(poll.indexOf("codexQuotaExhausted8307(now)") in 0 until poll.indexOf("codexPolls.incrementAndGet()"))
    }

    @Test fun a429HonoursRetryAfterAndWidensOnlyThatProvidersPace() {
        assertEquals(120_000L, ChartSources7955.rateLimitRestMs8307(0, 120L))
        assertEquals(5_000L, ChartSources7955.rateLimitRestMs8307(0, 1L))
        assertEquals(ChartSources7955.backoffMs7955(429, 2, false), ChartSources7955.rateLimitRestMs8307(2, null))
        assertEquals(20_000L, ChartSources7955.nextPaceMs8307(10_000L, 0L, 429, 0))
        assertEquals(60_000L, ChartSources7955.nextPaceMs8307(10_000L, 40_000L, 429, 0))
        assertEquals(25_000L, ChartSources7955.nextPaceMs8307(10_000L, 40_000L, 200, 9))
        assertEquals(40_000L, ChartSources7955.nextPaceMs8307(10_000L, 40_000L, 200, 3))
        assertEquals(90_000L, SolanaOhlcvFeed6916.cooldownMs8307(0L, 90L))
        assertEquals(SolanaOhlcvFeed6916.escalatedCooldownMs7819(2L), SolanaOhlcvFeed6916.cooldownMs8307(2L, null))
    }

    @Test fun onePumpFunHostCoolsDownAlone() {
        assertEquals("pumpfun", HostCircuitInterceptor.pumpFunLabel8307("frontend-api-v3.pump.fun"))
        assertEquals("pumpfun", HostCircuitInterceptor.pumpFunLabel8307("pump.fun"))
        assertEquals("pumpfun_swapapi", HostCircuitInterceptor.pumpFunLabel8307("swap-api.pump.fun"))
        assertEquals("pumpfun_advancedapiv2", HostCircuitInterceptor.pumpFunLabel8307("advanced-api-v2.pump.fun"))
    }

    // ── owner directive: Helius as the primary real-time and history source, efficiently ──

    private fun b58(seed: Int): String = io.github.novacrypto.base58.Base58.base58Encode(ByteArray(32) { (it + seed).toByte() })

    private fun curveTradeEvent(mintBytes: ByteArray, solLamports: Long, tokRaw: Long, buy: Boolean, tsSec: Long): ByteArray {
        val b = ByteArray(129)
        byteArrayOf(189.toByte(), 219.toByte(), 127, 211.toByte(), 78, 230.toByte(), 97, 238.toByte()).copyInto(b, 0)
        mintBytes.copyInto(b, 8); le(solLamports).copyInto(b, 40); le(tokRaw).copyInto(b, 48)
        b[56] = if (buy) 1 else 0
        le(tsSec).copyInto(b, 89); le(30_000_000_000L).copyInto(b, 97); le(1_000_000_000_000_000L).copyInto(b, 105)
        return b
    }

    @Test fun theParsedStreamDecodesCurveAndPumpSwapSwapsOfIncludedMintsOntoTheTape() {
        TradeTape8100.resetForTest8100(); PumpSwapTape8307.clearForTest8307()
        val curveBytes = ByteArray(32) { (it + 11).toByte() }
        val curveMint = io.github.novacrypto.base58.Base58.base58Encode(curveBytes)
        val ammMint = b58(40)
        val now = 1_760_000_100_000L
        val enc = java.util.Base64.getEncoder()
        val logs = org.json.JSONArray()
            .put("Program log: Instruction: Buy")
            .put("Program data: " + enc.encodeToString(curveTradeEvent(curveBytes, 200_000_000L, 5_000_000_000L, true, 1_760_000_090L)))
            .put("Program data: " + enc.encodeToString(pumpSwapEvent(true, 2_000_000_000L, 500_000_000L, 1_760_000_095L)))
        val result = org.json.JSONObject().put("signature", "sigE").put("transaction", org.json.JSONObject()
            .put("transaction", org.json.JSONObject().put("message", org.json.JSONObject()
                .put("accountKeys", org.json.JSONArray().put(org.json.JSONObject().put("pubkey", ammMint)))))
            .put("meta", org.json.JSONObject().put("err", org.json.JSONObject.NULL).put("logMessages", logs)))
        assertEquals(2, PumpSwapTape8307.onEnhancedTx8307(result, setOf(curveMint, ammMint), now))
        val c = TradeTape8100.prints8128(curveMint, now, 120_000L)
        assertEquals(1, c.size)
        assertEquals(TradeTape8100.VENUE_CURVE_8285, c[0].pool)
        assertEquals(0.2 / 5_000.0, c[0].priceSol, 1e-15)
        val a = TradeTape8100.prints8128(ammMint, now, 120_000L)
        assertEquals(1, a.size)
        assertEquals(poolB58, a[0].pool)
        assertEquals(ammMint, PumpSwapTape8307.mintForPool8307(poolB58))
        // A failed transaction, or a mint not included, never becomes a print.
        val failed = org.json.JSONObject(result.toString())
        failed.getJSONObject("transaction").getJSONObject("meta").put("err", org.json.JSONObject().put("InstructionError", 1))
        assertEquals(0, PumpSwapTape8307.onEnhancedTx8307(failed, setOf(curveMint, ammMint), now))
        TradeTape8100.resetForTest8100()
        assertEquals(0, PumpSwapTape8307.onEnhancedTx8307(org.json.JSONObject(result.toString()).put("signature", "sigF"), setOf(b58(90)), now))
    }

    @Test fun theParsedStreamIsBoundedByABudgetedWidthAndHeldCoinsNeverDrop() {
        // superseded by V5.0.8317: the width halves only past 3 MB/min and never under 6.
        assertEquals(6, PumpSwapTape8307.nextTapeWidth8307(12, 4_000_000L))
        assertEquals(6, PumpSwapTape8307.nextTapeWidth8307(3, 4_000_000L))
        assertEquals(12, PumpSwapTape8307.nextTapeWidth8307(11, 100_000L))
        assertEquals(8, PumpSwapTape8307.nextTapeWidth8307(8, 700_000L))
        val held = listOf(b58(1), b58(2))
        val near = (3..20).map { b58(it) }
        assertEquals(4, PumpSwapTape8307.tapeSet8307(held, near, 4, false).size)
        assertEquals(held, PumpSwapTape8307.tapeSet8307(held, near, 4, true))
        assertEquals(held, PumpSwapTape8307.tapeSet8307(held, near, 1, false))
        assertNull(PumpSwapTape8307.attributeMint8307(listOf("a", "b"), setOf("a", "b")))
        assertEquals("a", PumpSwapTape8307.attributeMint8307(listOf("a", "x"), setOf("a", "b")))
        assertTrue(com.lifecyclebot.network.HeliusEnhancedWS.tapeResubDue8307(true, 1_000L, 1_001L))
        assertFalse(com.lifecyclebot.network.HeliusEnhancedWS.tapeResubDue8307(false, 1_000L, 30_000L))
        assertTrue(com.lifecyclebot.network.HeliusEnhancedWS.tapeResubDue8307(false, 1_000L, 61_000L))
        assertEquals("UNSUPPORTED_PLAN", com.lifecyclebot.network.HeliusEnhancedWS.capabilityFromError8307(-32601, "Method not found"))
        assertEquals("UNSUPPORTED_PLAN", com.lifecyclebot.network.HeliusEnhancedWS.capabilityFromError8307(-32603, "transactionSubscribe is not available on your plan"))
        assertEquals("ERROR", com.lifecyclebot.network.HeliusEnhancedWS.capabilityFromError8307(-32602, "invalid params"))
        val ws = src("network/HeliusEnhancedWS.kt")
        assertTrue(ws.contains("meterWs(\"HELIUS_TAPE_8307\", text.length)"))
        assertTrue(ws.contains("PumpSwapTape8307.onEnhancedTx8307(result, tapeIncluded8307)"))
        assertTrue(src("engine/DataOrchestrator.kt").contains("syncHeliusTape8307(held, nowMs)"))
    }

    @Test fun theOnePaidHistoryPageAlsoFillsTheTape() {
        val h = com.lifecyclebot.engine.truth.HeliusSwapCandles7819
        assertEquals(TradeTape8100.VENUE_CURVE_8285, h.backfillVenue8307("CurveKey", "CurveKey"))
        assertEquals("PoolAddr", h.backfillVenue8307("PoolAddr", "CurveKey"))
        assertEquals("PoolAddr", h.backfillVenue8307("PoolAddr", null))
        val arr = org.json.JSONArray().put(org.json.JSONObject().put("signature", "sigH").put("timestamp", 1_760_000_000L)
            .put("feePayer", "Trader").put("events", org.json.JSONObject().put("swap", org.json.JSONObject()
                .put("nativeInput", org.json.JSONObject().put("amount", "500000000"))
                .put("tokenOutputs", org.json.JSONArray().put(org.json.JSONObject().put("mint", "MintH")
                    .put("rawTokenAmount", org.json.JSONObject().put("tokenAmount", "2000000000").put("decimals", 6)))))))
        val p = h.parseSwapPrints7819(arr, "MintH")
        assertEquals(1, p.size)
        assertEquals("sigH", p[0].signature)
        assertTrue(src("engine/truth/HeliusSwapCandles7819.kt").contains("tapeBackfill8307(ts.mint, prints, marketAddress)"))
    }

    @Test fun dasAuthorityFactsAreParsedWithoutInventingAnything() {
        val o = org.json.JSONObject().put("id", "MintD").put("token_info", org.json.JSONObject()
            .put("mint_authority", org.json.JSONObject.NULL).put("freeze_authority", "FreezeAuth").put("decimals", 6)
            .put("supply", 1.0e15).put("token_program", "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"))
            .put("mint_extensions", org.json.JSONObject().put("transfer_hook", org.json.JSONObject()))
        val a = com.lifecyclebot.network.HeliusDasAuthority8307.parseAsset8307(o, 1_000L)!!
        assertEquals("", a.mintAuthority)          // stated: renounced
        assertEquals("FreezeAuth", a.freezeAuthority)
        assertEquals(6, a.decimals)
        assertEquals(setOf("transfer_hook"), a.extensions)
        val bare = com.lifecyclebot.network.HeliusDasAuthority8307.parseAsset8307(org.json.JSONObject().put("id", "MintE"), 1_000L)!!
        assertNull(bare.mintAuthority)            // not stated = unknown, never "renounced"
        assertNull(bare.freezeAuthority)
        assertNull(bare.decimals)
        assertNull(com.lifecyclebot.network.HeliusDasAuthority8307.parseAsset8307(org.json.JSONObject(), 1_000L))
    }
}
