package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope
import com.lifecyclebot.engine.truth.FinalizedRepairStore8858
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8858 — a close the reconciler repaired onto the finalized bus is published once: the envelope is durable, the next
 * start publishes that exact envelope after the rich finality replay, and the reconciler does not derive it again.
 */
class Aate8858DurableBusRepairTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun env(position: String, ret: Double = -12.5, mfe: Double = Double.NaN) = Envelope(
        tradeId = position, atMs = 1_795_000_000_000L, realizedPnlSol = ret / 1000.0, realizedReturnPct = ret, mint = "Mint$position",
        lane = "SHITCOIN", positionId = position, mode = "paper", proofState = "PAPER_SIMULATED", holdingTimeMs = 61_000L, entryScore = 44,
        entryTactic = "REACCUMULATION", entrySetup = "BASE", entrySource = "PUMP_FUN_NEW", scoreBand = "40-50", mfePct = mfe, maePct = -20.0,
        learningEligible = true, learningEligibilityReason = "ELIGIBLE", economicEventId = "PAPER_SELL_$position",
        exitReason = "DURABLE_BUS_REPAIR_7459", dataQuality = "DURABLE_TYPED_SELL_RECONSTRUCTED_7459")

    @After fun tearDown() { FinalizedRepairStore8858.resetForTest8858() }

    @Test fun theStoredEnvelopeIsTheEnvelopeThatWasPublished() {
        val e = env("PAPER:A", mfe = 38.25)
        assertEquals(e, FinalizedRepairStore8858.decode8858(FinalizedRepairStore8858.encode8858(e)))
        // an unobserved excursion stays unobserved (never a stored 0)
        val unknown = FinalizedRepairStore8858.decode8858(FinalizedRepairStore8858.encode8858(env("PAPER:B")))!!
        assertTrue(unknown.mfePct.isNaN()); assertEquals(-20.0, unknown.maePct, 0.0)
        assertFalse(FinalizedRepairStore8858.encode8858(env("PAPER:B")).contains("\"mfe\""))
    }

    @Test fun aLineThatIsNotACompleteTerminalIsNotPublished() {
        assertNull(FinalizedRepairStore8858.decode8858("not json"))
        assertNull(FinalizedRepairStore8858.decode8858("{\"tradeId\":\"t\",\"positionId\":\"\",\"lane\":\"CORE\",\"pnl\":1,\"ret\":1}"))
        assertNull(FinalizedRepairStore8858.decode8858("{\"tradeId\":\"t\",\"positionId\":\"p\",\"lane\":\"\",\"pnl\":1,\"ret\":1}"))
        assertNull("no economics", FinalizedRepairStore8858.decode8858("{\"tradeId\":\"t\",\"positionId\":\"p\",\"lane\":\"CORE\"}"))
    }

    @Test fun oneEnvelopePerPositionTheFirstRecordedAndOnlyWhatTheBusLacks() {
        val first = FinalizedRepairStore8858.encode8858(env("PAPER:A", ret = -12.5))
        val later = FinalizedRepairStore8858.encode8858(env("PAPER:A", ret = +99.0))
        val b = FinalizedRepairStore8858.encode8858(env("PAPER:B"))
        val c = FinalizedRepairStore8858.encode8858(env("PAPER:C"))
        val plan = FinalizedRepairStore8858.plan8858(listOf(first, "", "garbage", b, later, c), onBus = setOf("PAPER:B"))
        assertEquals(listOf("PAPER:A", "PAPER:C"), plan.publish.map { it.positionId })
        assertEquals("the envelope the consumers were first given", -12.5, plan.publish.first().realizedReturnPct, 0.0)
        assertEquals(listOf(first, b, c), plan.keep)
        assertEquals(1, plan.unreadable); assertEquals(1, plan.onBus)
        assertTrue(FinalizedRepairStore8858.plan8858(emptyList(), emptySet()).publish.isEmpty())
    }

    @Test fun aRepairIsRecordedOncePerPositionAndReadBackAfterARestart() {
        val f = File.createTempFile("repair8858", ".jsonl").apply { delete(); deleteOnExit() }
        FinalizedRepairStore8858.attachForTest8858(f)
        FinalizedRepairStore8858.record8858(env("PAPER:A"))
        FinalizedRepairStore8858.record8858(env("PAPER:A", ret = 5.0))
        FinalizedRepairStore8858.record8858(env("PAPER:B"))
        assertEquals(2, f.readLines().size)
        assertTrue(FinalizedRepairStore8858.statusLine8858().startsWith("repairStore8858[held=2 restoredAtStart=0 recordedThisRun=2 "))
        // the next process: nothing in memory, the file alone
        FinalizedRepairStore8858.resetForTest8858()
        val plan = FinalizedRepairStore8858.plan8858(f.readLines(), emptySet())
        assertEquals(listOf(env("PAPER:A"), env("PAPER:B")), plan.publish)
        // before the store is attached nothing is written (the repair then derives it again next start, as before)
        FinalizedRepairStore8858.record8858(env("PAPER:C"))
        assertEquals(2, f.readLines().size)
    }

    @Test fun theRepairWaitsForTheReplayButNeverForever() {
        val t0 = 1_000_000L
        assertFalse(FinalizedRepairStore8858.settled8858(t0))
        assertFalse(FinalizedRepairStore8858.settled8858(t0 + FinalizedRepairStore8858.REPLAY_WAIT_MAX_MS_8858 - 1))
        assertTrue("the wait cannot strand a close", FinalizedRepairStore8858.settled8858(t0 + FinalizedRepairStore8858.REPLAY_WAIT_MAX_MS_8858))
        FinalizedRepairStore8858.resetForTest8858()
        assertFalse(FinalizedRepairStore8858.settled8858(t0))
        FinalizedRepairStore8858.markReplayComplete8858()
        assertTrue(FinalizedRepairStore8858.settled8858(t0 + 1))
    }

    @Test fun theReplayRestoresBeforeTheReconcilerRepairs() {
        val persistence = src("engine/truth/CanonicalFinalityPersistence6486.kt")
        val init = persistence.substringAfter("fun initAndReplay")
        val rich = init.indexOf("events.forEach { CanonicalTradeFinalizedBus6450.publish(it) }")
        val restore = init.indexOf("FinalizedRepairStore8858.restore8858(context)")
        assertTrue("rich finality rows first, then the repaired closes", rich >= 0 && restore > rich)
        val rec = src("engine/truth/FinalizedLearningReconciler7423.kt")
        val fn = rec.substringAfter("fun repairDurableBusPublishFailures7459")
        assertTrue(fn.indexOf("if (!FinalizedRepairStore8858.settled8858(started7514))") in 0 until fn.indexOf("closedPositions()"))
        assertTrue("only a close no rich row holds is kept here", fn.contains("if (rich == null) try { FinalizedRepairStore8858.record8858(env) }"))
        assertTrue(rec.contains("FinalizedRepairStore8858.statusLine8858()"))
        assertFalse("finality evidence is not a learning store",
            src("engine/truth/LearningResetSweep7781.kt").contains("finalized_repair_8858"))
    }

    @Test fun aDurableExclusionIsTerminalAndReplayedClosesTeachNothingTwice() {
        val bus = src("engine/truth/CanonicalFinalizedTradeBus6464.kt")
        val deliver = bus.substringAfter("private fun deliverOne6734").substringBefore("fun deliverToConsumers")
        val excluded = deliver.indexOf("CanonicalFinalityPersistence6486.hasExclusion8858(name, env.tradeId)")
        assertTrue("checked before the consumer is called", excluded in 0 until deliver.indexOf("val ok = try { deliver(name, env) }"))
        assertTrue(src("engine/truth/CanonicalFinalityPersistence6486.kt").contains("if (migrated8094) excluded8094[consumer]?.containsKey(tradeId) == true"))
        // the selection ring is fed inside publish(): it honours the reset line the consumer bridge honours
        assertTrue(bus.contains("if (finalLearningEligible6831 && env.lane.isNotBlank() && !LearningEpoch8164.preEpoch8164(env.atMs)) {"))
        // a replayed close is not a sell decision made now
        val reasons = src("engine/DecisionReasons8031.kt").substringAfter("fun sell8031").substringBefore("private fun add")
        assertTrue(reasons.indexOf("ExitRegret7752.FRESH_CLOSE_MS_7752) return") in 0 until reasons.indexOf("BotService.status.tokens"))
    }
}
