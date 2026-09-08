package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression locks for the 5.0.6701 Meme Trader phantom mega-PnL repair. */
class Aate6701MemeDecimalScalePnlRepairTest {

    @Test
    fun screenshot_class_one_million_scale_jump_is_basis_corruption_not_profit() {
        val verdict = OpenPnlSanity.inspect(
            entryPrice = 0.0000000274,
            currentPrice = 0.027515,
            entrySource = "DEXSCREENER_WS",
            currentSource = "DEXSCREENER_WS",
            entryPool = "RAYDIUM_POOL_REAL",
            currentPool = "RAYDIUM_POOL_REAL",
            tokenDecimals = 6,
            emit = false,
        )
        assertFalse(verdict.ok)
        assertEquals("TOKEN_DECIMAL_SCALE_DISCONTINUITY_6701", verdict.reason)
    }

    @Test
    fun missing_decimal_metadata_still_rejects_common_solana_raw_ui_scale() {
        val verdict = OpenPnlSanity.inspect(
            entryPrice = 0.000000019,
            currentPrice = 0.018887,
            entrySource = "DEXSCREENER_WS",
            currentSource = "DEXSCREENER_WS",
            entryPool = "SAME_POOL",
            currentPool = "SAME_POOL",
            tokenDecimals = -1,
            emit = false,
        )
        assertFalse(verdict.ok)
        assertEquals("TOKEN_DECIMAL_SCALE_DISCONTINUITY_6701", verdict.reason)
    }

    @Test
    fun genuine_same_pool_500x_runner_remains_unclamped() {
        val verdict = OpenPnlSanity.inspect(
            entryPrice = 0.000001,
            currentPrice = 0.0005,
            entrySource = "DEXSCREENER_WS",
            currentSource = "DEXSCREENER_WS",
            entryPool = "RAYDIUM_POOL_REAL",
            currentPool = "RAYDIUM_POOL_REAL",
            tokenDecimals = 6,
            emit = false,
        )
        assertTrue(verdict.ok)
        assertTrue(verdict.pnlPct > 49_000.0)
    }

    @Test
    fun rejected_decimal_mark_self_heals_poisoned_peak_and_route_high_water() {
        val ts = TokenState(mint = "mint6701", symbol = "CATE")
        ts.position = Position(
            qtyToken = 8_374_440.0,
            entryPrice = 0.0000000274,
            costSol = 0.2217,
            highestPrice = 0.027515,
            peakGainPct = 100_421_705.9,
            entryPriceSource = "DEXSCREENER_WS",
            entryPoolAddress = "RAYDIUM_POOL_REAL",
            lastRoutePrice = 0.027515,
            lastRoutePriceTs = System.currentTimeMillis(),
            isPaperPosition = true,
        )
        ts.lastPrice = 0.027515
        ts.lastPriceSource = "DEXSCREENER_WS"
        ts.lastPricePoolAddr = "RAYDIUM_POOL_REAL"
        ts.tokenMap.decimals = 6

        val verdict = OpenPnlSanity.inspect(ts, emit = false)
        assertFalse(verdict.ok)
        assertEquals(0.0, ts.position.peakGainPct, 0.0)
        assertEquals(ts.position.entryPrice, ts.position.highestPrice, 0.0)
        assertEquals(0.0, ts.position.lastRoutePrice, 0.0)
        assertEquals(0L, ts.position.lastRoutePriceTs)
    }

    @Test
    fun dexscreener_price_writer_stamps_provenance_with_the_price() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/DataOrchestrator.kt").readText()
        val callback = src.substringAfter("V5.0.6701 — PRICE + PROVENANCE ARE ONE ATOMIC FACT")
            .substringBefore("ts.lastBuyPressurePct")
        val sourceAt = callback.indexOf("ts.lastPriceSource = \"DEXSCREENER_WS\"")
        val poolAt = callback.indexOf("ts.lastPricePoolAddr = dexPair6701")
        val priceAt = callback.indexOf("ts.lastPrice = priceUsd")
        assertTrue(sourceAt >= 0)
        assertTrue(poolAt > sourceAt)
        assertTrue(priceAt > poolAt)
    }

    @Test
    fun main_open_position_pnl_cannot_bypass_failed_shared_verdict() {
        val src = File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        val block = src.substringAfter("val pnlVerdict = com.lifecyclebot.engine.OpenPnlSanity.inspect(ts, \"MainActivity.renderRow")
            .substringBefore("V5.0.6412 — PHANTOM -100% GUARD")
        assertTrue(block.contains("var basisTrusted = pnlVerdict.ok"))
        assertTrue(block.contains("if (basisTrusted)"))
        // The legacy canonical-fill recompute is permitted only after the shared
        // authority has already accepted the TokenState basis. 6701's decimal
        // discontinuity therefore cannot reach this raw display override.
        assertTrue(block.indexOf("if (basisTrusted)") < block.indexOf("recomputedPct"))
    }
}
