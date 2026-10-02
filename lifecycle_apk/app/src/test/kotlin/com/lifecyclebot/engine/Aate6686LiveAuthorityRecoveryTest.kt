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

    @Test fun `wallet proven pending bot buys recover into live exit scope without adopting unrelated holds`() {
        val bridge = src("com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt")
        val reservation = bridge.substringAfter("val pendingReservation7699")
            .substringBefore("val basis: Basis?")
        val fallback = bridge.substringAfter("botReservation7699?.let")
            .substringBefore("if (basis == null)")

        assertTrue(reservation.contains("it.mint == mint"))
        assertTrue(reservation.contains("it.mode.equals(\"live\", true)"))
        assertTrue(reservation.contains("PENDING_ENTRY_TTL_CANCELLED_6461"))
        assertTrue(reservation.contains("EXIT_ELIGIBILITY_6570:INVALID_ENTRY_BASIS"))
        assertTrue(reservation.contains("EXIT_ELIGIBILITY_6570:INVALID_REMAINING_QUANTITY"))
        assertTrue(fallback.contains("reservation.entryCostSol"))
        assertTrue(fallback.contains("reservation.entryPriceUsd"))
        assertTrue(fallback.contains("CANONICAL_PENDING_ENTRY_RESERVATION_7699"))
        assertTrue(bridge.contains("LIVE_PENDING_ENTRY_PROMOTED_FROM_WALLET_7133"))
        assertTrue(bridge.contains("if (basis == null)"))
        assertTrue(bridge.contains("action=retain_wallet_tracking_no_invented_basis"))
    }

    @Test fun `partial wallet fallback includes quarantined bot buys without a first-20 ceiling`() {
        val wallet = src("com/lifecyclebot/network/SolanaWallet.kt")
        val lots = src("com/lifecyclebot/engine/FillLotLedger6344.kt")
        val recovery = src("com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt")
        val known = wallet.substringAfter("private fun knownBotMints7374()")
            .substringBefore("fun getTokenAmountFromSig")
        val read = wallet.substringAfter("private fun readKnownMints7374")
            .substringBefore("fun getTokenAmountFromSig")
        assertTrue(known.contains("quarantinedLivePositions7454()"))
        assertTrue(known.contains("snapshotForWallet(owner)"))
        assertFalse(known.contains("take(20)"))
        assertTrue(read.contains("ExecutorCompletionService"))
        assertTrue(read.contains("shutdownNow()"))
        assertTrue(recovery.contains(".filter(::isRecoverableQuarantine7454)"))
        assertTrue(lots.contains("fun snapshotForWallet(walletAddress: String)"))
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
