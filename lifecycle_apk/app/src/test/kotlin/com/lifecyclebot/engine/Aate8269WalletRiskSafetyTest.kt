package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveRugCluster8269
import com.lifecyclebot.engine.truth.PaperUniverseParity8223
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 5.0.8269: real named wallet risk findings must survive every lane and reach the money door. */
class Aate8269WalletRiskSafetyTest {
    private fun f(name: String, level: String = "danger", detail: String = "") =
        LiveRugCluster8269.Flag(name, level, detail)

    private val screenshotRisk = listOf(
        f("Top 10 holders high ownership", detail = "The top 10 users hold more than 70% token supply"),
        f("Single holder ownership", detail = "One user holds a large amount of the token supply"),
        f("Low Liquidity", detail = "Low amount of liquidity in the token pool"),
        f("High ownership", detail = "The top users hold more than 80% token supply"),
        f("Unverified token", "warning", "Multiple tokens can use the same name and symbol")
    )

    @Test fun fourDangerFlagsAndOneWarningFromScreenshotHardBlockEvenWithPositiveRoute() {
        assertEquals(LiveRugCluster8269.CLUSTER_REFUSAL, LiveRugCluster8269.refusal(screenshotRisk))
        assertEquals(LiveRugCluster8269.CLUSTER_REFUSAL, LiveRugCluster8269.refusal(screenshotRisk.take(3)))
        assertEquals(LiveRugCluster8269.CLUSTER_REFUSAL,
            LiveRugCluster8269.refusal(screenshotRisk.filterIndexed { index, _ -> index != 1 }))
    }

    @Test fun noTradeChokeFromUnverifiedAloneOrAYoungConcentratedCurveWithoutLiquidityRisk() {
        assertNull(LiveRugCluster8269.refusal(emptyList()))
        assertNull(LiveRugCluster8269.refusal(listOf(f("Unverified token", "warning"))))
        assertNull(LiveRugCluster8269.refusal(listOf(
            f("Top 10 holders high ownership"), f("Single holder ownership"))))
        assertNull(LiveRugCluster8269.refusal(listOf(
            f("Top 10 holders high ownership", "warning"), f("Single holder ownership", "warning"),
            f("Low Liquidity", "warning"))))
        assertNull(LiveRugCluster8269.refusal(listOf(
            f("LP bonding curve custody", "danger"), f("Unverified token", "warning"))))
    }

    @Test fun fiveProviderDangerFlagsHardBlockEvenIfARouteExists() {
        assertEquals(LiveRugCluster8269.FIVE_DANGER_REFUSAL,
            LiveRugCluster8269.refusal(listOf(
                f("No known buyers"), f("Creator sells"), f("Bundled wallets"),
                f("Suspicious flow"), f("Supply controlled"))))
    }

    @Test fun paperParityMirrorsLiveHardWallInsteadOfLearningUnexecutableEntries() {
        val risk = PaperUniverseParity8223.Wall8223(
            liquidityUsd = 2_500.0, safetyHardBlocked = false, rugcheckScore = 55,
            mintAuthorityDisabled = true, freezeAuthorityDisabled = true,
            topHolderPct = -1.0, blacklisted = false, quarantined = false,
            confirmedWalletRiskCluster8269 = true)
        assertEquals(LiveRugCluster8269.CLUSTER_REFUSAL, PaperUniverseParity8223.wall8223(risk))
        assertNull(PaperUniverseParity8223.wall8223(risk.copy(confirmedWalletRiskCluster8269 = false)))
    }

    @Test fun liveCheckerAndFinalBroadcastUseExactlyTheSameRiskProof() {
        val checker = File("src/main/kotlin/com/lifecyclebot/engine/TokenSafetyChecker.kt").readText()
        val pre = File("src/main/kotlin/com/lifecyclebot/engine/PreTradeHardGate.kt").readText()
        val parity = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperUniverseParity8223.kt").readText()
        val holder = File("src/main/kotlin/com/lifecyclebot/engine/truth/OnChainHolderConcentration7379.kt").readText()
        assertTrue(checker.contains("LiveRugCluster8269.refusal(providerFlags8269)"))
        assertTrue(checker.contains("if (!isPaperMode) hard.add(confirmedRugCluster8269!!)"))
        assertTrue(checker.contains("confirmedWalletRiskCluster8269 = confirmedRugCluster8269 != null"))
        assertTrue(pre.contains("if (safety.confirmedWalletRiskCluster8269)"))
        assertTrue(pre.contains("val safetyAt = safety.checkedAt"))
        val shared = File("src/main/kotlin/com/lifecyclebot/engine/sell/LiveBuyAdmissionGate.kt").readText()
        // Main's later dedicated pretrade call retains its requeue semantics;
        // the top-up/any additional spend path must run it here before APPROVED.
        assertTrue(shared.contains("if (callSite != \"liveBuy.main\")"))
        assertTrue(shared.contains("PreTradeHardGate.requireLiveBuyAllowed("))
        assertTrue(shared.contains("SAFETY_PROOF_DEFERRED_8269"))
        assertTrue(shared.indexOf("PreTradeHardGate.requireLiveBuyAllowed(") < shared.indexOf("return Decision.Approved"))
        assertTrue(pre.contains("LIVE_SAFETY_REPORT_NOT_FRESH_8269"))
        assertTrue(pre.contains("HOLDER_DISTRIBUTION_RISK_UNVERIFIED_8269"))
        assertTrue(parity.contains("confirmedWalletRiskCluster8269 = s.confirmedWalletRiskCluster8269"))
        assertTrue(holder.contains("if (byOwner.isEmpty()) return null"))
    }
}
