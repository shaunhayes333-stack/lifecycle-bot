package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570
import com.lifecyclebot.engine.truth.CanonicalPriceMark6522
import com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
import com.lifecyclebot.engine.truth.PriceUsd
import com.lifecyclebot.network.normalizeDexscreenerChain6682
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aate6682PricingAuthorityRegressionTest {

    @Before fun reset() {
        CanonicalPriceMarkRegistry6522.resetForTest()
    }

    @Test fun older_valid_observation_reuses_fresher_canonical_mark_instead_of_false_reject() {
        val mint = "M6682_${System.nanoTime()}"
        val now = System.currentTimeMillis()
        val fresh = CanonicalPriceMark6522(
            mint = mint,
            pairId = "PAIR_FRESH",
            baseMint = mint,
            quoteMint = "USD",
            source = "DEXSCREENER_PAIR_POLL",
            timestampMs = now,
            priceUsd = PriceUsd(BigDecimal("0.002")),
            liquidityUsd = BigDecimal("50000"),
            purpose = CanonicalMarkPurpose6570.OBSERVATION_SCORING,
        )
        assertTrue(CanonicalPriceMarkRegistry6522.publish(fresh))

        val result = CanonicalPriceMarkRegistry6522.resolveExecutableFromSourceEvidence6616(
            mint = mint,
            observedBaseMint = mint,
            pairOrPool = "PAIR_OLDER",
            quoteMint = "USD",
            source = "DEXSCREENER_PAIR_POLL",
            priceUsd = 0.001,
            liquidityUsd = 40000.0,
            evidenceTimestampMs = now - 100L,
            nowMs = now,
        )

        assertTrue("fresher valid registry evidence must not become SOURCE_OBSERVATION_REJECTED", result.promoted)
        assertEquals("PROMOTED", result.reason)
        assertEquals(0, BigDecimal("0.002").compareTo(result.mark!!.priceUsd.value))
        assertEquals("PAIR_FRESH", result.mark!!.pairId)
    }

    @Test fun same_pool_hyper_extreme_scale_jump_is_not_accepted_as_open_pnl_truth() {
        val allowedMoonshot = OpenPnlSanity.inspect(
            entryPrice = 0.000001,
            currentPrice = 0.0005, // 500x
            entrySource = "DEXSCREENER_PAIR_POLL",
            currentSource = "DEXSCREENER_PAIR_POLL",
            entryPool = "PAIR_A",
            currentPool = "PAIR_A",
            emit = false,
        )
        assertTrue("500x runners must remain representable", allowedMoonshot.ok)

        val corrupt = OpenPnlSanity.inspect(
            entryPrice = 0.000001,
            currentPrice = 0.02, // 20,000x — unit/decimal corruption backstop
            entrySource = "DEXSCREENER_PAIR_POLL",
            currentSource = "DEXSCREENER_PAIR_POLL",
            entryPool = "PAIR_A",
            currentPool = "PAIR_A",
            emit = false,
        )
        assertFalse(corrupt.ok)
        assertEquals("PRICE_RATIO_HYPER_EXTREME_SCALE_GUARD_6682", corrupt.reason)
    }

    @Test fun crypto_universe_chain_aliases_are_normalized_for_dexscreener() {
        assertEquals("ethereum", normalizeDexscreenerChain6682("eth"))
        assertEquals("ethereum", normalizeDexscreenerChain6682("ethereum"))
        assertEquals("polygon", normalizeDexscreenerChain6682("polygon_pos"))
        assertEquals("polygon", normalizeDexscreenerChain6682("matic"))
        assertEquals("arbitrum", normalizeDexscreenerChain6682("arb"))
        assertEquals("optimism", normalizeDexscreenerChain6682("op"))
        assertEquals("bsc", normalizeDexscreenerChain6682("bnb"))
        assertEquals("solana", normalizeDexscreenerChain6682("sol"))
        assertEquals("base", normalizeDexscreenerChain6682("base-mainnet"))
    }
}
