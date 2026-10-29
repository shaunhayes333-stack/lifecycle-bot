package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.LiquidityTruth8037
import com.lifecyclebot.engine.truth.MintSupply8418
import com.lifecyclebot.engine.truth.MintSupply8418.Source8418
import com.lifecyclebot.engine.truth.MintSupply8418.Supply8418
import com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075
import com.lifecyclebot.engine.truth.PumpBandEntry8118
import com.lifecyclebot.engine.truth.TokenMetricsAuthority7069
import com.lifecyclebot.engine.truth.TrustedMcap8019
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.8418 — the mint account's supply, decimals, slot and observation time are kept end to end, never overwritten by
 * a weaker source or a default, and every market cap is verified supply x an observed price or UNKNOWN (no 1e9 guess).
 */
class Aate8418MintSupplyTest {

    private val pumpMint = "Aate8418PumpMintAAAAAAAAAAAAAAAAAAAAAAApump"
    private val oneBillionRaw = BigInteger("1000000000000000")   // 1e9 whole tokens at 6 decimals

    @Before fun reset() { MintSupply8418.resetForTest8418() }

    private fun tokenSupplyResult(amount: String, decimals: Int, slot: Long) = JSONObject()
        .put("context", JSONObject().put("slot", slot))
        .put("value", JSONObject().put("amount", amount).put("decimals", decimals).put("uiAmountString", "x"))

    private fun accountInfoResult(owner: String, type: String, supply: String, decimals: Int, slot: Long) = JSONObject()
        .put("context", JSONObject().put("slot", slot))
        .put("value", JSONObject().put("owner", owner).put("data", JSONObject().put("program", "spl-token")
            .put("parsed", JSONObject().put("type", type).put("info", JSONObject()
                .put("supply", supply).put("decimals", decimals).put("isInitialized", true)
                .put("mintAuthority", JSONObject.NULL).put("freezeAuthority", JSONObject.NULL)))))

    @Test fun eachMintAccountReadKeepsSupplyDecimalsSlotAndTime() {
        val ts = MintSupply8418.fromTokenSupplyResult8418(pumpMint, tokenSupplyResult("1000000000000000", 6, 301L), 5_000L)!!
        assertEquals(oneBillionRaw, ts.rawAmount)
        assertEquals(6, ts.decimals)
        assertEquals(301L, ts.slot)
        assertEquals(5_000L, ts.observedAtMs)
        assertEquals(Source8418.TOKEN_SUPPLY, ts.source)
        assertEquals(1e9, ts.wholeTokens, 0.0)

        val acct = MintSupply8418.fromAccountInfoResult8418(pumpMint,
            accountInfoResult(MintSupply8418.TOKEN_2022_PROGRAM_8418, "mint", "123456789", 3, 777L), 6_000L)!!
        assertEquals(BigInteger("123456789"), acct.rawAmount)
        assertEquals(3, acct.decimals)
        assertEquals(777L, acct.slot)
        assertEquals(Source8418.MINT_ACCOUNT, acct.source)
        assertEquals(123_456.789, acct.wholeTokens, 1e-9)
        // Not a token-program mint account, or not a mint at all: no supply.
        assertNull(MintSupply8418.fromAccountInfoResult8418(pumpMint, accountInfoResult("11111111111111111111111111111111", "mint", "5", 0, 1L), 1L))
        assertNull(MintSupply8418.fromAccountInfoResult8418(pumpMint, accountInfoResult(MintSupply8418.SPL_TOKEN_PROGRAM_8418, "account", "5", 0, 1L), 1L))

        val das = MintSupply8418.fromDasAsset8418(JSONObject().put("id", pumpMint)
            .put("token_info", JSONObject().put("supply", 999_000_000_000_000L).put("decimals", 6)), 7_000L)!!
        assertEquals(BigInteger("999000000000000"), das.rawAmount)
        assertEquals(0L, das.slot)
        assertEquals(Source8418.DAS, das.source)
        // A DAS item that does not state decimals is not a supply.
        assertNull(MintSupply8418.fromDasAsset8418(JSONObject().put("id", pumpMint).put("token_info", JSONObject().put("supply", 5L)), 1L))
    }

