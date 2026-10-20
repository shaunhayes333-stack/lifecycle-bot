package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.data.Candle
import com.lifecyclebot.network.SolanaOhlcvFeed6916
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic provider fixtures exercise the actual seeding path; no network or market observations are invented. */
class Aate8253CandleDeliveryTest {
    private fun orchestrator() = DataOrchestrator(cfg = { BotConfig() }, status = BotStatus(),
        onLog = { _, _ -> }, onNotify = { _, _, _ -> }, onNewTokenDetected = { _, _, _ -> }, onDevSell = { _, _ -> })
    private val bars = listOf(Candle(1_000L, 1.0, 0.0, 0.0, 0.0), Candle(61_000L, 1.1, 0.0, 0.0, 0.0))

    @Test fun freshCacheIsServedBeforeAnyProviderSlotOrRequest() = runBlocking {
        val result = orchestrator().keylessFetch7809("fixture", "1m", 120, "", 3,
            cached = { bars }, fetch = { error("Fresh cache must not request network") },
            slotWait = { error("Fresh cache must not wait for a provider") })
        assertSame(bars, result)
        assertEquals(1_000L, result.first().ts)
    }

    @Test fun primaryCooldownStillAllowsIndependentFallback() = runBlocking {
        var fetches = 0
        val result = orchestrator().keylessFetch7809("fixture", "1m", 120, "", 3,
            cached = { emptyList() }, fetch = { fetches++; bars },
            slotWait = { SolanaOhlcvFeed6916.SlotWait7809(60_000L, true) })
        assertSame(bars, result)
        assertEquals(1, fetches)
    }

    @Test fun unansweredFallbackIsNotConvertedIntoCandlesOrRepeatedRequests() = runBlocking {
        var fetches = 0
        val result = orchestrator().keylessFetch7809("fixture", "1m", 120, "", 3,
            cached = { emptyList() }, fetch = { fetches++; emptyList() },
            slotWait = { SolanaOhlcvFeed6916.SlotWait7809(60_000L, true) })
        assertTrue(result.isEmpty())
        assertEquals(1, fetches)
    }
}
