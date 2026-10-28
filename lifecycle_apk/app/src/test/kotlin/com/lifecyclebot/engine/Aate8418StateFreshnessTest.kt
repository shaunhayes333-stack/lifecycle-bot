package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ContaminatedPartialQuarantine7032
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.ExploreShadow8347
import com.lifecyclebot.engine.truth.QuoteAssetBasis8418
import com.lifecyclebot.engine.truth.ShadowCohortBook8397
import com.lifecyclebot.engine.truth.ShadowPersist8355
import com.lifecyclebot.engine.truth.laneOfKey8418
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.8418 — runtime 8416 state that was stale, lost or quarantined:
 *  1. choice8399 staleServed=35569: a lane choice is current while its lane's book revision is unchanged, stale otherwise;
 *  2. restored shadow positions get a full lost-mark window after the restart (ShadowPersist8355.lostMark8418);
 *  3. cross-asset basis-derived partials are not pre-7032 unreconstructible rows (23 quarantined positions);
 *  4. USDC / wSOL are cash at their quote basis, not unknown-basis positions.
 */
class Aate8418StateFreshnessTest {

    // ── 1. explore-shadow choice freshness ──

    @Test fun bookKeyLaneParsesVariantCohortAndFillRateKeys() {
        assertEquals("MOONSHOT", laneOfKey8418("MOONSHOT|NOW|RUNNER"))
        assertEquals("QUALITY", laneOfKey8418("C|QUALITY|PULLBACK|SCALP|S:BREAKOUT"))
        assertEquals("EXPRESS", laneOfKey8418("U|C|EXPRESS|CONFIRM|SCALP|M:MC_LT10K"))
        assertEquals("CASHGEN", laneOfKey8418("U|cashgen|NOW|SCALP"))
    }

    @Test fun laneRevisionChangesOnlyWhenThatLanesRowsChange() {
        val book = ShadowCohortBook8397(4)
        val moon0 = book.laneRevision8418("MOONSHOT")
        val qual0 = book.laneRevision8418("QUALITY")
        book.append(listOf("MOONSHOT|NOW|RUNNER", "C|MOONSHOT|NOW|RUNNER|S:BREAKOUT"), doubleArrayOf(1.0, 5.0, 8394.0, 1.0), "e1")
        val moon1 = book.laneRevision8418("MOONSHOT")
        assertTrue("a close in the lane changes its revision", moon1 != moon0)
        assertEquals("another lane's revision is untouched", qual0, book.laneRevision8418("QUALITY"))
        // the same event replayed is idempotent: no new rows, no new revision
        book.append(listOf("MOONSHOT|NOW|RUNNER"), doubleArrayOf(1.0, 5.0, 8394.0, 1.0), "e1")
        assertEquals(moon1, book.laneRevision8418("MOONSHOT"))
        book.append(listOf("U|MOONSHOT|PULLBACK|SCALP"), doubleArrayOf(2.0, 0.0, 8394.0, 2.0), "e2")
        assertTrue("an unfilled (fill-rate) row also changes the lane's answer", book.laneRevision8418("moonshot") != moon1)
    }

