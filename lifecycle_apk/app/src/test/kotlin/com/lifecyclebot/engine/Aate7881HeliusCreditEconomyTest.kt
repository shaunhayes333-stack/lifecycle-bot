package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.Tier
import com.lifecyclebot.network.HeliusCreditMeterInterceptor7881
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7881HeliusCreditEconomyTest {
    private val rpc = "mainnet.helius-rpc.com"

    @Test fun pricesFollowHeliusPublishedTable() {
        assertEquals(1.0, HeliusCreditEconomy7881.creditsFor(rpc, "/", listOf("getAccountInfo")), 1e-9)
        assertEquals(10.0, HeliusCreditEconomy7881.creditsFor(rpc, "/", listOf("getAssetBatch")), 1e-9)
        assertEquals(10.0, HeliusCreditEconomy7881.creditsFor(rpc, "/", listOf("getProgramAccounts")), 1e-9)
        // Batch: 15 getTransaction + nothing else = 15.
        assertEquals(15.0, HeliusCreditEconomy7881.creditsFor(rpc, "/", List(15) { "getTransaction" }), 1e-9)
        // Enhanced Transactions (legacy) — 100 per call.
        assertEquals(100.0, HeliusCreditEconomy7881.creditsFor("api.helius.xyz", "/v0/addresses/MINT/transactions", emptyList()), 1e-9)
        assertEquals(0.0, HeliusCreditEconomy7881.creditsFor("sender.helius-rpc.com", "/fast", emptyList()), 1e-9)
        // Websocket: 2 credits per 0.1 MB.
        assertEquals(2.0, HeliusCreditEconomy7881.creditsForWsBytes(100_000L), 1e-9)
    }

    @Test fun executionIsNeverEnrichment() {
        assertEquals(Tier.EXECUTION, HeliusCreditEconomy7881.tierFor(rpc, "/", listOf("sendTransaction")))
        assertEquals(Tier.EXECUTION, HeliusCreditEconomy7881.tierFor(rpc, "/", listOf("getLatestBlockhash", "getBalance")))
        assertEquals(Tier.EXECUTION, HeliusCreditEconomy7881.tierFor("sender.helius-rpc.com", "/fast", emptyList()))
        assertEquals(Tier.DECISION, HeliusCreditEconomy7881.tierFor(rpc, "/", listOf("getAccountInfo")))
        assertEquals(Tier.ENRICHMENT, HeliusCreditEconomy7881.tierFor(rpc, "/", listOf("getTransaction")))
        assertEquals(Tier.ENRICHMENT, HeliusCreditEconomy7881.tierFor("api.helius.xyz", "/v0/transactions", emptyList()))
    }

    @Test fun interceptorReadsSingleAndBatchMethods() {
        assertEquals(listOf("getAccountInfo"),
            HeliusCreditMeterInterceptor7881.methodsIn("""{"jsonrpc":"2.0","id":1,"method":"getAccountInfo","params":[]}"""))
        assertEquals(listOf("getTransaction", "getTransaction"),
            HeliusCreditMeterInterceptor7881.methodsIn("""[{"method":"getTransaction"},{"method" : "getTransaction"}]"""))
    }

    @Test fun enrichmentIsPacedByValueAndCanBorrowOnlyWhenWorthIt() {
        val b = 1_500_000.0
        // Early in the day a consumer has its first hour's slice: 1.5M * 0.10 * 1.0 * (1/24) = 6,250.
        assertTrue(HeliusCreditEconomy7881.admits(b, 0.0, 1.0, 0.10, 6_000.0, 6_000.0, 6_000.0, 100.0))
        // Over its own slice, a full-value consumer borrows from the unspent pool.
        assertTrue(HeliusCreditEconomy7881.admits(b, 0.0, 1.0, 0.10, 6_250.0, 6_250.0, 20_000.0, 100.0))
        // A measured-losing signal (value 0.15) cannot borrow: its own trickle is all it gets.
        assertFalse(HeliusCreditEconomy7881.admits(b, 0.0, 0.15, 0.08, 1_000.0, 1_000.0, 20_000.0, 100.0))
        // Nobody borrows while the whole day is running ahead of pace.
        assertFalse(HeliusCreditEconomy7881.admits(b, 0.5, 1.0, 0.10, 90_000.0, 100_000.0, 1_200_000.0, 100.0))
        // By the end of the day the full allowance is open.
        assertTrue(HeliusCreditEconomy7881.admits(b, 0.99, 1.0, 0.10, 149_000.0, 149_000.0, 900_000.0, 100.0))
    }
}
