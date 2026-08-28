package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6572 — MEME VOLUME REPAIR acceptance.
 *
 * Operator forensic (build 5.0.6569 snapshot, hero balance $1000+):
 *   * 27 lifetime trades in 2146s.
 *   * 26 EXEC_OPEN_BLOCKED_SIZE_NOT_EXECUTABLE_6491.
 *   * 20+ EXEC_GATE resolvedSize≈0.01 blocks (0.0099, 0.018, 0.019).
 *   * MemeTrader previously discovered 1000% moonshots easily; the
 *     recent stack has been generating dust-sized entries that get
 *     killed by the 0.05 executable floor and poison learning.
 *
 * Root causes fixed:
 *   1. SmartSizer economic-min promotion required tradeable ≥ 2×dustFloor
 *      (0.10 SOL). Relaxed to tradeable ≥ dustFloor so any wallet with
 *      the floor available promotes.
 *   2. Executor.paperBuy applied the graduated-entry 35% tranche in
 *      paper mode, cutting a healthy 0.05 SOL fluid size to 0.0175 SOL
 *      sub-floor. Paper mode now skips graduated tranching entirely
 *      and uses the fluid size directly (no real-money slippage cost
 *      to trade off against). Live mode still tranches per V5.0.6549.
 *
 * Both changes are source-only invariants; the testing agent verifies
 * them by string match rather than runtime because the APK cannot be
 * exercised in the JVM test runner.
 */
class MemeVolumeRepair6572Test {

    @Test
    fun smartSizer_promotes_at_floor_not_two_times_floor() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/SmartSizer.kt"
        ).readText()
        assertTrue(
            "V5.0.6572: paper economic-min promotion must fire when tradeable >= dustFloor",
            src.contains("if (size < dustFloor && tradeable >= dustFloor)"),
        )
        assertTrue(
            "V5.0.6572: old 2×dustFloor guard must be gone",
            !src.contains("tradeable >= dustFloor * 2.0"),
        )
    }

    @Test
    fun paperBuy_skips_graduated_tranche_for_paper_mode() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/Executor.kt"
        ).readText()
        assertTrue(
            "V5.0.6572: paper graduated tranche must be skipped",
            src.contains("PAPER_GRADUATED_TRANCHE_SKIPPED_6572"),
        )
        assertTrue(
            "V5.0.6572: paper full-fluid clamp must be present",
            src.contains("paperBuy.paperFullFluid_6572"),
        )
    }
}
