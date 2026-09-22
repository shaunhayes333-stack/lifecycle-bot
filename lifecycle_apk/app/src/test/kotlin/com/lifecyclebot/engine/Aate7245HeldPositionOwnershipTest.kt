package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7245HeldPositionOwnershipTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun stale_or_missing_prices_never_terminally_remove_held_positions() {
        val crypto = src("perps/CryptoAltTrader.kt")
        val stale = src("engine/truth/StaleMarkRunnerProtection6829.kt")
        val cyclic = src("engine/CyclicTradeEngine.kt")
        assertTrue(crypto.contains("CRYPTO_HELD_STALE_MARK_REFRESH_ONLY_7245"))
        assertFalse(crypto.contains("UNTRUSTED_DYNAMIC_MARK_ADMIN_REFUND_6663"))
        assertFalse(crypto.contains("settleUntrustedDynamicPaperPosition6663"))
        assertTrue(stale.contains("HELD_STALE_MARK_REFRESH_ONLY_7245"))
        assertFalse(stale.contains("Verdict.SCRATCH_ALLOWED"))
        assertTrue(cyclic.contains("CYCLIC_HELD_STALE_MARK_REFRESH_ONLY_7245"))
        assertTrue(cyclic.contains("HELD_MARK_STALE_REFRESH_ONLY_7245"))
    }

    @Test fun wallet_ownership_outranks_dead_price_quarantine() {
        val host = src("engine/HostWalletTokenTracker.kt")
        val recon = src("engine/sell/LiveWalletReconciler.kt")
        assertTrue(host.contains("WALLET_HELD_DEAD_QUARANTINE_PRESERVED_7245"))
        assertTrue(recon.contains("LIVE_WALLET_HELD_QUARANTINE_REFRESH_7245"))
        assertFalse(recon.contains("LIVE_WALLET_RECONCILER_SKIP_DEAD_TOKEN"))
    }

    @Test fun dynamic_registry_does_not_forge_freshness_or_evict_held_crypto() {
        val reg = src("perps/DynamicAltTokenRegistry.kt")
        val carry = reg.substringAfter("private fun carryForwardPrice6819").substringBefore("private const val CG_BATCH_TTL_MS_7164")
        assertFalse(carry.contains("lastUpdatedMs = System.currentTimeMillis()"))
        assertTrue(reg.contains("CRYPTO_REGISTRY_HELD_STALE_PRESERVED_7245"))
        assertTrue(reg.contains("heldCryptoKeys7245"))
    }

    @Test fun markets_dashboard_does_not_expose_crypto_universe_as_a_markets_tab() {
        val ui = src("ui/MultiAssetActivity.kt")
        assertTrue(ui.contains("visibleMarketTabs7245"))
        assertTrue(ui.contains("it != AssetTab.CRYPTO"))
        assertTrue(ui.contains("Crypto Universe owns its own CryptoAltActivity"))
    }
}
