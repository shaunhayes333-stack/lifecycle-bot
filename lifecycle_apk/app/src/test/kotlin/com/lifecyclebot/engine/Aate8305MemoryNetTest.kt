package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SpecialistContributorMerge6612
import com.lifecyclebot.network.BodyTooLargeException8305
import com.lifecyclebot.network.HttpBodyCap8305
import com.lifecyclebot.network.NetParseGuard8305
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import com.lifecyclebot.perps.InsiderWalletTracker
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * V5.0.8305 — 5.0.8302 died of OutOfMemoryError after 62 minutes, on an OkHttp dispatcher thread inside
 * JSONObject(String). Response bodies are read through a per-endpoint byte cap, expendable stream frames are size-capped
 * and an OOM while parsing one drops it and trims instead of killing the process, the smart-money push no longer runs a
 * whole-wallet scan per frame, and the stores found growing are bounded and counted in the census.
 */
class Aate8305MemoryNetTest {

    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun response(body: ResponseBody, code: Int = 200, host: String = "api.dexscreener.com"): Response =
        Response.Builder()
            .request(Request.Builder().url("https://$host/latest/dex/search?q=x").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("x").body(body).build()

    /** A body with no declared length (chunked / decompressed): only the counting source can stop it. */
    private fun streamed(bytes: Int, type: String = "application/json"): ResponseBody {
        val buf = Buffer().write(ByteArray(bytes) { 'a'.code.toByte() })
        return object : ResponseBody() {
            override fun contentType(): MediaType? = type.toMediaType()
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = buf
        }
    }

    // ── body cap ──

    @Test fun capsArePerEndpointAndGenerousForExecutionAnswers() {
        assertEquals(HttpBodyCap8305.RPC_CAP_8305, HttpBodyCap8305.capFor8305("mainnet.helius-rpc.com", "/"))
        assertEquals(HttpBodyCap8305.RPC_CAP_8305, HttpBodyCap8305.capFor8305("api.mainnet-beta.solana.com", "/"))
        assertEquals(HttpBodyCap8305.MARKET_CAP_8305, HttpBodyCap8305.capFor8305("api.dexscreener.com", "/latest/dex/search"))
        assertEquals(HttpBodyCap8305.MARKET_CAP_8305, HttpBodyCap8305.capFor8305("public-api.birdeye.so", "/defi/price"))
        assertEquals(HttpBodyCap8305.TOKEN_LIST_CAP_8305, HttpBodyCap8305.capFor8305("lite-api.jup.ag", "/tokens/v2/tag"))
        assertEquals(HttpBodyCap8305.DEFAULT_CAP_8305, HttpBodyCap8305.capFor8305("lite-api.jup.ag", "/swap/v1/quote"))
        assertEquals(HttpBodyCap8305.LLM_CAP_8305, HttpBodyCap8305.capFor8305("api.groq.com", "/openai/v1/chat"))
        // every cap is far above an execution answer (quote / swap / tx / balance: a few KB to a few hundred KB)
        for (c in listOf(HttpBodyCap8305.RPC_CAP_8305, HttpBodyCap8305.MARKET_CAP_8305, HttpBodyCap8305.DEFAULT_CAP_8305, HttpBodyCap8305.LLM_CAP_8305))
            assertTrue(c >= 2L shl 20)
        assertEquals("api.dexscreener.com/latest", HttpBodyCap8305.endpointKey8305("api.dexscreener.com", "/latest/dex/search"))
    }

    @Test fun anOversizeStreamedBodyIsRefusedWhileReadNeverBecomingAString() {
        HttpBodyCap8305.resetForTest8305()
        val over = (HttpBodyCap8305.MARKET_CAP_8305 + 64 * 1024).toInt()
        val r = HttpBodyCap8305.wrap8305(response(streamed(over)), "api.dexscreener.com", "/latest/dex/search")
        try {
            r.body!!.string()
            fail("an oversize body must not be read whole")
        } catch (e: BodyTooLargeException8305) {
            assertEquals("api.dexscreener.com/latest", e.endpoint)
            assertEquals(HttpBodyCap8305.MARKET_CAP_8305, e.capBytes)
        }
        assertEquals(1L, HttpBodyCap8305.refusedTotal8305())
        assertEquals("bytes counted in flight are released on refusal", 0L, HttpBodyCap8305.inFlightBytes8305())
        assertTrue(HttpBodyCap8305.line8305(), HttpBodyCap8305.line8305().contains("refused=1(api.dexscreener.com/latest:1)"))
    }

    @Test fun aBodyUnderItsCapReadsWholeAndIsMeasured() {
        HttpBodyCap8305.resetForTest8305()
        val json = "{\"pairs\":[" + (0 until 2_000).joinToString(",") { "{\"i\":$it}" } + "]}"
        val r = HttpBodyCap8305.wrap8305(response(json.toResponseBody("application/json".toMediaType())), "api.dexscreener.com", "/latest/dex/search")
        assertEquals(json, r.body!!.string())
        assertEquals(0L, HttpBodyCap8305.refusedTotal8305())
        assertEquals(0L, HttpBodyCap8305.inFlightBytes8305())
        assertTrue(HttpBodyCap8305.line8305(), HttpBodyCap8305.line8305().contains("read=1"))
    }

    @Test fun aDeclaredOversizeLengthIsRefusedBeforeAnyByteIsRead() {
        HttpBodyCap8305.resetForTest8305()
        val big = ByteArray((HttpBodyCap8305.MARKET_CAP_8305 + 1).toInt())
        try {
            HttpBodyCap8305.wrap8305(response(big.toResponseBody("application/json".toMediaType())), "api.dexscreener.com", "/x")
            fail("declared oversize must be refused")
        } catch (_: BodyTooLargeException8305) {}
        assertEquals(1L, HttpBodyCap8305.refusedTotal8305())
    }

    @Test fun upgradesAndEventStreamsPassThroughUnwrapped() {
        val ws = response("".toResponseBody(null), code = 101)
        assertTrue(HttpBodyCap8305.wrap8305(ws, "mainnet.helius-rpc.com", "/") === ws)
        val sse = response(streamed(10, "text/event-stream"))
        assertTrue(HttpBodyCap8305.wrap8305(sse, "hermes.pyth.network", "/v2/updates") === sse)
    }

    @Test fun theSharedClientCapsEveryBodyOutermost() {
        val shared = src("network/SharedHttpClient.kt")
        val cap = shared.indexOf(".addInterceptor(com.lifecyclebot.network.HttpBodyCap8305)")
        assertTrue(cap > 0)
        // outermost: before the rate / circuit / gzip interceptors, so it counts decompressed bytes
        assertTrue(cap < shared.indexOf(".addInterceptor(com.lifecyclebot.network.DexRateInterceptor8287)"))
        assertTrue(cap < shared.indexOf(".addInterceptor { chain ->"))
        assertTrue(com.lifecyclebot.network.SharedHttpClient.base.interceptors.first() === HttpBodyCap8305)
    }

    // ── expendable frames ──

    @Test fun anOversizeFrameIsDroppedBeforeParsing() {
        NetParseGuard8305.resetForTest8305()
        assertTrue(NetParseGuard8305.admit8305(100, 100))
        assertFalse(NetParseGuard8305.admit8305(101, 100))
        val frame = "{\"a\":\"" + "x".repeat(NetParseGuard8305.FRAME_CAP_SMALL_8305) + "\"}"
        assertNull(NetParseGuard8305.parseObject8305("PUMP_PORTAL", frame))
        assertEquals(1L, NetParseGuard8305.oversize8305())
        assertNotNull(NetParseGuard8305.parseObject8305("PUMP_PORTAL", "{\"txType\":\"buy\"}"))
        assertNull("malformed frames are dropped quietly", NetParseGuard8305.parseObject8305("PUMP_PORTAL", "not json"))
        assertTrue(NetParseGuard8305.line8305().contains("oversizeDropped=1(PUMP_PORTAL:1)"))
    }

    @Test fun anOutOfMemoryErrorWhileParsingDropsTheFrameAndAsksForATrim() {
        NetParseGuard8305.resetForTest8305()
        val trims = mutableListOf<String>()
        val prior = NetParseGuard8305.onOom8305
        NetParseGuard8305.onOom8305 = { s -> trims += s }
        try {
            val r: org.json.JSONObject? = NetParseGuard8305.guard8305("SMART_MONEY_STREAM") { throw OutOfMemoryError("Failed to allocate a 48 byte allocation") }
            assertNull(r)
            assertEquals(1L, NetParseGuard8305.parseOoms8305())
            assertEquals(listOf("SMART_MONEY_STREAM"), trims)
            NetParseGuard8305.dropOom8305("PUMP_PORTAL")
            assertEquals(2L, NetParseGuard8305.parseOoms8305())
            assertEquals(listOf("SMART_MONEY_STREAM", "PUMP_PORTAL"), trims)
        } finally { NetParseGuard8305.onOom8305 = prior }
        // anything that is not an OOM is not swallowed by the guard
        try { NetParseGuard8305.guard8305("X") { throw IllegalStateException("boom") }; fail() } catch (_: IllegalStateException) {}
    }

    @Test fun theDefaultOomHookRunsAnEmergencyHardTrimAtMostOncePerTenSeconds() {
        assertTrue(MemoryGuard7977.emergencyDue8305(0L, 1_000L))
        assertFalse(MemoryGuard7977.emergencyDue8305(1_000L, 10_999L))
        assertTrue(MemoryGuard7977.emergencyDue8305(1_000L, 11_000L))
        val g = src("engine/MemoryGuard7977.kt")
        assertTrue(g.contains("if (!emergencyDue8305(last, now) || !emergencyAt8305.compareAndSet(last, now))"))
        assertTrue(g.contains("try { trimAll(true) } finally { pressure8136 = prior8305 }"))
        assertTrue(g.contains("private fun trimAll(hard: Boolean) = synchronized(trimLock8305) { trimAllLocked8305(hard) }"))
        assertTrue(src("network/NetParseGuard8305.kt").contains("MemoryGuard7977.emergencyTrim8305(\"PARSE_OOM_\$stream\")"))
    }

    @Test fun everyExpendableStreamParsesThroughTheGuardAndNoneCanDieOfAnOom() {
        val streams = mapOf(
            "network/HeliusEnhancedWS.kt" to "\"SMART_MONEY_STREAM\"", "network/PumpFunWS.kt" to "\"PUMP_PORTAL\"",
            "network/PumpFunWebSocket.kt" to "\"PUMP_FUN_LEGACY\"", "network/HeliusWebSocket.kt" to "\"HELIUS_LOGS\"",
            "network/DexScreenerWebSocket.kt" to "\"DEXSCREENER_WS\"", "network/GradTicks8018.kt" to "\"GRAD_TICKS\"",
            "network/CurveTicks7968.kt" to "\"CURVE_TICKS\"", "network/ChainLaunchFeed8179.kt" to "\"CHAIN_LAUNCH\"",
            "network/PythHermesStream.kt" to "\"PYTH_SSE\"",
        )
        for ((f, name) in streams) {
            val s = src(f)
            assertTrue(f, s.contains("NetParseGuard8305.parseObject8305($name"))
        }
        // handlers that caught only Exception now also drop an OOM thrown while processing the frame
        for (f in listOf("network/HeliusEnhancedWS.kt", "network/PumpFunWS.kt", "network/PumpFunWebSocket.kt", "network/HeliusWebSocket.kt",
                "network/DexScreenerWebSocket.kt", "network/PythHermesStream.kt"))
            assertTrue(f, src(f).contains("} catch (_: OutOfMemoryError) { NetParseGuard8305.dropOom8305("))
        // never on a proof path
        for (f in listOf("engine/Executor.kt", "network/SolanaWallet.kt", "engine/sell/SellAmountAuthority.kt"))
            assertFalse(f, src(f).contains("NetParseGuard8305"))
    }

    // ── what grew ──

    @Test fun theSmartMoneyPushRunsOneInsiderScanAtATime() {
        val ttl = 60_000L
        assertTrue(InsiderWalletTracker.scanAdmit8305(false, 0L, 1_000L))
        assertFalse("never two at once", InsiderWalletTracker.scanAdmit8305(true, 0L, 1_000L))
        assertFalse("inside the holdings TTL a scan finds no delta", InsiderWalletTracker.scanAdmit8305(false, 1_000L, 1_000L + ttl - 1))
        assertTrue(InsiderWalletTracker.scanAdmit8305(false, 1_000L, 1_000L + ttl))
        val s = src("perps/InsiderWalletTracker.kt")
        assertTrue(s.contains("return try { scanForSignalsInner8305() } finally { scanInFlight8305.set(false) }"))
        assertTrue(s.contains("if (tokenPriceCache.size >= TOKEN_PRICE_CACHE_MAX_8305) tokenPriceCache.clear()"))
        assertTrue(InsiderWalletTracker.memCount8305() >= 0)
    }

    @Test fun theJupiterVerifiedListIsReadEverySixHoursNotEveryFiveMinutes() {
        assertTrue(DynamicAltTokenRegistry.jupiterListDue8305(0L, 1L))
        assertFalse(DynamicAltTokenRegistry.jupiterListDue8305(1_000L, 1_000L + 5 * 60_000L))
        assertTrue(DynamicAltTokenRegistry.jupiterListDue8305(1_000L, 1_000L + DynamicAltTokenRegistry.JUPITER_LIST_EVERY_MS_8305))
    }

    @Test fun contributorListsStayBoundedPastTheirCap() {
        val m = SpecialistContributorMerge6612
        m.resetForTest()
        try {
            val n = SpecialistContributorMerge6612.MINTS_CAP_8305 * 2
            for (i in 0 until n) m.recordContributor("C8305$i".padEnd(44, 'M'), "SHITCOIN", 50.0, 60.0, true)
            // all fresh (readable for 5 s): nothing a reader could still be served is dropped
            assertTrue(m.trackedMints() > SpecialistContributorMerge6612.MINTS_CAP_8305)
            // once unreadable, the next bound takes the map back under its cap
            m.boundMints8305(System.currentTimeMillis() + 60_000L)
            assertTrue("size ${m.trackedMints()}", m.trackedMints() <= SpecialistContributorMerge6612.MINTS_CAP_8305)
            assertEquals(1.0, m.boundedSizeMultiplier6612("C83050".padEnd(44, 'M')), 0.0)
        } finally { m.resetForTest() }
    }

    @Test fun mergeQueueEntriesNoPassReachesArePrunedOnceStale() {
        val now = System.currentTimeMillis()
        for (i in 0 until 20) TokenMergeQueue.enqueue("Q8305$i".padEnd(44, 'M'), "Q$i", "PUMP_PORTAL_WS")
        val n = TokenMergeQueue.size()
        assertTrue(n >= 20)
        assertEquals("fresh entries stay", 0, TokenMergeQueue.pruneStale8305(now, 10L * 60_000L))
        assertTrue(TokenMergeQueue.pruneStale8305(now + 60L * 60_000L, 10L * 60_000L) >= 20)
        assertFalse(TokenMergeQueue.isPending("Q83050".padEnd(44, 'M')))
    }

    @Test fun theMemoryLineShowsDispatcherLoadBodiesFramesAndEveryStore() {
        val stores = MemoryGuard7977.registeredStores8144()
        for (s in listOf("insiderTracker8305", "dynAltRegistry8305", "contribMerge8305", "mergeQueue8305", "httpBodiesInFlight8305")) assertTrue(s, s in stores)
        val line = MemoryGuard7977.netLine8305()
        for (k in listOf("dispatcher8305[queued=", "running=", "insiderScan8305[run=", "bodyCap8305[refused=", "frames8305[oversizeDropped=", "emergencyTrims8305=", "stores8305=["))
            assertTrue("$k in $line", line.contains(k))
        val rows = listOf(MemoryRegistry8144.Row8144("a", 10, 4_096L, 0L), MemoryRegistry8144.Row8144("b", -1, 8_192L, 0L))
        assertEquals("b:?/8KB,a:10/4KB", MemoryGuard7977.storesLine8305(rows))
        assertTrue(src("engine/MemoryGuard7977.kt").contains("censusStatus8144 + \" \" + netLine8305()"))
        assertTrue(src("engine/MemoryGuard7977.kt").contains("registerStores8305(r)  // V5.0.8305"))
    }
}
