package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.StopLatencyCore8153
import com.lifecyclebot.engine.sell.StopLatencyWiring8153
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441 as Cpa
import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import com.lifecyclebot.engine.truth.WalletHoldingFreshness8527
import com.lifecyclebot.engine.truth.WalletHoldingRead8527
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.math.BigInteger

/** Replay the 8526 whole-quantity re-adoption after a completed live sell. */
class Aate8527WalletRecoveryExitContinuityTest {
    @Before fun resetCanonicalState() { Cpa.resetForTest() }
    @After fun cleanupCanonicalState() { Cpa.resetForTest() }
    private val owner = "owner8527"
    private val amount = CanonicalTokenAmount(BigInteger.valueOf(48_870_058_147L), 6)
    private val mint = "F4Um7u8527"
    private fun proof(at: Long, quantity: CanonicalTokenAmount = amount) =
        WalletHoldingRead8527(mint, owner, quantity, at)

    @Test fun earlierPositiveReadDoesNotResurrectTheSoldWholeQuantity() {
        assertFalse(WalletHoldingFreshness8527.mayRecover(
            mint, amount, owner, proof(1_000L), 2_000L, false, 3_000L))
        assertFalse(WalletHoldingFreshness8527.mayRecover(
            mint, amount, owner, proof(2_000L), 2_000L, false, 3_000L))
        assertFalse(WalletHoldingFreshness8527.mayRecover(
            mint, amount, owner, null, 2_000L, false, 3_000L))
    }

    @Test fun newerOwnerReadPreservesRealResidualsAndNewBuys() {
        val residual = CanonicalTokenAmount(BigInteger.valueOf(12_345L), 6)
        assertTrue(WalletHoldingFreshness8527.mayRecover(
            mint, residual, owner, proof(2_001L, residual), 2_000L, false, 3_000L))
        assertTrue(WalletHoldingFreshness8527.mayRecover(
            mint, amount, owner, proof(2_001L), 2_000L, false, 3_000L))
        assertTrue(WalletHoldingFreshness8527.mayRecover(
            mint, amount, owner, null, 0L, false, 3_000L))
    }

    @Test fun oldReceiptWrongOwnerWrongQuantityAndFutureClockCannotBecomeNewEvidence() {
        val wrongAmount = CanonicalTokenAmount(BigInteger.valueOf(12_345L), 6)
        val wrongScale = CanonicalTokenAmount(amount.raw, 9)
        for (bad in listOf(
            proof(2_001L).copy(owner = "other"),
            proof(2_001L).copy(mint = "otherMint"),
            proof(2_001L, wrongAmount),
            proof(2_001L, wrongScale),
            proof(3_001L),
            proof(1L),
        )) assertFalse(WalletHoldingFreshness8527.mayRecover(
            mint, amount, owner, bad, 2_000L, false, 3_000L))
        assertFalse(WalletHoldingFreshness8527.mayRecover(
            mint, amount, owner, proof(2_001L), 2_000L, true, 3_000L))
    }

    @Test fun snapshotReadStartAndAmountRemainOneAtomicOwnerScopedObservation() {
        val snapshot = WalletAccountCache.Evidence(mapOf(mint to amount), 2_100L, false, owner)
        val found = WalletHoldingFreshness8527.fromSnapshot(snapshot, mint, owner, 3_000L, 45_000L)!!
        assertEquals(2_100L, found.observedAtMs)
        assertEquals(amount, found.amount)
        // A positive in a partial snapshot is still proof of this one holding.
        assertTrue(WalletHoldingFreshness8527.mayRecover(
            mint, amount, owner, found, 2_000L, false, 3_000L))
        val exitProof = StopLatencyCore8153.HeldProof8153(
            found.amount.raw, found.amount.decimals, "WALLET_SNAPSHOT", found.observedAtMs)
        assertTrue(StopLatencyCore8153.usable8153(exitProof, 3_000L, 2_000L, false))
        assertNull(WalletHoldingFreshness8527.fromSnapshot(snapshot, mint, "wrongOwner", 3_000L, 45_000L))
        assertNull(WalletHoldingFreshness8527.fromSnapshot(snapshot, mint, owner, 2_000L, 45_000L))
        assertNull(WalletHoldingFreshness8527.fromSnapshot(snapshot, mint, owner, 50_000L, 45_000L))
    }

