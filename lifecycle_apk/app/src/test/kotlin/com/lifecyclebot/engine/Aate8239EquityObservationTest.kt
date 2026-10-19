package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CapitalDrawdown7948
import com.lifecyclebot.engine.truth.LiveEquityObservation8239
import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import com.lifecyclebot.engine.truth.PeakLedger8188
import com.lifecyclebot.engine.truth.WalletSolObservation8239
import org.junit.Assert.*
import org.junit.Test

class Aate8239EquityObservationTest {
    private val now = 100_000L
    private val wallet = WalletSolObservation8239("owner", 300_000_000L, 812L, now - 1_000, now - 900, 7)
    private val position = LiveEquityObservation8239.PositionValue8239("position", 0.03, 0.027, now - 200, true, true)

    private fun observation(
        cash: WalletSolObservation8239? = wallet,
        tokenAt: Long = now - 500,
        complete: Boolean = true,
        positions: List<LiveEquityObservation8239.PositionValue8239> = listOf(position),
        mutation: Long = now - 2_000,
        revisionAfter: Long = 12,
        journalAfter: Long = 20,
        generationAfter: Long = 7,
        pending: Boolean = false,
    ) = LiveEquityObservation8239.assess8239(now, "owner", cash, tokenAt, complete, positions, mutation,
        12, revisionAfter, 20, journalAfter, 7, generationAfter, pending)

    @Test fun repeatedCacheReadsDoNotRenewCashEvidence() {
        assertTrue(wallet.usable8239(now, 7))
        assertFalse(wallet.usable8239(now + 30_000, 7))
        assertFalse(wallet.usable8239(now, 8))
        assertFalse(wallet.copy(startedAtMs = now + 1, observedAtMs = now + 1).usable8239(now, 7))
        assertFalse(wallet.copy(observedAtMs = now + 1).usable8239(now, 7))
        assertEquals(now - 900, wallet.observedAtMs)
        assertEquals(812L, wallet.contextSlot)
        assertEquals("CASH_UNOBSERVED_OR_STALE", observation(cash = wallet.copy(owner = "another-owner")).reason)
    }

    @Test fun saleProceedsCannotCombineWithThePreSaleInventory() {
        assertEquals("WALLET_PRECEDES_SETTLEMENT", observation(mutation = now - 800).reason)
        assertEquals("LEDGER_CHANGED_DURING_CAPTURE", observation(revisionAfter = 13).reason)
        assertEquals("LEDGER_CHANGED_DURING_CAPTURE", observation(journalAfter = 21).reason)
        assertEquals("TRANSACTION_UNRESOLVED", observation(pending = true).reason)
        assertEquals("WALLET_QUANTITY_MISMATCH", observation(positions = listOf(position.copy(quantityMatches = false))).reason)
    }

    @Test fun anEmptyWalletNeedsSuccessfulCompleteEnumeration() {
        assertTrue(observation(positions = emptyList()).coherent)
        assertFalse(observation(positions = emptyList(), complete = false).coherent)
        assertFalse(observation(positions = emptyList(), tokenAt = now - 30_001).coherent)
        assertEquals(0.3, observation(positions = emptyList()).riskSol, 1e-12)
    }

    @Test fun unrealisedGainsDoNotEnlargeTheLossBudgetAndMarksKeepTheirAge() {
        val loss = observation()
        assertTrue(loss.coherent)
        assertEquals(0.327, loss.riskSol, 1e-12)
        val winner = observation(positions = listOf(position.copy(valueSol = 0.06)))
        assertEquals(0.36, winner.markedSol, 1e-12)
        assertEquals(0.33, winner.riskSol, 1e-12)
        assertFalse(observation(positions = listOf(position.copy(observedAtMs = now - 45_001))).coherent)
        assertFalse(observation(positions = listOf(position.copy(valueSol = Double.NaN))).coherent)
    }

    @Test fun aMissingPositionMarkCannotIncreaseTheBudgetOrPermanentlyBlockVerifiedCash() {
        val unvalued = observation(positions = listOf(position.copy(priceTrusted = false, valueSol = 100.0)))
        assertFalse(unvalued.coherent)
        assertTrue(unvalued.cashProven)
        assertEquals(0.3, unvalued.sizingEquitySol8239(), 1e-12)
        assertEquals(0.3, observation(complete = false).sizingEquitySol8239(), 1e-12)
        assertTrue(observation(cash = null).sizingEquitySol8239().isNaN())
        assertFalse(observation(mutation = now - 800).cashProven)
        assertNull(PeakLedger8188.eventFromObservation8239(0.3, now, "NEW_HIGH", unvalued))
    }

