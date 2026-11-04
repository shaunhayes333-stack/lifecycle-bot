package com.lifecyclebot.network

import com.lifecyclebot.engine.ApiBackoff
import com.lifecyclebot.engine.HealthAwareHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Actual wrapper + shared-interceptor chain; fake terminal transport, no external HTTP. */
class Aate8527TradeQuoteAuthorityTest {
    private val labels = listOf("jupiter_quote", "jupiter_quote_obs", "jupiter", "birdeye")

    @After fun clearBackoff() { labels.forEach { ApiBackoff.markSuccess(it) } }

    private fun request(url: String = "https://api.jup.ag/swap/v1/quote", trade: Boolean = true): Request =
        Request.Builder().url(url).apply { if (trade) header(HostCircuitInterceptor.TRADE_HEADER_8496, "1") }.build()

    private fun transport(calls: AtomicInteger): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HostCircuitInterceptor)
        .addInterceptor { chain ->
            calls.incrementAndGet()
            assertNull("internal trade authority must not leak onto the wire",
                chain.request().header(HostCircuitInterceptor.TRADE_HEADER_8496))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("{}".toResponseBody()).build()
        }.build()

    @Test fun markedTradeQuoteTraversesBothLocalLockoutLayers() {
        val calls = AtomicInteger()
        ApiBackoff.markFailure("jupiter_quote", 429)
        ApiBackoff.markFailure("jupiter", 429)
        assertTrue(ApiBackoff.isLockedOut("jupiter_quote"))
        assertTrue(ApiBackoff.isLockedOut("jupiter"))
        HealthAwareHttp.execute(transport(calls), request(), "jupiter_quote").use { response ->
            assertEquals(200, response.code)
            assertFalse(HostCircuitInterceptor.isSyntheticBlock(response))
        }
        assertEquals(1, calls.get())
        assertFalse("a real answer still clears health", ApiBackoff.isLockedOut("jupiter_quote"))
    }

    @Test fun unmarkedObservationStillHonoursLocalBackoff() {
        val calls = AtomicInteger()
        ApiBackoff.markFailure("jupiter_quote_obs", 429)
        HealthAwareHttp.execute(transport(calls), request(trade = false), "jupiter_quote_obs").use {
            assertEquals(503, it.code)
            assertTrue(HostCircuitInterceptor.isSyntheticBlock(it))
        }
        assertEquals(0, calls.get())
    }

    @Test fun aTradeMarkerDoesNotBypassAnotherProvidersBudgetOrDiscoveryBackoff() {
        val calls = AtomicInteger()
        ApiBackoff.markFailure("birdeye", 429)
        HealthAwareHttp.execute(transport(calls), request("https://public-api.birdeye.so/defi/price"), "birdeye").use {
            assertTrue(HostCircuitInterceptor.isSyntheticBlock(it))
        }
        ApiBackoff.markFailure("jupiter_quote", 429)
        HealthAwareHttp.execute(transport(calls), request("https://api.jup.ag/tokens/v2/recent"), "jupiter_quote").use {
            assertTrue(HostCircuitInterceptor.isSyntheticBlock(it))
        }
        assertEquals(0, calls.get())
    }

    @Test fun onlyJupiterQuoteOrOrderReadsAcceptTheAuthorityMarker() {
        assertTrue(HostCircuitInterceptor.tradeQuote8527(request()))
        assertTrue(HostCircuitInterceptor.tradeQuote8527(request("https://lite-api.jup.ag/ultra/v1/order")))
        assertFalse(HostCircuitInterceptor.tradeQuote8527(request("https://not-jup.ag/swap/v1/quote")))
        assertFalse(HostCircuitInterceptor.tradeQuote8527(request("https://api.jup.ag/tokens/v2/recent")))
        assertFalse(HostCircuitInterceptor.tradeQuote8527(request(trade = false)))
        assertFalse(HostCircuitInterceptor.tradeQuote8527(request().newBuilder().post("{}".toRequestBody()).build()))
    }
}
