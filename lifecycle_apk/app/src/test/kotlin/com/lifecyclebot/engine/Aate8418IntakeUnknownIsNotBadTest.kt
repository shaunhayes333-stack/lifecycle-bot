package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.CurveReserves7951
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8418 — build 8416 (3.7 h live, 0 FDG allows in 9,286): intake refused unread values as if they were measured bad
 * ones. Each rule keeps the real refusal and turns only the unknown case into a deferral / re-fetch.
 */
class Aate8418IntakeUnknownIsNotBadTest {
    private val t = IntakeUnknownTruth8418
    private fun j(s: String) = JSONObject(s)
    private val mine = "Mine8418AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump"

    // ── 1. HARD_RUG: only real rug evidence confirms a rug ─────────────────────────────────────────────────────────

    @Test fun aRawOnlyRugcheckScoreIsVeryRiskyNeverTheConfirmedCeiling() {
        // superseded by V5.0.8472 — 8471's high-is-good reading reverted on the owner's live data (clean reports averaged score_normalised 1.0, 2+-danger reports 65.5).
        // The raw score is an uncapped risk sum; /100 approximates the 0..100 scale and must not reach "confirmed".
        assertEquals(2, RugcheckScore8399.safety8399(j("""{"score":20000}""")))
        assertEquals(2, RugcheckScore8399.safety8399(j("""{"score":9900}""")))
        assertEquals(95, RugcheckScore8399.safety8399(j("""{"score":500}""")))
        // The provider's own normalised ceiling is unchanged.
        assertEquals(0, RugcheckScore8399.safety8399(j("""{"score_normalised":100}""")))
        assertEquals(98.0, RugcheckScore8399.RAW_ONLY_MAX_RISK_8418, 0.0)
    }

    @Test fun onlyACompleteReportAboutThisMintConfirmsARug() {
        // superseded by V5.0.8472 — 8471's high-is-good reading reverted on the owner's live data (clean reports averaged score_normalised 1.0, 2+-danger reports 65.5).
        // Real rug evidence stays a confirmed rug (0).
        assertEquals(0, RugcheckScore8399.reportSafety8418(j("""{"mint":"$mine","rugged":true,"score_normalised":0,"risks":[]}"""), mine))
        assertEquals(0, RugcheckScore8399.reportSafety8418(j("""{"mint":"$mine","score_normalised":100,"risks":[{"name":"x","level":"danger"}]}"""), mine))
        assertEquals(0, RugcheckScore8399.reportSafety8418(j("""{"score_normalised":99,"risks":[]}"""), mine))
        // Unknown: no answer, or an answer about another mint, is unfetched (-1), never a rug.
        assertEquals(-1, RugcheckScore8399.reportSafety8418(null, mine))
        assertEquals(-1, RugcheckScore8399.reportSafety8418(j("""{"mint":"Other8418","rugged":true,"score_normalised":100,"risks":[]}"""), mine))
        // A score-only answer at the ceiling cannot name a finding: very risky (2), not a permanent confirmed-rug ban.
        assertEquals(2, RugcheckScore8399.reportSafety8418(j("""{"mint":"$mine","score_normalised":100}"""), mine))
        // Ordinary scores are untouched.
        assertEquals(95, RugcheckScore8399.reportSafety8418(j("""{"mint":"$mine","score_normalised":5,"risks":[]}"""), mine))
        assertEquals(-1, RugcheckScore8399.reportSafety8418(j("""{"mint":"$mine","risks":[]}"""), mine))
    }

    @Test fun anUnreadPriceIsNotACollapseButARealCollapseIs() {
        // Zero / non-finite prices are unread, not -100% candles.
        assertFalse(t.priceCollapsed8418(listOf(1.0, 1.1, 1.2, 1.0, 1.05, 0.0)))
        assertFalse(t.priceCollapsed8418(listOf(1.0, 1.1, Double.NaN, 1.0, 0.0, 0.0)))
        // Fewer than five real points proves nothing.
        assertFalse(t.priceCollapsed8418(listOf(1.0, 0.01, 0.0, 0.0, 0.0)))
        // A real -97% from the peak is a collapse.
        assertTrue(t.priceCollapsed8418(listOf(1.0, 1.2, 0.9, 0.2, 0.03)))
        assertFalse(t.priceCollapsed8418(listOf(1.0, 1.2, 0.9, 0.2, 0.07)))
    }