    @Test fun tokenAccountObservationsCannotCrossWalletOwners() {
        WalletAccountCache.bustNow()
        try {
            val generation = WalletAccountCache.readGeneration8237()
            assertTrue(WalletAccountCache.put(emptyMap(), complete = true, source = "RPC",
                observedAtMs = now, readGeneration = generation, owner = "owner-a"))
            assertNotNull(WalletAccountCache.snapshotWithProof8237(nowMs = now, expectedOwner = "owner-a"))
            assertNull(WalletAccountCache.snapshotWithProof8237(nowMs = now, expectedOwner = "owner-b"))
            WalletAccountCache.bustNow()
            assertFalse(WalletAccountCache.put(emptyMap(), complete = true, source = "OLD_RPC",
                observedAtMs = now + 1, readGeneration = generation, owner = "owner-a"))
        } finally { WalletAccountCache.bustNow() }
    }

    @Test fun theImmutableMarkRequiresItsOwnPurposeIdentityAndObservationTime() {
        val mark = com.lifecyclebot.engine.truth.CanonicalPriceMark6522("mint", "pool", "mint", "SOL",
            "LOCKED_VENUE_POOL", now - 1, com.lifecyclebot.engine.truth.PriceUsd(java.math.BigDecimal("0.001")),
            null, purpose = com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570.EXIT_ECONOMIC, tokenDecimals7424 = 6)
        assertTrue(LiveEquityObservation8239.markUsable8239("mint", 6, mark, now))
        assertFalse(LiveEquityObservation8239.markUsable8239("other-mint", 6, mark, now))
        assertFalse(LiveEquityObservation8239.markUsable8239("mint", 9, mark, now))
        assertFalse(LiveEquityObservation8239.markUsable8239("mint", 6, mark.copy(timestampMs = now + 1), now))
        assertFalse(LiveEquityObservation8239.markUsable8239("mint", 6, mark.copy(timestampMs = now - 45_001), now))
        assertFalse(LiveEquityObservation8239.markUsable8239("mint", 6, mark.copy(
            purpose = com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570.OBSERVATION_SCORING), now))
    }

    @Test fun peakEventCopiesOnlyTheObservationThatEstablishedItsValue() {
        val observed = observation()
        val event = PeakLedger8188.eventFromObservation8239(observed.riskSol, now,
            PeakLedger8188.ORIGIN_NEW_HIGH_8188, observed)!!
        assertEquals(0.3, event.liquidSol, 1e-12)
        assertEquals(0.03, event.openPrincipalSol, 1e-12)
        assertEquals(-0.003, event.unrealisedSol, 1e-12)
        assertTrue(event.markProvenance.contains("walletAt=99100"))
        assertNull(PeakLedger8188.eventFromObservation8239(0.5, now, "NEW_HIGH", observed))
        assertNull(PeakLedger8188.eventFromObservation8239(observed.riskSol, now, "NEW_HIGH", observation(pending = true)))
    }

    @Test fun cachedCashCannotConfirmANewPeakTwice() {
        CapitalDrawdown7948.reanchorExplicitRun8093(0.2, now - 10_000)
        try {
            val first = observation(positions = emptyList())
            CapitalDrawdown7948.observeCaptured8239(first, now)
            CapitalDrawdown7948.observeCaptured8239(first.copy(atMs = now + 1), now + 1)
            assertEquals(0.2, CapitalDrawdown7948.currentPeak7948().peakSol, 1e-12)
            val second = first.copy(atMs = now + 2, walletObservedAtMs = now + 1)
            CapitalDrawdown7948.observeCaptured8239(second, now + 2)
            assertEquals(0.3, CapitalDrawdown7948.currentPeak7948().peakSol, 1e-12)
        } finally {
            CapitalDrawdown7948.reanchorExplicitRun8093(0.2, now)
            PeakLedger8188.resetForTests8188()
        }
    }

    @Test fun unresolvedEquityCannotAuthorizeAFreshBuy() {
        val input = LiveRiskPolicy7807.Inputs(lane = "QUALITY", equitySol = 0.4, upstreamSol = 0.03,
            execMinSol = 0.02, planStopPct = 10.0, planFirstTargetPct = 30.0, planTargetPct = 60.0,
            solUsd = 100.0, liquidityUsd = 100_000.0, liveCloses = 30, liveMeanNetPct = 8.0,
            liveTotalNetSol = 0.04, liveWrPct = 60.0, drawdownFrac = 0.0, laneDailyLossSol = 0.0,
            governorLossMult = 1.0, partialProviderEvidence = false, oracleUnproven = false, laneOpenLive = 0,
            equityObservationRefusal8239 = "WALLET_PRECEDES_SETTLEMENT")
        val decision = LiveRiskPolicy7807.decide(input)
        assertFalse(decision.open)
        assertEquals(0.0, decision.sizeSol, 0.0)
        assertEquals("EQUITY_OBSERVATION_DEFER_8239_WALLET_PRECEDES_SETTLEMENT", decision.reason)
    }
}
