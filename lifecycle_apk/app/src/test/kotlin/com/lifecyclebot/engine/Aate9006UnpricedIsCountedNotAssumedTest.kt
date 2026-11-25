package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AgeProof9001
import com.lifecyclebot.engine.truth.PoolLock8895
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9006 — an admitted decision with no price at its horizon is counted beside the priced ones and reported, never
 * turned into a return; label means no longer lift the lane probe limit; a non-DLMM blamed pool names its program.
 */
class Aate9006UnpricedIsCountedNotAssumedTest {
    @Test fun unpricedDecisionsAreCountedAndLeaveTheMeanAlone() {
        AgeProof9001.resetForTest9001()
        repeat(300) { AgeProof9001.note9001("MOONSHOT", 3.0, 10.0, true, true) }
        repeat(100) { AgeProof9001.note9001("MOONSHOT", 3.0, Double.NaN, true, false) }
        repeat(50) { AgeProof9001.note9001("MOONSHOT", 3.0, 500.0, false, false) }     // refused: not an admission
        assertEquals(25, AgeProof9001.unpricedPct9006("MOONSHOT|UNDER_15M"))
        assertEquals(0, AgeProof9001.unpricedPct9006("MOONSHOT|OVER_24H"))
        val line = AgeProof9001.statusLine9001()
        assertTrue(line, line.contains("UNDER_15M=n300/+10.0%/unpriced25%"))
        AgeProof9001.resetForTest9001()
    }

    @Test fun aBlamedPoolThatIsNotDlmmNamesItsProgram() {
        assertEquals("NOT_DLMM_pAMMBay6", PoolLock8895.notDlmmClass9006("pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA"))
        assertEquals("NOT_DLMM_NONE", PoolLock8895.notDlmmClass9006(""))
    }

    @Test fun wired() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
        assertTrue(e.contains("if (!o.optBoolean(\"fill\", false)) { AgeProof9001.note9001(o.optString(\"lane\"), column8801(o, \"age\"), Double.NaN, true, false); return@row }"))
        val r = File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryReceipt8500.kt").readText()
        assertTrue(r.contains("fun ownAgeOpen9004(bandCloses: Double, bandSumPct: Double): Boolean = bandCloses >= 3.0 && bandSumPct / bandCloses >= 0.0"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 191. Unpriced is counted, not assumed (9006)"))
    }
}
