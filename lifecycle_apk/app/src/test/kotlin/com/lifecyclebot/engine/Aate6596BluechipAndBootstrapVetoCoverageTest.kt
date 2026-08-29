package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * V5.0.6596 — twin fix responding to operator's 6595 execution-liveness
 * directive:
 *
 *  (1) BOOTSTRAP_ADVISORY_ONLY: the 6594 LEARNED_POLICY_VETO_6593 was
 *      firing on cold lanes with hist=2 because currentAuthority(lane)
 *      falls back to globalAuthority() when a lane has no head yet.
 *      Operator directive:
 *        > "A bootstrap/warming policy head must be advisory ... It must
 *        >  NOT terminal-veto a candidate until the existing configured
 *        >  statistical sample/confidence requirement has been satisfied."
 *      Fix: the veto now gates on laneHasOwnAuthoritativeHead(lane).
 *      Fresh cold lanes get advisory shaping only.
 *
 *  (2) BLUECHIP_CONTAMINATION_FIX: source-family lane affinities in
 *      TokenMergeQueue.inferLaneAffinity unconditionally added BLUECHIP
 *      for SOLANA_RPC/METEORA/ORCA/JUPITER/HELIUS/DEX_BOOSTED/DEX_TRENDING/
 *      COINGECKO/BIRDEYE/WHALE. A fresh $17k-liq $170k-mcap Raydium pool
 *      routed through DexScreener therefore inherited BLUECHIP and got
 *      executed under BLUECHIP exit doctrine (activate=8/give=4 — the
 *      wrong doctrine for a micro-cap meme). Snapshot 6595 confirmed
 *      every canonical open attributed to BLUECHIP while SHITCOIN /
 *      MOONSHOT / SNIPER stayed at 0. Fix: BLUECHIP membership requires
 *      >= $5M mcap AND >= $200k liquidity; otherwise other productive
 *      lanes (MOONSHOT/QUALITY/PROJECT_SNIPER/STANDARD/CORE/V3) still
 *      attach but BLUECHIP is stripped.
 */
class Aate6596BluechipAndBootstrapVetoCoverageTest {

    @Test
    fun aate6596_veto_gates_on_lane_own_authoritative_head() {
        val botSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue(
            "V5.0.6596: the WAIT-veto must consult laneHasOwnAuthoritativeHead(lane)",
            botSrc.contains("laneHasOwnAuthoritativeHead(lane)") &&
                botSrc.contains("laneOwnHeadAuthoritative6596")
        )
        // Previous unconditional agiAuthority6020==AUTHORITATIVE veto must
        // NO LONGER be the sole gate.
        assertFalse(
            "V5.0.6596: pre-6596 unconditional agiAuthority6020==AUTHORITATIVE veto " +
                "check must not be the sole gate any more",
            botSrc.contains("val laneAuthoritativePolicyNegative6593 = agiAuthority6020 ==\n" +
                "                com.lifecyclebot.engine.UnifiedPolicyHead.AuthorityTier.AUTHORITATIVE &&\n" +
                "                !authoritativePolicyPositive6568")
        )
        val headSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/UnifiedPolicyHead.kt"
        ).readText()
        assertTrue(
            "V5.0.6596: UnifiedPolicyHead must expose laneHasOwnAuthoritativeHead(lane)",
            headSrc.contains("fun laneHasOwnAuthoritativeHead(lane: String): Boolean") &&
                headSrc.contains("h.trained >= AUTHORITY_AUTHORITATIVE")
        )
    }

    @Test
    fun aate6596_bluechip_affinity_requires_real_bluechip_floor() {
        val tmqSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/TokenMergeQueue.kt"
        ).readText()
        assertTrue(
            "V5.0.6596: BLUECHIP floor must require both mcap>=\$5M AND liq>=\$200k",
            tmqSrc.contains("meetsBluechipFloor6596") &&
                tmqSrc.contains("marketCapUsd >= 5_000_000.0 && liquidityUsd >= 200_000.0")
        )
        assertTrue(
            "V5.0.6596: addAll() helper must strip BLUECHIP when the floor is not met",
            tmqSrc.contains("if (l == \"BLUECHIP\" && !meetsBluechipFloor6596) continue")
        )
        // Legacy line 317 shortcut — the low-bar $1M-or-$75k-liq gate — must be gone.
        assertFalse(
            "V5.0.6596: pre-6596 low-bar BLUECHIP shortcut (marketCapUsd >= 1M OR liq >= 75k) must be removed",
            tmqSrc.contains("if (marketCapUsd >= 1_000_000.0 || liquidityUsd >= 75_000.0) out += \"BLUECHIP\"")
        )
    }
}
