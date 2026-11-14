package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8752: regression guard for the 8750 no-live-trades crypto-alt capture. */
class Aate8752CryptoDataAndAuthorityTest {
    private fun source(path: String): String =
        File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun dynamic_spot_tokens_do_not_become_orphans_of_a_dormant_perps_producer() {
        val trader = source("perps/CryptoAltTrader.kt")
        val dynamic = trader.substringAfter("private suspend fun runDynamicTokenScan")
            .substringBefore("private suspend fun runScanCycle")
        assertTrue(dynamic.contains("val cryptoDecision7244 = scoreDynamicCrypto7244("))
        assertFalse(dynamic.contains("if (SOL_PERPS_SYMBOLS.contains(tok.symbol.uppercase()))"))
        assertFalse(dynamic.contains("markEvaluationDisposition6567(tok, \"OWNED_BY_SOL_PERPS\")"))
    }

    @Test fun elected_crypto_signals_are_not_refused_by_a_duplicate_fixed_score_floor() {
        val trader = source("perps/CryptoAltTrader.kt")
        val pre = trader.substringAfter("val pre = when {").substringBefore("val adapter = when {")
        assertTrue(pre.contains("hardNo.isNotEmpty() -> CryptoFinalBuyCandidate.PreFdgVerdict.HARD_NO_BUY"))
        assertTrue(pre.contains("signal.score > 0 && signal.confidence > 0"))
        assertFalse(pre.contains("signal.score >= 50 && signal.confidence >= 40"))
        assertTrue(trader.contains("CanonicalEntryAuthority6551.submit("))
    }

    @Test fun crypto_missing_flow_is_enriched_with_real_provenance_under_a_network_budget() {
        val trader = source("perps/CryptoAltTrader.kt")
        val registry = source("perps/DynamicAltTokenRegistry.kt")
        val dex = source("network/DexscreenerApi.kt")
        assertTrue(trader.contains("CRYPTO_SIGNAL_HYDRATION_SCAN_BUDGET_8752 = 12"))
        assertTrue(trader.contains("CRYPTO_SIGNAL_HYDRATION_CONCURRENCY_8752 = 3"))
        assertTrue(trader.contains("requireSignalInputs8752 = true"))
        assertTrue(trader.contains("cryptoSignalHydrationInFlight8752.remove(identity8752)"))
        assertTrue(registry.contains("priceChange24h = pair.priceChangeH24.takeIf { it.isFinite() }"))
        assertTrue(registry.contains("txns24hObservedAtMs8752 = if (pair.txnsH24Observed)"))
        assertTrue(dex.contains("priceChangeH24   = p.optJSONObject(\"priceChange\")"))
        assertTrue(registry.contains("put(\"txns24hObservedAtMs8752\", tok.txns24hObservedAtMs8752)"))
    }

    @Test fun non_solana_mints_still_never_fall_through_to_solana_symbol_routes() {
        val resolver = source("perps/crypto/CryptoUniverseRouteResolver.kt")
        assertTrue(resolver.contains("val nonSolanaExplicit6544 ="))
        assertTrue(resolver.contains("val resolvedMint = if (nonSolanaExplicit6544)"))
        assertTrue(resolver.contains("sym == \"SOL\" && targetMint6493.isNullOrBlank()"))
        assertTrue(resolver.contains("executable = false"))
    }
}
