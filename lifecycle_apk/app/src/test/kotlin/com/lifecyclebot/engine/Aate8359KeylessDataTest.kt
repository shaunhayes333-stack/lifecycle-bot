package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.KeylessMarks8359
import com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075
import com.lifecyclebot.network.DexHostBudget8287
import com.lifecyclebot.network.DexHostBudget8287.Prio8287
import com.lifecyclebot.network.KeylessRpc8359
import com.lifecyclebot.network.KeylessRpc8359.Route8359
import com.lifecyclebot.network.OhlcvRoute8332
import com.lifecyclebot.network.PumpFunCandles8359
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8359 — market-data reads keyless first (owner: "free data points that require no key and don't affect my credit
 * balance"); execution truth stays on Helius; per-host keyless governors; shared per-mint TTL cache; getMultipleAccounts
 * batching; shadow-book mark coverage (KeylessMarks8359); pump.fun candles behind the tape; GeckoTerminal not left dead.
 */
class Aate8359KeylessDataTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val helius = "https://mainnet.helius-rpc.com/?api-key=k"
    private val alchemy = "https://solana-mainnet.g.alchemy.com/v2/k"
    private val beta = "https://api.mainnet-beta.solana.com"
    private val publicnode = "https://solana-rpc.publicnode.com"

    @Test fun marketReadsAreKeylessFirstAndExecutionReadsStayOnHelius() {
        for (m in listOf("getAccountInfo", "getMultipleAccounts", "getTokenSupply",
            "getSignaturesForAddress", "getBalance", "getTokenAccountsByOwner", "getTransaction")) {
            assertEquals(m, Route8359.MARKET, KeylessRpc8359.route8359(m))
        }
        // superseded (8481): the live buy's holder proof reads Helius first (8480: holders-7379 transport=68)
        assertEquals(Route8359.EXECUTION, KeylessRpc8359.route8359("getTokenLargestAccounts"))
        // the owner's wallet and our own transactions are execution truth
        for (m in listOf("getBalance", "getTokenAccountsByOwner", "getTransaction", "getSignaturesForAddress")) {
            assertEquals(m, Route8359.EXECUTION, KeylessRpc8359.route8359(m, ownWallet = true))
        }
        for (m in listOf("sendTransaction", "simulateTransaction", "getLatestBlockhash", "getSignatureStatuses", "getFeeForMessage",
            "getPriorityFeeEstimate", "getTokenAccountBalance")) {
            assertEquals(m, Route8359.EXECUTION, KeylessRpc8359.route8359(m))
        }
        for (m in listOf("getAsset", "getAssetBatch", "searchAssets", "getTransactionsForAddress", "getProgramAccounts")) {
            assertEquals(m, Route8359.HELIUS_ONLY, KeylessRpc8359.route8359(m))
        }
        assertEquals("an unknown method keeps the existing ladder", Route8359.EXECUTION, KeylessRpc8359.route8359("someNewMethod"))
    }

    @Test fun theMarketLadderPutsOpenKeylessRungsFirstAndClosedOnesBehindHelius() {
        val ladder = listOf(helius, alchemy, publicnode, beta)
        assertEquals(listOf(beta, publicnode, helius, alchemy), KeylessRpc8359.order8359(ladder, Route8359.MARKET, { true }))
        // a keyless host whose governor is closed goes behind the paid rungs
        assertEquals(listOf(publicnode, helius, alchemy, beta),
            KeylessRpc8359.order8359(ladder, Route8359.MARKET, { it != beta }))
        // execution / DAS routes keep the ladder exactly (Helius leads)
        assertEquals(ladder, KeylessRpc8359.order8359(ladder, Route8359.EXECUTION, { true }))
        assertEquals(ladder, KeylessRpc8359.order8359(ladder, Route8359.HELIUS_ONLY, { true }))
        assertTrue(KeylessRpc8359.isKeyless8359("$beta/"))
        assertFalse(KeylessRpc8359.isKeyless8359(helius))
        assertEquals("helius", KeylessRpc8359.label8359(helius))
        assertEquals("rpc_api.mainnet-beta.solana.com", KeylessRpc8359.label8359(beta))
    }

    @Test fun theKeylessGovernorCapsEachHostAndBacksOffOn429() {
        val g = KeylessRpc8359.Governor8359(limit = 3, windowMs = 10_000L)
        assertTrue(g.tryTake8359(0L)); assertTrue(g.tryTake8359(1L)); assertTrue(g.tryTake8359(2L))
        assertFalse("the fourth request inside 10 s is not sent", g.tryTake8359(3L))
        assertTrue("the window slides", g.tryTake8359(10_001L))
        g.on429_8359(20_000L)
        assertFalse(g.tryTake8359(25_000L))
        assertEquals(5_000L, g.closedLeftMs8359(25_000L))
        assertTrue(g.tryTake8359(30_000L))
        g.on429_8359(31_000L)                 // second consecutive: 20 s
        assertEquals(20_000L, g.closedLeftMs8359(31_000L))
        g.onOk8359()
        assertEquals(0, g.level8359())
        g.on429_8359(60_000L, retryAfterMs = 90_000L)   // a stated Retry-After longer than the ladder is honoured
        assertEquals(90_000L, g.closedLeftMs8359(60_000L))
        repeat(10) { g.on429_8359(200_000L) }
        assertTrue(g.closedLeftMs8359(200_000L) <= KeylessRpc8359.BACKOFF_MAX_MS_8359)
        assertTrue(KeylessRpc8359.rpcRateLimited8359(-32429))
        assertTrue(KeylessRpc8359.rpcRateLimited8359(-32005))
        assertFalse(KeylessRpc8359.rpcRateLimited8359(-32602))
        // api.mainnet-beta documents 100 / 10 s per IP and 40 / 10 s per method: this app stays under both
        assertTrue(KeylessRpc8359.limitFor8359("api.mainnet-beta.solana.com") < 40)
    }

    @Test fun theSharedTtlCacheServesSupplyForTenMinutesAndLargestAccountsForSixtySeconds() {
        val supply = KeylessRpc8359.MintTtlCache8359(KeylessRpc8359.SUPPLY_TTL_MS_8359)
        val v = JSONObject().put("value", JSONObject().put("amount", "1000000000000000").put("decimals", 6))
        supply.put8359("MINT", v, 0L)
        assertNotNull(supply.get8359("MINT", 9L * 60_000L))
        assertNull(supply.get8359("MINT", 10L * 60_000L))
        val largest = KeylessRpc8359.MintTtlCache8359(KeylessRpc8359.LARGEST_TTL_MS_8359)
        largest.put8359("MINT", v, 0L)
        assertNotNull(largest.get8359("MINT", 59_000L))
        assertNull(largest.get8359("MINT", 60_000L))
        assertEquals(10L * 60_000L, KeylessRpc8359.SUPPLY_TTL_MS_8359)
        assertEquals(60_000L, KeylessRpc8359.LARGEST_TTL_MS_8359)
        assertTrue(KeylessRpc8359.cacheFor8359("getTokenSupply") === KeylessRpc8359.supplyCache8359)
        assertTrue(KeylessRpc8359.cacheFor8359("getTokenLargestAccounts") === KeylessRpc8359.largestCache8359)
        assertNull(KeylessRpc8359.cacheFor8359("getAccountInfo"))
        // the supply reader parses the cached value exactly as a fresh answer
        val parsed = OnChainSupplyAuthority7075.supplyFromValue8359(v.optJSONObject("value"))
        assertEquals(OnChainSupplyAuthority7075.Outcome7116.RESOLVED, parsed.first)
        assertEquals(1_000_000_000.0, parsed.second!!, 1e-6)
        assertEquals(OnChainSupplyAuthority7075.Outcome7116.NOT_FOUND_YET_7809, OnChainSupplyAuthority7075.supplyFromValue8359(null).first)
    }

    @Test fun getMultipleAccountsBatchesHoldAtMostOneHundredKeysInOrder() {
        val keys = (1..250).map { "K$it" } + listOf("K3", "", "K7")
        val b = KeylessRpc8359.batches8359(keys)
        assertEquals(3, b.size)
        assertEquals(100, b[0].size); assertEquals(100, b[1].size); assertEquals(50, b[2].size)
        assertEquals((1..250).map { "K$it" }, b.flatten())
        assertEquals(listOf(listOf("A", "B"), listOf("C")), KeylessRpc8359.batches8359(listOf("A", "B", "C"), max = 2))
        assertEquals(100, KeylessRpc8359.batches8359((1..500).map { "x$it" }, max = 999)[0].size)
    }

    @Test fun aLaggingSlotIsStaleAndTheNewerAnswerIsPreferred() {
        assertTrue(KeylessRpc8359.staleSlot8359(answerSlot = 1_000L, bestSlot = 1_200L))
        assertFalse(KeylessRpc8359.staleSlot8359(answerSlot = 1_100L, bestSlot = 1_200L))
        assertFalse("no recent best slot: nothing to compare", KeylessRpc8359.staleSlot8359(1_000L, 0L))
        assertFalse("no context: never stale", KeylessRpc8359.staleSlot8359(0L, 1_200L))
        assertEquals(150L, KeylessRpc8359.STALE_SLOTS_8359)
        assertEquals(listOf("api.mainnet-beta.solana.com", "solana-rpc.publicnode.com"), KeylessRpc8359.KEYLESS_8359.map { KeylessRpc8359.host8359(it) })
        assertTrue(KeylessRpc8359.governor8359("api.mainnet-beta.solana.com") === KeylessRpc8359.governor8359("api.mainnet-beta.solana.com"))
        KeylessRpc8359.resetForTests8359()
        val fresh = JSONObject().put("result", JSONObject().put("context", JSONObject().put("slot", 5_000L)))
        val old = JSONObject().put("result", JSONObject().put("context", JSONObject().put("slot", 4_000L)))
        assertFalse(KeylessRpc8359.staleAnswer8359(beta, fresh, 1_000L))
        assertTrue(KeylessRpc8359.staleAnswer8359(publicnode, old, 2_000L))
        KeylessRpc8359.resetForTests8359()
    }

    @Test fun wantedMintsComeRightAfterHeldPositions() {
        assertEquals(listOf("H1", "H2", "W1", "W2"), KeylessMarks8359.priorityOrder8359(listOf("H1", "H2"), listOf("H2", "W1", "W2"), 10))
        assertEquals(listOf("H1", "H2", "W1"), KeylessMarks8359.priorityOrder8359(listOf("H1", "H2"), listOf("W1", "W2"), 3))
        assertEquals(listOf("H1"), KeylessMarks8359.priorityOrder8359(listOf("H1", "H2"), listOf("W1"), 1))
        assertTrue(KeylessMarks8359.curveLike8359("abcpump", hasKey = false, graduated = false))
        assertTrue(KeylessMarks8359.curveLike8359("abc", hasKey = true, graduated = false))
        assertFalse(KeylessMarks8359.curveLike8359("abcpump", hasKey = false, graduated = true))
        assertFalse(KeylessMarks8359.curveLike8359("abc", hasKey = false, graduated = false))
        KeylessMarks8359.resetForTests8359()
        val now = System.currentTimeMillis()
        val w = "So11111111111111111111111111111111111pump"
        KeylessMarks8359.putWantedForTests8359(w, now + 60_000L)
        KeylessMarks8359.putWantedForTests8359("Expired1111111111111111111111111111111pump", now - 1L)
        assertTrue(KeylessMarks8359.isWanted8359(w, now))
        assertEquals(listOf(w), KeylessMarks8359.wantedMints8359(now))
        assertEquals(listOf("HeldMint", w), KeylessMarks8359.withWantedCurves8359(listOf("HeldMint"), 40))
        KeylessMarks8359.resetForTests8359()
        assertTrue(KeylessMarks8359.tickDue8359(10_000L, 0L))
        assertFalse(KeylessMarks8359.tickDue8359(9_999L, 0L))
        // a wanted mint reads as near-decision on the DexScreener lane (after HELD)
        assertTrue(src("engine/DataPipeline8191.kt").contains("DataPipeline8191.nearDecision8191(mint, nowMs) || wanted8359"))
    }

    @Test fun theDexHostUsesTheDocumentedHeadroomOnlyAfterACleanSession() {
        val b = DexHostBudget8287.Bucket8287()
        assertEquals(DexHostBudget8287.PER_MIN_8287, b.perMin8359(0L))
        assertEquals(DexHostBudget8287.PER_MIN_8287, b.perMin8359(DexHostBudget8287.CLEAN_AFTER_MS_8359 - 1L))
        assertEquals(DexHostBudget8287.PER_MIN_CLEAN_8359, b.perMin8359(DexHostBudget8287.CLEAN_AFTER_MS_8359))
        assertTrue(DexHostBudget8287.PER_MIN_CLEAN_8359 + DexHostBudget8287.BURST_8287 < 300.0)
        b.noteLimited8287(DexHostBudget8287.CLEAN_AFTER_MS_8359 + 1L, 5_000L)
        assertEquals("a 429 drops it back for the session", DexHostBudget8287.PER_MIN_8287, b.perMin8359(DexHostBudget8287.CLEAN_AFTER_MS_8359 * 3))
        assertEquals(0L, b.take8287(DexHostBudget8287.CLEAN_AFTER_MS_8359 * 3, Prio8287.EXIT))
    }

    @Test fun pumpFunCandleUnitsAreCheckedNeverGuessed() {
        assertEquals(1.0, PumpFunCandles8359.unitScale8359(0.00012, 0.0001, 150.0)!!, 0.0)          // USD closes
        assertEquals(150.0, PumpFunCandles8359.unitScale8359(0.0000008, 0.0001, 150.0)!!, 0.0)      // SOL closes
        assertNull(PumpFunCandles8359.unitScale8359(0.5, 0.0001, 150.0))                            // neither: dropped
        assertNull(PumpFunCandles8359.unitScale8359(0.0001, 0.0, 150.0))
        // nothing cached and the fetch is off-thread: the caller's read never waits on pump.fun
        assertNull(PumpFunCandles8359.candles8359("NoSuchMint", 30, 0.0001, 150.0, 0L))
        assertEquals(60_000L, PumpFunCandles8359.backoffMs8359(0))
        assertEquals(15L * 60_000L, PumpFunCandles8359.backoffMs8359(9))
        assertTrue(PumpFunCandles8359.url8359("M", 5).startsWith("https://frontend-api-v3.pump.fun/candlesticks/M?offset=0&limit=10&timeframe=1"))
        val feed = src("network/SolanaOhlcvFeed6916.kt")
        assertTrue(feed.contains("observedCandles8287(mint, n, nowMs) ?: return pumpFunStep8359(mint, n, nowMs)"))
    }

    @Test fun aPoolLessMintNeverDeclaresGeckoTerminalDead() {
        OhlcvRoute8332.resetForTest8332()
        repeat(OhlcvRoute8332.DEAD_AFTER_ZERO_ANSWERS_8332 * 3) { OhlcvRoute8332.notePoolMiss8359(OhlcvRoute8332.GECKO_8332) }
        assertFalse(OhlcvRoute8332.dead8332(OhlcvRoute8332.GECKO_8332))
        assertTrue(OhlcvRoute8332.statusLine8332().contains("poolMiss8359{gecko=36}"))
        OhlcvRoute8332.resetForTest8332()
        val feed = src("network/SolanaOhlcvFeed6916.kt")
        assertTrue(feed.contains("OhlcvRoute8332.notePoolMiss8359(OhlcvRoute8332.GECKO_8332)"))
        assertTrue("a served answer still revives", feed.contains("OhlcvRoute8332.noteAnswer8332(OhlcvRoute8332.GECKO_8332, chronological.size, now)"))
    }

    @Test fun theHookSitesRouteMarketReadsKeylessFirst() {
        val rpa = src("engine/RuntimeProviderAuthority6685.kt")
        assertTrue(rpa.contains("fun marketReadCandidates8359(primary: String? = null, method: String = \"getAccountInfo\""))
        assertTrue(rpa.contains("com.lifecyclebot.network.KeylessRpc8359.marketLadder8359(ladder, method)"))
        val fan = src("network/ParallelMarkFanout7088.kt")
        assertTrue(fan.contains("RuntimeProviderAuthority6685.marketReadCandidates8359(url, \"getMultipleAccounts\").take(CURVE_LADDER_RUNGS_7279)"))
        assertTrue(fan.contains("if (!KeylessRpc8359.beforeCall8359(rung)) continue"))
        assertTrue(fan.contains("if (KeylessRpc8359.staleAnswer8359(rung, root7278)) return@use"))
        assertTrue(fan.contains("KeylessMarks8359.withWantedCurves8359(mints, CURVE_MAX_MINTS_7269)"))
        assertTrue(fan.contains("KeylessMarks8359.onCurveMarks8359(out)"))
        assertTrue(src("engine/truth/OnChainDepth8038.kt").contains("KeylessRpc8359.post8359(http, method, params, \"8038\", maxRungs = 3)"))
        val holders = src("engine/truth/OnChainHolderConcentration7379.kt")
        assertTrue(holders.contains("KeylessRpc8359.post8359(http, method, params, \"holders-7379\", maxRungs = 4, cacheKey = mintKey8359)"))
        assertFalse("Helius no longer leads holder reads", holders.contains("if (helius.isNotBlank()) add(helius)"))
        val supply = src("engine/truth/OnChainSupplyAuthority7075.kt")
        assertTrue(supply.contains("RuntimeProviderAuthority6685.marketReadCandidates8359(url, \"getTokenSupply\")"))
        // superseded by V5.0.8418 — the cached answer is read WITH its receipt time (the supply's observation time).
        assertTrue(supply.contains("KeylessRpc8359.supplyCache8359.getAt8418(mint, System.currentTimeMillis())"))
        val birth = src("engine/truth/TokenBirthHydrator7441.kt")
        assertTrue(birth.contains("RuntimeProviderAuthority6685.marketReadCandidates8359(null, \"getSignaturesForAddress\")"))
        assertTrue(birth.contains("KeylessRpc8359.beforeCall8359(rpc)"))
        // execution stays on Helius: the wallet / sell / send ladders are untouched
        assertTrue(src("network/SolanaWallet.kt").contains("RuntimeProviderAuthority6685.rpcCandidates(rpcUrl)"))
        assertTrue(src("engine/WalletManager.kt").contains("RuntimeProviderAuthority6685.rpcCandidates(rpcUrl, ctx)"))
        assertTrue(src("engine/sell/SellRouteProbe8144.kt").contains("RuntimeProviderAuthority6685.rpcCandidates(w.rpcUrl)"))
        // the shadow books' entry point keeps its exact signature
        assertTrue(src("engine/truth/KeylessMarks8359.kt").contains("fun noteWanted8359(mint: String, untilMs: Long)"))
        assertTrue(src("engine/DataPipeline8191.kt").contains("KeylessRpc8359.statusLine8359()"))
    }
}
