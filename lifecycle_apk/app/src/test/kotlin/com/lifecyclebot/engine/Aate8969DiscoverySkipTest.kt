package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ChainConfirm8950
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.MarketCohort8701
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8969 — discovery chain reads are not spent on coins whose own cohort is clearly losing; decisions are never starved. */
class Aate8969DiscoverySkipTest {
    private fun stat(n: Int, mean: Double, se: Double, lost: Int = 0) =
        ForwardReturnLabeler7731.CellStat("ORIGIN8701_PUMP_FRESH_LT5K", n, mean, 0.1, 0.02, se, lost)

    @Test fun onlyAClearLossOnEnoughResolvedLabelsCounts() {
        assertTrue(MarketCohort8701.clearlyLosingStat8969(stat(1300, -13.6, 1.0)))
        assertFalse("one SE is the door's bar, not this one", MarketCohort8701.clearlyLosingStat8969(stat(1300, -1.5, 1.0)))
        assertFalse("thin", MarketCohort8701.clearlyLosingStat8969(stat(99, -20.0, 1.0)))
        assertFalse("too many labels never resolved", MarketCohort8701.clearlyLosingStat8969(stat(100, -20.0, 1.0, lost = 50)))
        assertFalse(MarketCohort8701.clearlyLosingStat8969(null))
    }

    @Test fun onlyDiscoveryReadsSkipAndNeverForEvidenceOrHoldings() {
        val c = "ORIGIN8701_PUMP_FRESH_LT5K"
        assertEquals(c, ChainConfirm8950.skip8969("STAGE", c, armed = false, pocket = false, held = false))
        assertEquals(c, ChainConfirm8950.skip8969("lane", c, armed = false, pocket = false, held = false))
        for (use in listOf("DECISION", "EVIDENCE", "EXIT", "HOLD", "EXEC", "RUG_VERIFY"))
            assertNull(use, ChainConfirm8950.skip8969(use, c, armed = false, pocket = false, held = false))
        assertNull(ChainConfirm8950.skip8969("STAGE", c, armed = true, pocket = false, held = false))
        assertNull(ChainConfirm8950.skip8969("LANE", c, armed = false, pocket = true, held = false))
        assertNull(ChainConfirm8950.skip8969("LANE", c, armed = false, pocket = false, held = true))
        assertNull("a cohort not clearly losing is read", ChainConfirm8950.skip8969("STAGE", null, armed = false, pocket = false, held = false))
    }

    @Test fun wired() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ChainConfirm8950.kt").readText()
        val body = src.substringAfter("fun confirm8950(").substringBefore("val inputs = inputsOf8950(missing)")
        assertTrue(body.contains("if (skip != null) { noteSkip8969(use, skip); return }"))
        assertTrue(ChainConfirm8950.skipLine8969().startsWith("discoverySkipped8969{"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("ChainConfirm8950.skipLine8969()"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/market/PocketRecovery8961.kt").readText().contains("requested8969[row.mint] = nowMs"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 154. What cannot be traded is not drawn from the chain (8969)"))
    }
}
