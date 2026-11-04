package com.lifecyclebot.network

import com.lifecyclebot.engine.ApiBackoff
import com.lifecyclebot.engine.HealthAwareHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class Aate8527HalfOpenAuthorityTest {
    @Test fun aReadConsumesItsHalfOpenPermitOnlyAtTheHttpBoundary() {
        val label = "curve_half_open_8527"
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("{}".toResponseBody()).build()
        }.build()
        ApiBackoff.markSuccess(label)
        KeylessRpc8359.resetForTests8359()
        try {
            ApiBackoff.markFailure(label, 504) // soft window permits exactly one half-open attempt
            CurveReadBudget8527.walk(listOf("https://api.mainnet-beta.solana.com"), false,
                admit = { KeylessRpc8359.beforeCall8359(it) }) { url, _, _ ->
                HealthAwareHttp.execute(client, Request.Builder().url(url).build(), label).use { it.isSuccessful }
            }
            assertEquals(1, calls.get())
            assertFalse(ApiBackoff.isLockedOut(label))
            val source = File("src/main/kotlin/com/lifecyclebot/network/ParallelMarkFanout7088.kt").readText()
            val admission = source.substringAfter("CurveReadBudget8527.walk").substringBefore("}) { rung")
            assertFalse("the curve reader cannot spend the same permit before HTTP", admission.contains("isLockedOut"))
            assertTrue(admission.contains("KeylessRpc8359.beforeCall8359(rung)"))
        } finally {
            ApiBackoff.markSuccess(label)
            KeylessRpc8359.resetForTests8359()
        }
    }
}
