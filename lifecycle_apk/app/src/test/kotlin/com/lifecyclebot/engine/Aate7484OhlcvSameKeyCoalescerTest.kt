package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7484OhlcvSameKeyCoalescerTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/network/SolanaOhlcvFeed6916.kt").readText()

    @Test fun duplicate_request_is_coalesced_before_provider_resolution() {
        val s = src()
        val fn = s.substringAfter("fun fetchCandles6916(").substringBefore("// ══════ V5.0.7293")
        val coalesce = fn.indexOf("OHLCV_SAME_KEY_WINDOW_COALESCED_7484")
        val paprika = fn.indexOf("fetchDexPaprika7293")
        val pool = fn.indexOf("resolvePool(mint, poolHint)")
        assertTrue(coalesce >= 0)
        assertTrue(paprika > coalesce)
        assertTrue(pool > coalesce)
    }

    @Test fun provider_interval_and_caches_are_preserved() {
        val s = src()
        assertTrue(s.contains("SAME_KEY_COALESCE_MS_7484 = 2_500L"))
        assertTrue(s.contains("cache[key]?.let"))
        assertTrue(s.contains("negativeCache[mint]?.let"))
        assertTrue(s.contains("fetchDexPaprika7293"))
        assertTrue(s.contains("resolvePool(mint, poolHint)"))
    }

    @Test fun coalescer_is_bounded() {
        val s = src()
        assertTrue(s.contains("SAME_KEY_MAX_7484 = 8_000"))
        assertTrue(s.contains("sameKeyLastAttempt7484.clear()"))
    }
}
