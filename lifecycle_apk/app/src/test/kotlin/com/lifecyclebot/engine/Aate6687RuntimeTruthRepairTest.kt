package com.lifecyclebot.engine

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class Aate6687RuntimeTruthRepairTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun finalLiveSizeFloorIsAfterRealisticSizing() {
        val s = src("engine/Executor.kt")
        val realistic = s.indexOf("sol = realisticSol")
        val floor = s.indexOf("LIVE_FINAL_EXECUTABLE_FLOOR_RESTORED_6687")
        val impact = s.indexOf("val assumedSolUsd = 200.0", realistic)
        assertTrue(realistic >= 0 && floor > realistic && impact > floor)
    }

    @Test fun runnerBasisAcceptsMatchingTxEconomics() {
        val s = src("engine/truth/EntryPriceIntegrityAuthority6405.kt")
        assertTrue(s.contains("txEconomicsMatch6687"))
        assertTrue(s.contains("entrySource in TRUSTED_SOURCES || txEconomicsMatch6687"))
    }

    @Test fun staleLaneHardSeedsAreGone() {
        val s = src("engine/LaneAutoPauseGuard.kt")
        assertFalse(s.contains("Triple(\"QUALITY\", \"hard_seed_"))
        assertFalse(s.contains("Triple(\"BLUECHIP\", \"hard_seed_"))
        assertFalse(s.contains("Triple(\"EXPRESS\", \"hard_seed_"))
        assertTrue(s.contains("LANE_HARD_SEED_PATCH_ROT_PURGED_6687"))
    }

    @Test fun liveTreasuryUsesEffectiveAuthorityEverywherePatched() {
        assertFalse(src("engine/SmartSizer.kt").contains("val treasuryFloor = TreasuryManager.treasurySol"))
        val guard = src("engine/SecurityGuard.kt")
        assertTrue(guard.contains("TreasuryManager.effectiveLockedSol(walletSol, c.paperMode)"))
        val main = src("ui/MainActivity.kt")
        assertTrue(main.contains("effectiveLockedSol(liveWalletForTreasury6687, isPaperMode = false)"))
    }

    @Test fun openPositionUiCannotRegressToDuplicateChipOrUnboundedMoneyColumn() {
        val s = src("ui/MainActivity.kt")
        assertFalse(s.contains("LIVE LIVE\$laneChip"))
        assertTrue(s.contains("(132f * resources.displayMetrics.density).toInt()"))
    }
}
