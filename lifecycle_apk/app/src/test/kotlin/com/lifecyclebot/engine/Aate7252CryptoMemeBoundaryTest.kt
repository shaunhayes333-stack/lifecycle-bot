package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7252CryptoMemeBoundaryTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun immediate_ui_refresh_uses_canonical_projection() {
        val refresh = src("ui/BotViewModel.kt").substringAfter("fun forceRefresh()")
            .substringBefore("fun saveConfig(")
        assertTrue(refresh.contains("CanonicalUiPositionProjection6686.project(status)"))
        assertFalse(refresh.contains("status.openPositions.toList()"))
    }

    @Test fun meme_dashboard_resolves_blank_tags_before_admission() {
        val projection = src("engine/truth/CanonicalUiPositionProjection6686.kt")
        val main = src("ui/MainActivity.kt")
        assertTrue(projection.contains("fun isMemeDashboardOwned7252"))
        assertTrue(projection.contains("lane == \"CRYPTO_ALT\""))
        assertTrue(projection.contains("CanonicalPositionAuthority6441.getPosition(it)"))
        assertTrue(main.contains("isMemeDashboardOwned7252(it)"))
    }

    @Test fun crypto_paper_open_uses_economic_quantity_not_one_token_sentinel() {
        val paper = src("perps/CryptoAltTrader.kt")
            .substringAfter("val paperQuantityScale7252 = 9")
            .substringBefore("} else {\n            // LIVE mode")
        assertTrue(paper.contains("paperRawFromEconomics("))
        assertTrue(paper.contains("qtyRaw = paperQtyRaw7252"))
        assertTrue(paper.contains("quantityScale = paperQuantityScale7252"))
        assertTrue(paper.contains("CRYPTO_PAPER_QUANTITY_WITNESS_MISSING_7252"))
        assertTrue(paper.indexOf("paperQtyRaw7252 <= java.math.BigInteger.ZERO") <
            paper.indexOf("CanonicalPaperTransaction6486.open("))
    }

    @Test fun live_lane_election_filters_identity_contract_failures_before_ticket() {
        val contract = src("engine/LaneEntryContract6342.kt")
        val bot = src("engine/BotService.kt")
            .substringAfter("private fun canonicalCycleLaneFor(")
            .substringBefore("private fun executionBookForLane6494")
        assertTrue(contract.contains("fun isLaneIdentityEligible7252"))
        assertTrue(contract.contains("BLUECHIP") && contract.contains("isPumpFunMint(ts.mint)"))
        assertTrue(bot.contains("eligibleStyleLanes7252"))
        assertTrue(bot.contains("filter { LaneEntryContract6342.isLaneIdentityEligible7252(ts, it.lane) }"))
        assertTrue(bot.contains("identityEligiblePrimary7252"))
        assertTrue(bot.contains("LANE_ELECTION_INELIGIBLE_FILTERED_7252"))
        assertTrue(bot.indexOf("isLaneIdentityEligible7252") < bot.indexOf("identityEligiblePrimary7252"))
    }
}
