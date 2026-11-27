package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AuthorityTable8887
import com.lifecyclebot.engine.truth.ResearchRows8877
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Integration with the production authority parser/grouping/grader and the shared pocket-row reader. */
class Aate9043AuthorityCohortTest {
    private fun fixture(name: String): String = javaClass.getResourceAsStream("/aate9043/$name")?.bufferedReader()?.use { it.readText() }
        ?: File("src/test/resources/aate9043/$name").readText()

    @Test fun formerlyPays81SurvivorDoorIncludesAll321OriginalRefusals() {
        val source = fixture("lp_door_321.jsonl").lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
        val rows = source.mapNotNull { AuthorityTable8887.row8887(it) }
        assertEquals(321, rows.size)
        val cells = AuthorityTable8887.table8887(3_600_000L, rows).first
        val doors = cells.filter { it.factor == "door" }
        assertEquals(1, doors.size)
        val door = doors.single()
        assertEquals("LPSTATE8725_LP_BLOCKED_LT30_NOT_DLMM_CPAMDPZC", door.value)
        assertEquals(321, door.n)
        assertEquals(231, door.unpriced)
        assertEquals(28.037383177570092, door.coveragePct9038, 0.00000001)
        assertEquals(26.430194744444445, door.mean, 0.00000001)
        assertEquals(-64.55228184735203, door.worstMean9038, 0.00000001)
        assertEquals(-66.8439983427673, door.exTop3, 0.00000001)
        assertEquals(AuthorityTable8887.Verdict8887.UNPROVEN, door.verdict)
        assertEquals(90, source.count { ResearchRows8877.rowNet8983(it).isFinite() })
    }

    @Test fun giantVaultArtifactCannotBecomeObservedMeanOrPocketLearningValue() {
        val raw = JSONObject(fixture("unverified_vault_extreme.json"))
        val row = AuthorityTable8887.row8887(raw)!!
        assertTrue(row.net.isNaN())
        assertTrue(row.quarantined9043)
        assertTrue(ResearchRows8877.rowNet8983(raw).isNaN())
        val cells = AuthorityTable8887.table8887(3_600_000L, listOf(row)).first
        assertTrue(cells.isNotEmpty())
        assertTrue(cells.all { it.unpriced == 1 && it.quarantined9043 == 1 && it.mean.isNaN() })
        assertTrue(cells.all { it.verdict == AuthorityTable8887.Verdict8887.UNPROVEN })
        // -100 belongs solely to the explicitly named worst-case stress calculation.
        assertTrue(cells.all { it.worstMean9038 == -100.0 })
    }

    @Test fun writerKeepsFullOriginalIdentityBeforeItsLegacyReasonColumnTruncates() {
        val original = "LPSTATE8725_LP_BLOCKED:LT30/NOT_DLMM_" + "LongProtocol".repeat(8)
        val wrapped = "NO_PRICE_JUP_8884:LOSTMARK8983/" + original
        val line = ResearchRows8877.line8877(1L, 3_600_000L, "mint", "CORE", false, "BASE_START", "NONE",
            1.0, 50.0, 50_000.0, 20_000.0, 2.0, Double.NaN, Double.NaN, 0.0, 0.0, 0.0, wrapped)
        val row = JSONObject(line)
        assertEquals(original, row.getString("originalReason9043"))
        assertEquals(80, row.getString("reason").length)
        assertEquals(original, AuthorityTable8887.row8887(row)!!.reason)
        assertEquals(1L to 3_600_000L, AuthorityTable8887.head8887(line))
    }
}
