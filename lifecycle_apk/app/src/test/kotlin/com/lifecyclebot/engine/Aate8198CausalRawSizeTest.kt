package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 8198 P4/P5 — a real sizing with missing lineage is recorded raw, never as executable-size truth, never erased. */
class Aate8198CausalRawSizeTest {
    private val f = SpecialistCausalFunnel6625
    private val key = SpecialistCausalFunnel6625.CausalKey("run1", "LIVE", "mBLUE", "BLUECHIP", 6551L, "intent-8198")

    @Before fun setUp() = f.resetForTest()
    @After fun tearDown() = f.resetForTest()

    @Test fun anUnlineagedSizeIsRawVisibleButNotExecutableTruth() {
        assertFalse(f.executablePredecessorsReady7810(key))
        f.stamp6625(key, Stage.SIZE, ToolkitSignalSheet.SIZE_UNLINEAGED_8198)
        val snap = f.laneSnapshot6647("BLUECHIP")
        assertEquals(1, snap.rawCounts7086[Stage.SIZE] ?: 0)                  // the physical stage is not hidden (raw)
        assertEquals(0, snap.counts[Stage.SIZE] ?: 0)                         // nor promoted to validated executable size
        assertEquals(0, snap.phantomSizedOnly)                                // and never a phantom executable size
        assertTrue(snap.outcomes.keys.any { it == ToolkitSignalSheet.SIZE_UNLINEAGED_8198 })
        assertFalse(snap.outcomes.keys.any { it == "SIZED_EXECUTABLE" })       // no fabricated executable size
        f.stamp6625(key, Stage.TICKET, "TICKET")
        assertTrue(f.laneSnapshot6647("BLUECHIP").outcomes.keys.any { it.startsWith("ORPHAN_TICKET_") })  // validated chain still broken, by name
    }

    @Test fun theSheetStampsRawInsteadOfReturningSilently() {
        val sheet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        val i = sheet.indexOf("stamp6625(key, causalStage, SIZE_UNLINEAGED_8198)")
        assertTrue(i > 0)
        val between = sheet.substring(sheet.lastIndexOf("EXECUTABLE_SIZE_WITHHELD_MISSING_PREDECESSOR_7810", i), i)
        val code = between.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse(Regex("\\breturn\\b").containsMatchIn(code))               // the raw stamp lands before the withhold returns
        assertTrue(sheet.substring(i, i + 200).contains("return"))
    }
}
