package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexOutcomeDisposition8395
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323
import com.lifecyclebot.engine.cortex.LaneGradeHorizon8406
import com.lifecyclebot.engine.truth.EpisodeTrace8395
import com.lifecyclebot.engine.truth.EpisodeTrace8395.Basis
import com.lifecyclebot.engine.truth.EpisodeTrace8395.Stage
import com.lifecyclebot.engine.truth.FieldManual7715
import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8418 item 17 — every one of the 12 meme lanes driven through one synthetic, realistic candidate on the R10 harness
 * (EpisodeTrace8395 + its frozen chronological replay), with each stage asserting the REAL output of the component that
 * owns it:
 *   discovery intake      LaneCheatSheet8323.laneFits8323 (the merge-queue routing mandate) admits in-band, refuses out-of-band
 *   native strategy       FieldManual7715.mandateFor (setup family, expected gross, invalidation)
 *   intelligence          CortexLedger7885 fusion of a seated voter -> CortexScoreboard7885 verdict bucket
 *   election / owner      EpisodeTrace8395.onElection files the episode under the canonical lane
 *   sizing                NetEdge8030.paperTicket8399 x Cortex7885.graduatedMult7955 x CellAllocator7962.combinedMult7962
 *   execution (PAPER)     ticket / submission / fill hops on the episode (observer); the paper fill itself is BLOCKED (below)
 *   holds / exits         the lane's stop (FieldManual7715.stopPct8392) and target on a price path; hold horizon
 *                         CortexLabelHygiene8144.executableHorizonMs8392
 *   verified outcome      canonical PAPER close -> REALISED basis -> replay8395 counts it once in its lane
 *   learning              CortexGradeBook8144 accepts the lane's first label once; a redelivery is DUPLICATE; ACK closes it
 *
 * BLOCKED in a JVM test (named, not faked): the canonical paper transaction (Executor.paperBuy / Executor.paperSell) needs an
 * Executor instance (BotConfig provider, SecurityGuard, SoundManager, Android Context-backed wallet/registry singletons) that no
 * JVM test can construct; the finalized-bus fan-out (CanonicalFinalizedTradeBus6464.publish) delivers to Android-persisted
 * consumers. Those two stages are represented here only by the observer hops they emit in production.
 */
class Aate8418TwelveLaneJourneyTest {

    @Before fun setUp() { EpisodeTrace8395.resetForTest() }
    @After fun tearDown() { EpisodeTrace8395.resetForTest() }

