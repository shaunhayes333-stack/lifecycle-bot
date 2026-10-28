package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExitSweepTiming7264
import com.lifecyclebot.engine.truth.HeliusCandleGate8418
import com.lifecyclebot.engine.truth.HeliusSwapCandles7819
import com.lifecyclebot.engine.truth.MemoryBounds8289
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8418 — runtime health from the 5.0.8416 live report (3.7 h): the exit tick gauge, supervisor worker budgets,
 * memory bounds, Helius free-first candles / bundle scope, and the two mislabelled lifecycle counters.
 */
class Aate8418RuntimeHealthTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun field(line: String, name: String): Long =
        Regex("$name=(-?\\d+)").find(line)?.groupValues?.get(1)?.toLong() ?: error("$name missing in $line")

    // ── 1. exit hot tick ──

    @Test fun idleGapsAreHeldToTheIdleCadenceAndHeldGapsToTheHotOne() {
        assertFalse(ExitSweepTiming7264.slowHotTickGap8418(5_479L, prevIdle = true))     // 8416's lastGapMs, idle loop
        assertTrue(ExitSweepTiming7264.slowHotTickGap8418(10_000L, prevIdle = true))
        assertTrue(ExitSweepTiming7264.slowHotTickGap8418(5_479L, prevIdle = false))     // a held book ticking at 5.5 s is slow
        assertFalse(ExitSweepTiming7264.slowHotTickGap8418(1_200L, prevIdle = false))
    }

    @Test fun aStoppedBotIsNotAnExitGapAndIdleTicksAreNotSlowTicks() {
        val t = ExitSweepTiming7264
        val base = 900_000_000L
        t.onRuntimeStart8418()
        t.onHotTickStart(base)
        t.onHotTickIdle8418()
        val slow0 = field(t.statusLine(), "slowTicks")
        // idle iteration -> next start 5.4 s later: the idle cadence, not slow
        t.onHotTickStart(base + 5_400L)
        assertEquals(slow0, field(t.statusLine(), "slowTicks"))
        assertEquals(5_400L, field(t.statusLine(), "lastGapMs"))
        // the bot is stopped for 108 minutes, then started again: no gap is measured across the stop
        val heldBefore = t.maxHeldGapMs8418()
        t.onRuntimeStart8418()
        t.onHotTickStart(base + 5_400L + 6_505_712L)
        assertEquals(5_400L, field(t.statusLine(), "lastGapMs"))
        assertEquals(heldBefore, t.maxHeldGapMs8418())
        assertEquals(slow0, field(t.statusLine(), "slowTicks"))
        // a held iteration (not idle) followed by a 6 s gap is slow and is the held gap reported
        t.onHotTickStart(base + 5_400L + 6_505_712L + 6_000L)
        assertEquals(slow0 + 1, field(t.statusLine(), "slowTicks"))
        assertTrue(t.maxHeldGapMs8418() >= 6_000L)
        assertTrue(t.statusLine().contains("maxHeldGapMs8418="))
        assertTrue(t.statusLine().contains("hotExitWatchdog8418["))
    }

    @Test fun awakeClockIsRebasedOnStart() {
        val before = field(Awake8010.statusLine(), "cpuSuspends")
        Awake8010.resetBaseline8418()
        Awake8010.noteTick(1_000L, 1_000L)
        Awake8010.resetBaseline8418()
        // 108 minutes later in elapsed time, uptime barely moved (the phone slept while the bot was stopped)
        Awake8010.noteTick(6_506_000L, 2_000L)
        assertEquals(before, field(Awake8010.statusLine(), "cpuSuspends"))
        Awake8010.resetBaseline8418()
    }

    @Test fun watchdogPassesAreSingleFlightAndAThrowingPassNeverEndsTheSchedule() {
        val gate = HotExitWatchdog8418.Gate8418()
        var inner: Boolean? = null
        val outer = gate.runExclusive { inner = gate.runExclusive { } }
        assertTrue(outer)
        assertEquals(false, inner)
        assertTrue(gate.runExclusive { })   // released after the pass
        val errs0 = field(HotExitWatchdog8418.statusLine8418(), "errors")
        val ticks0 = field(HotExitWatchdog8418.statusLine8418(), "ticks")
        HotExitWatchdog8418.runTick8418 { throw IllegalStateException("boom") }
        HotExitWatchdog8418.runTick8418 { }
        assertEquals(errs0 + 1, field(HotExitWatchdog8418.statusLine8418(), "errors"))
        assertEquals(ticks0 + 1, field(HotExitWatchdog8418.statusLine8418(), "ticks"))
        val b = src("engine/BotService.kt")
        assertTrue(b.contains("HotExitWatchdog8418.start8418 { hotExitWatchdogTick8418() }"))
        assertTrue(b.contains("openPosSuperviseGate8418.runExclusive { superviseOpenPositionTickLoopPass8418(openCount, nowMs) }"))
        assertTrue(b.contains("superviseOpenPositionTickLoop7283(postSupervisorOpenCount, postSupervisorNowMs)"))
    }

    // ── 2. supervisor workers ──

    @Test fun admissionNeverExceedsWhatThePoolCanRun() {
        val s = SupervisorWorkerBudget8418
        // 8416: cap 48 against 16 threads — the 17th lease used to queue with its timer running
        assertTrue(s.admits8418(liveLeases = 15, effectiveCap = 48, poolThreads = 16, busyThreads = 15, queued = 0))
        assertFalse(s.admits8418(liveLeases = 16, effectiveCap = 48, poolThreads = 16, busyThreads = 10, queued = 0))
        // threads still wedged after a force release hold a thread without a lease
        assertFalse(s.admits8418(liveLeases = 4, effectiveCap = 48, poolThreads = 16, busyThreads = 16, queued = 0))
        assertFalse(s.admits8418(liveLeases = 4, effectiveCap = 48, poolThreads = 16, busyThreads = 12, queued = 4))
        // the adaptive (emergency) cap still binds below the pool
        assertFalse(s.admits8418(liveLeases = 8, effectiveCap = 8, poolThreads = 16, busyThreads = 0, queued = 0))
        assertTrue(s.admits8418(liveLeases = 7, effectiveCap = 8, poolThreads = 16, busyThreads = 7, queued = 0))
    }

    @Test fun aWorkerGetsItsWholeBudgetFromTheMomentItHasAThread() {
        val s = SupervisorWorkerBudget8418
        val budget = 15_750L
        // admitted at 0, waited 10 s for a thread: the old watchdog fired at 15.75 s (5.75 s of work). Now 25.75 s.
        assertEquals(10_000L, s.watchdogWaitMoreMs8418(nowMs = 15_750L, spawnedAtMs = 0L, workerStartedAtMs = 10_000L,
            budgetMs = budget, maxQueueWaitMs = s.MAX_QUEUE_WAIT_MS_8418))
        assertEquals(0L, s.watchdogWaitMoreMs8418(25_750L, 0L, 10_000L, budget, s.MAX_QUEUE_WAIT_MS_8418))
        // a worker that started at once keeps exactly the old deadline
        assertEquals(0L, s.watchdogWaitMoreMs8418(15_750L, 0L, 1L, budget, s.MAX_QUEUE_WAIT_MS_8418))
        // never got a thread: released at the queue bound, and that is a queue expiry, not a timeout
        assertEquals(0L, s.watchdogWaitMoreMs8418(15_750L, 0L, 0L, budget, s.MAX_QUEUE_WAIT_MS_8418))
        assertTrue(s.isQueueExpiry8418(0L))
        assertFalse(s.isQueueExpiry8418(10_000L))
        // the lease TTL prune ages a started lease from its start
        assertEquals(8_000L, s.leaseAgeMs8418(nowMs = 18_000L, admittedAtMs = 0L, workerStartedAtMs = 10_000L))
        assertEquals(18_000L, s.leaseAgeMs8418(18_000L, 0L, 0L))
        val b = src("engine/BotService.kt")
        assertTrue(b.contains("if (!supervisorPoolAdmits8418(live)) return -1L"))
        assertTrue(b.contains("supervisorAwaitWorkerBudget8418(leaseId, released)"))
        assertTrue(b.contains("scope.launch(entryHydrationDispatcher8418 + CoroutineName(\"entry-hydration-"))
        assertFalse(b.contains("scope.launch(supervisorDispatcher6647 + CoroutineName(\"entry-hydration-"))
    }

    // ── 3. memory ──

    @Test fun onlyLoadedPrefsFilesCountAsTheInMemoryMirror() {
        val files = mapOf(
            "aate_explore_shadow_8347.xml.migrated8397" to 18_289L * 1024,
            "forward_return_labeler_7731.xml" to 2_551L * 1024,
            "scanner_hard_rejects.xml" to 1_177L * 1024,
            "scanner_hard_rejects.xml.bak" to 1_177L * 1024,
        )
        assertEquals((2_551L + 1_177L) * 1024, MemoryGuard7977.residentPrefsBytes8418(files))
        assertTrue(MemoryGuard7977.residentPrefsFile8418("a.xml"))
        assertFalse(MemoryGuard7977.residentPrefsFile8418("a.xml.migrated8397"))
        assertTrue("serviceStamps8418" in MemoryGuard7977.registeredStores8144())
        assertTrue(MemoryGuard7977.untrimmedStores8309().isEmpty())
    }

    @Test fun throttleStampMapsStayBoundedWhateverTheArrivalRate() {
        // the rule boundServiceStamps8418 applies to each stamp map (cap 4,096, 10 min TTL)
        val m = ConcurrentHashMap<String, Long>()
        val now = 50_000_000L
        for (i in 0 until 6_000) m["mint$i"] = now - i * 1_000L      // newest first; many far past the TTL
        MemoryBounds8289.capByAge8289(m, 4_096, 10L * 60_000L, now) { it }
        assertTrue(m.size <= 4_096)
        assertTrue(m.containsKey("mint0"))                             // the fresh throttle survives
        // hold-until stamps (the warm-up map) expire the same way
        val hold = ConcurrentHashMap<String, Long>()
        for (i in 0 until 5_000) hold["m$i"] = now + 30_000L - i * 1_000L
        MemoryBounds8289.capByAge8289(hold, 4_096, 10L * 60_000L, now) { it }
        assertTrue(hold.size <= 4_096 && hold.containsKey("m0"))
    }

    // ── 4. Helius credits ──

    @Test fun heliusCandlesAreTheFallbackAfterTheFreeSources() {
        val g = HeliusCandleGate8418
        val held = HeliusSwapCandles7819.PRIORITY_HELD_8284
        val near = HeliusSwapCandles7819.PRIORITY_NEAR_DECISION_8284
        val now = 1_000_000L
        assertEquals(HeliusCandleGate8418.Decision8418.COVERED_BY_FREE, g.decide8418(held, true, now, now))
        assertEquals(HeliusCandleGate8418.Decision8418.BULK_KEYLESS_ONLY, g.decide8418(0, false, now - 60_000L, now))
        assertEquals(HeliusCandleGate8418.Decision8418.FREE_GRACE, g.decide8418(near, false, now - 5_000L, now))
        assertEquals(HeliusCandleGate8418.Decision8418.HELIUS_FALLBACK, g.decide8418(near, false, now - g.FREE_GRACE_MS_8418, now))
        assertEquals(HeliusCandleGate8418.Decision8418.HELIUS_FALLBACK, g.decide8418(held, false, now, now))   // held never waits
        g.resetForTest8418()
        g.note8418(HeliusCandleGate8418.Decision8418.BULK_KEYLESS_ONLY)
        assertEquals(1L, g.count8418(HeliusCandleGate8418.Decision8418.BULK_KEYLESS_ONLY))
        assertTrue(g.statusLine8418().contains("BULK_KEYLESS_ONLY=1"))
        assertEquals(now, g.firstAsk8418("mintA", now))
        assertEquals(now, g.firstAsk8418("mintA", now + 10_000L))      // the grace runs from the first ask
        val o = src("engine/DataOrchestrator.kt")
        val keyless = o.indexOf("val k1m = if (codexFirst8191)")
        val helius = o.indexOf("HeliusSwapCandles7819.request7819(ts)")
        assertTrue(keyless in 1 until helius)                           // keyless seed before the Helius ask
        assertTrue(o.contains("val heliusAfterKeyless8418 = keylessSeeded6916 == 0 && !codexFirst8191"))
        val h = src("engine/truth/HeliusSwapCandles7819.kt")
        assertTrue(h.indexOf("if (!heliusAfterFree8418(ts, nowMs, priority)) return") in 1 until h.indexOf("val reservation = admission8386.reserve(mint, nowMs, priority)"))
    }

    @Test fun bundleScansWaitForTheCoinToReachScoring() {
        val s = BundleScanScope8418
        assertFalse(s.scoredRecently8418(null, 1_000L))
        assertTrue(s.scoredRecently8418(1_000L, 1_000L + 9L * 60_000L))
        assertFalse(s.scoredRecently8418(1_000L, 1_000L + 11L * 60_000L))
        assertTrue(s.pendingLive8418(0L, s.PENDING_TTL_MS_8418))
        assertFalse(s.pendingLive8418(0L, s.PENDING_TTL_MS_8418 + 1L))
        val calls = ArrayList<String>()
        val prior = s.launcher8418
        s.resetForTest8418()
        s.launcher8418 = { m, sym, _ -> calls += "$m:$sym" }
        try {
            val t0 = 5_000_000L
            assertFalse(s.requestOrDefer8418("young1", "Y1", "key", t0))   // intake: not scored yet
            assertTrue(calls.isEmpty())
            assertEquals(1, s.pendingCount8418())
            s.noteScored8418("young1", t0 + 30_000L)                        // reaches scoring: scanned now
            assertEquals(listOf("young1:Y1"), calls)
            assertEquals(0, s.pendingCount8418())
            assertTrue(s.requestOrDefer8418("young1", "Y1", "key", t0 + 60_000L))   // already scored: at once
            assertEquals(2, calls.size)
            // a deferral that never reached scoring inside its window is not bought late
            s.requestOrDefer8418("stale", "S", "key", t0)
            s.noteScored8418("stale", t0 + s.PENDING_TTL_MS_8418 + 1L)
            assertEquals(2, calls.size)
            assertFalse(s.requestOrDefer8418("", "X", "key", t0))
            assertFalse(s.requestOrDefer8418("m", "X", "", t0))
        } finally {
            s.launcher8418 = prior
            s.resetForTest8418()
        }
        assertTrue(src("engine/TokenSafetyChecker.kt").contains("BundleScanScope8418.requestOrDefer8418(mint, symbol, cfg().heliusApiKey)"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("BundleScanScope8418.noteScored8418(ts.mint, nowMs)"))
    }

    // ── 5. lifecycle counters ──

    @Test fun v3ExecutesWithoutAnFdgTicketAreTypedNotRouteFailures() {
        assertEquals("EXECUTION_TICKET_MISSING_7835", V3ExecutionTicket8418.refusal8418(null, "mint", "LIVE", 0.05))
        assertEquals("EXECUTION_TICKET_MISSING_7835", V3ExecutionTicket8418.refusal8418(null, "mint", "PAPER", 0.05))
        assertEquals("", V3ExecutionTicket8418.V3_ATTEMPT_ID_8418)
        val b = src("engine/BotService.kt")
        val v3 = b.substring(b.indexOf("fun runV3Execution"), b.indexOf("fun manualBuy"))
        assertTrue(v3.indexOf("v3TicketRefusal8418(ts, req.sizeSol)?.let { return it }") in 1 until v3.indexOf("executor.doBuy("))
    }

    @Test fun theLiveBootstrapRuleIsCountedNotLoggedAsASuppression() {
        assertEquals(ShitcoinBootstrap8418.LIVE_NOT_APPLICABLE_8418, ShitcoinBootstrap8418.refusalLabel8418(paper = false))
        assertFalse(ShitcoinBootstrap8418.isForensicEvent8418(paper = false))
        assertEquals("SHITCOIN_LIVE_ADAPTIVE_FORCE_SUPPRESSED", ShitcoinBootstrap8418.refusalLabel8418(paper = true))
        assertTrue(ShitcoinBootstrap8418.isForensicEvent8418(paper = true))
    }
}