    @Test fun baseUnitsSurviveExactlyWhereADoubleWouldNot() {
        val s = MintSupply8418.fromTokenSupplyResult8418("Big8418", tokenSupplyResult("999999999999999999", 9, 9L), 1L)!!
        assertEquals(BigInteger("999999999999999999"), s.rawAmount)
        assertEquals(999_999_999.999999999, s.wholeTokens, 1e-6)
        // Zero, negative, fractional or missing amounts are not a supply.
        assertNull(MintSupply8418.fromTokenSupplyResult8418("Z", tokenSupplyResult("0", 6, 1L), 1L))
        assertNull(MintSupply8418.fromTokenSupplyResult8418("Z", tokenSupplyResult("-5", 6, 1L), 1L))
        assertNull(MintSupply8418.fromTokenSupplyResult8418("Z", tokenSupplyResult("1.5", 6, 1L), 1L))
        assertNull(MintSupply8418.fromTokenSupplyResult8418("Z", tokenSupplyResult("100", 40, 1L), 1L))
        assertNull(MintSupply8418.fromTokenSupplyResult8418("Z", JSONObject(), 1L))
    }

    @Test fun aWeakerSourceAnOlderSlotOrADefaultNeverOverwrites() {
        fun rec(raw: String, slot: Long, at: Long, src: Source8418) = Supply8418("M", BigInteger(raw), 6, slot, at, src)
        val chainAt100 = rec("1000", 100L, 10L, Source8418.TOKEN_SUPPLY)
        val dasLater = rec("2000", 0L, 99L, Source8418.DAS)
        assertTrue(MintSupply8418.replaces8418(null, chainAt100))
        assertFalse("DAS never overwrites a mint-account read", MintSupply8418.replaces8418(chainAt100, dasLater))
        assertTrue("a mint-account read overwrites DAS", MintSupply8418.replaces8418(dasLater, chainAt100))
        assertFalse("an older slot never overwrites", MintSupply8418.replaces8418(chainAt100, rec("5000", 90L, 50L, Source8418.MINT_ACCOUNT)))
        assertFalse("the same slot is the same state", MintSupply8418.replaces8418(chainAt100, rec("5000", 100L, 50L, Source8418.MINT_ACCOUNT)))
        assertTrue("a later slot (e.g. a burn) replaces", MintSupply8418.replaces8418(chainAt100, rec("900", 120L, 11L, Source8418.MINT_ACCOUNT)))
        assertFalse("a slotless read never replaces a slotted one", MintSupply8418.replaces8418(chainAt100, rec("5000", 0L, 99L, Source8418.TOKEN_SUPPLY)))
        assertFalse("an invalid offer is nothing", MintSupply8418.replaces8418(null, rec("0", 1L, 1L, Source8418.MINT_ACCOUNT)))

        // End to end through the store: DAS first, the chain read wins, a later DAS answer is kept out.
        assertTrue(MintSupply8418.accept8418(MintSupply8418.fromDasAsset8418(JSONObject().put("id", pumpMint)
            .put("token_info", JSONObject().put("supply", 2_000_000_000_000_000L).put("decimals", 6)), 1_000L), notifyMetrics = false))
        assertEquals(2e9, MintSupply8418.wholeOrZero8418(pumpMint), 0.0)
        assertFalse(MintSupply8418.chainRead8418(pumpMint))
        assertTrue(MintSupply8418.noteAccountInfo8418(pumpMint,
            accountInfoResult(MintSupply8418.SPL_TOKEN_PROGRAM_8418, "mint", "1000000000000000", 6, 400L), 2_000L))
        assertTrue(MintSupply8418.chainRead8418(pumpMint))
        assertFalse(MintSupply8418.noteDas8418(JSONObject().put("id", pumpMint)
            .put("token_info", JSONObject().put("supply", 7L).put("decimals", 0)), 3_000L))
        val held = MintSupply8418.of8418(pumpMint)!!
        assertEquals(400L, held.slot)
        assertEquals(2_000L, held.observedAtMs)
        assertEquals(1e9, held.wholeTokens, 0.0)
        // Every supply reader sees the mint account's record.
        assertEquals(1e9, OnChainSupplyAuthority7075.supplyOf7075(pumpMint), 0.0)
        assertEquals(held, TokenMetricsAuthority7069.supplyRecord8418(pumpMint))
    }

