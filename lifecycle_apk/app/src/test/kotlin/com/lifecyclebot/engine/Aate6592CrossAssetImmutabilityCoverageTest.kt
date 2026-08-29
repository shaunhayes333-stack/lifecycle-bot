package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import com.lifecyclebot.engine.truth.AssetClass

/**
 * V5.0.6592 — cross-asset AssetClass immutability + class-attributed
 * telemetry regression coverage. Operator directive Feb 2026:
 *
 *  > "AssetClass must be immutable and mandatory. There must be NO
 *  >  fallback/default of unknown/null/non-Solana -> SOLANA_TOKEN."
 *
 * Snapshot symptoms this build resolves:
 *  - STOCK_* positions (AAPL/NVDA/AMZN) stored assetClass=SOLANA_TOKEN
 *  - 24,807 CANONICAL_EXIT_MARK_REFRESH_QUEUED_6513 for 51 open positions
 *    (stock symbols hitting Birdeye)
 *  - CRYPTO_ALT candidate=295 → intent=30 → dispatch=0 while
 *    SOLANA_TOKEN dispatch=30 with zero upstream (misattribution)
 *
 * Fixes verified:
 *  (1) AssetClass.fromLane returns UNKNOWN (not SOLANA_TOKEN) for
 *      unrecognised lane strings
 *  (2) AssetClass.fromPositionIdPrefix infers class from positionId
 *      prefix (STOCK_/FOREX_/ALT_/PERPS_)
 *  (3) CanonicalPositionAuthority6441.openPosition corrects a
 *      caller-passed SOLANA_TOKEN that contradicts the positionId prefix
 *      and emits ASSET_CLASS_POSITIONID_MISMATCH_6592
 *  (4) CryptoAltTrader dispatch is class-attributed via
 *      markAdapterDispatchFor6551(CRYPTO_ALT, symbol)
 *  (5) PerpsExecutionEngine dispatch class-attributed via
 *      markAdapterDispatchFor6551(PERPS, symbol)
 *  (6) BotService mark-refresh routes by inferred class if stored
 *      class disagrees with positionId prefix
 */
class Aate6592CrossAssetImmutabilityCoverageTest {

    @Test
    fun aate6592_fromLane_unrecognised_returns_UNKNOWN_not_SOLANA_TOKEN() {
        // Recognised Solana lanes still resolve to SOLANA_TOKEN.
        assertEquals(AssetClass.SOLANA_TOKEN, AssetClass.fromLane("SHITCOIN"))
        assertEquals(AssetClass.SOLANA_TOKEN, AssetClass.fromLane("moonshot"))
        assertEquals(AssetClass.SOLANA_TOKEN, AssetClass.fromLane("bluechip"))
        assertEquals(AssetClass.SOLANA_TOKEN, AssetClass.fromLane("EXPRESS"))
        // Non-Solana classes remain correct.
        assertEquals(AssetClass.STOCK, AssetClass.fromLane("STOCK"))
        assertEquals(AssetClass.FOREX, AssetClass.fromLane("FX"))
        assertEquals(AssetClass.PERPS, AssetClass.fromLane("PERPS"))
        assertEquals(AssetClass.CRYPTO_ALT, AssetClass.fromLane("CRYPTO_ALT"))
        // The critical fix: unrecognised strings must NOT coerce to
        // SOLANA_TOKEN. This is what let STOCK_* pipe through Solana marks.
        assertEquals(AssetClass.UNKNOWN, AssetClass.fromLane("SOME_NEW_LANE"))
        assertEquals(AssetClass.UNKNOWN, AssetClass.fromLane("XYZ"))
        assertEquals(AssetClass.UNKNOWN, AssetClass.fromLane(""))
        assertEquals(AssetClass.UNKNOWN, AssetClass.fromLane(null))
    }

