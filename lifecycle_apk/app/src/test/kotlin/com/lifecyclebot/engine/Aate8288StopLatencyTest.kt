package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.CloseLease
import com.lifecyclebot.engine.sell.SellRouteSpine8144
import com.lifecyclebot.engine.sell.SellRouteSpine8144.Stage8144
import com.lifecyclebot.engine.sell.StopFast8288
import com.lifecyclebot.engine.sell.StopFast8288.Route8288
import com.lifecyclebot.engine.sell.StopLatencyCore8153
import com.lifecyclebot.engine.sell.StopLatencyCore8153.HeldProof8153
import com.lifecyclebot.engine.truth.ReconcileClassifier8144
import com.lifecyclebot.engine.truth.StopMark8288
import com.lifecyclebot.network.JupiterQuoteHealth8288
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InterruptedIOException
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * V5.0.8288 — stop latency (5.0.8286 live: EMRG queue avg 9.4 s / max 18.9 s, HARD_STOP t2b 19.7 s with
 * TOKEN_BALANCE(3) FAILED_RETRYABLE, CATASTROPHIC_EXIT queue up to 540 s), Jupiter quote client health and PumpPortal
 * buy landing. Behaviour tests with injected readers / clocks; no device numbers.
 */
class Aate8288StopLatencyTest {

    private fun bi(v: Long) = BigInteger.valueOf(v)
    private fun proof(raw: Long, atMs: Long, decimals: Int = 6, source: String = "T") = HeldProof8153(bi(raw), decimals, source, atMs)
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Before fun setUp() {
        StopFast8288.resetForTest8288()
        StopLatencyCore8153.resetForTest8153()
        SellRouteSpine8144.resetForTest8144()
    }

    // ── 1. balance stage: bounded read, canonical fallback, never more than held ──

    @Test fun slow_wallet_reads_fall_back_to_the_canonical_quantity_within_about_a_second() {
        val now = System.currentTimeMillis()
        val late = CopyOnWriteArrayList<HeldProof8153>()
        val lateArrived = CountDownLatch(1)
        val slow: () -> HeldProof8153? = { Thread.sleep(2_000L); proof(900_000, now) }
        val t0 = System.nanoTime()
        val a = StopFast8288.holdingForStop8288(
            proven = { null },
            readers = listOf(slow, { Thread.sleep(3_000L); null }),
            absentNow = { false },
            fallback = { StopFast8288.fallbackProof8288(bi(1_000_000), 6, now - 60_000L, null, 0L, 0L, false, false, now) },
            onLate = { p -> late.add(p); lateArrived.countDown() },
        )
        val ms = (System.nanoTime() - t0) / 1_000_000L
        assertEquals(Route8288.CANONICAL_FALLBACK, a.route)
        assertEquals(bi(1_000_000), a.proof!!.raw)
        assertTrue("stop sized in ${ms}ms (budget ${StopFast8288.STOP_READ_BUDGET_MS_8288})", ms < StopFast8288.STOP_READ_BUDGET_MS_8288 + 400L)
        // the slow read is not cancelled: its answer arrives for the next sizing
        assertTrue(lateArrived.await(4, TimeUnit.SECONDS))
        assertEquals(bi(900_000), late.first().raw)
    }

    @Test fun a_fast_wallet_read_wins_and_the_smallest_positive_answer_sizes_the_sale() {
        val now = System.currentTimeMillis()
        val a = StopFast8288.holdingForStop8288(
            proven = { null },
            readers = listOf({ proof(800, now) }, { Thread.sleep(50L); proof(700, now) }),
            absentNow = { false },
            fallback = { proof(5_000, now) },
            onLate = {},
            graceMs = 500L,
        )
        assertEquals(Route8288.PARALLEL_READ, a.route)
        assertEquals(bi(700), a.proof!!.raw)
    }

    @Test fun a_proven_holding_answers_without_any_read() {
        val now = System.currentTimeMillis()
        var reads = 0
        val a = StopFast8288.holdingForStop8288({ proof(42, now) }, listOf({ reads++; null }), { false }, { null }, {})
        assertEquals(Route8288.PROVEN_HOLDING, a.route)
        assertEquals(0, a.readsIssued)
        assertEquals(0, reads)
    }

