package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.data.Candle
import com.lifecyclebot.network.SolanaOhlcvFeed6916.SlotWait7809
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider cooldown belongs to that provider; it cannot hide candles returned by an independent rung. */
class Aate8237CandleFallbackTest {
    private fun orchestrator() = DataOrchestrator(
        cfg = { BotConfig() }, status = BotStatus(), onLog = { _, _ -> },
        onNotify = { _, _, _ -> }, onNewTokenDetected = { _, _, _ -> }, onDevSell = { _, _ -> },
    )

    private val observed = listOf(Candle(
        ts = 1_800_000_000_000L, priceUsd = 1.05, marketCap = 0.0, volumeH1 = 0.0,
        volume24h = 500.0, openUsd = 1.0, highUsd = 1.1, lowUsd = 0.95,
    ))

    @Test fun independentCandlesAreReturnedDuringALongGeckoCooldown() = runBlocking {
        val orch = orchestrator()
        var reads = 0
        try {
            val result = orch.keylessFetch7809("Independent8237xxxxxxxxxxxxxxxxxxxxxxxxpump", "1m", 120, "", 3,
                slotReader = { SlotWait7809(213_488L, cooldown = true) },
                candleReader = { reads++; observed },
            )
            assertSame(observed, result)
            assertEquals(1, reads)
        } finally { orch.stop() }
    }

    @Test fun anEmptyIndependentRungDoesNotInventBarsOrQueueBehindALongCooldown() = runBlocking {
        val orch = orchestrator()
        var reads = 0
        var slots = 0
        try {
            val result = orch.keylessFetch7809("IndependentEmpty8237xxxxxxxxxxxxxxxxxxxpump", "1m", 120, "", 3,
                slotReader = { slots++; SlotWait7809(213_488L, cooldown = true) },
                candleReader = { reads++; emptyList() },
            )
            assertTrue(result.isEmpty())
            assertEquals(1, reads)
            assertEquals(1, slots)
        } finally { orch.stop() }
    }

    @Test fun aDisabledSeedBudgetStillMakesNoReads() = runBlocking {
        val orch = orchestrator()
        try {
            val result = orch.keylessFetch7809("IndependentBudget8237xxxxxxxxxxxxxxxxxxpump", "5m", 60, "", 0,
                slotReader = { error("zero attempts cannot read a provider slot") },
                candleReader = { error("zero attempts cannot spend a provider read") },
            )
            assertTrue(result.isEmpty())
        } finally { orch.stop() }
    }
}