    private val lanes8418 = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE", "MOONSHOT", "PROJECT_SNIPER",
        "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")

    private fun inBandMcap8418(lane: String): Double {
        val lo = LaneCheatSheet8323.mcapMin8323(lane)
        val hi = LaneCheatSheet8323.mcapMax8323(lane)
        return if (hi < 1.0e14) (lo + hi) / 2.0 else maxOf(lo, 1.0) * 2.0 + 100_000.0
    }

    private fun seatedLedger8418(lane: String, voter: String): CortexLedger7885 {
        val ledger = CortexLedger7885()
        for (i in 0 until 240) {
            val raw = if (i % 2 == 0) 1.0 else -1.0
            ledger.grade(lane, listOf(voter), listOf(doubleArrayOf(0.0)), doubleArrayOf(raw), 12.0 * raw, 12.0 * raw)
        }
        return ledger
    }

    @Test fun theRolesTheReplayCoversAreExactlyTheTwelveMemeLanes8418() {
        assertEquals(lanes8418.toSet(), EpisodeTrace8395.ROLES_8395.toSet())
        for (lane in lanes8418) assertEquals("canonical identity of $lane", lane, EpisodeTrace8395.canonLane(lane))
    }

    @Test fun everyLaneCompletesDiscoveryToLearningOnTheR10Harness8418() {
        val book = CortexGradeBook8144()
        val history = ArrayList<EpisodeTrace8395.Episode>()
        var t = 10_000_000L
        for ((n, lane) in lanes8418.withIndex()) {
            val mint = ("J8418" + lane).padEnd(44, 'x')
            t += 3_600_000L

            // 1. discovery intake
            val mcap = inBandMcap8418(lane)
            assertTrue("$lane intake: in-band candidate mcap=$mcap must route", LaneCheatSheet8323.laneFits8323(lane, mcap, Double.NaN))
            val lo = LaneCheatSheet8323.mcapMin8323(lane)
            if (lo > 1_000.0) assertFalse("$lane intake: a coin 100x under its band must not route", LaneCheatSheet8323.laneFits8323(lane, lo / 100.0, Double.NaN))
            EpisodeTrace8395.notePre(mint, Stage.NATIVE_SCOPE, "READ", "$lane:Y mcap=${mcap.toLong()}", t)

            // 2. native strategy opinion
            val mandate = FieldManual7715.mandateFor(lane)
            assertTrue("$lane strategy: expected gross", mandate.expectedGrossPct > 0.0)
            assertTrue("$lane strategy: invalidation", mandate.invalidationPct > 0.0)
            EpisodeTrace8395.notePre(mint, Stage.TASK, "OPENED", "$lane setup=${mandate.setup.name}", t + 100L, lane)

            // 3. intelligence: the seated voter's bullish read makes the Cortex verdict STRONG
            val voter = "J8418_$lane"
            val ledger = seatedLedger8418(lane, voter)
            val fused = ledger.fuse(lane, listOf(CortexLedger7885.Vote(voter, doubleArrayOf(0.0), 1.0, setOf(voter))))
            val bucket = CortexScoreboard7885.bucketOf(fused.edgePct, fused.runnerRate, mandate.runner)
            assertEquals("$lane intelligence: fused edge ${fused.edgePct}", CortexScoreboard7885.Bucket.STRONG, bucket)
            EpisodeTrace8395.notePre(mint, Stage.ADMISSION, "ADMIT", "CORTEX_${bucket.name}", t + 200L, lane)

            // 4. election / owner
            val ep = EpisodeTrace8395.onElection("PAPER", mint, 1L, lane, t + 300L, fused.pWin, fused.edgePct, "BUY")
            assertNotNull("$lane election", ep)
            assertEquals("$lane owner", lane, ep!!.lane)

            // 5. sizing (paper ledger 10 SOL; no Cortex authority yet, unproven cell)
            val ticket = NetEdge8030.paperTicket8399(paperWalletSol = 10.0, solUsd = Double.NaN)
            val sized = ticket * CellAllocator7962.combinedMult7962(Cortex7885.graduatedMult7955(0.0, 0.0, ticket), 1.0)
            assertEquals("$lane sizing: an unproven candidate is sized at the paper ticket", 0.5, sized, 1e-9)

            // 6. execution in PAPER — observer hops at the sealed size (the fill itself is BLOCKED: see class doc)
            val attempt = "att8418-$n"; val pid = "pid8418-$n"
            EpisodeTrace8395.onTicket(attempt, "PAPER", mint, 1L, lane, t + 400L, sized)
            EpisodeTrace8395.onSubmission(attempt, "PAPER", mint, 1L, lane, t + 500L, sized)
            EpisodeTrace8395.onFill("PAPER", mint, 1L, lane, pid, t + 600L, sized)
            assertTrue("$lane execution: position opened", ep.has(Stage.POSITION))
            assertEquals(sized, ep.snapshot().first { it.stage == Stage.TICKET }.value, 0.0)

            // 7. holds / exits: the lane's own stop and target on a price path
            val stop = FieldManual7715.stopPct8392(lane, Double.NaN)
            assertEquals("$lane exit: stop is the mandate's invalidation when cost is unknown", mandate.invalidationPct, stop, 1e-9)
            val path = listOf(1.0, 1.0 - stop / 200.0, 1.0 + mandate.expectedGrossPct / 200.0, 1.0 + (mandate.expectedGrossPct + 1.0) / 100.0)
            val exitAt = path.indexOfFirst { px -> (px - 1.0) * 100.0 >= mandate.expectedGrossPct || (px - 1.0) * 100.0 <= -stop }
            assertEquals("$lane exit: the target fires on the path, the half-stop dip does not", 3, exitAt)
            val runnerHold = mandate.runner || LaneGradeHorizon8406.hourGraded8406(lane)
            val holdMs = CortexLabelHygiene8144.executableHorizonMs8392(runnerHold)
            assertTrue("$lane hold horizon", holdMs >= 60L * 60_000L)

            // 8. verified outcome: canonical PAPER close
            val retPct = (path[exitAt] - 1.0) * 100.0
            val pnlSol = sized * retPct / 100.0
            val closeAt = t + 600L + 30L * 60_000L
            EpisodeTrace8395.onDisposition(pid, "PAPER", lane, closeAt, pnlSol, retPct, "PAPER_CANONICAL", terminal = true, chainProven = false)
            val close = ep.snapshot().last { it.stage == Stage.DISPOSITION && it.verdict == "TERMINAL" }
            assertEquals("$lane outcome basis", Basis.REALISED, close.basis)
            assertEquals(pnlSol, close.value, 1e-12)

            // 9. learning: the lane's learner accepts the first label once; a redelivery is a duplicate
            val id = "$mint|$lane|${t + 300L}"
            assertTrue(book.addDecision(CortexGradeRecord8144('D', id, "$mint|$lane", t + 300L, lane, mandate.setup.name, "TREND",
                "EARLY", "LOW", "SMALL", bucket.ordinal, fused.edgePct, true, true, false)))
            assertEquals("$lane learning first", CortexOutcomeDisposition8395.ACCEPTED_FIRST,
                book.labelOutcome8395(id, retPct, retPct + 2.0, retPct, true, closeAt - (t + 300L), closeAt, closeAt))
            assertEquals("$lane learning once", CortexOutcomeDisposition8395.DUPLICATE_ACCEPTED,
                book.labelOutcome8395(id, retPct, retPct + 2.0, retPct, true, closeAt - (t + 300L), closeAt, closeAt))
            EpisodeTrace8395.onAck(pid, "Cortex7885", true, closeAt + 100L)
            val ev = ep.snapshot()
            assertEquals("$lane terminal", "DISPOSED_ACKED", EpisodeTrace8395.terminal8395(ev))
            assertEquals("$lane chronology", 0, ep.orderViolations)
            assertEquals("$lane stage order", listOf(Stage.NATIVE_SCOPE, Stage.TASK, Stage.ADMISSION, Stage.ELECTION, Stage.TICKET,
                Stage.SUBMISSION, Stage.FILL, Stage.POSITION, Stage.DISPOSITION, Stage.ACK), ev.map { it.stage }.distinct())
            history.add(ep)
        }

        // the frozen chronological replay reads all twelve lanes, each exactly once, realised money only
        val r = EpisodeTrace8395.replay8395(history, cutoffMs = Long.MAX_VALUE, live = false)
        assertEquals(0, r.unknownLane)
        for (row in r.lanes) {
            assertEquals("${row.lane} replayed once", 1, row.trainN)
            assertEquals("${row.lane} won", 1, row.trainWins)
            assertEquals(0, row.excludedUnverified)
            assertTrue(row.trainNetSol > 0.0)
        }
        // and the export round-trips (restart recovery reads only disposed episodes)
        val restored = EpisodeTrace8395.merged(EpisodeTrace8395.parseJsonl(history.joinToString("\n") { EpisodeTrace8395.toJson(it).toString() }))
        assertEquals(12, restored.size)
        assertEquals(lanes8418.toSet(), restored.map { it.lane }.toSet())
    }

    @Test fun thePaperTransactionStageIsNamedAsBlockedNotFaked8418() {
        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("Executor.paperBuy is the canonical paper entry", exec.contains("fun paperBuy(ts: TokenState, sol: Double"))
        assertTrue("Executor.paperSell is the canonical paper close", exec.contains("fun paperSell(ts: TokenState, reason: String"))
        assertTrue("and it needs an Executor (SecurityGuard, BotConfig) no JVM test constructs", exec.contains("val security: SecurityGuard,"))
    }
}