    @Test fun theMetricsAuthorityCannotBeHandedAWeakerNumber() {
        val m = "Metrics8418Mint"
        MintSupply8418.accept8418(Supply8418(m, BigInteger("5000000000"), 6, 50L, 1L, Source8418.TOKEN_SUPPLY), notifyMetrics = true)
        // A caller offering a different number while the mint account's record stands changes nothing.
        assertFalse(TokenMetricsAuthority7069.acceptOnChainSupply7075(m, 123.0))
        assertFalse(TokenMetricsAuthority7069.acceptOnChainSupply7075(m, 5_000.0))
    }

    @Test fun theRowCarriesTheRecordAndItsDecimals() {
        val row = TokenState(mint = pumpMint, symbol = "P")
        assertNull(MintSupply8418.stamp8418(row))
        MintSupply8418.accept8418(Supply8418(pumpMint, oneBillionRaw, 6, 10L, 1L, Source8418.MINT_ACCOUNT), notifyMetrics = false)
        assertNotNull(MintSupply8418.stamp8418(row))
        assertEquals(10L, row.mintSupply8418!!.slot)
        assertEquals(6, row.tokenMap.decimals)
        // superseded by V5.0.8418 — d4c01f7b1: the mint account is the decimals authority. A row whose token-map
        // decimals disagree (provider/default figure) takes the chain value; the disagreement is still counted.
        val other = TokenState(mint = pumpMint, symbol = "P").apply { tokenMap.decimals = 9 }
        MintSupply8418.stamp8418(other)
        assertEquals(6, other.tokenMap.decimals)
        assertEquals(10L, other.mintSupply8418!!.slot)
    }

    @Test fun capsAreVerifiedSupplyTimesPriceOrUnknown() {
        assertTrue(MintSupply8418.capUsd8418(pumpMint, 2.0e-5).isNaN())               // unread mint account: UNKNOWN
        assertEquals(0.0, MintSupply8418.capSolOrZero8418(pumpMint, 2.0e-8), 0.0)
        MintSupply8418.accept8418(Supply8418(pumpMint, oneBillionRaw, 6, 10L, 1L, Source8418.TOKEN_SUPPLY), notifyMetrics = false)
        assertEquals(20_000.0, MintSupply8418.capUsd8418(pumpMint, 2.0e-5), 1e-6)
        assertEquals(20.0, MintSupply8418.capSol8418(pumpMint, 2.0e-8), 1e-9)
        assertTrue(MintSupply8418.capUsd8418(pumpMint, 0.0).isNaN())
        // A non-standard pump supply (e.g. 1.1e9 after a mayhem mint) is priced as it is, not as 1e9.
        val odd = "Odd8418AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump"
        MintSupply8418.accept8418(Supply8418(odd, BigInteger("1100000000000000"), 6, 10L, 1L, Source8418.TOKEN_SUPPLY), notifyMetrics = false)
        assertEquals(22_000.0, MintSupply8418.capUsd8418(odd, 2.0e-5), 1e-6)
    }

    @Test fun trustedMcapNoLongerAssumesABillionOnAPumpSuffix() {
        val row = TokenState(mint = pumpMint, symbol = "Earth").apply {
            lastMcap = 47_047.0; lastPrice = 3.127e-6; lastPriceSource = "PUMP_PORTAL_TRADE_WS_7278"
        }
        assertEquals(0.0, TrustedMcap8019.supplyFor8019(row), 0.0)
        // No verified supply: the row cap is not "corrected" from a guess.
        assertEquals(47_047.0, TrustedMcap8019.mcap8019(row), 0.0)
        assertEquals(47_047.0, row.lastMcap, 0.0)
        // The mint account says 1e9 whole tokens: now the price-implied cap replaces the contradicted row cap.
        MintSupply8418.accept8418(Supply8418(pumpMint, oneBillionRaw, 6, 10L, 1L, Source8418.MINT_ACCOUNT), notifyMetrics = false)
        assertEquals(1e9, TrustedMcap8019.supplyFor8019(row), 0.0)
        assertEquals(3_127.0, TrustedMcap8019.mcap8019(row), 1.0)
        assertEquals(10L, row.mintSupply8418!!.slot)
    }

