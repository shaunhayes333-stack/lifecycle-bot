package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6381 — Paper size-floor recalibration invariants.
 *
 * Operator's V5.0.6375 snapshot repeatedly showed:
 *   PAPER_BUY_SIZE_CLAMPED requested=0.021340 clamped=0.117600 min=0.117600
 * The 0.02 minimum floor was clamping learning-shrunk sizes (0.021 SOL for
 * a low-confidence bucket) back UP to 0.1176 SOL — a 5.5× override that
 * directly nullified everything the LiveProbabilityEngine + TacticSwitcher
 * had learned about that bucket. Lowered to 0.005 so learning's shape
 * signal actually survives clamp.
 */
class Bundle6381PaperSizeFloorTest {

    @Test
    fun paper_size_floor_lowered_to_survive_learning_shrink() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "V5.0.6381/6511: paper executable minimum remains independently bounded to [0.005, 0.15]",
            txt.contains("PaperPreTicketSizeFloor6511.boundedMinimum(runtimeMinimum6511)")
        )
        val minimumBlock = txt.substring(txt.indexOf("private fun minConfiguredPaperTradeSol"), txt.indexOf("private fun clampPaperTradeSol"))
        assertTrue("V5.0.6511: ordinary smallBuySol target must not authorize the executable floor", !minimumBlock.contains("c.smallBuySol"))
    }
}
