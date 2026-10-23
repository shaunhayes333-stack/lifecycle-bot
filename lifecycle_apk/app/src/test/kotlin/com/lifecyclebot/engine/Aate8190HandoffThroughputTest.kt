package com.lifecyclebot.engine

import com.lifecyclebot.engine.HandoffReceiverTasks8153.Block8153
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Outcome8153
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8190 — specialist hand-off throughput: a refusal is classified (judgement on fresh data / data gap / lane scope),
 * a task carries across clock buckets and newer hand-offs instead of ending, a WAIT keeps the task, the receiver record
 * defers on missing inputs, live targets are prioritised into the next cycle, every settled task leaves a causal trace,
 * the sniper keeps its pre-admission launch flow and its own hunter's pool, and one receiver stands per candidate.
 */
class Aate8190HandoffThroughputTest {
    private val tk = HandoffReceiverTasks8153
    private val t0 = 5_000_000_000L
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private fun cls(reason: String): Block8153 = HandoffRefusal8190.classify8190(reason,
        SpecialistOwnership7951.laneFitRefusal8132(reason), SpecialistOwnership7951.refusalIsJudgement8136(reason, fdg = false))

    @Before fun setUp() { tk.resetForTests8153(); HandoffTrace8190.resetForTests8190(); ReceiverRecord8190.resetForTests8190() }
    @After fun tearDown() { tk.resetForTests8153(); HandoffTrace8190.resetForTests8190(); ReceiverRecord8190.resetForTests8190() }

    // ── 1. refusal classification ──

    @Test fun refusalsAreClassifiedAsJudgementDataOrScope() {
        // (b) data gaps named in native reasons defer, never end the task.
        assertEquals(Block8153.DATA, cls("Insufficient data: thin_data hist=3"))
        assertEquals(Block8153.DATA, cls("Insufficient data: early_unknown"))
        assertEquals(Block8153.DATA, cls("RC pending"))
        assertEquals(Block8153.DATA, cls("LIQ_UNKNOWN_8190"))
        assertEquals(Block8153.DATA, cls("DEFERRED_RECEIVER_SETUP_INPUTS_8190"))
        assertEquals(Block8153.DATA, cls(HandoffRefusal8190.STALE_PREFIX_8190 + "NO_CONTINUATION"))
        // (c) a lane's own floor / band hands the task on to another eligible lane.
        assertEquals(Block8153.FIT, cls("LANE_LIQ_BELOW_FLOOR_2000_MOONSHOT"))
        assertEquals(Block8153.FIT, cls("HIGH_MCAP: \$600K > \$500K"))
        assertEquals(Block8153.FIT, cls("MCAP_TOO_LOW"))
        // "not yet" waits.
        assertEquals(Block8153.WAIT, cls("TOO_FRESH: 10s < 15s (waiting)"))
        // (a) the receiver's judgement on fresh data, and binding safety, are unchanged.
        assertEquals(Block8153.JUDGEMENT, cls("NO_CONTINUATION: 2.0% < 3.0%"))
        assertEquals(Block8153.JUDGEMENT, cls("RECEIVER_RECORD_UNSOUND_8094"))
        assertEquals(Block8153.JUDGEMENT, cls("MEME_PWIN_GATE_6604"))
        assertEquals(Block8153.BINDING, cls("RUG_DETECTED"))
        assertEquals(Block8153.BINDING, cls("ZERO_LIQ_BELOW_FLOOR"))
        // A judgement on a stale mark is a data deferral; data / fit / binding are untouched by staleness.
        assertTrue(HandoffRefusal8190.staleMark8190(1.0, t0 - HandoffRefusal8190.STALE_EVIDENCE_MS_8190 - 1, t0))
        assertTrue(HandoffRefusal8190.staleMark8190(0.0, t0, t0))
        assertFalse(HandoffRefusal8190.staleMark8190(1.0, t0 - 1_000L, t0))
        assertEquals(Block8153.DATA, HandoffRefusal8190.staleAware8190(Block8153.JUDGEMENT, true))
        assertEquals(Block8153.DATA, HandoffRefusal8190.staleAware8190(Block8153.WAIT, true))
        assertEquals(Block8153.BINDING, HandoffRefusal8190.staleAware8190(Block8153.BINDING, true))
        assertEquals(Block8153.FIT, HandoffRefusal8190.staleAware8190(Block8153.FIT, true))
        assertEquals(Block8153.JUDGEMENT, HandoffRefusal8190.staleAware8190(Block8153.JUDGEMENT, false))
        // The runtime and the ledger read the same classification.
        assertTrue(src("engine/HandoffReceiverTasks8153.kt").contains("HandoffRefusal8190.classify8190(reason, fit8153(reason), binding8153(reason))"))
        assertTrue(src("engine/truth/AdmissionHandoff8144.kt").contains("com.lifecyclebot.engine.HandoffRefusal8190.classify8190(reason, fit = false, binding = false)"))
    }

