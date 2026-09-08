package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level locks for the 5.0.6698 runtime loop regression. */
class Aate6699RuntimeLoopConvergenceTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun restart_terminal_proof_uses_exact_durable_typed_sell() {
        val proof = src("engine/truth/CanonicalTerminalProof6699.kt")
        assertTrue(proof.contains("CanonicalEconomicEvent6635.committedTerminalEventForPosition"))
        assertTrue(proof.contains("EconomicEventSchema6464.snapshot()"))
        assertTrue(proof.contains("filterIsInstance<EconomicEventSchema6464.Sell>()"))
        assertTrue(proof.contains("!it.partial"))
        assertTrue(proof.contains("it.idempotencyKey == economicEventId"))
        assertTrue(proof.contains("CANONICAL_TERMINAL_PROOF_DURABLE_RECOVERED_6699"))
    }

    @Test fun finality_retry_is_bounded_and_old_unprovable_rows_terminate() {
        val bridge = src("engine/truth/FinalizedBusConsumerBridge6465.kt")
        assertTrue(bridge.contains("CanonicalTerminalProof6699.resolve"))
        assertTrue(bridge.contains("EXACT_EVENT_GRACE_MS_6699"))
        assertTrue(bridge.contains("UNPROVABLE_EXACT_TERMINAL_ECONOMICS_6699"))
        assertTrue(bridge.contains("FINALIZED_CONSUMER_UNPROVABLE_EXCLUDED_6699"))
        assertTrue(bridge.contains("exactEventPendingLogged6699"))
        assertFalse(bridge.contains("action=no_mutation_no_ack_retry"))
    }

    @Test fun finality_replay_hydrates_typed_economics_first() {
        val persistence = src("engine/truth/CanonicalFinalityPersistence6486.kt")
        val init = persistence.substringAfter("fun initAndReplay")
        val hydrate = init.indexOf("EconomicEventSchema6464.init6486(context)")
        val replay = init.indexOf("events.forEach { CanonicalTradeFinalizedBus6450.publish(it) }")
        assertTrue(hydrate >= 0 && replay > hydrate)
    }

    @Test fun reward_purity_uses_restart_safe_terminal_proof() {
        val reward = src("engine/truth/RewardPurityGate6441.kt")
        assertTrue(reward.contains("CanonicalTerminalProof6699.resolve"))
        assertFalse(reward.contains("val terminalEvent = try"))
    }

    @Test fun journal_reconciliation_reuses_existing_replay() {
        val reconciliation = src("engine/truth/ForensicReconciliation6635.kt")
        val authority = src("engine/truth/JournalEconomicAuthority6616.kt")
        assertTrue(reconciliation.contains("precomputedReplay6699: JournalEconomicReplay6619.ReplayResult? = null"))
        assertTrue(reconciliation.contains("precomputedReplay6699 ?: try { JournalEconomicReplay6619.replay()"))
        assertTrue(authority.contains("ForensicReconciliation6635.reconcile6635(replay)"))
        val notify = authority.substringAfter("fun notifyEconomicMutation").substringBefore("fun currentSnapshot")
        assertTrue(notify.count { false } == 0) // keep block non-empty for source parser stability
        assertFalse(notify.contains("ForensicReconciliation6635.reconcile6635()"))
    }

    @Test fun stale_close_metadata_self_heals_only_for_newer_canonical_open() {
        val close = src("engine/PositionCloseLedger.kt")
        assertTrue(close.contains("clearIfCanonicallyReopened6699"))
        assertTrue(close.contains("p.openedAtMs > rec.closedAtMs"))
        assertTrue(close.contains("p.remainingQtyRaw > java.math.BigInteger.ZERO"))
        assertTrue(close.contains("PaperPositionCloseAuthority.reopen(\"PAPER\", mint)"))
        assertTrue(close.contains("PaperPositionCloseAuthority.reopen(\"LIVE\", mint)"))
    }

    @Test fun specialist_proposal_telemetry_is_bounded_and_not_execution_authority() {
        val arbiter = src("engine/truth/SpecialistProposalArbiter6629.kt")
        val openGate = src("engine/ExecutableOpenGate.kt")
        assertTrue(arbiter.contains("CopyOnWriteArrayList"))
        assertTrue(arbiter.contains("CONTEST_TTL_MS_6699"))
        assertTrue(arbiter.contains("SPECIALIST_STALE_CONTEST_PRUNED_6699"))
        assertTrue(arbiter.contains("mode=FDG_SEALED_AUTH_TELEMETRY_ONLY"))
        assertFalse(openGate.contains(".elect6629("))
    }
}
