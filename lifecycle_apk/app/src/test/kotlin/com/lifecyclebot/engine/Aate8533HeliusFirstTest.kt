package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DiscoveryCapture8400
import com.lifecyclebot.engine.truth.MintSupply8418
import com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075
import com.lifecyclebot.engine.truth.PairFlow8169
import com.lifecyclebot.network.HeliusSupplyBatch8533
import com.lifecyclebot.network.KeylessRpc8359
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8533 §HELIUS_MATE — 8529 live: 1,397 market caps unresolved for want of a verified supply while Helius answered
 * 100 %; the DAS answer that carried the supply was parsed for its price and the supply thrown away; the supply request's
 * silent early returns had no counter; the sweep row and the pair carried a cap nobody read.
 */
class Aate8533HeliusFirstTest {

    @Test fun paid_rungs_lead_a_market_read_and_keyless_nodes_follow() {
        val helius = "https://mainnet.helius-rpc.com/?api-key=k"; val alchemy = "https://solana-mainnet.g.alchemy.com/v2/k"
        val beta = "https://api.mainnet-beta.solana.com"; val pn = "https://solana-rpc.publicnode.com"
        val ladder = listOf(beta, pn, helius, alchemy)
        assertEquals(listOf(helius, alchemy, beta, pn), KeylessRpc8359.order8359(ladder, KeylessRpc8359.Route8359.MARKET, { true }))
        assertEquals(listOf(helius, alchemy, pn, beta), KeylessRpc8359.order8359(ladder, KeylessRpc8359.Route8359.MARKET, { it != beta }))
        assertEquals(ladder, KeylessRpc8359.order8359(ladder, KeylessRpc8359.Route8359.EXECUTION, { true }))
    }

    @Test fun a_das_asset_answer_is_kept_as_the_mints_supply() {
        MintSupply8418.resetForTest8418()
        val asset = JSONObject("""{"id":"So11111111111111111111111111111111111111112","token_info":{"supply":1000000000000000,"decimals":6}}""")
        val s = MintSupply8418.fromDasAsset8418(asset, 1_000L)
        assertTrue(s != null && MintSupply8418.accept8418(s, notifyMetrics = false))
        assertEquals(1_000_000_000.0, MintSupply8418.wholeOrZero8418("So11111111111111111111111111111111111111112"), 1e-6)
        assertFalse("DAS is not a mint-account read", MintSupply8418.chainRead8418("So11111111111111111111111111111111111111112"))
        MintSupply8418.resetForTest8418()
    }

    @Test fun the_supply_batch_is_bounded_and_asks_a_mint_once_per_ttl() {
        val now = 1_900_000_000_000L
        assertTrue(HeliusSupplyBatch8533.callAllowed8533(List(5) { now - 10_000L }, now))
        assertFalse(HeliusSupplyBatch8533.callAllowed8533(List(6) { now - 10_000L }, now))
        assertTrue("stamps older than a minute do not count", HeliusSupplyBatch8533.callAllowed8533(List(6) { now - 61_000L }, now))
        assertTrue(HeliusSupplyBatch8533.due8533(null, now))
        assertFalse(HeliusSupplyBatch8533.due8533(now - 60_000L, now))
        assertTrue(HeliusSupplyBatch8533.due8533(now - HeliusSupplyBatch8533.NEGATIVE_TTL_MS, now))
    }

    @Test fun a_wedged_gate_is_recognised_and_refused_ids_are_named_by_shape() {
        assertFalse(OnChainSupplyAuthority7075.gateStale8533(1_000L, 1_000L + OnChainSupplyAuthority7075.GATE_TTL_MS_8533))
        assertTrue(OnChainSupplyAuthority7075.gateStale8533(1_000L, 1_001L + OnChainSupplyAuthority7075.GATE_TTL_MS_8533))
        assertEquals("EVM", OnChainSupplyAuthority7075.keyShape8533("0xdAC17F958D2ee523a2206206994597C13D831ec7"))
        assertEquals("SHORT", OnChainSupplyAuthority7075.keyShape8533("BTC"))
        assertEquals("LONG", OnChainSupplyAuthority7075.keyShape8533("x".repeat(45)))
        assertEquals("BASE58_FAIL", OnChainSupplyAuthority7075.keyShape8533("O".repeat(40)))
    }

    @Test fun a_providers_cap_stands_in_for_a_non_curve_coin_only_while_fresh() {
        val now = 1_900_000_000_000L
        assertEquals(35_440.0, DiscoveryCapture8400.providerCap8533(35_440.0, now - 60_000L, 40_000.0, now - 1_000L, now)!!, 0.0)
        assertEquals("the sweep row leads; the pair fdv follows", 40_000.0,
            DiscoveryCapture8400.providerCap8533(35_440.0, now - 16L * 60_000L, 40_000.0, now - 1_000L, now)!!, 0.0)
        assertNull(DiscoveryCapture8400.providerCap8533(35_440.0, now - 16L * 60_000L, 40_000.0, now - 16L * 60_000L, now))
        assertNull(DiscoveryCapture8400.providerCap8533(null, null, Double.NaN, now, now))
        assertNull(DiscoveryCapture8400.providerCap8533(0.0, now, 0.0, now, now))
        assertNull("a reading from the future is no reading", DiscoveryCapture8400.providerCap8533(35_440.0, now + 1L, null, null, now))
    }

    @Test fun the_pair_flow_keeps_the_pairs_cap() {
        val f = PairFlow8169.fromJson8169(JSONObject("""{"priceUsd":"0.001","fdv":123456.0,"liquidity":{"usd":5000}}"""), 1_000L)
        assertEquals(123_456.0, f.fdvUsd8533, 0.0)
        val m = PairFlow8169.fromJson8169(JSONObject("""{"marketCap":99.0,"fdv":123456.0}"""), 1_000L)
        assertEquals("marketCap wins over fdv when both are served", 99.0, m.fdvUsd8533, 0.0)
        assertTrue(PairFlow8169.fromJson8169(JSONObject("""{"priceUsd":"0.001"}"""), 1_000L).fdvUsd8533.isNaN())
    }
}
