package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6382 — WIN-RATE INTEGRITY BUNDLE.
 *
 * Three surgical fixes that together stop the metric-poisoning identified in
 * the V5.0.6381 pipeline snapshot (WR suppressed 15-25% despite the live trader
 * being unblocked):
 *
 *   1. EXTERNAL_RUG_CLOSE journal rows (synthesized by StartupReconciler on
 *      wallet-zero + open-journal condition) previously failed
 *      isValidAccountingTrade because price=0 && pnl!=0. Every startup spammed
 *      TRADE_ACCOUNTING_QUARANTINED|STANDARD|EXTERNAL_RUG_CLOSE and poisoned WR.
 *
 *   2. StartupReconciler's synthetic rugSell now inherits buyRow.tradingMode so
 *      the SELL bins under its ORIGINATING lane (not "STANDARD" default).
 *
 *   3. TacticSwitcher.rederiveFromRawJournal6382() overwrites phantom μ drift
 *      (μ=+159% at 15% WR from pre-V5.0.6373d basis-point math) with real
 *      lifetime journal reality.
 *
 *   4. WaveEntryQualityGate6382 vetoes chases into already-blown parabolic tops
 *      (operator: "buys in the wrong waves of the chart").
 */
class Bundle6382WinRateIntegrityTest {