    @Test fun token_account_proven_absent_is_absent_not_a_canonical_sale() {
        val now = System.currentTimeMillis()
        val a = StopFast8288.holdingForStop8288({ null }, listOf({ null }), { true }, { proof(1_000, now) }, {})
        assertEquals(Route8288.ABSENT, a.route)
        assertNull(a.proof)
        assertTrue(src("engine/Executor.kt").contains("stopAbsentHandOff8288(ts, reason, wallet)?.let { return it }"))
        assertTrue(src("engine/sell/StopLatencyWiring8153.kt").contains("Balance8144.CONFIRMED_ABSENT && c.second >= sinceMs"))
    }

    @Test fun canonical_fallback_is_min_of_canonical_and_last_wallet_read_and_refuses_unsafe_states() {
        val now = 1_000_000L
        // min(canonical, observed) — never more than either
        assertEquals(bi(600), StopFast8288.fallbackProof8288(bi(1_000), 6, 10L, proof(600, 500L), 100L, 0L, false, false, now)!!.raw)
        assertEquals(bi(1_000), StopFast8288.fallbackProof8288(bi(1_000), 6, 10L, proof(5_000, 500L), 100L, 0L, false, false, now)!!.raw)
        // an observation older than the last sell touch is spent: canonical alone
        assertEquals("CANONICAL_HELD_8288", StopFast8288.fallbackProof8288(bi(1_000), 6, 10L, proof(600, 50L), 100L, 0L, false, false, now)!!.source)
        // a signed sell unresolved, a frozen account, a landed sell not yet absorbed, a scale split: no size
        assertNull(StopFast8288.fallbackProof8288(bi(1_000), 6, 10L, null, 0L, 0L, true, false, now))
        assertNull(StopFast8288.fallbackProof8288(bi(1_000), 6, 10L, null, 0L, 0L, false, true, now))
        assertNull(StopFast8288.fallbackProof8288(bi(1_000), 6, 10L, null, 0L, 20L, false, false, now))
        assertNull(StopFast8288.fallbackProof8288(bi(1_000), 6, 10L, proof(600, 500L, decimals = 9), 100L, 0L, false, false, now))
        assertNull(StopFast8288.fallbackProof8288(null, 6, 10L, null, 0L, 0L, false, false, now))
        assertNull(StopFast8288.fallbackProof8288(bi(1), 6, 10L, null, 0L, 0L, false, false, now))
    }

    @Test fun a_canonical_answer_is_reused_by_the_same_exit_and_spent_by_any_later_order() {
        val now = System.currentTimeMillis()
        StopFast8288.rememberFallback8288("M8288A", proof(1_000, now))
        assertNotNull(StopFast8288.cachedFallback8288("M8288A", now + 1_000L, now - 1L))
        assertNull("an order signed after it spends it", StopFast8288.cachedFallback8288("M8288A", now + 1_000L, now + 500L))
        StopFast8288.rememberFallback8288("M8288B", proof(1_000, now))
        assertNull("too old", StopFast8288.cachedFallback8288("M8288B", now + StopFast8288.FALLBACK_TTL_MS_8288 + 1L, 0L))
        // the canonical answer is never written as a wallet proof (reconcile / PositionProof8189 read those)
        assertNull(StopLatencyCore8153.proof8153("M8288A"))
        assertTrue(src("engine/sell/StopLatencyWiring8153.kt").contains("StopFast8288.rememberFallback8288(mint, proof)"))
        assertTrue(src("engine/sell/StopLatencyWiring8153.kt").contains("fun stopHolding8288(wallet: SolanaWallet, mint: String)"))
    }

    // ── 1b. queue stage: pre-route waits re-dispatch fast and are not route attempts ──

