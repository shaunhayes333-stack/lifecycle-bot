package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.CanonicalUiPositionProjection6686
import org.junit.Assert.assertEquals
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
        val header = main.substringAfter("val openModel6078 = displayedOpen8521")
            .substringBefore("renderOpenPositions(openPos, preSorted6078 = true)")
        val bindHeader = main.substringAfter("private fun bindOpenHeader8521(")
            .substringBefore("private fun renderPaperPortfolio8521(")

        assertTrue(models.contains("val canonicalAssetClassTag: String = \"\""))
        assertTrue(projection.contains("canonicalAssetClassTag = p.assetClass.tag"))
        assertTrue(projection.contains("isMemeDashboardOwned7252"))
        assertTrue(builder.contains("isMemeDashboardOwned7252(it)"))
        assertTrue(builder.contains("isDashboardOwned8521(ts, isPaperMode)"))
        assertTrue(header.contains("val openPos = openModel6078.allSorted"))
        assertTrue(header.contains("bindOpenHeader8521(openModel6078)"))
        assertTrue(bindHeader.contains("model.totalExposureSol.fastFixed(3)"))
        assertTrue(bindHeader.contains("model.totalUnrealizedSol.fastSigned(4)"))
        assertFalse(header.contains("acct7047.openMarketSol.fastFixed(3)"))
        assertFalse(bindHeader.contains("acct7047.openMarketSol.fastFixed(3)"))

        // The 7247 ownership boundary remains on LIVE. The PAPER portfolio
        // deliberately includes every canonical asset family, in its own mode.
        for (asset in AssetClass.values().filter { it != AssetClass.UNKNOWN }) {
            val live = TokenState("owned-${asset.tag}", position = Position(qtyToken = 1.0,
                isPaperPosition = false, canonicalAssetClassTag = asset.tag))
            val paper = live.copy(position = live.position.copy(isPaperPosition = true))
            assertEquals(asset == AssetClass.SOLANA_TOKEN,
                CanonicalUiPositionProjection6686.isDashboardOwned8521(live, false))
            assertFalse(CanonicalUiPositionProjection6686.isDashboardOwned8521(live, true))
            assertTrue(CanonicalUiPositionProjection6686.isDashboardOwned8521(paper, true))
            assertFalse(CanonicalUiPositionProjection6686.isDashboardOwned8521(paper, false))
        }
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
