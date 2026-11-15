package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8774 — code audit (discovery F16, F19): an API instance per call no longer builds an HTTP client per call. */
class Aate8774SharedClientsTest {
    @Test fun every_instance_shares_one_client() {
        for ((f, name) in listOf("DexscreenerApi.kt" to "DEX_HTTP_8774", "BirdeyeApi.kt" to "BIRDEYE_HTTP_8774")) {
            val s = File("src/main/kotlin/com/lifecyclebot/network/$f").readText()
            assertTrue(f, s.contains("private val $name: OkHttpClient by lazy {"))
            assertTrue(f, s.contains("private val http: OkHttpClient get() = $name"))
            assertFalse(f, s.contains("    private val http = SharedHttpClient.builder()"))
        }
    }
}
