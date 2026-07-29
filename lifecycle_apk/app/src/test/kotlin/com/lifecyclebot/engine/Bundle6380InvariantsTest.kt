package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6380 — Golden-tape invariants for the two operator-driven fixes:
 *
 * (A) PAPER WALLET CONTINUITY (hole #2). V5.0.6376 patched
 *     BotService.startBot but MISSED PaperWalletStore.restore() which is
 *     the actual code MainActivity calls on every cold open / config
 *     change. That helper still had the modeChangedLiveToPaper reset
 *     branch — so a LIVE→PAPER toggle wiped gains via a completely
 *     different code path than the one V5.0.6376 fixed.
 *
 * (B) LEARNING TRAJECTORY GOVERNOR. Operator directive: "minimum daily
 *     wallet increase of 2x-5x in paper or live trading. no exceptions.
 *     bot must benchmark there!!!!". Additive telemetry only — never
 *     blocks trades. Emits UP/DOWN/FLAT + 2X/5X/MISS counters every ~200
 *     bot-loop ticks so the operator can see whether the AATE stack is
 *     hitting the benchmark or regressing.
 */
class Bundle6380InvariantsTest {

    // ── (A) PaperWalletStore.restore no longer resets on mode toggle ────

    @Test
    fun paper_wallet_store_no_longer_wipes_on_mode_toggle() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/PaperWalletStore.kt").readText()
        assertTrue(
            "V5.0.6380: reset condition must depend ONLY on savedBalance < 0.01 (fresh install), never on modeChangedLiveToPaper",
            txt.contains("if (cfg.paperMode && savedBalance < 0.01) {")
        )
        assertTrue(
            "V5.0.6380: SECOND wallet-continuity hole must be documented so future refactors do not reintroduce it",
            txt.contains("V5.0.6380 — SECOND wallet-continuity hole")
        )
    }

    // ── (B) Learning Trajectory Governor ────────────────────────────────

    @Test
    fun learning_trajectory_governor_exists_and_targets_2x_5x() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LearningTrajectoryGovernor6380.kt").readText()
        assertTrue(
            "V5.0.6380: governor must enshrine the operator's 2x minimum target",
            txt.contains("TARGET_MIN_MULT: Double = 2.0")
        )
        assertTrue(
            "V5.0.6380: governor must enshrine the operator's 5x stretch target",
            txt.contains("TARGET_STRETCH_MULT: Double = 5.0")
        )
        assertTrue(
            "V5.0.6380: governor must be ADDITIVE (never blocks trades / never mutates state)",
            txt.contains("Additive read-only telemetry module")
        )
    }

    @Test
    fun trajectory_governor_wired_into_bot_loop() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6380: BotService.emitBotLoopTick must call LearningTrajectoryGovernor6380.observe alongside ForensicReconciler",
            txt.contains("LearningTrajectoryGovernor6380.observe(")
        )
    }

    @Test
    fun trajectory_governor_emits_up_down_flat_and_target_labels() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LearningTrajectoryGovernor6380.kt").readText()
        assertTrue(
            "V5.0.6380: governor must emit WALLET_GROWTH_TRAJECTORY_6380 counter for UP/DOWN/FLAT direction",
            txt.contains("WALLET_GROWTH_TRAJECTORY_6380|")
        )
        assertTrue(
            "V5.0.6380: governor must emit WALLET_GROWTH_TARGET_HIT_6380 for 2x and 5x achievements",
            txt.contains("WALLET_GROWTH_TARGET_HIT_6380|2X") &&
                txt.contains("WALLET_GROWTH_TARGET_HIT_6380|5X")
        )
        assertTrue(
            "V5.0.6380: governor must emit WALLET_GROWTH_TARGET_MISS_6380 so misses are counted, not silently swallowed",
            txt.contains("WALLET_GROWTH_TARGET_MISS_6380|")
        )
    }

    @Test
    fun trajectory_governor_bucket_and_lookback_correct() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LearningTrajectoryGovernor6380.kt").readText()
        assertTrue(
            "V5.0.6380: snapshot cadence must be 1/hour so 24 samples cover the daily benchmark",
            txt.contains("SNAPSHOT_BUCKET_MS: Long = 60L * 60L * 1000L")
        )
        assertTrue(
            "V5.0.6380: lookback window must be exactly 24 hours per operator's daily benchmark language",
            txt.contains("LOOKBACK_MS: Long = 24L * 60L * 60L * 1000L")
        )
    }

    @Test
    fun trajectory_governor_observe_is_thread_safe() {
        // The @Synchronized guarantees the hourly bucket check + ArrayDeque
        // mutation is atomic even under concurrent bot-loop invocations.
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LearningTrajectoryGovernor6380.kt").readText()
        assertTrue(
            "V5.0.6380: observe() must be @Synchronized because bot loops can overlap during tactic rotations",
            txt.contains("@Synchronized\n    fun observe(")
        )
    }

    @Test
    fun trajectory_governor_smoke_test() {
        LearningTrajectoryGovernor6380.resetForTest()
        assertEquals(0, LearningTrajectoryGovernor6380.sampleCount())
        // First observe records a snapshot.
        LearningTrajectoryGovernor6380.observe(walletSol = 15.0, startCapitalSol = 11.76)
        assertEquals(1, LearningTrajectoryGovernor6380.sampleCount())
        // Second observe in the same hour bucket must NOT record a second snapshot.
        LearningTrajectoryGovernor6380.observe(walletSol = 16.0, startCapitalSol = 11.76)
        assertEquals(
            "V5.0.6380: back-to-back observes within the same hour must not double-count snapshots",
            1, LearningTrajectoryGovernor6380.sampleCount()
        )
    }
}
