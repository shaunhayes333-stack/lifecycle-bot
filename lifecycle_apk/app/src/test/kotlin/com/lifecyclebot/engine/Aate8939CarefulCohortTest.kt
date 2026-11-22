package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8939 — the careful path takes the research rows' proven cohort ($10k-$20k off pump.fun, a +10 %+ forecast, a lane
 * carrying the pocket's evidence) without asking the coin's V3 >= 23; every other caller keeps the V3 test; a pocket miss is
 * named. RugCheck, the LP rule, the mint checks, the flow test, size and budget are unchanged.
 */
class Aate8939CarefulCohortTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theCarefulPathAsksItsCohortAndNamesItsMiss() {
        val pocket = src("engine/truth/AgreementPocket8874.kt")
        assertTrue("the V3 test is kept unless the careful path waives it", pocket.contains("requireV3: Boolean = true"))
        assertTrue(pocket.contains("else if (requireV3 && !(base.v3 >= V3_MIN_8874)) null to \"V3\""))
        assertTrue(pocket.contains("inPocket8874(ts.mint, base.mcap, if (requireV3) base.v3 else V3_MIN_8874, it)"))
        val explore = src("engine/truth/ExploreLive8733.kt")
        assertTrue(explore.contains("AgreementPocket8874.pocketVerdict8939(ts, CAP_LO_USD_8928, nowMs, requireV3 = false)"))
        assertTrue(explore.contains("why8928(\"NOT_IN_POCKET_\$pocketWhy8939\")"))
        assertTrue("the rest of the path is unchanged", explore.contains("tokenRefusal8936(proof)?.let { why8928(it); return false }") &&
            explore.contains("const val MAX_OPEN_8733 = 4") && explore.contains("const val MAX_PER_DAY_8733 = 30") &&   // 8940
            explore.contains("private const val MAX_SOL_8733 = 0.05"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 127. The careful path takes its proven cohort (8939)"))
    }
}
