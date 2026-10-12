package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8078 — the Open Positions card and the detail sheet paint one basis and one mark. */
class Aate8078PositionDisplayTruthTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun oneViewUsesCanonicalRemainingBasisAndVerifiedExitMark() {
        val v = src("engine/truth/PositionDisplayTruth8078.kt")
        assertTrue(v.contains("CanonicalPositionAuthority6441.getPosition(it)"))
        assertTrue(v.contains("canon.entryCostSol - canon.soldCostBasisSol"))
        assertTrue(v.contains("CanonicalPriceMarkRegistry6522.get(ts.mint, CanonicalMarkPurpose6570.EXIT_ECONOMIC)"))
        assertTrue(v.contains("entryPrice = entryUsd, currentPrice = markUsd"))
        assertFalse(v.contains(".registerPosition("))
        assertFalse(v.contains("position.entryPrice ="))
    }

    @Test fun cardAndDetailSheetConsumeTheSameView() {
        val ui = src("ui/MainActivity.kt")
        val pop = src("ui/MemePositionPopout.kt")
        assertTrue(ui.contains("PositionDisplayTruth8078.view(ts)"))
        assertTrue(ui.contains("gainPct = if (view8078.basisOk) view8078.pnlPct else 0.0"))
        assertTrue(ui.contains("\"Mark \$mk · \${view8078.markTag} \$age\""))
        assertTrue(ui.contains("val entryToRender6634 = view8078?.takeIf { it.canonicalBasis }?.entryUsd"))
        assertTrue(pop.contains("PositionDisplayTruth8078.view(t)"))
        assertTrue(pop.contains("current = view8078?.markUsd ?: t.ref"))
        assertTrue(pop.contains("qty = view8078?.qty ?: pos.qtyToken"))
    }
}
