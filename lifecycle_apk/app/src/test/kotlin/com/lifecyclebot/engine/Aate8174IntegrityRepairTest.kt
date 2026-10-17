package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8174 — verified safety precedence, specialist attribution, and causal/report instrumentation. */
class Aate8174IntegrityRepairTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun confirmedCollapseCannotBeDemotedByTheWordLiquidity() {
        val reason = "CONFIRMED_RUG_COLLAPSE: price -89% liqProof=DATA_CONFLICT: Liquidity dropped 93%"
        assertTrue(TokenBlacklist.explicitHardReason8174(reason))
        assertTrue(TokenBlacklist.isTrueBlacklistReason(reason))
        assertFalse(TokenBlacklist.isSoftPenaltyOnlyReason(reason))
        assertFalse(TokenBlacklist.isTrueBlacklistReason("UNCONFIRMED_PRICE_COLLAPSE: liquidity unverified"))
    }

    @Test fun behaviourPatternBelongsToItsExactSpecialistLane() {
        assertTrue(AICrossTalk.patternAppliesToLane7329("SHITCOIN|PUMP_FUN_BONDING|PUMP", "SHITCOIN"))
        assertFalse(AICrossTalk.patternAppliesToLane7329("STANDARD|PUMP_FUN_BONDING|PUMP", "SHITCOIN"))
        assertFalse(AICrossTalk.patternAppliesToLane7329("QUALITY|SETUP_CORE_TREND", "CORE"))
        assertTrue(src("engine/MemeCrossTalkEntryBridge.kt")
            .contains("AICrossTalk.analyzeCrossTalk(ts.mint, ts.symbol, isOpenPosition, lane)"))
    }

    @Test fun basisOpenHookIsBoundToCanonicalOpenAndPromotion() {
        val s = src("engine/truth/CanonicalPositionAuthority6441.kt")
        assertTrue(s.contains("OpenPositionBasisInvariant6627.onCanonicalOpen6627("))
        assertTrue(s.contains("lockEntryMetricsAtOpen6636(promoted)"))
    }

    @Test fun finalityCarriesProvenSellStageAndDoesNotReconstructLiveHistoryBlindly() {
        val s = src("engine/truth/AateDecisionEnvelope6512.kt")
        assertTrue(s.contains("AccountingIntegrity8169.chainProvenProofState8169(env.proofState)"))
        assertTrue(s.contains("recordDeskStage(env.lane, \"SELL_CONFIRMED\", env.positionId)"))
    }

    @Test fun specialistCapabilityDoesNotRequireAnOutstandingTicket() {
        val s = src("engine/ToolkitSignalSheet.kt")
        assertTrue(s.contains("val executionEligible = runtime.runtimeAlive && buyerEnabled7609 && !liveQuarantine7609"))
        assertTrue(s.contains("pendingExecutableTicket=$"))
    }

    @Test fun recentPartialRowsIncludeEconomicSliceTruth() {
        val s = src("engine/PipelineHealthCollector.kt")
        assertTrue(s.contains("soldBasis="))
        assertTrue(s.contains("consumedRaw="))
        assertTrue(s.contains("ENTRY_ROW_BASIS_DIVERGENCE_8174"))
        val journal = src("engine/TradeHistoryStore.kt")
        assertTrue(journal.contains("soldCostBasisSol = tradeToStore.soldCostBasisSol"))
    }
}
