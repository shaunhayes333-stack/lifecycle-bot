package com.lifecyclebot.engine.truth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6548 §P0-B — CANONICAL PROVIDER IDENTITY acceptance test.
 *
 * Operator forensic (V5.0.6547):
 *   `MARK_AUTHORITY_GATE_BLOCKED_6547|SOURCE_NOT_WHITELISTED:DEXSCREENER_WS`
 *   fired 3,547× in one session on AUTHORITATIVE marks with finite price,
 *   sane liquidity, real pool address — solely because the transport
 *   channel name (`DEXSCREENER_WS`) was not on the whitelist that only
 *   admitted `DEXSCREENER` / `DEXSCREENER_PAIR_POLL`.
 *
 * The fix canonicalises DEXSCREENER_* / BIRDEYE_* / JUPITER_* /
 * PUMPFUN_* / PUMP_FUN_* / PUMP_PORTAL_* to a single provider identity
 * BEFORE the whitelist check. This test locks that behaviour in.
 */
class MarkAuthorityProviderNormalization6548Test {

    private val validMint = "MintMark_6548"
    private val validPool = "PoolAbc_6548"
    private val price = 0.001234
    private val mcap = 250_000.0
    private val liq = 12_000.0

    private fun evaluate(src: String) = MarkAuthorityIntegrityGate6496.evaluate(
        mint = validMint,
        priceUsd = price,
        mcapUsd = mcap,
        liquidityUsd = liq,
        source = src,
        poolAddress = validPool,
        fresh = true,
    )

    @Test
    fun dexscreener_ws_is_authoritative() {
        assertTrue("DEXSCREENER_WS must be admitted after 6548 P0-B", evaluate("DEXSCREENER_WS").priceAuthoritative)
    }

    @Test
    fun dexscreener_rest_and_poll_and_pair_poll_are_authoritative() {
        assertTrue(evaluate("DEXSCREENER_REST").priceAuthoritative)
        assertTrue(evaluate("DEXSCREENER_POLL").priceAuthoritative)
        assertTrue(evaluate("DEXSCREENER_PAIR_POLL").priceAuthoritative)
        assertTrue(evaluate("DEXSCREENER_BASE_MINT_MARKET_CAP").priceAuthoritative)
    }

    @Test
    fun birdeye_variants_are_authoritative() {
        assertTrue(evaluate("BIRDEYE").priceAuthoritative)
        assertTrue(evaluate("BIRDEYE_POLL").priceAuthoritative)
        assertTrue(evaluate("BIRDEYE_MARKET_CAP").priceAuthoritative)
    }

    @Test
    fun jupiter_variants_are_authoritative() {
        assertTrue(evaluate("JUPITER").priceAuthoritative)
        assertTrue(evaluate("JUPITER_LITE").priceAuthoritative)
    }

    @Test
    fun pumpfun_family_variants_are_authoritative() {
        assertTrue(evaluate("PUMPFUN_BONDING_CURVE").priceAuthoritative)
        assertTrue(evaluate("PUMP_FUN_BC_SYNTHETIC").priceAuthoritative)
        assertTrue(evaluate("PUMP_PORTAL_WS").priceAuthoritative)
    }

    @Test
    fun unknown_provider_is_still_blocked() {
        // Whitelist itself must not be widened — only the family collapse
        // is normalised. Genuinely unrecognised sources still block.
        assertFalse(evaluate("SOME_RANDOM_PROVIDER_2026").priceAuthoritative)
        assertFalse(evaluate("").priceAuthoritative)
    }

    @Test
    fun known_template_50m_5m_still_blocks_regardless_of_provider() {
        // Existing template-price safety must survive the normalization.
        val r = MarkAuthorityIntegrityGate6496.evaluate(
            mint = validMint,
            priceUsd = 0.050250000,
            mcapUsd = 50_000_000.0,
            liquidityUsd = 5_000_000.0,
            source = "DEXSCREENER_WS",
            poolAddress = validPool,
            fresh = true,
        )
        assertFalse("50m/5m template price must still block after 6548 P0-B", r.priceAuthoritative)
    }
}