    // ── 3. a supersession / clock roll carries the task to the newer version ──

    @Test fun aClockRollCarriesTheTaskWithoutProlongingIt() {
        val t = tk.open8153(null, "mint-carry8190", "MOONSHOT", "PROJECT_SNIPER", "YIELD", 10L, t0, t0)!!
        val carried = tk.carry8190(t, 11L, t0 + 1_000L)
        assertEquals(11L, carried.version)
        assertEquals(t.validUntilMs, carried.validUntilMs)                 // never prolonged
        assertTrue(tk.due8153(carried, "MOONSHOT", t0 + 1_000L, 11L))
        assertFalse(tk.due8153(t, "MOONSHOT", t0 + 1_000L, 11L))           // the pure version rule is unchanged
        assertSame(t, tk.carry8190(t, 9L, t0 + 1_000L))                    // an older version never carries
        assertSame(t, tk.carry8190(t, 11L, t.validUntilMs + 1L))           // a lapsed task is never revived
        val unversioned = tk.open8153(null, "mint-carry8190b", "MOONSHOT", "PROJECT_SNIPER", "YIELD", -1L, t0, t0)!!
        assertSame(unversioned, tk.carry8190(unversioned, 11L, t0 + 1L))
        // Through the table: the receiver's FDG verdict on the newer bucket completes the task.
        tk.openTask8153("mint-carry8190", "MOONSHOT", "PROJECT_SNIPER", "YIELD", 10L, t0, t0)
        assertFalse(tk.isDue8153("mint-carry8190", "MOONSHOT", t0 + 1_000L, 11L))
        tk.carryForward8190("mint-carry8190", 11L, t0 + 1_000L)
        assertTrue(tk.isDue8153("mint-carry8190", "MOONSHOT", t0 + 1_000L, 11L))
        tk.onFdgVerdict8153("mint-carry8190", "MOONSHOT", false, "EDGE_VETO", t0 + 2_000L, 12L)
        val done = tk.taskOf8153("mint-carry8190", t0 + 3_000L)!!
        assertEquals(Outcome8153.EVALUATED, done.outcome)
        assertEquals(12L, done.version)
        assertTrue(src("engine/HandoffReceiverTasks8153.kt").contains(".also { HandoffReceiverTasks8153.carryForward8190(mint, it, System.currentTimeMillis()) }"))
    }

    @Test fun aNewerHandoffCarriesThePendingTaskInsteadOfRefusingIt() {
        val m = "mint-super8190"
        tk.openTask8153(m, "MOONSHOT", "PROJECT_SNIPER", "YIELD", 1L, t0, t0)
        val transferred = tk.transferTask8153(m, "MOONSHOT", "LANE_LIQ_BELOW_FLOOR", mapOf("SHITCOIN" to 50.0), t0 + 10L)
        assertEquals("SHITCOIN", transferred)
        val next = tk.openTask8153(m, "EXPRESS", "PROJECT_SNIPER", "YIELD", 2L, t0 + 100L, t0 + 100L)!!
        assertTrue(next.pending)
        assertEquals(2L, next.version)
        assertEquals(1L, next.carriedFrom8190)
        assertTrue("MOONSHOT" in next.tried)                                // the lineage of refusals is kept
        val n = tk.outcomeCounts8153()
        assertEquals(1L, n["OPENED"])
        assertEquals(1L, n["CARRIED_8190"])
        assertNull(n["REFUSED"])                                            // no SUPERSEDED_FRESH_VERSION refusal
        assertTrue(tk.statusLine8153(t0 + 200L).contains("carried=1"))
        // 8175's old-evidence rule still holds: a stamped hand-off emitted before the task never supersedes it.
        assertSame(next, tk.open8153(next, m, "QUALITY", "PROJECT_SNIPER", "OLD", 3L, t0 + 150L, t0 + 150L, newHandoffAtMs = t0 + 50L))
    }