    private fun protected(tag: String): Pair<String, String> {
        val m = "Recovery8527${tag}${System.nanoTime()}"
        val pid = "LIVE:$m:8527"
        assertEquals(Cpa.ProtectiveOwnership7807.CREATED,
            Cpa.ensureProtectiveLiveOwnership7807(pid, m, tag, "MOONSHOT",
                BigInteger.valueOf(10_000L), 6, 0.0337, 0.001, "buy$tag", "test8527"))
        return m to pid
    }

    @Test fun lateReceiptForClosedPositionCannotCreateASecondProtectiveOwner() {
        val (m, pid) = protected("late")
        assertTrue(Cpa.applyProtectiveSellFill7807(m, BigInteger.valueOf(10_000L), true,
            "finalized8527", confirmedRemainingRaw = BigInteger.ZERO))
        assertEquals(Cpa.Lifecycle.CLOSED, Cpa.getPosition(pid)!!.lifecycle)
        assertEquals(Cpa.ProtectiveOwnership7807.REFUSED,
            Cpa.ensureProtectiveLiveOwnership7807(pid, m, "late", "MOONSHOT",
                BigInteger.valueOf(10_000L), 6, 0.0337, 0.001, "buylate", "late_receipt8527"))
        assertFalse(Cpa.protectiveInventoryMints7807("live").contains(m))
    }

    @Test fun oldTrackerQuantityCannotHealAJustClosedPosition() {
        val (m, pid) = protected("heal")
        assertTrue(Cpa.applyProtectiveSellFill7807(m, BigInteger.valueOf(10_000L), true,
            "finalized8527", confirmedRemainingRaw = BigInteger.ZERO))
        assertEquals(0, LiveCanonicalRecovery6686.protectUnadoptedBotHoldings7807(
            mapOf(m to CanonicalTokenAmount(BigInteger.valueOf(10_000L), 6))))
        assertFalse(Cpa.protectiveInventoryMints7807("live").contains(m))
        assertEquals(BigInteger.ZERO, Cpa.getPosition(pid)!!.remainingQtyRaw)
    }

    @Test fun pricedRecoveryCannotCreateANewRowFromAReadThatPredatesTheCanonicalClose() {
        val (m, pid) = protected("priced")
        val beforeClose = System.currentTimeMillis() - 1_000L
        assertTrue(Cpa.applyProtectiveSellFill7807(m, BigInteger.valueOf(10_000L), true,
            "pricedClosed8527", confirmedRemainingRaw = BigInteger.ZERO))
        val stale = WalletHoldingRead8527(m, owner,
            CanonicalTokenAmount(BigInteger.valueOf(10_000L), 6), beforeClose)
        val replacement = "LIVE:${m}:replacement"
        assertEquals(Cpa.MutateResult.LIFECYCLE_FORBIDDEN,
            Cpa.openPosition("priced-recovery:$replacement", replacement, m, "priced", "MOONSHOT", "8527",
                0.0337, BigInteger.valueOf(10_000L), 6, 0.0, false,
                entryPriceUsd = 0.001, entryPriceSource = "WALLET_RECOVERY_TEST",
                walletRecovery8527 = true, holdingRead8527 = stale))
        assertNull(Cpa.getPosition(replacement))
        assertEquals(BigInteger.ZERO, Cpa.getPosition(pid)!!.remainingQtyRaw)
    }

    @Test fun neverSoldPricedRecoveryAndPendingProofRemainAvailable() {
        val m = "NeverSold8527${System.nanoTime()}"
        val pid = "LIVE:$m:8527"
        assertEquals(Cpa.MutateResult.APPLIED,
            Cpa.openPosition("open:$pid", pid, m, "new", "MOONSHOT", "8527",
                0.03, BigInteger.ZERO, 6, 0.0, false,
                entryPriceUsd = 0.001, entryPriceSource = "LIVE_FINALIZED_TEST"))
        assertEquals(Cpa.MutateResult.APPLIED,
            Cpa.promotePendingToOpen(pid, BigInteger.valueOf(10_000L), 0.03, 0.0, 6, false,
                actualEntryPriceUsd = 0.001, actualEntryPriceSource = "LIVE_FINALIZED_TEST",
                walletRecovery8527 = true))
        assertEquals(BigInteger.valueOf(10_000L), Cpa.getPosition(pid)!!.remainingQtyRaw)
        assertEquals(Cpa.Lifecycle.OPEN, Cpa.getPosition(pid)!!.lifecycle)
    }