    @Test fun everyHardRugRefusalNamesItsReason() {
        assertEquals("PURE_SELL_PRESSURE", t.hardRugKey8418("PURE_SELL_PRESSURE: buy% 12%", null))
        assertEquals("PRICE_COLLAPSED", t.hardRugKey8418("PRICE_COLLAPSED: -96%", null))
        assertEquals("TRUE_ZERO_LIQUIDITY", t.hardRugKey8418("TRUE_ZERO_LIQUIDITY (live-adaptive)", null))
        assertEquals("UNSPECIFIED", t.hardRugKey8418(null, null))
        // A safety hard block also names the safety rule that set it.
        assertEquals("CONFIRMED_HARD_SAFETY_7828:LP_ONLY",
            t.hardRugKey8418("CONFIRMED_HARD_SAFETY_7828", "LP only 0% locked — EXTREME RUG RISK"))
        assertEquals("CONFIRMED_HARD_SAFETY_7828:CONFIRMED_WALLET_RUG_CLUSTER_8269",
            t.hardRugKey8418("CONFIRMED_HARD_SAFETY_7828", "CONFIRMED_WALLET_RUG_CLUSTER_8269"))
        assertEquals("CONFIRMED_HARD_SAFETY_7828:UNSPECIFIED", t.hardRugKey8418("CONFIRMED_HARD_SAFETY_7828", null))
        t.noteHardRug8418("PURE_SELL_PRESSURE: buy% 12%", null)
        assertTrue(t.statusLine8418().contains("PURE_SELL_PRESSURE="))
    }

    // ── 2. Liquidity: an unread pool defers, a measured low pool still blocks ───────────────────────────────────────

    @Test fun strategyPoolFloorDefersAnUnreadPoolAndBlocksARealOne() {
        // Measured below the floor: the real block, with the reading.
        assertEquals("Low liquidity \$40 (min=\$100)", t.strategyLiquidityBlock8418(40.0, false, 100.0))
        // Proven true zero: still a block.
        val zero = t.strategyLiquidityBlock8418(0.0, true, 100.0)!!
        assertTrue(zero, zero.contains("TRUE_ZERO_LIQUIDITY"))
        // Unread ($0 / NaN, not proven zero): a data deferral, not "HARD BLOCK: Low liquidity $0".
        for (unread in listOf(0.0, Double.NaN, -1.0)) {
            val r = t.strategyLiquidityBlock8418(unread, false, 100.0)!!
            assertTrue(r, r.startsWith(IntakeUnknownTruth8418.DATA_DEFER_LIQ_UNREAD_8418))
            assertFalse(r, t.strategyBlockedReason8418(r).startsWith("HARD BLOCK"))
        }
        // A real pool above the floor passes.
        assertNull(t.strategyLiquidityBlock8418(2_500.0, false, 100.0))
        // A real block keeps its HARD BLOCK label.
        assertEquals("HARD BLOCK: Low liquidity \$40 (min=\$100)", t.strategyBlockedReason8418("Low liquidity \$40 (min=\$100)"))
    }

    @Test fun intakeDoubleZeroIsUnreadButARealDustReadingIsNot() {
        assertTrue(t.intakeUnread8418(0.0, 0.0))
        assertTrue(t.intakeUnread8418(Double.NaN, 0.0))
        assertFalse(t.intakeUnread8418(0.4, 0.0))     // a real (dust) liquidity reading
        assertFalse(t.intakeUnread8418(0.0, 6.0))     // a real (dust) market cap reading
        assertFalse(t.intakeUnread8418(12_000.0, 40_000.0))
    }