    @Test fun anUnjudgedExpiryReopensOnFreshEvidenceButAJudgedOneKeepsTheHold() {
        val t = tk.open8153(null, "mint-reopen8190", "MOONSHOT", "PROJECT_SNIPER", "TOO_OLD", 10L, t0, t0)!!
        val stale = tk.expire8153(t, t.validUntilMs + 1L)
        assertEquals("STALE_NOT_SCHEDULED", stale.verdict)
        val at = t.validUntilMs + 2_000L
        assertTrue(tk.reopensOnFreshEvidence8190(stale, at))
        assertNotSame(stale, tk.open8153(stale, "mint-reopen8190", "MOONSHOT", "PROJECT_SNIPER", "TOO_OLD", 10L, at, at, newHandoffAtMs = t0))
        assertSame(stale, tk.open8153(stale, "mint-reopen8190", "MOONSHOT", "PROJECT_SNIPER", "TOO_OLD", 10L, stale.settledAtMs, at, newHandoffAtMs = t0))
        val judged = tk.block8153(t, "MOONSHOT", Block8153.JUDGEMENT, "NO_CONTINUATION", t0 + 1L)
        assertFalse(tk.reopensOnFreshEvidence8190(judged, t0 + 10_000L))
        assertSame(judged, tk.open8153(judged, "mint-reopen8190", "MOONSHOT", "PROJECT_SNIPER", "TOO_OLD", 10L, t0 + 10_000L, t0 + 10_000L, newHandoffAtMs = t0))
    }

    @Test fun aNativeWaitKeepsTheTaskAndIsNamedTheReceiversJudgementOnlyAtExpiry() {
        val t = tk.open8153(null, "mint-wait8190", "MOONSHOT", "PROJECT_SNIPER", "R", 10L, t0, t0)!!
        val waiting = tk.block8153(t, "MOONSHOT", Block8153.WAIT, HandoffRefusal8190.NATIVE_WAIT_PREFIX_8190 + "NATIVE_NO_BUY_WAIT", t0 + 1L)
        assertTrue(waiting.pending)
        val scheduled = tk.scheduled8153(waiting, "MOONSHOT", t0 + 2L)
        val end = tk.expire8153(scheduled, t.validUntilMs + 1L)
        assertEquals(Outcome8153.REFUSED to "RECEIVER_REFUSED:NATIVE_NO_BUY_WAIT", end.outcome to end.verdict)
        val tasks = src("engine/HandoffReceiverTasks8153.kt")
        assertTrue(tasks.contains("HandoffRefusal8190.NATIVE_WAIT_PREFIX_8190 + r, nowMs,"))
    }

    // ── 2. receiver record ──

    @Test fun receiverRecordIsUnsoundOnlyOnAGenuineLosingRecord() {
        val nt = com.lifecyclebot.engine.cortex.LanePlaybook7907.NO_TRIGGER
        val v = ReceiverRecord8190
        assertEquals(v.DEFER_NO_STATE_8190, v.verdict8190(false, false, null, emptyList(), null, t0))
        assertNull(v.verdict8190(true, true, "X", emptyList(), null, t0))
        assertNull(v.verdict8190(true, false, null, emptyList(), null, t0))
        assertEquals(v.DEFER_INPUTS_8190, v.verdict8190(true, false, nt, listOf("tape:UNKNOWN"), null, t0))
        assertEquals(v.DEFER_INPUTS_8190, v.verdict8190(true, false, nt, listOf("tape:UNKNOWN"), t0 - 1_000L, t0))
        // After the re-fetch grace the record is judged on the best data obtained.
        // V5.0.8221 — a data gap older than the grace window is a PERSISTENT data gap (re-fetched, re-armed), never a losing record.
        assertEquals(v.DEFER_DATA_GAP_PERSISTENT_8221, v.verdict8190(true, false, nt, listOf("tape:UNKNOWN"), t0 - v.INPUT_GRACE_MS_8190 - 1L, t0))
        assertEquals(v.UNSOUND_8190, v.verdict8190(true, false, nt, emptyList(), null, t0))
        assertEquals(v.UNSOUND_8190, v.verdict8190(true, false, "LAUNCH_CONTINUATION", listOf("tape:UNKNOWN"), null, t0))
        assertEquals(v.DEFER_NO_STATE_8190, v.check8190("mint-nostate8190", "MOONSHOT", t0))
        assertEquals(1L, v.counts8190()[v.DEFER_NO_STATE_8190])
        val own = src("engine/SpecialistOwnership7951.kt")
        assertTrue(own.contains("ReceiverRecord8190.check8190(mint, lane) != ReceiverRecord8190.UNSOUND_8190"))
        assertTrue(own.contains("else -> ReceiverRecord8190.check8190(mint, l)"))
        assertTrue(own.contains("if (ready && native8094 && !receiverRecordSound8094(mint, l)) {"))   // the 8094 guard stands
    }

    // ── 4. live targets are scheduled in the next cycle ──

