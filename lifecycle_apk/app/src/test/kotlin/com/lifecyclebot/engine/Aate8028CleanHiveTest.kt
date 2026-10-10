package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HiveEdge8000
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8028 — a clean hive in the same database: new tables, build-tagged rows, history baselined out. */
class Aate8028CleanHiveTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun newTablesOnlyAndOldOnesUntouched() {
        assertEquals("hive_edge_8028", HiveEdge8000.EDGE_TABLE_8028)
        assertEquals("hive_runners_8028", HiveEdge8000.RUNNER_TABLE_8028)
        val code = src("engine/truth/HiveEdge8000.kt").lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse(code.contains("hive_edge_8000"))
        assertFalse(code.contains("hive_runners_8000"))
        assertFalse(code.contains("DROP TABLE") || code.contains("DELETE FROM"))
        assertTrue(code.contains("build = excluded.build"))
    }

    @Test fun historyBeforeTheSwitchNeverUploads() {
        val local = mapOf("FRL|a" to doubleArrayOf(40.0, 120.0, 900.0, 10.0, 2.0, 300.0))
        val base = HiveEdge8000.baseline8028(local)
        assertArrayEquals(local.getValue("FRL|a"), base.getValue("FRL|a"), 0.0)
        assertNull(HiveEdge8000.delta8000(local.getValue("FRL|a"), base["FRL|a"]))   // nothing old goes up
        val later = doubleArrayOf(45.0, 150.0, 1100.0, 12.0, 2.0, 300.0)
        assertArrayEquals(doubleArrayOf(5.0, 30.0, 200.0, 2.0, 0.0, 300.0), HiveEdge8000.delta8000(later, base["FRL|a"]), 1e-9)
    }
}
