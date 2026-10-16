package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexAudit8169
import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexVoters7885
import com.lifecyclebot.engine.market.HunterAssetClass8169
import com.lifecyclebot.engine.market.LaneHunter7297
import com.lifecyclebot.engine.market.MarketSweep7297
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8169 — Cortex / scanner / hunter audit and repairs (owner: "ensure the Cortex is included in the audit including
 * all functions including the scanners and hunters"). 5.0.8168: calibration slope pinned at 1.50 on every lane, 164.85 ms
 * per assessment, 1,345 replayed closes filed into a 600-trade book, NVDAx / jlUSDC among meme hunter picks, scanner dedup
 * printing raw=0 from a component nothing calls.
 */
class Aate8169CortexTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    // ── calibration: centred, robust, old rows re-learn ──

    @Test fun calibrationSlopeIsCentredSoAnOffsetIsNotReadAsSlope() {
        for (offset in listOf(-10.0, 10.0)) {
            val c = CortexCalibration7901()
            // Deviations all positive (2 and 4), outcomes = offset + 0.5 d: the through-origin ratio read 0 or 1.5 (clamp).
            repeat(300) { i -> val d = if (i % 2 == 0) 2.0 else 4.0; c.learn("L", d, 0.0, offset + 0.5 * d) }
            assertEquals("offset $offset", 0.5, c.slope("L"), 1e-6)
        }
    }

    @Test fun oneMisBasedLabelCannotPinTheSlope() {
        val c = CortexCalibration7901()
        repeat(300) { i -> val d = if (i % 2 == 0) 10.0 else -10.0; c.learn("L", d, 0.0, d * 0.5) }
        c.learn("L", 10.0, 0.0, 185_800.0)
        val s = c.slope("L")
        assertTrue("slope $s", s > 0.5 && s < 0.8)
    }

    @Test fun throughOriginRowsAreNotReadAndTheLaneStartsAtIdentity() {
        val c = CortexCalibration7901()
        c.decode(org.json.JSONObject().put("MOONSHOT", "300.0,1000.0,1500.0"))
        assertEquals(1.0, c.slope("MOONSHOT"), 0.0)
        val enc = CortexCalibration7901().also { k -> repeat(150) { i -> k.learn("Q", if (i % 2 == 0) 3.0 else -3.0, 0.0, if (i % 2 == 0) 3.0 else -3.0) } }.encode()
        val back = CortexCalibration7901().also { it.decode(enc) }
        assertEquals(1.0, back.slope("Q"), 1e-6)
        assertEquals(1.0, CortexCalibration7901.centredSlope8169(50.0, 1.0, 2.0, 1.0, 1.0), 0.0)
        assertEquals(1.0, CortexCalibration7901.centredSlope8169(200.0, 200.0, 200.0, 0.0, 0.0), 0.0)  // no spread in d
    }

    // ── grade book: newest trades kept, replays cannot evict this session's records ──

    private fun trade(id: String, at: Long) = CortexGradeRecord8144('T', id, "m|L", at, "L", "-", "-", "T", "C", "U", 2, 0.0, true, true, false)

    @Test fun aFullTradeBookKeepsTheNewestAndRefusesOlderReplays() {
        val book = CortexGradeBook8144(maxDecisions = 10, maxTrades = 3)
        assertTrue(book.openTrade(trade("a", 300))); assertTrue(book.openTrade(trade("b", 400))); assertTrue(book.openTrade(trade("c", 500)))
        assertFalse("a replayed older close cannot evict", book.openTrade(trade("old", 100)))
        assertNull(book.trade("old"))
        assertTrue(book.openTrade(trade("d", 600)))
        assertNull("the oldest by time goes", book.trade("a"))
        assertEquals(3, book.tradeCount())
        assertTrue(book.evictsOlder8169(100, 100)); assertFalse(book.evictsOlder8169(100, 99))
    }

    // ── voters: lane-free read sharing ──

    @Test fun laneFreeReadsAreSharedOnlyForTheSameCoinPriceAndMoment() {
        assertTrue(CortexVoters7885.memoReusable8169(true, 5L, 5L, 1_000L, 2_000L, 1_000L, 1_500L))
        assertFalse("other object", CortexVoters7885.memoReusable8169(false, 5L, 5L, 1_000L, 2_000L, 1_000L, 1_500L))
        assertFalse("new price", CortexVoters7885.memoReusable8169(true, 5L, 6L, 1_000L, 2_000L, 1_000L, 1_500L))
        assertFalse("stale", CortexVoters7885.memoReusable8169(true, 5L, 5L, 1_000L, 1_000L + CortexVoters7885.MEMO_MS_8169 + 1, 1_000L, 1_500L))
        assertFalse("decision far apart", CortexVoters7885.memoReusable8169(true, 5L, 5L, 1_000L, 2_000L, 1_000L, 1_000L + CortexVoters7885.MEMO_MS_8169 + 1))
        assertFalse("clock went back", CortexVoters7885.memoReusable8169(true, 5L, 5L, 2_000L, 1_000L, 1_000L, 1_000L))
    }

    @Test fun theLaneDependentSetMatchesEveryVoterThatReadsTheLane() {
        val re = Regex("""(?:Voter|\bv)\(\s*("?[A-Za-z0-9_.]+"?)\s*,[^{]*?\{\s*(\w+)\s*,\s*(\w+)\s*,\s*(\w+)\s*->""")
        val all = listOf("engine/cortex/CortexVoters7885.kt", "engine/cortex/CortexVotersWide8004.kt").flatMap { f -> re.findAll(src(f)).toList() }
        assertEquals("every voter declaration parsed", CortexVoters7885.ALL.size, all.size)
        val dependent = all.filter { it.groupValues[3] != "_" }.map { it.groupValues[1].trim('"') }.toSet()
        assertEquals(dependent, CortexVoters7885.LANE_DEPENDENT_8169)
        assertEquals(CortexVoters7885.ALL.size - dependent.size, CortexVoters7885.laneFreeCount8169())
    }

    // ── leakage: voters read after the label started ──

    @Test fun aVoterReadAfterTheLabelStartIsLeakage() {
        assertFalse(CortexAudit8169.voterReadLeaks8169(1_000L + 5_000L, 1_000L, 5_000L))
        assertTrue(CortexAudit8169.voterReadLeaks8169(1_000L + 5_001L, 1_000L, 5_000L))
        assertFalse("a read before the decision is fine", CortexAudit8169.voterReadLeaks8169(500L, 1_000L, 5_000L))
        assertFalse("unknown times never flag", CortexAudit8169.voterReadLeaks8169(0L, 1_000L, 5_000L))
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("val late8169 = lateVoterRead8169(labelLane, nowMs, if (cached8169 != null && a === cached8169) a.atMs else jobAt8169)"))
        assertTrue(cx.contains("if (!hyg8144.authority || late8153 || lateRead8169) {"))
        assertTrue(cx.contains("inc(\"LABEL_HYGIENE_LATE_VOTER_READ_8169\")"))
        assertTrue("survives a restart", cx.contains(".put(\"lr8169\", p.lateRead8169)") && cx.contains("o.optBoolean(\"lr8169\", false)"))
    }

    // ── hunters ──

    private val nvdaMint = "Xsc9qvGR1efVDFGLrVsmkzv3qi45LTBjeUKSPmx9qEh"

    @Test fun equitiesAndPegsAreNotMemePrey() {
        val K = HunterAssetClass8169.Kind
        assertEquals(K.TOKENIZED_EQUITY, HunterAssetClass8169.kind(nvdaMint, "NVDAx", 7e7, -0.5, Double.NaN, setOf(nvdaMint), false))
        assertEquals(K.TOKENIZED_EQUITY, HunterAssetClass8169.kind("XsNew11111111111111111111111111111111111111", "TSLAx", 1e8, 1.0, 2.0, emptySet(), false))
        assertEquals(K.PEGGED_OR_RECEIPT, HunterAssetClass8169.kind("9BEcn9aPEmhSPbPQeFGjidRiEKki46fVQDyPpSQXPA2D", "jlUSDC", 4.75e8, 7.2e-4, Double.NaN, emptySet(), false))
        assertEquals(K.PEGGED_OR_RECEIPT, HunterAssetClass8169.kind("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "USDC", 5e9, 0.0, 0.0, emptySet(), true))
        assertEquals(K.MEME_TRADEABLE, HunterAssetClass8169.kind("DdVXE425xZe27FhY41PR8dwfL3VczYdyJJAWDC1jpump", "OPTIMA", 62_536.0, 103.9, Double.NaN, emptySet(), false))
        assertEquals("a flat small coin is still a coin", K.MEME_TRADEABLE, HunterAssetClass8169.kind("m1", "QUIET", 900_000.0, 0.01, 0.2, emptySet(), false))
        assertEquals("an x-suffixed meme off the Xs mints", K.MEME_TRADEABLE, HunterAssetClass8169.kind("DogeXpump1111111111111111111111111111111111", "DOGEx", 5e5, 3.0, 9.0, emptySet(), false))
        assertEquals("ZEC moves: kept", K.MEME_TRADEABLE, HunterAssetClass8169.kind("A7bdiYdS5GjqGFtxf17ppRHtDKPkkRqbKtR27dxvQXaS", "ZEC", 1.2e8, -2.9, Double.NaN, emptySet(), false))
    }

    private fun row(mint: String, sym: String, mcap: Double, liq: Double, chg: Double) = MarketSweep7297.Row(
        mint = mint, symbol = sym, name = sym, priceUsd = 1.5, mcapUsd = mcap, liquidityUsd = liq,
        volumeH1Usd = liq * 0.5, volumeH24Usd = liq * 5.0, priceChangeH1Pct = chg, txCountH1 = 200, holders = 900,
        organicScore = 60.0, verified = true, ageHours = 900.0, providers = setOf("TEST_8169"),
    )

    @Test fun theHuntNeverPicksEquitiesOrReceipts() {
        val rows = ArrayList<MarketSweep7297.Row>()
        rows += row(nvdaMint, "NVDAx", 7e7, 3.4e6, -0.5)
        rows += row("9BEcn9aPEmhSPbPQeFGjidRiEKki46fVQDyPpSQXPA2D", "jlUSDC", 4.75e8, 4.75e8, 0.0007)
        for (i in 1..20) rows += row(("M8169x" + i.toString().padStart(3, '0')).padEnd(44, 'B'), "C$i", 2e6 * i, 4e5 * i, 3.0 + i)
        val picks = LaneHunter7297.hunt(MarketSweep7297.Snapshot(rows, emptyMap(), mapOf("TEST_8169" to rows.size), System.currentTimeMillis()))
        val picked = picks.values.flatten().map { it.mint }.toSet()
        assertFalse(nvdaMint in picked)
        assertFalse("9BEcn9aPEmhSPbPQeFGjidRiEKki46fVQDyPpSQXPA2D" in picked)
        assertTrue("BLUECHIP still hunts", picks["BLUECHIP"].orEmpty().isNotEmpty())
        assertTrue(LaneHunter7297.auditLine8169().contains("nonMemeExcluded8169[TOKENIZED_EQUITY=1,PEGGED_OR_RECEIPT=1"))
    }

    @Test fun picksOnRequeueCooldownRankAfterDeliverableOnes() {
        assertEquals(listOf("b", "d", "a", "c"), LaneHunter7297.deliverableFirst8169(listOf("a", "b", "c", "d")) { it == "a" || it == "c" })
        val now = 10_000_000L
        assertTrue(LaneHunter7297.onCooldown8169(now - 1_000L, now))
        assertFalse(LaneHunter7297.onCooldown8169(now - LaneHunter7297.REQUEUE_COOLDOWN_MS_8169, now))
        assertFalse(LaneHunter7297.onCooldown8169(null, now))
        assertTrue(src("engine/SolanaMarketScanner.kt").contains("LaneHunter7297.noteRequeued8169(r.mint, now7301)"))
    }

    // ── audit block ──

    @Test fun auditHelpersReadStatusLinesAndLabels() {
        assertEquals(164L, CortexAudit8169.numOf("replaying=136 observed=164 finalized=28", "observed"))
        assertNull(CortexAudit8169.numOf("unobserved=5", "observed"))
        assertEquals("ALIVE", CortexAudit8169.aliveTag(3)); assertEquals("IDLE", CortexAudit8169.aliveTag(0)); assertEquals("UNKNOWN", CortexAudit8169.aliveTag(null))
        val labels = mapOf("MARKET_HUNT_7301_EMITTED_SHITCOIN" to 4L, "LIFECYCLE/MARKET_HUNT_7301_EMITTED_QUALITY" to 2L, "X" to 9L)
        assertEquals(6L, CortexAudit8169.labelPrefixSum(labels, "MARKET_HUNT_7301_EMITTED_"))
        assertEquals(2L, CortexAudit8169.labelSum(labels, "MARKET_HUNT_7301_EMITTED_QUALITY"))
        assertEquals(7L, CortexAudit8169.residentSum("QUALITY[resident=3 qualified=0 ready=0] · CORE[resident=4 qualified=1 ready=0]"))
        CortexAudit8169.noteGate("ZZTEST", true, true, true, true)
        CortexAudit8169.noteGate("ZZTEST", false, false, false, false)
        assertTrue(CortexAudit8169.gateLine().contains("ZZTEST 2/1/1/1/1"))
    }

    @Test fun theAuditIsInTheReportAndEveryEntryPointCountsItsCallers() {
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("CortexAudit8169.append(sb, report.labelCounts, report.intakeBySource, report.nowMs)"))
        val cx = src("engine/cortex/Cortex7885.kt")
        for (k in listOf("ENTRY_REFUSAL", "OVERRULE", "FULL_AUTHORITY", "PAPER_CHOICE", "CONVICTION", "SIZE_SHAPE", "VETO_AUDIT")) {
            assertTrue(k, cx.contains("CortexAudit8169.call(\"$k\")"))
        }
        assertTrue(cx.contains("CortexAudit8169.noteGate(a.lane, true, a.bucket == CortexScoreboard7885.Bucket.REFUSE, proven || relative8019, rule != null)"))
        assertTrue(cx.contains("inc(\"REALIZED_UNBOUND_OLDER_THAN_BOOK_8169\")"))
        assertTrue(com.lifecyclebot.engine.truth.CandidateAccumulator6430.deadNote8169(0L).contains("NO_CALLERS_8169"))
        assertEquals("", com.lifecyclebot.engine.truth.CandidateAccumulator6430.deadNote8169(5L))
    }
}
