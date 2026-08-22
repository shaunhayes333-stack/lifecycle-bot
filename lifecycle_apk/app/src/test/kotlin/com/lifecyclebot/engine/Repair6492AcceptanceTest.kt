package com.lifecyclebot.engine

import com.lifecyclebot.data.CanonicalTokenMap
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalCapitalAuthority6450
import com.lifecyclebot.engine.truth.CanonicalLotQuantity6464
import com.lifecyclebot.engine.truth.CanonicalPaperReplay6464
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class Repair6492AcceptanceTest {
    @Before fun reset() {
        PaperAccountLedger6430.resetForTest()
        EconomicEventSchema6464.resetForTest()
        CanonicalPaperReplay6464.resetForTest()
        CanonicalLotQuantity6464.resetForTest()
        CanonicalPositionAuthority6441.rebuildPaperFromEvents6486(emptyList())
    }

    @Test fun replay_carry_restores_one_funded_mode_mint_position_and_quantity() {
        val mint = "CarryMint649211111111111111111111111111"
        assertTrue(EconomicEventSchema6464.establishReplayCarry6489(
            cashDeltaSol = -1.2, openCostSol = 1.2, realizedPnlSol = 0.0, feesSol = 0.0,
            perMintQty = mapOf(mint to BigInteger.valueOf(43_350_000_000L)),
            perMintCostSol = mapOf(mint to 1.2),
        ))
        assertEquals(1, CanonicalPositionAuthority6441.rebuildPaperFromEvents6486(emptyList()))
        CanonicalLotQuantity6464.rebuildPaperFromEvents6486(emptyList())

        val projections = CanonicalPositionAuthority6441.activeMintProjections6490("paper")
        assertEquals(1, projections.size)
        assertEquals(mint, projections.single().mint)
        assertEquals(BigInteger.valueOf(43_350_000_000L), projections.single().remainingQtyRaw)
        assertEquals(1.2, projections.single().remainingCostBasisSol, 1e-9)
        val pid = CanonicalPositionAuthority6441.openPositions().single { it.mode == "paper" && it.mint == mint }.positionId
        assertTrue(CanonicalLotQuantity6464.hasFundedOpenLot6485(pid))
    }

    @Test fun missing_quote_keeps_last_good_mark_then_basis_never_zero() {
        val mint = "MarkMint6492111111111111111111111111111"
        PaperAccountLedger6430.initialize(5.0)
        assertTrue(PaperAccountLedger6430.onBuy(1.0, 0.0))
        assertTrue(EconomicEventSchema6464.establishReplayCarry6489(
            cashDeltaSol = -1.0, openCostSol = 1.0, realizedPnlSol = 0.0, feesSol = 0.0,
            perMintQty = mapOf(mint to BigInteger.valueOf(1_000_000_000L)),
            perMintCostSol = mapOf(mint to 1.0),
        ))
        CanonicalPositionAuthority6441.rebuildPaperFromEvents6486(emptyList())
        CanonicalLotQuantity6464.rebuildPaperFromEvents6486(emptyList())

        val fresh = CanonicalCapitalAuthority6450.snapshot { 1.4 }
        val stale = CanonicalCapitalAuthority6450.snapshot { 0.0 }
        assertEquals(1.4, fresh.openMarketValueSol, 1e-9)
        assertEquals(1.4, stale.openMarketValueSol, 1e-9)
        assertEquals(1, stale.staleMarkMints)
        assertTrue(stale.totalEquitySol > stale.cashSol)
    }

    @Test fun token_map_aliases_consume_same_mint_shared_result() {
        val mint = "TokenMapMint649211111111111111111111111"
        val owner = TokenState(mint = mint, symbol = "MAP", pairAddress = "pair6492").also {
            it.lastLiquidityUsd = 50_000.0; it.lastPrice = 0.25; it.lastPriceDex = "RAYDIUM"
            it.tokenMap = CanonicalTokenMap(expectedOutAmount = 1.0, dexRouteOk = true)
        }
        val joinedAlias = TokenState(mint = mint, symbol = "MAP")
        assertEquals("DEX_ROUTABLE", TokenMapAuthority.ensureDiscoveryTokenMap(owner, "DEXSCREENER").routeStatus)
        assertEquals("DEX_ROUTABLE", TokenMapAuthority.ensureDiscoveryTokenMap(joinedAlias, "OTHER_ALIAS").routeStatus)
        assertEquals(owner.tokenMap.canonicalTargetMint, joinedAlias.tokenMap.canonicalTargetMint)
    }

    @Test fun market_cap_requires_explicit_semantics_not_positive_number_only() {
        val wrongLegacy = DynamicAltTokenRegistry.DynToken(
            mint = "cg:maple-finance", symbol = "SYRUP", name = "Maple Finance",
            price = 0.19, mcap = 1_365_145_603.0, source = "restored",
        )
        val trusted = wrongLegacy.copy(mcap = 238_000_000.0, source = "cg_markets:p1", mcapSource = "COINGECKO_MARKET_CAP")
        assertFalse(wrongLegacy.hasTrustedMarketCap6492)
        assertTrue(trusted.hasTrustedMarketCap6492)
        assertNull(MarketDataIntegrity6492.trustedMarketCapUsd(Double.POSITIVE_INFINITY, "SYRUP", "bad", 1_000_000.0))
    }
}
