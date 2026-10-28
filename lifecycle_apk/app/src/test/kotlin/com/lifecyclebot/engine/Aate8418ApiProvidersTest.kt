package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.network.LlmProviderHealth8418
import com.lifecyclebot.network.LlmProviderHealth8418.Kind8418
import com.lifecyclebot.network.PumpFunCandles8359
import com.lifecyclebot.network.RpcCapacityLatch8418
import com.lifecyclebot.network.RpcOutcome8418
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** V5.0.8418 — dead LLM providers read correctly, a spent RPC plan left out, failure causes named, pools and candles. */
class Aate8418ApiProvidersTest {

    @Before fun reset() {
        LlmProviderHealth8418.resetForTests8418()
        RpcCapacityLatch8418.resetForTests8418()
        RpcOutcome8418.resetForTests8418()
    }

    @After fun clean() {
        RpcCapacityLatch8418.resetForTests8418()
        LlmProviderHealth8418.resetForTests8418()
        RpcOutcome8418.resetForTests8418()
    }

    private fun utc(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(y, mo, d, h, mi, 0)
        return c.timeInMillis
    }

    // ── LLM council ──

    @Test fun pollinationsFreeLimit402IsPacingNotATerminalBench() {
        val body = """{"status":402,"success":false,"error":{"code":"FREE_LIMIT","message":"Free limit reached: 1 reply every 30 seconds"}}"""
        val v = LlmProviderHealth8418.classify8418(402, body, null, null, 0L)
        assertNotNull(v)
        assertEquals(Kind8418.RATE, v!!.kind)
        assertEquals(30_000L, v.penaltyMs)
        // The provider's own Retry-After wins over the prose.
        assertEquals(12_000L, LlmProviderHealth8418.classify8418(402, body, "12", null, 0L)!!.penaltyMs)
        // Not owner business: it recovers in seconds.
        assertNull(LlmProviderHealth8418.ownerAction8418("llm_pollinations", 402, body))
    }

    @Test fun openRouterFreeDailyQuotaBenchesUntilTheStatedResetOrUtcMidnight() {
        val body = """{"error":{"message":"Rate limit exceeded: free-models-per-day. Add 10 credits to unlock 1000 free model requests"}}"""
        val now = utc(2026, Calendar.OCTOBER, 28, 22, 0)
        val reset = now + 90L * 60_000L
        val withHeader = LlmProviderHealth8418.classify8418(429, body, null, reset.toString(), now)!!
        assertEquals(Kind8418.QUOTA, withHeader.kind)
        assertEquals(90L * 60_000L, withHeader.penaltyMs)
        val noHeader = LlmProviderHealth8418.classify8418(429, body, null, null, now)!!
        assertEquals(2L * 60L * 60_000L, noHeader.penaltyMs)   // 22:00 -> 00:00 UTC
        // An ordinary per-minute 429 is left to the 7150 rules.
        assertNull(LlmProviderHealth8418.classify8418(429, """{"error":"rate limit"}""", null, null, now))
    }

    @Test fun billingAndRevokedKeysStayTerminalWithEscalatingBenchAndNameTheOwnerAction() {
        val cerebras = """{"message":"Payment required to access this resource. Visit your billing tab.","type":"payment_required_error"}"""
        assertNull("billing is not pacing", LlmProviderHealth8418.classify8418(402, cerebras, null, null, 0L))
        assertEquals(6L * 3_600_000L, LlmProviderHealth8418.noteTerminal8418("llm_cerebras"))
        assertEquals(12L * 3_600_000L, LlmProviderHealth8418.noteTerminal8418("llm_cerebras"))
        assertEquals(24L * 3_600_000L, LlmProviderHealth8418.noteTerminal8418("llm_cerebras"))
        assertEquals(48L * 3_600_000L, LlmProviderHealth8418.noteTerminal8418("llm_cerebras"))
        assertEquals(48L * 3_600_000L, LlmProviderHealth8418.noteTerminal8418("llm_cerebras"))
        assertEquals(5, LlmProviderHealth8418.strikes8418("llm_cerebras"))
        LlmProviderHealth8418.noteSuccess8418("llm_cerebras")
        assertEquals(0, LlmProviderHealth8418.strikes8418("llm_cerebras"))

        assertTrue(LlmProviderHealth8418.ownerAction8418("llm_cerebras", 402, cerebras)!!.contains("billing"))
        assertTrue(LlmProviderHealth8418.ownerAction8418("llm_emergent", 401, "Invalid API key")!!.contains("not owner-settable"))
        assertTrue(LlmProviderHealth8418.ownerAction8418("llm_groq", 401, "Invalid API Key")!!.contains("replace the groq key"))

        LlmProviderHealth8418.noteRefusal8418("llm_cerebras", 402, cerebras)
        LlmProviderHealth8418.noteTerminal8418("llm_cerebras")
        val line = LlmProviderHealth8418.healthLine8418(
            mapOf("llm_cerebras" to (10_000L + 3_600_000L to "TERMINAL"), "llm_old" to (5L to "RATE")), listOf("gemini"), 10_000L,
        )
        assertTrue(line, line.contains("llm_cerebras:TERMINAL(strikes=1,left=3600s)"))
        assertFalse("an expired bench is not shown", line.contains("llm_old"))
        assertTrue(line.contains("gemini: no key configured"))
        assertTrue(line.contains("cerebras: account needs billing"))
    }

