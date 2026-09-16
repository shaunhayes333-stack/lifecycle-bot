package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6759 — regression fence for the OrderSizeResolver6441 throughput gate.
 *
 * The 6758 operator-uploaded fix put `ExitThroughputAuthority6727` at the
 * mandatory sizing authority so no specialist (including cross-asset
 * CanonicalEntryAuthority6551 admissions) can seal a positive entry size
 * while exits are saturated. Without a lane-fairness bypass at the gate,
 * portfolio-wide cash-starved / velocity blocks would starve MEME lanes
 * that still had headroom under their target allocation.
 *
 * 6759 §MEME_UNCHOKE_SAFETY adds an explicit `LaneCapitalFairness6732`
 * re-check at the gate boundary: any lane with headroom bypasses the
 * block UNLESS the reason is `POSITION_HARD_CAP_EXIT_THROUGHPUT_6727`
 * (portfolio-wide sanity ceiling, honoured for every lane).
 *
 * This test locks:
 *   • the gate itself is present at the mandatory sizer
 *   • the gate consults `ExitThroughputAuthority6727.evaluate(mode, lane)`
 *   • the meme-unchoke bypass fires on lane headroom + non-hard-cap reason
 *   • the hard-cap reason short-circuits the bypass so the ceiling still
 *     fires for meme lanes when inventory is genuinely saturated
 *   • per-lane block AND bypass telemetry labels are emitted (so choke
 *     points are visible in the funnel snapshot without a grep)
 *   • fail-open on any bookkeeping exception in the gate
 */
class Aate6759OrderSizeResolverThroughputGateTest {

    private val src by lazy {
        File("src/main/kotlin/com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt").readText()
    }

    @Test fun mandatory_sizer_consults_exit_throughput_authority_with_lane() {
        assertTrue(
            "OrderSizeResolver must call ExitThroughputAuthority6727.evaluate(mode, lane)",
            src.contains("ExitThroughputAuthority6727.evaluate("),
        )
        assertTrue(
            "gate must pass mode='paper' when paperMode is true and lane=laneName",
            src.contains("mode = if (paperMode) \"paper\" else \"live\"") &&
                src.contains("lane = laneName"),
        )
        assertTrue(
            "sizer must be fail-open on throughput authority exceptions",
            src.contains("val throughput6758 = try {") &&
                src.contains("} catch (_: Throwable) { null }"),
        )
    }

    @Test fun meme_unchoke_bypass_uses_lane_capital_fairness() {
        assertTrue(
            "gate must re-check LaneCapitalFairness6732.hasHeadroom before rejecting",
            src.contains("LaneCapitalFairness6732.hasHeadroom("),
        )
        assertTrue(
            "unchoke bypass must fire when laneHeadroom is true and reason is not the hard cap",
            src.contains("laneHeadroom6759 && !hardCap6759"),
        )
        assertTrue(
            "hard-cap check must use the exact reason emitted by ExitThroughputAuthority6727",
            src.contains("POSITION_HARD_CAP_EXIT_THROUGHPUT_6727"),
        )
    }

    @Test fun bypass_and_block_paths_both_emit_per_lane_telemetry() {
        assertTrue(
            "bypass path must emit a lane-scoped counter for meme choke visibility",
            src.contains("ORDER_SIZE_MEME_UNCHOKE_LANE_HEADROOM_6759_\${laneName.uppercase().take(24)}"),
        )
        assertTrue(
            "block path must emit a lane-scoped counter for meme choke visibility",
            src.contains("ORDER_SIZE_BLOCKED_EXIT_THROUGHPUT_6758_\${laneName.uppercase().take(24)}"),
        )
        assertTrue(
            "block forensic must attribute both hardCap and laneHeadroom state for triage",
            src.contains("hardCap=\$hardCap6759 laneHeadroom=\$laneHeadroom6759"),
        )
    }

    @Test fun block_returns_executable_false_with_zero_final_size_and_original_reason() {
        // The blocked Resolution must not smuggle a positive size — that would
        // let downstream authorities try to fill on a saturated portfolio.
        assertTrue(src.contains("finalSizeSol = 0.0,"))
        assertTrue(src.contains("executable = false,"))
        assertTrue(src.contains("reason = throughput6758.reason,"))
    }
}
