package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DowntimeRecovery8430
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8456 -> superseded by V5.0.8457 (owner: Birdeye is dead; free sources and Helius). Labels a restart interrupted are
 * recovered from free GeckoTerminal 1-minute candles: while its bucket is full, and otherwise one call a minute while 2+
 * tokens remain for live reads. 8450: queued=82 fetched=0 yieldedToLive=71. No Birdeye call.
 */
class Aate8456DowntimeBirdeyeTest {
    @Test fun recoveryTricklesOnFreeGeckoAndNeverCallsBirdeye() {
        val d = File("src/main/kotlin/com/lifecyclebot/engine/truth/DowntimeRecovery8430.kt").readText()
        val drain = d.substring(d.indexOf("fun drain8430("), d.indexOf("@Volatile private var inFlight8430"))
        assertTrue(drain.contains("GeckoHostBudget8382.spare8439(nowMs)"))
        assertTrue(drain.contains("com.lifecyclebot.network.GeckoHostBudget8382.trickle8457(nowMs)"))
        assertTrue(drain.contains("nowMs - lastFetchMs >= TRICKLE_GAP_MS_8457"))
        assertFalse("no Birdeye in recovery", d.contains("BirdeyeApi(") || d.contains("BirdeyeBudgetGate"))
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("trickle8457="))
        val g = File("src/main/kotlin/com/lifecyclebot/network/GeckoHostBudget8382.kt").readText()
        // superseded by V5.0.8458 — one whole token (8457's 3.0 was the full burst: the trickle never fired)
        assertTrue(g.contains("bucket.tokens8287(nowMs) >= TRICKLE_TOKENS_8458 - 0.01"))
        assertTrue(com.lifecyclebot.network.GeckoHostBudget8382.TRICKLE_TOKENS_8458 < com.lifecyclebot.network.GeckoHostBudget8382.BURST_8382)
    }
}
