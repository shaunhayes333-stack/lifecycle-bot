package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AateDecisionEnvelope6512
import com.lifecyclebot.engine.truth.AateDecisionFabric6512
import com.lifecyclebot.engine.truth.AateStrategyContext6512
import com.lifecyclebot.engine.truth.EpisodeTrace8395
import com.lifecyclebot.engine.truth.EpisodeTrace8395.Basis
import com.lifecyclebot.engine.truth.EpisodeTrace8395.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** V5.0.8395 R10 — per-episode trace from native scope to learner ack, and the frozen chronological replay. */
class Aate8395EpisodeTraceTest {
    private val gen get() = BotRuntimeController.currentGeneration()

    @Before fun setUp() { EpisodeTrace8395.resetForTest() }
    @After fun tearDown() { EpisodeTrace8395.resetForTest() }

    private fun key(mode: String, mint: String, v: Long, lane: String) = EpisodeTrace8395.episodeKey(mode, mint, v, lane, gen)

    @Test fun oneEpisodeCarriesEveryHopInOrderAndALaterReadIsNotFiledIntoIt() {
        val m = "MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1"
        EpisodeTrace8395.notePre(m, Stage.NATIVE_SCOPE, "READ", "MOONSHOT:Y,QUALITY:N", 1_000L)
        EpisodeTrace8395.notePre(m, Stage.RECEIVER, "HANDOFF", "QUALITY->MOONSHOT", 1_100L)
        EpisodeTrace8395.notePre(m, Stage.TASK, "OPENED", "QUALITY->MOONSHOT v7", 1_200L, "MOONSHOT", materialAtMs = 1_150L)
        EpisodeTrace8395.notePre(m, Stage.TASK, "OPENED", "other lane", 1_250L, "TREASURY")
        EpisodeTrace8395.notePre(m, Stage.NATIVE_SCOPE, "READ", "LATER READ", 9_000L)   // after the election
        val ep = EpisodeTrace8395.onElection("PAPER", m, 7L, "MOONSHOT", 2_000L, 0.61, 14.0, "BUY")!!
        EpisodeTrace8395.noteForLatest("PAPER", m, "MOONSHOT", Stage.ECONOMICS, "CLEAR", "", 2_100L, Basis.MODELLED, 3.1)
        EpisodeTrace8395.onTicket("att-7", "PAPER", m, 7L, "MOONSHOT", 2_200L, 0.05)
        EpisodeTrace8395.onSubmission("att-7", "PAPER", m, 7L, "MOONSHOT", 2_300L, 0.05)
        EpisodeTrace8395.onFill("PAPER", m, 7L, "MOONSHOT", "pid-7", 2_400L, 0.0502)
        EpisodeTrace8395.onDisposition("pid-7", "PAPER", "MOONSHOT", 5_000L, 0.012, 24.0, "PAPER_CANONICAL", terminal = true, chainProven = false)
        EpisodeTrace8395.onAck("pid-7", "Cortex7885", true, 5_100L)

        val ev = ep.snapshot()
        val stages = ev.map { it.stage }.distinct()
        assertEquals(listOf(Stage.NATIVE_SCOPE, Stage.RECEIVER, Stage.TASK, Stage.ELECTION, Stage.ECONOMICS, Stage.TICKET,
            Stage.SUBMISSION, Stage.FILL, Stage.POSITION, Stage.DISPOSITION, Stage.ACK), stages)
        assertFalse("a read after the election must not be filed into the decision", ev.any { it.reason == "LATER READ" })
        assertFalse("another lane's task is not this episode's", ev.any { it.reason == "other lane" })
        assertEquals(1_150L, ep.materialAtMs)
        assertEquals(Basis.MODELLED, ev.first { it.stage == Stage.ECONOMICS }.basis)
        assertEquals(Basis.REALISED, ev.first { it.stage == Stage.DISPOSITION }.basis)
        assertEquals("DISPOSED_ACKED", EpisodeTrace8395.terminal8395(ev))
        assertEquals(0, ep.orderViolations)
    }

    @Test fun fillBindsTheSealedVersionEvenAfterAReassessment() {
        val m = "MintBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB2"
        EpisodeTrace8395.onElection("LIVE", m, 3L, "CORE", 1_000L, 0.55, 6.0, "BUY")
        EpisodeTrace8395.onTicket("att-A", "LIVE", m, 3L, "CORE", 1_100L, 0.03)
        EpisodeTrace8395.onElection("LIVE", m, 4L, "CORE", 1_200L, 0.20, -3.0, "BUY")   // reassessment B
        EpisodeTrace8395.fillSafe8395("LIVE", m, 4L, "CORE", "pid-A", 0.0301, "att-A")
        assertTrue(EpisodeTrace8395.episodeFor(key("LIVE", m, 3L, "CORE"))!!.has(Stage.FILL))
        assertFalse(EpisodeTrace8395.episodeFor(key("LIVE", m, 4L, "CORE"))!!.has(Stage.FILL))
        assertEquals(3L, EpisodeTrace8395.episodeForPosition("pid-A")!!.version)
    }

