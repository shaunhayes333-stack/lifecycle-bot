package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6374 — Golden-tape invariants for the three P0 fixes bundled into
 * this build:
 *
 *  (1) Scanner Fanout Throttle — per-mint 60s dedupe upstream of INTAKE,
 *      collapses PUMP_PORTAL_WS burst that drove 184s bot-loop cycles.
 *
 *  (2) Aggregate-bad-band Tactic Rotation — force rotate a (lane, band)
 *      tactic when n>=50 and WR<30% (loss-rate > 70%). Applies both to
 *      since-rotation inline counter AND lifetime memory sweep.
 *
 *  (3) Heatmap Render Cache — top main-thread blocking call site
 *      (MainActivity.renderWrRecoveryHeatmap) moved off Main via a
 *      Dispatchers.Default-backed cache to eliminate the cumulative
 *      "thousands of ANRs after 5 hours" the operator captured.
 */
class Bundle6374InvariantsTest {

    // ── (1) Scanner Fanout Throttle ─────────────────────────────────────

    @Test
    fun scannerFanoutDedupe_admits_first_and_skips_within_ttl() {
        ScannerFanoutDedupe6374.resetForTest()
        val mint = "9x".padEnd(44, 'A')
        assertTrue(
            "V5.0.6374: first arrival for a mint must ADMIT (no prior sighting)",
            ScannerFanoutDedupe6374.admit("PUMP_PORTAL_WS", mint)
        )
        assertFalse(
            "V5.0.6374: second arrival within TTL must be SKIPPED (fanout throttle)",
            ScannerFanoutDedupe6374.admit("PUMP_PORTAL_WS", mint)
        )
        assertFalse(
            "V5.0.6374: repeated re-arrivals within TTL must stay skipped",
            ScannerFanoutDedupe6374.admit("PUMP_PORTAL_WS", mint)
        )
    }

    @Test
    fun scannerFanoutDedupe_isolates_by_source() {
        ScannerFanoutDedupe6374.resetForTest()
        val mint = "8y".padEnd(44, 'B')
        assertTrue(ScannerFanoutDedupe6374.admit("PUMP_PORTAL_WS", mint))
        assertTrue(
            "V5.0.6374: different SOURCE for same mint must still admit (per-(source,mint) key)",
            ScannerFanoutDedupe6374.admit("DEXSCREENER_PAIR", mint)
        )
        // Same source, same mint again → skip.
        assertFalse(ScannerFanoutDedupe6374.admit("PUMP_PORTAL_WS", mint))
    }

    @Test
    fun scannerFanoutDedupe_default_ttl_is_60_seconds() {
        ScannerFanoutDedupe6374.resetForTest()
        assertEquals(
            "V5.0.6374: operator directive is per-mint 60s dedupe upstream of INTAKE",
            60_000L, ScannerFanoutDedupe6374.currentTtlMs()
        )
    }

    @Test
    fun scannerFanoutDedupe_ttl_is_fluid_tunable() {
        ScannerFanoutDedupe6374.resetForTest()
        ScannerFanoutDedupe6374.setTtlMs(30_000L)
        assertEquals(
            "V5.0.6374: TTL must be adjustable at runtime by the on-board learning layer",
            30_000L, ScannerFanoutDedupe6374.currentTtlMs()
        )
        // Boundary clamp — never <5s, never >10min.
        ScannerFanoutDedupe6374.setTtlMs(1L)
        assertEquals(5_000L, ScannerFanoutDedupe6374.currentTtlMs())
        ScannerFanoutDedupe6374.setTtlMs(9_999_999L)
        assertEquals(600_000L, ScannerFanoutDedupe6374.currentTtlMs())
    }

    @Test
    fun scannerFanoutDedupe_admits_blank_mint_conservatively() {
        ScannerFanoutDedupe6374.resetForTest()
        // Blank/invalid mints should not consume a dedupe slot — downstream
        // validators (GlobalTradeRegistry.addToWatchlist "INVALID_MINT")
        // still reject them. Never dedupe on empty keys.
        assertTrue(ScannerFanoutDedupe6374.admit("PUMP_PORTAL_WS", ""))
        assertTrue(ScannerFanoutDedupe6374.admit("PUMP_PORTAL_WS", ""))
    }

