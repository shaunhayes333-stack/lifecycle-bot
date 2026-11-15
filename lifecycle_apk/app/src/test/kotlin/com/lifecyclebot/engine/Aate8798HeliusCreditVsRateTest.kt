package com.lifecyclebot.engine

import com.lifecyclebot.network.HeliusRateGate8191
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 429 compute-credit exhaustion must not be mislearned as an RPS ceiling. */
class Aate8798HeliusCreditVsRateTest {
    @Test fun distinguishesMonthlyCreditsFromBurstRateLimit() {
        val quota = "{\"error\":{\"code\":429,\"message\":\"Your app has exceeded its compute units\"}}"
        assertTrue(HeliusRateGate8191.isProviderQuota4298798(429, quota))
        assertTrue(HeliusRateGate8191.isProviderQuota4298798(429, "Credits exhausted for this project"))
        assertTrue(HeliusRateGate8191.isProviderQuota4298798(429, "Monthly quota exceeded"))
        assertFalse(HeliusRateGate8191.isProviderQuota4298798(429, "Too many requests in 1 second"))
        assertFalse(HeliusRateGate8191.isProviderQuota4298798(500, quota))
    }

    @Test fun boundedQuotaProbesDoNotTreatCreditRefillAsPermanentFailure() {
        assertEquals(30_000L, HeliusRateGate8191.quotaRecheckDelayMs8798(1L))
        assertEquals(60_000L, HeliusRateGate8191.quotaRecheckDelayMs8798(2L))
        assertEquals(120_000L, HeliusRateGate8191.quotaRecheckDelayMs8798(3L))
        assertEquals(600_000L, HeliusRateGate8191.quotaRecheckDelayMs8798(10L))
        val b = HeliusRateGate8191.Bucket8191("test", 8.0, 2.0, 40.0)
        b.onQuotaExhausted8798(1_000L)
        assertTrue(b.quotaRecheckAt8798() > 1_000L)
        assertEquals(-1L, b.take8191(1_001L, priority = false))
        // Exit/balance RPC and socket upgrade remain priority even through quota cooldown.
        assertEquals(0L, b.take8191(1_001L, priority = true))
        b.onProviderSuccess8798()
        assertEquals(0L, b.quotaRecheckAt8798())
    }

    @Test fun interceptorClassifiesRealProviderBodyBeforePenalizingRate() {
        val src = File("src/main/kotlin/com/lifecyclebot/network/HeliusRateGate8191.kt").readText()
        val intercept = src.substringAfter("object HeliusRateInterceptor8191")
        assertTrue(intercept.contains("resp.peekBody(1_024L).string()"))
        assertTrue(intercept.indexOf("isProviderQuota4298798(resp.code, body)") < intercept.indexOf("providerRateLimit8191(resp.code, resp.message)"))
        assertTrue(intercept.contains("bucket.onProviderSuccess8798()"))
    }
}