    @Test fun choiceIsFreshAtAnyAgeWhileTheBookIsUnchangedAndWithheldOnceStalePastGrace() {
        val now = 10_000_000L
        assertEquals(ExploreShadow8347.ChoiceFreshness8418.COLD, ExploreShadow8347.choiceFreshness8418(null, null, 5L, now))
        // unchanged rows: the cached answer is exactly what a recompute would return, however old it is
        assertEquals(ExploreShadow8347.ChoiceFreshness8418.FRESH, ExploreShadow8347.choiceFreshness8418(5L, now - 3_600_000L, 5L, now))
        // changed rows: stale; servable only inside the grace window, then withheld (the lane has no adopted choice)
        assertEquals(ExploreShadow8347.ChoiceFreshness8418.STALE_GRACE, ExploreShadow8347.choiceFreshness8418(5L, now - 1_000L, 6L, now))
        assertEquals(ExploreShadow8347.ChoiceFreshness8418.STALE_EXPIRED, ExploreShadow8347.choiceFreshness8418(5L, now - 31_000L, 6L, now))
        assertEquals(ExploreShadow8347.ChoiceFreshness8418.STALE_EXPIRED, ExploreShadow8347.choiceFreshness8418(5L, now + 5_000L, 6L, now))
        assertEquals("v", ExploreShadow8347.choiceServed8418(ExploreShadow8347.ChoiceFreshness8418.FRESH, "v"))
        assertEquals("v", ExploreShadow8347.choiceServed8418(ExploreShadow8347.ChoiceFreshness8418.STALE_GRACE, "v"))
        assertNull(ExploreShadow8347.choiceServed8418(ExploreShadow8347.ChoiceFreshness8418.STALE_EXPIRED, "v"))
        assertNull(ExploreShadow8347.choiceServed8418(ExploreShadow8347.ChoiceFreshness8418.COLD, "v"))
        assertTrue(ExploreShadow8347.choiceStats8399().contains("fresh8418="))
    }

    // ── 2. restart is not a lost mark ──

    @Test fun restoredPositionGetsAFullLostMarkWindowFromTheRestore() {
        val lostMs = 600_000L
        val lastMark = 1_000_000L
        val restoredAt = lastMark + 900_000L   // down 15 minutes
        // pre-8418 rule: lost on the first tick after the restart
        assertTrue(ShadowPersist8355.lostMark8418(lastMark, 0L, restoredAt + 1_000L, lostMs))
        // 8418: the clock restarts at the restore
        assertFalse(ShadowPersist8355.lostMark8418(lastMark, restoredAt, restoredAt + 1_000L, lostMs))
        assertFalse(ShadowPersist8355.lostMark8418(lastMark, restoredAt, restoredAt + lostMs - 1L, lostMs))
        assertTrue("still no mark a whole window after the restore: lost", ShadowPersist8355.lostMark8418(lastMark, restoredAt, restoredAt + lostMs, lostMs))
        // a mark observed after the restore runs the clock from that mark
        assertFalse(ShadowPersist8355.lostMark8418(restoredAt + 300_000L, restoredAt, restoredAt + lostMs + 1L, lostMs))
    }

    // ── 3. cross-asset partials ──

    @Test fun ratelessPartialIsVerifiedOnlyWhenBasisDerivedAndInsideTheRatioBound() {
        val v = ContaminatedPartialQuarantine7032.RatelessPartial8418.BASIS_DERIVED_VERIFIED
        val u = ContaminatedPartialQuarantine7032.RatelessPartial8418.UNRECONSTRUCTIBLE
        assertEquals(v, ContaminatedPartialQuarantine7032.classifyRatelessPartial8418(true, null, 0.5, 0.6))
        assertEquals(v, ContaminatedPartialQuarantine7032.classifyRatelessPartial8418(false, true, 0.5, 0.3))
        assertEquals("a Solana-token rateless partial is the pre-7032 marker", u,
            ContaminatedPartialQuarantine7032.classifyRatelessPartial8418(false, false, 0.5, 0.6))
        assertEquals("unknown asset class stays unknown", u, ContaminatedPartialQuarantine7032.classifyRatelessPartial8418(false, null, 0.5, 0.6))
        assertEquals(u, ContaminatedPartialQuarantine7032.classifyRatelessPartial8418(true, true, 0.0, 0.6))
        assertEquals(u, ContaminatedPartialQuarantine7032.classifyRatelessPartial8418(true, true, 0.001, 5.0))   // 5000x
        assertEquals(true, ContaminatedPartialQuarantine7032.crossAssetClass8418("CRYPTO_ALT"))
        assertEquals(false, ContaminatedPartialQuarantine7032.crossAssetClass8418("SOLANA_TOKEN"))
        assertNull(ContaminatedPartialQuarantine7032.crossAssetClass8418("UNKNOWN"))
        assertNull(ContaminatedPartialQuarantine7032.crossAssetClass8418(""))
    }