    @Test
    fun botservice_wires_scanner_fanout_dedupe_upstream_of_intake() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6374: BotService must call ScannerFanoutDedupe6374.admit before admitProtectedMemeIntake in the PumpPortal WS callback",
            txt.contains("com.lifecyclebot.engine.ScannerFanoutDedupe6374.admit(\"PUMP_PORTAL_WS\", mint)")
        )
    }

    // ── (2) Aggregate-bad-band Tactic Rotation ──────────────────────────

    @Test
    fun tacticSwitcher_declares_aggregate_bad_band_constants() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(
            "V5.0.6374: AGG_BAD_BAND_MIN_SAMPLES must be exactly 50 per operator directive",
            txt.contains("AGG_BAD_BAND_MIN_SAMPLES = 50")
        )
        assertTrue(
            "V5.0.6374: AGG_BAD_BAND_MAX_WR must be exactly 0.30 per operator directive (WR<30%)",
            txt.contains("AGG_BAD_BAND_MAX_WR      = 0.30")
        )
        assertTrue(
            "V5.0.6374: AGG_BAD_BAND_MIN_LOSS_RATE must be 0.70 (complement of 30% WR)",
            txt.contains("AGG_BAD_BAND_MIN_LOSS_RATE = 0.70")
        )
    }

    @Test
    fun tacticSwitcher_inline_gate_forces_moonshot_s41_60_rotation() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(
            "V5.0.6374: onTradeClosed must fire agg-bad-band rotate when n>=50 && lossRate>0.70 regardless of pivot state",
            txt.contains("tradesIn >= AGG_BAD_BAND_MIN_SAMPLES && lossRate > AGG_BAD_BAND_MIN_LOSS_RATE")
        )
        assertTrue(
            "V5.0.6374: rotation reason string must be 'agg-bad-band' so telemetry surfaces the gate",
            txt.contains("\"agg-bad-band wr=")
        )
    }

    @Test
    fun tacticSwitcher_memory_sweep_also_forces_rotation() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(
            "V5.0.6374: maybeRotateFromMemory must include the aggregate-bad-band check so lifetime bleeders rotate",
            txt.contains("val aggBadBand = totalSamples >= AGG_BAD_BAND_MIN_SAMPLES && lossRate > AGG_BAD_BAND_MIN_LOSS_RATE")
        )
        assertTrue(
            "V5.0.6374: memory-sweep tag must be 'mem-agg-bad-band' so lifetime rotations are distinguishable",
            txt.contains("\"mem-agg-bad-band\"")
        )
    }

    @Test
    fun tacticSwitcher_67_trade_22pct_wr_bleeder_rotates() {
        // Reproduce the operator's exact snapshot: MOONSHOT|S41-60
        // REACCUMULATION n=67 W/L=15/52. WR=22.4% <30% → must rotate.
        // We use raw counters via onTradeClosed to verify the inline gate.
        // Guard: iterate 67 closes; 15 wins at +40% (bearing μ back up)
        // and 52 losses at -6% each (mild bleed matches operator μ path).
        val lane = "MOONSHOT_TEST_6374"
        val band = "S41-60"
        // Reset state — TacticSwitcher persists via LearningPersistence but
        // creates a fresh cell for a fresh key on first access.
        var closed = 0
        // 15 wins
        repeat(15) {
            com.lifecyclebot.engine.learning.TacticSwitcher.onTradeClosed(lane, band, +40.0)
            closed++
        }
        // 52 losses — takes us to n=67, lossRate=52/67 ≈ 77.6% (> 70% gate)
        repeat(52) {
            com.lifecyclebot.engine.learning.TacticSwitcher.onTradeClosed(lane, band, -6.0)
            closed++
        }
        val finalTactic = com.lifecyclebot.engine.learning.TacticSwitcher.currentTactic(lane, band)
        assertNotEquals(
            "V5.0.6374: MOONSHOT|S41-60 with 15W/52L (WR=22.4%) MUST NOT sit on the initial MOMENTUM tactic — agg-bad-band gate is required to force rotation",
            com.lifecyclebot.engine.learning.TacticSwitcher.Tactic.MOMENTUM,
            finalTactic
        )
    }

    @Test
    fun tacticSwitcher_healthy_band_does_not_rotate() {
        // Regression: a healthy band (50 trades, 60% WR) must NOT trigger
        // the new agg-bad-band gate. Guards against false-positive rotation.
        val lane = "QUALITY_HEALTHY_6374"
        val band = "S41-60"
        repeat(30) { com.lifecyclebot.engine.learning.TacticSwitcher.onTradeClosed(lane, band, +15.0) }
        repeat(20) { com.lifecyclebot.engine.learning.TacticSwitcher.onTradeClosed(lane, band, -6.0) }
        val finalTactic = com.lifecyclebot.engine.learning.TacticSwitcher.currentTactic(lane, band)
        assertEquals(
            "V5.0.6374: a healthy 30W/20L band (WR=60%) must remain on the initial tactic",
            com.lifecyclebot.engine.learning.TacticSwitcher.Tactic.MOMENTUM,
            finalTactic
        )
    }

    // ── (3) Heatmap Render Cache (ANR fix) ──────────────────────────────

    @Test
    fun heatmapRenderCache_source_wires_dispatchers_default() {
        val txt = File("src/main/kotlin/com/lifecyclebot/ui/HeatmapRenderCache6374.kt").readText()
        assertTrue(
            "V5.0.6374: heatmap compute must run on Dispatchers.Default, never on Main",
            txt.contains("Dispatchers.Default")
        )
        assertTrue(
            "V5.0.6374: default refresh cadence must be 15s so SQLite reads don't burn the main thread",
            txt.contains("DEFAULT_MIN_REFRESH_MS: Long = 15_000L")
        )
        assertTrue(
            "V5.0.6374: cache must be tunable by the on-board learning layer at runtime",
            txt.contains("fun setMinRefreshMs(")
        )
    }

    @Test
    fun mainActivity_uses_heatmap_cache_instead_of_sync_sqlite() {
        val txt = File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        assertTrue(
            "V5.0.6374: renderWrRecoveryHeatmap must delegate to HeatmapRenderCache6374 (off-main-thread)",
            txt.contains("com.lifecyclebot.ui.HeatmapRenderCache6374.get()")
        )
        // Assert the old synchronous fetch path is gone from that function
        // to prevent silent regression — we search for a distinctive line
        // that only appeared in the old inline compute.
        val heatmapFnStart = txt.indexOf("private fun renderWrRecoveryHeatmap(")
        val heatmapFnBody = if (heatmapFnStart >= 0) txt.substring(heatmapFnStart, minOf(txt.length, heatmapFnStart + 1500)) else ""
        assertFalse(
            "V5.0.6374: MainActivity.renderWrRecoveryHeatmap must not call rollingWinRatePctSlice inline any more (moved off Main)",
            heatmapFnBody.contains("rollingWinRatePctSlice(offset")
        )
    }

    @Test
    fun heatmapRenderCache_min_refresh_is_clamped() {
        com.lifecyclebot.ui.HeatmapRenderCache6374.resetForTest()
        com.lifecyclebot.ui.HeatmapRenderCache6374.setMinRefreshMs(1L)
        assertEquals(2_000L, com.lifecyclebot.ui.HeatmapRenderCache6374.currentMinRefreshMs())
        com.lifecyclebot.ui.HeatmapRenderCache6374.setMinRefreshMs(9_999_999L)
        assertEquals(300_000L, com.lifecyclebot.ui.HeatmapRenderCache6374.currentMinRefreshMs())
        com.lifecyclebot.ui.HeatmapRenderCache6374.setMinRefreshMs(15_000L)
        assertEquals(15_000L, com.lifecyclebot.ui.HeatmapRenderCache6374.currentMinRefreshMs())
    }
}