    @Test fun pre_route_classification() {
        assertTrue(StopFast8288.preRoute8288(true, "FAILED_RETRYABLE", true, false, false, false))
        assertFalse("non-emergency keeps its cadence", StopFast8288.preRoute8288(false, "FAILED_RETRYABLE", true, false, false, false))
        assertFalse("a stage after the lease ran", StopFast8288.preRoute8288(true, "FAILED_RETRYABLE", false, false, false, false))
        assertFalse("a failure phase was logged", StopFast8288.preRoute8288(true, "FAILED_RETRYABLE", true, true, false, false))
        assertFalse("something was signed", StopFast8288.preRoute8288(true, "FAILED_RETRYABLE", true, false, true, false))
        assertFalse("a veto re-asked every 250 ms changes nothing", StopFast8288.preRoute8288(true, "FAILED_RETRYABLE", true, false, false, true))
        assertFalse(StopFast8288.preRoute8288(true, "ROUTE_FAILED_NO_SIGNATURE", true, false, false, false))
        assertEquals(StopFast8288.PRE_ROUTE_FAST_MS_8288, StopFast8288.preRouteDelayMs8288(1))
        assertEquals(StopFast8288.PRE_ROUTE_FAST_MS_8288, StopFast8288.preRouteDelayMs8288(StopFast8288.FAST_PRE_ROUTE_WAITS_8288))
        assertEquals(StopFast8288.PRE_ROUTE_SLOW_MS_8288, StopFast8288.preRouteDelayMs8288(StopFast8288.FAST_PRE_ROUTE_WAITS_8288 + 1))
        assertTrue(StopFast8288.preRouteEligible8288(100L, 100L))
        assertFalse(StopFast8288.preRouteEligible8288(0L, 100L))
        assertFalse(StopFast8288.preRouteEligible8288(101L, 100L))
    }

    @Test fun spine_says_an_attempt_that_never_left_the_lease_is_a_pre_route_wait() {
        val m = "SPINE8288${System.nanoTime()}"
        SellRouteSpine8144.trigger8144(m, "STRICT_SL_-8", "pid")
        SellRouteSpine8144.mark8144(m, Stage8144.CLOSE_LEASE)
        assertTrue(StopFast8288.preRouteWait8288(m, "STRICT_SL_-8", "FAILED_RETRYABLE"))
        // a veto in the same attempt is not
        StopFast8288.noteVeto8288(m)
        assertFalse(StopFast8288.preRouteWait8288(m, "STRICT_SL_-8", "FAILED_RETRYABLE"))
        // the balance stage ran: a real attempt
        SellRouteSpine8144.mark8144(m, Stage8144.CLOSE_LEASE)
        SellRouteSpine8144.onPhase8144(m, "SELL_BALANCE_CHECK")
        assertFalse(StopFast8288.preRouteWait8288(m, "STRICT_SL_-8", "FAILED_RETRYABLE"))
        // a failure phase logged after the lease: a real failure
        SellRouteSpine8144.mark8144(m, Stage8144.CLOSE_LEASE)
        SellRouteSpine8144.onPhase8144(m, "SELL_FAILED")
        assertFalse(StopFast8288.preRouteWait8288(m, "STRICT_SL_-8", "FAILED_RETRYABLE"))
    }

