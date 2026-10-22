package com.lifecyclebot.engine

import com.lifecyclebot.engine.HandoffReceiverTasks8153.Block8153
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Outcome8153
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Task8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8330 — the specialist last mile: a held ticket resumes when its cause clears (same attempt, never a second
 * purchase, the 8327 budget intact), LIVE_BUY_NOT_OPENED names its cause, a hand-off task resumes on its receiver's
 * TokenState arriving, and every refusal between QUALIFIED and OWNER_SELECTED is counted by name.
 */
class Aate8330LastMileTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // ── 1. event-driven ticket retry ──

    @Test
    fun dispatcherResumesOncePerCauseWithTheSameIdentity() {
        TicketRetry8327.resetForTests8327()
        val t0 = 1_000_000L
        var ran = 0
        TicketRetry8327.registerResume8330("MOONSHOT", "att-1", "MintA") { ran++; true }
        assertTrue(TicketRetry8327.hasResume8330("att-1"))
        // the hold arms itself once per hold
        assertNull(TicketRetry8327.retryOrTerminal8327("MOONSHOT", "att-1", "LIVE_BUY_NOT_OPENED_7948:MUTEX_BUSY_DEFERRED", t0))
        assertEquals(1, TicketRetry8327.armedHolds8330("att-1"))
        val hold = TicketRetry8327.Hold8327("MOONSHOT", "att-1", "MUTEX_BUSY_DEFERRED", t0, 1)
        val resume = TicketRetry8327.Resume8330("MOONSHOT", "att-1", "MintA", t0) { true }
        // the matching cause dispatches; another cause does not; the late-event guard re-checks any cause
        assertTrue(TicketRetry8327.shouldDispatch8330(hold, resume, 1, TicketRetry8327.TRIGGER_MUTEX_8330, null))
        assertFalse(TicketRetry8327.shouldDispatch8330(hold, resume, 1, TicketRetry8327.TRIGGER_PROOF_8330, null))
        assertFalse(TicketRetry8327.shouldDispatch8330(hold, resume, 1, TicketRetry8327.TRIGGER_ROUTE_8330, null))
        assertTrue(TicketRetry8327.shouldDispatch8330(hold, resume, 1, TicketRetry8327.TRIGGER_GUARD_8330, null))
        // once per hold: a consumed arm (null) or a stale arm (older hold count) never dispatches again
        assertFalse(TicketRetry8327.shouldDispatch8330(hold, resume, null, TicketRetry8327.TRIGGER_MUTEX_8330, null))
        assertFalse(TicketRetry8327.shouldDispatch8330(hold.let { TicketRetry8327.Hold8327(it.lane, it.attemptId, it.cause, it.firstMs, 2) }, resume, 1, TicketRetry8327.TRIGGER_MUTEX_8330, null))
        // a mint-scoped event resumes only that mint's ticket; no closure = nothing to resume
        assertFalse(TicketRetry8327.shouldDispatch8330(hold, resume, 1, TicketRetry8327.TRIGGER_MUTEX_8330, "MintB"))
        assertTrue(TicketRetry8327.shouldDispatch8330(hold, resume, 1, TicketRetry8327.TRIGGER_MUTEX_8330, "MintA"))
        assertFalse(TicketRetry8327.shouldDispatch8330(hold, null, 1, TicketRetry8327.TRIGGER_MUTEX_8330, null))
        // the cause -> trigger map
        assertTrue(TicketRetry8327.triggerMatches8330("SAFETY_PROOF_PENDING_DEFERRED_8270", TicketRetry8327.TRIGGER_PROOF_8330))
        assertTrue(TicketRetry8327.triggerMatches8330("TOKEN_MAP_ROUTE_PENDING_7371", TicketRetry8327.TRIGGER_ROUTE_8330))
        assertTrue(TicketRetry8327.triggerMatches8330("TOKEN_MAP_PENDING_DEFERRED_7371", TicketRetry8327.TRIGGER_ROUTE_8330))
        assertFalse(TicketRetry8327.triggerMatches8330("MUTEX_BUSY_DEFERRED", TicketRetry8327.TRIGGER_ROUTE_8330))
        assertFalse(TicketRetry8327.triggerMatches8330("MUTEX_BUSY_DEFERRED", "something_else"))
        // a route commit resumes only when the route RESOLVED
        assertTrue(TicketRetry8327.routeResolved8330("DEX_ROUTABLE"))
        assertTrue(TicketRetry8327.routeResolved8330("PUMPFUN_BONDING_CURVE_EXECUTABLE"))
        assertFalse(TicketRetry8327.routeResolved8330("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP"))
        assertFalse(TicketRetry8327.routeResolved8330("DEX_DISCOVERY_VERIFIED_PENDING_JUPITER"))
        assertFalse(TicketRetry8327.routeResolved8330(""))
        assertEquals(0, ran)  // nothing here runs the wrapper: the dispatcher is event-driven, not test-driven
        TicketRetry8327.resetForTests8327()
        assertFalse(TicketRetry8327.hasResume8330("att-1"))
    }

    @Test
    fun resumeNeverDuplicatesAHeldOrInFlightMintAndRechecksSealSafetyAndPrice() {
        val r = { held: Boolean, inFlight: Boolean, live: Boolean, intent: Boolean, safety: Boolean, decision: Double, now: Double ->
            TicketRetry8327.resumeRefusal8330(held, inFlight, live, intent, safety, decision, now)
        }
        assertEquals("ALREADY_HELD", r(true, true, true, true, false, 1.0, 1.0))
        assertEquals("BUY_IN_FLIGHT", r(false, true, true, true, false, 1.0, 1.0))
        assertEquals("TICKET_GONE", r(false, false, false, true, false, 1.0, 1.0))
        assertEquals("VERSION_RESEALED", r(false, false, true, false, false, 1.0, 1.0))
        assertEquals("SAFETY_BLOCKED", r(false, false, true, true, true, 1.0, 1.0))
        assertEquals("PRICE_ABOVE_DECISION", r(false, false, true, true, false, 1.0, 1.06))
        assertNull(r(false, false, true, true, false, 1.0, 1.04))
        assertNull(r(false, false, true, true, false, 1.0, 0.9))
        assertNull(r(false, false, true, true, false, 0.0, 1.5))   // no decision mark: liveBuy's own entry checks decide
        assertEquals(0.05, TicketRetry8327.MAX_RESUME_PRICE_DRIFT_8330, 1e-12)
        // a held / in-flight / gone / resealed / unsafe ticket ends by name; a price that ran away keeps the hold
        for (w in listOf("ALREADY_HELD", "BUY_IN_FLIGHT", "TICKET_GONE", "VERSION_RESEALED", "SAFETY_BLOCKED")) assertTrue(w, TicketRetry8327.refusalIsTerminal8330(w))
        assertFalse(TicketRetry8327.refusalIsTerminal8330("PRICE_ABOVE_DECISION"))
        assertFalse(TicketRetry8327.refusalIsTerminal8330("TOKEN_STATE_MISSING"))
    }

    @Test
    fun budgetAndExpiryTerminaliseExactlyOnceAndClearTheResume() {
        TicketRetry8327.resetForTests8327()
        val t0 = 5_000_000L
        val reason = "LIVE_BUY_NOT_OPENED_7948:SAFETY_PROOF_PENDING_DEFERRED_8270"
        TicketRetry8327.registerResume8330("EXPRESS", "att-b", "MintB") { false }
        for (i in 0 until TicketRetry8327.MAX_HOLDS_8327) {
            assertNull(TicketRetry8327.retryOrTerminal8327("EXPRESS", "att-b", reason, t0 + i))
            assertEquals(i + 1, TicketRetry8327.armedHolds8330("att-b"))   // re-armed on every hold
        }
        assertEquals(1, TicketRetry8327.heldCount8327())
        assertEquals("SAFETY_PROOF_PENDING_DEFERRED_8270_RETRY_BUDGET_8327", TicketRetry8327.retryOrTerminal8327("EXPRESS", "att-b", reason, t0 + 10))
        assertEquals(0, TicketRetry8327.heldCount8327())
        // the terminal clears the hold, its arm and its closure — a late event finds nothing to resume
        TicketRetry8327.onTerminal8327("att-b")
        assertNull(TicketRetry8327.armedHolds8330("att-b"))
        assertFalse(TicketRetry8327.hasResume8330("att-b"))
        // expiry by the clock, once
        TicketRetry8327.registerResume8330("EXPRESS", "att-c", "MintC") { false }
        assertNull(TicketRetry8327.retryOrTerminal8327("EXPRESS", "att-c", reason, t0))
        val h = TicketRetry8327.Hold8327("EXPRESS", "att-c", "SAFETY_PROOF_PENDING_DEFERRED_8270", t0, 1)
        assertNull(TicketRetry8327.expiryReason8327(h, t0 + TicketRetry8327.HOLD_TTL_MS_8327 - 1))
        assertEquals("SAFETY_PROOF_PENDING_DEFERRED_8270_RETRY_EXPIRED_8327", TicketRetry8327.expiryReason8327(h, t0 + TicketRetry8327.HOLD_TTL_MS_8327))
        TicketRetry8327.onExec8327("att-c")
        assertEquals(0, TicketRetry8327.heldCount8327())
        assertFalse(TicketRetry8327.hasResume8330("att-c"))
        TicketRetry8327.resetForTests8327()
    }

    @Test
    fun resumeCountersOnTheTicketExecLine() {
        assertEquals("", TicketRetry8327.formatResumeSuffix8330(emptyMap(), 0L, emptyMap()))
        val s = TicketRetry8327.formatResumeSuffix8330(mapOf("mutex" to 3L, "proof" to 1L), 2L, mapOf("ALREADY_HELD" to 1L, "PRICE_ABOVE_DECISION" to 2L))
        assertEquals(" resumed=4(mutex=3/proof=1/route=0/guard=0) resumedExecuted=2 resumedRefused=[PRICE_ABOVE_DECISION=2,ALREADY_HELD=1]", s)
        assertTrue(TicketRetry8327.formatResumeSuffix8330(mapOf("route" to 1L), 1L, emptyMap()).endsWith("resumedRefused=[NONE]"))
    }

    @Test
    fun theThreeHookPointsAndTheSameWrapperResume() {
        val ex = src("engine/Executor.kt")
        // mutex release: one helper line REPLACES the release inside liveBuy's finally (budgeted method), release then dispatch
        assertTrue(ex.contains("releaseBuyMutex8330(liveBuyMutexAcquired)  // V5.0.8330"))
        assertTrue(ex.contains("try { MEME_LIVE_BUY_MUTEX.release() } catch (_: Throwable) {}\n        try { TicketRetry8327.onMutexReleased8330() } catch (_: Throwable) {}"))
        assertFalse(ex.contains("try { if (liveBuyMutexAcquired) MEME_LIVE_BUY_MUTEX.release() } catch (_: Throwable) {}"))
        // proof landing and route resolution
        assertTrue(src("engine/TokenSafetyChecker.kt").contains("cache[mint] = report\n        try { TicketRetry8327.onProofLanded8330(mint) } catch (_: Throwable) {}"))
        assertTrue(src("engine/TokenMapAuthority.kt").contains("tm.updatedAtMs = now\n        try { TicketRetry8327.onRouteResolved8330(target, tm.routeStatus) } catch (_: Throwable) {}"))
        // every lane wrapper registers its own re-run with the SAME attemptId before its liveBuy
        assertTrue(ex.contains("TicketRetry8327.registerResume8330(layerTag, preflight.attemptId, ts.mint) { blueChipBuy(ts, sizeSol, walletSol, takeProfitPct, stopLossPct, wallet, isPaper, layerTag, layerTagEmoji, true, preflight.attemptId) }"))
        assertTrue(ex.contains("TicketRetry8327.registerResume8330(executionLane, preflight.attemptId, ts.mint) { shitCoinBuy(ts, sizeSol, walletSol, takeProfitPct, stopLossPct, wallet, isPaper, launchPlatform, riskLevel, true, preflight.attemptId, entryScore, entryConfidence, executionLane) }"))
        assertTrue(ex.contains("TicketRetry8327.registerResume8330(\"MOONSHOT\", preflight.attemptId, ts.mint) { moonshotBuy(ts, sizeSol, walletSol, wallet, isPaper, score, spaceModeEmoji, spaceModeName, true, preflight.attemptId) }"))
        // the 8327 hold / budget path is unchanged: a resume that does not open re-enters it
        assertTrue(ex.contains("TicketRetry8327.retryOrTerminal8327(normalizeExecutionLane(layerTag), attemptId, reason) ?: return"))
        assertTrue(src("engine/TicketRetry8327.kt").contains("arm8330(attemptId, holds)"))
        assertTrue(src("engine/TicketExec8304.kt").contains("?.plus(TicketCause8330.suffix8330(lane8304(lane)))"))
    }

    // ── 2. LIVE_BUY_NOT_OPENED cause split ──

    @Test
    fun notOpenedIsSplitIntoHeldVanishedRefusedUnnamed() {
        val w = TicketCause8330.WRAPPER_8330
        assertEquals("ALREADY_HELD_8330", TicketCause8330.HELD_8330); assertEquals("TICKET_VANISHED_8330", TicketCause8330.VANISHED_8330)
        assertTrue(TicketCause8330.innerIsDuplicate8330("BUY_TERMINAL_DUPLICATE_SUPPRESSED:MULTILAYER_TRUE_DUPLICATE_OPEN"))
        assertTrue(TicketCause8330.innerIsDuplicate8330("SIGNED_TRANSACTION_PENDING"))
        assertFalse(TicketCause8330.innerIsDuplicate8330("FLAT_CHART_8027")); assertFalse(TicketCause8330.innerIsDuplicate8330(null))
        assertEquals("$w:${TicketCause8330.HELD_8330}", TicketCause8330.refine8330(w, null, mintHeld = true, ticketLive = true))
        assertEquals("$w:ALREADY_HELD_8330:MULTILAYER_TRUE_DUPLICATE_OPEN", TicketCause8330.refine8330(w, "MULTILAYER_TRUE_DUPLICATE_OPEN", mintHeld = false, ticketLive = true))
        assertEquals("$w:${TicketCause8330.VANISHED_8330}", TicketCause8330.refine8330(w, null, mintHeld = false, ticketLive = false))
        assertEquals("$w:UNNAMED_8330", TicketCause8330.refine8330(w, null, mintHeld = false, ticketLive = true))
        assertEquals("$w:FLAT_CHART_8027", TicketCause8330.refine8330(w, "FLAT_CHART_8027", mintHeld = false, ticketLive = true))
        // a transient inner stop keeps its 8327 shape, so the hold path still parses it
        assertEquals("MUTEX_BUSY_DEFERRED", TicketRetry8327.transientCause8327(TicketCause8330.refine8330(w, "MUTEX_BUSY_DEFERRED", false, true)))
        // other wrappers pass through untouched
        assertEquals("BUY_NOT_OPENED_7948", TicketCause8330.refine8330("BUY_NOT_OPENED_7948", null, true, false))
        assertEquals("held", TicketCause8330.classOf8330("$w:ALREADY_HELD_8330"))
        assertEquals("held", TicketCause8330.classOf8330("$w:ALREADY_HELD_8330:MULTILAYER_TRUE_DUPLICATE_OPEN"))
        assertEquals("vanished", TicketCause8330.classOf8330("$w:TICKET_VANISHED_8330"))
        assertEquals("unnamed", TicketCause8330.classOf8330("$w:UNNAMED_8330"))
        assertEquals("unnamed", TicketCause8330.classOf8330(w))
        assertEquals("refused", TicketCause8330.classOf8330("$w:TOKEN_MAP_INCOMPLETE"))
        assertNull(TicketCause8330.classOf8330("BUY_NOT_OPENED_7948"))
        assertEquals("", TicketCause8330.formatSuffix8330(0, 0, 0, 0))
        assertEquals(" notOpened8330[held=2 vanished=1 refused=3 unnamed=0]", TicketCause8330.formatSuffix8330(2, 1, 3, 0))
        TicketCause8330.resetForTests8330()
        TicketCause8330.onDrop8330("MOONSHOT", "$w:ALREADY_HELD_8330")
        TicketCause8330.onDrop8330("MOONSHOT", "$w:TICKET_VANISHED_8330")
        TicketCause8330.onDrop8330("MOONSHOT", "RESEALED_TO_PRIMARY_CORE_7819")
        assertEquals(" notOpened8330[held=1 vanished=1 refused=0 unnamed=0]", TicketCause8330.suffix8330("MOONSHOT"))
        assertEquals("", TicketCause8330.suffix8330("EXPRESS"))
        TicketCause8330.resetForTests8330()
        assertTrue(src("engine/Executor.kt").contains("@Suppress(\"NAME_SHADOWING\") val reason = TicketCause8330.refineLive8330(attemptId, reason)"))
        assertTrue(src("engine/TicketExec8304.kt").contains("TicketCause8330.onDrop8330(l, reason)"))
    }

    // ── 3. hand-off resume on evidence arrival ──

    @Test
    fun handoffResumeKeepsTaskIdentityVersionAndEvidence() {
        val t0 = 10_000_000L
        val task = Task8153("MintH", "QUALITY", "MOONSHOT", "MCAP_TOO_LOW", 77L, t0, t0 + HandoffReceiverTasks8153.TASK_TTL_MS_8153, t0 + 5,
            lastBlock = "DEFERRED:DEFERRED_NO_TOKEN_STATE")
        assertTrue(HandoffReceiverTasks8153.awaitsState8330(task))
        assertFalse(HandoffReceiverTasks8153.awaitsState8330(task.copy(lastBlock = "DEFERRED:PRICE_UNKNOWN")))
        assertFalse(HandoffReceiverTasks8153.awaitsState8330(task.copy(lastBlock = "")))
        val now = t0 + 50_000L   // past the first validity
        val r = HandoffReceiverTasks8153.resumeOnState8330(task, true, now)!!
        assertEquals("MintH", r.mint); assertEquals("QUALITY", r.lane); assertEquals("MOONSHOT", r.from)
        assertEquals("MCAP_TOO_LOW", r.reason); assertEquals(77L, r.version); assertEquals(t0, r.createdAtMs); assertEquals(t0 + 5, r.refreshedAtMs)
        assertEquals("", r.lastBlock)
        assertTrue(r.pending)
        assertEquals(now + HandoffReceiverTasks8153.TASK_TTL_MS_8153, r.validUntilMs)
        // never past the max age from creation
        val late = HandoffReceiverTasks8153.resumeOnState8330(task, true, t0 + HandoffReceiverTasks8153.TASK_MAX_AGE_MS_8153 - 1_000L)!!
        assertEquals(t0 + HandoffReceiverTasks8153.TASK_MAX_AGE_MS_8153, late.validUntilMs)
        assertNull(HandoffReceiverTasks8153.resumeOnState8330(task, true, t0 + HandoffReceiverTasks8153.TASK_MAX_AGE_MS_8153))
        assertNull(HandoffReceiverTasks8153.resumeOnState8330(task, false, now))
        assertNull(HandoffReceiverTasks8153.resumeOnState8330(null, true, now))
        assertNull(HandoffReceiverTasks8153.resumeOnState8330(task.copy(outcome = Outcome8153.REFUSED), true, now))
        // without the state's arrival the expiry terminal is unchanged (DEFERRED_DATA by name)
        HandoffReceiverTasks8153.resetForTests8153()
        val e = HandoffReceiverTasks8153.expire8153(task, now)
        assertEquals(Outcome8153.DEFERRED_DATA, e.outcome); assertEquals("DEFERRED_NO_TOKEN_STATE", e.verdict)
        assertSame(task, HandoffReceiverTasks8153.expire8153(task, t0 + 1))
    }

    @Test
    fun handoffRuntimeResumesTheSameTaskWhenTheStateArrives() {
        HandoffReceiverTasks8153.resetForTests8153()
        val t0 = 20_000_000L
        val m = "H".repeat(44)
        val opened = HandoffReceiverTasks8153.openTask8153(m, "QUALITY", "MOONSHOT", "MCAP_TOO_LOW", 9L, t0, t0)!!
        HandoffReceiverTasks8153.blockTask8153(m, "QUALITY", Block8153.DATA, "DEFERRED_NO_TOKEN_STATE", t0 + 1)
        assertTrue(HandoffReceiverTasks8153.awaitsState8330(HandoffReceiverTasks8153.taskOf8153(m, t0 + 2)!!))
        // the state arrives inside validity: the same task continues, block cleared, validity extended
        HandoffReceiverTasks8153.onStateInstalled8330(m, t0 + 30_000L)
        val r = HandoffReceiverTasks8153.taskOf8153(m, t0 + 30_001L)!!
        assertTrue(r.pending); assertEquals("", r.lastBlock); assertEquals(9L, r.version); assertEquals("QUALITY", r.lane)
        assertEquals(opened.createdAtMs, r.createdAtMs)
        assertEquals(t0 + 30_000L + HandoffReceiverTasks8153.TASK_TTL_MS_8153, r.validUntilMs)
        assertTrue(HandoffReceiverTasks8153.isDue8153(m, "QUALITY", t0 + 60_000L))
        assertTrue(HandoffReceiverTasks8153.resumeLine8330().contains("handoffResumed8330=1 expiredAfterState=0"))
        // idempotent: a second arrival of the same state changes nothing
        HandoffReceiverTasks8153.onStateInstalled8330(m, t0 + 31_000L)
        assertEquals(r.validUntilMs, HandoffReceiverTasks8153.taskOf8153(m, t0 + 31_001L)!!.validUntilMs)
        assertTrue(HandoffReceiverTasks8153.resumeLine8330().contains("handoffResumed8330=1 "))
        // a task deferred again and read only AFTER its validity: the expiry asks whether the state arrived before terminalising
        HandoffReceiverTasks8153.blockTask8153(m, "QUALITY", Block8153.DATA, "DEFERRED_NO_TOKEN_STATE", t0 + 32_000L)
        val afterExpiry = HandoffReceiverTasks8153.taskOf8153(m, t0 + 100_000L)!!   // state was seen at t0+30s: resumed, not DEFERRED_DATA
        assertTrue(afterExpiry.pending); assertEquals("", afterExpiry.lastBlock)
        assertTrue(HandoffReceiverTasks8153.resumeLine8330().contains("expiredAfterState=1"))
        HandoffReceiverTasks8153.resetForTests8153()
        // the arrival hooks
        assertTrue(src("engine/RemainingGaps8185.kt").contains("if (ok) try { HandoffReceiverTasks8153.onStateInstalled8330(key, nowMs) } catch (_: Throwable) {}"))
        assertTrue(src("engine/HandoffThroughput8190.kt").contains("if (tokenFor(m)) { set.add(m); joined++; try { HandoffReceiverTasks8153.onStateInstalled8330(m, nowMs) } catch (_: Throwable) {} } else rehydrate(m)"))
        assertTrue(src("engine/HandoffReceiverTasks8153.kt").contains("verdictLine8169() + \"]\" + resumeLine8330()"))
    }

    // ── 4. ownership: every refusal between QUALIFIED and OWNER_SELECTED by name ──

    @Test
    fun ownerWhyCountsEveryPreIntentRefusalPerLane() {
        SpecialistOwnership7951.resetForTests7951()
        assertNull(SpecialistOwnership7951.ownerWhyLine8330("QUALITY"))
        assertNull(SpecialistOwnership7951.formatOwnerWhy8330("QUALITY", emptyMap()))
        for (i in 0 until 5) SpecialistOwnership7951.notePreIntent7951("QUALITY", "READY_NOT_EVALUATED_PRIMARY_MOONSHOT")
        for (i in 0 until 3) SpecialistOwnership7951.notePreIntent7951("QUALITY", "FDG_LIVE_PROVEN_SETUP_ONLY_8059")
        SpecialistOwnership7951.notePreIntent7951("QUALITY", "CAPITAL_ONLY_CAPITAL_BELOW_MIN_EXECUTABLE_6490")
        SpecialistOwnership7951.notePreIntent7951("BLUECHIP", "FDG_BLUECHIP_MCAP_BELOW_MANDATE")
        val q = SpecialistOwnership7951.ownerWhyCounts8330("QUALITY")
        assertEquals(5L, q["READY_NOT_EVALUATED_PRIMARY_MOONSHOT"]); assertEquals(3L, q["FDG_LIVE_PROVEN_SETUP_ONLY_8059"]); assertEquals(1L, q["CAPITAL_ONLY_CAPITAL_BELOW_MIN_EXECUTABLE_6490"])
        assertEquals("QUALITY ownerWhy8330[READY_NOT_EVALUATED_PRIMARY_MOONSHOT=5,FDG_LIVE_PROVEN_SETUP_ONLY_8059=3,CAPITAL_ONLY_CAPITAL_BELOW_MIN_EXECUTABLE_6490=1] total=9",
            SpecialistOwnership7951.ownerWhyLine8330("QUALITY"))
        assertEquals("BLUECHIP ownerWhy8330[FDG_BLUECHIP_MCAP_BELOW_MANDATE=1] total=1", SpecialistOwnership7951.ownerWhyLine8330("BLUECHIP"))
        assertNull(SpecialistOwnership7951.ownerWhyLine8330("CYCLIC"))
        // the whole book, not the top four
        for (i in 0 until 12) SpecialistOwnership7951.notePreIntent7951("TREASURY", "R_$i")
        val line = SpecialistOwnership7951.ownerWhyLine8330("TREASURY")!!
        assertTrue(line.endsWith("total=12"))
        assertEquals(10, line.substringAfter('[').substringBefore(']').split(',').size)
        SpecialistOwnership7951.resetForTests7951()
        assertTrue(src("engine/ToolkitSignalSheet.kt").contains("SpecialistOwnership7951.ownerWhyLine8330(lane)?.let { appendLine(it) }"))
    }
}
