package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441 as Authority
import com.lifecyclebot.engine.truth.CanonicalUiPositionProjection6686 as Projection
import com.lifecyclebot.engine.truth.PaperPortfolioDisplay8521 as Paper
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class Aate8521PaperPortfolioProjectionTest {
    private val now = 1_800_000_000_000L

    private fun canonical(id: String, paper: Boolean, asset: AssetClass, mint: String = id) = Authority.Position(
        positionId = id, mode = if (paper) "paper" else "live", mint = mint, symbol = "SAME", lane = asset.tag,
        runId = "test", openedAtMs = now - 10_000L, entryCostSol = 1.0,
        remainingQtyRaw = BigInteger("1000000000"), originalQtyRaw = BigInteger("1000000000"),
        soldCostBasisSol = 0.0, realizedPnlSol = 0.0, realizedProceedsSol = 0.0, feesSol = 0.0,
        tokenDecimals = 9, quantityScale = 9, lifecycle = Authority.Lifecycle.OPEN,
        lastMutationMs = now, quarantineReason = "", entryPriceUsd = 100.0,
        entryPriceSource = "OBSERVED", entryPoolAddress = "pool", assetClass = asset)

    @Test fun allSixtySevenCanonicalPaperPositionsRemainVisibleAcrossAssetFamiliesAndWalletFlags() {
        val families = AssetClass.values().filter { it != AssetClass.UNKNOWN }
        val paper = List(67) { canonical("P-$it", true, families[it % families.size]) }
        val live = paper.map { it.copy(positionId = "L-${it.positionId}", mode = "live") }
        val rows = Projection.projectRows8521(paper + live, emptyMap()) { true }
        assertEquals(67, rows.size)
        assertTrue(rows.all { Projection.isDashboardOwned8521(it, true) })
        assertTrue(rows.none { Projection.isDashboardOwned8521(it, false) })
        val display = Paper.rows8521(rows, emptyMap(), now)
        assertEquals(67, display.size)
        assertEquals(families.toSet(), display.map { it.assetClass }.toSet())
        assertTrue(display.all { it.markPriceUsd == null && it.pnlPct == null && it.pnlSol == null })
    }

    @Test fun aSameMintLiveProjectionCannotSupplyPaperEntryRoutePeaksOrPolicy() {
        val paper = canonical("paper-id", true, AssetClass.SOLANA_TOKEN, "same-mint")
        val live = canonical("live-id", false, AssetClass.SOLANA_TOKEN, "same-mint")
        val existing = TokenState("same-mint", position = Position(qtyToken = 10.0, entryPrice = 900.0,
            costSol = 9.0, positionId = "live-id", isPaperPosition = false, highestPrice = 999.0,
            entryPolicySnapshot = "LIVE_POLICY", lastRoutePrice = 777.0))
        val rows = Projection.projectRows8521(listOf(paper, live), mapOf(existing.mint to existing)) { false }
        assertEquals(2, rows.size)
        val paperRow = rows.single { it.position.isPaperPosition }
        assertEquals("paper-id", paperRow.position.positionId)
        assertEquals(100.0, paperRow.position.entryPrice, 0.0)
        assertEquals(100.0, paperRow.position.highestPrice, 0.0)
        assertEquals(0.0, paperRow.position.lastRoutePrice, 0.0)
        assertEquals("", paperRow.position.entryPolicySnapshot)
        assertEquals("LIVE_POLICY", rows.single { !it.position.isPaperPosition }.position.entryPolicySnapshot)
        assertEquals(900.0, existing.position.entryPrice, 0.0)
    }

    @Test fun closedLotsAndOtherModesDoNotReappearAndIdsDoNotCollapseBySymbol() {
        val a = canonical("A", true, AssetClass.CRYPTO_ALT, "crypto|solana")
        val b = canonical("B", true, AssetClass.STOCK, "STOCK")
        val closed = canonical("C", true, AssetClass.FOREX).copy(lifecycle = Authority.Lifecycle.CLOSED)
        val rows = Projection.projectRows8521(listOf(a, a, b, closed), emptyMap()) { false }
        assertEquals(setOf("A", "B"), rows.map { it.position.positionId }.toSet())
        assertEquals(2, Paper.rows8521(rows + rows.first().copy(position = rows.first().position.copy(isPaperPosition = false)), emptyMap(), now).size)
        val live = Projection.projectRows8521(listOf(a.copy(mode = "live"), canonical("L", false, AssetClass.SOLANA_TOKEN)), emptyMap()) { false }
        assertEquals(listOf("L"), live.filter { Projection.isDashboardOwned8521(it, false) }.map { it.position.positionId })
    }

    @Test fun ownerSignedLeveragedPnlIsNotRecomputedAsALongSpotReturn() {
        val ts = Projection.projectPosition8521(canonical("short", true, AssetClass.PERPS), null)!!
        val owner = Paper.OwnerMark("short", true, 100.0, 90.0, now - 1_000L, 50.0)
        val row = Paper.rows8521(listOf(ts), mapOf("short" to owner), now).single()
        assertEquals(90.0, row.markPriceUsd!!, 0.0)
        assertEquals(50.0, row.pnlPct!!, 0.0)
        assertEquals(0.5, row.pnlSol!!, 0.0)
        val partial = ts.copy(position = ts.position.copy(costSol = 0.4))
        assertEquals(0.2, Paper.rows8521(listOf(partial), mapOf("short" to owner), now).single().pnlSol!!, 0.0)
        val zero = Paper.rows8521(listOf(ts), mapOf("short" to owner.copy(pnlPct = 0.0)), now).single()
        assertEquals(0.0, zero.pnlSol!!, 0.0)
    }

    @Test fun liveWrongBasisAndStaleOwnerMarksCannotBecomeCurrentPaperPnl() {
        val ts = Projection.projectPosition8521(canonical("P", true, AssetClass.CRYPTO_ALT), null)!!
        val owner = Paper.OwnerMark("P", true, 100.0, 90.0, now - 1_000L, -10.0)
        for (invalid in listOf(owner.copy(paper = false), owner.copy(entryPriceUsd = 1.0),
            owner.copy(markAtMs = now - 61_000L), owner.copy(markAtMs = now + 1L), owner.copy(markPriceUsd = 0.0))) {
            val row = Paper.rows8521(listOf(ts), mapOf("P" to invalid), now).single()
            assertNull(row.pnlPct)
            assertNull(row.pnlSol)
        }
    }

    @Test fun aSelectedPaperLotCannotResolveToLiveOrAReentryWithTheSameMint() {
        val paper = Position(qtyToken = 0.5, positionId = "selected", isPaperPosition = true)
        assertTrue(Projection.matchesPosition8521(paper, "selected", true))
        assertFalse(Projection.matchesPosition8521(paper.copy(isPaperPosition = false), "selected", true))
        assertFalse(Projection.matchesPosition8521(paper.copy(positionId = "reentry"), "selected", true))
        assertFalse(Projection.matchesPosition8521(paper.copy(qtyToken = 0.0), "selected", true))
    }
}
