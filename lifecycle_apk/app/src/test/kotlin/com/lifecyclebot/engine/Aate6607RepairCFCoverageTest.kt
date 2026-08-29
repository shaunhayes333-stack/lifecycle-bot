package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * V5.0.6607 — Operator REPAIR C (ownerLane immutability + STANDARD leak)
 * and REPAIR F (executable mark propagation).
 *
 * REPAIR C — Owner-lane immutability at the paper executor:
 *   Prior code defaulted a blank layerTag + blank ts.source to the
 *   literal string "STANDARD", which then propagated as ownerLane
 *   through ticket / journal / learning. The operator captured real
 *   PAPER BUY journal rows with lane=STANDARD despite STANDARD being
 *   a shadow/read-only lane with no FDG/exec. Fix: refuse to synthesize
 *   an ownerLane. Fail-close with EXECUTOR_OWNERLANE_MISSING_6607 so
 *   the upstream owner-attribution bug is surfaced instead of masked.
 *
 * REPAIR C — AcceptanceInvariantAudit6441 lane-name alignment:
 *   Specialists correctly call CanonicalSizingBridge6532 but emit lane
 *   suffixes (STOCK_SPOT, STOCK_LEV, CRYPTO_SPOT, PERPS_SOLUSDT). The
 *   audit's hard-coded lane list only checked short names (STOCK,
 *   PERPS) so it never resolved to actual emitted labels and reported
 *   7/7 E_no_specialized_trader_routed_through_sizing_bridge. Fix:
 *   PipelineHealthCollector.labelSnapshotByPrefix6607 + prefix match
 *   on CANONICAL_SIZING_BRIDGE_6532|CLASS=<klass>|LANE= .
 *
 * REPAIR F — Executable mark propagation:
 *   158× missingExecutableMarkWithValidSource against 184×
 *   PAPER_BUY_NOT_OPENED. The 6600 bootstrap required tokenMap or state
 *   freshness within 120s; scans → V3 → FDG → sizing → executor can
 *   take 2-3 minutes under load. Widen to WINDOW_MS_6607=300s and add
 *   a last-resort provisional bootstrap when a valid priceUsd exists
 *   but timestamps are all zero.
 */
class Aate6607RepairCFCoverageTest {

    @Test
    fun aate6607_executor_refuses_standard_or_blank_ownerlane() {
        val exec = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/Executor.kt"
        ).readText()
        assertTrue(
            "V5.0.6607: paper executor must refuse blank/STANDARD ownerLane before entry gate",
            exec.contains("EXECUTOR_OWNERLANE_MISSING_6607") &&
                exec.contains("OWNERLANE_MISSING_OR_STANDARD_6607") &&
                exec.contains("laneRawSource6607.isBlank() || laneRawSource6607 == \"STANDARD\"")
        )
        // The literal .ifBlank { "STANDARD" } synthesis pattern must be gone.
        assertTrue(
            "V5.0.6607: the .ifBlank { \"STANDARD\" } synthesis must be removed at the entry-gate lane derivation",
            !exec.contains(".uppercase().take(24).ifBlank { \"STANDARD\" }")
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
        // The hard-coded PERPS_SOL / PERPS_BTC / PERPS_ETH exact list must be gone.
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
        // The previous 120s window must not be re-emitted as the primary gate.
        val idx = exec.indexOf("120_000L")
        // 120_000L may still appear elsewhere; the bootstrap block specifically
        // must not reference it as WINDOW_MS.
        val bootstrapStart = exec.indexOf("§REPAIR_F_EXECUTABLE_MARK_PROPAGATION")
        val bootstrapEnd = exec.indexOf("val strictMark6575", bootstrapStart)
        assertTrue("V5.0.6607: bootstrap block must not fall back to 120s window",
            bootstrapStart > 0 && bootstrapEnd > bootstrapStart &&
                !exec.substring(bootstrapStart, bootstrapEnd).contains("120_000L"))
    }
}
