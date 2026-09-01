package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AssetClass
import org.junit.Assert.*
import org.junit.Test

/**
 * V5.0.6632b §P0-D — CRYPTO_ALT alias normalization.
 *
 * Operator: aliases used across the ingest tree ("ALT", "ALTS",
 * "CRYPTO_UNIVERSE", ...) were silently collapsing to UNKNOWN, which
 * killed cross-asset dispatch for non-Solana crypto candidates.
 */
class CryptoAltAliasNormalization6632bTest {

    @Test fun canonical_variants_still_normalise() {
        assertEquals(AssetClass.CRYPTO_ALT, AssetClass.fromLane("CRYPTO_ALT"))
        assertEquals(AssetClass.CRYPTO_ALT, AssetClass.fromLane("CRYPTOALT"))
        assertEquals(AssetClass.CRYPTO_ALT, AssetClass.fromLane("ALTCRYPTO"))
    }

    @Test fun new_aliases_normalise_to_crypto_alt() {
        val aliases = listOf(
            "ALT", "ALTS", "ALT_CRYPTO", "ALTCOIN", "ALTCOINS",
            "CRYPTOALTS", "CRYPTO_ALTS", "BLUECHIP_CRYPTO",
            "CRYPTO_UNIVERSE", "CRYPTOUNIVERSE",
        )
        for (alias in aliases) {
            assertEquals("alias=$alias must normalise to CRYPTO_ALT",
                AssetClass.CRYPTO_ALT, AssetClass.fromLane(alias))
            assertEquals("alias=$alias (lowercase) must normalise to CRYPTO_ALT",
                AssetClass.CRYPTO_ALT, AssetClass.fromLane(alias.lowercase()))
        }
    }

    @Test fun solana_lanes_still_map_to_solana_token() {
        assertEquals(AssetClass.SOLANA_TOKEN, AssetClass.fromLane("SHITCOIN"))
        assertEquals(AssetClass.SOLANA_TOKEN, AssetClass.fromLane("MEME"))
        assertEquals(AssetClass.SOLANA_TOKEN, AssetClass.fromLane("BLUECHIP"))
    }

    @Test fun unknown_lane_still_unknown_no_solana_coercion() {
        assertEquals(AssetClass.UNKNOWN, AssetClass.fromLane("some_random_lane"))
        assertEquals(AssetClass.UNKNOWN, AssetClass.fromLane(null))
        assertEquals(AssetClass.UNKNOWN, AssetClass.fromLane(""))
    }
}