    @Test fun theRingsOwnCancelIsNotAProviderNetworkFailure() {
        assertTrue(LlmProviderHealth8418.isOwnCancellation8418(java.io.InterruptedIOException("interrupted")))
        assertTrue(LlmProviderHealth8418.isOwnCancellation8418(java.io.IOException("Canceled")))
        assertFalse(LlmProviderHealth8418.isOwnCancellation8418(java.io.InterruptedIOException("timeout")))
        assertFalse(LlmProviderHealth8418.isOwnCancellation8418(java.net.SocketTimeoutException("Read timed out")))
        assertFalse(LlmProviderHealth8418.isOwnCancellation8418(java.net.UnknownHostException("x")))
    }

    @Test fun pacingRefusesInsideTheIntervalAndCatalogueKeepsChatModels() {
        assertTrue(LlmProviderHealth8418.tryPace8418("pollinations.ai", 30_000L, 1_000L))
        assertFalse(LlmProviderHealth8418.tryPace8418("pollinations.ai", 30_000L, 20_000L))
        assertTrue(LlmProviderHealth8418.tryPace8418("pollinations.ai", 30_000L, 31_000L))
        assertTrue("another host is independent", LlmProviderHealth8418.tryPace8418("other", 30_000L, 31_500L))

        val ids = listOf("Meta-Llama-3_3-70B-Instruct", "bge-multilingual-gemma2", "whisper-large-v3", "Qwen3-32B", "stable-diffusion-xl", "Qwen3-32B")
        assertEquals(listOf("Meta-Llama-3_3-70B-Instruct", "Qwen3-32B"), LlmProviderHealth8418.chatModels8418(ids))

        assertEquals(60_000L, LlmProviderHealth8418.modelCoolMs8418(429))
        assertEquals(0L, LlmProviderHealth8418.modelCoolMs8418(400))
        LlmProviderHealth8418.coolModel8418("ovh:Qwen3-32B", 60_000L, 0L)
        assertTrue(LlmProviderHealth8418.modelCooling8418("ovh:Qwen3-32B", 59_999L))
        assertFalse(LlmProviderHealth8418.modelCooling8418("ovh:Qwen3-32B", 60_000L))
    }

    // ── RPC ──

    @Test fun alchemyMonthlyCapacityLatchesUntilTheNextUtcMonthAndLeavesTheLadder() {
        assertEquals(utc(2027, Calendar.JANUARY, 1), RpcCapacityLatch8418.nextMonthStartUtcMs8418(utc(2026, Calendar.DECEMBER, 31, 23, 59)))
        val now = utc(2026, Calendar.OCTOBER, 28, 12)
        val msg = """{"code":429,"message":"Monthly capacity limit exceeded. Visit https://dashboard.alchemy.com to upgrade"}"""
        RpcCapacityLatch8418.observe8418("helius", msg, now)
        assertFalse("Helius is never latched here", RpcCapacityLatch8418.alchemyExhausted8418(now))
        RpcCapacityLatch8418.observe8418("pump_curve_rpc", "rate limited", now)
        assertFalse(RpcCapacityLatch8418.alchemyExhausted8418(now))
        RpcCapacityLatch8418.observe8418("pump_curve_rpc", msg, now)
        assertTrue(RpcCapacityLatch8418.alchemyExhausted8418(now))
        assertTrue(RpcCapacityLatch8418.alchemyExhausted8418(utc(2026, Calendar.OCTOBER, 31, 23, 59)))
        assertFalse(RpcCapacityLatch8418.alchemyExhausted8418(utc(2026, Calendar.NOVEMBER, 1)))
    }

    @Test fun aLatchedAlchemyKeyIsNotOfferedToAnyLadder() {
        RpcCapacityLatch8418.observe8418("rpc_solana-mainnet.g.alchemy.com", "Monthly capacity limit exceeded.")
        assertEquals("", RuntimeProviderAuthority6685.configuredAlchemyRpc())
        assertFalse(RuntimeProviderAuthority6685.rpcCandidates(null).any { it.contains("alchemy") })
        RpcCapacityLatch8418.resetForTests8418()
        val key = try { com.lifecyclebot.data.DefaultKeys.ALCHEMY } catch (_: Throwable) { "" }
        if (key.isNotBlank()) assertTrue(RuntimeProviderAuthority6685.configuredAlchemyRpc().contains("alchemy"))
    }

