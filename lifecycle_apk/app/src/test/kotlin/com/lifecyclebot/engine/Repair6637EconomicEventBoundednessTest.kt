package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class Repair6637EconomicEventBoundednessTest {
    @Test
    fun `economic event retention stays bounded across a full cold start sized corpus`() {
        EconomicEventSchema6464.resetForTest()

        repeat(8_208) { index ->
            EconomicEventSchema6464.recordBuy(
                mode = "paper",
                positionId = "position-$index",
                mint = "mint-$index",
                symbol = "T$index",
                idempotencyKey = "buy-$index",
                executedCostSol = 0.001,
                filledQty = BigInteger.ONE,
                fillPrice = 1.0,
                tokenDecimals = 0,
                quantityScale = 0,
            )
        }

        val retained = EconomicEventSchema6464.snapshot()
        assertEquals(8_192, retained.size)
        assertEquals("buy-8207", retained.first().idempotencyKey)
        assertEquals("buy-16", retained.last().idempotencyKey)
        assertEquals(8_208L, EconomicEventSchema6464.version())
    }
}
