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
}
