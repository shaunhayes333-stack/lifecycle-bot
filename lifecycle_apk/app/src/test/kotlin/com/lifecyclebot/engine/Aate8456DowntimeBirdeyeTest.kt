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
        // Superseded (8480): the spare-bucket / trickle yield is removed (pipeline report: recovered 0/145); the provider slot paces it.
        assertFalse(drain.contains("spare8439") || drain.contains("trickle8457"))
        assertTrue(drain.contains("val slot8457 = !wait.cooldown && wait.waitMs <= MAX_SLOT_SLEEP_MS_8435"))
        assertFalse("no Birdeye in recovery", d.contains("BirdeyeApi(") || d.contains("BirdeyeBudgetGate"))
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("slotBusy="))
    }
}