    @Test fun anEmptyMarketReadNamesItsCause() {
        RpcOutcome8418.note8418("8038", "ok")
        RpcOutcome8418.note8418("8038", "noRung")
        RpcOutcome8418.note8418("8038", "noRung")
        assertEquals("monthlyCap", RpcOutcome8418.rpcErrorKey8418("rpc_x", 429, "Monthly capacity limit exceeded"))
        assertTrue(RpcCapacityLatch8418.alchemyExhausted8418())
        assertEquals("rpc-32005", RpcOutcome8418.rpcErrorKey8418("rpc_x", -32005, "Node is behind"))
        RpcOutcome8418.note8418("8038", "rpc-32005")
        assertEquals(2L, RpcOutcome8418.count8418("8038", "noRung"))
        assertEquals(1L, RpcOutcome8418.count8418("8038", "ok"))
        val line = RpcOutcome8418.reportLine8418()
        assertTrue(line, line.contains("8038{noRung=2"))
        assertTrue(line.contains("alchemyLatch8418[exhausted=true"))
    }

    // ── pools ──

    @Test fun orcaWhirlpoolVaultsDecodeAsDepthOnlyAndDammV2UsesItsDeclaredId() {
        assertEquals("cpamdpZCGKUy5JxQXB4dcpGPiikHawvSWAd6mEn1sGG", OnChainDepth8038.METEORA_DAMM_V2_8418)
        assertEquals(133 to 213, OnChainDepth8038.vaultOffsets8038(OnChainDepth8038.ORCA_WHIRLPOOL_8418))
        assertTrue(OnChainDepth8038.concentrated8418(OnChainDepth8038.ORCA_WHIRLPOOL_8418))
        assertEquals("OrcaWhirlpool", OnChainDepth8038.venueName8418(OnChainDepth8038.ORCA_WHIRLPOOL_8418))
        assertEquals("MeteoraDAMMv2", OnChainDepth8038.venueName8418("cpamdpZCGKUy5JxQXB4dcpGPiikHawvSWAd6mEn1sGG"))
        val data = ByteArray(653)
        val a = ByteArray(32) { (it + 3).toByte() }
        val b = ByteArray(32) { (it + 90).toByte() }
        a.copyInto(data, 133); b.copyInto(data, 213)
        val (va, vb) = OnChainDepth8038.vaultKeys8038(OnChainDepth8038.ORCA_WHIRLPOOL_8418, data)!!
        assertEquals(io.github.novacrypto.base58.Base58.base58Encode(a), va)
        assertEquals(io.github.novacrypto.base58.Base58.base58Encode(b), vb)
        assertNull("a short account is no reading", OnChainDepth8038.vaultKeys8038(OnChainDepth8038.ORCA_WHIRLPOOL_8418, ByteArray(200)))
        // The shared concentrated set (depth only), and DAMM v1 stays UNKNOWN (reserves are vault shares).
        assertTrue(OnChainDepth8038.concentrated8418(OnChainDepth8038.RAYDIUM_CLMM_8418))
        assertTrue(OnChainDepth8038.concentrated8418(OnChainDepth8038.METEORA_DLMM_8418))
        assertNull(OnChainDepth8038.vaultOffsets8038(OnChainDepth8038.METEORA_DAMM_V1_8418))
        assertFalse(OnChainDepth8038.depthOnlyFresh8418("NoReadMint8418", 90_000L))
        assertTrue(OnChainDepth8038.statusLine().contains("OrcaWhirlpool(depth)"))
        assertTrue(OnChainDepth8038.statusLine().contains("unknownDepth=MeteoraDAMMv1"))
    }

    // ── pump.fun candles ──

    @Test fun pumpCandlesTrySwapApiFirstAndRotateOffADeadOrEmptyRoute() {
        val r = PumpFunCandles8359.routes8418("Mint1pump", 60)
        assertEquals(2, r.size)
        assertTrue(r[0].startsWith("https://swap-api.pump.fun/v2/coins/Mint1pump/candles?interval=1m&limit=60&currency=USD"))
        assertEquals(PumpFunCandles8359.url8359("Mint1pump", 60), r[1])
        assertEquals(1 to 0, PumpFunCandles8359.routeAfter8418(0, 2, 404, true, 0))
        assertEquals(0 to 1, PumpFunCandles8359.routeAfter8418(0, 2, 200, true, 0))
        assertEquals(1 to 0, PumpFunCandles8359.routeAfter8418(0, 2, 200, true, 2))
        assertEquals(0 to 0, PumpFunCandles8359.routeAfter8418(1, 2, 410, true, 0))
        assertEquals(0 to 0, PumpFunCandles8359.routeAfter8418(0, 2, 200, false, 2))
        assertEquals("a 5xx keeps the route (its own back-off handles it)", 0 to 0, PumpFunCandles8359.routeAfter8418(0, 2, 503, true, 0))
    }
}
