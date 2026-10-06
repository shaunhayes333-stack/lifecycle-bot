package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.7815 — recurrent-fault root-cause guards.
 *
 * These are source-contract tests for repairs that had previously existed in
 * one layer while a different authority still contradicted them.
 */
class Aate7815RecurringRootCauseRepairTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun emergency_owner_delta_reaches_existing_broadcast_authority() {
        val s = src("engine/sell/SellAmountAuthority.kt")
        val resolve = s.substringAfter("fun resolveForExit(").substringBefore("fun canBroadcastLiveOrEmergency(")
        assertTrue(resolve.contains("EMERGENCY_OWNER_DELTA_AMOUNT_RESTORED_7815"))
        assertTrue(resolve.contains("return Resolution.Confirmed(cached.rawAmount, cached.decimals, Source.TX_META_OWNER_DELTA)"))
        assertTrue(s.contains("canBroadcastLiveOrEmergency("))
        assertTrue(s.contains("emergencyWalletSnapshotBalance7730(mint, reason)?.let { return it }"))
    }

    @Test fun resident_specialist_book_is_valid_discovery_provenance() {
        val s = src("engine/truth/MemeExecutionFunnelReceivers6625.kt")
        val helper = s.substringAfter("fun ensureAffinityLineage7464(").substringBefore("fun stamp6625(")
        assertTrue(helper.contains("SpecialistCandidateBooks7803"))
        assertTrue(helper.contains("DISCOVER_FROM_RESIDENT_SPECIALIST_7815"))
        assertTrue(helper.contains("SPECIALIST_DISCOVER_RESIDENT_RECOVERED_7815_"))
    }

    @Test fun disabled_specialist_cannot_own_central_execution() {
        val s = src("engine/LaneExecutionCoordinator.kt")
        val owner = s.substringAfter("private fun enabledOwnerTrader7815(").substringBefore("private fun sealedFdgOwnerLane6679(")
        assertTrue(owner.contains("\"CYCLIC\" -> EnabledTraderAuthority.Trader.CYCLIC"))
        assertTrue(owner.contains("EnabledTraderAuthority.isEnabled(trader7815)"))
        assertTrue(owner.contains("LANE_OWNER_DISABLED_BY_AUTHORITY_7815_"))
    }

    @Test fun crypto_acceptance_counts_terminal_dispatches() {
        val authority = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        val acceptance = src("engine/truth/ExecutionSpineAcceptance6647.kt")
        assertTrue(authority.contains("AssetDispatchAccounting7815"))
        assertTrue(authority.contains("assetClassByDispatchAttempt7815"))
        assertTrue(acceptance.contains("cryptoTerminalResults"))
        assertTrue(acceptance.contains("CRYPTO_DISPATCH_WITHOUT_OPEN_PENDING_OR_TERMINAL"))
    }

    @Test fun native_liveness_reports_global_fanout_scope() {
        val s = src("engine/ToolkitSignalSheet.kt")
        assertTrue(s.contains("nativeScope7815=RESIDENT_OR_BOUNDED_SPECIALIST_SCOPE_7828"))
        assertTrue(s.contains("residentOwnLane7815="))
        assertTrue(s.contains("residentReady7815="))
    }
}
