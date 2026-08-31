package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MarkAuthorityIntegrityGate6496
import com.lifecyclebot.engine.truth.MarketDataProvenance6471
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate6615LoopStarvationRepairTest {
    @Test fun unchanged_market_sentinel_is_processed_once_then_coalesced() {
        MarketDataProvenance6471.resetForTest()
        repeat(100) {
            assertEquals(
                MarketDataProvenance6471.Provenance.NON_AUTHORITATIVE_SENTINEL,
                MarketDataProvenance6471.classify(
                    price = 0.001, mcap = 10_000.0, liquidity = 5_000.0,
                    source = "DEXSCREENER_PAIR_POLL", poolAddress = "MINT_ROUTE:Mint6615",
                    identity = "Mint6615",
                )
            )
        }
        val status = MarketDataProvenance6471.statusLine()
        assertTrue(status, status.contains("sentinelActive=1"))
        assertTrue(status, status.contains("sentinelCoalesced=99"))
    }

    @Test fun unchanged_degraded_mark_uses_bounded_retry_state() {
        MarketDataProvenance6471.resetForTest()
        MarkAuthorityIntegrityGate6496.resetForTest()
        repeat(100) {
            val r = MarkAuthorityIntegrityGate6496.evaluate(
                mint = "MintMark6615", priceUsd = 0.001, mcapUsd = 10_000.0,
                liquidityUsd = 5_000.0, source = "DEXSCREENER_PAIR_POLL",
                poolAddress = "MINT_ROUTE:MintMark6615", fresh = true,
                isKnownOpenMint6596 = false,
            )
            assertFalse(r.priceAuthoritative)
        }
        val status = MarkAuthorityIntegrityGate6496.statusLine()
        assertTrue(status, status.contains("evaluated=1"))
        assertTrue(status, status.contains("markCoalesced=99"))
        assertTrue(status, status.contains("markStates=1"))
    }

    @Test fun crypto_evaluation_has_one_owner_and_terminal_per_material_generation() {
        val id = "0x6615${System.nanoTime()}"
        val first = DynamicAltTokenRegistry.DynToken(
            mint = id, tokenAddress = id, chainId = "ethereum", symbol = "T6615",
            name = "Test 6615", price = 1.0, liquidityUsd = 50_000.0,
            volume24h = 20_000.0, source = "dex_test",
        )
        assertTrue(DynamicAltTokenRegistry.markEvaluationStarted6567(first))
        assertFalse(DynamicAltTokenRegistry.markEvaluationStarted6567(first))
        DynamicAltTokenRegistry.markEvaluationDisposition6567(first, "NO_EXECUTION_INTENT")
        assertFalse(DynamicAltTokenRegistry.markEvaluationStarted6567(first))
        val newer = first.copy(price = 1.01)
        assertTrue(DynamicAltTokenRegistry.markEvaluationStarted6567(newer))
        DynamicAltTokenRegistry.markEvaluationDisposition6567(first, "STALE_EXPIRED")
        DynamicAltTokenRegistry.markEvaluationDisposition6567(newer, "NO_EXECUTION_INTENT")
        val report = DynamicAltTokenRegistry.discoveryReport6544()
        assertTrue(report, report.contains("inflightCount=0"))
        assertTrue(report, report.contains("coalescedCount="))
        assertTrue(report, report.contains("staleDroppedCount="))
    }

    @Test fun source_contracts_keep_intake_cheap_and_unknown_out_of_execution_gate() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val worker = File("src/main/kotlin/com/lifecyclebot/engine/truth/MaintenanceWorker6448.kt").readText()
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val crypto = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(bot.contains("requestHotWatchlistRebalance6615(\"post_intake\")"))
        assertFalse(bot.contains("rebalanceHotWatchlistSources(\"post_intake\")"))
        assertTrue(worker.contains("runningNames6615.add(name)"))
        assertTrue(worker.contains("runningNames6615.remove(name)"))
        assertTrue(gate.contains("EXEC_OPEN_BLOCKED_NO_EXECUTION_INTENT_6615"))
        assertFalse(gate.contains("SIGNAL_NOT_BUY:${'$'}{signal.ifBlank { \"UNKNOWN\" }}"))
        assertTrue(crypto.contains("SHARED_INTELLIGENCE_BACKLOG_COALESCED"))
        assertEquals(1, Regex("SHARED_INTELLIGENCE_BACKLOG_COALESCED_REQUEUE").findAll(crypto).count())
    }
}
