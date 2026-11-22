package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8954 — an unnamed quote (or a moved event layout) reads as before: it can never silence the SOL curve tape. */
class Aate8954QuoteGuardTest {
    @Test fun anUnnamedQuoteNeverDropsTheSolTape() {
        val s = File("src/main/kotlin/com/lifecyclebot/network/ChainLaunchFeed8179.kt").readText()
        assertTrue(s.contains("if (kind == QuoteTruth8952.OTHER) { QuoteTruth8952.noteQuote8952(kind, priced = false); return base }"))
        assertTrue(s.indexOf("if (kind == QuoteTruth8952.OTHER)") < s.indexOf("val dec = QuoteTruth8952.decimals8952(kind)"))
    }
}
