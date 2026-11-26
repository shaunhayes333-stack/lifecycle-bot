package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.9036 — the 26 Nov 23:38 report: `CanonicalPositionAuthority6441.hasOpenMint` was 2.7 % of every running stack, a walk
 * of every ledger row (3,120 on the phone) for one mint. The ledger now files each row id under its mint as it is written.
 */
class Aate9036MintIndexTest {
    private fun open(mint: String, id: String, qty: Long, mode: String) = CanonicalPositionAuthority6441.openPosition(
        idempotencyKey = "OPEN9036:$id", positionId = id, mint = mint, symbol = "M9036", lane = "SHITCOIN", runId = "r9036",
        entryCostSol = 0.1, openedQtyRaw = BigInteger.valueOf(qty), tokenDecimals = 0, feesSol = 0.0,
        paperMode = mode == "paper", modeOverride = mode,
    )

    @Test fun theIndexAnswersExactlyWhatTheFullWalkAnswered() {
        val sfx = System.nanoTime().toString()
        val a = "MintA9036$sfx"; val b = "MintB9036$sfx"; val never = "MintNever9036$sfx"
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, open(a, "LOT-A:$sfx", 100L, "paper"))
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, open(b, "LOT-B:$sfx", 50L, "paper"))
        assertTrue(CanonicalPositionAuthority6441.hasOpenMint(a))
        assertTrue(CanonicalPositionAuthority6441.hasOpenMint(a, "paper"))
        assertFalse("an opposite-mode holding is never ownership", CanonicalPositionAuthority6441.hasOpenMint(a, "live"))
        assertFalse(CanonicalPositionAuthority6441.hasOpenMint(never))
        assertEquals("LOT-B:$sfx", CanonicalPositionAuthority6441.firstOpenForMint(b, "paper")?.positionId)
        // the same answer the full walk gives, for every mint the ledger holds
        for (p in CanonicalPositionAuthority6441.openPositions()) assertTrue(p.mint, CanonicalPositionAuthority6441.hasOpenMint(p.mint, p.mode))
        // a row sold to nothing is no longer open, through the index as through the walk
        val sold = CanonicalPositionAuthority6441.partialSell("SELL9036:$sfx", "LOT-A:$sfx", BigInteger.valueOf(100L), 0.2, 0.1, 0.0, paperMode = true)
        assertTrue(sold.name, sold == CanonicalPositionAuthority6441.MutateResult.APPLIED)
        assertFalse(CanonicalPositionAuthority6441.hasOpenMint(a, "paper"))
        assertTrue(CanonicalPositionAuthority6441.hasOpenMint(b, "paper"))
    }

    @Test fun anIdIsFiledBeforeItsRowAndAStaleIdIsSkipped() {
        val m = CanonicalPositionAuthority6441.MintIndexed9036()
        assertTrue(m.ofMint9036("X").isEmpty())
        assertEquals(0, m.indexedMints9036())
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        val put = src.indexOf("override fun put(key: String, value: Position): Position? {")
        assertTrue(put > 0)
        val body = src.substring(put, put + 300)
        assertTrue("filed before the row is written", body.indexOf("byMint.computeIfAbsent(value.mint)") in 0 until body.indexOf("return super.put(key, value)"))
        assertTrue("a lookup checks the row it gets back", src.contains("if (p != null && p.mint == mint) out.add(p)"))
        assertTrue(src.contains("private val positions = MintIndexed9036()"))
        // no per-mint lookup walks the whole ledger any more
        for (f in listOf("fun hasOpenMint(mint: String): Boolean = positions.ofMint9036(mint).any {",
                "fun hasOpenMint(mint: String, mode: String): Boolean = positions.ofMint9036(mint).any {",
                "val values = positions.ofMint9036(mint)"))
            assertTrue(f, src.contains(f))
    }

    @Test fun theBuildIsRecorded() {
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §221 — V5.0.9036: one mint, one lookup"))
        assertTrue(File("../../.github/workflows/build.yml").readText().contains("--tests '*Aate9036*'"))
    }
}
