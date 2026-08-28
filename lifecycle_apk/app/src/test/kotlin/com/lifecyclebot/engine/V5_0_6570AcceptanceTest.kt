package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class V5_0_6570AcceptanceTest {
    @Before fun reset() {
        CanonicalPriceMarkRegistry6522.resetForTest()
        CanonicalPositionAuthority6441.resetForTest()
        CanonicalPositionAuthority6441.setPaperCash(10.0, "6570-test")
    }

    @Test fun observation_mark_accepts_fresh_provider_mint_route_but_executable_mark_does_not() {
        val now = System.currentTimeMillis()
        val observation = CanonicalPriceMark6522(
            "mint6570", "MINT_ROUTE:mint6570", "mint6570", "USD", "DEXSCREENER_PAIR_POLL",
            now, PriceUsd(BigDecimal("0.00125")), null, CanonicalMarkPurpose6570.OBSERVATION_SCORING,
        )
        assertTrue(CanonicalPriceMarkRegistry6522.publish(observation))
        assertEquals(BigDecimal("0.00125"), CanonicalPriceMarkRegistry6522.get("mint6570")!!.priceUsd.value)
        assertFalse(CanonicalPriceMarkRegistry6522.publish(observation.copy(
            mint = "mint6570-live", baseMint = "mint6570-live", pairId = "MINT_ROUTE:mint6570-live",
            purpose = CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE,
        )))
        assertFalse(CanonicalPriceMarkRegistry6522.publish(observation.copy(
            mint = "stale6570", baseMint = "stale6570", pairId = "MINT_ROUTE:stale6570",
            timestampMs = now - 121_000L,
        )))
    }

    @Test fun canonical_exit_preflight_requires_open_positive_basis_quantity_mode_and_class() {
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.openPosition(
                idempotencyKey = "buy6570", positionId = "pid6570", mint = "asset6570", symbol = "A57",
                lane = "PERPS", runId = "run6570", entryCostSol = 0.25,
                openedQtyRaw = BigInteger.valueOf(1_000_000L), tokenDecimals = 6,
                feesSol = 0.0, paperMode = true, assetClass = com.lifecyclebot.engine.truth.AssetClass.PERPS,
                entryPriceUsd = 25.0, entryPriceSource = "TEST",
            ))
        val ok = CanonicalPositionAuthority6441.exitEligibility6570("pid6570", "asset6570", "paper", com.lifecyclebot.engine.truth.AssetClass.PERPS)
        assertTrue(ok.eligible)
        assertEquals("ELIGIBLE", ok.reason)
        val wrongClass = CanonicalPositionAuthority6441.exitEligibility6570("pid6570", "asset6570", "paper", com.lifecyclebot.engine.truth.AssetClass.STOCK)
        assertFalse(wrongClass.eligible)
        assertEquals("ASSET_CLASS_MISMATCH", wrongClass.reason)
        assertFalse(CanonicalPositionAuthority6441.exitEligibility6570(null, "missing6570", "paper").eligible)
    }
}