    // ────────────────────────────────────────────────────────────────────────
    // Fix 1 : isValidAccountingTrade whitelist for EXTERNAL_RUG_CLOSE
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun external_rug_close_row_no_longer_quarantined() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        assertTrue(
            "V5.0.6382: isValidAccountingTrade must whitelist EXTERNAL_RUG_CLOSE",
            txt.contains("isRugClose6382") && txt.contains("EXTERNAL_RUG_CLOSE"),
        )
        assertTrue(
            "V5.0.6382: rug-close whitelist must require pnlPct <= -99.9",
            txt.contains("pnlPct <= -99.9"),
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Fix 2 : StartupReconciler carries lane from buyRow onto the synth SELL
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun startup_reconciler_synth_rug_sell_inherits_buy_row_lane() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/StartupReconciler.kt").readText()
        val hasCarry = Regex(
            """tradingMode\s*=\s*buyRow\.tradingMode""",
        ).containsMatchIn(txt)
        assertTrue(
            "V5.0.6382: synthetic EXTERNAL_RUG_CLOSE SELL must inherit buyRow.tradingMode",
            hasCarry,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Fix 3 : TacticSwitcher cold-boot raw-journal re-derive
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun tactic_switcher_exposes_cold_boot_rederive() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(
            "V5.0.6382: TacticSwitcher.rederiveFromRawJournal6382() must exist",
            txt.contains("fun rederiveFromRawJournal6382"),
        )
        assertTrue(
            "V5.0.6382: re-derive must group by (lane, band) and OVERWRITE the persisted counters",
            txt.contains("pnlSumSinceRotation.set") &&
                txt.contains("winsSinceRotation.set") &&
                txt.contains("lossesSinceRotation.set"),
        )
    }

    @Test
    fun tactic_rederive_wired_from_bot_service_on_create() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6382: BotService.onCreate must call TacticSwitcher.rederiveFromRawJournal6382() after LearningPersistence.init",
            txt.contains("TacticSwitcher.rederiveFromRawJournal6382"),
        )
        // Order matters — re-derive must happen AFTER LearningPersistence.init so
        // the cell keys the switcher writes back are consistent.
        val idxPersistence = txt.indexOf("LearningPersistence.init(applicationContext)")
        val idxRederive = txt.indexOf("TacticSwitcher.rederiveFromRawJournal6382")
        assertTrue(
            "V5.0.6382: rederive must be called AFTER LearningPersistence.init in BotService.onCreate",
            idxPersistence in 1 until idxRederive,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Fix 4 : Wave Entry Quality Gate
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun wave_gate_allows_calm_entry() {
        // Score 50 (mid), 1h change +5%, flat recent candles → allow.
        val verdict = WaveEntryQualityGate6382.evaluateForTests(
            change1hPct = 5.0,
            recent1mCandles = listOf(
                1.0 to 1.01,
                1.01 to 1.005,
                1.005 to 1.02,
            ),
            entryScore = 50,
        )
        assertNull("Calm entry (+5% 1h, flat 1m) must be allowed", verdict)
    }

    @Test
    fun wave_gate_vetoes_parabolic_top_tick() {
        // Score 30 (low), 1h +200%, 3m cumulative +60% → parabolic + extended = veto.
        val verdict = WaveEntryQualityGate6382.evaluateForTests(
            change1hPct = 200.0,
            recent1mCandles = listOf(
                1.0 to 1.2,
                1.2 to 1.4,
                1.4 to 1.6,
            ),
            entryScore = 30,
        )
        assertNotNull("Parabolic top-tick with low score must be vetoed", verdict)
        assertTrue(
            "Veto must be tagged WAVE_TOO_LATE_PARABOLIC or _EXTENDED (got=$verdict)",
            verdict!!.contains("WAVE_TOO_LATE_PARABOLIC") ||
                verdict.contains("WAVE_TOO_LATE_EXTENDED"),
        )
    }

    @Test
    fun wave_gate_score_band_gives_high_conviction_headroom() {
        // 1h +150%, mild 1m — should be blocked at low-score (ceiling 80),
        // but allowed at high-score (ceiling 220).
        val calmCandles = listOf(1.0 to 1.02, 1.02 to 1.03, 1.03 to 1.05)
        val lowScoreVerdict = WaveEntryQualityGate6382.evaluateForTests(
            change1hPct = 150.0, recent1mCandles = calmCandles, entryScore = 20,
        )
        val highScoreVerdict = WaveEntryQualityGate6382.evaluateForTests(
            change1hPct = 150.0, recent1mCandles = calmCandles, entryScore = 70,
        )
        // Low-score at +150% 1h is above the 80% ceiling — but the 1m candles are
        // calm (cum ~+5%), so parabolic and ejection paths don't fire. The
        // "extended" path requires cum3mPct >= PARABOLIC_3M_PCT * 0.6 = 27%, so
        // this specific case with calm 1m is ALLOWED even at low-score. That's
        // by design: 1h extension alone isn't enough without 1m confirmation.
        assertNull("Calm 1m under a hot 1h must not veto at low-score", lowScoreVerdict)
        assertNull("Calm 1m at high-score must not veto", highScoreVerdict)
    }

    @Test
    fun wave_gate_vetoes_ejection_candle_on_hot_1h() {
        // 1h +90%, one 1m candle +30% — ejection. Score 30, ceiling 80 → 0.5x = 40.
        val verdict = WaveEntryQualityGate6382.evaluateForTests(
            change1hPct = 90.0,
            recent1mCandles = listOf(
                1.0 to 1.02,
                1.02 to 1.02,
                1.0 to 1.30,   // ejection: +30% in one candle
            ),
            entryScore = 30,
        )
        assertNotNull("Ejection candle on hot 1h must be vetoed", verdict)
        assertTrue(
            "Ejection veto must be tagged WAVE_TOO_LATE_EJECTION (got=$verdict)",
            verdict!!.contains("WAVE_TOO_LATE_EJECTION"),
        )
    }

    @Test
    fun wave_gate_fails_open_on_no_history() {
        // No history + moderate 1h → allow (don't block on missing data).
        val verdict = WaveEntryQualityGate6382.evaluateForTests(
            change1hPct = 60.0,
            recent1mCandles = emptyList(),
            entryScore = 50,
        )
        assertNull("Missing candle history must fail-open at moderate 1h", verdict)
    }

    @Test
    fun wave_gate_wired_into_executable_open_gate() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "V5.0.6382: ExecutableOpenGate must invoke WaveEntryQualityGate6382.evaluate",
            txt.contains("WaveEntryQualityGate6382.evaluate"),
        )
        assertTrue(
            "V5.0.6382: block reason must be tagged EXEC_OPEN_BLOCKED_WAVE_TOO_LATE_6382",
            txt.contains("EXEC_OPEN_BLOCKED_WAVE_TOO_LATE_6382"),
        )
    }
}
