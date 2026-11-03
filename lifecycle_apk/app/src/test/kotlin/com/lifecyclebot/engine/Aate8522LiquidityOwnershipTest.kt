package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.FreezeAuthorityHardBlock7238
import com.lifecyclebot.engine.truth.OnChainHolderConcentration7379
import com.lifecyclebot.network.PumpCurveKeys7269
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Live safety regression: policy, pool and evidence time must survive the last spending boundary. */
class Aate8522LiquidityOwnershipTest {
    private val now = 20_000_000L
    private val mint = "Mint8522AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump"

    private fun pool(id: String, locked: Double, depth: Double = 10_000.0, type: String = "raydium") =
        LiquidityOwnership8522.Pool(id, type, locked, depth)

    private fun report(vararg pools: LiquidityOwnership8522.Pool, unlocked: Boolean = false): SafetyReport = SafetyReport(
        checkedAt = now - 1_000L,
        rugcheckScore = 99,
        rugcheckStatus = "CONFIRMED",
        evaluatedForPaper8522 = false,
        lpOwnership8522 = LiquidityOwnership8522.Evidence(mint, pools.toList(), unlocked, true),
    )

    private fun assess(r: SafetyReport, selected: String = "main", curve: Boolean = false, curveAddress: String = "curve") =
        LiquidityOwnership8522.assess(r, selected, curve, activeCurveAddress = curveAddress, nowMs = now, expectedMint = mint)

