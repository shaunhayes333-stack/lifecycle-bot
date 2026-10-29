package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.network.GradTicks8018
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8418 §A_HELD_POSITION_IS_NEVER_PRICED_BY_A_POLL — every held pool, any known venue, on a chain feed. */
class Aate8418HeldPoolFeedTest {
    @Test fun aTickNamesItsVenueAndIsOnThePositionsBasis() {
        assertEquals("PUMPSWAP_VAULT_WS_8018", GradTicks8018.sourceFor8418(OnChainDepth8038.PUMP_AMM, false))
        assertEquals("POOL_VAULT_WS_8418_RaydiumV4", GradTicks8018.sourceFor8418(OnChainDepth8038.RAYDIUM_V4, false))
        assertEquals("POOL_VAULT_WS_8418_MeteoraDAMMv2", GradTicks8018.sourceFor8418(OnChainDepth8038.METEORA_DAMM_V2_8418, false))
        assertEquals("POOL_STATE_WS_8418_RaydiumCLMM", GradTicks8018.sourceFor8418(OnChainDepth8038.RAYDIUM_CLMM_8418, true))
        for (s in listOf("POOL_VAULT_WS_8418_RaydiumV4", "POOL_STATE_WS_8418_OrcaWhirlpool", "PUMPSWAP_VAULT_WS_8018")) {
            assertTrue(s, StopMarkConfirm8418.onBasis8418(s, "", "MINT_ROUTE:x"))
        }
        assertFalse(StopMarkConfirm8418.onBasis8418("DEXSCREENER_PAIR_POLL", "", "MINT_ROUTE:x"))
    }

    @Test fun theFeedFollowsEveryHeldPoolNotOnlyPumpSwap() {
        val g = java.io.File("src/main/kotlin/com/lifecyclebot/network/GradTicks8018.kt").readText()
        assertTrue(g.contains("private const val MAX_POOLS = 60"))
        assertTrue(g.contains("OnChainDepth8038.priceInState8418(owner8418) ->"))
        assertTrue(g.contains("OnChainDepth8038.vaultKeys8038(owner8418, data)?.let { (a, b) ->"))
        assertTrue(g.contains("ConcentratedPrice8418.decode8418(pool.owner, bytes8418, mint, pool.baseDecimals, 9) ?: return"))
        assertTrue(g.contains("onTick(mint, px, pool.pool, sourceFor8418(pool.owner, pool.stateAccount.isNotBlank()))"))
        val b = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(b.contains("{ mint, priceSol, pool, source8418 ->"))
        assertTrue(b.contains("applyPumpTradeMark7278(mint, priceSol, 0.0, source8418, pool)"))
        assertTrue(b.contains("GradTicks8018.sync8018(openMints.take(60).toSet())"))
    }
}

/** V5.0.8418 §A_POLL_NEVER_OVERWRITES_THE_CHAIN. */
class Aate8418ChainFedMarksTest {
    @Test fun aChainFedRowIsNotOverwrittenByAPollUntilTheFeedGoesQuiet() {
        ChainFedMarks8418.resetForTest8418()
        ChainFedMarks8418.note8418("M", 10_000L)
        assertTrue(ChainFedMarks8418.chainFed8418("M", 10_500L))
        assertTrue(ChainFedMarks8418.chainFed8418("M", 70_000L))
        assertFalse("quiet for over a minute: the polls take over", ChainFedMarks8418.chainFed8418("M", 70_001L))
        assertFalse(ChainFedMarks8418.chainFed8418("NEVER", 10_500L))
        assertTrue(ChainFedMarks8418.pollMayWrite8418(0L, 5L))
        assertFalse(ChainFedMarks8418.pollMayWrite8418(10_000L, 30_000L))
        assertTrue(ChainFedMarks8418.pollMayWrite8418(10_000L, 71_000L))
    }

    @Test fun wiring() {
        val b = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(b.contains("ChainFedMarks8418.note8418(mint, now)"))
        assertTrue(b.contains("val chainFed8418 = ChainFedMarks8418.chainFed8418(mint, now)"))
        assertTrue(b.contains("LiquidityTruth8037.onPoolVaultObserved8418(mint, liq8418)"))
        val h = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/HeldHotMarkAuthority7419.kt").readText()
        assertTrue(h.contains("!com.lifecyclebot.engine.ChainFedMarks8418.chainFed8418(p.mint, publishedAt7835)"))
    }
}