    private fun sell(pid: String, key: String, basisDerived: Boolean) = EconomicEventSchema6464.recordSell(
        mode = "paper", positionId = pid, mint = "mint-$pid", symbol = "T", idempotencyKey = key, partial = true,
        soldQty = BigInteger.valueOf(500), preRemainingQty = BigInteger.valueOf(1_000), preRemainingCostBasisSol = 1.0,
        grossProceedsSol = 0.62, exitFeesSol = 0.003, basisDerived8418 = basisDerived,
    )

    private fun buy(pid: String, key: String, assetClass: String) = EconomicEventSchema6464.recordBuy(
        mode = "paper", positionId = pid, mint = "mint-$pid", symbol = "T", idempotencyKey = key, executedCostSol = 1.0,
        filledQty = BigInteger.valueOf(1_000), fillPrice = 1.0, tokenDecimals = 0, quantityScale = 0, lane = "X",
        assetClassTag = assetClass,
    )

    @Test fun scanQuarantinesOnlyTheRatelessTokenPartialAndReleasesVerifiedCrossAssetPositions() {
        EconomicEventSchema6464.resetForTest()
        try {
            val alt = "alt-8418"; val meme = "meme-8418"; val late = "late-8418"; val marked = "marked-8418"
            buy(alt, "b-alt", "CRYPTO_ALT"); buy(meme, "b-meme", "SOLANA_TOKEN")
            sell(alt, "s-alt", basisDerived = false)      // a cross-asset row written before this build (no marker)
            sell(meme, "s-meme", basisDerived = false)    // a token-quantity partial with no rate: genuinely unreconstructible
            sell(marked, "s-marked", basisDerived = true) // a row written by this build
            sell(late, "s-late", basisDerived = false)    // position class not yet known: quarantined
            ContaminatedPartialQuarantine7032.scan()
            assertFalse(ContaminatedPartialQuarantine7032.isContaminated(alt))
            assertFalse(ContaminatedPartialQuarantine7032.isContaminated(marked))
            assertTrue(ContaminatedPartialQuarantine7032.isContaminated(meme))
            assertTrue(ContaminatedPartialQuarantine7032.isContaminated(late))
            // its Buy (asset class) becomes known: the next scan releases it, and the token one stays quarantined
            buy(late, "b-late", "FOREX")
            ContaminatedPartialQuarantine7032.scan()
            assertFalse(ContaminatedPartialQuarantine7032.isContaminated(late))
            assertTrue(ContaminatedPartialQuarantine7032.isContaminated(meme))
            assertTrue(ContaminatedPartialQuarantine7032.statusLine().contains("basisDerivedVerified8418=3"))
        } finally { EconomicEventSchema6464.resetForTest() }
    }

    @Test fun basisDerivedMarkerSurvivesTheDurableRoundTrip() {
        EconomicEventSchema6464.resetForTest()
        try {
            sell("rt-8418", "s-rt", basisDerived = true)
            val e = EconomicEventSchema6464.snapshot().first() as EconomicEventSchema6464.Sell
            assertTrue(e.basisDerived8418)
            val back = EconomicEventSchema6464.decode6486(EconomicEventSchema6464.encode6486(e)) as EconomicEventSchema6464.Sell
            assertTrue(back.basisDerived8418)
            val legacy = EconomicEventSchema6464.decode6486(EconomicEventSchema6464.encode6486(e.copy(basisDerived8418 = false))) as EconomicEventSchema6464.Sell
            assertFalse("an older row (no field) reads false", legacy.basisDerived8418)
        } finally { EconomicEventSchema6464.resetForTest() }
    }

