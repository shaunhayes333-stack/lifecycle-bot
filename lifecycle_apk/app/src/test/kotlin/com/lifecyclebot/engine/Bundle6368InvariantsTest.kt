package com.lifecyclebot.engine

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * V5.0.6368 — Bundle invariants.
 *
 * P0.1: Magnitude Awareness Downstream
 *   - RetrainingDecay.noteOutcome MUST have a 5-arg magnitude overload
 *     accepting pnlPct: Double
 *   - ExplorationBudget MUST expose onLaneOutcome(lane, pnlPct) and apply
 *     the magnitude multiplier inside allowPaperMicroTrade
 *   - V3JournalRecorder MUST pass pnlPctLearn to both
 *
 * P0.2: Centralized Locale-Free Format Helpers in ForensicLogger
 *   - fmt1/fmt2/fmt4/fmtPct/fmtUsd/fmtInt MUST exist and use Locale.ROOT
 *
 * P0.3: Zero-Liq LANE_EVAL Suppression at source
 *   - ForensicLogger MUST maintain a zeroLiqQuarantine ConcurrentHashMap
 *   - lifecycle("REJECTED_FATAL_V3", "ZERO_LIQUIDITY") MUST populate it
 *   - phase(LANE_EVAL, symbol, …) MUST short-circuit when quarantined
 *
 * P0.4: Report Builder Timeout raised
 *   - PipelineHealthActivity watchdog delay MUST be raised from 8_000L to
 *     20_000L while retaining the `full_builder_timeout_8s` label token
 *     (Golden Tape asserts the label literal)
 */
class Bundle6368InvariantsTest {

    @Test
    fun retrainingDecay_has_magnitude_overload() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/learning/ExplorationBudget.kt").readText()
        assertTrue(
            "V5.0.6368: RetrainingDecay.noteOutcome must have a magnitude-aware 5-arg overload accepting pnlPct: Double",
            txt.contains("fun noteOutcome(lane: String, scoreBand: String, isWin: Boolean, isLoss: Boolean, pnlPct: Double)"),
        )
        // Legacy 4-arg overload preserved for Golden Tape callers
        assertTrue(
            "V5.0.6368: legacy 4-arg RetrainingDecay.noteOutcome must remain (Golden Tape)",
            txt.contains("fun noteOutcome(lane: String, scoreBand: String, isWin: Boolean, isLoss: Boolean)"),
        )
        // Compounded decay steps for magnitude
        assertTrue(
            "V5.0.6368: catastrophic |pnl|>=50% must compound 4× decay steps",
            txt.contains("mag >= 50.0 -> 4"),
        )
    }

    @Test
    fun explorationBudget_has_onLaneOutcome_and_magnitude_mult() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/learning/ExplorationBudget.kt").readText()
        assertTrue(
            "V5.0.6368: ExplorationBudget must expose onLaneOutcome(lane, pnlPct)",
            txt.contains("fun onLaneOutcome(lane: String, pnlPct: Double)"),
        )
        assertTrue(
            "V5.0.6368: allowPaperMicroTrade must apply the magnitude multiplier",
            txt.contains("peekLaneMagnitudeMult(lane)") && txt.contains("val ceiling = (budget.maxPaperMicroTradesPerHour * mult).toInt()"),
        )
        assertTrue(
            "V5.0.6368: magnitude floor / ceiling constants must be present",
            txt.contains("MAG_MULT_MIN = 0.25") && txt.contains("MAG_MULT_MAX = 1.0"),
        )
    }

    @Test
    fun v3JournalRecorder_passes_magnitude_to_downstream_learning() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()
        assertTrue(
            "V5.0.6368: V3JournalRecorder must pass pnlPctLearn to RetrainingDecay",
            txt.contains("RetrainingDecay.noteOutcome(layer, bandL, isWinL, isLossL, pnlPctLearn)"),
        )
        assertTrue(
            "V5.0.6368: V3JournalRecorder must feed magnitude to ExplorationBudget",
            txt.contains("ExplorationBudget.onLaneOutcome(layer, pnlPctLearn)"),
        )
        // Golden Tape compat: substring `RetrainingDecay.noteOutcome(` still present
        assertTrue(
            "V5.0.6368: RetrainingDecay.noteOutcome( substring preserved for older tests",
            txt.contains("RetrainingDecay.noteOutcome("),
        )
    }

    @Test
    fun forensicLogger_has_locale_free_format_helpers() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/ForensicLogger.kt").readText()
        assertTrue(
            "V5.0.6368: ForensicLogger must declare Locale.ROOT for its shared LR field",
            txt.contains("java.util.Locale.ROOT"),
        )
        for (helper in listOf("fun fmt1(", "fun fmt2(", "fun fmt4(", "fun fmtPct(", "fun fmtUsd(", "fun fmtInt(")) {
            assertTrue("V5.0.6368: ForensicLogger must expose $helper helper", txt.contains(helper))
        }
        assertTrue(
            "V5.0.6368: ForensicLogger helpers must format via LR (Locale.ROOT)",
            txt.contains("String.format(LR,"),
        )
    }

    @Test
    fun forensicLogger_zero_liq_quarantine_present() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/ForensicLogger.kt").readText()
        assertTrue(
            "V5.0.6368: ForensicLogger must maintain a zeroLiqQuarantine map",
            txt.contains("private val zeroLiqQuarantine = ConcurrentHashMap<String, Long>()"),
        )
        assertTrue(
            "V5.0.6368: lifecycle(REJECTED_FATAL_V3, …ZERO_LIQUIDITY…) must populate the quarantine",
            txt.contains("event == \"REJECTED_FATAL_V3\"") && txt.contains("fields.contains(\"ZERO_LIQUIDITY\")") && txt.contains("quarantineSymbol(sym)"),
        )
        assertTrue(
            "V5.0.6368: phase(LANE_EVAL, …) must short-circuit for quarantined symbols",
            txt.contains("p == PHASE.LANE_EVAL && isZeroLiqQuarantined(symbol)") &&
                txt.contains("LANE_EVAL_SUPPRESSED_ZERO_LIQ_6368"),
        )
    }

    @Test
    fun report_builder_watchdog_raised_to_20s_and_label_token_retained() {
        val txt = File("src/main/kotlin/com/lifecyclebot/ui/PipelineHealthActivity.kt").readText()
        assertTrue(
            "V5.0.6368: report-builder watchdog must be raised to 20_000L so the FULL dump has room",
            txt.contains("}, 20_000L)"),
        )
        // Golden Tape (V5_0_6308_pipeline_report_generation_has_watchdog_fallback_and_main_clipboard)
        // still asserts the label token literal — keep the exact substring intact.
        assertTrue(
            "V5.0.6368: `full_builder_timeout_8s` label token MUST remain (Golden Tape V5.0.6308)",
            txt.contains("full_builder_timeout_8s"),
        )
    }

    @Test
    fun pipelineHealthCollector_dumptext_uses_locale_root() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(
            "V5.0.6368: dumpText SimpleDateFormat must use Locale.ROOT to skip default-locale clone lock",
            txt.contains("SimpleDateFormat(\"HH:mm:ss.SSS\", Locale.ROOT)"),
        )
    }
}
