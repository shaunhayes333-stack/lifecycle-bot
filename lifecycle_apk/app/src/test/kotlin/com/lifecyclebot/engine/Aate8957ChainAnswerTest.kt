package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ChainConfirm8950
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** V5.0.8957 — a chain ping for a data defer re-runs the coin when the answers land; it never waits for the scan loop. */
class Aate8957ChainAnswerTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    @After fun tearDown() { ChainConfirm8950.answered8957 = null }

    @Test fun priceAndStageWaitsDoNotStartTimers() {
        val price = ChainConfirm8950.Input8950.PRICE_DEPTH
        val bars = ChainConfirm8950.Input8950.HISTORY
        assertFalse(ChainConfirm8950.needsTimedFallback8959(setOf(price)))
        assertFalse(ChainConfirm8950.needsTimedFallback8959(setOf(price, bars)))
        assertTrue(ChainConfirm8950.needsTimedFallback8959(setOf(price, ChainConfirm8950.Input8950.LP)))
    }

    @Test fun transportDelayCannotExtendTheRunnerWindow() {
        val router = com.lifecyclebot.engine.truth.LaunchSniper8748
        val fire = com.lifecyclebot.engine.truth.LaunchSniper8748.Fire("mint", 15000L, 0L, 100.0, "rule", 90, 5.0, 1.0, 0.5, true, 5000L)
        assertTrue(router.liveFire8959(fire, 125000L))
        assertFalse(router.liveFire8959(fire, 125001L))
        assertFalse(router.liveFire8959(fire, 14000L))
        assertFalse(router.liveFire8959(fire.copy(armed = false), 16000L))
    }

    @Test fun realMarketAnswerIsExactFreshAndConsumedOnce() {
        val c = ChainConfirm8950
        val m = "timing8959"
        c.armDepth8959(m, 10000L)
        assertNull(c.claimDepth8959("other", 10100L, 10120L))
        assertNull(c.claimDepth8959(m, 9999L, 10120L))
        assertNull(c.claimDepth8959(m, 10200L, 10120L))
        assertEquals(10000L, c.claimDepth8959(m, 10100L, 10120L))
        assertNull(c.claimDepth8959(m, 10110L, 10120L))
        c.armDepth8959(m, 20000L)
        assertNull(c.claimDepth8959(m, 20010L, 27000L))
        assertEquals(20000L, c.claimDepth8959(m, 29000L, 29020L))
    }

    @Test fun answeredPingRunsTheCoinAgainOncePerScheduledPass() {
        val mint = "So11111111111111111111111111111111111111112"
        val keys = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(2)
        ChainConfirm8950.answered8957 = { m, k -> assertEquals(mint, m); keys += k; latch.countDown() }
        val t0 = System.currentTimeMillis()
        ChainConfirm8950.rerun8957(mint, t0)
        assertTrue("both passes ran", latch.await(9, TimeUnit.SECONDS))
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("the answers get time to land, but the loop is not awaited: $elapsed ms", elapsed in 6_500L..9_000L)
        assertEquals("one key per pass, deduplicated per coin and slot", 2, keys.toSet().size)
        assertTrue(keys.all { it.startsWith("$mint|8957|") })
    }

    @Test fun decisionDefersAreReRunButHoldsAndExitsKeepTheirOwnLoops() {
        assertEquals(setOf("DECISION", "LANE", "EVIDENCE", "STAGE", "EXEC"), ChainConfirm8950.RERUN_USES_8957)
        assertEquals(listOf(2_500L, 7_000L), ChainConfirm8950.RERUN_AFTER_MS_8957)
        val c = src("engine/truth/ChainConfirm8950.kt")
        assertTrue(c.contains("if (askedAny && use.uppercase() in RERUN_USES_8957 && needsTimedFallback8959(inputs)) rerun8957(ts.mint, nowMs)"))
        assertTrue("only a ping actually sent re-runs (the 20 s per-input gap still binds)", c.contains("if (!due) return false"))
        val b = src("engine/BotService.kt")
        assertTrue(b.contains("com.lifecyclebot.engine.truth.ChainConfirm8950.answered8957 = { mint, onceKey ->"))
        assertTrue(b.contains("fastLaneEvaluate7277(mint, ConfigStore.load(applicationContext), \"CHAIN_ANSWER_8957\", onceKey = onceKey)"))
    }
}
