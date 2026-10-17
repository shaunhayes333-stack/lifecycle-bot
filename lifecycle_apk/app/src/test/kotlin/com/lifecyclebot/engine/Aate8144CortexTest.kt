package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.cortex.CortexOutcomeRoute8144
import com.lifecyclebot.engine.cortex.CortexOutcomeRoute8144.Attribution8144
import com.lifecyclebot.engine.cortex.CortexRealisedAuthority8136
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexStrategySelect8144
import com.lifecyclebot.engine.cortex.CortexWalkForward8144
import com.lifecyclebot.engine.cortex.CortexWalkForward8144.Verdict8144
import com.lifecyclebot.engine.cortex.LaneEvidence8077
import com.lifecyclebot.engine.truth.AateEntryIdentity8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8144 — Cortex recalibrated on actual results: observed forward vs executable vs realised kept apart in exact grade
 * records; label hygiene (repeats, horizon, unexecutable, unresolved) removes the MOONSHOT REFUSE > STRONG artefact;
 * STRONG authority needs walk-forward out-of-sample validation and composes with the realised cap; a negative default
 * setup never suppresses a valid alternative; every close returns to the strategy that owned it.
 */
class Aate8144CortexTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val t0 = 2_000_000_000_000L
    private val strong = CortexScoreboard7885.Bucket.STRONG.ordinal
    private val neutral = CortexScoreboard7885.Bucket.NEUTRAL.ordinal
    private val refuse = CortexScoreboard7885.Bucket.REFUSE.ordinal

    /** Replays the Cortex7885.recordLabel8144 flow on a book: hygiene verdict, record, first label. */
    private fun decide(
        book: CortexGradeBook8144, mint: String, lane: String, atMs: Long, bucket: Int, admitted: Boolean, reason: String?,
        net: Double, latencyMeasured: Boolean = true, setup: String = "S",
    ): CortexGradeRecord8144 {
        val episode = "$mint|$lane"
        val v = CortexLabelHygiene8144.assess8144(admitted, reason, book.lastGradedAt(episode), atMs)
        val id = "$episode|$atMs"
        val r = CortexGradeRecord8144('D', id, episode, atMs, lane, setup, "BULL", "MID", "C3_6", "MC_10K_100K", bucket, 4.5, admitted, v.executable, v.repeat)
        assertTrue(book.addDecision(r))
        assertTrue(book.label(id, net, net, CortexLabelHygiene8144.executablePct8144(net, latencyMeasured), true))
        return r
    }

    private fun alternating(n: Int, a: Double, b: Double) = List(n) { if (it % 2 == 0) a else b }

    // ── 1. forward vs realised separation ──

    @Test fun observedExecutableAndRealisedAreSeparateFieldsAndPredictionIsNeverOverwritten() {
        val book = CortexGradeBook8144()
        // An unexecutable refusal: observed is booked, executable stays unknown.
        val hard = decide(book, "M1", "MOONSHOT", t0, refuse, false, "SAFETY_HONEYPOT_SIM", 300.0)
        assertEquals(300.0, hard.observedNetPct, 1e-9)
        assertTrue(hard.executablePct.isNaN())
        assertFalse(hard.graded)
        // An executable decision on a lane whose fill latency is unmeasured: the executable value carries the haircut.
        val soft = decide(book, "M2", "MOONSHOT", t0 + 1, strong, true, null, 10.0, latencyMeasured = false)
        assertEquals(10.0, soft.observedNetPct, 1e-9)
        assertEquals(10.0, soft.observedGrossPct, 1e-9)
        assertEquals(10.0 -CortexLabelHygiene8144.UNMEASURED_FILL_HAIRCUT_PCT_8144, soft.executablePct, 1e-9)
        // A revision past the lane's executable horizon moves the observed record only.
        assertFalse(CortexLabelHygiene8144.revisionCounts8144(true, 24L * 3_600_000L))
        assertTrue(book.label(soft.id, 900.0, 900.0, 900.0, CortexLabelHygiene8144.revisionCounts8144(true, 24L * 3_600_000L)))
        assertEquals(900.0, soft.observedNetPct, 1e-9)
        assertEquals(7.0, soft.executablePct, 1e-9)
        // A trade record holds the realised close; the decision records never receive it.
        val trade = CortexGradeRecord8144('T', "pos-1", "M2|MOONSHOT", t0 + 2, "MOONSHOT", "S", "BULL", "MID", "C3_6", "MC_10K_100K", strong, 4.5, true, true, false)
        trade.mode = "LIVE"
        assertTrue(book.openTrade(trade))
        val owner = CortexOutcomeRoute8144.owner8144("LIVE", "M2", null, null, null, null, null, "MOONSHOT", "S")
        book.closeTrade("pos-1", owner, -14.4)
        assertEquals(-14.4, trade.realisedPct, 1e-9)
        assertTrue(soft.realisedPct.isNaN())
        // The model's prediction is untouched by every outcome.
        assertEquals(strong, soft.bucket); assertEquals(4.5, soft.predictedEdgePct, 1e-9)
        assertEquals(listOf(-14.4), book.realisedSeries("MOONSHOT", "S", strong, "LIVE"))
        assertTrue(book.realisedSeries("MOONSHOT", "S", strong, "PAPER").isEmpty())
        assertEquals(listOf(7.0), book.executableSeries("MOONSHOT", null, strong))
        // Exact grade key.
        assertEquals("MOONSHOT|S|BULL|MID|C3_6|MC_10K_100K", soft.key())
        // Round trip.
        val copy = CortexGradeBook8144().also { it.decode(book.encode()) }
        assertEquals(book.size(), copy.size()); assertEquals(1, copy.tradeCount())
        assertEquals(listOf(7.0), copy.executableSeries("MOONSHOT", null, strong))
        assertEquals(listOf(-14.4), copy.realisedSeries("MOONSHOT", "S", strong, "LIVE"))
    }

    // ── 2. walk-forward promotion ──

    @Test fun walkForwardDeniesAnInSampleOnlyEdge() {
        // 24 decisions at ~+20% then 16 at ~-10%: the whole sample still clears mean - SE > 2%.
        val xs = alternating(24, 18.0, 22.0) + alternating(16, -8.0, -12.0)
        val ins = CortexWalkForward8144.stat8144(xs)
        assertTrue(ins.lower > CortexWalkForward8144.MARGIN_PCT_8144)
        val r = CortexWalkForward8144.promote8144(xs)
        assertEquals(Verdict8144.IN_SAMPLE_ONLY, r.verdict)
        assertEquals(24, r.oos.n); assertEquals(3, r.foldsTested); assertEquals(1, r.foldsPassed)
        assertEquals(0.0, CortexWalkForward8144.authorityCap8144(r), 0.0)
    }

    @Test fun walkForwardValidatesAPersistentEdgeAndRefusesAnInvertedOne() {
        val xs = alternating(60, 8.0, 12.0)
        val ok = CortexWalkForward8144.promote8144(xs)
        assertEquals(Verdict8144.VALIDATED, ok.verdict)
        assertEquals(36, ok.oos.n)
        assertEquals(CortexScoreboard7885.GRAD_PARTIAL_CAP_7955, CortexWalkForward8144.authorityCap8144(ok), 1e-12)
        // A resolved REFUSE cohort above STRONG out of sample: inverted, no STRONG authority.
        val inverted = CortexWalkForward8144.promote8144(xs, listOf(alternating(20, 28.0, 32.0) to 1.0))
        assertEquals(Verdict8144.INVERTED_OOS, inverted.verdict)
        assertEquals(30.0, inverted.rivalOosMean, 1e-9)
        assertEquals(0.0, CortexWalkForward8144.authorityCap8144(inverted), 0.0)
        // The same rival, mostly unresolved (a survivor statistic), neither blocks nor helps.
        assertEquals(Verdict8144.VALIDATED, CortexWalkForward8144.promote8144(xs, listOf(alternating(20, 28.0, 32.0) to 0.5)).verdict)
        // A NEUTRAL cohort just under STRONG: STRONG is not clearly better by the margin.
        assertEquals(Verdict8144.OOS_BELOW_MARGIN, CortexWalkForward8144.promote8144(xs, listOf(alternating(20, 7.0, 11.0) to 1.0)).verdict)
        // Too few records, or an unresolved STRONG cohort, never promote.
        assertEquals(Verdict8144.INSUFFICIENT_DATA, CortexWalkForward8144.promote8144(alternating(30, 8.0, 12.0)).verdict)
        assertEquals(Verdict8144.UNRESOLVED_COHORT, CortexWalkForward8144.promote8144(xs, emptyList(), 0.5).verdict)
    }

    @Test fun walkForwardAndRealisedAuthorityComposeByMinimum() {
        val validated = CortexWalkForward8144.promote8144(alternating(60, 8.0, 12.0))
        val wfCap = CortexWalkForward8144.authorityCap8144(validated)
        // Realised MOONSHOT STRONG closes -14.4% (n=6): denied whatever the walk-forward says.
        val denied = CortexRealisedAuthority8136.cappedFraction8136(0.9, 6.0, -14.4, 3.0)
        assertEquals(0.0, CortexWalkForward8144.compose8144(0.9, denied, wfCap), 0.0)
        // Realised proven but no out-of-sample validation: denied.
        val proven = CortexRealisedAuthority8136.cappedFraction8136(0.9, 25.0, 6.0, 1.0)
        assertEquals(0.9, proven, 1e-12)
        assertEquals(0.0, CortexWalkForward8144.compose8144(0.9, proven, CortexWalkForward8144.authorityCap8144(CortexWalkForward8144.promote8144(alternating(30, 8.0, 12.0)))), 0.0)
        // Both pass: the label fraction stands (each cap is only a ceiling).
        assertEquals(0.9, CortexWalkForward8144.compose8144(0.9, proven, wfCap), 1e-12)
        // No realised closes yet: partial at most, never above the label fraction.
        val none = CortexRealisedAuthority8136.cappedFraction8136(1.0, 0.0, 0.0, Double.POSITIVE_INFINITY)
        assertEquals(CortexScoreboard7885.GRAD_PARTIAL_CAP_7955, CortexWalkForward8144.compose8144(1.0, none, 1.0), 1e-12)
        assertEquals(0.0, CortexWalkForward8144.compose8144(Double.NaN, proven, wfCap), 0.0)
    }

    @Test fun wilsonLowerBoundIsBelowTheRate() {
        assertEquals(0.0, CortexWalkForward8144.wilsonLower8144(0, 0), 0.0)
        val lb = CortexWalkForward8144.wilsonLower8144(30, 50)
        assertTrue(lb > 0.5 && lb < 0.6)
    }

    // ── 3. label-bias fix: the REFUSE > STRONG artefact, reproduced and removed ──

    @Test fun syntheticCohortReproducesRefuseAboveStrongAndHygieneRemovesIt() {
        val book = CortexGradeBook8144()
        val lane = "MOONSHOT"
        var t = t0
        // STRONG reads that were bought: 40 distinct coins, executable, ~+8%.
        alternating(40, 6.0, 10.0).forEachIndexed { i, v -> decide(book, "S$i", lane, t, strong, true, null, v); t += 60_000L }
        // Soft REFUSE reads: 20 distinct coins, ~-10%.
        alternating(20, -8.0, -12.0).forEachIndexed { i, v -> decide(book, "R$i", lane, t, refuse, false, "EDGE_GATE_SOFT_SCORE", v); t += 60_000L }
        // Two runners refused every 15 minutes while they ran (re-observation), each label revised along a 7-day run.
        for (m in listOf("RUNA", "RUNB")) {
            val start = t
            for (j in 0 until 12) {
                val at = start + j * 15L * 60_000L
                val r = decide(book, m, lane, at, refuse, false, "LATE_CHASE_SCORE", 20.0)
                book.label(r.id, 1_500.0, 1_500.0, 1_500.0, CortexLabelHygiene8144.revisionCounts8144(true, 7L * 24L * 3_600_000L))
            }
            t = start + 12L * 15L * 60_000L
        }
        // Unexecutable refusals (no route / honeypot): an illiquid print nobody could buy.
        for (i in 0 until 10) { decide(book, "H$i", lane, t, refuse, false, if (i % 2 == 0) "ROUTE_UNAVAILABLE" else "SAFETY_HONEYPOT", 300.0); t += 60_000L }

        // The raw observed cohorts reproduce the artefact: REFUSE far above STRONG.
        val obsS = CortexWalkForward8144.stat8144(book.observedSeries(lane, strong))
        val obsR = CortexWalkForward8144.stat8144(book.observedSeries(lane, refuse))
        assertEquals(54, obsR.n)
        assertEquals(8.0, obsS.mean, 1e-9)
        assertEquals((20 * -10.0 + 24 * 1_500.0 + 10 * 300.0) / 54.0, obsR.mean, 1e-6)
        assertTrue(obsR.mean > obsS.mean)
        assertEquals(Verdict8144.INVERTED_OOS,
            CortexWalkForward8144.promote8144(book.observedSeries(lane, strong), listOf(book.observedSeries(lane, refuse) to 1.0)).verdict)

        // Hygiene: repeats and unexecutable decisions leave the executable cohort; revisions past the horizon do not move it.
        val exS = CortexWalkForward8144.stat8144(book.executableSeries(lane, null, strong))
        val exR = CortexWalkForward8144.stat8144(book.executableSeries(lane, null, refuse))
        assertEquals(22, exR.n)                                    // 20 soft refusals + each runner's first read
        assertEquals((20 * -10.0 + 2 * 20.0) / 22.0, exR.mean, 1e-9)
        assertTrue(exR.mean < exS.mean)
        val wf = CortexWalkForward8144.promote8144(book.executableSeries(lane, null, strong),
            listOf(book.executableSeries(lane, null, refuse) to book.resolvedShare(lane, refuse)), book.resolvedShare(lane, strong))
        assertEquals(Verdict8144.VALIDATED, wf.verdict)
        assertEquals(1.0, book.resolvedShare(lane, refuse), 0.0)
    }

    @Test fun hygieneRules() {
        assertTrue(CortexLabelHygiene8144.executableDecision8144(true, "SAFETY_X"))       // an admit was executed
        assertFalse(CortexLabelHygiene8144.executableDecision8144(false, "ROUTE_UNAVAILABLE"))
        assertFalse(CortexLabelHygiene8144.executableDecision8144(false, "C1_HARD_SAFETY"))
        assertTrue(CortexLabelHygiene8144.executableDecision8144(false, "EDGE_GATE_SOFT_SCORE"))
        assertTrue(CortexLabelHygiene8144.executableDecision8144(false, null))
        // Repeats: refused, inside the episode of the coin's last graded decision; an admit never is.
        assertTrue(CortexLabelHygiene8144.repeatInEpisode8144(false, t0, t0 + 15L * 60_000L))
        assertFalse(CortexLabelHygiene8144.repeatInEpisode8144(true, t0, t0 + 15L * 60_000L))
        assertFalse(CortexLabelHygiene8144.repeatInEpisode8144(false, t0, t0 + CortexLabelHygiene8144.EPISODE_MS_8144))
        assertFalse(CortexLabelHygiene8144.repeatInEpisode8144(false, null, t0))
        // Horizon: a scalp lane's revision past the hour, a runner lane's past 6 h, never count.
        assertFalse(CortexLabelHygiene8144.revisionCounts8144(false, 2L * 3_600_000L))
        assertTrue(CortexLabelHygiene8144.revisionCounts8144(true, 4L * 3_600_000L))
        assertFalse(CortexLabelHygiene8144.revisionCounts8144(true, 7L * 3_600_000L))
        assertEquals("C3_6", CortexLabelHygiene8144.costBand8144(4.0))
        assertEquals("CUNK", CortexLabelHygiene8144.costBand8144(Double.NaN))
        // Unresolved decisions lower the cohort's resolved share.
        val book = CortexGradeBook8144()
        decide(book, "A", "MOONSHOT", t0, refuse, false, "SOFT", -5.0)
        val lost = CortexGradeRecord8144('D', "B|MOONSHOT|$t0", "B|MOONSHOT", t0, "MOONSHOT", "S", "-", "MID", "C3_6", "U", refuse, 0.0, false, true, false)
        assertTrue(book.addDecision(lost)); assertTrue(book.markLost(lost.id))
        assertEquals(0.5, book.resolvedShare("MOONSHOT", refuse), 1e-12)
        // A late label resolves it.
        assertTrue(book.label(lost.id, -3.0, -3.0, -3.0, true))
        assertEquals(1.0, book.resolvedShare("MOONSHOT", refuse), 1e-12)
    }

    // ── 4. competing strategies: a negative default never suppresses a valid alternative ──

    @Test fun negativeDefaultDoesNotSuppressAPositiveValidAlternative() {
        // The classifier's ranking (prior-shrunk) picks the losing default over a valid smaller-edge alternative...
        val def = LaneEvidence8077.Setup("LAUNCH_CONTINUATION", 0.5125, 12.0, -0.3, 0.9)
        val alt = LaneEvidence8077.Setup("ACCELERATING_TAPE", 0.45, 12.0, 1.2, 1.08)
        assertEquals("LAUNCH_CONTINUATION", LaneEvidence8077.choose(listOf(def, alt))?.id)
        val records = listOf(
            CortexStrategySelect8144.Record8144(def.id, def.n, def.mean, def.variance),
            CortexStrategySelect8144.Record8144(alt.id, alt.n, alt.mean, alt.variance),
        )
        assertTrue(CortexStrategySelect8144.negative8144(records[0]))
        assertTrue(CortexStrategySelect8144.validPositive8144(records[1]))
        // ...the coin trades as the valid alternative instead.
        assertEquals("ACCELERATING_TAPE", CortexStrategySelect8144.alternative8144(def.id, records))
        // A default that is not negative keeps its place; with no valid alternative the negative default stands.
        val okDefault = CortexStrategySelect8144.Record8144("PULLBACK", 40.0, 2.0, 25.0)
        assertNull(CortexStrategySelect8144.alternative8144("PULLBACK", listOf(okDefault, records[1])))
        val thin = CortexStrategySelect8144.Record8144("THIN", 8.0, 30.0, 100.0)
        assertNull(CortexStrategySelect8144.alternative8144(def.id, listOf(records[0], thin)))
        // The best valid alternative by its own lower bound wins among several.
        val better = CortexStrategySelect8144.Record8144("SPECIALIST_EDGE", 30.0, 9.0, 120.0)
        assertEquals("SPECIALIST_EDGE", CortexStrategySelect8144.alternative8144(def.id, records + better))
    }

    // ── 5. outcome routed to the owning strategy (sealed identity) ──

    private fun sealed(mode: String, mint: String, lane: String, setupTag: String) = AateEntryIdentity8136(
        mode = mode, mint = mint, positionId = "pos-9", candidateVersion = 7L, ownerLane = lane, exactSetup = setupTag,
        strategyVariant = "v1", tactic = "MOMENTUM", entryStyle = "", exitPolicy = "", evidenceVersion = "rev1", decisionTimestampMs = t0,
        scoreFinal = 70.0, pWin = 0.6, expectedPnlPct = 5.0, rugP = 0.05, contributors = emptyList(), shadow = false,
    )

    @Test fun realisedOutcomeReturnsToTheSealedOwnerStrategy() {
        val book = CortexGradeBook8144()
        // The Cortex bound the trade under the setup it saw; the sealed identity says another setup owned it.
        val trade = CortexGradeRecord8144('T', "pos-9", "MINT9|MOONSHOT", t0, "MOONSHOT", "LAUNCH_CONTINUATION", "BULL", "PEAK", "C6_12", "MC_10K_100K", strong, 6.0, true, true, false)
        trade.mode = "LIVE"
        assertTrue(book.openTrade(trade))
        val id = sealed("LIVE", "MINT9", "MOONSHOT", "ACCELERATING_TAPE;LAUNCH_CONTINUATION#O=MATCHED#V=123")
        val owner = CortexOutcomeRoute8144.owner8144("LIVE", "MINT9", id.mode, id.mint, id.ownerLane, id.exactSetup, null, trade.lane, trade.setup)
        assertEquals("ACCELERATING_TAPE", CortexOutcomeRoute8144.setupOf8144(id.exactSetup))
        assertEquals(Attribution8144.SEALED_IDENTITY, owner.attribution)
        assertEquals("ACCELERATING_TAPE", owner.setup)
        assertTrue(owner.mismatch)
        book.closeTrade("pos-9", owner, 42.0)
        assertEquals(listOf(42.0), book.realisedSeries("MOONSHOT", "ACCELERATING_TAPE", strong, "LIVE"))
        assertTrue(book.realisedSeries("MOONSHOT", "LAUNCH_CONTINUATION", strong, "LIVE").isEmpty())
        assertEquals("SEALED_IDENTITY", trade.attribution)
        assertEquals("ACCELERATING_TAPE", trade.ownerSetup); assertEquals("MOONSHOT", trade.ownerLane)
        assertEquals("LAUNCH_CONTINUATION", trade.setup)                              // the entry key is not rewritten
        // One close per trade.
        assertNull(book.closeTrade("pos-9", owner, 99.0))

        // A sealed identity for another mode is not this trade's: the entry snapshot tag owns it, then the Cortex's own.
        val paperSeal = sealed("PAPER", "MINT9", "MOONSHOT", "ACCELERATING_TAPE")
        val snap = CortexOutcomeRoute8144.owner8144("LIVE", "MINT9", paperSeal.mode, paperSeal.mint, paperSeal.ownerLane, paperSeal.exactSetup,
            "PRE_IGNITION_BASE#O=MATCHED#V=9", "MOONSHOT", "LAUNCH_CONTINUATION")
        assertEquals(Attribution8144.ENTRY_SNAPSHOT, snap.attribution); assertEquals("PRE_IGNITION_BASE", snap.setup)
        val own = CortexOutcomeRoute8144.owner8144("LIVE", "MINT9", null, null, null, null, null, "MOONSHOT", "LAUNCH_CONTINUATION")
        assertEquals(Attribution8144.CORTEX_ENTRY, own.attribution); assertFalse(own.mismatch)
        // Nothing attributes it: the lane keeps the economics, no strategy book receives a sample.
        val book2 = CortexGradeBook8144()
        val t2 = CortexGradeRecord8144('T', "pos-10", "M|MOONSHOT", t0, "MOONSHOT", "", "-", "UNK", "CUNK", "UNK", strong, 0.0, true, true, false)
        t2.mode = "LIVE"; book2.openTrade(t2)
        val unv = CortexOutcomeRoute8144.owner8144("LIVE", "M", null, null, null, null, null, "MOONSHOT", "")
        assertEquals(Attribution8144.UNVERIFIABLE, unv.attribution); assertFalse(unv.trainsStrategy)
        book2.closeTrade("pos-10", unv, -20.0)
        assertEquals(listOf(-20.0), book2.realisedSeries("MOONSHOT", null, strong, "LIVE"))
        assertTrue(book2.realisedSeries("MOONSHOT", "", strong, "LIVE").isNotEmpty())   // lane-level only (blank setup)
        assertTrue(book2.cells(1).none { it.realisedN > 0 })                          // never a strategy cell's sample
    }

    // ── wiring (supplement) ──

    @Test fun cortexWiresHygieneWalkForwardAndOwnerRouting() {
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("walkForwardCapped8144(lane, null, f, realisedCapped8136(lane, f))"))
        assertTrue(cx.contains("walkForwardCapped8144(a.lane, setup8083, cell8083?.fraction ?: 0.0, realisedCapped8136(a.lane, cell8083?.fraction ?: 0.0))"))
        assertTrue(cx.contains("routeRealised8144(env, mode, a, sealed8144, snapTag8144)"))
        assertTrue(cx.contains("AateEntryIdentityBook8136.identity8136(env.positionId)"))
        val on = cx.substringAfter("fun onLabel(mint: String, labelLane: String").substringBefore("// ── V5.0.8144 — grade records")
        // superseded by V5.0.8187: the playbook takes the label clamped to its cohort's cap (net8187 / gross8187 / fam8187).
        assertTrue(on.indexOf("recordLabel8144(key, p, netPct, grossPct, lat8144)") in 0 until on.indexOf("LanePlaybook7907.onLabel(mint, labelLane, net8187, gross8187, fam8187, p.setup7961)"))
        assertTrue(on.contains("LanePlaybook7907.dropPending8144(mint, labelLane)"))
        val rv = cx.substringAfter("fun reviseLabel8006(").substringBefore("fun progressiveLine8006(")
        assertTrue(rv.indexOf("if (!exec8144) { inc(\"REVISION_PAST_EXEC_HORIZON_8144\"); return }") in 0 until rv.indexOf("ledger.revise8006("))
        assertTrue(cx.contains("FirstSight8026.grant8026(ts, a.lane, refusal, if (barred8144) -1.0 else evidence, strong, maturity)"))
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("?: (try { validAlternative8144(ts, laneRaw, nowMs) } catch (_: Throwable) { null })  // V5.0.8144"))
        assertTrue(pb.contains("if (setup == null || alt8144 != null) null else synchronized(this) {"))
        assertTrue(pb.contains("CortexLabelHygiene8144.revisionCounts8144(runnerHorizonLane8144(g.lane), System.currentTimeMillis() - g.atMs)"))
    }
}
