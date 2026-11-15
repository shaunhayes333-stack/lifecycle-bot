package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurveShapeFeed8236
import com.lifecyclebot.engine.truth.KeylessMarks8359
import com.lifecyclebot.engine.truth.MemePaperTrader8544
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8808 — owner: "we don't kill things. we make them work properly." The paper meme book's winners paid (trailing
 * stops about +4.7 SOL) while its hard stops closed at -23 % to -62 % on 4-25 % stops and ~800 positions never saw a price
 * after the buy: its open positions shared 40-a-read curve marks with ~400 shadow tickets. They are now their own class of
 * the same feed and ride the wallet's per-trade ticks.
 */
class Aate8808PaperBookMarksTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theBooksPositionsAreReadBeforeEveryShadowTicket() {
        KeylessMarks8359.resetForTests8359()
        val now = System.currentTimeMillis()
        val shadow = "Shadow1111111111111111111111111111111pump"; val book = "Book11111111111111111111111111111111pump"
        KeylessMarks8359.putWantedForTests8359(shadow, now + 60_000L)
        KeylessMarks8359.putBookForTests8808(book, now + 60_000L)
        KeylessMarks8359.putBookForTests8808("Closed111111111111111111111111111111pump", now - 1L)
        assertTrue(KeylessMarks8359.isBook8808(book, now)); assertFalse(KeylessMarks8359.isBook8808(shadow, now))
        assertTrue("the pair lane and the curve read treat a book mint as wanted", KeylessMarks8359.isWanted8359(book, now))
        assertEquals(listOf(book, shadow), KeylessMarks8359.wantedMints8359(now))
        assertEquals(listOf("HeldMint", book, shadow), KeylessMarks8359.withWantedCurves8359(listOf("HeldMint"), 40))
        assertEquals("held, then the book, within the read's cap", listOf("HeldMint", book), KeylessMarks8359.withWantedCurves8359(listOf("HeldMint"), 2))
        assertEquals(listOf("B", "S1", "S2"), KeylessMarks8359.bookFirst8808(listOf("B"), listOf("S1", "B", "S2")))
        assertTrue(KeylessMarks8359.statusLine8359(now).contains("book8808{open=1 "))
        KeylessMarks8359.resetForTests8359()
        assertFalse(KeylessMarks8359.isBook8808(book, now))
    }

    @Test fun theBooksOpenCoinsRideTheWalletsTradeTicks() {
        assertEquals(listOf("H1", "H2", "P1", "P2"), CurveShapeFeed8236.heldWithBook8808(linkedSetOf("H1", "H2"), listOf("P1", "H2", "", "P2")).toList())
        assertEquals("at most 40 book coins beside the held ones", 41, CurveShapeFeed8236.heldWithBook8808(setOf("H"), List(60) { "P$it" }).size)
        assertTrue(MemePaperTrader8544.openMints8808().all { it.isNotBlank() })
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("CurveShapeFeed8236.heldWithBook8808(curveMints7278, paperOpen8808), status.tokens.values, System.currentTimeMillis())"))
        assertTrue(bot.contains("CurveShapeFeed8236.heldWithBook8808(openMints.take(60).toSet(), paperOpen8808))"))
        assertTrue("the paid PumpPortal stream stays the wallet's only", bot.contains("com.lifecyclebot.network.PumpFunWS.syncTradeSubscriptions7278(curveMints7278)"))
        assertTrue(src("engine/truth/MemePaperTrader8544.kt").contains("KeylessMarks8359.noteBook8808(mint, until)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 15. The paper book prices its own positions (8808)"))
    }
}
