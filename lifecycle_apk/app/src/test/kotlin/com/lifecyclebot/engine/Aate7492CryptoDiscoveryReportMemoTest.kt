package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7492CryptoDiscoveryReportMemoTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()

    @Test fun report_is_short_ttl_memoized() {
        val s = src()
        assertTrue(s.contains("DISCOVERY_REPORT_TTL_MS_7492 = 5_000L"))
        assertTrue(s.contains("CRYPTO_DISCOVERY_REPORT_REUSED_7492"))
        assertTrue(s.contains("discoveryReportMemo7492 = DiscoveryReportMemo7492"))
    }

    @Test fun memo_is_report_only() {
        val s = src()
        val fn = s.substringAfter("fun discoveryReport6544(): String").substringBefore("\n    fun ")
        assertTrue(fn.contains("registry.values.toList()"))
        assertFalse(fn.contains("markEvaluationStarted6567"))
        assertFalse(fn.contains("CanonicalEntryAuthority6551.submit"))
    }
}
