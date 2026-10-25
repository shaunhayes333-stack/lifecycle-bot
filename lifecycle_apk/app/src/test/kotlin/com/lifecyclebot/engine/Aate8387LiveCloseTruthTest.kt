package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LearningQuarantineGate6470
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8387LiveCloseTruthTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun aVerifiedFillReleasesTheLearningGateForEveryPromoterNotOnlyTheLateProof() {
        LearningQuarantineGate6470.resetForTest()
        LearningQuarantineGate6470.quarantinePositionId("pos-1", "BASIS_UNCERTAIN_7807:BOT_HOLDING_BASIS_UNPROVEN_7718")
        assertTrue(LearningQuarantineGate6470.shouldDropForLearning("pos-1", null))
        assertTrue(LearningQuarantineGate6470.releaseProvenBasis8308("pos-1"))
        assertFalse(LearningQuarantineGate6470.isQuarantined("pos-1", null))
        // other quarantine reasons stay permanent
        LearningQuarantineGate6470.quarantinePositionId("pos-2", "REPLAY_DIVERGENCE")
        assertFalse(LearningQuarantineGate6470.releaseProvenBasis8308("pos-2"))
        val c = src("engine/truth/CanonicalPositionAuthority6441.kt")
        val promote = c.substringAfter("fun promotePendingToOpen(").substringBefore("fun ensureProtectiveLiveOwnership7807(")
        assertTrue(promote.indexOf("positions[positionId] = promoted") in 0 until promote.indexOf("LearningQuarantineGate6470.releaseProvenBasis8308(positionId)"))
        assertTrue(promote.contains("if (protective7807 && !paperMode && promoted.lifecycle != Lifecycle.CLOSED)"))
    }

    @Test fun theWalletSweepDoesNotAdoptABotBuyWhoseVerifierIsStillRunning() {
        val r = src("engine/LiveCanonicalRecovery6686.kt")
        val sweep = r.substringAfter("val covered7807 =").substringBefore("if (n7807 > 0)")
        assertTrue(sweep.contains("CanonicalPositionAuthority6441.pendingEntryLiveMints8387()"))
        assertTrue(sweep.indexOf("if (mint in pendingEntry8387)") in 0 until sweep.indexOf("ensureProtectiveLiveOwnership7807("))
        val c = src("engine/truth/CanonicalPositionAuthority6441.kt")
        assertTrue(c.contains("it.lifecycle == Lifecycle.PENDING_ENTRY && it.mode.equals(\"live\", true) && nowMs - it.lastMutationMs <= maxAgeMs"))
    }

    @Test fun aFiniteNonPositiveMfeIsAnObservedExitTimingLabel() {
        val b = src("engine/truth/FinalizedBusConsumerBridge6465.kt")
        val head = b.substringAfter("private fun deliverToUnifiedExitPolicyHead6696(").substringBefore("UnifiedExitPolicyHead.recordOutcome(env.mint, exitWasOptimal)")
        assertTrue(head.contains("if (env.mfePct > 0.0) env.realizedReturnPct >= env.mfePct * 0.70 else env.realizedReturnPct >= env.mfePct - 2.0"))
        assertTrue(head.contains("if (!env.mfePct.isFinite() || !env.realizedReturnPct.isFinite()) {"))
        assertFalse(head.contains("|| env.mfePct <= 0.0) {"))
    }

    @Test fun aQueuedEmergencySellWakesItsOwnRetryAndTakeAdvancesTheAttempt() {
        PendingSellQueue.clear()
        val woke = ArrayList<String>()
        PendingSellQueue.urgentRetry8387 = { mint, reason -> woke += "$mint|$reason" }
        try {
            PendingSellQueue.add("MintA", "A", "DEEP_CATASTROPHE_EXIT_-45PCT:PENDING_RETRY")
            PendingSellQueue.add("MintB", "B", "SMART_TRAIL_TAKE_PROFIT")
            assertEquals(1, woke.count { it.startsWith("MintA|") })
            assertEquals(0, woke.count { it.startsWith("MintB|") })
            val taken = PendingSellQueue.take8387("MintA")
            assertNotNull(taken); assertEquals(1, taken!!.retryCount)
            assertNull(PendingSellQueue.take8387("MintA"))
            PendingSellQueue.requeue(taken)
            assertEquals(2, PendingSellQueue.take8387("MintA")!!.retryCount) // requeue keeps the count; take adds one
        } finally { PendingSellQueue.urgentRetry8387 = null; PendingSellQueue.clear() }
        val s = src("engine/BotService.kt")
        assertTrue(s.contains("PendingSellQueue.urgentRetry8387 = { mint, reason -> urgentPendingSell8387(mint, reason) }"))
        val fn = s.substringAfter("private fun urgentPendingSell8387(").substringBefore("private fun ")
        assertTrue(fn.contains("ProtectiveExitClass7807.emergencyRetryDelayMs(1)"))
        assertTrue(fn.contains("EmergencyExitDispatcher7807.forReason(reason)"))
        assertTrue(fn.contains("executor.requestSell(ts, com.lifecyclebot.engine.sell.CloseLease.canonicalReason(sell.reason), wallet, wallet.getSolBalance())"))
    }
}