    @Test fun theCurveHydratesACapOnlyOnAVerifiedSupply() {
        CurveReserves7951.resetForTest7951()
        val now = 1_000_000L
        CurveReserves7951.noteFrame7951(pumpMint, 34.0, 9.4e8, now - 1_000L)
        val row = TokenState(mint = pumpMint, symbol = "C")
        assertTrue(CurveReserves7951.hydrateMcap8399(row, 150.0, now).isNaN())
        assertEquals(0.0, row.lastMcap, 0.0)
        MintSupply8418.accept8418(Supply8418(pumpMint, oneBillionRaw, 6, 10L, 1L, Source8418.TOKEN_SUPPLY), notifyMetrics = false)
        assertEquals(34.0 / 9.4e8 * 1e9 * 150.0, CurveReserves7951.hydrateMcap8399(row, 150.0, now), 1e-6)
    }

    @Test fun curveDepthFromAPriceIsTheCurveInvariantNotASupply() {
        for (p in doubleArrayOf(2.8e-8, 5.0e-8, 1.0e-7, 3.0e-7)) {
            assertEquals(LiquidityTruth8037.curveRealSolFromCap8048(p * 1e9), CurveReserves7951.realSolFromPrice8418(p), 1e-9)
            val old = LiquidityTruth8037.curveDepthUsd8164(p * 1e9 * 150.0, 150.0)
            val new = CurveReserves7951.curveDepthUsdFromPrice8418(p, 150.0)
            if (old == null) assertNull(new) else assertEquals(old, new!!, 1e-6)
        }
        assertEquals(0.0, CurveReserves7951.realSolFromPrice8418(0.0), 0.0)
        assertNull(CurveReserves7951.curveDepthUsdFromPrice8418(1.0e-6, 150.0))   // past the curve's completion
        assertNull(CurveReserves7951.curveDepthUsdFromPrice8418(5.0e-8, 0.0))
    }

    @Test fun aBandCrossingNeedsTheMintAccountsSupply() {
        val born = 1_000_000L
        val now = born + 3 * 60_000L
        val obs = PumpBandEntry8118.Observation8136(pumpMint, born, 1.5e-5, now - 2_000L, PumpBandEntry8118.BandSource8136.EXECUTED_MARK)
        assertFalse("no verified supply: cap UNKNOWN, no crossing", PumpBandEntry8118.crossing8136(obs, null, now))
        assertTrue(PumpBandEntry8118.crossing8136(obs.copy(verifiedSupply8418 = 1e9), null, now))          // $15k
        assertFalse(PumpBandEntry8118.crossing8136(obs.copy(verifiedSupply8418 = 5e8), null, now))         // $7.5k: under the band
    }

    @Test fun pumpFrontendPricesUseTheMintAccountAndNeverAssumeABillion() {
        // No total_supply and no mint-account read: no quote (was 1e9 "standard assumed").
        assertEquals(0.0, PumpFunPriceUnits7017.wholeSupply(JSONObject().put("usd_market_cap", 20_919.0)), 0.0)
        assertEquals(0.0, PumpFunPriceUnits7017.priceUsd(JSONObject().put("usd_market_cap", 20_919.0)), 0.0)
        // The mint account's supply outranks the payload.
        MintSupply8418.accept8418(Supply8418(pumpMint, BigInteger("800000000000000"), 6, 10L, 1L, Source8418.TOKEN_SUPPLY), notifyMetrics = false)
        val payload = JSONObject().put("mint", pumpMint).put("usd_market_cap", 16_000.0).put("total_supply", 1e15)
        assertEquals(8e8, PumpFunPriceUnits7017.wholeSupply(payload), 0.0)
        assertEquals(2.0e-5, PumpFunPriceUnits7017.priceUsd(payload), 1e-12)
    }
}