    @Test fun anUnverifiedLiveCloseAndModelledValuesAreNeverRealisedOutcomes() {
        val m = "MintCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC3"
        EpisodeTrace8395.onElection("LIVE", m, 1L, "EXPRESS", 1_000L, 0.9, 50.0, "BUY")
        EpisodeTrace8395.onTicket("att-c", "LIVE", m, 1L, "EXPRESS", 1_010L, 0.03)
        EpisodeTrace8395.onFill("LIVE", m, 1L, "EXPRESS", "pid-c", 1_020L, 0.03)
        EpisodeTrace8395.onDisposition("pid-c", "LIVE", "EXPRESS", 2_000L, 0.5, 900.0, "SELL_SUBMITTED", terminal = true, chainProven = false)
        val ep = EpisodeTrace8395.episodeForPosition("pid-c")!!
        assertEquals(Basis.UNVERIFIED, ep.snapshot().last { it.stage == Stage.DISPOSITION }.basis)
        val r = EpisodeTrace8395.replay8395(listOf(ep), cutoffMs = 10_000L, live = true).lanes.first { it.lane == "EXPRESS" }
        assertEquals(0, r.trainN); assertEquals(0.0, r.trainNetSol, 0.0); assertEquals(1, r.excludedUnverified)
    }

    @Test fun anEconomicsDeferWithoutTicketIsANamedStop() {
        val m = "MintDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD4"
        val ep = EpisodeTrace8395.onElection("PAPER", m, 2L, "TREASURY", 1_000L, 0.5, 2.0, "BUY")!!
        EpisodeTrace8395.latestSafe8395(m, "TREASURY", Stage.ECONOMICS, "DEFER", "ADMISSION_COST_REQUOTE_UNAVAILABLE_8395")
        assertEquals("STOPPED_AFTER_ELECTION:ECONOMICS_DEFER", EpisodeTrace8395.terminal8395(ep.snapshot()))
    }

    @Test fun onlyTerminallyDisposedEpisodesSurviveTheExportAndRoundTripExactly() {
        val m = "MintEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE5"
        val done = EpisodeTrace8395.onElection("PAPER", m, 1L, "CASHGEN", 1_000L, 0.6, 4.0, "BUY")!!
        EpisodeTrace8395.onTicket("att-e", "PAPER", m, 1L, "CASHGEN", 1_100L, 0.04)
        EpisodeTrace8395.onFill("PAPER", m, 1L, "CASHGEN", "pid-e", 1_200L, 0.04)
        EpisodeTrace8395.onDisposition("pid-e", "PAPER", "CASHGEN", 3_000L, -0.004, -10.0, "PAPER_CANONICAL", true, false)
        val open = EpisodeTrace8395.onElection("PAPER", "MintOpen", 1L, "CASHGEN", 1_500L, 0.6, 4.0, "BUY")!!
        val text = listOf(done, open).joinToString("\n") { EpisodeTrace8395.toJson(it).toString() }
        val back = EpisodeTrace8395.merged(EpisodeTrace8395.parseJsonl(text))
        assertEquals(1, back.size)
        val b = back.single()
        assertEquals(done.key, b.key); assertEquals("pid-e", b.positionId)
        assertEquals(done.snapshot().map { it.stage to it.basis }, b.snapshot().map { it.stage to it.basis })
        assertEquals(-0.004, b.snapshot().first { it.stage == Stage.DISPOSITION }.value, 1e-12)
        assertTrue(b.snapshot().first { it.stage == Stage.ELECTION && it.reason == "P_WIN" }.value == 0.6)
    }

    private fun closed(mode: String, lane: String, mint: String, electedAt: Long, closedAt: Long, net: Double,
                       basis: Basis = Basis.REALISED): EpisodeTrace8395.Episode {
        val ep = EpisodeTrace8395.Episode(EpisodeTrace8395.episodeKey(mode, mint, 1L, lane, 0L), mode, mint, 1L, lane, 0L, electedAt)
        ep.events.add(EpisodeTrace8395.Event(Stage.ELECTION, "BUY", "EXPECTED_NET_PCT", Basis.MODELLED, electedAt, 99.0, lane))
        ep.events.add(EpisodeTrace8395.Event(Stage.FILL, "CONFIRMED", "", Basis.REALISED, electedAt + 1, 0.05, lane))
        ep.events.add(EpisodeTrace8395.Event(Stage.DISPOSITION, "TERMINAL", "P", basis, closedAt, net, lane))
        return ep
    }

