package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.ProtectiveExitClass7807
import com.lifecyclebot.engine.sell.StopSlip8327
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8327 — relaxer on losing lanes, transient ticket defers, stop routing, bounded main-thread flushes. */
class Aate8327RuntimeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun relaxerNeverLoosensALosingLane() {
        // SHITCOIN n7 wr14% mean < 0: reversed above 1.00
        val shit = LiveRecord8327.governed8327(0.85, 0.85, doubleArrayOf(7.0, -8.0, 14.3))
        assertTrue(shit > 1.0 && shit <= 1.075 + 1e-9)
        // MOONSHOT n4 wr25% mean < 0: reversed, partially
        val moon = LiveRecord8327.governed8327(0.85, 0.85, doubleArrayOf(4.0, -3.0, 25.0))
        assertTrue(moon > 1.0 && moon < shit)
        // low WR but mean >= 0: faded to 1.00
        assertEquals(1.0, LiveRecord8327.governed8327(0.85, 0.85, doubleArrayOf(6.0, 2.0, 20.0)), 1e-9)
        // cold start and healthy lanes unchanged
        assertEquals(0.85, LiveRecord8327.governed8327(0.85, 0.85, doubleArrayOf(2.0, -50.0, 0.0)), 1e-9)
        assertEquals(0.85, LiveRecord8327.governed8327(0.85, 0.85, null), 1e-9)
        assertEquals(0.85, LiveRecord8327.governed8327(0.85, 0.85, doubleArrayOf(10.0, 5.0, 60.0)), 1e-9)
        val m = HashMap<String, DoubleArray>()
        LiveRecord8327.merge8327(m, "SHITCOIN", 7, 0.5, 14.0, -0.02)
        assertTrue(m["SHITCOIN"]!![1] < 0.0)   // SOL-losing reads losing
        assertTrue(LiveRecord8327.fromBus8327(emptyList()) { it }.isEmpty())
        assertTrue(LiveRecord8327.tag8327(doubleArrayOf(7.0, -8.0, 14.0)).contains("LIVE_LOSING_REVERSED_8327"))
        assertTrue(src("engine/LiveLayerGateRelaxer.kt").contains("LiveRecord8327.governed8327(softStartMultiplier8327(traderTag)"))
    }

    @Test fun transientDefersAreHeldThenEndByName() {
        TicketRetry8327.resetForTests8327()
        assertEquals("MUTEX_BUSY_DEFERRED", TicketRetry8327.transientCause8327("LIVE_BUY_NOT_OPENED_7948:MUTEX_BUSY_DEFERRED"))
        assertEquals("SAFETY_PROOF_PENDING_DEFERRED_8270", TicketRetry8327.transientCause8327("LIVE_BUY_NOT_OPENED_7948:SAFETY_PROOF_PENDING_DEFERRED_8270"))
        assertNull(TicketRetry8327.transientCause8327("LIVE_BUY_NOT_OPENED_7948:DEFERRED_DATA_SAFETY_PROOF_8270"))
        assertNull(TicketRetry8327.transientCause8327("LIVE_BUY_NOT_OPENED_7948"))
        val r = "LIVE_BUY_NOT_OPENED_7948:TOKEN_MAP_ROUTE_PENDING_7371"
        val t0 = 1_000_000L
        for (i in 0 until TicketRetry8327.MAX_HOLDS_8327) assertNull(TicketRetry8327.retryOrTerminal8327("QUALITY", "att1", r, t0 + i))
        assertEquals(1, TicketRetry8327.heldCount8327())
        assertEquals("TOKEN_MAP_ROUTE_PENDING_7371_RETRY_BUDGET_8327", TicketRetry8327.retryOrTerminal8327("QUALITY", "att1", r, t0 + 10))
        assertEquals("PLAIN_7948", TicketRetry8327.retryOrTerminal8327("QUALITY", "att2", "PLAIN_7948", t0))
        val h = TicketRetry8327.Hold8327("QUALITY", "a", "MUTEX_BUSY_DEFERRED", t0, 1)
        assertTrue(TicketRetry8327.mayHold8327(h, t0 + 1))
        assertFalse(TicketRetry8327.mayHold8327(h, t0 + TicketRetry8327.HOLD_TTL_MS_8327))
        assertEquals("MUTEX_BUSY_DEFERRED_RETRY_EXPIRED_8327", TicketRetry8327.expiryReason8327(h, t0 + TicketRetry8327.HOLD_TTL_MS_8327))
        assertTrue(TicketRetry8327.formatSuffix8327(2, 1, 1, 0, 0).contains("retry8327[held=2 exec=1"))
        TicketRetry8327.sweepAsync8327(t0)
        TicketRetry8327.resetForTests8327()
        assertTrue(src("engine/Executor.kt").contains("TicketRetry8327.retryOrTerminal8327(normalizeExecutionLane(layerTag), attemptId, reason) ?: return"))
        val auth = src("engine/TradeAuthorizer.kt")
        assertTrue(auth.indexOf("TicketRetry8327.preSealCooldownRefusal8327(mint, isPaperMode)") in 0 until auth.indexOf("sealedTicketAttempt7948 = sealedIntent7812.attemptId"))
    }

    @Test fun fluidLossStopIsAHardStopAndRapidSellsLeaveTheSweep() {
        assertTrue(ProtectiveExitClass7807.isEmergency("RAPID_FLUID_STOP"))
        assertEquals(ProtectiveExitClass7807.Priority.PROFIT_PROTECTION, ProtectiveExitClass7807.of("RAPID_TRAILING_STOP"))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("requestSellOffLoop7288(ts, \"RAPID_\${stopType}_STOP\", wallet, effectiveBalance)"))
        assertFalse(bot.contains("reason = \"RAPID_\${stopType}_STOP\","))
        assertTrue(StopFeed8327.chainMark8327("PUMP_PORTAL_TRADE_WS_7278"))
        assertFalse(StopFeed8327.chainMark8327("DEXSCREENER"))
        assertEquals(-20.0, StopSlip8327.pnlAt8327(0.8, 1.0), 1e-9)
        val (slip, t2f) = StopSlip8327.slip8327(-15.0, -38.0, 1_000L, 4_000L)
        assertEquals(-23.0, slip, 1e-9); assertEquals(3_000L, t2f)
    }

    @Test fun mainThreadFlushesAreBoundedAndCloseSavesOnce() {
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("private const val FLUSH_WAIT_MS_8094 = 250L"))
        assertTrue(cx.contains("private fun postCloseCheckpoint8198() { inc(\"CLOSE_CHECKPOINT_WRITTEN_BY_COMMIT_8327\") }"))
        assertTrue(src("engine/BotService.kt").contains("ShutdownFlush8327.bounded8327(\"FWD_LABELS\") { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.persistNow7731() }"))
        assertEquals(250L, ShutdownFlush8327.MAIN_WAIT_MS_8327)
    }
}
