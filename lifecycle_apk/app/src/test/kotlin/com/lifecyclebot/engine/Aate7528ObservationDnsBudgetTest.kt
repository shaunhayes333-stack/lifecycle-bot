package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7528ObservationDnsBudgetTest {
    @Test fun observation_jupiter_does_not_use_serial_doh_chain() {
        val s = File("src/main/kotlin/com/lifecyclebot/network/JupiterApi.kt").readText()
        assertTrue(s.contains(".dns(if (observationOnly7397) Dns.SYSTEM else CloudflareDns.INSTANCE)"))
        assertTrue(s.contains("OBS_CALL_TIMEOUT_MS_7528 = 1_800L"))
        assertTrue(s.contains(".retryOnConnectionFailure(!observationOnly7397)"))
    }

    @Test fun live_execution_jupiter_keeps_cloudflare_doh() {
        val s = File("src/main/kotlin/com/lifecyclebot/network/JupiterApi.kt").readText()
        assertTrue(s.contains("else CloudflareDns.INSTANCE"))
        assertTrue(s.contains("else CONNECT_TIMEOUT_MS"))
        assertTrue(s.contains("else 12_000L"))
    }
}
