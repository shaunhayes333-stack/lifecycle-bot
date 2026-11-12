package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8721 — a paper crypto attempt is dispatched and confirmed under one lock, never dispatched while waiting for it. */
class Aate8721PaperDispatchUnderLockTest {
    @Test fun thePaperBranchLeavesTheDispatchMarkToTheExecutor() {
        val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val mark = "CanonicalEntryAuthority6551.markDispatch(canonicalCryptoIntent6565)"
        val open = trader.indexOf("CanonicalPaperTransaction6486.open(")
        assertTrue(open > 0)
        assertEquals("only the live branch marks its own dispatch", 1, Regex(Regex.escape(mark)).findAll(trader).count { it.range.first > 0 && !trader.lineAt(it.range.first).trimStart().startsWith("//") })
        assertTrue("and it is after the paper open", trader.indexOf("        $mark") > open || trader.lastIndexOf(mark) > open)
        val paper = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()
        val lock = paper.indexOf("settleDispatchedOpen7809(assetClass, executionIntent, mint) { lock.withLock {")
        val dispatched = paper.indexOf("CanonicalEntryAuthority6551.markDispatch(intent)")
        val confirmed = paper.indexOf("CanonicalEntryAuthority6551.markConfirmed(it, positionId)")
        assertTrue("dispatch and confirm sit inside the one open lock", lock in 1 until dispatched && dispatched < confirmed)
    }

    private fun String.lineAt(i: Int): String = substring(lastIndexOf('\n', i) + 1, indexOf('\n', i).let { if (it < 0) length else it })
}
