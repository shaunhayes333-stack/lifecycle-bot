package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.LaunchBirth8169
import com.lifecyclebot.engine.truth.MarketEvidence8346
import com.lifecyclebot.engine.truth.PairFlow8169
import com.lifecyclebot.engine.truth.SetupFeatureResolver8169
import com.lifecyclebot.engine.truth.TokenBirthHydrator7441
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8346 — market evidence: a birth from the cheapest certain witness (no walk when one is held), the curve's real SOL
 * as the liquidity of an un-graduated pump coin (a dexPair NO_LIQUIDITY beside it is a reading, not unknown), the tape
 * keyed by the bare mint on both sides with a pump coin asking for its stream when it has none, and identity breaks
 * classified by unit per source.
 */
class Aate8346MarketEvidenceTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val pump = "So11111111111111111111111111111111111pump"
    private val now = 1_760_000_000_000L

    @Before fun fresh() { MarketEvidence8346.resetForTests8346(); TradeTape8100.resetForTest8100() }
    @After fun back() { MarketEvidence8346.resetForTests8346(); TradeTape8100.resetForTest8100() }

    // ── birth ──

    @Test fun createFrameOrChainLaunchIsTheBirthAndTheEarliestDirectWitnessWins() {
        val frame = now - 20_000L
        val pair = now - 10_000L
        val r = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(createFrameMs = frame, pairCreatedMs = pair), now)
        assertEquals(AdmissionBirth8144.Kind8144.KNOWN, r.kind)
        assertEquals("CREATE_FRAME", r.source)
        assertEquals(20_000L, r.minAgeMs)
        val chain = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(chainMintInitMs = now - 5_000L), now)
        assertEquals("CHAIN_MINT_INIT", chain.source)
        assertEquals(AdmissionBirth8144.Kind8144.KNOWN, chain.kind)
        // a pair alone (a pump mint's own curve pair) is a direct witness too
        val pairOnly = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(pairCreatedMs = pair), now)
        assertEquals("PAIR_CREATION", pairOnly.source)
        assertEquals(AdmissionBirth8144.Window8144.IN_WINDOW, AdmissionBirth8144.window8144(pairOnly, 180_000L))
    }

    @Test fun dexScreenerPairCreationIsTheBirthOnlyForThePumpCurvePair() {
        assertEquals(LaunchBirth8169.PairProof8169.BIRTH, LaunchBirth8169.pairProof8169(pump, "pumpfun", now - 1_000L, now))
        assertEquals("a graduated PumpSwap pool only proves the token existed then", LaunchBirth8169.PairProof8169.FLOOR,
            LaunchBirth8169.pairProof8169(pump, "pumpswap", now - 1_000L, now))
        assertEquals(LaunchBirth8169.PairProof8169.FLOOR, LaunchBirth8169.pairProof8169("So11111111111111111111111111111111111111112", "raydium", now - 1_000L, now))
        assertEquals("a future time proves nothing", LaunchBirth8169.PairProof8169.NONE, LaunchBirth8169.pairProof8169(pump, "pumpfun", now + 600_000L, now))
    }

    @Test fun theSignatureWalkIsSpentOnlyWhenNoDirectWitnessIsHeld() {
        assertTrue(TokenBirthHydrator7441.walkNeeded8346(null))
        assertFalse("a frame / launch note / pair already dated the mint: no RPC page", TokenBirthHydrator7441.walkNeeded8346(now - 30_000L))
    }

    @Test fun unknownBirthStillDefersAndAProvenFloorPastTheWindowIsOld() {
        val unknown = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(), now)
        assertEquals(AdmissionBirth8144.Window8144.UNKNOWN_DEFER, AdmissionBirth8144.window8144(unknown, 180_000L))
        val floor = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(poolFloorMs = now - 3_600_000L), now)
        assertEquals(AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN, floor.kind)
        assertEquals(AdmissionBirth8144.Window8144.PAST_WINDOW, AdmissionBirth8144.window8144(floor, 180_000L))
        val floorInWindow = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(poolFloorMs = now - 60_000L), now)
        assertEquals("a lower bound inside the window never makes a token young", AdmissionBirth8144.Window8144.UNKNOWN_DEFER,
            AdmissionBirth8144.window8144(floorInWindow, 180_000L))
    }

    // ── liquidity ──

    @Test fun curveFrameRealSolIsTheLiquidityOfAnUngraduatedPumpCoin() {
        val real = CurveReserves7951.realSolFromVirtual7951(31.5)
        assertEquals(1.5, real, 1e-9)
        val c = MarketEvidence8346.curveLiq8346(real, 200.0)
        assertEquals(MarketEvidence8346.CURVE_8346, c.source)
        assertEquals(300.0, c.value!!, 1e-9)
        assertEquals("", c.why)
        assertNull(MarketEvidence8346.curveLiq8346(null, 200.0).value)
        assertEquals("NO_ACTIVE_CURVE", MarketEvidence8346.curveLiq8346(null, 200.0).why)
        assertEquals("nobody bought is not a reading of 0", "CURVE_EMPTY", MarketEvidence8346.curveLiq8346(0.0, 200.0).why)
        assertEquals("NO_SOL_PRICE", MarketEvidence8346.curveLiq8346(1.5, 0.0).why)
        assertEquals("INVALID", MarketEvidence8346.curveLiq8346(Double.NaN, 200.0).why)
    }

    @Test fun dexPairNoLiquidityOnAKnownCurveIsNotUnknown() {
        val pairNoLiq = PairFlow8169.Flow8169(3, 1, 10, 5, 2.0, 5.0, Double.NaN, now - 5_000L)
        val pairCand = SetupFeatureResolver8169.pairLiq8169(pairNoLiq, now)
        assertNull(pairCand.value)
        assertEquals("NO_LIQUIDITY", pairCand.why)
        val liq = listOf(
            SetupFeatureResolver8169.Cand8169<Double>(SetupFeatureResolver8169.CHAIN, null, "NOT_OBSERVED"),
            SetupFeatureResolver8169.Cand8169<Double>(SetupFeatureResolver8169.PRIMARY, null, "UNKNOWN"),
            MarketEvidence8346.curveLiq8346(1.5, 200.0),
            pairCand,
            SetupFeatureResolver8169.Cand8169<Double>(SetupFeatureResolver8169.SWEEP, null, "NOT_LISTED"),
        )
        val none = listOf<SetupFeatureResolver8169.Cand8169<Double>>()
        val fill = SetupFeatureResolver8169.resolve8169(none, emptyList(), emptyList(), liq)
        assertEquals(MarketEvidence8346.CURVE_8346, fill.liq.source)
        assertEquals(300.0, fill.liq.value!!, 1e-9)
        assertNull(fill.liq.why)
        // without the curve the same evaluation stays unknown with every source's reason
        val without = SetupFeatureResolver8169.resolve8169(none, emptyList(), emptyList(), liq.filter { it.source != MarketEvidence8346.CURVE_8346 })
        assertNull(without.liq.value)
        assertTrue(without.liq.why!!.contains("dexPair:NO_LIQUIDITY"))
    }

    @Test fun liquidityPicksAreCountedPerSource() {
        MarketEvidence8346.noteLiqPick8346(MarketEvidence8346.CURVE_8346)
        MarketEvidence8346.noteLiqPick8346(SetupFeatureResolver8169.PAIR)
        MarketEvidence8346.noteLiqPick8346(null)
        val line = MarketEvidence8346.liqLine8346()
        assertTrue(line, line.contains("pickedCurve=1"))
        assertTrue(line, line.contains("fromDexPair=1"))
        assertTrue(line, line.contains("stillUnknown=1"))
    }

    // ── tape -> shape ──

    @Test fun tapeKeyedByTheBareMintStatesAShapeFromThreeMinuteCloses() {
        assertEquals("NO_PRINTS", TradeTape8100.absentWhy8169(pump, now))
        TradeTape8100.note8100(pump, 1.0e-7, 0.5, true, now - 150_000L)
        TradeTape8100.note8100(pump, 1.2e-7, 0.5, true, now - 90_000L)
        TradeTape8100.note8100(pump, 1.1e-7, 0.5, false, now - 30_000L)
        val r = TradeTape8100.read8100(pump, now)
        assertNotNull("three one-minute closes are a shape", r)
        assertNotNull(r!!.shape)
        assertEquals("TOO_FEW", TradeTape8100.absentWhy8169(pump, now))
        assertEquals("a different key format would be a different mint", "NO_PRINTS", TradeTape8100.absentWhy8169("solana|$pump", now))
    }

    @Test fun aPumpCoinWithNoTapeAsksForItsStreamOncePerMinute() {
        assertTrue(MarketEvidence8346.tapeWanted8346(pump = true, graduated = false, tapeWhy = "NO_PRINTS", lastAskMs = null, nowMs = now))
        assertTrue(MarketEvidence8346.tapeWanted8346(true, false, "STALE", null, now))
        assertFalse("prints exist, merely too few: already streamed", MarketEvidence8346.tapeWanted8346(true, false, "TOO_FEW", null, now))
        assertFalse("not a pump coin: PumpPortal has no stream for it", MarketEvidence8346.tapeWanted8346(false, false, "NO_PRINTS", null, now))
        assertFalse("graduated: the curve stream is over", MarketEvidence8346.tapeWanted8346(true, true, "NO_PRINTS", null, now))
        assertFalse("asked 10 s ago", MarketEvidence8346.tapeWanted8346(true, false, "NO_PRINTS", now - 10_000L, now))
        assertTrue("asked a minute ago", MarketEvidence8346.tapeWanted8346(true, false, "NO_PRINTS", now - MarketEvidence8346.TAPE_ASK_GAP_MS_8346, now))
    }

    @Test fun shapeSourcesAreCounted() {
        MarketEvidence8346.noteShape8346(SetupFeatureResolver8169.TAPE, null)
        MarketEvidence8346.noteShape8346(SetupFeatureResolver8169.PRIMARY, null)
        MarketEvidence8346.noteShape8346(null, "primary:UNKNOWN|tape8100:NO_PRINTS")
        assertEquals("tape", MarketEvidence8346.shapeBucket8346(SetupFeatureResolver8169.TAPE))
        assertEquals("candles", MarketEvidence8346.shapeBucket8346(SetupFeatureResolver8169.PRIMARY))
        assertNull(MarketEvidence8346.shapeBucket8346(null))
        val line = MarketEvidence8346.shapeLine8346()
        assertTrue(line, line.contains("fromTape=1"))
        assertTrue(line, line.contains("fromCandles=1"))
        assertTrue(line, line.contains("primary:UNKNOWN|tape8100:NO_PRINTS=1"))
    }

    // ── identity ──

    @Test fun identityBreaksAreClassifiedByUnit() {
        val sol = 180.0
        assertEquals(MarketEvidence8346.Unit8346.SOL_VS_USD, MarketEvidence8346.unitBreak8346(sol, sol))
        assertEquals("either side in SOL reads the same unit", MarketEvidence8346.Unit8346.SOL_VS_USD, MarketEvidence8346.unitBreak8346(1.0 / sol, sol))
        assertEquals(MarketEvidence8346.Unit8346.DECIMALS_1E3, MarketEvidence8346.unitBreak8346(1.0 / 1000.0, sol))
        assertEquals(MarketEvidence8346.Unit8346.DECIMALS_1E6, MarketEvidence8346.unitBreak8346(1.0e6, sol))
        assertEquals(MarketEvidence8346.Unit8346.DECIMALS_1E9, MarketEvidence8346.unitBreak8346(1.0e-9, sol))
        assertEquals("8344's worstBreak 2579x matches no unit: a transient cap or another pool's price", MarketEvidence8346.Unit8346.OTHER,
            MarketEvidence8346.unitBreak8346(2579.0, sol))
        assertEquals("SOL unit is never claimed without a SOL price", MarketEvidence8346.Unit8346.OTHER, MarketEvidence8346.unitBreak8346(sol, 0.0))
        assertEquals(MarketEvidence8346.Unit8346.OTHER, MarketEvidence8346.unitBreak8346(Double.NaN, sol))
        val u = MarketEvidence8346.noteIdentityBreak8346("PUMP_PORTAL_WS", sol, sol)
        assertEquals(MarketEvidence8346.Unit8346.SOL_VS_USD, u)
        MarketEvidence8346.noteIdentityHeld8346(); MarketEvidence8346.noteIdentitySkew8346()
        val line = MarketEvidence8346.identityLine8346()
        assertTrue(line, line.contains("held=1 skew=1"))
        assertTrue(line, line.contains("SOL_VS_USD@PUMP_PORTAL_WS=1"))
    }

    // ── source pins ──

    @Test fun hookPointsAreWired() {
        val dex = src("network/DexscreenerApi.kt")
        assertTrue("the batch pair lane dates every pair row", dex.contains("AdmissionBirth8144.notePairCreated8169(r.mint, r.dexId, created8346)"))
        val hyd = src("engine/truth/TokenBirthHydrator7441.kt")
        assertTrue("a queued walk ends when a direct witness is held", hyd.contains("if (!walkNeeded8346(known8346))"))
        assertTrue(hyd.contains("TOKEN_BIRTH_WALK_SKIPPED_KNOWN_8346"))
        val res = src("engine/truth/SetupFeatureResolver8169.kt")
        assertTrue("the curve is a liq candidate before the DEX pair", res.contains("Cand8169(PRIMARY, liqPrimary, \"UNKNOWN\"), curve8346, pairLiq8169(pair, nowMs, maxAge)"))
        assertTrue(res.contains("MarketEvidence8346.requestTape8346(mint, tapeWhy, nowMs)"))
        val liq = src("engine/truth/LiquidityTruth8037.kt")
        assertTrue("a curve observation goes through the 8343 hydration write", liq.contains("fun onCurveObserved8346(mint: String, realSol: Double, fromAccount: Boolean)"))
        assertTrue(liq.contains("write8343(ts, usd)\n        try { ts.tokenMap.liquidityUsd = usd }"))
        val curve = src("engine/truth/CurveReserves7951.kt")
        assertTrue(curve.contains("LiquidityTruth8037.onCurveObserved8346(mint, real8346, fromAccount = false)"))
        assertTrue(curve.contains("LiquidityTruth8037.onCurveObserved8346(mint, realSol, fromAccount = true)"))
        assertTrue("the 8343 resume hook in put() is untouched", curve.contains("HydrationReeval8343.onCurveRead8343(m, real, atMs)"))
        val metrics = src("engine/truth/TokenMetricsAuthority7069.kt")
        assertTrue(metrics.contains("MarketEvidence8346.noteIdentityBreak8346(source, ratio,"))
        assertTrue("the break is still never substituted", metrics.contains("METRICS_IDENTITY_BROKEN_NO_SUBSTITUTION_7087"))
        val birth = src("engine/truth/AdmissionBirth8144.kt")
        assertTrue(birth.contains("MarketEvidence8346.noteSniperReleased8346()"))
        assertTrue(src("engine/truth/CanonicalTokenBirthTime7440.kt").contains("MarketEvidence8346.noteBirthUnresolved8346()"))
        // FinalDecisionGate.evaluate() is not touched by 8346
        assertFalse(src("engine/FinalDecisionGate.kt").contains("8346"))
    }

    @Test fun tokenStateIsTheWriteTargetOfTheCurveObservation() {
        val ts = TokenState(mint = pump)
        assertEquals(0.0, ts.lastLiquidityUsd, 0.0)
        // the pure fill rule the hydration write follows: an unread tick takes the observation, a read tick is never lowered by it
        assertEquals(300.0, com.lifecyclebot.engine.truth.LiquidityTruth8037.fill8037(0.0, 300.0)!!, 1e-9)
        assertNull(com.lifecyclebot.engine.truth.LiquidityTruth8037.fill8037(50.0, 300.0))
    }
}
