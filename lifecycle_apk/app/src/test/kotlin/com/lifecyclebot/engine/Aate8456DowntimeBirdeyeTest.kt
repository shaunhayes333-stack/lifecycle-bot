package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DowntimeRecovery8430
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8456 — the labels a restart interrupted are recovered from 1-minute candles. GeckoTerminal (10 calls a minute) is
 * used only while its bucket is full; otherwise Birdeye serves the same candles inside its own CU budget. 8450:
 * queued=82 fetched=0 yieldedToLive=71 with Birdeye at 7.6 % of its daily CU.
 */
class Aate8456DowntimeBirdeyeTest {
    @Test fun aBusyGeckoHandsTheRecoveryToBirdeyeNotToNothing() {
        val d = File("src/main/kotlin/com/lifecyclebot/engine/truth/DowntimeRecovery8430.kt").readText()
        val drain = d.substring(d.indexOf("fun drain8430("), d.indexOf("@Volatile private var inFlight8430"))
        assertTrue(drain.contains("GeckoHostBudget8382.spare8439(nowMs)"))
        assertTrue(drain.contains("val birdeye8456 = !gecko8456 && (try { com.lifecyclebot.engine.BirdeyeBudgetGate.canAfford(1) }"))
        assertTrue("yields only when neither source can serve", drain.contains("if (!gecko8456 && !birdeye8456) { yielded8439.incrementAndGet(); return }"))
        assertTrue(drain.contains("else try { birdeyeCandles8456(mint) } catch (_: Throwable) { emptyList() }"))
        assertTrue(d.contains("com.lifecyclebot.network.BirdeyeApi(key).getCandles(mint, \"1m\", CANDLES_8430)"))
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("birdeye8456="))
    }
}
