package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.TerminalReceipt8419
import com.lifecyclebot.engine.truth.MintSupply8418
import com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8445 — supply is read 100 mint accounts per getMultipleAccounts call (8441: 52 of 1,300 asks resolved, one
 * getTokenSupply at a time); a held Cortex episode's terminal acknowledges on its own receipt row instead of waiting on the
 * multi-MB checkpoint (8439: terminal outbox 5,947 of 6,000, oldest 3.7 h, every expiring observation refused capacity).
 */
class Aate8445SupplyAndTerminalsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun mintValue(supply: String, decimals: Int) = JSONObject()
        .put("owner", "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
        .put("data", JSONObject().put("program", "spl-token").put("parsed", JSONObject().put("type", "mint")
            .put("info", JSONObject().put("supply", supply).put("decimals", decimals).put("isInitialized", true))))

    @Test fun oneBatchEntryParsesAsTheMintAccountRecord() {
        val ctx = JSONObject().put("slot", 300_000_000L)
        val one = OnChainSupplyAuthority7075.accountResult8445(ctx, mintValue("1000000000000000", 6))
        assertNotNull(one)
        val s = MintSupply8418.fromAccountInfoResult8418("MintA", one, 1_000L)
        assertNotNull(s)
        assertEquals(1e9, s!!.wholeTokens, 1e-3)
        assertNull("an account the node cannot see yet", OnChainSupplyAuthority7075.accountResult8445(ctx, JSONObject.NULL))
        assertNull(OnChainSupplyAuthority7075.accountResult8445(ctx, null))
        assertEquals(100, OnChainSupplyAuthority7075.BATCH_KEYS_8445)
    }

    @Test fun requestsGoToTheBatchBeforeTheSingleRead() {
        val o = src("engine/truth/OnChainSupplyAuthority7075.kt")
        val ask = o.substring(o.indexOf("fun requestAsync7075("), o.indexOf("private fun sweepCooldowns7116()"))
        assertTrue(ask.indexOf("if (enqueueBatch8445(mint, gate)) return") in 0 until ask.indexOf("pool.execute { resolveOnce7116(mint, gate) }"))
        assertTrue(o.contains("KeylessRpc8359.post8359(http, \"getMultipleAccounts\", params, \"supply-batch-8445\")"))
        assertTrue(OnChainSupplyAuthority7075.status().contains("batch8445[calls="))
    }

    @Test fun aHeldEpisodeAcknowledgesOnItsOwnReceipt() {
        assertTrue(TerminalReceipt8419.directReceipt8419("m|L|1", "DEADLINE", 1L, held = true).contains("EPISODE_RELEASED_8445"))
        assertTrue(TerminalReceipt8419.directReceipt8419("m|L|1", "DEADLINE", 1L).contains("EPISODE_NOT_HELD_8419"))
        val cx = src("engine/cortex/Cortex7885.kt")
        assertFalse(cx.contains("if (!held8419 && !observationTerminals8395.containsKey(receipt)) {"))
        assertTrue(cx.contains("TerminalReceipt8419.directReceipt8419(id, reason, atMs, held8419)"))
        assertEquals(192, com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.TERMINAL_DRAIN_BATCH_8445)
    }
}
