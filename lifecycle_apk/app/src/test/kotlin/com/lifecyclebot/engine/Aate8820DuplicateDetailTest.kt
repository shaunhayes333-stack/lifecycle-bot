package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8820 — a duplicate found at restore names its lots (lane, asset class, position, open time, quantity, lifecycle). */
class Aate8820DuplicateDetailTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun noDuplicateIsADash() {
        assertEquals("-", CanonicalPaperTransaction6486.duplicateDetail8820(emptyList()))
    }

    @Test fun theBootLineAndTheRepairLineCarryTheLots() {
        val tx = src("engine/truth/CanonicalPaperTransaction6486.kt")
        assertTrue(tx.contains("lots8820=\${duplicateDetail8820(groups.values)}"))
        assertTrue(tx.contains("return DuplicateMintRepair6490(groups.size, refunded, basisTotal, failures, duplicateDetail8820(groups.values))"))
        val boot = src("engine/BotService.kt")
        // the CI device check reads "events= positions= duplicateMints=" at the start of this line; the detail follows it
        assertTrue(boot.contains("duplicateMints=\${inventoryRepair6490.duplicateMints} dupLots8820=\${inventoryRepair6490.detail8820.replace(' ', '_')} durMs="))
    }
}