    @Test fun journalExitPriceIsTheObservedUsdMarkNotSolPerUnit() {
        val p = com.lifecyclebot.engine.truth.CanonicalPaperPartialOperation6510
        assertEquals(64_250.0, p.journalExitPrice8418(64_250.0, 0.62, 0.5, 60_000.0), 1e-9)
        assertEquals("unmarked: the legacy figure is unchanged", 1.24, p.journalExitPrice8418(0.0, 0.62, 0.5, 60_000.0), 1e-9)
    }

    // ── 4. quote assets ──

    @Test fun usdcIsValuedOneToOneAndIsNotAPosition() {
        QuoteAssetBasis8418.resetForTests8418()
        val usdc = QuoteAssetBasis8418.basis8418(QuoteAssetBasis8418.USDC_MINT_8418, BigInteger.valueOf(92), 6, 0.0, 1L)
        assertNotNull(usdc)
        assertEquals(QuoteAssetBasis8418.Kind8418.USD_STABLE, usdc!!.kind)
        assertEquals(0.000092, usdc.valueUsd, 1e-12)
        assertEquals("a stable's basis is its face value", usdc.valueUsd, usdc.basisUsd, 0.0)
        assertTrue("no observed SOL price: its SOL value is unknown, not a default", usdc.valueSol.isNaN())
        val priced = QuoteAssetBasis8418.basis8418(QuoteAssetBasis8418.USDT_MINT_8418, BigInteger.valueOf(150_000_000), 6, 150.0, 1L)!!
        assertEquals(150.0, priced.valueUsd, 1e-9); assertEquals(1.0, priced.valueSol, 1e-12)
        val wsol = QuoteAssetBasis8418.basis8418(QuoteAssetBasis8418.WSOL_MINT_8418, BigInteger.valueOf(1_500_000_000), 9, 0.0, 1L)!!
        assertEquals(QuoteAssetBasis8418.Kind8418.NATIVE_SOL, wsol.kind)
        assertEquals(1.5, wsol.valueSol, 1e-12); assertEquals(1.5, wsol.basisSol, 1e-12); assertTrue(wsol.valueUsd.isNaN())
        val lst = QuoteAssetBasis8418.basis8418("mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So", BigInteger.TEN, 9, 150.0, 1L)!!
        assertEquals("an LST is not a position but is not 1:1 either", QuoteAssetBasis8418.Kind8418.OTHER_BASE, lst.kind)
        assertTrue(lst.valueSol.isNaN())
        assertNull(QuoteAssetBasis8418.basis8418("2BJDwUdMbnJhRRfhacFDjrDNhxUWdzE9H6LEND89pump", BigInteger.TEN, 6, 150.0, 1L))

        assertTrue(QuoteAssetBasis8418.skipRecovery8418(QuoteAssetBasis8418.USDC_MINT_8418, BigInteger.valueOf(92), 6))
        assertFalse(QuoteAssetBasis8418.skipRecovery8418("2BJDwUdMbnJhRRfhacFDjrDNhxUWdzE9H6LEND89pump", BigInteger.TEN, 6))
        assertEquals(0.000092, QuoteAssetBasis8418.balance8418(QuoteAssetBasis8418.USDC_MINT_8418)!!.valueUsd, 1e-12)
        assertTrue(QuoteAssetBasis8418.statusLine8418().contains("USD_STABLE:EPjFWd"))
        QuoteAssetBasis8418.resetForTests8418()
    }

    @Test fun walletRecoveryAsksTheQuoteRuleBeforeAnyBasisSearch() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt").readText()
        val loop = src.substringAfter("fun recoverWalletSnapshot(").substringBefore("LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686")
        val quote = loop.indexOf("QuoteAssetBasis8418.skipRecovery8418(mint, amount.raw, amount.decimals)")
        assertTrue(quote > 0)
        assertTrue("before the receipt chain", quote < loop.indexOf("durableLiveBuyBasis7931(mint)"))
    }
}
