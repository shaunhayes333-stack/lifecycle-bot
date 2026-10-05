package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.7807 — PROTECTIVE INVENTORY.
 *
 * Runtime 5.0.7805 showed "BOT-BUY COVERAGE §7718 unmanagedBotMints=1". A
 * landed LIVE bot buy must stay under protective management until the wallet
 * is proven empty, even when its canonical commit is rejected or its row is
 * quarantined for accounting reasons.
 */
class Aate7807ProtectiveInventoryTest {

    private val suffix = System.nanoTime().toString()
    private fun mint(tag: String) = "PROT7807${tag}Mint$suffix"

    private fun openLive(pid: String, mint: String, qty: Long) = CanonicalPositionAuthority6441.openPosition(
        idempotencyKey = "OPEN7807:$pid", positionId = pid, mint = mint,
        symbol = "P7807", lane = "SHITCOIN", runId = suffix,
        entryCostSol = 0.5, openedQtyRaw = BigInteger.valueOf(qty), tokenDecimals = 6,
        feesSol = 0.0, paperMode = false, modeOverride = "live",
        entryPriceUsd = 0.001, entryPriceSource = "TEST_7807",
    )

    @Test
    fun fundedLiveQuarantineIsProtectedButNotValuedOrOpen() {
        val m = mint("Q")
        val pid = "LIVE:$m:q7807"
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, openLive(pid, m, 5_000_000L))
        CanonicalPositionAuthority6441.quarantine(pid, "EXIT_ELIGIBILITY_6570:INVALID_ENTRY_BASIS")

