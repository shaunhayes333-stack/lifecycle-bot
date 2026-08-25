package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QuantityInvariantRepair6521Test {
    @Test fun `quantity invariant uses canonical raw and never mutable current SOL price`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/QuantityInvariantAuthority6500.kt").readText()
        assertTrue(src.contains("CanonicalPositionAuthority6441.openPositions()"))
        assertTrue(src.contains("remainingQtyRaw.toBigDecimal().movePointLeft"))
        assertTrue(src.contains("reconstructFromCanonical"))
        assertFalse(src.contains("WalletManager.lastKnownSolPrice"))
        assertFalse(src.contains("HistoricalEconomicQuarantine6496.reportOrphanLot"))
    }

    @Test fun `startup repairs projection and never force closes repairable position`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(src.contains("QUANTITY_PROJECTION_RECONSTRUCTED_FROM_CANONICAL_RAW_6521"))
        assertTrue(src.contains("PositionPersistence.savePosition(ts)"))
        assertTrue(src.contains("QUANTITY_REPAIR_DEFERRED_NO_FORCE_CLOSE_6521"))
        assertFalse(src.contains("requestSell(ts = ts, reason = \"INVARIANT_QUARANTINE_6500\""))
    }
}