    @Test fun frozenChronologicalReplayCoversAllTwelveRolesWithoutFutureLeakage() {
        assertEquals(12, EpisodeTrace8395.ROLES_8395.size)
        val cutoff = 100_000L
        val hist = ArrayList<EpisodeTrace8395.Episode>()
        EpisodeTrace8395.ROLES_8395.forEachIndexed { i, role ->
            hist += closed("PAPER", role, "T$i", 1_000L + i, 2_000L + i, 0.01)            // train win
            hist += closed("PAPER", role, "L$i", 3_000L + i, 4_000L + i, -0.02)           // train loss
            hist += closed("PAPER", role, "S$i", 90_000L + i, 150_000L + i, 5.0)          // straddles the cutoff
            hist += closed("PAPER", role, "V$i", 120_000L + i, 130_000L + i, 0.03)        // validation
        }
        hist += closed("PAPER", "MYSTERY", "U", 1_000L, 2_000L, 1.0)
        val r = EpisodeTrace8395.replay8395(hist, cutoff, live = false)
        assertEquals(EpisodeTrace8395.ROLES_8395, r.lanes.map { it.lane })
        assertEquals(1, r.unknownLane)
        for (l in r.lanes) {
            assertEquals(l.lane, 2, l.trainN); assertEquals(l.lane, -0.01, l.trainNetSol, 1e-12); assertEquals(1, l.trainWins)
            assertEquals(l.lane, 1, l.validN); assertEquals(0.03, l.validNetSol, 1e-12)
            assertEquals("the straddler's future outcome is in neither period", 1, l.straddled)
        }
        // Changing every validation-period outcome cannot move a single training number.
        val mutated = hist.map { ep -> if (ep.mint.startsWith("V") || ep.mint.startsWith("S"))
            closed("PAPER", ep.lane, ep.mint, ep.electedAtMs, ep.snapshot().last().atMs, -50.0) else ep }
        val r2 = EpisodeTrace8395.replay8395(mutated, cutoff, live = false)
        assertEquals(r.lanes.map { Triple(it.trainN, it.trainNetSol, it.trainWins) }, r2.lanes.map { Triple(it.trainN, it.trainNetSol, it.trainWins) })
        // Modelled expected net (99%) never appears as realised money.
        assertTrue(r.lanes.all { it.trainNetSol < 1.0 })
        // live/paper are separate populations
        assertTrue(EpisodeTrace8395.replay8395(hist, cutoff, live = true).lanes.all { it.trainN == 0 && it.validN == 0 })
        val text = EpisodeTrace8395.render8395(r)
        EpisodeTrace8395.ROLES_8395.forEach { assertTrue(it, text.contains(it)) }
    }

    @Test fun everyRoleIsItsOwnCanonicalDeskAndExpressAliasCollapses() {
        EpisodeTrace8395.ROLES_8395.forEach { assertEquals(it, EpisodeTrace8395.canonLane(it)) }
        assertEquals("EXPRESS", EpisodeTrace8395.canonLane("SHITCOIN_EXPRESS"))
        assertEquals("BLUECHIP", EpisodeTrace8395.canonLane("blue_chip"))
    }

    @Test fun chronologicalCutoffSplitsByDecisionTime() {
        val hist = (1..10).map { closed("PAPER", "CORE", "M$it", it * 1_000L, it * 1_000L + 10, 0.0) }.shuffled()
        assertEquals(8_000L, EpisodeTrace8395.chronologicalCutoff8395(hist))
        assertEquals(0L, EpisodeTrace8395.chronologicalCutoff8395(emptyList()))
    }

    @Test fun productionFabricElectionAndTicketOpenTheEpisode() {
        val m = "MintFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF6"
        val ctx = AateStrategyContext6512("cand-f", gen, "PAPER", m, "F", 11L, "DIP_HUNTER", "test", "NEUTRAL")
        val env = AateDecisionEnvelope6512("env-f", 1L, ctx, "BUY", 0.58, 7.5, null, null, 60.0, 62.0, 0.04, 0.04, "DIP",
            emptyList(), emptyList(), "TEST")
        try {
            AateDecisionFabric6512.record(env)
            assertNotNull(AateDecisionFabric6512.sealForExecution("att-f", "PAPER", m, 11L, "DIP_HUNTER"))
            val ep = EpisodeTrace8395.episodeFor(key("PAPER", m, 11L, "DIP_HUNTER"))
            assertNotNull("fabric election opens the episode", ep)
            assertTrue(ep!!.has(Stage.ELECTION)); assertTrue(ep.has(Stage.TICKET))
            assertEquals(Basis.MODELLED, ep.snapshot().first { it.reason == "EXPECTED_NET_PCT" }.basis)
        } finally { AateDecisionFabric6512.resetForTest() }
    }
}
