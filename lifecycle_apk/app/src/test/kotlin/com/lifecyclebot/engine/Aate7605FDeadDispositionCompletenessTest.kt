package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7605FDeadDispositionCompletenessTest {
    @Test
    fun `every F DEAD ledger row has exactly one explicit disposition`() {
        val source = File("../../../ci/UNWIRED_LEDGER.tsv")
        val disposition = File("../../audits/f_dead_disposition_7605.tsv")
        assertTrue(source.exists())
        assertTrue(disposition.exists())

        val fDead = source.readLines().drop(1).filter { it.startsWith("F_DEAD\t") }
        val rows = disposition.readLines().drop(1).filter { it.isNotBlank() }

        assertEquals(1458, fDead.size)
        assertEquals(1458, rows.size)
        assertFalse(rows.any { it.contains("\tUNCLASSIFIED\t") })

        val keys = rows.map {
            val c = it.split('\t')
            c[1] + "\t" + c[2] + "\t" + c[3]
        }
        assertEquals(keys.size, keys.toSet().size)

        val sourceKeys = fDead.map {
            val c = it.split('\t')
            c[1] + "\t" + c[2] + "\t" + c[3]
        }.toSet()
        assertEquals(sourceKeys, keys.toSet())
    }

    @Test
    fun `audit declares zero unresolved F DEAD rows`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("F_DEAD unresolved count: 0 / 1,458"))
        assertTrue(audit.contains("1,458 / 1,458 classified"))
    }
}
