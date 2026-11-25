package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AgeProof9001
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9005 — the age record (9001 / 9003 / 9004 all read it) is memory only; it is filled from the labelled dataset at
 * every start, not only when the edge model is rebuilt, and the report says how it was filled.
 */
class Aate9005AgeRecordSeededAtStartTest {
    @Test fun theReportNamesHowTheRecordWasFilled() {
        AgeProof9001.resetForTest9001()
        assertTrue(AgeProof9001.statusLine9001().contains("seed9005=pending"))
        AgeProof9001.noteSeed9005("seeded:5890/62192")
        assertTrue(AgeProof9001.statusLine9001().contains("seed9005=seeded:5890/62192"))
        AgeProof9001.resetForTest9001()
    }

    @Test fun wired() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
        assertTrue(e.contains("if (current) { val ds9005 = File(f.parentFile, DATASET_FILE); io.execute { seedAgeProof9005(ds9005) } } else AgeProof9001.noteSeed9005(\"replayed\")"))
        assertTrue(e.contains("if (!o.optBoolean(\"admitted\")) return@row"))   // superseded by V5.0.9006: unpriced admitted rows are counted too
        assertTrue(e.contains("if (!replayAccept8642(o)) return@row"))
        assertTrue(e.contains("AgeProof9001.note9001(o.optString(\"lane\"), column8801(o, \"age\"), column8801(o, \"net\"), true, true)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 190. The age record is filled at every start (9005)"))
    }
}
