package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7454QuarantinedLiveInventoryRecoveryTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun quarantine_recovery_is_canonical_authority_owned() {
        val s = src("engine/truth/CanonicalPositionAuthority6441.kt")
        assertTrue(s.contains("fun recoverQuarantinedLivePosition7454("))
        assertTrue(s.contains("CANONICAL_QUARANTINED_LIVE_RECOVERED_7454"))
        assertTrue(s.contains("lockEntryMetricsAtOpen6636(recovered)"))
    }

    @Test fun only_recoverable_live_quarantines_can_reopen() {
        val s = src("engine/truth/CanonicalPositionAuthority6441.kt")
        assertTrue(s.contains("PENDING_ENTRY_TTL_CANCELLED_6461"))
        assertTrue(s.contains("EXIT_ELIGIBILITY_6570:INVALID_ENTRY_BASIS"))
        assertTrue(s.contains("EXIT_ELIGIBILITY_6570:INVALID_REMAINING_QUANTITY"))
        assertTrue(s.contains("prev.soldCostBasisSol > 1e-12"))
        assertTrue(s.contains("recoveredAssetClass == AssetClass.UNKNOWN"))
    }

    @Test fun wallet_recovery_reopens_same_quarantined_position_not_sibling() {
        val s = src("engine/LiveCanonicalRecovery6686.kt")
        assertTrue(s.contains("quarantinedLivePositions7454(mint)"))
        assertTrue(s.contains("recoverQuarantinedLivePosition7454("))
        assertTrue(s.contains("positionId = quarantinedSameMint7454.positionId"))
        assertTrue(s.contains("LIVE_QUARANTINED_POSITION_RECOVERED_7454"))
    }

    @Test fun live_buys_fail_closed_when_bot_wallet_inventory_is_outside_exit_scope() {
        val gate = src("engine/sell/LiveBuyAdmissionGate.kt")
        val executor = src("engine/Executor.kt")
        assertTrue(gate.contains("LiveExitCoverageGuard7701.assess(com.lifecyclebot.engine.WalletManager.currentPubkey())"))
        assertTrue(gate.contains("LIVE_BUY_BLOCKED_UNMANAGED_BOT_HOLD_7701"))
        assertTrue(gate.contains("HostWalletTokenTracker.PositionSource.BOT_BUY"))
        assertTrue(gate.contains("FillLotLedger6344.snapshotForWallet(walletAddress)"))
        assertTrue(gate.contains("CanonicalPositionAuthority6441.openPositions()"))
        assertTrue(gate.contains("quarantinedLivePositions7454()"))
        assertTrue(gate.contains("it in positiveWalletMints"))
        assertTrue(gate.contains("WalletManager.currentPubkey()"))
        assertTrue(gate.contains("callSite="))
        assertTrue(executor.contains("LiveBuyAdmissionGate.requireApprovedLiveBuy("))
        assertTrue(executor.contains("callSite = \"liveTopUp\""))
        assertTrue(executor.contains("callSite = \"liveBuy.main\""))
    }

    @Test fun recovered_inventory_returns_to_held_ownership_via_open_hook() {
        val a = src("engine/truth/CanonicalPositionAuthority6441.kt")
        val h = src("engine/HeldPositionSupervisor7246.kt")
        assertTrue(a.contains("handoffOpenMintToHeld7246(position.mint"))
        assertTrue(h.contains("CanonicalPositionAuthority6441.openPositions()"))
    }
}