    @Test fun liveHandoffTargetsArePrioritisedIntoTheNextCycle() {
        val targets = listOf("a" to t0 + 30_000L, "b" to t0 + 5_000L, "z" to t0 + 1_000L, "b" to t0 + 9_000L)
        assertEquals(listOf("b", "a"), HandoffPriority8190.select8190(targets, listOf("a", "b", "c")))
        assertEquals(listOf("b"), HandoffPriority8190.select8190(targets, setOf("a", "b"), max = 1))
        assertTrue(HandoffPriority8190.select8190(emptyList(), listOf("a")).isEmpty())
        val many = (1..40).map { "m$it" to t0 + it }
        assertEquals(HandoffPriority8190.MAX_TARGETS_8190, HandoffPriority8190.select8190(many, many.map { it.first }).size)
        tk.openTask8153("mint-prio8190", "MOONSHOT", "PROJECT_SNIPER", "R", 10L, t0, t0)
        assertEquals(listOf("mint-prio8190" to t0 + tk.TASK_TTL_MS_8153), tk.pendingTargets8190(t0 + 1L))
        assertTrue(tk.pendingTargets8190(t0 + tk.TASK_TTL_MS_8153 + 1L).isEmpty())
        val bot = src("engine/BotService.kt")
        // superseded by V5.0.8293: HunterWindow8293 wraps the hand-off targets (still HandoffPriority8190) plus opportunity leaders
        assertTrue(bot.contains("HunterWindow8293.windowTargets8293(all)"))
        assertTrue(bot.contains("mustInclude.addAll(HunterWindow8293.dueTargets8293(otherMints).filter { it !in mustInclude })"))
        assertTrue(src("engine/HunterWindow8293.kt").contains("HandoffPriority8190.windowTargets8190(all, nowMs)"))
        assertTrue(src("engine/HunterWindow8293.kt").contains("HandoffPriority8190.dueTargets8190(otherMints, nowMs)"))
        assertTrue(bot.contains("mustInclude.addAll(pendingLiveBuyMints8094(otherMints))"))
        assertTrue(src("engine/SpecialistElection8136.kt").contains("fun openYieldMints8190("))
    }

    // ── causal trace ──

    @Test fun everySettledTaskLeavesACausalTrace() {
        val m = "mint-trace8190"
        tk.openTask8153(m, "MOONSHOT", "PROJECT_SNIPER", "R", 10L, t0, t0)
        tk.blockTask8153(m, "MOONSHOT", Block8153.JUDGEMENT, "NO_CONTINUATION", t0 + 4_000L)
        val tr = HandoffTrace8190.recent8190().last()
        assertEquals(m, tr.mint)
        assertEquals("MOONSHOT", tr.receiver)
        assertEquals(10L, tr.version)
        assertEquals(4_000L, tr.evidenceAgeMs)
        assertEquals("JUDGEMENT", tr.kind)
        assertEquals("MOONSHOT:v10:age4s:s0:RECEIVER_REFUSED:NO_CONTINUATION", HandoffTrace8190.line8190(tr))
        val done = tk.taskOf8153(m, t0 + 5_000L)!!
        assertEquals(tr, HandoffTrace8190.traceOf8190(done))
        val line = tk.statusLine8153(t0 + 5_000L)
        assertTrue(line, line.contains("handoffTrace8190[") && line.contains("JUDGEMENT=1"))
        assertEquals("FDG", HandoffRefusal8190.kindOf8190(Outcome8153.EVALUATED, "FDG_ALLOW"))
        assertEquals("SCOPE", HandoffRefusal8190.kindOf8190(Outcome8153.REFUSED, "FIT_NO_COMPATIBLE_RECEIVER:X"))
        assertEquals("UNEVALUATED", HandoffRefusal8190.kindOf8190(Outcome8153.REFUSED, "STALE_NOT_SCHEDULED"))
        assertTrue(src("engine/HandoffReceiverTasks8153.kt").contains("verdictLine8169() + \"]\""))   // the 8169 line is kept
    }

    // ── 5. sniper / EXPRESS data-read and scope ──

