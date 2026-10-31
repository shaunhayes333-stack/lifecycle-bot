package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8476 — owner: "fix it. this has to be a all lane trader and specialist issue everywhere" (approved tickets dying
 * before the buy: 8471 23 tickets, 2 buys) and "helius/sender first anyway as its faster and more reliable".
 */
class Aate8476TicketToBuyTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun aHeldTicketLivesForItsHold() {
        assertNull(TicketRetry8327.heldUntil8476("no-such-attempt-8476", 1_000L))
        val gate = src("engine/ExecutableOpenGate.kt").substringAfter("private fun ticketLive(").substringBefore("fun ")
        assertTrue(gate.contains("TicketRetry8327.heldUntil8476(ticket.attemptId, now)"))
        assertTrue(gate.contains("if (heldUntil8476 != null && now < heldUntil8476 && now - ticket.createdAtMs >= 0L) return true"))
        val retry = src("engine/TicketRetry8327.kt")
        assertTrue(retry.contains("heldUntil8476(h.attemptId, now8476)?.let { maxOf(e, it) } ?: e"))
        assertTrue(retry.contains("if (ticket != null && expiry8476 != null && now8476 >= expiry8476) return \"AUTHORIZATION_EXPIRED_8385\""))
    }

    @Test fun staleCurveReservesAreReadInlineAtTheBuyDoor() {
        val d = src("engine/truth/OnChainDepth8038.kt").substringAfter("fun buyRefusal8038(").substringBefore("UNVERIFIED_RESERVES_8385\n")
        val body = src("engine/truth/OnChainDepth8038.kt").substringAfter("if (observedAt8174 != null && !LaunchCohort8173.reserveFresh8174(observedAt8174, nowMs)) {")
            .substringBefore("return \"CURVE_RESERVES_STALE_DEFER_8174\"")
        assertTrue(body.contains("ParallelMarkFanout7088.curvePrices7392(listOf(ts.mint))"))
        assertTrue("the read lands before the freshness re-check", body.indexOf("curvePrices7392(listOf(ts.mint))") in 0 until body.indexOf("val refreshedAt8174"))
        assertTrue(d.isNotEmpty())
    }

    @Test fun aFlatChartIsDeadOnlyWhenNothingTrades() {
        assertTrue(FlatChart8027.deadTapePrints8476(0))
        assertTrue(FlatChart8027.deadTapePrints8476(2))
        assertFalse(FlatChart8027.deadTapePrints8476(3))
        assertEquals(3, FlatChart8027.DEAD_TAPE_MAX_PRINTS_8476)
        val f = src("engine/FlatChart8027.kt")
        assertEquals("both the live refusal and the pre-seal verdict", 2, Regex("flatCandles8076\\(bars, now, nowMs\\) && deadTape8476\\(ts\\.mint, nowMs\\)").findAll(f).count())
        // superseded (8484): executed prints AND the minutes they span (one burst candle is not a live tape)
        assertTrue(f.contains("prints8128(mint, nowMs, FLAT_WINDOW_MS).filter { !it.priceOnly }"))
        assertTrue(f.contains("deadTapePrints8476(exec.size, exec.map { it.atMs / 60_000L }.distinct().size)"))
    }

    @Test fun aDegradedJupiterBuilderSendsCurveBuysPumpFirstThroughSender() {
        assertTrue(Costs8369.jupiterBuilderDegraded8476(swapBuildDegraded = true, successRate = 1.0, samples = 0))
        assertTrue("8471: jupiter sr=16 % on 54 calls", Costs8369.jupiterBuilderDegraded8476(false, 0.16, 54))
        assertFalse("too few samples to judge", Costs8369.jupiterBuilderDegraded8476(false, 0.0, 4))
        assertFalse(Costs8369.jupiterBuilderDegraded8476(false, 0.9, 54))
        assertFalse(Costs8369.jupiterFirstForBuy8369("PUMP_CURVE", true, false, false, false, builderDegraded = true))
        assertTrue("a graduated pool still goes to the aggregator", Costs8369.jupiterFirstForBuy8369("PUMPSWAP", true, false, false, false, builderDegraded = true))
        assertTrue("unchanged when healthy", Costs8369.jupiterFirstForBuy8369("PUMP_CURVE", true, false, false, false))
        assertEquals(5, Costs8369.JUPITER_HEALTH_MIN_SAMPLES_8476)
        assertEquals(0.5, Costs8369.JUPITER_HEALTH_MIN_RATE_8476, 0.0)
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("Costs8369.pumpBuysLanding8418(), degraded8476)"))
        assertTrue("an active-curve coin is PumpPortal-routable whatever found it",
            ex.contains("PumpCurveKeys7269.keyFor(ts.mint) != null && !com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(ts.mint) } catch (_: Throwable) { false }) ||"))
    }
}