        assertFalse(CanonicalPositionAuthority6441.openPositions().any { it.mint == m })
        assertFalse(CanonicalPositionAuthority6441.openPositionsForValuation().any { it.mint == m })
        assertTrue(CanonicalPositionAuthority6441.protectiveInventory7807("live").any { it.positionId == pid })
        assertTrue(m in CanonicalPositionAuthority6441.protectiveInventoryMints7807("live"))
        assertFalse(m in CanonicalPositionAuthority6441.protectiveInventoryMints7807("paper"))
        assertTrue(CanonicalPositionAuthority6441.hasFundedProtectiveQuarantine7807(m))
    }

    @Test
    fun walletProvenZeroQuarantineReasonsAreNotProtective() {
        val m = mint("Z")
        val pid = "LIVE:$m:z7807"
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, openLive(pid, m, 5_000_000L))
        CanonicalPositionAuthority6441.quarantine(pid, "LIVE_CLOSED_NO_SIG_FINALITY_7362")
        assertFalse(m in CanonicalPositionAuthority6441.protectiveInventoryMints7807())
    }

    @Test
    fun landedBuyWithRejectedCommitGetsProtectiveOwnershipThenPromotesOnProof() {
        val m = mint("R")
        val pid = "LIVE:$m:r7807"
        // Reservation (PENDING_ENTRY) that the pending-entry TTL quarantined: the
        // exact shape that made promotePendingToOpen return LIFECYCLE_FORBIDDEN.
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, openLive(pid, m, 0L))
        CanonicalPositionAuthority6441.quarantine(pid, "PENDING_ENTRY_TTL_CANCELLED_6461")
        assertFalse(m in CanonicalPositionAuthority6441.protectiveInventoryMints7807("live"))

        val outcome = CanonicalPositionAuthority6441.ensureProtectiveLiveOwnership7807(
            positionIdHint = pid, mint = m, symbol = "P7807", lane = "SHITCOIN",
            actualQtyRaw = BigInteger.valueOf(7_000_000L), tokenDecimals = 6,
            entryCostSol = 0.4, entryPriceUsd = 0.002, signature = "sig7807abcdef",
            reason = "CANONICAL_COMMIT_REJECTED_6486",
        )
        assertEquals(CanonicalPositionAuthority6441.ProtectiveOwnership7807.ATTACHED_TO_EXISTING_ROW, outcome)
        val row = CanonicalPositionAuthority6441.getPosition(pid)!!
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.QUARANTINED, row.lifecycle)
        assertEquals(BigInteger.valueOf(7_000_000L), row.remainingQtyRaw)
        // BASIS_UNCERTAIN: promotable by a later verified fill or wallet recovery.
        assertTrue(row.quarantineReason.startsWith("BASIS_UNCERTAIN_7807"))
        assertTrue(m in CanonicalPositionAuthority6441.protectiveInventoryMints7807("live"))
        assertFalse(CanonicalPositionAuthority6441.openPositionsForValuation().any { it.mint == m })
        // Idempotent.
        assertEquals(
            CanonicalPositionAuthority6441.ProtectiveOwnership7807.ALREADY_PROTECTED,
            CanonicalPositionAuthority6441.ensureProtectiveLiveOwnership7807(
                pid, m, "P7807", "SHITCOIN", BigInteger.valueOf(7_000_000L), 6, 0.4, 0.002, "sig7807abcdef", "again",
            ),
        )
    }

    @Test
    fun landedBuyWithNoCanonicalRowIsCreatedAsBasisUncertainAndPromotable() {
        val m = mint("N")
        val outcome = CanonicalPositionAuthority6441.ensureProtectiveLiveOwnership7807(
            positionIdHint = "LIVE:$m:n7807", mint = m, symbol = "P7807", lane = "",
            actualQtyRaw = BigInteger.valueOf(3_000_000L), tokenDecimals = 6,
            entryCostSol = 0.0, entryPriceUsd = 0.0, signature = "sig7807n",
            reason = "TX_TRUTH_DEFERRED:DIRECT_VERIFY",
        )
        assertEquals(CanonicalPositionAuthority6441.ProtectiveOwnership7807.CREATED, outcome)
        val row = CanonicalPositionAuthority6441.protectiveInventory7807("live").single { it.mint == m }
        assertTrue(row.quarantineReason.startsWith("BASIS_UNCERTAIN_7807"))

        // A later verified fill promotes the protective row instead of refusing it.
        val promoted = CanonicalPositionAuthority6441.promotePendingToOpen(
            positionId = row.positionId, actualQtyRaw = BigInteger.valueOf(3_000_000L),
            actualEntryCostSol = 0.3, actualFeesSol = 0.0, tokenDecimals = 6, paperMode = false,
            actualEntryPriceUsd = 0.004, actualEntryPriceSource = "TX_7807",
        )
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, promoted)
        val open = CanonicalPositionAuthority6441.getPosition(row.positionId)!!
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.OPEN, open.lifecycle)
        assertEquals("", open.quarantineReason)
        assertTrue(CanonicalPositionAuthority6441.openPositions().any { it.positionId == row.positionId })
    }

    @Test
    fun protectiveSellFillReducesThenClosesOnlyQuarantineOwnedMints() {
        val m = mint("S")
        CanonicalPositionAuthority6441.ensureProtectiveLiveOwnership7807(
            "", m, "P7807", "SHITCOIN", BigInteger.valueOf(10_000L), 6, 0.1, 0.01, "sig7807s", "test",
        )
        assertTrue(CanonicalPositionAuthority6441.applyProtectiveSellFill7807(m, BigInteger.valueOf(4_000L), false, "test"))
        val partial = CanonicalPositionAuthority6441.protectiveInventory7807("live").single { it.mint == m }
        assertEquals(BigInteger.valueOf(6_000L), partial.remainingQtyRaw)
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.QUARANTINED, partial.lifecycle)
        assertTrue(CanonicalPositionAuthority6441.applyProtectiveSellFill7807(m, BigInteger.ZERO, true, "walletZero"))
        assertFalse(m in CanonicalPositionAuthority6441.protectiveInventoryMints7807())

        // A mint with a normal OPEN row is owned by the canonical sell path.
        val m2 = mint("O")
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, openLive("LIVE:$m2:o7807", m2, 9_000L))
        assertFalse(CanonicalPositionAuthority6441.applyProtectiveSellFill7807(m2, BigInteger.valueOf(9_000L), true, "test"))
        assertTrue(CanonicalPositionAuthority6441.openPositions().any { it.mint == m2 })
    }

    // ── Wiring contracts ────────────────────────────────────────────────

    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    /**
     * Regression: no landed bot buy may finish its verification path without
     * protected inventory ownership. Every `return false` in
     * completeVerifiedLiveBuyWithProof before the canonical commit succeeds
     * must be preceded by protectLandedLiveBuy7807; returns after a successful
     * commit already have a canonical OPEN owner.
     */
    @Test
    fun everyPreCommitFailureInLiveBuyVerificationEstablishesProtectiveOwnership() {
        val exec = src("engine/Executor.kt")
        val start = exec.indexOf("fun completeVerifiedLiveBuyWithProof(")
        assertTrue(start > 0)
        val end = exec.indexOf("return true", start)
        val body = exec.substring(start, end)
        val commitOk = body.indexOf("if (!canonicalOpen6486)")
        assertTrue("canonical commit check present", commitOk > 0)
        val commitFailEnd = body.indexOf("return false", commitOk)
        var idx = body.indexOf("return false")
        var checked = 0
        while (idx >= 0) {
            if (idx <= commitFailEnd) {
                val window = body.substring(maxOf(0, idx - 400), idx)
                assertTrue("return false at body offset $idx lacks protectLandedLiveBuy7807", window.contains("protectLandedLiveBuy7807("))
                checked++
            }
            idx = body.indexOf("return false", idx + 1)
        }
        assertTrue(checked >= 4)
        assertTrue(exec.contains("private fun protectLandedLiveBuy7807("))
        assertTrue(exec.contains("ensureProtectiveLiveOwnership7807("))
    }

    @Test
    fun protectiveSurfaceIsWiredIntoRiskClockSupervisorCoverageAndReconciliation() {
        assertTrue(src("engine/truth/CanonicalRiskClock6454.kt").contains("protectiveInventory7807(activeMode7254)"))
        assertTrue(src("engine/HeldPositionSupervisor7246.kt").contains("protectiveInventory7807(mode)"))
        val gate = src("engine/sell/LiveBuyAdmissionGate.kt")
        assertTrue(gate.contains("val canonicalRows = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.protectiveInventory7807(\"live\")"))
        assertTrue(src("engine/sell/SellFinalizationCoordinator.kt").contains("applyProtectiveSellFill7807("))
        assertTrue(src("engine/sell/SellReconciler.kt").contains("applyProtectiveSellFill7807("))
        assertTrue(src("engine/WalletReconciler.kt").contains("retireProtectiveQuarantinesOnWalletZero7807(walletMints)"))
        assertTrue(src("engine/LiveCanonicalRecovery6686.kt").contains("protectUnadoptedBotHoldings7807(subset)"))
        // Exits never refuse a funded quarantine.
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("hasFundedProtectiveQuarantine7807(ts.mint))"))
    }
}
