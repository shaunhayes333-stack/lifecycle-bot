package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.6680 — regression locks for operator screenshot phantom mega-PnL. */
class Aate6680OpenPnlIntegrityRegressionTest {

    @Test
    fun historical_rescale_flag_cannot_waive_extreme_ratio_guard() {
        val verdict = OpenPnlSanity.inspect(
            entryPrice = 0.000001,
            currentPrice = 0.01,
            entrySource = "PUMP_FUN_BC_SYNTHETIC",
            currentSource = "DEXSCREENER_WS",
            entryPool = "pump-old",
            currentPool = "raydium-new",
            priceBasisRescaled = true,
            emit = false,
        )
        assertFalse("historical priceBasisRescaled must never be current-mark proof", verdict.ok)
    }

    @Test
    fun same_source_name_alone_cannot_authorize_astronomical_ratio() {
        val verdict = OpenPnlSanity.inspect(
            entryPrice = 0.000001,
            currentPrice = 0.0025, // 2500x
            entrySource = "DEXSCREENER_PAIR_POLL",
            currentSource = "DEXSCREENER_PAIR_POLL",
            entryPool = "",
            currentPool = "",
            emit = false,
        )
        assertFalse("2500x with no immutable same-pool proof must be basis-wait", verdict.ok)
        assertTrue(verdict.reason.contains("ASTRONOMICAL_RATIO_REQUIRES_SAME_POOL_PROOF_6680"))
    }

    @Test
    fun genuine_500x_move_is_not_clamped_or_disabled() {
        val verdict = OpenPnlSanity.inspect(
            entryPrice = 0.000001,
            currentPrice = 0.0005, // 500x ratio / +49,900%
            entrySource = "DEXSCREENER_WS",
            currentSource = "DEXSCREENER_WS",
            entryPool = "RAYDIUM_POOL_REAL_123",
            currentPool = "RAYDIUM_POOL_REAL_123",
            emit = false,
        )
        assertTrue("coherent same-pool 500x moonshot must remain fully representable", verdict.ok)
        assertTrue(verdict.pnlPct > 49_000.0)
    }

    @Test
    fun persisted_known_sentinel_entry_never_becomes_trusted_pnl() {
        val verdict = OpenPnlSanity.inspect(
            entryPrice = 0.050250000,
            currentPrice = 119.0,
            entrySource = "DEXSCREENER_PAIR_POLL",
            currentSource = "DEXSCREENER_PAIR_POLL",
            entryPool = "SAME_POOL",
            currentPool = "SAME_POOL",
            emit = false,
        )
        assertFalse("known provider-template entry must stay quarantined even with a later real mark", verdict.ok)
        assertTrue(verdict.reason.contains("ENTRY_PRICE_SENTINEL_6680"))
    }

    @Test
    fun source_contract_must_not_reintroduce_6116b_patch_rot() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OpenPnlSanity.kt").readText()
        assertTrue(src.contains("V5.0.6116b RESTORED BY V5.0.6680"))
        assertTrue(src.contains("val explicitComparable = samePool || sameSource"))
        assertFalse(src.contains("val explicitComparable = samePool || sameSource || priceBasisRescaled"))
        assertTrue(src.contains("ASTRONOMICAL_RATIO_REPROOF_6680"))
    }
}
