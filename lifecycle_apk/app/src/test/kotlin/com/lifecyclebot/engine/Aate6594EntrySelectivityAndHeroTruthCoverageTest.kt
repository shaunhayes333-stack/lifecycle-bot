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
    fun aate6594_wait_to_probe_is_lane_locally_shaped_when_authoritative_policy_negative() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6613: learned policy must shape rather than terminal-veto lane evidence",
            src.contains("LEARNED_POLICY_NEGATIVE_LANE_WAIT_SHAPED_6613") &&
                src.contains("laneAuthoritativePolicyNegative6593") &&
                src.contains("learnedWaitShape6613")
        )
        assertFalse(src.contains("LEARNED_POLICY_NEGATIVE_LANE_WAIT_PROMOTION_VETO_6593"))
        val shapeIdx = src.indexOf("LEARNED_POLICY_NEGATIVE_LANE_WAIT_SHAPED_6613")
        val dustProbeIdx = src.indexOf("LANE_WAIT_OVERRIDE_DUST_PROBE\")")
        assertTrue(shapeIdx > 0 && dustProbeIdx > 0 && shapeIdx < dustProbeIdx)
    }

    @Test
    fun aate6594_perps_card_reads_shared_paper_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt"
        ).readText()
        assertTrue(
            "V5.0.6643: Perps card must consume the unified atomic account snapshot",
            src.contains("UnifiedAccountSnapshot6635.read(\"PERPS_CARD\")") &&
                src.contains("§SHARED_WALLET_HERO_TRUTH")
        )
    }
}