    @Test fun close_lease_pre_route_wait_reenters_in_a_quarter_second_without_escalating_attempts() {
        val m = "LEASE8288${System.nanoTime()}"
        val lease = CloseLease.acquire(m, "T", "STRICT_SL_-8")
        assertNotNull(lease)
        val delay = CloseLease.recordPreRouteWait8288(m)
        assertEquals(StopFast8288.PRE_ROUTE_FAST_MS_8288, delay)
        assertEquals("not a route attempt: no slippage / route escalation", 1, CloseLease.attemptCount(m))
        assertNull("single flight: not before its clock", CloseLease.acquire(m, "T", "STRICT_SL_-8"))
        Thread.sleep(StopFast8288.PRE_ROUTE_FAST_MS_8288 + 80L)
        val again = CloseLease.acquire(m, "T", "STRICT_SL_-8")
        assertNotNull("re-enters on the short clock, not the 2 s rung", again)
        assertNull("in flight: never doubled", CloseLease.acquire(m, "T", "STRICT_SL_-8"))
        // a real route failure keeps the 7807 cadence
        CloseLease.recordRetry(m, "RETRYABLE")
        assertEquals(2, CloseLease.attemptCount(m))
        Thread.sleep(StopFast8288.PRE_ROUTE_FAST_MS_8288 + 80L)
        assertNull("after a real failure the 2 s rung applies again", CloseLease.acquire(m, "T", "STRICT_SL_-8"))
        CloseLease.release(m, "TEST_DONE")
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("if (preRoute8288) com.lifecyclebot.engine.sell.CloseLease.recordPreRouteWait8288(ts.mint)"))
        assertTrue(exec.contains("if (!preRoute8288) noteEmergencyOutcome7807(ts, canonicalReason, r)"))
        assertTrue(exec.contains("com.lifecyclebot.engine.sell.StopFast8288.noteVeto8288(ts.mint)"))
    }

    @Test fun the_deep_stop_quote_check_holds_an_emergency_for_at_most_its_budget() {
        val t0 = System.nanoTime()
        val v: Double? = StopFast8288.bounded8288(150L) { Thread.sleep(2_000L); 1.0 }
        val ms = (System.nanoTime() - t0) / 1_000_000L
        assertNull("no quote in time = allow (7928 never blocks on a failed quote)", v)
        assertTrue("held ${ms}ms", ms < 600L)
        assertEquals(2.0, StopFast8288.bounded8288(1_000L) { 2.0 }!!, 0.0)
        assertTrue(StopFast8288.STOP_QUOTE_CHECK_BUDGET_MS_8288 + StopFast8288.STOP_READ_BUDGET_MS_8288 < StopFast8288.STOP_T2B_TARGET_MS_8288)
        assertTrue(src("engine/Executor.kt").contains("StopFast8288.bounded8288(com.lifecyclebot.engine.sell.StopFast8288.STOP_QUOTE_CHECK_BUDGET_MS_8288)"))
    }

    // ── 1c. a stop is not held for a feed when the trade tape proves it ──

    @Test fun tape_print_confirms_a_loss_side_stop_only() {
        assertTrue(StopMark8288.stopFamily8288("TICK_HARD_FLOOR_CATASTROPHIC_LANE_SHITCOIN_-41PC"))
        assertTrue(StopMark8288.stopFamily8288("RAPID_CATASTROPHE_STOP"))
        assertTrue(StopMark8288.stopFamily8288("STRICT_SL_-12"))
        assertFalse(StopMark8288.stopFamily8288("TAKE_PROFIT_25"))
        assertFalse(StopMark8288.stopFamily8288("PROFIT_LOCK_STOP"))
        assertFalse(StopMark8288.stopFamily8288("TRAILING_STOP"))
        // 0.1 SOL for 1,000,000 tokens (6 dp raw) = 1e-7 SOL / token
        val entry = StopMark8288.entrySolPerToken8288(0.1, bi(1_000_000_000_000L), 6)
        assertEquals(1e-7, entry, 1e-15)
        assertEquals(-41.0, StopMark8288.printPnlPct8288(0.59e-7, entry), 1e-6)
        assertTrue(StopMark8288.confirms8288(StopMark8288.printPnlPct8288(0.59e-7, entry)))
        assertFalse("a print at +5% does not confirm a stop", StopMark8288.confirms8288(StopMark8288.printPnlPct8288(1.05e-7, entry)))
        assertFalse("a print 1e6 off (raw vs ui) is not one basis", StopMark8288.confirms8288(StopMark8288.printPnlPct8288(0.1, entry)))
        assertTrue(StopMark8288.CONFIRM_LOSS_PCT_8288 > -15.0)
        assertTrue(StopMark8288.PRINT_WINDOW_MS_8288 >= 10L * 60_000L)
        assertTrue(src("engine/truth/MissingMarkExitVeto6835.kt").contains("StopMark8288.tapeConfirmsStop8288(mintKey, exitReason, nowMs)"))
    }

    @Test fun risk_clock_reads_the_last_trade_print_when_the_feed_is_dark() {
        val now = 10_000_000L
        // entry $0.00002; print 1e-7 SOL at $150 = $0.000015 (-25%): a usable mark 5 minutes after the last trade
        val m = StopMark8288.tapeMarkUsd8288(1e-7, now - 300_000L, 150.0, 0.00002, now - 900_000L, now)
        assertEquals(0.000015, m!!, 1e-12)
        assertNull("older than the window", StopMark8288.tapeMarkUsd8288(1e-7, now - StopMark8288.RISK_PRINT_MAX_AGE_MS_8288 - 1L, 150.0, 0.00002, 0L, now))
        assertNull("a print from before this position opened", StopMark8288.tapeMarkUsd8288(1e-7, now - 1_000L, 150.0, 0.00002, now - 500L, now))
        assertNull("no SOL price", StopMark8288.tapeMarkUsd8288(1e-7, now - 1_000L, 0.0, 0.00002, 0L, now))
        assertNull("off the entry's unit basis", StopMark8288.tapeMarkUsd8288(1e-1, now - 1_000L, 150.0, 0.00002, 0L, now))
        val bot = src("engine/BotService.kt")
        val helper = bot.substringAfter("private fun riskClockMark7960(").substringBefore("fun startBot()")
        assertTrue(helper.contains("StopMark8288.tapeRiskMark8288(mint, t.position.entryPrice, t.position.entryTime, now)"))
        // the canonical 60 s mark and the 15 s runtime mark still come first
        assertTrue(helper.indexOf("age <= 60_000L") < helper.indexOf("tapeRiskMark8288"))
        assertTrue(helper.indexOf("runtimeRiskMark7960") < helper.indexOf("tapeRiskMark8288"))
    }

    // ── 1d. a ghost canonical row reaches the zero-proof poller ──

    @Test fun canonical_not_on_chain_ghosts_not_closed_by_a_sell_go_to_the_poller() {
        val ex = listOf(
            ReconcileClassifier8144.Exception8144(ReconcileClassifier8144.Cause8144.CANONICAL_NOT_ON_CHAIN, "Cbkg5w", "p1", ""),
            ReconcileClassifier8144.Exception8144(ReconcileClassifier8144.Cause8144.CANONICAL_NOT_ON_CHAIN, "GOMO", "p2", ""),
            ReconcileClassifier8144.Exception8144(ReconcileClassifier8144.Cause8144.CHAIN_BOT_HOLDING_NOT_CANONICAL, "X", "", ""),
        )
        assertEquals(setOf("Cbkg5w"), ReconcileRunner8144.ghostMintsForPoller8288(ex, setOf("GOMO")))
        assertTrue(src("engine/ReconcileRunner8144.kt").contains("ghostZeroHandOff8288(report.exceptions, closed.toSet(), inputs.lots)"))
    }

    // ── 2. Jupiter quote client ──

    @Test fun jupiter_key_goes_only_to_the_keyed_host_and_transport_failures_fail_over() {
        assertTrue(JupiterQuoteHealth8288.keyedHost8288("https://api.jup.ag/swap/v1/quote?x=1"))
        assertFalse("lite-api contains api.jup.ag but is keyless", JupiterQuoteHealth8288.keyedHost8288("https://lite-api.jup.ag/swap/v1/quote"))
        assertTrue(JupiterQuoteHealth8288.hostFailover8288("Cannot resolve Jupiter host"))
        assertTrue(JupiterQuoteHealth8288.hostFailover8288("Jupiter GET network error: reset"))
        assertTrue(JupiterQuoteHealth8288.hostFailover8288("Jupiter API 401: API key required"))
        assertTrue(JupiterQuoteHealth8288.hostFailover8288("Jupiter GET 503: {}"))
        assertTrue(JupiterQuoteHealth8288.hostFailover8288("Jupiter GET 403: forbidden"))
        assertFalse("a route 4xx is the same on every host", JupiterQuoteHealth8288.hostFailover8288("Jupiter GET 400: Could not find any route"))
        assertFalse(JupiterQuoteHealth8288.hostFailover8288("Jupiter GET 422: TOKEN_NOT_TRADABLE"))
        assertTrue(JupiterQuoteHealth8288.failoverAffordable8288(null))
        assertTrue(JupiterQuoteHealth8288.failoverAffordable8288(1_500L))
        assertFalse(JupiterQuoteHealth8288.failoverAffordable8288(200L))
        val jup = src("network/JupiterApi.kt")
        assertTrue(jup.contains("if (JupiterQuoteHealth8288.hostFailover8288(msg)) {"))
        assertTrue(jup.contains("if (JupiterQuoteHealth8288.failoverAffordable8288(left8288)) continue@hosts"))
        assertFalse(jup.contains("url.contains(\"api.jup.ag\")"))
        assertTrue(JupiterQuoteHealth8288.QUOTE_CALL_MS_8288 < 12_000L)
    }

    @Test fun our_own_budget_or_cancel_is_not_a_provider_failure() {
        assertTrue(JupiterQuoteHealth8288.localFailure8288(SocketTimeoutException("timeout"), budgetScoped = true, interrupted = false))
        assertFalse("a provider read timeout on the full quote clock counts", JupiterQuoteHealth8288.localFailure8288(SocketTimeoutException("timeout"), false, false))
        assertTrue(JupiterQuoteHealth8288.localFailure8288(InterruptedIOException("interrupted"), false, true))
        assertTrue(JupiterQuoteHealth8288.localFailure8288(java.io.IOException("Canceled"), false, false))
        assertTrue(JupiterQuoteHealth8288.localFailure8288(SocketTimeoutException("Exit quote budget exhausted"), false, false))
        assertFalse("DNS is the provider path", JupiterQuoteHealth8288.localFailure8288(UnknownHostException("api.jup.ag"), true, false))
        val h = src("engine/HealthAwareHttp.kt")
        assertTrue(h.contains("val local8288 = try { isLocalFailure8288?.invoke(e) == true } catch (_: Throwable) { false }"))
        assertTrue(src("network/JupiterApi.kt").contains("host = if (observationOnly7397) JupiterQuoteHealth8288.OBS_HOST_8288 else \"jupiter_quote\""))
        for (a in 1..5) {
            val s = JupiterQuoteHealth8288.retrySleepMs8288(a, 150L)
            assertEquals(300L * a + 150L, s)
        }
        assertEquals(300L + 199L, JupiterQuoteHealth8288.retrySleepMs8288(1, 10_000L))
    }

    // ── 3. PumpPortal buys ──

    @Test fun low_pumpportal_land_rate_routes_buys_away_until_the_window_slides() {
        val now = 10_000_000L
        fun row(state: String, ago: Long, route: String = "PUMPPORTAL", side: String = "BUY") = PumpLanding8288.Row8288(side, route, state, now - ago)
        // 8286: 14 sent, 4 landed, 6 expired, 3 failed, 1 pending
        val rows = List(4) { row("CONFIRMED", 60_000L) } + List(6) { row("EXPIRED_PROVEN", 60_000L) } +
            List(3) { row("FAILED_ON_CHAIN", 60_000L) } + row("ACCEPTED", 1_000L) +
            List(5) { row("CONFIRMED", 1_000L, route = "JUPITER") } + List(3) { row("EXPIRED_PROVEN", 1_000L, side = "SELL") }
        val r = PumpLanding8288.pumpBuyRate8288(rows, now)
        assertEquals(13, r.resolved)
        assertEquals(4, r.landed)
        assertEquals(30, r.pct)
        assertTrue(PumpLanding8288.routeAway8288(r))
        // too few resolved to judge; a healthy rate; rows outside the window
        assertFalse(PumpLanding8288.routeAway8288(PumpLanding8288.Rate8288(PumpLanding8288.MIN_RESOLVED_8288 - 1, 0)))
        assertTrue(PumpLanding8288.MIN_LAND_RATE_PCT_8288 in 31..80)
        assertFalse(PumpLanding8288.routeAway8288(PumpLanding8288.Rate8288(10, 7)))
        assertEquals(0, PumpLanding8288.pumpBuyRate8288(rows, now + PumpLanding8288.WINDOW_MS_8288 + 120_000L).resolved)
        assertTrue(src("engine/Executor.kt").contains("if (PumpLanding8288.skipPumpPortalBuy8288(ts.mint)) return null"))
    }

    @Test fun pumpportal_blockhash_is_replaced_and_nothing_else_moves() {
        val sig = ByteArray(64) { 0 }
        val keys = ByteArray(64) { (it + 1).toByte() }
        val oldHash = ByteArray(32) { 7 }
        val ix = byteArrayOf(1, 0, 1, 2, 9, 9)
        val tx = byteArrayOf(1) + sig + byteArrayOf(0x80.toByte(), 1, 0, 1, 2) + keys + oldHash + ix
        val newHash = ByteArray(32) { 42 }
        val out = PumpLanding8288.replaceBlockhash8288(tx, newHash)!!
        assertEquals(tx.size, out.size)
        val at = 1 + 64 + 1 + 3 + 1 + 64
        assertArrayEquals(newHash, out.copyOfRange(at, at + 32))
        assertArrayEquals(tx.copyOfRange(0, at), out.copyOfRange(0, at))
        assertArrayEquals(tx.copyOfRange(at + 32, tx.size), out.copyOfRange(at + 32, out.size))
        // legacy (no v0 prefix) too
        val legacy = byteArrayOf(1) + sig + byteArrayOf(1, 0, 1, 2) + keys + oldHash + ix
        assertArrayEquals(newHash, PumpLanding8288.replaceBlockhash8288(legacy, newHash)!!.copyOfRange(at - 1, at + 31))
        assertNull("truncated bytes are kept as they are", PumpLanding8288.replaceBlockhash8288(tx.copyOf(100), newHash))
        assertNull(PumpLanding8288.replaceBlockhash8288(tx, ByteArray(31)))
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("val pumpTx8288 = PumpLanding8288.freshen8288(wallet, built.txBase64)"))
        assertTrue(exec.contains("pumpEnv7311?.txBase64 ?: pumpTx8288,"))
    }
}
