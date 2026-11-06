package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.PaperPortfolioDisplay8521
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8581 — owner: the paper meme trader's open positions render on the same cards as live. */
class Aate8581PaperCardsLikeLiveTest {
    private fun ts(mint: String, asset: AssetClass, paper: Boolean) =
        TokenState(mint, position = Position(qtyToken = 1.0, isPaperPosition = paper, canonicalAssetClassTag = asset.tag))

    @Test fun a_paper_book_shows_its_memes_on_the_live_cards() {
        val meme = ts("m1", AssetClass.SOLANA_TOKEN, true)
        val alt = ts("a1", AssetClass.CRYPTO_ALT, true)
        assertEquals(listOf(meme), PaperPortfolioDisplay8521.memeCardsLikeLive8581(listOf(meme, alt)))
        val onlyAlt = listOf(alt)
        assertSame(onlyAlt, PaperPortfolioDisplay8521.memeCardsLikeLive8581(onlyAlt))     // no meme: the portfolio rows stay
        val live = listOf(ts("l1", AssetClass.SOLANA_TOKEN, false), alt)
        assertSame(live, PaperPortfolioDisplay8521.memeCardsLikeLive8581(live))          // a live / mixed list is untouched
        assertTrue(PaperPortfolioDisplay8521.memeCardsLikeLive8581(emptyList()).isEmpty())
    }

    @Test fun the_main_panel_routes_paper_memes_to_the_live_renderer() {
        val ui = File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        val live = ui.substringAfter("private fun renderOpenPositions(")
        assertTrue(live.contains("val positions = com.lifecyclebot.engine.truth.PaperPortfolioDisplay8521.memeCardsLikeLive8581(positionsIn8581)"))
        assertTrue(live.indexOf("memeCardsLikeLive8581(") < live.indexOf("if (renderPaperPortfolio8521(positions)) return"))
        val paper = ui.substringAfter("private fun renderPaperPortfolio8521(").substringBefore("private fun openPaperPosition8521(")
        assertTrue(paper.contains("PaperPortfolioDisplay8521.assetClass8521(it) == com.lifecyclebot.engine.truth.AssetClass.SOLANA_TOKEN }) return false"))
    }
}
