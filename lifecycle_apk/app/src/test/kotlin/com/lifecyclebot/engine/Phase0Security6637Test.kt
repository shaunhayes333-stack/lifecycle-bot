package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase0Security6637Test {
    @Test
    fun wallet_has_no_trust_all_or_hostname_bypass_and_requires_finality() {
        val wallet = File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        assertFalse(wallet.contains("unsafeWalletRpcClient"))
        assertFalse(wallet.contains("hostnameVerifier { _, _ -> true }"))
        assertFalse(wallet.contains("checkServerTrusted(chain"))
        assertTrue(wallet.contains("SolanaSigningEnvelope.validate"))
        assertTrue(wallet.contains("if (status == \"finalized\") return true"))
    }

    @Test
    fun network_security_does_not_trust_user_installed_cas() {
        val xml = File("src/main/res/xml/network_security_config.xml").readText()
        assertFalse(xml.contains("certificates src=\"user\""))
        assertTrue(xml.contains("certificates src=\"system\""))
    }

    @Test
    fun live_buy_journal_and_fee_are_proof_gated() {
        val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertFalse(executor.contains("entryQtyToken = if (price > 0.0) sol / price else 0.0"))
        assertTrue(executor.contains("LIVE_BUY_SIDE_EFFECTS_DEFERRED_6637"))
        assertTrue(executor.contains("proofState = \"LIVE_FINALIZED\""))
        assertTrue(executor.contains("LIVE_BUY_PROOF_SIDE_EFFECTS_COMMITTED_6637"))
        assertTrue(executor.contains("actualRawQty = actualRawQty6637"))
    }

    @Test
    fun oracle_paths_have_no_synthetic_trading_fallback() {
        val pyth = File("src/main/kotlin/com/lifecyclebot/perps/PythOracle.kt").readText()
        val fetcher = File("src/main/kotlin/com/lifecyclebot/perps/PerpsMarketDataFetcher.kt").readText()
        val perps = File("src/main/kotlin/com/lifecyclebot/perps/JupiterPerps.kt").readText()
        assertFalse(pyth.contains("getFallbackPrice"))
        assertFalse(pyth.contains("?: 150.0"))
        assertTrue(fetcher.contains("createSolMarketData(0.0)"))
        assertFalse(perps.contains("PythOracle.getPrice(market.symbol)?.price ?: 150.0"))
    }

    @Test
    fun no_bundled_provider_defaults_or_plaintext_secret_fallback() {
        val defaults = File("src/main/kotlin/com/lifecyclebot/data/DefaultKeys.kt").readText()
        val config = File("src/main/kotlin/com/lifecyclebot/data/BotConfig.kt").readText()
        assertFalse(defaults.contains("private fun dec"))
        assertFalse(defaults.contains("_X"))
        assertFalse(config.contains("bot_secrets_fallback"))
        assertFalse(config.contains("KEY_FILE}_fallback"))
        assertTrue(config.contains("refusing plaintext secret storage"))
    }

    @Test
    fun untrusted_cross_instance_learning_is_quarantined() {
        val cloud = File("src/main/kotlin/com/lifecyclebot/engine/CloudLearningSync.kt").readText()
        val collective = File("src/main/kotlin/com/lifecyclebot/collective/CollectiveLearning.kt").readText()
        assertTrue(cloud.contains("SECURE_HIVE_GATEWAY_READY_6637 = false"))
        assertTrue(collective.contains("SECURE_HIVE_GATEWAY_REQUIRED_6637"))
    }
}
