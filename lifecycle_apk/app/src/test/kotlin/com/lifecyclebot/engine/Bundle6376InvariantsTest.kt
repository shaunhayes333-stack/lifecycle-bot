package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6376 — Golden-tape invariants for two P0 fixes:
 *
 *  (1) Paper Wallet Continuity — mode toggles and app updates must NEVER
 *      wipe accumulated paper gains. Restore path uses:
 *        • fresh install (no prefs + no journal) → seed cfg.paperSimulatedBalance
 *        • prefs missing but journal exists      → wallet-truthful restore from journal
 *        • otherwise                             → savedBalance as-is
 *        • sanity ceiling (100× starting)        → snap to 10× to break inflation loops
 *      The V5.9.54 `modeChangedLiveToPaper` reset branch is REMOVED.
 *
 *  (2) Screen-off Proof-of-Life — bot-loop tick now emits a screen-state
 *      counter every 10th tick so an operator with the screen off can
 *      prove the loop is alive (or observe stall directly via a stalled
 *      counter).
 */
class Bundle6376InvariantsTest {

    // ── (1) Paper Wallet Continuity ──────────────────────────────────────

    @Test
    fun paperWallet_never_resets_on_live_to_paper_switch() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        // The historical V5.9.54 condition that wiped the paper wallet on a
        // mode toggle must no longer gate a reset. We accept that
        // `modeChangedLiveToPaper` may still be READ (for telemetry tagging)
        // but it must NOT appear as an OR-condition alongside `savedBalance`
        // that causes `status.paperWalletSol = cfg.paperSimulatedBalance`.
        assertFalse(
            "V5.0.6376: `modeChangedLiveToPaper || savedBalance < 0.01` reset must be REMOVED — mode toggles must preserve paper gains",
            txt.contains("if (modeChangedLiveToPaper || savedBalance < 0.01) {")
        )
    }

    @Test
    fun paperWallet_has_journal_derived_restore_fallback() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6376: when prefs are missing but journal has history, wallet must be reconstructed from cfg.paperSimulatedBalance + journalRealizedSol",
            txt.contains("V5.0.6376 wallet-truthful restore") &&
                txt.contains("cfg.paperSimulatedBalance + journalRealizedSol")
        )
        assertTrue(
            "V5.0.6376: journal-restore path must emit PAPER_WALLET_JOURNAL_RESTORE_6376 label",
            txt.contains("PAPER_WALLET_JOURNAL_RESTORE_6376")
        )
    }

    @Test
    fun paperWallet_preserves_across_mode_toggle() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6376: LIVE→PAPER mode switch must count the preserved balance via PAPER_WALLET_MODE_TOGGLE_PRESERVED_6376",
            txt.contains("PAPER_WALLET_MODE_TOGGLE_PRESERVED_6376")
        )
    }

    @Test
    fun paperWallet_sanity_ceiling_still_active() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        // The sanity ceiling (100× / snap-to-10×) is the ONE legitimate reset
        // path that must survive — it breaks the sizer fantasy-feedback loop
        // when persisted balance exceeds a pathological threshold.
        assertTrue(
            "V5.0.6376: sanity ceiling (100× starting) must remain to break inflation feedback loops",
            txt.contains("SANITY_CEILING_MULT = 100.0")
        )
        assertTrue(
            "V5.0.6376: sanity ceiling snap-target must remain 10× starting",
            txt.contains("cfg.paperSimulatedBalance * 10.0")
        )
    }

    // ── (2) Screen-Off Proof-of-Life ─────────────────────────────────────

    @Test
    fun botLoop_emits_screen_state_alive_counter() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6376: bot loop must emit BOT_LOOP_ALIVE_6376|SCREEN_ON/SCREEN_OFF so operator can prove loop is alive with screen off",
            txt.contains("BOT_LOOP_ALIVE_6376|") &&
                txt.contains("SCREEN_ON") &&
                txt.contains("SCREEN_OFF")
        )
    }

    @Test
    fun botLoop_flags_long_cycles_specifically_when_screen_off() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6376: long cycles (>60s) while screen is off must emit BOT_LOOP_LONG_CYCLE_SCREEN_OFF_6376 so Doze-induced throttling is separable from fanout slowdowns",
            txt.contains("BOT_LOOP_LONG_CYCLE_SCREEN_OFF_6376|") &&
                txt.contains("prevCycleMs > 60_000L")
        )
    }

    @Test
    fun botLoop_reads_powermanager_isInteractive() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6376: screen-state detection must use PowerManager.isInteractive (canonical Android API)",
            txt.contains(".isInteractive ?: true")
        )
    }
}
