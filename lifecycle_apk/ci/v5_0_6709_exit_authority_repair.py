#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/lifecyclebot"
TEST = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/Aate6709ProtectiveExitAuthorityTest.kt"

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    path.write_text(text.replace(old, new, 1))

# 1) AutoModeEngine owns ModeConfig.stopLossPct as a POSITIVE magnitude.
# FluidLearningAI.getFluidStopLoss owns NEGATIVE signed PnL thresholds.
# Convert at the boundary in both directions so later learning-state changes
# can never flip ModeConfig.stopLossPct negative and silently disable stopPx.
auto = SRC / "engine/AutoModeEngine.kt"
old_auto = '''    private fun fluidStop(modeDefaultStop: Double): Double {
        return try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getFluidStopLoss(modeDefaultStop)
        } catch (_: Exception) {
            modeDefaultStop  // Fallback to mode default
        }
    }
'''
new_auto = '''    private fun fluidStop(modeDefaultStop: Double): Double {
        // V5.0.6709 — SIGNED-THRESHOLD / MAGNITUDE AUTHORITY BOUNDARY.
        // AutoModeEngine.ModeConfig stores stopLossPct as a POSITIVE magnitude
        // (SNIPE=12%, RANGE=8%, etc). FluidLearningAI.getFluidStopLoss expects
        // a NEGATIVE PnL threshold. Passing +12 into that API lets the returned
        // sign vary by learning branch; Executor then sees a non-positive mode
        // stop and constructs stopPx=0, disabling the canonical SL scheduler.
        val magnitude6709 = kotlin.math.abs(modeDefaultStop).takeIf { it.isFinite() && it > 0.0 } ?: 10.0
        return try {
            val signed6709 = com.lifecyclebot.v3.scoring.FluidLearningAI.getFluidStopLoss(-magnitude6709)
            kotlin.math.abs(signed6709).takeIf { it.isFinite() && it > 0.0 } ?: magnitude6709
        } catch (_: Exception) {
            magnitude6709
        }
    }
'''
replace_once(auto, old_auto, new_auto, "AUTOMODE_STOP_SIGN_6709")

# 2) Belt-and-braces at the actual canonical trigger calculation. Even if any
# future caller hands riskCheck a signed stop again, stopPx remains executable.
executor = SRC / "engine/Executor.kt"
old_exec = '''            val effStopPct = modeConf?.stopLossPct ?: cfg().stopLossPct
            val stopPx = if (pos.entryPrice > 0.0 && effStopPct > 0.0) pos.entryPrice * (1.0 - effStopPct / 100.0) else 0.0
'''
new_exec = '''            // V5.0.6709 — canonical protective-exit stop uses magnitude authority.
            // ModeConfig historically drifted between signed PnL thresholds and
            // positive percentages. A negative learned value previously made the
            // `effStopPct > 0` predicate false and silently set stopPx=0.
            val effStopPctRaw6709 = modeConf?.stopLossPct ?: cfg().stopLossPct
            val effStopPct = kotlin.math.abs(effStopPctRaw6709)
            if (effStopPctRaw6709 < 0.0) {
                try { PipelineHealthCollector.labelInc("PROTECTIVE_EXIT_STOP_SIGN_NORMALIZED_6709") } catch (_: Throwable) {}
            }
            val stopPx = if (pos.entryPrice > 0.0 && effStopPct.isFinite() && effStopPct > 0.0) pos.entryPrice * (1.0 - effStopPct / 100.0) else 0.0
'''
replace_once(executor, old_exec, new_exec, "EXECUTOR_STOP_MAGNITUDE_6709")

# 3) Source regression lock. This deliberately checks BOTH sides of the type
# boundary so a future overlay cannot fix one and re-break the other.
TEST.write_text(r'''package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.6709: canonical SL sign/magnitude authority must remain coherent. */
class Aate6709ProtectiveExitAuthorityTest {

    @Test
    fun `auto mode passes signed negative threshold to fluid learner and returns magnitude`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/AutoModeEngine.kt").readText()
        val block = src.substringAfter("private fun fluidStop(modeDefaultStop: Double)")
            .substringBefore("private fun fluidTrailing")
        assertTrue(block.contains("getFluidStopLoss(-magnitude6709)"))
        assertTrue(block.contains("kotlin.math.abs(signed6709)"))
        assertFalse(block.contains("getFluidStopLoss(modeDefaultStop)"))
    }

    @Test
    fun `executor canonical stop price cannot be disabled by signed learned stop`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val risk = src.substringAfter("fun riskCheck(ts: TokenState")
            .substringBefore("PROTECTIVE_EXIT_DELIVERY_ERROR_6600")
        assertTrue(risk.contains("effStopPctRaw6709"))
        assertTrue(risk.contains("val effStopPct = kotlin.math.abs(effStopPctRaw6709)"))
        assertTrue(risk.contains("PROTECTIVE_EXIT_STOP_SIGN_NORMALIZED_6709"))
        assertTrue(risk.contains("effStopPct.isFinite() && effStopPct > 0.0"))
    }
}
''')

print("V5.0.6709 protective exit authority repair applied")
