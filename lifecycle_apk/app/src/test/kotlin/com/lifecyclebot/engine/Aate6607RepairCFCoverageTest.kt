package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * V5.0.6607 — Operator REPAIR C (audit lane alignment) + REPAIR F (mark
 * propagation), plus V5.0.6607b MemeTrader-organism follow-up:
 *
 *   V5.0.6607a — Executor initially refused synthesized "STANDARD" lane
 *     at the entry gate. The operator's follow-up directive explicitly
 *     lists STANDARD as a legitimate MemeTrader specialist alongside
 *     QUALITY / BLUECHIP / SHITCOIN / etc. The refusal was REVERTED.
 *     STANDARD remains executable; only a forensic telemetry emit
 *     (EXECUTOR_OWNERLANE_SYNTHESIZED_STANDARD_6607) fires when both
 *     layerTag and ts.source were blank so the operator can grep for
 *     upstream owner-attribution gaps without blocking the buy.
 *
 *   V5.0.6607b — MEME_RING contributorOnly=23155 / allLaneContribution=0
 *     was caused by the LIVE_ALL_LANE_CONTRIBUTION_4469 emit being
 *     gated on RuntimeModeAuthority.isLive(). Paper runs — the
 *     operator's mode — could never increment the counter regardless
 *     of how many specialists contributed. Emit now fires in BOTH
 *     paper and live so the causal-contribution invariant reflects
 *     reality.
 *
 *   REPAIR C §AcceptanceInvariantAudit6441 lane-name alignment:
 *     Specialists correctly call CanonicalSizingBridge6532 with
 *     STOCK_SPOT / STOCK_LEV / CRYPTO_SPOT / PERPS_SOLUSDT lane
 *     suffixes. The audit switched from exact-lane matching to
 *     prefix-scan via labelSnapshotByPrefix6607 so it can enumerate
 *     every specialist-emitted lane suffix.
 *
 *   REPAIR F §Executable mark propagation: freshness window widened
 *     from 120s to 300s + last-resort provisional bootstrap
 *     (PAPER_ENTRY_OBSERVATION_MARK_STALE_BOOTSTRAPPED_6607).
 */
class Aate6607RepairCFCoverageTest {

    @Test
    fun aate6607_standard_lane_remains_executable_per_operator_directive() {
        val exec = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/Executor.kt"
        ).readText()
        // The V5.0.6607a fail-close refusal must be reverted so STANDARD is
        // still a legitimate MemeTrader specialist per operator directive.
        assertTrue(
            "V5.0.6607b: STANDARD lane must remain executable (operator directive lists it as legitimate specialist)",
            exec.contains(".ifBlank { \"STANDARD\" }") &&
                !exec.contains("markPaperBuyNotOpened(\"OWNERLANE_MISSING_OR_STANDARD_6607\")")
        )
        assertTrue(
            "V5.0.6607b: blank-both forensic emit must still fire so upstream owner-attribution gaps are surfaced",
            exec.contains("EXECUTOR_OWNERLANE_SYNTHESIZED_STANDARD_6607") &&
                exec.contains("layerTag.isBlank() && ts.source.isBlank()")
        )
    }

    @Test
    fun aate6607_audit_bridge_hits_via_prefix_scan() {
        val audit = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/AcceptanceInvariantAudit6441.kt"
        ).readText()
        assertTrue(
            "V5.0.6607: AcceptanceInvariantAudit6441 must enumerate sizing-bridge labels by prefix, not exact match",
            audit.contains("labelSnapshotByPrefix6607(classPrefix)") &&
                audit.contains("CANONICAL_SIZING_BRIDGE_6532|CLASS=\$klass|LANE=")
        )
        assertTrue(
            "V5.0.6607: hard-coded PERPS_SOL/PERPS_BTC/PERPS_ETH exact list must be removed",
            !audit.contains("listOf(\"PERPS_SOL\", \"PERPS_BTC\", \"PERPS_ETH\", \"PERPS\")")
        )
        val hub = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt"
        ).readText()
        assertTrue(
            "V5.0.6607: PipelineHealthCollector must expose labelSnapshotByPrefix6607",
            hub.contains("fun labelSnapshotByPrefix6607(prefix: String): Map<String, Long>")
        )
    }

    @Test
    fun aate6607_paper_mark_bootstrap_widened_to_300s_with_last_resort() {
        val exec = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/Executor.kt"
        ).readText()
        assertTrue(
            "V5.0.6607: bootstrap freshness window must be widened to 300s",
            exec.contains("WINDOW_MS_6607 = 300_000L") &&
                exec.contains("tokenMapFresh6607") &&
                exec.contains("stateFresh6607")
        )
        assertTrue(
            "V5.0.6607: last-resort provisional bootstrap must be emitted with stale timestamp fallback",
            exec.contains("PAPER_ENTRY_OBSERVATION_MARK_STALE_BOOTSTRAPPED_6607") &&
                exec.contains("if (isStale6607) now6607 else markTs6607")
        )
    }

    @Test
    fun aate6607b_all_lane_contribution_emits_in_paper_mode() {
        val bot = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        // The LIVE_ALL_LANE_CONTRIBUTION_4469 emit must fire regardless of
        // isLive(). The MEME_ALL_LANE_CONTRIBUTION_6607_<MODE>_<LANE>
        // label carries the mode tag so operator can grep either.
        val emitIdx = bot.indexOf("PipelineHealthCollector.labelInc(\"LIVE_ALL_LANE_CONTRIBUTION_4469_")
        assertTrue("V5.0.6607b: 4469 emit must exist", emitIdx > 0)
        // Search backward for the nearest enclosing `if (` — it must NOT be
        // the RuntimeModeAuthority.isLive() gate.
        val liveGateNearestBefore = bot.substring(0, emitIdx).lastIndexOf(
            "if (com.lifecyclebot.engine.RuntimeModeAuthority.isLive())"
        )
        val runBlockNearestBefore = bot.substring(0, emitIdx).lastIndexOf("run {")
        assertTrue(
            "V5.0.6607b: 4469 emit must be inside a `run { }` block that runs unconditionally in both paper and live, not inside the isLive() gate",
            runBlockNearestBefore > liveGateNearestBefore
        )
        assertTrue(
            "V5.0.6607b: mode-tagged all-lane contribution label must exist",
            bot.contains("MEME_ALL_LANE_CONTRIBUTION_6607_")
        )
    }
}
