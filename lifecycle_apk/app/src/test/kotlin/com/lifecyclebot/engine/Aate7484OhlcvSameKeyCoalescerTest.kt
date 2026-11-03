package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7484OhlcvSameKeyCoalescerTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/network/SolanaOhlcvFeed6916.kt").readText()

    @Test fun duplicate_request_is_coalesced_before_provider_resolution() {
        val s = src()
        val fn = s.substringAfter("fun fetchCandles6916(").substringBefore("fun statusLine()")
        val coalesce = fn.indexOf("OHLCV_SAME_KEY_WINDOW_COALESCED_7484")
        val request = fn.indexOf("val url =")
        val pool = fn.indexOf("resolvePool(mint, poolHint)")
        assertTrue(coalesce >= 0)
        assertTrue(request > coalesce)
        assertTrue(pool > coalesce)
    }

    @Test fun provider_interval_and_caches_are_preserved() {
        val s = src()
        assertTrue(s.contains("SAME_KEY_COALESCE_MS_7484 = 2_500L"))
        assertTrue(s.contains("cachedCandles8253(mint, timeframeLabel, n, now)"))
        assertTrue(s.contains("val row = cache[key] ?: return emptyList()"))
        assertTrue(s.contains("negativeCache[mint]?.let"))
        assertTrue(s.contains("currency=usd&token=$" + "mint&include_empty_intervals=false"))
        assertTrue(s.contains("resolvePool(mint, poolHint)"))
    }

    @Test fun coalescer_is_bounded() {
        val s = src()
        assertTrue(s.contains("SAME_KEY_MAX_7484 = 8_000"))
        assertTrue(s.contains("sameKeyLastAttempt7484.clear()"))
    }
}