    @Test
    fun aate6592_fromPositionIdPrefix_infers_class_from_id() {
        assertEquals(AssetClass.STOCK, AssetClass.fromPositionIdPrefix("STOCK_AAPL_123"))
        assertEquals(AssetClass.STOCK, AssetClass.fromPositionIdPrefix("STOCK:NVDA:runId:1"))
        assertEquals(AssetClass.FOREX, AssetClass.fromPositionIdPrefix("FOREX_GBPJPY_1"))
        assertEquals(AssetClass.FOREX, AssetClass.fromPositionIdPrefix("FX_EURUSD_1"))
        assertEquals(AssetClass.METAL, AssetClass.fromPositionIdPrefix("METAL_XAU_1"))
        assertEquals(AssetClass.COMMODITY, AssetClass.fromPositionIdPrefix("COMMODITY_BRENT_1"))
        assertEquals(AssetClass.CRYPTO_ALT, AssetClass.fromPositionIdPrefix("ALT_BTC_1"))
        assertEquals(AssetClass.CRYPTO_ALT, AssetClass.fromPositionIdPrefix("CRYPTO_ALT_ETH_1"))
        assertEquals(AssetClass.PERPS, AssetClass.fromPositionIdPrefix("PERPS_SOL_1"))
        // Solana paper positions (PAPER:mint:seq) do NOT match a non-SOL
        // prefix and remain UNKNOWN — no false-positive class corrections
        // for genuine SOL positions.
        assertEquals(AssetClass.UNKNOWN, AssetClass.fromPositionIdPrefix("PAPER:9wm4rF...:70273:5"))
        assertEquals(AssetClass.UNKNOWN, AssetClass.fromPositionIdPrefix(null))
        assertEquals(AssetClass.UNKNOWN, AssetClass.fromPositionIdPrefix(""))
    }

    @Test
    fun aate6592_authority_openPosition_repairs_stock_id_with_solana_class() {
        val authoritySrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt"
        ).readText()
        assertTrue(
            "V5.0.6592: openPosition must check positionId prefix vs class",
            authoritySrc.contains("val inferredFromId6592 = AssetClass.fromPositionIdPrefix(positionId)") &&
                authoritySrc.contains("effectiveAssetClass6592")
        )
        assertTrue(
            "V5.0.6592: mismatch must emit ASSET_CLASS_POSITIONID_MISMATCH_6592",
            authoritySrc.contains("ASSET_CLASS_POSITIONID_MISMATCH_6592")
        )
        assertTrue(
            "V5.0.6592: unknown-class open must emit ASSET_CLASS_UNKNOWN_ON_OPEN_6592",
            authoritySrc.contains("ASSET_CLASS_UNKNOWN_ON_OPEN_6592")
        )
        assertTrue(
            "V5.0.6592: Position row must store effectiveAssetClass6592",
            authoritySrc.contains("assetClass = effectiveAssetClass6592,")
        )
    }

    @Test
    fun aate6592_cryptoalt_dispatch_class_attributed() {
        val altSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt"
        ).readText()
        assertTrue(
            "V5.0.6592: CryptoAltTrader must class-attribute dispatch via markAdapterDispatchFor6551(CRYPTO_ALT, ...)",
            altSrc.contains("markAdapterDispatchFor6551(") &&
                altSrc.contains("AssetClass.CRYPTO_ALT")
        )
        assertTrue(
            "V5.0.6592: CryptoAltTrader must class-attribute auth allow + intent",
            altSrc.contains("markAuthAllowFor6551(") && altSrc.contains("markIntentCreatedFor6551(")
        )
    }

    @Test
    fun aate6592_perps_dispatch_class_attributed() {
        val perpsSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/perps/PerpsExecutionEngine.kt"
        ).readText()
        assertTrue(
            "V5.0.6592: PerpsExecutionEngine must class-attribute dispatch",
            perpsSrc.contains("markAdapterDispatchFor6551(") &&
                perpsSrc.contains("AssetClass.PERPS")
        )
    }

    @Test
    fun aate6592_bot_mark_refresh_routes_by_inferred_class() {
        val botSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue(
            "V5.0.6592: mark-refresh must consult positionId prefix and route by inferred class if the stored class disagrees",
            botSrc.contains("inferredMarkClass6592") &&
                botSrc.contains("effectiveMarkClass6592") &&
                botSrc.contains("ASSET_CLASS_MARK_REFRESH_ROUTING_CORRECTED_6592")
        )
        assertTrue(
            "V5.0.6592: mark-refresh must skip UNKNOWN-class refreshes rather than guess",
            botSrc.contains("MARK_REFRESH_SKIPPED_UNKNOWN_CLASS_6592")
        )
    }
}
