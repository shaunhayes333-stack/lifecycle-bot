package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.FeatureFunnel8283
import com.lifecyclebot.engine.truth.FeatureFunnel8283.Stage8283
import com.lifecyclebot.engine.truth.FeatureKind8283
import com.lifecyclebot.engine.truth.FeatureRevision8283
import com.lifecyclebot.engine.truth.Lifecycle8283
import com.lifecyclebot.engine.truth.LifecycleData8283
import com.lifecyclebot.engine.truth.PairFlow8169
import com.lifecyclebot.engine.truth.SetupFeatureResolver8169
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8283 — P0 shared feature cache invalidation (FeatureRevision8283) and P4 lifecycle-aware data requirements:
 * fresh provider evidence is consumed on the next evaluation without waiting for the 5 s setup cache, and a genuine
 * early-launch setup is judged on its own launch evidence with its unavailable history recorded, while critical
 * unknowns (identity, executable price, route, liquidity, sellability, economics, wallet balance) stay binding.
 */
class Aate8283FeatureCacheLifecycleTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun mint(tag: String) = ("M8283" + tag + "x".repeat(40)).take(44)

    private fun row(mint: String, observedAt: Long, liq: Double = 30_000.0, buys: Int = 40, sells: Int = 20) = MarketSweep7297.Row(
        mint = mint, symbol = "ROW", name = "row", priceUsd = 0.002, mcapUsd = 80_000.0, liquidityUsd = liq,
        volumeH1Usd = 10_000.0, volumeH24Usd = 50_000.0, priceChangeH1Pct = 12.0, txCountH1 = 60, holders = 0,
        organicScore = 0.0, verified = false, ageHours = 2.0, providers = setOf("JUP_TRENDING"),
        priceChange5mPct = 3.0, buysH1 = buys, sellsH1 = sells, observedAtMs8169 = observedAt,
    )

    // P0 — the directive's test: a fresh pair observation is read by the very next evaluation, inside the 5 s reuse window.
    @Test fun freshPairEvidenceIsConsumedOnTheNextEvaluationWithoutWaitingForTheCache() {
        FeatureRevision8283.clearForTest8283()
        val m = mint("pair")
        val now = System.currentTimeMillis()
        val ts = TokenState(mint = m, symbol = "FRSH")
        ts.lastPrice = 0.001; ts.lastPriceUpdate = now; ts.lastMcap = 50_000.0; ts.lastLiquidityUsd = 20_000.0
        val a = LanePlaybook7907.classify8084(ts, "SHITCOIN", now)!!
        assertTrue(a.missing.toString(), a.missing.any { it.startsWith("bp:") })
        assertSame(a, LanePlaybook7907.classify8084(ts, "SHITCOIN", now + 500L))   // unchanged evidence: the cache serves
        val k0 = LanePlaybook7907.inputKey8084(ts)
        PairFlow8169.note8169(m, PairFlow8169.Flow8169(30, 10, -1, -1, 4.0, 12.0, 25_000.0, now + 600L))
        assertNotEquals(k0, LanePlaybook7907.inputKey8084(ts))                      // the revision is part of the identity
        val b = LanePlaybook7907.classify8084(ts, "SHITCOIN", now + 1_000L)!!     // well inside EXTERNAL_TTL_MS_8084
        assertNotSame(a, b)
        assertFalse(b.missing.toString(), b.missing.any { it.startsWith("bp:") })
        assertFalse(b.missing.toString(), b.missing.any { it.startsWith("chg:") })
        assertTrue(FeatureRevision8283.kindRevision8283(m, FeatureKind8283.POOL_FLOW) > 0L)
        assertTrue(FeatureRevision8283.kindRevision8283(m, FeatureKind8283.LIQUIDITY) > 0L)
        assertTrue(FeatureRevision8283.statusLine8283(), FeatureRevision8283.statusLine8283().contains("cacheInvalidatedAfterEvidence=1"))
    }

    // P0 — evidenceArrived8252 invalidates setup classifications too; non-feature evidence (safety) leaves the revision.
    @Test fun evidenceArrivalAdvancesTheSharedRevisionPerKind() {
        FeatureRevision8283.clearForTest8283()
        val m = mint("arrive")
        val now = System.currentTimeMillis()
        val ts = TokenState(mint = m, symbol = "ARRV")
        val k0 = LanePlaybook7907.inputKey8084(ts)
        CandidateAdmission8136.evidenceArrived8252(m, setOf("safety", "freeze", "authority"), now)
        assertEquals(0L, FeatureRevision8283.revision8283(m))
        assertEquals(k0, LanePlaybook7907.inputKey8084(ts))
        CandidateAdmission8136.evidenceArrived8252(m, setOf("shape"), now)
        val r1 = FeatureRevision8283.revision8283(m)
        assertTrue(r1 > 0L)
        assertEquals(r1, FeatureRevision8283.kindRevision8283(m, FeatureKind8283.SHAPE))
        assertEquals(0L, FeatureRevision8283.kindRevision8283(m, FeatureKind8283.TAPE))
        assertNotEquals(k0, LanePlaybook7907.inputKey8084(ts))
        CandidateAdmission8136.evidenceArrived8252(m, CandidateAdmission8136.TAPE_FEATURES_8252, now + 1L)
        assertTrue(FeatureRevision8283.revision8283(m) > r1)
        assertTrue(FeatureRevision8283.kindRevision8283(m, FeatureKind8283.TAPE) > 0L)
        // the revision is shared by every specialist: one mint, one number, whatever lane reads it
        assertEquals(setOf(FeatureKind8283.POOL_FLOW, FeatureKind8283.LIQUIDITY), FeatureRevision8283.kindsOf8283(CandidateAdmission8136.PAIR_FEATURES_8252))
    }

    // P0 — the coordination API: bump wakes the held defer that waits on it; a burst is coalesced per mint + kind.
    @Test fun bumpWakesTheHeldDeferAndCoalescesABurst() {
        FeatureRevision8283.clearForTest8283()
        val m = mint("bump")
        val now = System.currentTimeMillis()
        CandidateAdmission8136.holdDeferForTest8252(m, "SHITCOIN", listOf("tape:NO_PRINTS", "shape:UNKNOWN"), now)
        assertFalse(CandidateAdmission8136.reevalDueForTest8252(m, "tape:NO_PRINTS", now + 100L))
        FeatureRevision8283.bump8283(m, FeatureKind8283.TAPE, now + 100L)
        assertTrue(CandidateAdmission8136.reevalDueForTest8252(m, "tape:NO_PRINTS", now + 100L))
        assertFalse(CandidateAdmission8136.reevalDueForTest8252(m, "shape:UNKNOWN", now + 100L))
        val r1 = FeatureRevision8283.revision8283(m)
        FeatureRevision8283.bump8283(m, FeatureKind8283.TAPE, now + 150L)     // same kind inside the coalesce gap
        assertTrue(FeatureRevision8283.revision8283(m) > r1)                   // still a new revision
        assertTrue(FeatureRevision8283.statusLine8283(), FeatureRevision8283.statusLine8283().contains("coalesced=1"))
        FeatureRevision8283.bump(m, FeatureKind8283.SHAPE)                     // the public signature
        assertTrue(FeatureRevision8283.kindRevision8283(m, FeatureKind8283.SHAPE) > 0L)
        assertEquals(0L, FeatureRevision8283.revision8283(""))
    }

    // P0 — a sweep row advances its mint only when it is a NEW observation carrying that family.
    @Test fun sweepRowsAdvanceOnlyOnNewObservations() {
        FeatureRevision8283.clearForTest8283()
        val m = mint("sweep")
        val t = System.currentTimeMillis()
        val first = row(m, t - 60_000L)
        assertEquals(setOf(FeatureKind8283.PRICE, FeatureKind8283.POOL_FLOW, FeatureKind8283.LIQUIDITY), FeatureRevision8283.sweepKinds8283(null, first))
        assertTrue(FeatureRevision8283.sweepKinds8283(first, first).isEmpty())          // carried row: nothing new
        assertTrue(FeatureRevision8283.sweepKinds8283(first, row(m, 0L)).isEmpty())     // undated row: not an observation
        assertEquals(1, FeatureRevision8283.noteSweepRows8283(null, listOf(first), t))
        val r1 = FeatureRevision8283.revision8283(m)
        assertEquals(0, FeatureRevision8283.noteSweepRows8283(listOf(first), listOf(first), t))
        assertEquals(r1, FeatureRevision8283.revision8283(m))
        val newer = row(m, t, liq = Double.NaN, buys = -1, sells = -1).copy(priceChange5mPct = Double.NaN)
        assertEquals(setOf(FeatureKind8283.PRICE), FeatureRevision8283.sweepKinds8283(first, newer))
        assertEquals(1, FeatureRevision8283.noteSweepRows8283(listOf(first), listOf(newer), t))
        assertTrue(FeatureRevision8283.revision8283(m) > r1)
        assertTrue(src("engine/market/MarketSweep7297.kt").contains("FeatureRevision8283.noteSweepRows8283(previous7777?.rows, snap.rows, snap.atMs)"))
    }

    // P0 — sealed tickets are untouched: the revision never reaches a seal; the unsealed-evidence invalidation stays as it was.
    @Test fun theRevisionNeverTouchesASealedTicket() {
        val rev = src("engine/truth/FeatureRevision8283.kt")
        assertFalse(rev.contains("invalidateUnsealedEvidence8253"))
        assertFalse(rev.contains("LaneExecutionCoordinator."))
        val adm = src("engine/truth/CandidateAdmission8136.kt").substringAfter("fun evidenceArrived8252(").substringBefore("if (held8144.isEmpty()) return")
        assertTrue(adm.contains("FeatureRevision8283.advance8283(mint, kinds8283 ?: FeatureRevision8283.kindsOf8283(features), nowMs)"))
        assertTrue(adm.contains("com.lifecyclebot.engine.LaneExecutionCoordinator.invalidateUnsealedEvidence8253(mint)"))
    }

    // ── P4 lifecycle-aware data requirements ──

    /** A freshly priced, costed launch candidate (gross 30%, cost 3%) with no setup record of its own. */
    private fun launch(
        missing: List<String> = emptyList(), unavailable: List<String> = emptyList(), selected: String? = null,
        setups: List<Evidence8136> = emptyList(), priceAgeMs: Long = 2_000L, cost: Double = 3.0, safety: String? = null,
    ) = Candidate8136(
        mint = mint("launch"), lane = "SHITCOIN", selectedSetup = selected, stage = "FRESH_LAUNCH", priceAgeMs = priceAgeMs,
        missingInputs = missing, historyUnavailable8283 = unavailable, expectedGrossPct = 30.0, costPct = cost, downsidePct = 15.0,
        expectedGrossBySetup = setups.associate { it.id to 30.0 }, setups = setups, hotLaunch = true, safetyRefusal = safety,
        genericScore = 60.0, genericFloor = 40.0,
    )

    // P4 — a 10 s old token has no valid 5 m (or 1 m, or 15 s) history; availability grows with age; unknown age claims nothing.
    @Test fun historyWindowsExistOnlyOnceTheTokenIsOldEnough() {
        val tenSeconds = 10.0 / 60.0
        assertTrue(LifecycleData8283.availableWindows8283(tenSeconds).isEmpty())
        assertFalse("5m" in LifecycleData8283.availableWindows8283(tenSeconds))
        assertFalse(LifecycleData8283.windowAvailable8283(5L * 60_000L, tenSeconds))
        assertEquals(listOf("15s", "1m"), LifecycleData8283.availableWindows8283(4.0))
        assertEquals(listOf("15s", "1m", "5m", "15m", "1h"), LifecycleData8283.availableWindows8283(240.0))
        assertTrue(LifecycleData8283.windowAvailable8283(5L * 60_000L, Double.NaN))     // unknown age: no claim, stays binding
        assertEquals(Lifecycle8283.NEW, LifecycleData8283.lifecycleOf8283(tenSeconds))
        assertEquals(Lifecycle8283.DEVELOPING, LifecycleData8283.lifecycleOf8283(30.0))
        assertEquals(Lifecycle8283.ESTABLISHED, LifecycleData8283.lifecycleOf8283(600.0))
        assertEquals(Lifecycle8283.UNKNOWN, LifecycleData8283.lifecycleOf8283(Double.NaN))
    }

    // P4 — the split: too-young history is recorded; everything else, and every critical unknown, stays binding.
    @Test fun criticalUnknownsStayBindingAtEveryAge() {
        val tenSeconds = 10.0 / 60.0
        val young = LifecycleData8283.split8283(listOf("bp:DEFAULT_UNOBSERVED", "chg:DEFAULT_UNOBSERVED", "shape:UNKNOWN", "liq:UNKNOWN"), tenSeconds, null)
        assertEquals(listOf("chg:DEFAULT_UNOBSERVED", "shape:UNKNOWN"), young.unavailable)
        assertEquals(listOf("bp:DEFAULT_UNOBSERVED", "liq:UNKNOWN"), young.binding)
        // two minutes old: a 30 s shape can exist (wait for it), a 5 m move cannot
        val twoMin = LifecycleData8283.split8283(listOf("chg:UNKNOWN", "shape:UNKNOWN"), 2.0, null)
        assertEquals(listOf("chg:UNKNOWN"), twoMin.unavailable)
        assertEquals(listOf("shape:UNKNOWN"), twoMin.binding)
        // an established token waits for its history; an unknown age claims nothing
        assertTrue(LifecycleData8283.split8283(listOf("chg:UNKNOWN", "shape:UNKNOWN"), 90.0, null).unavailable.isEmpty())
        assertTrue(LifecycleData8283.split8283(listOf("chg:UNKNOWN", "shape:UNKNOWN"), Double.NaN, null).unavailable.isEmpty())
        // a setup that reads history keeps it binding; a launch-evidence setup does not
        assertTrue(LifecycleData8283.split8283(listOf("chg:UNKNOWN"), tenSeconds, "TREND_PULLBACK").unavailable.isEmpty())
        assertEquals(listOf("chg:UNKNOWN"), LifecycleData8283.split8283(listOf("chg:UNKNOWN"), tenSeconds, "LAUNCH_CONTINUATION").unavailable)
        // identity, executable price, route, actual liquidity, sellability, economic cost, wallet balance
        for (c in listOf("identity:UNKNOWN", "mint:UNKNOWN", "price", "quote:UNKNOWN", "route:UNKNOWN", "liq:UNKNOWN", "liquidity:UNKNOWN",
                "sellability:UNKNOWN", "economics", "cost:UNKNOWN", "wallet_balance:UNKNOWN", "balance:UNKNOWN")) {
            assertTrue(c, LifecycleData8283.criticalUnknown8283(c))
            assertTrue(c, CandidateAdmission8136.criticalMissing8170(c))
            assertEquals(c, listOf(c), LifecycleData8283.split8283(listOf(c), tenSeconds, null).binding)
        }
        assertFalse(LifecycleData8283.criticalUnknown8283("shape:UNKNOWN"))
    }

    // P4 — the directive's test: a genuine early-launch setup is judged on its fresh launch evidence, no fake history.
    @Test fun earlyLaunchIsJudgedOnLaunchEvidenceWithItsUnavailableHistoryRecorded() {
        // before 8283 the too-young history sat in missingInputs and the candidate waited for it
        val before = CandidateAdmission8136.decide8136(launch(missing = listOf("chg:DEFAULT_UNOBSERVED", "shape:UNKNOWN")))
        assertEquals(Terminal8136.DEFER_DATA, before.terminal)
        assertEquals("DATA_INCOMPLETE_DEFER_8136", before.reason)
        // now it is recorded apart and the candidate is judged (paper: the generic prior over its own launch evidence)
        val d = CandidateAdmission8136.decide8136(launch(unavailable = listOf("chg:DEFAULT_UNOBSERVED", "shape:UNKNOWN")))
        assertEquals(d.compact, Terminal8136.ADMIT, d.terminal)
        assertEquals("GENERIC_PRIOR_CLEARS_8136", d.reason)
        assertTrue(d.compact, d.trace.any { it.startsWith("historyUnavailable8283=chg:DEFAULT_UNOBSERVED,shape:UNKNOWN") })
        assertFalse(d.compact, d.trace.any { it.startsWith("missing=") })
        // live money still needs supported evidence: the generic prior alone is refused (no bypass)
        val live = CandidateAdmission8136.decide8136(launch(unavailable = listOf("chg:UNKNOWN")).copy(live = true))
        assertEquals(Terminal8136.REFUSE, live.terminal)
        assertEquals("LIVE_GENERIC_PRIOR_UNSUPPORTED_8238", live.reason)
    }

    // P4 — unpriceable / unsellable / uncosted / unfunded stay refused or deferred whatever the lifecycle.
    @Test fun unpriceableUnsellableAndUncostedLaunchesAreStillRefused() {
        val hist = listOf("chg:UNKNOWN", "shape:UNKNOWN")
        val unpriced = CandidateAdmission8136.decide8136(launch(unavailable = hist, priceAgeMs = Long.MAX_VALUE))
        assertEquals(Terminal8136.DEFER_DATA, unpriced.terminal)
        assertTrue(unpriced.compact, unpriced.trace.any { it.startsWith("missing=") && it.contains("price") })
        val unsellable = CandidateAdmission8136.decide8136(launch(unavailable = hist, safety = "HARD_BLOCK:UNSELLABLE"))
        assertEquals(Terminal8136.REFUSE, unsellable.terminal)
        assertEquals("SAFETY_REFUSED_8136", unsellable.reason)
        val sellUnknown = CandidateAdmission8136.decide8136(launch(missing = listOf("sellability:UNKNOWN"), unavailable = hist))
        assertEquals(Terminal8136.DEFER_DATA, sellUnknown.terminal)
        assertTrue(sellUnknown.compact, sellUnknown.trace.any { it.startsWith("criticalUnobserved8170=sellability") })
        val uncosted = CandidateAdmission8136.decide8136(launch(unavailable = hist, cost = Double.NaN))
        assertEquals(Terminal8136.DEFER_DATA, uncosted.terminal)
        val unfunded = CandidateAdmission8136.decide8136(launch(missing = listOf("wallet_balance:UNKNOWN"), unavailable = hist))
        assertEquals(Terminal8136.DEFER_DATA, unfunded.terminal)
        assertTrue(unfunded.compact, unfunded.trace.any { it.startsWith("criticalUnobserved8170=wallet_balance") })
    }

    // P4 — an otherwise supported setup is not rejected for an unrelated optional missing feature (critical still binds).
    @Test fun aSupportedSetupIsNotRejectedForAnUnrelatedOptionalGap() {
        val rec = listOf(Evidence8136(CandidateAdmission8136.KIND_SETUP, "LAUNCH_CONTINUATION", 40, 12.0, 2.0))
        val d = CandidateAdmission8136.decide8136(launch(missing = listOf("tape:NO_PRINTS"), selected = "LAUNCH_CONTINUATION", setups = rec))
        assertEquals(d.compact, Terminal8136.ADMIT, d.terminal)
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.reason)
        assertTrue(d.trace.any { it.startsWith("optionalUnobserved8170=tape:NO_PRINTS") })
        val liq = CandidateAdmission8136.decide8136(launch(missing = listOf("liquidity:UNKNOWN"), selected = "LAUNCH_CONTINUATION", setups = rec))
        assertEquals(Terminal8136.DEFER_DATA, liq.terminal)
    }

    // P4 — end to end through the classifier: a 10 s old token's missing history is recorded, not waited for.
    @Test fun aTenSecondTokenDoesNotWaitForHistoryItCannotHave() {
        LifecycleData8283.clearForTest8283()
        val now = System.currentTimeMillis()
        val young = TokenState(mint = mint("young"), symbol = "YNG")
        young.lastPrice = 0.0001; young.lastPriceUpdate = now; young.lastMcap = 12_000.0  // V5.0.8383 — inside SHITCOIN's envelope (a sub-10k cap is its own OUTSIDE_ENVELOPE cohort); young.lastLiquidityUsd = 4_000.0
        AdmissionBirth8144.noteChainMintInit8179(young.mint, now - 10_000L)
        val split = LanePlaybook7907.lifecycleSplit8283(young, "SHITCOIN", now)
        assertNotNull("a token with no flow, move or shape is DATA_INCOMPLETE", split)
        assertTrue(split!!.unavailable.toString(), split.unavailable.any { it.startsWith("chg:") })
        assertTrue(split.unavailable.toString(), split.unavailable.any { it.startsWith("shape:") })
        assertTrue(split.binding.none { it.startsWith("chg:") || it.startsWith("shape:") })
        assertEquals(split.binding.ifEmpty { null }, LanePlaybook7907.dataIncomplete8136(young, "SHITCOIN", now))
        assertTrue(LifecycleData8283.statusLine8283(), LifecycleData8283.statusLine8283().contains("chg="))
        // an established token waits for the same history: it can exist
        val old = TokenState(mint = mint("old"), symbol = "OLD")
        old.lastPrice = 0.01; old.lastPriceUpdate = now; old.lastMcap = 900_000.0; old.lastLiquidityUsd = 90_000.0
        AdmissionBirth8144.noteChainMintInit8179(old.mint, now - 3L * 60L * 60_000L)
        val oldSplit = LanePlaybook7907.lifecycleSplit8283(old, "SHITCOIN", now)
        assertNotNull(oldSplit)
        assertTrue(oldSplit!!.unavailable.isEmpty())
        assertTrue(oldSplit.binding.toString(), oldSplit.binding.any { it.startsWith("chg:") })
    }

    // P4 — data arrival re-evaluates the same specialist's held defer; it never promotes a rejected strategy by itself.
    @Test fun dataArrivalReevaluatesTheSameSpecialistAndNeverPromotesARefusal() {
        val m = mint("same")
        val now = System.currentTimeMillis()
        CandidateAdmission8136.holdDeferForTest8252(m, "MOONSHOT", listOf("shape:UNKNOWN"), now)
        FeatureRevision8283.bump8283(m, FeatureKind8283.SHAPE, now + 10L)
        assertTrue(CandidateAdmission8136.reevalDueForTest8252(m, "shape:UNKNOWN", now + 10L))
        // the wake only schedules: it decides nothing, admits nothing, and touches only held DEFER_DATA records
        val body = src("engine/truth/CandidateAdmission8136.kt").substringAfter("fun evidenceArrived8252(").substringBefore("/** Test seam: is the held defer")
        assertFalse(body.contains("decide8136("))
        assertFalse(body.contains("Terminal8136.ADMIT"))
        assertTrue(body.contains("for ((key, h) in held8144)"))
        // a refusal is a pure function of the candidate: the same refused candidate stays refused after any arrival
        val refused = launch(unavailable = listOf("chg:UNKNOWN"), safety = "HARD_BLOCK:FREEZE_AUTHORITY")
        assertEquals(Terminal8136.REFUSE, CandidateAdmission8136.decide8136(refused).terminal)
        CandidateAdmission8136.evidenceArrived8252(refused.mint, setOf("shape", "chg"), now + 20L)
        assertEquals(Terminal8136.REFUSE, CandidateAdmission8136.decide8136(refused).terminal)
        assertNull(LanePlaybook7907.dataIncomplete8136(TokenState(mint = "", symbol = ""), "NOT_A_LANE", now))
    }

    // ── P5 report ──

    // P5 — per-specialist UNIQUE candidates: re-evaluating the same mint never inflates a stage; repair follows required.
    @Test fun theFeatureFunnelCountsUniqueCandidatesPerSpecialist() {
        FeatureFunnel8283.clearForTest8283()
        val a = mint("fa"); val b = mint("fb")
        repeat(5) { FeatureFunnel8283.noteClassified8283("SHITCOIN", a, coreMissing = true) }
        FeatureFunnel8283.noteClassified8283("SHITCOIN", b, coreMissing = false)
        assertEquals(2L, FeatureFunnel8283.count8283("SHITCOIN", Stage8283.NATIVE_EVALUATED))
        assertEquals(1L, FeatureFunnel8283.count8283("SHITCOIN", Stage8283.FEATURE_REQUIRED))
        assertEquals(0L, FeatureFunnel8283.count8283("SHITCOIN", Stage8283.FEATURE_REPAIRED))   // b never lacked it
        FeatureFunnel8283.noteClassified8283("SHITCOIN", a, coreMissing = false)                  // evidence arrived for a
        FeatureFunnel8283.noteClassified8283("SHITCOIN", a, coreMissing = false)
        assertEquals(1L, FeatureFunnel8283.count8283("SHITCOIN", Stage8283.FEATURE_REPAIRED))
        assertEquals(2L, FeatureFunnel8283.count8283("SHITCOIN", Stage8283.FEATURE_PRESENT))
        FeatureFunnel8283.noteClassified8283("moonshot", a, coreMissing = true)                   // another specialist, its own row
        assertEquals(1L, FeatureFunnel8283.count8283("MOONSHOT", Stage8283.FEATURE_REQUIRED))
        val line = FeatureFunnel8283.line8283()
        assertTrue(line, line.contains("SHITCOIN{NATIVE_EVALUATED=2>FEATURE_REQUIRED=1>FEATURE_PRESENT=2>FEATURE_REPAIRED=1}"))
        assertTrue(line, line.contains("MOONSHOT{NATIVE_EVALUATED=1>FEATURE_REQUIRED=1}"))
        assertFalse(FeatureFunnel8283.note8283("", a, Stage8283.ADMISSION))
    }

    // P5 — the report carries invalidations after evidence, deferrals resolved / recovered after evidence, and the funnel.
    @Test fun theReportLineCarriesTheNewCounters() {
        FeatureRevision8283.clearForTest8283()
        FeatureRevision8283.noteDeferResolved8283(afterEvidence = true)
        FeatureRevision8283.noteDeferResolved8283(afterEvidence = false)
        FeatureRevision8283.noteInvalidation8283()
        val rev = FeatureRevision8283.statusLine8283()
        assertTrue(rev, rev.contains("cacheInvalidatedAfterEvidence=1"))
        assertTrue(rev, rev.contains("defersResolved=2 recoveredAfterEvidence=1"))
        val line = SetupFeatureResolver8169.statusLine8169()
        assertTrue(line, line.startsWith("features8169[core=bp,chg,shape,liq complete="))
        assertTrue(line, line.contains(" featureRev8283["))
        assertTrue(line, line.contains(" lifecycle8283["))
        assertTrue(line, line.contains(" funnel8283["))
        // wiring: the classifier feeds the funnel; admission feeds NATIVE_QUALIFIED / ADMISSION and the recovered-defer count
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("FeatureFunnel8283.noteClassified8283(lane, mint, c.missing.any { it.substringBefore(':') in CORE_8084 })"))
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(adm.contains("held8144.remove(key)?.let { h -> try { FeatureRevision8283.noteDeferResolved8283(FeatureRevision8283.advancedAt8283(mint) > h.atMs) } catch (_: Throwable) {} }"))
        assertTrue(adm.contains("if (d.admitted) FeatureFunnel8283.note8283(c.lane, c.mint, FeatureFunnel8283.Stage8283.ADMISSION)"))
    }
}