    @Test fun partialProtectiveHoldingKeepsItsExitPrewarmUntilTheConfirmedRemainderIsZero() {
        val (m, pid) = protected("partial")
        assertFalse(Cpa.openPositions().any { it.positionId == pid })
        assertTrue(StopLatencyWiring8153.liveExitInventory8527().any { it.positionId == pid })
        assertTrue(Cpa.applyProtectiveSellFill7807(m, BigInteger.valueOf(4_000L), false,
            "partial8527", confirmedRemainingRaw = BigInteger.valueOf(6_000L)))
        val partial = StopLatencyWiring8153.liveExitInventory8527().single { it.positionId == pid }
        assertEquals(BigInteger.valueOf(6_000L), partial.remainingQtyRaw)
        // Duplicate partial finality is an absolute post-balance, not a second subtraction.
        assertTrue(Cpa.applyProtectiveSellFill7807(m, BigInteger.valueOf(4_000L), false,
            "partial8527_replay", confirmedRemainingRaw = BigInteger.valueOf(6_000L)))
        assertEquals(BigInteger.valueOf(6_000L), Cpa.getPosition(pid)!!.remainingQtyRaw)
        assertTrue(Cpa.applyProtectiveSellFill7807(m, BigInteger.valueOf(6_000L), true,
            "closed8527", confirmedRemainingRaw = BigInteger.ZERO))
        assertFalse(StopLatencyWiring8153.liveExitInventory8527().any { it.positionId == pid })
    }

    @Test fun recoveryAndExitWiringCarryTheObservationInsteadOfCallbackTime() {
        fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()
        val stop = src("engine/sell/StopLatencyWiring8153.kt")
        assertFalse(stop.contains("SNAPSHOT_READ_SPAN_MS_8153"))
        assertFalse(stop.contains("singleMintDataAt8153"))
        assertTrue(stop.contains("protectiveExitScope7809(\"live\")"))
        assertTrue(stop.substringAfter("private fun canonicalFallback8288").substringBefore("fun stopAbsent8288")
            .contains("liveExitInventory8527()"))
        assertTrue(stop.substringAfter("private fun tick8153()").contains("val open = liveExitInventory8527()"))
        val recovery = src("engine/LiveCanonicalRecovery6686.kt")
        assertEquals(4, recovery.split("walletRecovery8527 = true, holdingRead8527 = holdingProofs8527[mint]").size - 1)
        val canonical = src("engine/truth/CanonicalPositionAuthority6441.kt")
        for (method in listOf("openPosition(", "promotePendingToOpen(", "recoverQuarantinedLivePosition7454(")) {
            val body = canonical.substringAfter("fun " + method).substringBefore("\n    fun ")
            assertTrue(method, body.indexOf("lock.lock()") < body.indexOf("recoveryHoldingCurrent8527("))
            assertTrue(method, body.contains("walletRecovery8527"))
        }
        assertTrue(recovery.contains("recoverWalletSnapshot(BotService.status, subset, holdingProofs8527)"))
        assertTrue(src("engine/HostWalletTokenTracker.kt").contains("recoveryHoldingCurrent8527(mint, pair)"))
        assertTrue(src("engine/WalletReconciler.kt").contains("recoveryHoldingCurrent8527(mint, pair)"))
        assertTrue(src("engine/sell/LiveWalletReconciler.kt").contains("recoveryHoldingCurrent8527(mint, pair)"))
        val wallet = src("network/SolanaWallet.kt").substringAfter("fun getSingleMintBalanceRead8527(")
            .substringBefore("private fun knownMintsFallbackBounded7374")
        assertTrue(wallet.contains("mint, publicKeyB58, it, readStarted8527"))
        assertTrue(wallet.contains("generation8527 == com.lifecyclebot.engine.WalletAccountCache.readGeneration()"))
    }
}
