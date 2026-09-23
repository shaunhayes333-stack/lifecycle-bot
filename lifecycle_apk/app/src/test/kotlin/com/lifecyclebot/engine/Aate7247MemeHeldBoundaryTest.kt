package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7247MemeHeldBoundaryTest {
    private fun src(path: String) =
        File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun memetrader_open_card_excludes_explicit_cross_asset_rows() {
        val models = src("data/Models.kt")
        val projection = src("engine/truth/CanonicalUiPositionProjection6686.kt")
        val main = src("ui/MainActivity.kt")
        val builder = main.substringAfter("private fun buildUnifiedOpenPositions")
            .substringBefore("private fun countLaneHeldPositions")
        val header = main.substringAfter("val openModel6078 = cachedOpenPositionsModel6078")
            .substringBefore("renderOpenPositions(openPos, preSorted6078 = true)")

        assertTrue(models.contains("val canonicalAssetClassTag: String = \"\""))
        assertTrue(projection.contains("canonicalAssetClassTag = p.assetClass.tag"))
        assertTrue(projection.contains("isMemeDashboardOwned7252"))
        assertTrue(builder.contains("isMemeDashboardOwned7252(it)"))
        assertTrue(header.contains("openModel6078.totalExposureSol.fastFixed(3)"))
        assertFalse(header.contains("acct7047.openMarketSol.fastFixed(3)"))
    }

    @Test fun restored_held_mints_cannot_reenter_discovery_during_startup() {
        val registry = src("engine/GlobalTradeRegistry.kt")
        val bot = src("engine/BotService.kt")
        val init = registry.substringAfter("fun init(initialWatchlist")
            .substringBefore("// WATCHLIST OPERATIONS")
        val probation = registry.substringAfter("fun addWithProbation(")
            .substringBefore("// PROBATION ROUTING DECISION")
        val startup = bot.substringAfter("GlobalTradeRegistry.init(preScanCfg.watchlist, \"CONFIG_PRESCAN\")")
            .substringBefore("GlobalTradeRegistry.isPaperMode")

        assertTrue(init.contains("HELD_CONFIG_RESTORE_BLOCKED_7247"))
        assertTrue(init.contains("HeldPositionSupervisor7246.isHeld(mint)"))
        assertTrue(probation.contains("HELD_PROBATION_READMISSION_BLOCKED_7247"))
        assertTrue(probation.contains("handoffOpenMintToHeld7246(mint, symbol)"))
        assertTrue(startup.contains("HeldPositionSupervisor7246.reconcileDiscoveryResidency()"))
    }

    @Test fun held_health_reads_exit_projection_but_never_displays_suppressed_pnl() {
        val supervisor = src("engine/HeldPositionSupervisor7246.kt")
        val heldUi = src("ui/WatchlistActivity.kt")

        assertTrue(supervisor.contains("BotService.status.tokens[p.mint]"))
        assertTrue(supervisor.contains("MarkIdentityExecutionGate7230.isExecutionSuppressed7243(p.mint)"))
        assertTrue(supervisor.contains("suppressedRefresh="))
        assertTrue(heldUi.contains("row.markState != \"SUPPRESSED_REFRESH\""))
    }
}
