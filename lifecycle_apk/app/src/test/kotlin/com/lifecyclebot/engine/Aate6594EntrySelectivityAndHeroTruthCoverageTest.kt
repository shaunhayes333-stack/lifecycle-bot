package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * V5.0.6594 — entry selectivity (learned-policy veto of WAIT->PROBE
 * promotion) + shared-wallet hero truth on the Perps card.
 *
 * Operator directive Feb 2026:
 *   > "Lane evidence MAY overcome weak generic scoring only when it
 *   >  provides independent positive evidence. Lane membership alone
 *   >  cannot promote WAIT."
 *   > "Trader-specific screens may filter positions/PnL by trader/lane,
 *   >  but may NOT invent their own wallet balance."
 *
 * Snapshot 6591 evidence:
 *   - LANE_BUY_INTENT_OVERRIDES_BASE_WAIT: 221
 *   - LANE_WAIT_OVERRIDE_DUST_PROBE: 107
 *   - LANE_WAIT_OVERRIDE_ZERO_SIGNAL_DUST_PROBE: 80
 *   - Learned global bias: -0.62
 *   - Every context reported winP ~11-20%
 *   - calibrationDemotes: 0
 * i.e. the learned negative evidence had zero causal effect on the
 * WAIT->PROBE conversion. V5.0.6594 gates the promotion on the
 * AUTHORITATIVE policy tier; a negative policy signal now vetoes the
 * probe (LEARNED_POLICY_NEGATIVE_LANE_WAIT_PROMOTION_VETO_6593) instead
 * of silently promoting.
 *
 * Snapshot 6591 also showed the Perps card taking balance from a trader-
 * local cache (state.paperBalanceSol) which could diverge from the
 * canonical PaperCapitalAuthority6577. V5.0.6594 routes the Perps card
 * balance through the canonical authority so every shared-wallet hero
 * reads the same immutable snapshot.
 */
class Aate6594EntrySelectivityAndHeroTruthCoverageTest {

    @Test
    fun aate6594_wait_to_probe_vetoed_when_authoritative_policy_negative() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue(
            "V5.0.6594: authoritative-negative policy must veto WAIT->PROBE promotion " +
                "with a distinct telemetry line",
            src.contains("LEARNED_POLICY_NEGATIVE_LANE_WAIT_PROMOTION_VETO_6593") &&
                src.contains("laneAuthoritativePolicyNegative6593")
        )
        assertTrue(
            "V5.0.6594: veto must return WAIT (never silently promote)",
            src.contains("blockReason = \"LEARNED_POLICY_VETO_6593\"")
        )
        // Regression: the veto check must live BEFORE the DUST_PROBE
        // fallthrough; if it moves after, the promotion sneaks through.
        val vetoIdx = src.indexOf("LEARNED_POLICY_NEGATIVE_LANE_WAIT_PROMOTION_VETO_6593")
        val dustProbeIdx = src.indexOf("LANE_WAIT_OVERRIDE_DUST_PROBE\")")
        assertTrue(
            "V5.0.6594: the veto site must precede the DUST_PROBE fallthrough",
            vetoIdx > 0 && dustProbeIdx > 0 && vetoIdx < dustProbeIdx
        )
    }

    @Test
    fun aate6594_perps_card_reads_shared_paper_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt"
        ).readText()
        assertTrue(
            "V5.0.6594: Perps card must consume PaperCapitalAuthority6577.totalEquitySol() " +
                "in paper mode (not a trader-local cache)",
            src.contains("com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.totalEquitySol()") &&
                src.contains("§SHARED_WALLET_HERO_TRUTH")
        )
    }
}