    @Test fun namedUnlockedRiskSurvivesSafeScoreAndLockedNumericPool() {
        val json = JSONObject("""{"mint":"$mint","score_normalised":1,"risks":[
            {"name":"Large Amount of LP Unlocked","level":"danger"}],"markets":[
            {"pubkey":"main","marketType":"raydium","lp":{"lpLockedPct":100,"baseUSD":10000,"quoteUSD":10000}}]}""")
        val r = report().copy(lpLockPct = 100.0, lpOwnership8522 = LiquidityOwnership8522.parse(json, mint))
        assertTrue(r.lpOwnership8522.explicitUnlocked)
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(r).status)
        assertEquals("LP_UNLOCKED_CONFIRMED_8522", assess(r).reason)
    }

    @Test fun lockedMinorPoolDoesNotEraseUnlockedPrimary() {
        val r = report(pool("main", 0.0, 80_000.0), pool("side", 100.0, 100.0))
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(r, "side").status)
    }

    @Test fun selectedPoolCannotBorrowAnotherPoolsLock() {
        val r = report(pool("main", 100.0), pool("side", 10.0, 100.0))
        assertEquals(LiquidityOwnership8522.Status.PROVEN, assess(r).status)
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(r, "side").status)
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(r, "unobserved").status)
    }

    @Test fun existingSeventyPercentPolicyIsEnforcedWithoutChangingItsBoundary() {
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(report(pool("main", 69.999))).status)
        assertEquals(LiquidityOwnership8522.Status.PROVEN, assess(report(pool("main", 70.0))).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(report(pool("main", -1.0))).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(report(pool("main", Double.NaN))).status)
    }

    @Test fun paperPolicyDoesNotHideItsObservedUnlockedLiquidityAtLiveSpend() {
        val r = report().copy(tier = SafetyTier.CAUTION, evaluatedForPaper8522 = true, lpLockPct = 0.0,
            softPenalties = listOf("LP only 0% locked (paper soft, learning data)" to 40))
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(r).status)
    }

    @Test fun legacyPositiveNumberWithoutPoolIdentityCannotProveSelectedLiquidity() {
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(report().copy(lpLockPct = 100.0)).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(report(pool("", 100.0)), "").status)
    }

    @Test fun unknownOwnershipIsRetryableAndCannotBeReplacedByARoutableQuote() {
        val pending = listOf("RUGCHECK_PENDING_OR_UNKNOWN:PENDING_REVIEW", "LP_OWNERSHIP_PROOF_PENDING_8522:pool=main")
        val kept = PreTradeHardGate.chainProvenRugcheckSubstitute8471(pending, true, 12.0, true)
        assertEquals(pending, kept)
        assertEquals(pending, PreTradeHardGate.criticalProofPending8373(kept))
        val withOtherProofs = PreTradeHardGate.chainProvenRugcheckSubstitute8471(pending, true, 12.0, true, true)
        assertEquals(listOf(pending[1]), PreTradeHardGate.criticalProofPending8373(withOtherProofs))
    }

    @Test fun completeOwnershipAllowsTheExistingChainSubstitute() {
        val pending = listOf("RUGCHECK_PENDING_OR_UNKNOWN:PENDING_REVIEW")
        val changed = PreTradeHardGate.chainProvenRugcheckSubstitute8471(pending, true, 12.0, true, true)
        assertEquals("RUGCHECK_NO_FINDINGS_CHAIN_PROVEN_8471:PENDING_REVIEW", changed.single())
        assertTrue(PreTradeHardGate.criticalProofPending8373(changed).isEmpty())
        assertEquals(pending, PreTradeHardGate.chainProvenRugcheckSubstitute8471(pending, true, Double.NaN, true, true))
    }

    @Test fun invalidHolderReadingCannotEraseAnotherProvidersAdverseFinding() {
        assertEquals(85.0, PreTradeHardGate.topHolderPct8522(Double.NaN, 85.0), 0.0)
        assertEquals(85.0, PreTradeHardGate.topHolderPct8522(85.0, Double.NaN), 0.0)
        assertEquals(-1.0, PreTradeHardGate.topHolderPct8522(Double.NaN, -1.0), 0.0)
        assertEquals(-1.0, PreTradeHardGate.topHolderPct8522(Double.POSITIVE_INFINITY, Double.NaN), 0.0)
        assertEquals(22.0, PreTradeHardGate.topHolderPct8522(12.0, 22.0), 0.0)
        assertEquals(100.0, PreTradeHardGate.topHolderPct8522(120.0, 22.0), 0.0)
    }

    @Test fun oldSafeStateCannotReplaceCurrentUnknownOrStaleHolderProof() {
        val missing = report(pool("main", 100.0)).copy(topHolderPct = -1.0)
        val oldSafe = PreTradeHardGate.holderProof8522(3.0, missing, now)
        assertNull(oldSafe.freshPct)
        assertEquals(3.0, oldSafe.adversePct, 0.0)
        val pending = listOf("RUGCHECK_PENDING_OR_UNKNOWN:PENDING_REVIEW", "HOLDER_DATA_UNKNOWN")
        assertEquals(pending, PreTradeHardGate.chainProvenRugcheckSubstitute8471(
            pending, true, oldSafe.freshPct ?: -1.0, true, true))
        val stale = missing.copy(topHolderPct = 3.0, topHolderObservedAtMs8522 = now - 120_001L)
        assertNull(PreTradeHardGate.holderProof8522(3.0, stale, now).freshPct)
        assertFalse(TokenSafetyChecker.cacheUsable8522(stale, false, 90_000L, now))
    }

    @Test fun currentHolderObservationPreservesAdverseConcentrationAndItsSourceAge() {
        val current = report(pool("main", 100.0)).copy(topHolderPct = 85.0,
            topHolderObservedAtMs8522 = now - 20_000L, topHolderSource8522 = "RPC_TOP10")
        val result = PreTradeHardGate.holderProof8522(3.0, current, now)
        assertEquals(85.0, result.adversePct, 0.0)
        assertEquals(85.0, result.freshPct!!, 0.0)
        val updated = FreezeAuthorityHardBlock7238.withAuthorityProof8522(current, true, true)
        assertEquals(current.topHolderObservedAtMs8522, updated.topHolderObservedAtMs8522)
        assertNull(PreTradeHardGate.holderProof8522(3.0, current, now + 100_001L).freshPct)
        assertNull(PreTradeHardGate.holderProof8522(3.0, current.copy(topHolderObservedAtMs8522 = now + 1L), now).freshPct)
        assertTrue(TokenSafetyChecker.cacheUsable8522(current, false, 90_000L, now))
    }

    @Test fun holderCacheSupportsInformationAgeWithoutCallingItFreshLiveProof() {
        val observation = OnChainHolderConcentration7379.Observation8522(12.0, now - 300_000L)
        assertTrue(OnChainHolderConcentration7379.usable8522(observation, now, Long.MAX_VALUE))
        assertFalse(OnChainHolderConcentration7379.usable8522(observation, now, 120_000L))
        assertFalse(OnChainHolderConcentration7379.usable8522(observation.copy(observedAtMs = now + 1L), now, 120_000L))
        assertFalse(OnChainHolderConcentration7379.usable8522(observation.copy(pct = Double.NaN), now, Long.MAX_VALUE))
        val report = report(pool("main", 100.0)).copy(topHolderPct = observation.pct,
            topHolderObservedAtMs8522 = observation.observedAtMs, evaluatedForPaper8522 = true)
        assertTrue(TokenSafetyChecker.cacheUsable8522(report, true, 90_000L, now))
        assertFalse(TokenSafetyChecker.cacheUsable8522(report.copy(evaluatedForPaper8522 = false), false, 90_000L, now))
    }

    @Test fun whitelistedPolicyCarriesRealHolderProofAndPreservesItsSourceClock() {
        val major = report().copy(rugcheckStatus = "WHITELISTED", evaluatedPool8522 = "major-pool",
            liquidityObservedUsd8522 = 50_000.0, liquidityObservedAtMs8522 = now - 5_000L,
            liquidityObservedPool8522 = "major-pool")
        val observation = OnChainHolderConcentration7379.Observation8522(18.0, now - 20_000L)
        val hydrated = TokenSafetyChecker.withHolderObservation8522(major, observation, now, 120_000L)
        assertEquals(18.0, hydrated.topHolderPct, 0.0)
        assertEquals(observation.observedAtMs, hydrated.topHolderObservedAtMs8522)
        assertEquals("RPC_TOP10", hydrated.topHolderSource8522)
        assertEquals(major.checkedAt, hydrated.checkedAt)
        assertEquals(major.evaluatedForPaper8522, hydrated.evaluatedForPaper8522)
        assertEquals(major.evaluatedPool8522, hydrated.evaluatedPool8522)
        assertEquals(major.liquidityObservedAtMs8522, hydrated.liquidityObservedAtMs8522)
        assertEquals(18.0, PreTradeHardGate.holderProof8522(null, hydrated, now).freshPct!!, 0.0)
        val concentrated = TokenSafetyChecker.withHolderObservation8522(major, observation.copy(pct = 85.0), now, 120_000L)
        assertEquals(85.0, PreTradeHardGate.holderProof8522(null, concentrated, now).adversePct, 0.0)
    }

    @Test fun whitelistedMissingOrExpiredReadDoesNotInventOrRetainSafeHoldings() {
        val previous = report().copy(rugcheckStatus = "WHITELISTED", topHolderPct = 3.0,
            topHolderObservedAtMs8522 = now - 10_000L, topHolderSource8522 = "RPC_TOP10")
        for (observation in listOf(null,
                OnChainHolderConcentration7379.Observation8522(3.0, now - 120_001L),
                OnChainHolderConcentration7379.Observation8522(3.0, now + 1L),
                OnChainHolderConcentration7379.Observation8522(Double.NaN, now))) {
            val missing = TokenSafetyChecker.withHolderObservation8522(previous, observation, now, 120_000L)
            assertEquals(-1.0, missing.topHolderPct, 0.0)
            assertEquals(0L, missing.topHolderObservedAtMs8522)
            assertEquals("", missing.topHolderSource8522)
            assertNull(PreTradeHardGate.holderProof8522(3.0, missing, now).freshPct)
            assertEquals(previous.checkedAt, missing.checkedAt)
        }
    }

    @Test fun explicitRefreshSkipsEvenSameMillisecondUnknownCacheWithoutForcingNormalScans() {
        val unknown = report().copy(checkedAt = now, rugcheckStatus = "PENDING_REVIEW", evaluatedPool8522 = "main")
        assertEquals(Long.MAX_VALUE, SafetyProofPriority8310.maxCacheAge8310(false))
        assertEquals(0L, SafetyProofPriority8310.maxCacheAge8310(true))
        assertEquals(0L, SafetyProofPriority8310.maxCacheAge8310(false, explicitRefresh = true))
        assertTrue(TokenSafetyChecker.cacheUsable8522(unknown, false,
            SafetyProofPriority8310.maxCacheAge8310(false), now, "main"))
        assertFalse(TokenSafetyChecker.cacheUsable8522(unknown, false,
            SafetyProofPriority8310.maxCacheAge8310(true), now, "main"))
        assertFalse(TokenSafetyChecker.cacheUsable8522(unknown, false,
            SafetyProofPriority8310.maxCacheAge8310(false, explicitRefresh = true), now, "main"))
    }

    @Test fun forcedFollowerSharesOnlyNewMatchingEvidenceProducedAfterItsRequest() {
        val old = report().copy(checkedAt = now, evaluatedPool8522 = "main", rugcheckStatus = "PENDING_REVIEW")
        assertFalse(TokenSafetyChecker.completedRefreshUsable8522(old, old, now, false, "main", now + 1L))
        val fresh = old.copy(checkedAt = now + 1L)
        assertTrue(TokenSafetyChecker.completedRefreshUsable8522(fresh, old, now, false, "main", now + 10L))
        assertFalse(TokenSafetyChecker.completedRefreshUsable8522(fresh.copy(checkedAt = now - 1L), old, now, false, "main", now + 10L))
        assertFalse(TokenSafetyChecker.completedRefreshUsable8522(fresh.copy(evaluatedForPaper8522 = true), old, now, false, "main", now + 10L))
        assertFalse(TokenSafetyChecker.completedRefreshUsable8522(fresh, old, now, false, "another-pool", now + 10L))
        assertFalse(TokenSafetyChecker.completedRefreshUsable8522(fresh.copy(checkedAt = now + 20L), old, now, false, "main", now + 10L))
    }

    @Test fun activeCurveRequiresActualProofAndItsExactAddress() {
        val r = report(pool("curve", 0.0, 500.0, "pump_fun"), unlocked = true)
        assertEquals(LiquidityOwnership8522.Status.PROVEN, assess(r, "curve", curve = true).status)
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(r, "curve", curve = false).status)
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(r, "some-amm", curve = true).status)
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(r, "curve", curve = true, curveAddress = "different").status)
    }

    @Test fun freshUnindexedCurveCanUseItsAccountProofButOrdinaryPoolCannot() {
        val r = report().copy(rugcheckStatus = "PENDING_REVIEW", lpOwnership8522 = LiquidityOwnership8522.Evidence())
        assertEquals(LiquidityOwnership8522.Status.PROVEN, assess(r, "curve", curve = true).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(r, "ordinary-amm", curve = true).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(r, "curve", curve = false).status)
    }

    @Test fun activeCurveCannotExcuseAnUnprotectedOrdinaryPool() {
        val r = report(pool("main", 0.0), pool("curve", 0.0, 500.0, "pump_fun"))
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(r, "main", curve = true).status)
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(r, "curve", curve = true).status)
    }

    @Test fun providerCurveLabelAloneDoesNotAuthorizeSpending() {
        val r = report(pool("curve", 0.0, 500.0, "pump_fun"))
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(r, "curve", curve = false).status)
    }

    @Test fun staleFutureOrIncompleteReportsCannotCertifyAHealthyPool() {
        val r = report(pool("main", 100.0))
        for (at in listOf(0L, now - 120_001L, now + 1L)) {
            assertEquals(LiquidityOwnership8522.Status.PENDING, assess(r.copy(checkedAt = at)).status)
        }
        assertEquals(LiquidityOwnership8522.Status.PENDING,
            assess(r.copy(lpOwnership8522 = r.lpOwnership8522.copy(riskInventoryComplete = false))).status)
    }

    @Test fun parserRejectsOtherMintAndDoesNotInventLockPercentages() {
        val wrong = JSONObject("""{"mint":"other","risks":[{"name":"LP unlocked"}],"markets":[]}""")
        assertEquals(LiquidityOwnership8522.Evidence(), LiquidityOwnership8522.parse(wrong, mint))
        val missing = JSONObject("""{"mint":"$mint","risks":[],"markets":[{"pubkey":"main","marketType":"raydium","lp":{"baseUSD":10000}}]}""")
        val evidence = LiquidityOwnership8522.parse(missing, mint)
        assertEquals(-1.0, evidence.pools.single().lockedPct, 0.0)
        assertEquals(-1.0, evidence.pools.single().depthUsd, 0.0)
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(report().copy(lpOwnership8522 = evidence)).status)
    }

    @Test fun poolDepthDistinguishesBothObservedZeroBalancesFromMissingData() {
        val json = JSONObject("""{"mint":"$mint","risks":[],"markets":[
            {"pubkey":"empty","lp":{"lpLockedPct":100,"baseUSD":0,"quoteUSD":0}},
            {"pubkey":"missing","lp":{"lpLockedPct":100}},
            {"pubkey":"partial","lp":{"lpLockedPct":100,"baseUSD":20000}},
            {"pubkey":"deep","lp":{"lpLockedPct":100,"baseUSD":10000,"quoteUSD":15000}}
        ]}""")
        val pools = LiquidityOwnership8522.parse(json, mint).pools.associateBy { it.address }
        assertEquals(0.0, pools.getValue("empty").depthUsd, 0.0)
        assertEquals(-1.0, pools.getValue("missing").depthUsd, 0.0)
        assertEquals(-1.0, pools.getValue("partial").depthUsd, 0.0)
        assertEquals(25_000.0, pools.getValue("deep").depthUsd, 0.0)
    }

    @Test fun reportCannotContributeAnotherMintsProofOrAdverseFindings() {
        val r = report(pool("main", 100.0))
        assertEquals(LiquidityOwnership8522.Status.PENDING,
            assess(r.copy(lpOwnership8522 = r.lpOwnership8522.copy(mint = "other"))).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING,
            assess(r.copy(lpOwnership8522 = r.lpOwnership8522.copy(mint = "other", explicitUnlocked = true))).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING,
            assess(r.copy(lpOwnership8522 = r.lpOwnership8522.copy(mint = ""))).status)
    }

    @Test fun cacheIdentityIncludesModePoolAndRequestedFreshness() {
        val r = report(pool("main", 100.0)).copy(evaluatedPool8522 = "main")
        assertTrue(TokenSafetyChecker.cacheUsable8522(r, false, 90_000L, now, "main"))
        assertFalse(TokenSafetyChecker.cacheUsable8522(r, true, 90_000L, now, "main"))
        assertFalse(TokenSafetyChecker.cacheUsable8522(r.copy(evaluatedForPaper8522 = true), false, 90_000L, now, "main"))
        assertFalse(TokenSafetyChecker.cacheUsable8522(r.copy(evaluatedForPaper8522 = null), false, 90_000L, now, "main"))
        assertFalse(TokenSafetyChecker.cacheUsable8522(r, false, 90_000L, now, "other"))
        assertFalse(TokenSafetyChecker.cacheUsable8522(r.copy(checkedAt = now - 90_001L), false, 90_000L, now, "main"))
        assertFalse(TokenSafetyChecker.cacheUsable8522(r.copy(checkedAt = now + 1L), false, Long.MAX_VALUE, now, "main"))
    }

    @Test fun authorityRefreshPreservesRiskEvidenceTimeAndPool() {
        val old = report(pool("main", 0.0), unlocked = true).copy(checkedAt = now - 300_000L,
            mintAuthorityDisabled = null, freezeAuthorityDisabled = null)
        val updated = FreezeAuthorityHardBlock7238.withAuthorityProof8522(old, true, true)
        assertEquals(old.checkedAt, updated.checkedAt)
        assertEquals(old.lpOwnership8522, updated.lpOwnership8522)
        assertEquals(true, updated.freezeAuthorityDisabled)
        assertEquals(true, updated.mintAuthorityDisabled)
        assertFalse(TokenSafetyChecker.cacheUsable8522(updated, false, 90_000L, now))
        val activeMint = FreezeAuthorityHardBlock7238.withAuthorityProof8522(updated, false, true)
        assertEquals(false, activeMint.mintAuthorityDisabled)
        assertEquals(old.checkedAt, activeMint.checkedAt)
    }

    @Test fun reusedRiskReportConsumesNewZeroLiquidityWithoutRefreshingRiskAge() {
        val old = report(pool("main", 100.0)).copy(liquidityObservedUsd8522 = 20_000.0,
            liquidityObservedAtMs8522 = now - 30_000L, liquidityObservedPool8522 = "main", liqConfirmed = true)
        val updated = TokenSafetyChecker.withLiquidityObservation8522(old, 0.0, now - 100L, "main", now)
        assertEquals(0.0, updated.liquidityObservedUsd8522, 0.0)
        assertEquals(now - 100L, updated.liquidityObservedAtMs8522)
        assertFalse(updated.liqConfirmed)
        assertEquals(old.checkedAt, updated.checkedAt)
        assertEquals(old.lpOwnership8522, updated.lpOwnership8522)
        assertEquals(updated, TokenSafetyChecker.withLiquidityObservation8522(updated, 30_000.0, now - 500L, "main", now))
        assertEquals(updated, TokenSafetyChecker.withLiquidityObservation8522(updated, 30_000.0, now - 120_001L, "main", now))
        assertEquals(updated, TokenSafetyChecker.withLiquidityObservation8522(updated, 30_000.0, now + 1L, "main", now))
        assertEquals(updated, TokenSafetyChecker.withLiquidityObservation8522(updated, Double.NaN, now, "main", now))
    }

    @Test fun exactPoolDepthPreservesNewZeroAndDoesNotBorrowAnotherPoolsLiquidity() {
        val r = report(pool("main", 100.0, 20_000.0), pool("side", 100.0, 100.0)).copy(
            liquidityObservedUsd8522 = 0.0, liquidityObservedAtMs8522 = now - 100L, liquidityObservedPool8522 = "main")
        fun depth(report: SafetyReport, pool: String) = LiquidityOwnership8522.observedPoolDepth8522(report, pool, now, mint)
        assertEquals(0.0, depth(r, "main")!!, 0.0)
        assertEquals(100.0, depth(r, "side")!!, 0.0)
        assertNull(depth(r, "not-seen"))
        assertEquals(0.0, depth(r, "")!!, 0.0)
        assertEquals(20_000.0, depth(r.copy(liquidityObservedAtMs8522 = now - 120_001L), "main")!!, 0.0)
        assertNull(depth(r.copy(checkedAt = now - 120_001L, liquidityObservedAtMs8522 = now - 120_001L), "main"))
        assertNull(depth(r.copy(lpOwnership8522 = r.lpOwnership8522.copy(mint = "other")), "main"))
        assertNull(depth(report(pool("main", 100.0, -1.0)), "main"))
    }

    @Test fun staticLpMirrorClearsUnknownAndExpiresOldProof() {
        val id = "StaticLp8522"
        TokenSafetyChecker.recordLpLockPct(id, 100.0)
        assertEquals(100.0, TokenSafetyChecker.peekLpLockPct(id), 0.0)
        TokenSafetyChecker.recordLpLockPct(id, -1.0)
        assertEquals(-1.0, TokenSafetyChecker.peekLpLockPct(id), 0.0)
        TokenSafetyChecker.recordLpLockPct(id, 100.0, System.currentTimeMillis() - 120_001L)
        assertEquals(-1.0, TokenSafetyChecker.peekLpLockPct(id), 0.0)
    }

    @Test fun curveExceptionNeedsRecentAccountReservesAndCannotSurviveGraduation() {
        val id = "CurveProof8522AAAAAAAAAAAAAAAAAAAAAAAAAApump"
        CurveReserves7951.noteFrame7951(id, 40.0, 1_000_000.0, now - 5_000L)
        assertFalse(LiquidityOwnership8522.activeCurveVerified(id, now))
        CurveReserves7951.noteAccount7951(id, 40.0, 1_000_000.0, 10.0, now - 4_000L)
        assertTrue(LiquidityOwnership8522.activeCurveVerified(id, now))
        assertFalse(LiquidityOwnership8522.activeCurveVerified(id, now + 30_000L))
        PumpCurveKeys7269.markGraduated7392(id)
        assertFalse(LiquidityOwnership8522.activeCurveVerified(id, now))
    }
}
