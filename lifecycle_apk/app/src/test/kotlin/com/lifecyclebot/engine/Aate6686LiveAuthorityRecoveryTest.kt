package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6686LiveAuthorityRecoveryTest {
    private fun src(path: String) = File("src/main/kotlin/$path").readText()

    @Test fun `token account snapshots use runtime provider authority and rotate every rpc error`() {
        val wallet = src("com/lifecyclebot/network/SolanaWallet.kt")
        val fn = wallet.substringAfter("private fun walletRpcEndpointsForTokenSnapshot")
            .substringBefore("private fun rpcTokenAccountsByOwnerFast")
        assertTrue(fn.contains("RuntimeProviderAuthority6685") && fn.contains("rpcCandidates(rpcUrl)"))
        assertTrue(fn.contains("applyRoundRobin(candidates)"))
        assertFalse(fn.contains("WalletManager.FALLBACK_RPCS"))
        val fast = wallet.substringAfter("private fun rpcTokenAccountsByOwnerFast")
            .substringBefore("private fun heliusDasFungibleTokensByOwner")
        assertTrue(fast.contains("WALLET_RPC_PROVIDER_ERROR_FAILOVER_6686"))
        assertTrue(fast.contains("action=continue_next_rpc"))
    }

    @Test fun `wallet positive live positions recover into canonical authority only with proven basis`() {
        val bridge = src("com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt")
        val reconciler = src("com/lifecyclebot/engine/WalletReconciler.kt")
        assertTrue(bridge.contains("activeMintProjections6490(\"live\")"))
        assertTrue(bridge.contains("PositionPersistence.loadPositions()"))
        assertTrue(bridge.contains("CanonicalBuyFillRegistry.get(mint)"))
        assertTrue(bridge.contains("modeOverride = \"live\""))
        assertTrue(bridge.contains("openedQtyRaw = amount.raw"))
        assertTrue(bridge.contains("LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686"))
        assertTrue(reconciler.contains("LiveCanonicalRecovery6686.recoverWalletSnapshot(status, walletMints)"))
    }

    @Test fun `ui positions project from canonical authority not mutable discovery token map`() {
        val projection = src("com/lifecyclebot/engine/truth/CanonicalUiPositionProjection6686.kt")
        val authority = src("com/lifecyclebot/engine/truth/UiSnapshotAuthority6496.kt")
        val vm = src("com/lifecyclebot/ui/BotViewModel.kt")
        assertTrue(projection.contains("CanonicalPositionAuthority6441.openPositions()"))
        assertTrue(authority.contains("CanonicalUiPositionProjection6686.project(status)"))
        assertFalse(authority.substringAfter("private fun refresh(status: BotStatus)").substringBefore("fun current()").contains("status.tokens.values"))
        assertTrue(vm.split("CanonicalUiPositionProjection6686.project(status)").size - 1 >= 2)
    }

    @Test fun `live specialist report cannot source capital from paper ledger`() {
        val toolkit = src("com/lifecyclebot/engine/ToolkitSignalSheet.kt")
        val report = toolkit.substringAfter("fun specialistCapitalReport6599()")
            .substringBefore("fun contributionSummary")
        assertTrue(report.contains("paperMode6686"))
        assertTrue(report.contains("LIVE_WALLET_AUTHORITY_6686"))
        assertTrue(report.contains("capitalSource6686"))
    }
}
