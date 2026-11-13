package com.lifecyclebot.engine

import com.lifecyclebot.network.PoolBalanceSwap8734
import com.lifecyclebot.network.PumpSwapTape8307
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8734 — a swap on any venue read from the pool's own vault moves; the parsed stream sized by the owner's plan. */
class Aate8734AnyDexTapeTest {
    private val wsol = PoolBalanceSwap8734.WSOL_8734
    private val coin = "CoinMint1111111111111111111111111111111111"
    private fun bal(idx: Int, owner: String, mint: String, ui: Double) = JSONObject().put("accountIndex", idx).put("owner", owner)
        .put("mint", mint).put("uiTokenAmount", JSONObject().put("uiAmountString", ui.toString()).put("uiAmount", ui))
    private fun meta(pre: List<JSONObject>, post: List<JSONObject>) =
        JSONObject().put("preTokenBalances", JSONArray(pre)).put("postTokenBalances", JSONArray(post))

    @Test fun aBuyIsThePoolGivingTokensForSol() {
        // trader wraps 1 SOL (wSOL account closed in the same tx: absent both sides), receives 1,000 tokens; pool vaults move
        val m = meta(
            listOf(bal(3, "POOLAUTH", coin, 50_000.0), bal(4, "POOLAUTH", wsol, 20.0)),
            listOf(bal(3, "POOLAUTH", coin, 49_000.0), bal(4, "POOLAUTH", wsol, 21.0), bal(5, "TRADER", coin, 1_000.0)),
        )
        val s = PoolBalanceSwap8734.swapOf8734(m, coin, "TRADER")!!
        assertTrue(s.isBuy); assertEquals(1.0, s.sol, 1e-9); assertEquals(1_000.0, s.tokens, 1e-9); assertEquals(0.001, s.priceSol, 1e-12)
        assertEquals(setOf(coin), PoolBalanceSwap8734.mintsOf8734(m))
    }

    @Test fun aSellAndTheTraderNeverCountsAsThePool() {
        val m = meta(
            listOf(bal(3, "POOLAUTH", coin, 49_000.0), bal(4, "POOLAUTH", wsol, 21.0), bal(5, "TRADER", coin, 1_000.0), bal(6, "TRADER", wsol, 0.0)),
            listOf(bal(3, "POOLAUTH", coin, 50_000.0), bal(4, "POOLAUTH", wsol, 20.1), bal(5, "TRADER", coin, 0.0), bal(6, "TRADER", wsol, 0.9)),
        )
        val s = PoolBalanceSwap8734.swapOf8734(m, coin, "TRADER")!!
        assertFalse(s.isBuy); assertEquals(0.9, s.sol, 1e-9)
        assertNull("with the trader not excluded two owners qualify: ambiguous, no print", PoolBalanceSwap8734.swapOf8734(m, coin, ""))
    }

    @Test fun aRouteThroughAnotherQuoteIsNoPrint() {
        val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val m = meta(listOf(bal(3, "POOL", coin, 100.0), bal(4, "POOL", usdc, 10.0)), listOf(bal(3, "POOL", coin, 90.0), bal(4, "POOL", usdc, 11.0)))
        assertNull(PoolBalanceSwap8734.swapOf8734(m, coin, "TRADER"))
    }

    @Test fun theStreamIsSizedByThePlanAndPutsNonPumpCoinsFirst() {
        assertEquals(6, PumpSwapTape8307.nextTapeWidth8307(12, 4_000_000L))
        val high = PumpSwapTape8307.tapeHighBytes8734(2_833_333.0)
        assertTrue("$high", high > 10_000_000L)
        assertEquals(12, PumpSwapTape8307.nextTapeWidth8307(12, 4_000_000L, high, PumpSwapTape8307.tapeMax8734(high)))
        assertEquals(14, PumpSwapTape8307.nextTapeWidth8307(12, 1_000_000L, high, PumpSwapTape8307.tapeMax8734(high)))
        assertEquals(6, PumpSwapTape8307.nextTapeWidth8307(12, high + 1L, high, PumpSwapTape8307.tapeMax8734(high)))
        assertEquals(PumpSwapTape8307.TAPE_MAX_8307, PumpSwapTape8307.tapeMax8734(PumpSwapTape8307.tapeHighBytes8734(10_000.0)))
        val src = File("src/main/kotlin/com/lifecyclebot/network/PumpSwapTape8307.kt").readText()
        assertTrue(src.contains("|| !it.trim().endsWith(\"pump\") }"))
        assertTrue(src.contains("PoolBalanceSwap8734.swapOf8734(meta, it, signer8734) }"))
    }
}
