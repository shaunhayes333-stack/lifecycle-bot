package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ChainConfirm8950
import com.lifecyclebot.engine.truth.FastLane8962
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** V5.0.8962 — the event slots belong to the signals: chain-answer re-runs are bounded, and a stage read is not a decision ping. */
class Aate8962FastLaneTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    @Before fun setUp() = FastLane8962.resetForTest8962()
    @After fun tearDown() = FastLane8962.resetForTest8962()

    @Test fun chainAnswersHoldAtMostTwoSlotsAndSignalsAlwaysEnter() {
        val chain = FastLane8962.CHAIN_ANSWER_ORIGIN_8962
        assertTrue(FastLane8962.enter8962(chain))
        assertTrue(FastLane8962.enter8962(chain))
        assertFalse("a third chain answer yields; it holds nothing", FastLane8962.enter8962(chain))
        for (signal in listOf("LAUNCH_RULE_8949", "LAUNCH_PRINTS_8942_20", "EVIDENCE_WAIT_8948", "PUMP_PORTAL_WS", "COPY_SIGNAL", "STAGE_READ_8950"))
            assertTrue("$signal is never held back by chain answers", FastLane8962.enter8962(signal))
        FastLane8962.leave8962("LAUNCH_RULE_8949")
        assertEquals("a signal leaving frees no chain-answer slot", 0, FastLane8962.chainAnswerSlotsFree8962())
        FastLane8962.leave8962(chain)
        assertTrue("a finished chain answer frees its slot", FastLane8962.enter8962(chain))
        FastLane8962.leave8962(chain); FastLane8962.leave8962(chain)
        assertEquals(FastLane8962.CHAIN_ANSWER_SLOTS_8962, FastLane8962.chainAnswerSlotsFree8962())
    }

    @Test fun everyRequestIsCountedByOriginAndOutcome() {
        FastLane8962.note8962("LAUNCH_RULE_8949", true)
        FastLane8962.note8962("LAUNCH_RULE_8949", false)
        FastLane8962.note8962("CHAIN_ANSWER_8957", false)
        FastLane8962.enter8962("CHAIN_ANSWER_8957"); FastLane8962.enter8962("CHAIN_ANSWER_8957"); FastLane8962.enter8962("CHAIN_ANSWER_8957")
        val line = FastLane8962.statusLine8962()
        assertTrue(line, line.contains("ran{LAUNCH_RULE_8949=1}"))
        assertTrue(line, line.contains("LAUNCH_RULE_8949=1") && line.contains("CHAIN_ANSWER_8957=1"))
        assertTrue(line, line.contains("chainAnswerYielded=1"))
        assertEquals("OTHER", FastLane8962.key8962("x", (1..48).map { "K$it" }.toSet()))
        assertEquals("K1", FastLane8962.key8962("k1", (1..48).map { "K$it" }.toSet()))
    }

    @Test fun aStageReadIsNotADecisionPing() {
        assertFalse("STAGE" in ChainConfirm8950.RERUN_USES_8957)
        assertEquals(setOf("DECISION", "LANE", "EVIDENCE", "EXEC"), ChainConfirm8950.RERUN_USES_8957)
        val c = src("engine/truth/ChainConfirm8950.kt")
        assertTrue("only a decision use arms the answer re-run", c.contains("if (input == Input8950.PRICE_DEPTH && use.uppercase() in RERUN_USES_8957 && due)"))
        assertTrue("only a decision use reads past the 60 s pool gap", c.contains("decisionPing8959 = use.uppercase() in RERUN_USES_8957"))
        assertTrue("the stage's own history answer still re-runs the coin", src("engine/BotService.kt").contains("\"STAGE_READ_8950\", onceKey = "))
    }

    @Test fun theFastLaneReleasesItsChainAnswerSlotOnEveryPath() {
        val b = src("engine/BotService.kt")
        val body = b.substringAfter("private fun fastLaneEvaluate7277(").substringBefore("private fun sealedEntryScore7688")
        assertTrue(body.contains("if (!com.lifecyclebot.engine.truth.FastLane8962.enter8962(origin)) { fastLaneOnce7277.remove(onceKey); return@launch }"))
        assertEquals("one enter", 1, Regex("FastLane8962\\.enter8962\\(origin\\)").findAll(body).count())
        assertEquals("leave on the acquire failure, the no-slot return and the finally", 3, Regex("FastLane8962\\.leave8962\\(origin\\)").findAll(body).count())
        assertTrue(body.indexOf("FastLane8962.enter8962(origin)") < body.indexOf("acquireFast8958(origin, fastLaneSemaphore7277)"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("FastLane8962.statusLine8962()"))
    }
}