    @Test fun v3NonCurveFloorStandsAndAnExitSafeActiveCurveIsScored() {
        assertTrue(t.v3FloorRefuses8418(200.0, 500.0, false))
        assertFalse(t.v3FloorRefuses8418(200.0, 500.0, true))
        assertFalse(t.v3FloorRefuses8418(800.0, 500.0, false))
        CurveReserves7951.resetForTest7951()
        val now = System.currentTimeMillis()
        val curve = "Curve8418ActiveAAAAAAAAAAAAAAAAAAAAAAAApump"
        val thin = "Curve8418ThinBBBBBBBBBBBBBBBBBBBBBBBBBBBpump"
        CurveReserves7951.noteAccount7951(curve, 31.2, 1_031_000_000.0, 1.2, now)
        CurveReserves7951.noteAccount7951(thin, 30.3, 1_062_000_000.0, 0.3, now)
        // Owner's 8320 rule: >= 0.5 real SOL on an active, freshly observed curve is exit-safe.
        assertFalse(t.v3FloorRefuses8418(curve, 200.0, 500.0))
        // A thin curve and an unread / non-curve pool keep the $500 floor.
        assertTrue(t.v3FloorRefuses8418(thin, 200.0, 500.0))
        assertTrue(t.v3FloorRefuses8418("NoCurve8418CCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", 200.0, 500.0))
        assertFalse(t.v3FloorRefuses8418("NoCurve8418CCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", 900.0, 500.0))
        CurveReserves7951.resetForTest7951()
    }

    // ── 3. Age: unknown defers; only a proven lower bound past the window refuses ──────────────────────────────────

    @Test fun unknownAgeDefersAndOnlyAProvenBoundPastTheWindowRefuses() {
        val s = LaneAgeLiquidityScope8140
        // Unknown true age and no proven bound: DEFER, never "past the window".
        assertEquals(LaneAgeLiquidityScope8140.Outcome8140.DEFER, s.ageVerdictBounded8144("SHITCOIN", null, null, 360.0).outcome)
        assertEquals(LaneAgeLiquidityScope8140.Outcome8140.DEFER, s.ageVerdictBounded8144("SHITCOIN", -1.0, null, 360.0).outcome)
        assertEquals(LaneAgeLiquidityScope8140.Outcome8140.DEFER, s.ageVerdictBounded8144("SHITCOIN", null, -1.0, 360.0).outcome)
        // A bound inside the window proves nothing: still DEFER.
        assertEquals(LaneAgeLiquidityScope8140.Outcome8140.DEFER, s.ageVerdictBounded8144("SHITCOIN", null, 120.0, 360.0).outcome)
        // A witnessed bound past the window is proof of age.
        assertEquals(LaneAgeLiquidityScope8140.Outcome8140.REFUSE, s.ageVerdictBounded8144("SHITCOIN", null, 400.0, 360.0).outcome)
        // The witnesses: nothing observed reads UNKNOWN (defer), a future / pre-2020 stamp is not a witness.
        val now = 1_800_000_000_000L
        val none = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(), now)
        assertEquals(AdmissionBirth8144.Kind8144.UNKNOWN, none.kind)
        assertEquals(AdmissionBirth8144.Window8144.UNKNOWN_DEFER, AdmissionBirth8144.window8144(none, 360L * 60_000L))
        val bogus = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(firstSeenMs = now + 3_600_000L, historyFloorMs = 5L), now)
        assertEquals(AdmissionBirth8144.Kind8144.UNKNOWN, bogus.kind)
        val seen7h = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(firstSeenMs = now - 7L * 3_600_000L), now)
        assertEquals(AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN, seen7h.kind)
        assertEquals(AdmissionBirth8144.Window8144.PAST_WINDOW, AdmissionBirth8144.window8144(seen7h, 360L * 60_000L))
        val seen1h = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(firstSeenMs = now - 3_600_000L), now)
        assertEquals(AdmissionBirth8144.Window8144.UNKNOWN_DEFER, AdmissionBirth8144.window8144(seen1h, 360L * 60_000L))
    }
}
