package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PrintWitness8750
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8805 — the 5.0.8802 report: TAPE_PRINT_PHYSICS_REFUSED_8801 = 10,649 in 44 minutes, HELIUS_CURVE_8354:CURVE_PHYSICS =
 * 9,724. The curve bound assumed every pump.fun coin holds 1e9 tokens; mayhem-mode coins launch with 2e9, so their real
 * curve prices read as half a curve. The bound now uses the coin's own verified supply, and no supply is no judgement.
 */
class Aate8805RealSupplyTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aMayhemCurvePriceIsACurvePrice() {
        val sol = 73.0
        assertNull("a 2e9-supply coin at launch: 28 SOL of cap", PrintWitness8750.physicsRefusal8801(1.4e-8, true, 2e9, sol, null))
        assertEquals("the same price at 1e9 is half a curve", "CURVE_PHYSICS", PrintWitness8750.physicsRefusal8801(1.4e-8, true, 1e9, sol, null))
        assertNull("supply unknown: no curve judgement", PrintWitness8750.physicsRefusal8801(1.4e-8, true, 1e9, sol, null, curveSupplyWhole = Double.NaN))
        assertEquals("CURVE_PHYSICS", PrintWitness8750.physicsRefusal8801(1.23e-2, true, 1e9, sol, null, curveSupplyWhole = 2e9))
        val w = src("engine/truth/PrintWitness8750.kt")
        assertTrue(w.contains("MintSupply8418.wholeOrZero8418(mint)"))
        assertTrue(w.contains("curveSupplyWhole = known ?: Double.NaN"))
    }

    @Test fun theDesksSayWhatTheyAreDoing() {
        assertTrue(src("xstocks/XsApi.kt").contains("fun line(): String = \"api{gecko ok="))
        assertTrue(src("xstocks/XsDesk.kt").contains("\${XsApi.line()}"))
        assertTrue(src("hyperliquid/HlDesk.kt").contains("status = \"loading history \${i + 1}/\${chosen.size}\""))
    }
}
