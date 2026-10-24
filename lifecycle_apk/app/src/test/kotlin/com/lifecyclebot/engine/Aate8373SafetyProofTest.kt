package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveRugCluster8269
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live entry must not confuse unresolved exact-mint safety evidence with a soft score penalty. */
class Aate8373SafetyProofTest {
    @Test fun everyEssentialUnresolvedFieldDefersIndependently() {
        val unresolved = listOf(
            "RUGCHECK_PENDING_OR_UNKNOWN:TIMEOUT",
            "RUGCHECK_PENDING_OR_UNKNOWN:PENDING_REVIEW",
            "MINT_AUTHORITY_UNKNOWN",
            "HOLDER_DATA_PENDING",
            "HOLDER_DATA_UNKNOWN",
            "HOLDER_DISTRIBUTION_PENDING",
            "LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP:hydrate",
            "LIQUIDITY_PROOF_PENDING",
        )
        for (reason in unresolved) {
            assertEquals(reason, PreTradeHardGate.criticalProofPending8373(listOf(reason)).single())
        }
        assertEquals(unresolved, PreTradeHardGate.criticalProofPending8373(unresolved + unresolved))
    }

    @Test fun provenNonessentialPenaltyDoesNotBecomeANewHardBlock() {
        assertTrue(PreTradeHardGate.criticalProofPending8373(listOf(
            "LOW_LIQUIDITY_SIZE_REDUCED:liq=1000 min=1500", "SENTIMENT_WEAK", "MOMENTUM_WAIT"
        )).isEmpty())
    }

    @Test fun theFiveDangerFlagsFromTheOperatorRiskSheetCannotBeApprovedByScore() {
        fun red(name: String, description: String) = LiveRugCluster8269.Flag(name, "danger", description)
        val warnings = listOf(
            red("Large Amount of LP Unlocked", "Owner can remove liquidity"),
            red("Top 10 holders high ownership", "Top 10 users hold more than 70% token supply"),
            red("Single holder ownership", "One user holds a large share"),
            red("Low Liquidity", "Low liquidity in the pool"),
            red("High ownership", "Top users hold more than 80%"),
        )
        assertEquals(LiveRugCluster8269.CLUSTER_REFUSAL, LiveRugCluster8269.refusal(warnings))
    }
}