    @Test fun theSniperKeepsItsPreAdmissionLaunchFlowAndItsOwnHunterPool() {
        val m = "mint-preadmit8190pump"
        val now = System.currentTimeMillis()
        WhaleDetector.clearToken(m)
        for (i in 0 until 4) WhaleDetector.recordLaunchOnly8190(m, "w$i", 0.2, true, now - 10_000L + i * 1_000L)
        val flow = WhaleDetector.launchFlow7401(m, "dev-none", now)
        assertEquals(4, flow.buyTx60s)
        assertEquals(4, flow.distinctBuyers60s)
        WhaleDetector.clearToken(m)
        assertTrue(WhaleDetector.PRE_ADMISSION_MINTS_8190 in 1..10_000)
        assertTrue(src("engine/DataOrchestrator.kt").contains("?: run { WhaleDetector.recordLaunchOnly8190(mint, wallet, solAmount, isBuy); return }"))
        assertTrue(SniperScope8190.ownHunterPool8190(noDeskHypothesis = true, huntedByOwnHunter = true))
        assertFalse(SniperScope8190.ownHunterPool8190(noDeskHypothesis = false, huntedByOwnHunter = true))
        assertFalse(SniperScope8190.ownHunterPool8190(noDeskHypothesis = true, huntedByOwnHunter = false))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("SniperScope8190.ownHunterPool8190(sniperSetup6599 == null, huntedByLane7297)"))
        assertTrue(bot.contains("PROJECT_SNIPER_DESIGNATED_POOL_REJECTED_6599"))
        // An in-window pump launch that arrived by another path is put on the trade stream (bounded lifecycle set).
        assertTrue(src("engine/truth/LaunchBirth8169.kt").contains("com.lifecyclebot.network.PumpFunWS.watchLaunch8190(ts.mint)"))
        assertTrue(src("network/PumpFunWS.kt").contains("fun watchLaunch8190(mint: String)"))
    }

    @Test fun expressReadsAContinuationInsteadOfAFlatZeroOnNoHistory() {
        val x = ExpressMomentum8190
        assertEquals(12.0, x.momentum8190(12.0, 3.0, 5, 40.0), 1e-9)
        assertEquals(3.0, x.momentum8190(0.0, 3.0, 5, 40.0), 1e-9)
        assertEquals(40.0, x.momentum8190(0.0, 0.0, 1, 40.0), 1e-9)
        assertEquals(0.0, x.momentum8190(0.0, 0.0, 0, null), 1e-9)
        // superseded by V5.0.8348 — the chain (1 h, history, launch price) now lives in ContinuationRead8348 with the tape first inside the launch hour
        assertTrue(src("engine/SpecialistBrainBridge7542.kt").contains("val expressMomentum7555 = cont8348.pct"))
        assertTrue(src("engine/SpecialistBrainBridge7542.kt").contains("ExpressMomentum8190.sinceLaunchPct8190(ts, ageMin)"))
    }

    @Test fun manipulatedReadsAnUnhydratedCapAsDataAndTheLedgerHandsOnLaneFloors() {
        assertEquals(Block8153.DATA, cls(com.lifecyclebot.v3.scoring.ManipulatedTraderAI.MCAP_UNHYDRATED_DEFER_8190))
        val manip = src("v3/scoring/ManipulatedTraderAI.kt")
        assertTrue(manip.indexOf("return noEnter(MCAP_UNHYDRATED_DEFER_8190)") in 0 until manip.indexOf("return noEnter(\"MCAP_TOO_LOW\")"))
        val ledger = src("engine/truth/AdmissionHandoff8144.kt")
        assertTrue(ledger.contains("com.lifecyclebot.engine.HandoffTaskRuntime8153.blockOf8153(r) == com.lifecyclebot.engine.HandoffReceiverTasks8153.Block8153.FIT"))
    }

    // ── 6. one executable owner per candidate ──

    @Test fun oneReceiverStandsPerCandidateThroughCarries() {
        val m = "mint-owner8190"
        tk.openTask8153(m, "MOONSHOT", "PROJECT_SNIPER", "R", 10L, t0, t0)
        tk.carryForward8190(m, 11L, t0 + 1L)
        // A second lane's same-version hand-off does not open a second task: the first live receiver stands.
        val same = tk.openTask8153(m, "EXPRESS", "PROJECT_SNIPER", "R", 11L, t0 + 2L, t0 + 2L)!!
        assertEquals("MOONSHOT", same.lane)
        assertTrue(tk.isDue8153(m, "MOONSHOT", t0 + 3L, 11L))
        assertFalse(tk.isDue8153(m, "EXPRESS", t0 + 3L, 11L))
        // A carried successor replaces (never duplicates) the task: one per mint.
        tk.openTask8153(m, "EXPRESS", "PROJECT_SNIPER", "R", 12L, t0 + 4L, t0 + 4L)
        assertTrue(tk.isDue8153(m, "EXPRESS", t0 + 5L, 12L))
        assertFalse(tk.isDue8153(m, "MOONSHOT", t0 + 5L, 12L))
        assertEquals(1, tk.pendingTargets8190(t0 + 5L).size)
    }
}
