package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.SellDoor8435
import com.lifecyclebot.engine.sell.SellRouteSpine8144
import com.lifecyclebot.engine.sell.SellRouteSpine8144.Stage8144
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8435 — 8434: a HARD_STOP spent 35 attempts / 20.5 s before its broadcast and the spine said failStage
 * TOKEN_BALANCE=35, a stage none of those attempts reached. A pre-route attempt is charged to the lease stage under the
 * last sell row it logged, and no exit the shared emergency class protects is deferred by the recovery lock.
 */
class Aate8435SellSpineCauseTest {
    @Before fun setUp() { SellRouteSpine8144.resetForTest8144() }
    @After fun tearDown() { SellRouteSpine8144.resetForTest8144() }

    @Test fun aCauseIsThePhaseAndTheFirstNameItLogged() {
        assertEquals("SELL_VERIFY_INCONCLUSIVE_PENDING:RECOVERY_LOCK",
            SellRouteSpine8144.causeKey8435("SELL_VERIFY_INCONCLUSIVE_PENDING", "🔒 RECOVERY_LOCK: deferring discretionary x exit"))
        assertEquals("SELL_VERIFY_INCONCLUSIVE_PENDING:SELL_BLOCKED_ALREADY_IN_PROGRESS",
            SellRouteSpine8144.causeKey8435("SELL_VERIFY_INCONCLUSIVE_PENDING", "SELL_BLOCKED_ALREADY_IN_PROGRESS lock=true ageMs=3"))
        assertEquals("INFO", SellRouteSpine8144.causeKey8435("INFO", "ok"))
        assertTrue(SellRouteSpine8144.preRoute8435(Stage8144.CLOSE_LEASE, false))
        assertFalse(SellRouteSpine8144.preRoute8435(Stage8144.CLOSE_LEASE, true))
        assertFalse(SellRouteSpine8144.preRoute8435(Stage8144.TOKEN_BALANCE, false))
    }

    @Test fun aPreRouteAttemptIsChargedToTheLeaseUnderItsOwnCause() {
        val m = "MintPreRoute8435aaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val b = System.currentTimeMillis() - 10_000L   // the status line prunes spines older than 10 min by wall clock
        SellRouteSpine8144.trigger8144(m, "HARD_STOP", "p", b + 1_000L)
        SellRouteSpine8144.mark8144(m, Stage8144.CLOSE_LEASE, b + 1_100L)
        SellRouteSpine8144.notePhaseText8435(m, "SELL_VERIFY_INCONCLUSIVE_PENDING", "🔒 RECOVERY_LOCK: deferring", b + 1_150L)
        SellRouteSpine8144.onResult8144(m, "FAILED_RETRYABLE", b + 1_200L)
        val t = SellRouteSpine8144.timer8144(m)!!
        assertEquals(Stage8144.CLOSE_LEASE, t.failedStage)
        assertEquals("SELL_VERIFY_INCONCLUSIVE_PENDING:RECOVERY_LOCK|FAILED_RETRYABLE", t.failureCause)
        val line = SellRouteSpine8144.statusLine8144()
        assertTrue(line, line.contains("failStage={CLOSE_LEASE=1}"))
        assertTrue(line, line.contains("preRoute8435{EMRG|SELL_VERIFY_INCONCLUSIVE_PENDING:RECOVERY_LOCK=1}"))
        // a row logged BEFORE this attempt's lease is not its cause
        SellRouteSpine8144.mark8144(m, Stage8144.CLOSE_LEASE, b + 2_000L)
        SellRouteSpine8144.onResult8144(m, "FAILED_RETRYABLE", b + 2_100L)
        assertEquals("FAILED_RETRYABLE", t.failureCause)
        // an attempt that reached the balance stage fails at the stage after it, as before
        SellRouteSpine8144.mark8144(m, Stage8144.CLOSE_LEASE, b + 3_000L)
        SellRouteSpine8144.onPhase8144(m, "SELL_BALANCE_CHECK", b + 3_100L)
        SellRouteSpine8144.onResult8144(m, "FAILED_RETRYABLE", b + 3_200L)
        assertEquals(Stage8144.VENUE_RESOLVE, t.failedStage)
    }

    @Test fun theRecoveryLockNeverDefersAProtectiveExit() {
        assertTrue(SellDoor8435.protectiveExit8435("HARD_STOP"))
        assertTrue(SellDoor8435.protectiveExit8435("CATASTROPHIC_HARD_BACKSTOP_-25"))
        assertFalse(SellDoor8435.protectiveExit8435("TAKE_PROFIT"))
        assertFalse(SellDoor8435.protectiveExit8435(null))
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val block = ex.substringAfter("val rLock = reason.uppercase()").substringBefore("val lockAgeMs = com.lifecyclebot.engine.sell.RecoveryLockTracker.lockAgeMs(ts.mint)")
        assertTrue(block.contains("com.lifecyclebot.engine.sell.SellDoor8435.protectiveExit8435(reason)"))
        val log = File("src/main/kotlin/com/lifecyclebot/engine/LiveTradeLogStore.kt").readText()
        assertTrue(log.indexOf("SellRouteSpine8144.notePhaseText8435(mint, phase.name, message)") in 1 until log.indexOf("SellRouteSpine8144.onPhase8144(mint, phase.name)"))
    }
}
